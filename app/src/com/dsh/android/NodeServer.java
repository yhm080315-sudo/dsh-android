package com.dsh.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.GZIPInputStream;

/**
 * Owns the bundled Node process that runs the harness, and the runtime extraction
 * that has to happen before it can start.
 *
 * Everything lives under filesDir/runtime so the binaries are exec'd from the
 * app's private data directory — the same place Termux runs from.
 */
public class NodeServer extends Service {

    private static final String TAG = "DSH";
    private static final String CHANNEL_ID = "dsh-runner";
    private static final int NOTIFICATION_ID = 1001;

    public interface Listener {
        void onLog(String line);

        void onReady(String url, String token);

        void onExit(int code);
    }

    public static final String ACTION_START = "com.dsh.android.START";
    public static final String ACTION_STOP = "com.dsh.android.STOP";

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<Listener>();
    private static volatile String sUrl;
    private static volatile String sToken;
    private static volatile boolean sRunning;
    private static volatile boolean sPreparing;

    private Process process;
    private Thread pump;
    private Thread worker;
    private int port = 3220;
    private int restarts;
    private static final int MAX_RESTARTS = 3;
    private static final String[] ASSET_PROBES = {"probes/tsys.bin"};

    public static boolean isRunning() {
        return sRunning;
    }

    public static String currentUrl() {
        return sUrl;
    }

    public static void addListener(Listener l) {
        LISTENERS.add(l);
    }

    public static void removeListener(Listener l) {
        LISTENERS.remove(l);
    }

    private static void broadcastLog(String line) {
        for (Listener l : LISTENERS) {
            l.onLog(line);
        }
    }

    /** Fan a line out to listeners and to the on-disk log the diagnostics page reads. */
    private void logLine(String line) {
        DiagnosticServer.appendLog(getFilesDir(), line);
        Log.i(TAG, line);
        broadcastLog(line);
    }

    private DiagnosticServer diagnostics;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        diagnostics = new DiagnosticServer(getFilesDir());
        File ext = getExternalFilesDir(null);
        if (ext != null) {
            // readable without the app running: /sdcard/Android/data/<pkg>/files/
            DiagnosticServer.setExternalLog(new File(ext, "dsh.log"));
        }
        diagnostics.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopEverything();
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, buildNotification("Starting runtime..."));
        if (worker == null || !worker.isAlive()) {
            worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        prepare();
                        launch();
                    } catch (Throwable t) {
                        Log.e(TAG, "startup failed", t);
                        logLine("STARTUP FAILED: " + t);
                        postNotification("dsh failed to start", String.valueOf(t.getMessage()));
                        stopSelf();
                    }
                }
            }, "dsh-start");
            worker.start();
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopEverything();
        super.onDestroy();
    }

    private void stopEverything() {
        if (process != null) {
            process.destroy();
            process = null;
        }
        sRunning = false;
    }

    // ---------------------------------------------------------------- runtime

    private File runtimeDir() {
        return new File(getFilesDir(), "runtime");
    }

    private File prefixDir() {
        return new File(runtimeDir(), "usr");
    }

    private File binDir() {
        return new File(prefixDir(), "bin");
    }

    private File libDir() {
        return new File(prefixDir(), "lib");
    }

    private File dshEntry() {
        return new File(runtimeDir(), "dsh/node_modules/@deepseek-ai/dsh/lib/bin.js");
    }

    private File marker() {
        return new File(runtimeDir(), ".extracted");
    }

    /**
     * Install plugin tarballs left in shared storage.
     *
     * The phone has no npm, so a plugin can only arrive as a tarball. Dropping
     * one in /sdcard/DSH/plugins installs it into the profile, where it survives
     * app updates -- previous installs went into the runtime and were lost on
     * every upgrade.
     */
    private void installSideLoadedPlugins(File web) {
        File modules = new File(web, "node_modules");
        File inbox = new File("/sdcard/DSH/plugins");
        File[] tarballs = inbox.listFiles();
        if (tarballs == null || tarballs.length == 0) {
            return;
        }
        for (File tarball : tarballs) {
            if (!tarball.getName().endsWith(".tgz")) {
                continue;
            }
            File staging = new File(getCacheDir(), "plugin-" + System.currentTimeMillis());
            try {
                InputStream raw = new java.io.FileInputStream(tarball);
                TarExtractor.Counting in = new TarExtractor.Counting(new GZIPInputStream(raw, 1 << 16));
                final int[] count = {0};
                TarExtractor.extract(in, staging, new TarExtractor.Progress() {
                    @Override
                    public void onEntry(String name) {
                        count[0]++;
                    }
                });
                in.close();
                // npm tarballs wrap everything in a top-level "package/" directory
                File root = new File(staging, "package");
                if (!root.isDirectory()) {
                    root = staging;
                }
                File manifest = new File(root, "package.json");
                String name = readPackageName(manifest);
                if (name == null) {
                    logLine("plugin " + tarball.getName() + ": no package name, skipped");
                    continue;
                }
                File target = new File(modules, name);
                if (target.exists()) {
                    logLine("plugin " + name + " already installed");
                    continue;
                }
                Process p = new ProcessBuilder("/system/bin/cp", "-a",
                        root.getAbsolutePath(), target.getAbsolutePath())
                        .redirectErrorStream(true).start();
                int rc = waitForExit(p, 120000);
                logLine("installed plugin " + name + " from " + tarball.getName()
                        + " (" + count[0] + " entries, rc=" + rc + ")");
            } catch (Throwable t) {
                logLine("plugin " + tarball.getName() + " failed: " + t);
            } finally {
                deleteRecursive(staging);
            }
        }
    }

    /** The "name" field of a package.json, or null when it cannot be read. */
    private String readPackageName(File manifest) {
        try {
            if (!manifest.isFile()) {
                return null;
            }
            byte[] buf = new byte[(int) Math.min(manifest.length(), 1 << 16)];
            java.io.FileInputStream in = new java.io.FileInputStream(manifest);
            int n = in.read(buf);
            in.close();
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"name\"\\s*:\\s*\"([^\"]+)\"")
                    .matcher(new String(buf, 0, Math.max(0, n), "UTF-8"));
            return m.find() ? m.group(1) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Length of the bundled payload, used to tell one archive from another. */
    private long assetLength() {
        try {
            return getAssets().openFd("runtime.tar.gz").getLength();
        } catch (IOException e) {
            return -1;
        }
    }

    private File stateFile() {
        return new File(runtimeDir(), ".extract-state");
    }

    private void prepare() throws IOException {
        sPreparing = true;
        File rt = runtimeDir();
        String want = BuildConfig.RUNTIME_STAMP;
        String have = marker().isFile() ? readFile(marker()) : "";
        // Always first: the harness must never read a home that predates the split.
        migrateLegacyHome();
        if (want.equals(have) && dshEntry().isFile()) {
            logLine("runtime already extracted");
            sPreparing = false;
            return;
        }

        if (rt.exists()) {
            // Clear leftovers from a previous archive layout. (extracted-runtime/)
            File legacy = new File(rt, "runtime");
            if (legacy.isDirectory()) {
                logLine("removing stale nested runtime/ from an older build");
                deleteRecursive(legacy);
            }
        }

        /*
         * Extraction always starts from an empty tree.
         *
         * Resuming looked like free time saved on a 600 MB unpack, but a
         * checkpoint is only meaningful for the exact archive that wrote it, and
         * an interrupted run leaves a tree this app may not be able to overwrite
         * -- root-owned leftovers stop the extractor with EACCES on the same file
         * every time. Parking the tree (a rename, which always succeeds) and
         * unpacking from scratch takes seconds and cannot inherit that state.
         */
        logLine("extracting runtime from an empty tree (~600 MB, one time)");
        wipeRuntimeTree(rt);
        long assetBytes = assetLength();
        stateFile().delete();

        if (!rt.mkdirs() && !rt.isDirectory()) {
            throw new IOException("cannot create " + rt);
        }


        File probeDir = new File(rt.getParentFile() == null ? rt : rt, "probe");
        if (!probeDir.mkdirs() && !probeDir.isDirectory()) {
            logLine("could not create " + probeDir);
        }
        for (String asset : ASSET_PROBES) {
            try {
                InputStream in = getAssets().open(asset);
                File out = new File(probeDir, asset.substring(asset.lastIndexOf('/') + 1));
                java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) {
                    fos.write(buf, 0, n);
                }
                fos.close();
                in.close();
                out.setReadable(true, false);
                out.setExecutable(true, false);
            } catch (IOException e) {
                logLine("probe asset " + asset + " failed: " + e);
            }
        }
        logLine("shape probes extracted");

        InputStream raw = getAssets().open("runtime.tar.gz");
        TarExtractor.Counting counting =
                new TarExtractor.Counting(new GZIPInputStream(raw, 1 << 16));
        long start = System.currentTimeMillis();
        final int[] count = {0};
        final long[] lastCheckpoint = {0};
        final int[] lastEntries = {0};
        try {
            TarExtractor.extract(counting, rt, new TarExtractor.Progress() {
                @Override
                public void onEntry(String name) {
                    count[0]++;
                    if (count[0] % 2000 == 0) {
                        postNotification("Extracting runtime", count[0] + " entries");
                    }
                }
            }, new TarExtractor.Checkpoint() {
                @Override
                public void onCheckpoint(int entries, long offset) {
                    lastCheckpoint[0] = offset;
                    lastEntries[0] = entries;
                    // No checkpoint: the next launch starts from an empty tree
                    // rather than trusting an offset from a previous attempt.
                }
            }, 0);
        } catch (IOException failure) {
            diagnoseExtractionFailure(failure);
            throw failure;
        } finally {
            counting.close();
        }
        long ms = System.currentTimeMillis() - start;
        File nodeCheck = new File(binDir(), "node");
        logLine("extracted " + count[0] + " entries in " + (ms / 1000) + "s");
        logLine("node: exists=" + nodeCheck.isFile()
                + " bytes=" + nodeCheck.length()
                + " exec=" + nodeCheck.canExecute());

        for (File f : new File[]{binDir(), libDir()}) {
            makeExecutable(f);
        }
        installBusyboxApplets();
        writeFile(marker(), want);
        stateFile().delete();
        sPreparing = false;
    }

    /**
     * Link busybox's applets (sh, ls, grep, sed, find, ...) into usr/bin.
     *
     * busybox refuses to derive its applet list when argv[0] is not absolute, so
     * this has to run here, with the final path, rather than at packaging time.
     * Without it the harness still boots; it just loses its shell toolchain.
     */
    /** Environment every child of the app needs: bundled runtime + bundled TLS. */
    static void applyRuntimeEnv(Map<String, String> env, File prefix, File bin, File home, File tmp) {
        env.clear();
        env.put("PATH", bin.getAbsolutePath() + ":/system/bin:/system/xbin");
        env.put("HOME", home.getAbsolutePath());
        env.put("TMPDIR", tmp.getAbsolutePath());
        env.put("PREFIX", prefix.getAbsolutePath());
        // Keep the harness's state inside the app's own tree rather than any
        // ambient /root/.dsh the platform might expose.
        env.put("DSH_HOME", new File(home, ".dsh").getAbsolutePath());
        /*
         * Confinement is what the harness sandboxes *its own* child processes with
         * (bwrap, then a Landlock launcher). Neither exists for Android, and the
         * sandbox service fails closed when its chain is empty -- every shell tool
         * call would be refused. The plugin reads this variable for its default
         * mode, so full access is the only setting under which an agent turn can
         * run here; Android's own app sandbox is the boundary that remains.
         */
        env.put("DSH_PERMISSION_MODE", "danger-full-access");
        /*
         * The device bridge is token-authenticated (loopback is not isolation on
         * Android), so the agent needs the token to call it. Export it and drop a
         * copy in the harness home -- anything the agent runs can read it there.
         */
        String deviceToken = DeviceServer.token();
        if (deviceToken != null && !deviceToken.isEmpty()) {
            env.put("DSH_DEVICE_TOKEN", deviceToken);
            try {
                File tokenFile = new File(new File(home, ".dsh"), ".device-token");
                java.io.FileOutputStream out = new java.io.FileOutputStream(tokenFile);
                out.write(deviceToken.getBytes("UTF-8"));
                out.close();
                Process p = new ProcessBuilder("/system/bin/chmod", "600",
                        tokenFile.getAbsolutePath()).redirectErrorStream(true).start();
                p.waitFor();
            } catch (Throwable t) {
                // The token is still in the environment; the file is a convenience.
            }
        }
        env.put("LD_LIBRARY_PATH", new File(prefix, "lib").getAbsolutePath());
        env.put("LANG", "C.UTF-8");
        env.put("TERM", "xterm-256color");
        env.put("SHELL", new File(bin, "bash").getAbsolutePath());
        // Termux's runtime has /data/data/com.termux/... compiled in; the bundled
        // TLS config and CA store must be pointed at explicitly or OpenSSL aborts.
        File tls = new File(prefix, "etc/tls");
        env.put("OPENSSL_CONF", new File(tls, "openssl.cnf").getAbsolutePath());
        env.put("SSL_CERT_FILE", new File(tls, "cert.pem").getAbsolutePath());
        env.put("SSL_CERT_DIR", tls.getAbsolutePath());
    }

    private void installBusyboxApplets() {
        File busybox = new File(binDir(), "busybox");
        if (!busybox.isFile()) {
            logLine("no busybox: shell toolchain will be limited to the bundled binaries");
            return;
        }
        try {
            ProcessBuilder busyboxCmd = new ProcessBuilder(busybox.getAbsolutePath(),
                    "--install", "-s", binDir().getAbsolutePath());
            /*
             * busybox here is a dynamically linked bionic build: it needs
             * libbusybox.so.1.38.0 from the payload's usr/lib. This Java process
             * has no LD_LIBRARY_PATH of its own, so without setting it on the child
             * the link fails and NOT ONE applet gets installed (rc=1, and the only
             * trace is a single CANNOT LINK line). A shell has the variable, which
             * is why testing from a shell never shows this.
             */
            try {
                String libDir = new File(runtimeDir(), "usr/lib").getAbsolutePath();
                busyboxCmd.environment().put("LD_LIBRARY_PATH", libDir);
                // Say it out loud: without this the child cannot link libbusybox.so and
                // zero applets get installed, while the only trace is one CANNOT LINK line.
                logLine("busybox applets: LD_LIBRARY_PATH=" + libDir);
            } catch (Throwable t) {
                logLine("busybox applets: could not set LD_LIBRARY_PATH: " + t);
            }
            Process p = busyboxCmd.redirectErrorStream(true).start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
            String line;
            int lines = 0;
            while ((line = r.readLine()) != null && lines < 40) {
                logLine("busybox: " + line);
                lines++;
            }
            r.close();
            int rc = waitForExit(p, 30000);
            // a symlink named bash keeps spawns that ask for bash working
            File sh = new File(binDir(), "sh");
            File bash = new File(binDir(), "bash");
            if (!bash.isFile() && sh.exists() && !sh.getCanonicalPath().equals(bash.getPath())) {
                // busybox has no bash applet; a shell that merely behaves like one
                // is still better than a missing binary for non-interactive use.
            }
            logLine("busybox applets installed (rc=" + rc + ")");
        } catch (Throwable t) {
            logLine("busybox applet install failed: " + t);
        }
    }

    /**
     * Give the profile a node_modules view of the bundled packages.
     *
     * The client plugin scan resolves each plugin's manifest relative to the
     * profile directory. A freshly created profile has an empty node_modules, so
     * every lookup fails and the browser is served an empty plugin graph -- the
     * page then dies with "HTML did not preload
     * @deepseek-ai/dsh-client-modules/client.js". One symlink to the runtime's
     * node_modules fixes the whole graph (65 entries instead of 0).
     */
    private void prepareProfile() {
        File web = new File(new File(dshHomeDir(), "profiles"), "web");
        if (!web.mkdirs() && !web.isDirectory()) {
            logLine("profile dir unavailable: " + web);
            return;
        }
        /*
         * The profile gets a real node_modules directory, filled with symlinks to
         * the harness's own packages.
         *
         * It used to be a single symlink to runtime/dsh/node_modules, which meant
         * every plugin installed for the profile landed inside the runtime -- and
         * the runtime is replaced wholesale whenever the app is updated, taking
         * those plugins with it. A real directory lives in the harness home
         * (files/home), which no update touches, so installs survive; the symlinks
         * to the bundled packages are re-created here on every start, and entries
         * that are not ours are left exactly as they are.
         */
        File modules = new File(web, "node_modules");
        File bundled = new File(runtimeDir(), "dsh/node_modules");
        try {
            boolean legacyLink = java.nio.file.Files.isSymbolicLink(modules.toPath());
            if (legacyLink) {
                logLine("profile node_modules was a symlink; converting it to a real directory");
                java.nio.file.Files.delete(modules.toPath());
            }
        } catch (Throwable t) {
            logLine("profile node_modules check failed: " + t);
        }
        if (!modules.mkdirs() && !modules.isDirectory()) {
            logLine("profile node_modules unavailable: " + modules);
        } else {
            File[] entries = bundled.listFiles();
            int linked = 0;
            if (entries != null) {
                for (File entry : entries) {
                    File view = new File(modules, entry.getName());
                    if (view.exists()) {
                        continue;
                    }
                    try {
                        Process p = new ProcessBuilder("/system/bin/ln", "-s",
                                entry.getAbsolutePath(), view.getAbsolutePath())
                                .redirectErrorStream(true).start();
                        if (waitForExit(p, 10000) == 0) {
                            linked++;
                        }
                    } catch (Throwable t) {
                        logLine("profile link failed for " + entry.getName() + ": " + t);
                    }
                }
            }
            logLine("profile node_modules: " + linked + " bundled packages linked, "
                    + modules.listFiles().length + " entries total");
        }

        installSideLoadedPlugins(web);

        ensurePluginRows(web);

        /*
         * The credentials provider refuses to start unless its store is owner-only.
         * Android hands files a group-readable mode, so the harness dies with
         * "is readable beyond its owner (mode 660)". Seed the file and pin 600.
         */
        File creds = new File(dshHomeDir(), ".credentials.yaml");
        try {
            if (!creds.isFile()) {
                java.io.FileOutputStream fos = new java.io.FileOutputStream(creds);
                fos.write("{}\n".getBytes("UTF-8"));
                fos.close();
            }
            Process p = new ProcessBuilder("/system/bin/chmod", "600", creds.getAbsolutePath())
                    .redirectErrorStream(true)
                    .start();
            int rc = waitForExit(p, 10000);
            logLine("credentials mode rc=" + rc);
        } catch (Throwable t) {
            logLine("credentials seed failed: " + t);
        }
    }

    /**
     * User state (credentials, sessions, profile) lives beside the runtime, never
     * inside it: the runtime tree is wiped and re-extracted whenever the bundled
     * archive changes, and that must not take the user's data with it.
     */
    private File homeDir() {
        return new File(getFilesDir(), "home");
    }

    /**
     * Client-side plugin rows the app adds to the profile.
     *
     * All of them are browser-side presentation: the portrait layout fix and the
     * two whale-themed skins the user runs on the desktop client. Each is a bundle
     * whose own patch inserts exactly one loader row, so inserting the same row
     * here is equivalent -- and works without the bundle list, which the harness
     * writes itself.
     */
    /** Profile rows the app turns off: HMR cannot run without --expose-internals. */
    private static final String[] DISABLED_ROWS = {"hmr"};

    private static final String[][] CLIENT_ROWS = {
            {"dsh-web-mobile", "dsh-web-mobile"},
            {"ui-skin-whale-fantasy-18", "dsh-skin-whale-fantasy"},
            {"dsh-boot-animation-2", "dsh-boot-animation"},
            {"dsh-pro-mode-splash-3", "dsh-pro-mode-splash"},
    };

    private void ensurePluginRows(File web) {
        File patch = new File(web, "cordis.patch.yml");
        File modules = new File(web, "node_modules");
        try {
            String existing = "";
            if (patch.isFile()) {
                byte[] buf = new byte[(int) Math.min(patch.length(), 1 << 20)];
                java.io.FileInputStream in = new java.io.FileInputStream(patch);
                int n = in.read(buf);
                in.close();
                existing = new String(buf, 0, Math.max(0, n), "UTF-8");
            }

            StringBuilder rows = new StringBuilder();
            for (String[] row : CLIENT_ROWS) {
                if (existing.contains(row[1])) {
                    continue;
                }
                if (!new File(modules, row[1]).isDirectory()) {
                    logLine("plugin not resolvable from the profile, skipping: " + row[1]);
                    continue;
                }
                rows.append("- insert:\n    - id: ").append(row[0])
                        .append("\n      name: '").append(row[1]).append("'\n");
            }
            if (rows.length() == 0) {
                logLine("profile patch: all plugin rows present");
                return;
            }

            // A patch holding nothing but comments and an empty list must be
            // replaced, not appended to: two documents in one file will not parse.
            boolean emptyList = true;
            for (String line : existing.split("\n")) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#") || t.equals("[]")) {
                    continue;
                }
                emptyList = false;
                break;
            }
            StringBuilder off = new StringBuilder();
            for (String id : DISABLED_ROWS) {
                if (existing.contains("id: " + id)) {
                    continue;
                }
                // HMR needs --expose-internals, which a shipping build should not set.
                // Left on, it fails on every start and adds noise to every bug report.
                off.append("- id: ").append(id).append("\n  disabled: true\n");
            }
            String body = emptyList
                    ? "# Android: client-side presentation rows (added by the app)\n" + rows + off
                    : existing + (existing.endsWith("\n") ? "" : "\n") + rows + off;
            java.io.FileOutputStream out = new java.io.FileOutputStream(patch);
            out.write(body.getBytes("UTF-8"));
            out.close();
            logLine("profile patch: plugin rows written (" + rows.length() + " bytes)");
        } catch (Throwable t) {
            logLine("plugin rows failed: " + t);
        }
    }

    /**
     * Give extraction an empty directory, whatever the old one contains.
     *
     * A plain delete is not enough: files from an earlier run may be owned by
     * root (a payload unpacked through `su`), so the app can neither overwrite
     * nor remove them -- and the extractor dies on the first such file with
     * EACCES. Renaming the tree needs write access only to its *parent*, which
     * the app always owns, so that happens first; removal is then best effort,
     * with root as the escalation.
     */
    private void wipeRuntimeTree(File rt) {
        File parent = rt.getParentFile();
        if (parent != null) {
            File[] old = parent.listFiles();
            if (old != null) {
                for (File f : old) {
                    if (f.getName().startsWith("runtime.stale-")) {
                        removeTree(f);
                    }
                }
            }
        }
        if (!rt.isDirectory()) {
            return;
        }
        if (parent != null) {
            File stale = new File(parent, "runtime.stale-" + System.currentTimeMillis());
            if (rt.renameTo(stale)) {
                logLine("parked the old runtime tree as " + stale.getName());
                removeTree(stale);
                return;
            }
            logLine("could not park the old runtime tree; falling back to delete");
        }
        deleteRecursive(rt);
        if (rt.isDirectory()) {
            removeTree(rt);
        }
    }

    /** Best-effort removal, escalating to root and reporting what survived. */
    private void removeTree(File tree) {
        deleteRecursive(tree);
        if (!tree.exists()) {
            return;
        }
        logLine("removing " + tree.getName() + " as root");
        try {
            Process p = new ProcessBuilder("/system/bin/su", "-c",
                    "rm -rf " + tree.getAbsolutePath()).redirectErrorStream(true).start();
            waitForExit(p, 300000);
        } catch (Throwable t) {
            logLine("root removal failed: " + t);
        }
        if (tree.exists()) {
            logLine("still present after root removal: " + tree.getAbsolutePath());
        }
    }

    /** Report what is in the way when extraction fails. */
    private void diagnoseExtractionFailure(IOException failure) {
        logLine("extraction failed: " + failure.getMessage());
        String path = String.valueOf(failure.getMessage());
        int mark = path.indexOf("/data/");
        int end = path.indexOf(".js:", mark);
        if (mark < 0 || end <= mark) {
            return;
        }
        String probe = path.substring(mark, end + 3);
        try {
            Process p = new ProcessBuilder("/system/bin/su", "-c",
                    "ls -lad " + probe + " " + probe.replace("/index.js", "")
                            + " 2>&1 | head -4").redirectErrorStream(true).start();
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            String line;
            int shown = 0;
            while ((line = r.readLine()) != null && shown < 4) {
                logLine("probe: " + line);
                shown++;
            }
            waitForExit(p, 20000);
        } catch (Throwable t) {
            logLine("probe failed: " + t);
        }
    }

    /** The harness's own home directory (`$HOME/.dsh`). */
    private File dshHomeDir() {
        return new File(homeDir(), ".dsh");
    }

    /** Where the state used to live, so it can be carried over once. */
    private File legacyHomeDir() {
        return new File(runtimeDir(), "home");
    }

    /**
     * Keep the user's credentials and profile config where uninstalling cannot
     * reach them.
     *
     * Android deletes an app's private data on uninstall, so a reinstall means
     * re-entering the API key and losing sessions. Shared storage survives that,
     * and the app already holds all-files access, so the small state files are
     * mirrored there and restored on a fresh install.
     */
    private File stateBackupDir() {
        return new File("/sdcard/DSH/.dsh-state");
    }

    /** State worth surviving a reinstall: small, and painful to recreate. */
    private static final String[] STATE_FILES = {
            ".credentials.yaml",
            "profiles/web/cordis.patch.yml",
            "profiles/web/package.json",
    };

    private void restoreUserState() {
        if (stateMirrorDisabled()) {
            return;
        }
        File backup = stateBackupDir();
        if (!backup.isDirectory()) {
            return;
        }
        for (String rel : STATE_FILES) {
            File src = new File(backup, rel);
            File dst = new File(dshHomeDir(), rel);
            if (!src.isFile() || dst.isFile()) {
                continue;
            }
            try {
                File parent = dst.getParentFile();
                if (parent != null && !parent.mkdirs() && !parent.isDirectory()) {
                    continue;
                }
                Process p = new ProcessBuilder("/system/bin/cp", "-f",
                        src.getAbsolutePath(), dst.getAbsolutePath())
                        .redirectErrorStream(true).start();
                waitForExit(p, 20000);
                logLine("restored user state: " + rel);
            } catch (Throwable t) {
                logLine("restore of " + rel + " failed: " + t);
            }
        }
    }

    /** Opt-out marker: create this file to stop mirroring state to shared storage. */
    private boolean stateMirrorDisabled() {
        return new File(stateBackupDir(), "NO-BACKUP").isFile();
    }

    private void backupUserState() {
        if (stateMirrorDisabled()) {
            return;
        }
        File backup = stateBackupDir();
        if (!backup.mkdirs() && !backup.isDirectory()) {
            return;
        }
        for (String rel : STATE_FILES) {
            File src = new File(dshHomeDir(), rel);
            if (!src.isFile()) {
                logLine("backup: nothing at " + src.getAbsolutePath());
                continue;
            }
            try {
                File dst = new File(backup, rel);
                File parent = dst.getParentFile();
                if (parent != null && !parent.mkdirs() && !parent.isDirectory()) {
                    continue;
                }
                Process p = new ProcessBuilder("/system/bin/cp", "-f",
                        src.getAbsolutePath(), dst.getAbsolutePath())
                        .redirectErrorStream(true).start();
                waitForExit(p, 20000);
            } catch (Throwable t) {
                logLine("backup of " + rel + " failed: " + t);
            }
        }
        // keep the copy owner-only, like the original
        try {
            new ProcessBuilder("/system/bin/chmod", "600",
                    new File(backup, ".credentials.yaml").getAbsolutePath())
                    .redirectErrorStream(true).start();
        } catch (Throwable ignored) {
        }
    }

    /** One line of evidence about the credential store, for remote diagnosis. */
    private void reportCredentialState() {
        File creds = new File(dshHomeDir(), ".credentials.yaml");
        File backup = new File(stateBackupDir(), ".credentials.yaml");
        logLine("credentials: private=" + (creds.isFile() ? creds.length() + "b" : "missing")
                + " backup=" + (backup.isFile() ? backup.length() + "b" : "missing"));
    }

    /**
     * One-shot import of a state bundle left in shared storage.
     *
     * The container-side installation keeps its persona, profile patch and
     * session history under /root/.dsh; this carries them into the app so the
     * same assistant continues here. Runs before the harness starts, so the
     * profile patch is in place the first time the harness reads it.
     */
    private void importStateBundleIfPresent() {
        File marker = new File(homeDir(), ".imported-bundle");
        File bundle = new File(stateBackupDir(), "import.tar.gz");
        if (marker.isFile()) {
            return;
        }
        if (!bundle.isFile()) {
            return;
        }
        logLine("importing state bundle (" + bundle.length() + " bytes) ...");
        InputStream raw = null;
        TarExtractor.Counting in = null;
        final int[] count = {0};
        try {
            raw = new java.io.FileInputStream(bundle);
            in = new TarExtractor.Counting(new GZIPInputStream(raw, 1 << 16));
            TarExtractor.extract(in, homeDir(), new TarExtractor.Progress() {
                @Override
                public void onEntry(String name) {
                    count[0]++;
                }
            });
            logLine("imported " + count[0] + " entries from the bundle");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(marker);
            fos.write("1\n".getBytes("UTF-8"));
            fos.close();
        } catch (Throwable t) {
            logLine("bundle import failed: " + t);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
            if (raw != null) {
                try {
                    raw.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /** Carry a pre-existing home out of the runtime tree before it is wiped. */
    private void migrateLegacyHome() {
        File legacy = legacyHomeDir();
        File current = homeDir();
        if (!legacy.isDirectory() || current.exists()) {
            return;
        }
        try {
            if (current.mkdirs() || current.isDirectory()) {
                Process p = new ProcessBuilder("/system/bin/cp", "-a",
                        legacy.getAbsolutePath() + "/.", current.getAbsolutePath())
                        .redirectErrorStream(true).start();
                int rc = waitForExit(p, 120000);
                logLine("migrated home out of runtime tree rc=" + rc
                        + " -> " + current.getAbsolutePath());
                if (rc == 0) {
                    // The old copy would otherwise shadow the new home on a later boot.
                    deleteRecursive(legacy);
                }
            }
        } catch (Throwable t) {
            logLine("home migration failed: " + t);
        }
    }

    /**
     * Teach the agent about this device.
     *
     * The harness reads AGENTS.md from the workspace, so this is the supported
     * place for environment facts an agent cannot infer: where the workspace is,
     * that shared storage is reachable, and how to obtain root.
     */
    private void writeAgentInstructions(File workspace) {
        File target = new File(workspace, "AGENTS.md");
        String body = ""
                + "# Android environment\n"
                + "\n"
                + "You are running inside the **DeepSeek Harness Android app** (com.dsh.android),\n"
                + "not on a desktop. Everything below is specific to this device.\n"
                + "\n"
                + "## Where you are\n"
                + "\n"
                + "- Workspace (also your working directory): `" + workspace.getAbsolutePath() + "`\n"
                + "- Shared storage is reachable: `/sdcard` is `/storage/emulated/0`.\n"
                + "  `Download`, `Documents`, `DCIM`, `Pictures` and your own workspace are all readable\n"
                + "  and writable. Files you create here are visible in the phone's file manager.\n"
                + "- Prefer `/sdcard` paths for anything the user should be able to see or open.\n"
                + "- Other apps' private data (`/data/data/*`) and system paths need root (see below).\n"
                + "\n"
                + "## Shell\n"
                + "\n"
                + "- `bash` is available. `usr/bin` ships busybox plus standalone coreutils\n"
                + "  (`ls`, `cp`, `mv`, `cat`, `head`, `tail`, `sort`, `wc`, ...) -- but NOT\n"
                + "  `grep`/`sed`/`awk`/`find`/`xargs`/`tar`, which fall through to Android's toybox.\n"
                + "  toybox `grep` has no BRE alternation: `grep 'a\\|b'` returns ZERO matches with\n"
                + "  exit status 0 -- a silent false negative. Use `grep -E`, or `rg`.\n"
                + "- `rg` (ripgrep) is at `usr/bin/rg`.\n"
                + "- `busybox --list` prints every available applet.\n"
                + "- There is no python, no compilers and no package manager on this device.\n"
                + "- Terminals are not interactive: run one-shot commands, never a REPL or an editor.\n"
                + "\n"
                + "## Root\n"
                + "\n"
                + "- The phone is rooted with KernelSU; `/system/bin/su` is on `PATH`.\n"
                + "- Prefix a command with `su -c '<command>'` to run it as root. The first use may\n"
                + "  raise the root manager's prompt on screen, which the user must accept.\n"
                + "- Root is the only way to read another app's `/data/data/<package>` or system\n"
                + "  paths. Ask before doing anything that writes outside `/sdcard`.\n"
                + "\n"
                + "## Notes\n"
                + "\n"
                + "- The user is on a phone screen: keep output short, prefer files over long replies.\n"
                + "\n"
                + "## Device bridge\n"
                + "\n"
                + "- A local HTTP bridge exposes the phone itself: `/device/shot` (screenshot),\n"
                + "  `/device/tap?x=&y=`, `/device/swipe`, `/device/key?name=`, `/device/text?value=`,\n"
                + "  `/device/start?component=`, `/device/top`, `/device/ocr`, `/device/status`.\n"
                + "- It is bound to 127.0.0.1 **and requires a token** (loopback is not isolation on\n"
                + "  Android -- any app can reach it). Pass it as `?token=` or an `X-Device-Token`\n"
                + "  header. The token is in `$DSH_HOME/.device-token` (mode 0600) -- read it there.\n"
                + "  It is NOT in the shell environment: the shell executor discards ambient `DSH_*`\n"
                + "  values and injects only its own registry, so `$DSH_DEVICE_TOKEN` expands to\n"
                + "  nothing. Easiest client is `dshdev` (tools/dshdev.js).\n"
                + "- Most endpoints act through `su`, so they need root to have been granted.\n";
        try {
            if (target.isFile()) {
                byte[] existing = new byte[(int) target.length()];
                java.io.FileInputStream in = new java.io.FileInputStream(target);
                int n = in.read(existing);
                in.close();
                if (n == existing.length && new String(existing, "UTF-8").equals(body)) {
                    return;
                }
            }
            java.io.FileOutputStream out = new java.io.FileOutputStream(target);
            out.write(body.getBytes("UTF-8"));
            out.close();
            logLine("wrote agent instructions: " + target.getAbsolutePath());
        } catch (Throwable t) {
            logLine("agent instructions failed: " + t);
        }
    }

    private static int waitForExit(Process p, long millis) {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            try {
                return p.exitValue();
            } catch (IllegalThreadStateException running) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return -1;
                }
            }
        }
        return -1;
    }

    private static void makeExecutable(File dir) {
        File[] kids = dir.listFiles();
        if (kids == null) {
            return;
        }
        for (File f : kids) {
            if (f.isDirectory()) {
                makeExecutable(f);
            } else {
                f.setExecutable(true, false);
            }
        }
    }

    private void launch() throws IOException {
        File node = new File(binDir(), "node");
        if (!node.isFile()) {
            throw new IOException("node binary missing at " + node);
        }

        port = freePort(3220);
        List<String> cmd = new ArrayList<String>();
        cmd.add(node.getAbsolutePath());
        cmd.add(dshEntry().getAbsolutePath());
        cmd.add("web");
        cmd.add("--port");
        cmd.add(String.valueOf(port));
        cmd.add("--no-open");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        // HOME must be the same directory the state helpers above use. Pointing the
        // harness at the old in-runtime home left it reading a stale credential file
        // whose mode the app never fixed.
        final File home = homeDir();
        File tmp = new File(runtimeDir(), "tmp"); // scratch: safe to discard with the runtime
        if (!home.mkdirs() && !home.isDirectory()) {
            throw new IOException("cannot create home");
        }
        if (!tmp.mkdirs() && !tmp.isDirectory()) {
            throw new IOException("cannot create tmp");
        }
        // Prefer a workspace the user can actually see: /sdcard/DSH once the
        // app holds shared-storage access, otherwise the private home directory.
        File workspace = home;
        File visible = new File("/sdcard/DSH");
        try {
            if (visible.isDirectory() || visible.mkdirs()) {
                workspace = visible;
            }
        } catch (Throwable ignored) {
            // no storage permission yet; the private home stays the workspace
        }
        logLine("workspace: " + workspace.getAbsolutePath());
        // Both layers in one line: a bug report should identify the app build and
        // the embedded harness without anyone having to ask.
        logLine("versions: app " + BuildConfig.APP_VERSION
                + " · dsh " + BuildConfig.DSH_VERSION);
        writeAgentInstructions(workspace);
        pb.directory(workspace);
        Map<String, String> env = pb.environment();
        applyRuntimeEnv(env, prefixDir(), binDir(), home, tmp);
        env.put("NODE_ENV", "production");

        // The profile needs its node_modules view in place before the harness
        // composes its client plugin graph.
        restoreUserState();
        importStateBundleIfPresent();
        prepareProfile();
        logLine("launching: " + dshEntry().getName() + " web --port " + port);
        process = pb.start();

        final InputStream out = process.getInputStream();
        final InputStream err = process.getErrorStream();
        startPump(out, false);
        startPump(err, true);

        sRunning = true;
        backupUserState();
        reportCredentialState();
        postNotification("dsh is running", "port " + port);

        // keep the service alive while the process lives
        new Thread(new Runnable() {
            @Override
            public void run() {
                int code = 0;
                long startedAt = System.currentTimeMillis();
                try {
                    code = process.waitFor();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                sRunning = false;
                long lived = System.currentTimeMillis() - startedAt;
                for (Listener l : LISTENERS) {
                    l.onExit(code);
                }
                // A harness that dies straight away is broken, not unlucky; only
                // retry when it had run for a while, and only a few times.
                if (code != 0 && lived > 10000 && restarts < MAX_RESTARTS) {
                    restarts++;
                    logLine("harness exited (code " + code + ") after " + (lived / 1000)
                            + "s; restart " + restarts + "/" + MAX_RESTARTS);
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        stopSelf();
                        return;
                    }
                    try {
                        launch();
                    } catch (Throwable t) {
                        logLine("restart failed: " + t);
                        stopSelf();
                    }
                    return;
                }
                logLine("harness exited (code " + code + ") after " + (lived / 1000)
                        + "s; not restarting");
                if (lived < 10000) {
                    // Died on takeoff: leave the pieces' verdict in the log without
                    // needing anyone to tap the probe button.
                    try {
                        java.util.List<RuntimeProbe.Step> steps =
                                new RuntimeProbe(runtimeDir()).runAll();
                        for (RuntimeProbe.Step step : steps) {
                            logLine("probe " + step.name + " -> exit=" + step.exit);
                            if (step.stderr.length() > 0) {
                                logLine("probe err: " + step.stderr.trim());
                            }
                            if (step.stdout.length() > 0) {
                                logLine("probe out: " + step.stdout.trim());
                            }
                        }
                    } catch (Throwable t) {
                        logLine("auto probe failed: " + t);
                    }
                }
                stopSelf();
            }
        }, "dsh-wait").start();
    }

    private void startPump(final InputStream stream, final boolean isErr) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                BufferedReader r = null;
                try {
                    r = new BufferedReader(new InputStreamReader(stream, "UTF-8"), 4096);
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (isErr) {
                            Log.w(TAG, line);
                        } else {
                            Log.i(TAG, line);
                        }
                        logLine(line);
                        String url = extractUrl(line);
                        if (url != null) {
                            sUrl = url;
                            for (Listener l : LISTENERS) {
                                l.onReady(url, sToken);
                            }
                        }
                    }
                } catch (IOException ignored) {
                    // process died; the waiter thread reports it
                } finally {
                    if (r != null) {
                        try {
                            r.close();
                        } catch (IOException ignored) {
                        }
                    }
                }
            }
        }, isErr ? "dsh-err" : "dsh-out");
        t.start();
        if (isErr) {
            pump = t;
        }
    }

    /** Pull http://127.0.0.1:PORT/?token=... out of the banner the harness prints. */
    private static String extractUrl(String line) {
        int i = line.indexOf("http://127.0.0.1:");
        if (i < 0) {
            return null;
        }
        int end = i;
        while (end < line.length() && !Character.isWhitespace(line.charAt(end))) {
            end++;
        }
        String url = line.substring(i, end);
        return url.startsWith("http://127.0.0.1:") ? url : null;
    }

    private static int freePort(int start) {
        for (int p = start; p < start + 50; p++) {
            java.net.ServerSocket s = null;
            try {
                s = new java.net.ServerSocket(p);
                return p;
            } catch (IOException inUse) {
                // try the next one
            } finally {
                if (s != null) {
                    try {
                        s.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }
        return start;
    }

    // ------------------------------------------------------------ plumbing

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CHANNEL_ID) != null) {
            return;
        }
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "dsh runtime",
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Keeps the bundled Node runtime alive");
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification(String text) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        Intent open = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, flags);
        return b.setContentTitle("DeepSeek Harness")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }

    /** Named postNotification because Service already inherits Object.notify(). */
    private void postNotification(String title, String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(NOTIFICATION_ID, buildNotification(text));
        Log.i(TAG, title + ": " + text);
    }

    private static String readFile(File f) {
        try {
            FileInputStream in = new FileInputStream(f);
            try {
                byte[] buf = new byte[(int) f.length()];
                int n = in.read(buf);
                return new String(buf, 0, Math.max(0, n), "UTF-8");
            } finally {
                in.close();
            }
        } catch (IOException e) {
            return "";
        }
    }

    private static void writeFile(File f, String content) throws IOException {
        OutputStream out = new FileOutputStream(f);
        try {
            out.write(content.getBytes("UTF-8"));
        } finally {
            out.close();
        }
    }

    static void deleteRecursive(File f) {
        if (!f.exists()) {
            return;
        }
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) {
                    deleteRecursive(k);
                }
            }
        }
        f.delete();
    }
}
