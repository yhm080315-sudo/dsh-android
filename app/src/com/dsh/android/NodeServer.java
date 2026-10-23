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

    /**
     * Set only when the user or the app actually asked for a stop.
     *
     * The exit code cannot answer "should this come back?": the app stops the
     * harness with SIGTERM, which the harness reports as a clean exit 0 -- exactly
     * like a runtime that finished its work. Treating 0 as success is why the
     * console sat on an exited runtime every time a system panel (the share sheet,
     * for one) took the foreground and the service was torn down.
     */
    private volatile boolean stopRequested = false;

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
    private int port = PORT_START;
    private int restarts;
    private static final int MAX_RESTARTS = 3;
    private static final String[] ASSET_PROBES = {"probes/tsys.bin"};

    /**
     * Ports the harness is started on: 3220 upward, the band freePort(PORT_START)
     * hands out first. The band bounds where a harness left by a previous app
     * process can be looked for; a harness found elsewhere is still adopted, but
     * only through the process itself (see runningHarnesses).
     */
    private static final int PORT_START = 3220;
    private static final int PORT_PROBE_SPAN = 10;

    /** A loopback HTTP answer takes milliseconds; these only bound a stuck peer. */
    private static final int PROBE_CONNECT_MS = 500;
    private static final int PROBE_READ_MS = 1500;

    /** What a live harness answers to a request that carries no session token. */
    private static final String HARNESS_UNAUTH = "dsh web authentication required";

    /**
     * An adopted harness is not this app's child: nothing can wait() for it, and
     * this instance is the only thing that knows it should still be there.
     */
    private volatile int adoptedPid = -1;

    /**
     * Set when this service instance is torn down without the app asking for a stop.
     *
     * The harness is left running in that case, so this instance's threads have to
     * fall silent instead of supervising a runtime that now belongs to the next
     * service instance -- a second supervisor would start a second harness.
     */
    private volatile boolean retired = false;

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
            stopRequested = true;
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
        /*
         * A stop the app asked for takes the runtime with it; a teardown it did not
         * ask for does not.
         *
         * A service instance can be destroyed while its harness is mid-task -- the
         * system stopping a background service is the usual way -- and SIGTERMing
         * the harness there interrupts work in flight and leaves the next instance
         * to start a second harness on the same session logs. The harness is left
         * running instead, and the next instance either adopts it (pid unchanged,
         * task intact) or, if it is no longer serving, clears it.
         */
        if (stopRequested || !somethingRunning()) {
            stopEverything();
        } else {
            retired = true;
            logLine("service instance is going away; the harness keeps running for the next one");
        }
        super.onDestroy();
    }

    /**
     * True when this instance still has a harness at work.
     *
     * Either its own child, or one it adopted -- an adopted harness is invisible to
     * `exitValue()`, so it is recognised by its /proc entry, the same way it was found.
     */
    private boolean somethingRunning() {
        if (stillRunning(process)) {
            return true;
        }
        int pid = adoptedPid;
        if (pid <= 0) {
            return false;
        }
        Harness h = new Harness(pid, port);
        h.token = sToken;
        return stillTheHarness(h);
    }

    private void stopEverything() {
        if (process != null) {
            process.destroy();
            process = null;
        }
        stopAdoptedHarness();
        sRunning = false;
    }

    /**
     * Stop a harness this instance adopted rather than started.
     *
     * `process.destroy()` cannot reach it, so without this an explicit stop would
     * leave the runtime serving with nothing left to stop it -- and the next app
     * start would adopt it again as if the stop never happened.
     */
    private void stopAdoptedHarness() {
        int pid = adoptedPid;
        if (pid <= 0) {
            return;
        }
        adoptedPid = -1;
        Harness h = new Harness(pid, port);
        h.token = sToken;
        if (!stillTheHarness(h)) {
            return;
        }
        logLine("stopping the adopted harness (pid " + pid + ") because the app asked for a stop");
        android.os.Process.sendSignal(pid, 15);
        if (!awaitGone(pid, 3000)) {
            android.os.Process.sendSignal(pid, 9);
        }
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

    /**
     * Plugins shipped inside the APK. Their tarballs live in assets/plugins and the
     * app installs them into the profile on every start (see installBundledPlugins),
     * because the device has no package manager: a plugin can only arrive bundled
     * with the app or dropped into /sdcard/DSH/plugins by hand.
     */
    private static final String[][] BUNDLED_ROWS = {
            {"screen-operate", "dsh-screen-operate"},
    };

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
            // Same shape as the client rows below: the profile patch dialect wants the
            // "- insert:" wrapper, and a row whose package is not resolvable from the
            // profile must be skipped rather than written as a broken entry.
            for (String[] row : BUNDLED_ROWS) {
                if (existing.contains(row[1])) {
                    continue;
                }
                if (!new File(modules, row[1]).isDirectory()) {
                    logLine("bundled plugin not resolvable from the profile, skipping: " + row[1]);
                    continue;
                }
                rows.append("- insert:\n    - id: ").append(row[0])
                        .append("\n      name: '").append(row[1]).append("'\n");
            }
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
            /*
             * Compute the rows to switch off BEFORE deciding whether anything needs
             * writing: the four insert rows are present from the first launch onwards,
             * so an early return here would mean this code never runs at all -- which
             * is exactly what happened once, leaving HMR to fail on every start.
             */
            StringBuilder off = new StringBuilder();
            for (String id : DISABLED_ROWS) {
                if (existing.contains("id: " + id)) {
                    continue;
                }
                // HMR needs --expose-internals, which a shipping build should not set.
                off.append("- id: ").append(id).append("\n  disabled: true\n");
            }
            if (rows.length() == 0 && off.length() == 0) {
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
        String language = "zh".equals(java.util.Locale.getDefault().getLanguage())
                ? "# 语言 / Language\n"
                + "\n"
                + "用用户所用的语言回答。本机用户使用中文：除非用户改用别的语言，\n"
                + "**一律用中文**——正文、计划、提问、总结都是中文。\n"
                + "代码、命令、文件路径、标识符、以及引用英文原文时保持原样。\n"
                + "\n"
                + "Reply in the language the user writes in. This device's owner works in\n"
                + "Chinese, so answer in Chinese unless they switch. Keep code, commands,\n"
                + "paths, identifiers and quoted English text as they are.\n"
                + "\n"
                : "# Language\n"
                + "\n"
                + "Reply in the language the user writes in. Default to English unless the user\n"
                + "writes in another language. Use that language for prose, plans, questions and\n"
                + "summaries. Keep code, commands, file paths, identifiers and quoted English\n"
                + "source text as they are.\n"
                + "\n";
        String body = language
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
        /*
         * Two copies, deliberately.
         *
         * The workspace one is what the harness calls a nested instruction file: it is
         * only projected into a session once a file tool has touched a path inside that
         * project. The baseline, which loads before the first request, comes from the
         * session's cwd -- and a new session's cwd is /storage/emulated/0, where there
         * is no AGENTS.md and no project root marker. So a new session used to start
         * with no instructions at all: English answers, no screen tools, guessing at
         * the environment. The user-level copy under $HOME/.dsh is cwd-independent.
         */
        writeInstructions(new File(new File(homeDir(), ".dsh"), "AGENTS.md"), body);
        writeInstructions(new File(workspace, "AGENTS.md"), body);
    }

    /** Write the instructions if they changed; log the path either way. */
    private void writeInstructions(File target, String body) {
        try {
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                logLine("cannot create " + parent.getAbsolutePath());
                return;
            }
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
            logLine("agent instructions failed for " + target.getAbsolutePath() + ": " + t);
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

    // ------------------------------------------------------- the running harness

    /** One of this app's harness processes, as found in /proc. */
    private static final class Harness {
        final int pid;
        final int port;
        /** The session token that opens it, once one has been shown to work. */
        String token;
        /** Why it is being cleared, filled in for the ones that cannot be reused. */
        String why;

        Harness(int pid, int port) {
            this.pid = pid;
            this.port = port;
        }
    }

    /** What the scan before a launch found: the harness to reuse, and the leftovers. */
    private static final class HarnessSweep {
        Harness adopted;
        final List<Harness> stale = new ArrayList<Harness>();
    }

    /** The pieces of an HTTP answer that tell one loopback server from another. */
    private static final class Reply {
        int status = -1;
        String body = "";

        boolean isHarness() {
            return status == 401 && body.contains(HARNESS_UNAUTH);
        }
    }

    /**
     * Every harness of ours that is still running, with the port it claims.
     *
     * Read from /proc rather than trusted from the port band: the `--port N` in a
     * process's own cmdline is what says which port that process was started with,
     * so a port that is busy for some other reason can never be mistaken for our
     * harness. Only processes whose /proc entry we can read are considered, which in
     * practice means our own uid -- another app's harness is invisible and stays
     * untouched.
     */
    private List<Harness> runningHarnesses() {
        List<Harness> found = new ArrayList<Harness>();
        File[] entries = new File("/proc").listFiles();
        if (entries == null) {
            return found;
        }
        String marker = dshEntry().getAbsolutePath();
        int mine = android.os.Process.myPid();
        String ourUid = "Uid:\t" + android.os.Process.myUid();
        for (File entry : entries) {
            String name = entry.getName();
            if (!entry.isDirectory() || !name.matches("[0-9]+")) {
                continue;
            }
            int pid;
            try {
                pid = Integer.parseInt(name);
            } catch (NumberFormatException e) {
                continue;
            }
            if (pid == mine) {
                continue;
            }
            String cmd = readProc(entry, "cmdline");
            if (cmd.isEmpty() || !cmd.contains(marker)) {
                continue;
            }
            if (!readProc(entry, "status").contains(ourUid)) {
                continue;
            }
            int p = portFromCmdline(cmd);
            if (p <= 0) {
                logLine("harness pid " + pid + " carries no --port argument; it cannot be reused");
                continue;
            }
            logLine("found a running harness of ours: pid " + pid + " on port " + p);
            found.add(new Harness(pid, p));
        }
        return found;
    }

    /** The value after `--port ` in a cmdline read from /proc, or -1. */
    private static int portFromCmdline(String cmd) {
        String flag = "--port ";
        int at = cmd.indexOf(flag);
        if (at < 0) {
            return -1;
        }
        int i = at + flag.length();
        int end = i;
        while (end < cmd.length() && Character.isDigit(cmd.charAt(end))) {
            end++;
        }
        if (end == i) {
            return -1;
        }
        try {
            return Integer.parseInt(cmd.substring(i, end));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * One HTTP/1.1 GET on loopback, without following anything.
     *
     * A listening socket proves nothing: a stopped or wedged harness still holds its
     * socket, and any other process on the phone may own the port. An *answer* proves
     * something, and the harness's answers are its own -- `GET /` without a session
     * token gets 401 "dsh web authentication required", and the same request with the
     * token that process was started with gets its 303 cookie exchange. Liveness and
     * identity are both decided from that, with no other app involved.
     *
     * @returns null when nothing answered at all.
     */
    private static Reply httpGet(int p, String path) {
        java.net.Socket socket = new java.net.Socket();
        try {
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", p), PROBE_CONNECT_MS);
            socket.setSoTimeout(PROBE_READ_MS);
            OutputStream out = socket.getOutputStream();
            out.write(("GET " + path + " HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:" + p + "\r\n"
                    + "Accept: */*\r\n"
                    + "Connection: close\r\n\r\n").getBytes("UTF-8"));
            out.flush();
            byte[] buf = new byte[4096];
            int total = 0;
            InputStream in = socket.getInputStream();
            int n;
            while (total < buf.length && (n = in.read(buf, total, buf.length - total)) > 0) {
                total += n;
            }
            String raw = new String(buf, 0, total, "UTF-8");
            Reply reply = new Reply();
            if (raw.startsWith("HTTP/")) {
                int sp = raw.indexOf(' ');
                if (sp > 0 && sp + 4 <= raw.length()) {
                    try {
                        reply.status = Integer.parseInt(raw.substring(sp + 1, sp + 4));
                    } catch (NumberFormatException notAStatus) {
                        reply.status = -1;
                    }
                }
                int at = raw.indexOf("\r\n\r\n");
                reply.body = at < 0 ? "" : raw.substring(at + 4);
            }
            return reply;
        } catch (Throwable nothingAnswered) {
            return null;
        } finally {
            try {
                socket.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Decide, before anything is started or killed, whether a harness is already
     * serving this app.
     *
     * Alive and usable are different questions, and only the second one may decide a
     * reuse. Reusing means handing the WebView the URL the harness printed when it
     * started -- `http://127.0.0.1:PORT/?token=...` -- and that token lives in the
     * process that printed it plus the copy `web.token` keeps. So a candidate is
     * adopted only when a request carrying that token is answered with the harness's
     * 303 exchange, which is the same proof a browser gets. Everything else is a
     * leftover: a harness from before an upgrade whose token died with the old app
     * process, a process that no longer answers, or a foreign listener on the port.
     *
     * Installs a new APK and the harness is still working? That is exactly the case
     * this exists for -- the session keeps its single writer, and the turn that was
     * in flight when the app process died keeps running.
     */
    private HarnessSweep sweepHarnesses() {
        HarnessSweep sweep = new HarnessSweep();
        List<Harness> live = runningHarnesses();
        if (live.isEmpty()) {
            logLine("no harness of ours is running; a fresh one will be started");
            reportPortBand();
            return sweep;
        }
        int preferred = rememberedPort();
        for (int i = 0; i < live.size(); i++) {
            if (live.get(i).port == preferred) {
                live.add(0, live.remove(i));
                break;
            }
        }
        String token = rememberedToken();
        for (Harness h : live) {
            String candidate = token;
            if (candidate == null || !tokenOpens(h.port, candidate)) {
                String recovered = tokenFromLog(h.port);
                if (recovered != null && !recovered.equals(candidate) && tokenOpens(h.port, recovered)) {
                    logLine("recovered the session token of pid " + h.pid
                            + " from a rotated dsh.log; it was started by an earlier app build");
                    candidate = recovered;
                }
            }
            if (candidate != null && tokenOpens(h.port, candidate)) {
                h.token = candidate;
                sweep.adopted = h;
                break;
            }
        }
        for (Harness h : live) {
            if (h == sweep.adopted) {
                continue;
            }
            h.why = sweep.adopted == null
                    ? refusal(h)
                    : "a second harness of ours on port " + h.port
                            + ", which would write the same session logs twice";
            sweep.stale.add(h);
        }
        return sweep;
    }

    /** True when `token` is that process's token: the harness answers with its 303. */
    private boolean tokenOpens(int p, String token) {
        Reply reply = httpGet(p, "/?token=" + token);
        return reply != null && reply.status == 303;
    }

    /**
     * Why a live harness on this port cannot be reused, in one clause for the log.
     *
     * Retried, because "does not answer" and "did not answer this instant" are not the
     * same claim and the difference is a runtime: an LLM turn does not block the event
     * loop, but a synchronous burst does, and a single timed-out request must never be
     * the evidence that gets a working process killed. What the process is doing while
     * it stays silent separates the two diagnoses.
     */
    private String refusal(Harness h) {
        Reply reply = null;
        for (int attempt = 0; attempt < 3 && reply == null; attempt++) {
            if (attempt > 0) {
                sleepQuietly(700);
            }
            reply = httpGet(h.port, "/");
        }
        if (reply == null) {
            boolean busy = cpuBusy(h.pid);
            return "a stale harness that is not serving (pid " + h.pid + " is alive "
                    + (busy ? "and still burning CPU" : "but silent") + " on port " + h.port + ")";
        }
        if (reply.isHarness()) {
            return "a leftover harness that cannot be reused (no session token for it on disk)";
        }
        return "a process on port " + h.port + " that does not answer as this app's harness (status "
                + reply.status + ")";
    }

    /**
     * What the port band answers when no harness of ours was found.
     *
     * A port in the band can be held by something that is not ours, and then the only
     * thing that matters is knowing it: the new harness gets a different port and the
     * log says why. Nothing here can decide an adoption -- that requires a process of
     * ours, checked in /proc.
     */
    private void reportPortBand() {
        for (int p = PORT_START; p < PORT_START + PORT_PROBE_SPAN; p++) {
            Reply reply = httpGet(p, "/");
            if (reply == null) {
                continue;
            }
            logLine("port " + p + " answers (status " + reply.status + ")"
                    + (reply.isHarness() ? " as a harness that is not one of our processes" : ""));
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * True when a process is burning CPU right now, sampled from its own /proc entry.
     *
     * Only ever used to keep the log honest: "alive but silent" and "alive, working,
     * and silent" are different diagnoses, and a runtime stuck in a synchronous burst
     * looks dead from the outside for exactly as long as the burst lasts.
     */
    private static boolean cpuBusy(int pid) {
        long before = cpuTicks(pid);
        sleepQuietly(500);
        long after = cpuTicks(pid);
        return before >= 0 && after > before;
    }

    /** utime + stime from /proc/<pid>/stat, or -1 when it cannot be read. */
    private static long cpuTicks(int pid) {
        String stat = readProc(new File("/proc/" + pid), "stat");
        int close = stat.lastIndexOf(')'); // comm may contain spaces and parentheses
        if (close < 0) {
            return -1;
        }
        String[] fields = stat.substring(close + 1).trim().split("\\s+");
        // After the comm field comes state(3)..cmajflt(13), then utime(14) and stime(15).
        if (fields.length < 13) {
            return -1;
        }
        try {
            return Long.parseLong(fields[11]) + Long.parseLong(fields[12]);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Clear the harnesses that could not be adopted.
     *
     * Every signal is announced with the reason it is being sent, because the harness
     * only reports the symptom ("SIGTERM received -> shutting down (exit 0)") and the
     * app used to report nothing at all: a task died with a clean exit code and no
     * line anywhere saying who ended it or why.
     *
     * The wait matters as much as the signal. A harness signalled a moment before the
     * new one starts may still be flushing a session log, and two writers on one log
     * is the corruption this whole path exists to prevent -- so the tree has to be
     * clear before anything new is started.
     */
    private void purgeStaleHarnesses(List<Harness> stale) throws IOException {
        for (Harness h : stale) {
            if (!new File("/proc/" + h.pid).isDirectory()) {
                continue;
            }
            logLine("killing " + h.why + " (pid " + h.pid + ")");
            android.os.Process.sendSignal(h.pid, 15);
            if (awaitGone(h.pid, 3000)) {
                continue;
            }
            logLine("pid " + h.pid + " ignored SIGTERM; sending SIGKILL");
            android.os.Process.sendSignal(h.pid, 9);
            if (!awaitGone(h.pid, 3000)) {
                throw new IOException("harness pid " + h.pid + " survived SIGKILL; refusing to"
                        + " start a second harness that would write the same session logs");
            }
        }
    }

    /** Wait for a process to leave /proc; a zombie has an empty cmdline and is gone. */
    private static boolean awaitGone(int pid, long millis) {
        File dir = new File("/proc/" + pid);
        long deadline = System.currentTimeMillis() + millis;
        while (true) {
            if (!dir.isDirectory() || readProc(dir, "cmdline").isEmpty()) {
                return true;
            }
            if (System.currentTimeMillis() >= deadline) {
                return false;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /**
     * True while that harness is still there.
     *
     * Two signals, either is enough: /proc still describes that process on that port,
     * or the port still answers with that process's own token exchange. Deliberately
     * generous, because "not there" is the expensive verdict -- it starts a second
     * harness, and a second writer on one session log is worse than a late restart.
     * The token exchange cannot be faked by another process: it is per-process random.
     */
    private boolean stillTheHarness(Harness h) {
        String cmd = readProc(new File("/proc/" + h.pid), "cmdline");
        if (cmd.contains(dshEntry().getAbsolutePath()) && cmd.contains("--port " + h.port)) {
            return true;
        }
        return h.token != null && tokenOpens(h.port, h.token);
    }

    /**
     * Continue with the harness that is already running.
     *
     * Nothing is killed and nothing is started: the process keeps serving, the session
     * keeps one writer, and the work that was in flight when the previous app process
     * was killed is still in flight. The only thing this instance has to add is the
     * URL the WebView needs, rebuilt from the port and token on disk.
     */
    private void adoptHarness(final Harness h) {
        port = h.port;
        adoptedPid = h.pid;
        sToken = h.token;
        sUrl = "http://127.0.0.1:" + h.port + "/?token=" + h.token;
        sRunning = true;
        rememberWebEndpoint(h.port, h.token);
        logLine("adopted the running harness on port " + h.port + " (pid " + h.pid + ")");
        postNotification("dsh is running", "adopted pid " + h.pid + " on port " + h.port);
        for (Listener l : LISTENERS) {
            l.onReady(sUrl, sToken);
        }
        superviseAdopted(h);
    }

    /**
     * An adopted harness is not this app's child, so `waitFor()` will never report its
     * death, and a runtime that dies unwatched would leave the console on a page that
     * cannot recover. Poll its identity instead (see stillTheHarness) and start a fresh
     * one only once it is really gone.
     */
    private void superviseAdopted(final Harness h) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!stopRequested && !retired) {
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (stopRequested || retired) {
                        return;
                    }
                    if (stillTheHarness(h)) {
                        continue;
                    }
                    sRunning = false;
                    adoptedPid = -1;
                    logLine("the adopted harness (pid " + h.pid + ", port " + h.port
                            + ") is gone; starting a fresh one");
                    try {
                        launch();
                    } catch (Throwable failed) {
                        logLine("restart after the adopted harness failed: " + failed);
                        stopSelf();
                    }
                    return;
                }
            }
        }, "dsh-adopted");
        t.start();
    }

    // ------------------------------------------------------------ web endpoint

    /**
     * Where the last harness was reachable, for the next app process.
     *
     * The harness prints its URL -- port plus a per-process random token -- once, on
     * stdout, and keeps the token in memory only: a new app process cannot ask the
     * running process for it, so without a copy on disk a harness can be proven alive
     * and still be unusable. These two files are that copy. They sit in the app's
     * private home, never on shared storage: they are credentials.
     */
    private File webPortFile() {
        return new File(homeDir(), "web.port");
    }

    private File webTokenFile() {
        return new File(homeDir(), "web.token");
    }

    private void rememberWebEndpoint(int p, String token) {
        try {
            if (!homeDir().mkdirs() && !homeDir().isDirectory()) {
                logLine("cannot create " + homeDir().getAbsolutePath());
                return;
            }
            writeFile(webPortFile(), p + "\n");
            writeFile(webTokenFile(), token + "\n");
            chmod600(webPortFile());
            chmod600(webTokenFile());
            logLine("remembered the web endpoint: port " + p + ", token " + token.length() + " chars");
        } catch (Throwable t) {
            logLine("cannot remember the web endpoint: " + t);
        }
    }

    /** Drop a description of a harness that is gone, so nothing adopts its ghost. */
    private void forgetWebEndpoint() {
        for (File f : new File[]{webPortFile(), webTokenFile()}) {
            if (f.isFile() && !f.delete()) {
                logLine("cannot remove " + f.getAbsolutePath());
            }
        }
    }

    private int rememberedPort() {
        try {
            int p = Integer.parseInt(readFile(webPortFile()).trim());
            return p > 0 && p < 65536 ? p : -1;
        } catch (NumberFormatException notANumber) {
            return -1;
        }
    }

    private String rememberedToken() {
        String s = readFile(webTokenFile()).trim();
        return s.isEmpty() ? null : s;
    }

    private static void chmod600(File f) {
        try {
            new ProcessBuilder("/system/bin/chmod", "600", f.getAbsolutePath())
                    .redirectErrorStream(true).start();
        } catch (Throwable ignored) {
            // already inside app-private storage; the mode is belt and braces
        }
    }

    /**
     * The token of a harness that outlived the app process that started it.
     *
     * The banner `dsh web: http://127.0.0.1:PORT/?token=...` is written into the log
     * as it arrives, so the rotated copies are the only remaining description of a
     * token whose process is still doing useful work -- the one case where clearing
     * the harness would interrupt a task for no reason. The recovered token is not
     * trusted on its own: the caller still has to get that process's 303 with it.
     */
    private String tokenFromLog(int p) {
        File dir = getFilesDir();
        String found = tokenInLog(new File(dir, "dsh.log"), p);
        for (int i = 1; found == null && i <= DiagnosticServer.LOG_GENERATIONS; i++) {
            found = tokenInLog(new File(dir, "dsh.log." + i), p);
        }
        return found;
    }

    /** The last token for that port in one log file, or null. */
    private static String tokenInLog(File f, int p) {
        if (!f.isFile() || f.length() > (1 << 20)) {
            return null;
        }
        String text = readFile(f);
        String needle = "http://127.0.0.1:" + p + "/?token=";
        String found = null;
        int at = text.indexOf(needle);
        while (at >= 0) {
            int i = at + needle.length();
            int end = i;
            while (end < text.length() && tokenChar(text.charAt(end))) {
                end++;
            }
            if (end > i) {
                found = text.substring(i, end);
            }
            at = text.indexOf(needle, at + 1);
        }
        return found;
    }

    /** The alphabet of the harness's launch token (base64url). */
    private static boolean tokenChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == '-' || c == '_';
    }

    /** First bytes of a /proc entry, or "" when it cannot be read. */
    private static String readProc(File dir, String name) {
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(new File(dir, name));
            byte[] buf = new byte[512];
            int n = in.read(buf);
            in.close();
            return n > 0 ? new String(buf, 0, n, "UTF-8").replace('\0', ' ') : "";
        } catch (Throwable t) {
            return "";
        }
    }

    private void launch() throws IOException {
        File node = new File(binDir(), "node");
        if (!node.isFile()) {
            throw new IOException("node binary missing at " + node);
        }

        /*
         * The harness this instance is already working with: leave the world alone.
         *
         * Every Activity start sends ACTION_START, so this method runs again whenever
         * the Activity is recreated -- on the way back from the background, on a
         * WebView reload, on a theme change. What it would find is the child this
         * service is supervising (replacing it kills the work in flight) or a harness
         * this same instance already adopted (adopting it twice would put two
         * watchdogs on one process, and each would start a harness when it dies).
         */
        if (somethingRunning()) {
            /*
             * Leaving the harness alone is not the same as doing nothing (P20).
             *
             * Every Activity start lands here once a harness exists, and the
             * Activity that asked is usually a new one with an empty WebView and
             * no URL of its own. This branch used to return in silence, so that
             * WebView was never navigated: the console came back to the
             * foreground blank, and only clearing the app fixed it. The harness
             * is still untouched -- the recorded URL is simply handed back, the
             * same way the adoption path hands it back, and the Activity decides
             * whether the page needs loading (it skips the load when the view is
             * already on it).
             */
            sRunning = true;
            if (sUrl != null) {
                logLine("harness already running on port " + port
                        + "; harness restart skipped, the view load is the Activity's");
                for (Listener l : LISTENERS) {
                    l.onReady(sUrl, sToken);
                }
            } else {
                logLine("this instance's harness is still running on port " + port
                        + "; nothing to do (no url recorded yet)");
            }
            return;
        }

        /*
         * Reuse before cleanup: being first is what broke a running task.
         *
         * The harness is a separate node process, it survives the app process being
         * killed, and the next app instance used to SIGTERM it as "stale" -- in the
         * middle of a turn, which the harness reports as a clean exit 0. What has to
         * be established first is which processes of ours are running, which one is
         * actually serving, and whether this instance can authenticate to it. Only
         * the leftovers are cleared.
         */
        HarnessSweep sweep = sweepHarnesses();
        if (sweep.adopted != null) {
            adoptHarness(sweep.adopted);
            purgeStaleHarnesses(sweep.stale);
            return;
        }
        purgeStaleHarnesses(sweep.stale);
        // The files describe a harness that just went away; the new one rewrites them.
        forgetWebEndpoint();

        port = freePort(PORT_START);
        List<String> cmd = new ArrayList<String>();
        cmd.add(node.getAbsolutePath());
        cmd.add(dshEntry().getAbsolutePath());
        cmd.add("web");
        cmd.add("--port");
        cmd.add(String.valueOf(port));
        cmd.add("--no-open");

        /*
         * Put the harness in its own session (setsid).
         *
         * Without it the harness shares a process group with whatever the agent
         * runs -- and a group-directed signal, which a root shell or su can emit,
         * then takes the harness down with the command. That is exactly how a test
         * command killed the runtime with SIGINT (exit 130, no crash, no error).
         * Starting it in its own session makes the runtime immune to that.
         */
        File sessionTool = new File(binDir(), "busybox");
        if (sessionTool.isFile()) {
            cmd.add(0, "setsid");
            cmd.add(0, sessionTool.getAbsolutePath());
        } else {
            logLine("warning: no busybox for setsid; the runtime shares this process group");
        }

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
                if (retired) {
                    // This instance was torn down without a stop request, so its threads
                    // have to fall silent: the successor owns the runtime now, and two
                    // restarters would start two harnesses on one set of session logs.
                    logLine("harness exited (" + exitReason(code) + ") after " + (lived / 1000)
                            + "s; this service instance is retired, the running one decides");
                    return;
                }
                for (Listener l : LISTENERS) {
                    l.onExit(code);
                }
                /*
                 * Bring it back whenever it was not stopped on purpose.
                 *
                 * The exit code is not the question: 0 here means "someone signalled
                 * it" (the app stops the harness with SIGTERM, which the harness
                 * reports as a clean exit), not "the work is done". A runtime that is
                 * supposed to be running and is not has to come back. A harness that
                 * dies straight away is broken, not unlucky, so the uptime floor and
                 * the retry cap still apply.
                 */
                if (!stopRequested && lived > 10000 && restarts < MAX_RESTARTS) {
                    restarts++;
                    logLine("harness exited (" + exitReason(code) + ") after " + (lived / 1000)
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
                logLine("harness exited (" + exitReason(code) + ") after " + (lived / 1000)
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
                        logLine(stamp() + " " + line);
                        String url = extractUrl(line);
                        if (url != null) {
                            sUrl = url;
                            sToken = tokenOf(url);
                            int announced = portOf(url);
                            if (announced > 0) {
                                port = announced;
                            }
                            if (announced > 0 && sToken != null) {
                                // The only moment the token can be captured: the harness
                                // prints it once and keeps it in memory afterwards, so
                                // this write is what lets a later app process log into
                                // the process that is still running.
                                rememberWebEndpoint(announced, sToken);
                            }
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

    /** The port in a loopback URL, or -1. */
    private static int portOf(String url) {
        String prefix = "http://127.0.0.1:";
        if (!url.startsWith(prefix)) {
            return -1;
        }
        int i = prefix.length();
        int end = i;
        while (end < url.length() && Character.isDigit(url.charAt(end))) {
            end++;
        }
        if (end == i) {
            return -1;
        }
        try {
            return Integer.parseInt(url.substring(i, end));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** The `?token=` value of an app URL, or null. */
    private static String tokenOf(String url) {
        int at = url.indexOf("?token=");
        if (at < 0) {
            return null;
        }
        int i = at + "?token=".length();
        int end = i;
        while (end < url.length() && tokenChar(url.charAt(end))) {
            end++;
        }
        return end > i ? url.substring(i, end) : null;
    }

    /** True while a child process is still alive (exitValue throws while it runs). */
    private static boolean stillRunning(Process p) {
        if (p == null) {
            return false;
        }
        try {
            p.exitValue();
            return false;
        } catch (IllegalThreadStateException alive) {
            return true;
        }
    }

    /**
     * Say what an exit code means, because "code 130" tells nobody anything.
     *
     * A shell reports a fatal signal as 128+N, so 130 is SIGINT and 143 is SIGTERM.
     * The runtime dying that way is not a crash -- someone signalled it -- and the
     * difference decides where to look. Logging the signal name is the whole point.
     */
    /**
     * Wall-clock prefix for every harness line.
     *
     * The harness prints no timestamps of its own, so two events could not be
     * ordered after the fact -- which is exactly what made the port/screen-flicker
     * hunt take a whole round: we could see the lines but not when they happened.
     */
    private static String stamp() {
        return new java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
                .format(new java.util.Date());
    }

    private static String exitReason(int code) {
        if (code > 128 && code < 160) {
            String[] names = {"HUP", "INT", "QUIT", "ILL", "TRAP", "ABRT", "BUS", "FPE",
                    "KILL", "USR1", "SEGV", "USR2", "PIPE", "ALRM", "TERM"};
            int n = code - 128;
            String name = n >= 1 && n <= names.length ? "SIG" + names[n - 1] : "signal " + n;
            return "code " + code + " -- killed by " + name + " (128+" + n + ")";
        }
        if (code == 0) {
            return "code 0 -- clean exit";
        }
        return "code " + code;
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
