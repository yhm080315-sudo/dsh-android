package com.dsh.android;

import android.app.Activity;
import java.io.File;
import android.content.Intent;
import android.net.Uri;
import java.io.IOException;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Hosts the harness: starts the bundled runtime service, then hands the browser
 * half of the UI to a WebView pointed at the loopback URL the harness prints.
 */
public class MainActivity extends Activity implements NodeServer.Listener {

    /**
     * Diagnostic mode: keep the log panel on screen instead of attaching the
     * WebView, so the runtime state is readable by an accessibility dump.
     * Enabled when the launch intent carries the extra, or when the file
     * filesDir/diagnostics exists.
     */
    private boolean diagnostics;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private LinearLayout logPanel;
    private TextView logView;
    private TextView statusView;
    private Button updateButton;
    private Button probeButton;
    private Button storageButton;
    private Button rootButton;
    private Button resumeButton;
    private TextView logChip;
    /**
     * Console appearance (palette + optional background image), read from
     * shared storage at every launch so the look can be changed without
     * rebuilding the APK. See {@link LauncherTheme}.
     */
    private LauncherTheme theme;
    private ImageView backgroundView;
    /** Bounded retries: the image only becomes readable once access is granted. */
    private int backgroundAttempts;
    /** Set while the system picker is open for a page's file input. */
    private android.webkit.ValueCallback<Uri[]> pendingFileCallback;
    private static final int REQUEST_FILE_CHOOSER = 4001;
    /** Picking an image to use as the launcher console background. */
    private static final int REQUEST_BACKGROUND = 4002;
    /** When the WebView attached, to timestamp resource failures against it. */
    private volatile long webAttachedAt;
    private File pendingApk;
    private WebView web;
    private boolean webAttached;
    private String pendingUrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        diagnostics = getIntent() != null
                && getIntent().getBooleanExtra("diagnostics", false);
        if (new java.io.File(getFilesDir(), "diagnostics").isFile()) {
            diagnostics = true;
        }
        theme = LauncherTheme.load(this);
        buildUi();
        theme.writeExample();
        logLine("launcher theme: " + theme.describe());
        applyBackgroundIfNeeded();

        NodeServer.addListener(this);
        // Identify the running build in the log: several were shipped while this
        // was being brought up, and the versionName is what tells them apart.
        try {
            android.content.pm.PackageInfo info = getPackageManager()
                    .getPackageInfo(getPackageName(), 0);
            logLine("app version: " + info.versionName + " (code " + info.versionCode + ")");
        } catch (Throwable t) {
            logLine("app version unavailable: " + t);
        }
        reportStorageAccess();
        reportLinkSupport();
        // Device control lives in the app because screen capture and input
        // injection need signature-level permissions. Starting here (not in the
        // service) keeps it alive across WebView reloads.
        DeviceServer.startOnce(getFilesDir());
        if (new File(getFilesDir(), "root-granted").isFile()) {
            reportRoot(false);
        }
        if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[]{
                    "android.permission.READ_EXTERNAL_STORAGE",
                    "android.permission.WRITE_EXTERNAL_STORAGE"}, 1);
        }
        if (diagnostics) {
            describeRuntime();
        }

        if (NodeServer.isRunning() && NodeServer.currentUrl() != null) {
            pendingUrl = NodeServer.currentUrl();
        }
        Intent svc = new Intent(this, NodeServer.class);
        svc.setAction(NodeServer.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(svc);
        } else {
            startService(svc);
        }
    }

    private void reportRoot(boolean interactive) {
        // The bridge already probed root on its own thread, so this is a cheap
        // read rather than another blocking su round trip on the UI thread. The
        // root-granted marker records a previous successful grant; a marker
        // without a live bridge means the grant was revoked, so clear it.
        boolean root = DeviceServer.rootAvailable();
        if (root) {
            logLine("root: GRANTED (device bridge on 127.0.0.1:" + DeviceServer.currentPort() + ")");
        } else if (new File(getFilesDir(), "root-granted").isFile()) {
            logLine("root: marker present but bridge not root; clearing marker");
            new File(getFilesDir(), "root-granted").delete();
        } else {
            logLine("root: not granted");
        }
    }

    /**
     * Session publishing hard-links a temp file into place. Some Android
     * app-storage volumes refuse link(2), so probe once and report the raw error.
     */
    private void reportLinkSupport() {
        File dir = new File(getFilesDir(), "linktest");
        File src = new File(dir, "src.txt");
        File dst = new File(dir, "dst.txt");
        try {
            if (!dir.mkdirs() && !dir.isDirectory()) {
                logLine("hard link: cannot create probe dir");
                return;
            }
            java.io.FileOutputStream fos = new java.io.FileOutputStream(src);
            fos.write("link probe\n".getBytes("UTF-8"));
            fos.close();
            dst.delete();
            if (Build.VERSION.SDK_INT >= 26) {
                java.nio.file.Files.createLink(dst.toPath(), src.toPath());
                logLine("hard link: OK (Files.createLink)");
            } else {
                Process p = new ProcessBuilder("/system/bin/ln",
                        src.getAbsolutePath(), dst.getAbsolutePath())
                        .redirectErrorStream(true).start();
                int rc = p.waitFor();
                logLine("hard link: ln rc=" + rc);
            }
        } catch (Throwable t) {
            logLine("hard link: FAILED -> " + t);
        }
    }

    /**
     * Ask for shared-storage access and report what the app actually got.
     *
     * Two mechanisms: the classic runtime permissions (API 23+) and, on API 30+,
     * the "All files access" special permission which the user must toggle in
     * Settings -- it cannot be granted from an in-app dialog.
     */
    private void requestStorageAccess() {
        try {
            if (Build.VERSION.SDK_INT >= 30
                    && !android.os.Environment.isExternalStorageManager()) {
                Intent all = new Intent(
                        "android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION");
                all.setData(android.net.Uri.parse("package:" + getPackageName()));
                startActivity(all);
                logLine("opened All-files-access settings for " + getPackageName());
            } else if (Build.VERSION.SDK_INT >= 23) {
                requestPermissions(new String[]{
                        "android.permission.READ_EXTERNAL_STORAGE",
                        "android.permission.WRITE_EXTERNAL_STORAGE"}, 1);
            }
        } catch (Throwable t) {
            logLine("storage request failed: " + t);
        }
    }

    /** Report, in the log, exactly how far the app's file access reaches. */
    private void reportStorageAccess() {
        StringBuilder sb = new StringBuilder("storage: ");
        if (Build.VERSION.SDK_INT >= 30) {
            sb.append("all-files=")
              .append(android.os.Environment.isExternalStorageManager())
              .append(' ');
        }
        sb.append("externalState=").append(android.os.Environment.getExternalStorageState());
        logLine(sb.toString());

        for (String dir : new String[]{"/sdcard", "/sdcard/Download", "/sdcard/DCIM",
                "/sdcard/Android/data", "/storage/emulated/0"}) {
            File f = new File(dir);
            logLine("storage: " + dir + " exists=" + f.exists()
                    + " r=" + f.canRead() + " w=" + f.canWrite());
        }
        File probeDir = new File("/sdcard/DSH");
        try {
            if (probeDir.mkdirs() || probeDir.isDirectory()) {
                File probe = new File(probeDir, "app-write-test.txt");
                java.io.FileOutputStream fos = new java.io.FileOutputStream(probe);
                fos.write(("written by com.dsh.android at "
                        + new java.util.Date() + "\n").getBytes("UTF-8"));
                fos.close();
                logLine("storage: write test OK -> " + probe.getAbsolutePath());
            } else {
                logLine("storage: cannot create /sdcard/DSH (no permission yet?)");
            }
        } catch (Throwable t) {
            logLine("storage: write test failed: " + t);
        }
    }

    /**
     * Fetch the newest APK from the build machine and raise the system installer.
     * Android still requires the user's confirmation tap; everything else is
     * automatic, which is what makes iterating possible without a sideload cable.
     */
    private void checkForUpdate() {
        updateButton.setEnabled(false);
        statusView.setText("正在检查更新…");
        nl();
        final String prefix = "update";
        logView.append("[" + prefix + "] feed: " + SelfUpdater.feedUrl(getFilesDir()) + "\n");
        SelfUpdater.checkAndDownload(getFilesDir(), getCacheDir(), new SelfUpdater.Callback() {
            @Override
            public void onProgress(final String message) {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        logView.append("[" + prefix + "] " + message + "\n");
                    }
                });
            }

            @Override
            public void onReady(final File apk) {
                pendingApk = apk;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        updateButton.setEnabled(true);
                        updateButton.setText("安装 " + apk.getName());
                        statusView.setText("更新已就绪：" + apk.getName());
                        logView.append("[" + prefix + "] ready: " + apk.getAbsolutePath()
                                + " (" + apk.length() + " bytes)\n");
                        try {
                            SelfUpdater.install(MainActivity.this, apk);
                        } catch (Throwable t) {
                            logView.append("[" + prefix + "] installer failed: " + t + "\n");
                        }
                    }
                });
            }

            @Override
            public void onFailed(final String reason) {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        updateButton.setEnabled(true);
                        statusView.setText("更新失败：" + reason);
                        statusView.setTextColor(theme.err);
                        logView.append("[" + prefix + "] failed: " + reason + "\n");
                    }
                });
            }
        });
    }

    /** Mirror a line into the on-disk log the diagnostics page reads. */
    private void logLine(String line) {
        DiagnosticServer.appendLog(getFilesDir(), line);
        android.util.Log.i("DSH", line);
    }

    private void nl() {
    }

    /**
     * Run the runtime pieces separately so a failure points at one of them.
     * Also re-shows the log panel: the WebView never attached on a failed boot.
     */
    private void runProbe() {
        probeButton.setEnabled(false);
        showLog();
        logView.append("\n=== runtime probe ===\n");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final StringBuilder sb = new StringBuilder();
                try {
                    java.io.File rt = new java.io.File(getFilesDir(), "runtime");
                    java.util.List<RuntimeProbe.Step> steps =
                            new RuntimeProbe(rt).runAll();
                    for (RuntimeProbe.Step step : steps) {
                        sb.append(step.render());
                        DiagnosticServer.appendLog(getFilesDir(), step.render());
                    }
                } catch (Throwable t) {
                    sb.append("probe crashed: ").append(t).append('\n');
                    DiagnosticServer.appendLog(getFilesDir(), "probe crashed: " + t);
                }
                final String text = sb.toString();
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        probeButton.setEnabled(true);
                        logView.append(text);
                        statusView.setText("自检完成，结果见下方日志");
                    }
                });
            }
        }, "dsh-probe").start();
    }

    /** Print what the app can see of its own extracted runtime. */
    private void describeRuntime() {
        java.io.File rt = new java.io.File(getFilesDir(), "runtime");
        java.io.File node = new java.io.File(rt, "usr/bin/node");
        java.io.File entry = new java.io.File(rt,
                "dsh/node_modules/@deepseek-ai/dsh/lib/bin.js");
        java.io.File marker = new java.io.File(rt, ".extracted");
        StringBuilder sb = new StringBuilder();
        sb.append("DIAG filesDir=").append(getFilesDir().getAbsolutePath()).append('\n');
        sb.append("DIAG runtime exists=").append(rt.isDirectory()).append('\n');
        sb.append("DIAG entries=").append(rt.list() == null ? "null"
                : java.util.Arrays.toString(rt.list())).append('\n');
        sb.append("DIAG node file=").append(node.isFile())
                .append(" bytes=").append(node.length())
                .append(" exec=").append(node.canExecute()).append('\n');
        sb.append("DIAG dsh entry=").append(entry.isFile()).append('\n');
        sb.append("DIAG marker=").append(marker.isFile()).append('\n');
        logView.append(sb.toString());
    }

    // ---- palette ------------------------------------------------------------
    // The defaults live in LauncherTheme now, and every one of them can be
    // overridden from /sdcard/DSH/launcher/theme.conf -- this screen is the one
    // users stare at while the runtime unpacks, and it used to be the only part
    // of the app whose colours could not be changed without a rebuild.

    private static android.graphics.drawable.GradientDrawable plate(int color, float radius) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private static android.graphics.drawable.GradientDrawable outlined(int color, int stroke, float radius) {
        android.graphics.drawable.GradientDrawable d = plate(color, radius);
        d.setStroke(2, stroke);
        return d;
    }

    private Button action(String label, boolean primary) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(15f);
        b.setTextColor(primary ? 0xFFFFFFFF : theme.text);
        b.setTypeface(android.graphics.Typeface.DEFAULT, primary
                ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        b.setBackground(primary
                ? plate(alpha(theme.primary), theme.radius)
                : outlined(alpha(theme.card), alpha(theme.stroke), theme.radius));
        b.setPadding(0, 0, 0, 0);
        float h = 46 * getResources().getDisplayMetrics().density;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) h);
        lp.bottomMargin = (int) (10 * getResources().getDisplayMetrics().density);
        b.setLayoutParams(lp);
        return b;
    }

    /** Rebuild the console with the current theme, keeping the log text. */
    private View buildUiAgain() {
        CharSequence kept = logView == null ? "" : logView.getText();
        buildUi();
        if (logView != null && kept.length() > 0) {
            logView.setText(kept);
        }
        return root;
    }

    private void buildUi() {
        getWindow().setBackgroundDrawable(
                new android.graphics.drawable.ColorDrawable(theme.bg));
        if (Build.VERSION.SDK_INT >= 21) {
            getWindow().setStatusBarColor(theme.bg);
            getWindow().setNavigationBarColor(theme.bg);
        }
        root = new FrameLayout(this);
        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (18 * d);

        /*
         * Sits behind the console when a background image is configured, and
         * stays empty otherwise. It is added first and kept invisible until the
         * bitmap is actually in memory, so the flat palette shows first and the
         * image fades in rather than the screen flashing empty.
         */
        backgroundView = new ImageView(this);
        backgroundView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        backgroundView.setVisibility(View.GONE);
        root.addView(backgroundView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        logPanel = new LinearLayout(this);
        logPanel.setOrientation(LinearLayout.VERTICAL);
        logPanel.setBackgroundColor(alpha(theme.bg));
        logPanel.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText(theme.title);
        title.setTextSize(21f);
        title.setTextColor(theme.text);
        title.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        logPanel.addView(title);

        TextView version = new TextView(this);
        try {
            android.content.pm.PackageInfo info = getPackageManager()
                    .getPackageInfo(getPackageName(), 0);
            version.setText("版本 " + info.versionName);
        } catch (Throwable t) {
            version.setText("版本 未知");
        }
        version.setTextSize(12f);
        version.setTextColor(theme.dim);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vlp.bottomMargin = (int) (14 * d);
        logPanel.addView(version, vlp);

        statusView = new TextView(this);
        statusView.setTextSize(13f);
        statusView.setTextColor(theme.warn);
        statusView.setBackground(outlined(alpha(theme.card), alpha(theme.stroke), 12f));
        int sp = (int) (12 * d);
        statusView.setPadding(sp, sp, sp, sp);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.bottomMargin = (int) (16 * d);
        statusView.setText("正在启动内置运行环境…");
        logPanel.addView(statusView, slp);

        resumeButton = action("返回界面", true);
        resumeButton.setVisibility(View.GONE);
        resumeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showWeb();
            }
        });
        logPanel.addView(resumeButton);

        Button bgButton = action("换背景图", false);
        bgButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickBackground();
            }
        });
        logPanel.addView(bgButton);

        rootButton = action("授予 root 权限", false);
        rootButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                logLine("root status: bridge=" + DeviceServer.currentPort()
                        + " root=" + DeviceServer.rootAvailable());
                reportRoot(true);
            }
        });
        logPanel.addView(rootButton);

        storageButton = action("授予文件访问", false);
        storageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestStorageAccess();
                reportStorageAccess();
            }
        });
        logPanel.addView(storageButton);

        probeButton = action("运行环境自检", false);
        probeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runProbe();
            }
        });
        logPanel.addView(probeButton);

        updateButton = action("检查更新", false);
        updateButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkForUpdate();
            }
        });
        logPanel.addView(updateButton);

        Button copy = action("复制日志", false);
        copy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                        getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("dsh log", logView.getText()));
                statusView.setText("日志已复制到剪贴板");
            }
        });
        logPanel.addView(copy);

        TextView logLabel = new TextView(this);
        logLabel.setText("运行日志");
        logLabel.setTextSize(12f);
        logLabel.setTextColor(theme.dim);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = (int) (8 * d);
        llp.bottomMargin = (int) (6 * d);
        logPanel.addView(logLabel, llp);

        logView = new TextView(this);
        logView.setTextSize(11f);
        logView.setTextColor(theme.log);
        logView.setTypeface(android.graphics.Typeface.MONOSPACE);
        logView.setLineSpacing(0f, 1.15f);
        logView.setTextIsSelectable(true);
        logView.setBackground(plate(alpha(theme.card), 12f));
        logView.setPadding(sp, sp, sp, sp);
        logView.setMovementMethod(new ScrollingMovementMethod());
        ScrollView logCard = new ScrollView(this);
        logCard.setBackground(plate(alpha(theme.card), 12f));
        logCard.addView(logView);
        logPanel.addView(logCard, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(logPanel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // a chip that brings the log back while the UI is on screen
        logChip = new TextView(this);
        logChip.setText("日志");
        logChip.setTextSize(13f);
        logChip.setTextColor(theme.text);
        logChip.setBackground(plate(alpha(theme.card), 20f));
        int cp = (int) (14 * d);
        logChip.setPadding(cp, (int) (8 * d), cp, (int) (8 * d));
        logChip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showLog();
            }
        });
        FrameLayout.LayoutParams chipLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chipLp.gravity = android.view.Gravity.TOP | android.view.Gravity.END;
        chipLp.topMargin = (int) (10 * d);
        chipLp.rightMargin = (int) (10 * d);
        logChip.setVisibility(View.GONE);
        root.addView(logChip, chipLp);

        setContentView(root);
    }

    /**
     * Card/panel fills, at the opacity the theme asks for. With a background
     * image in play the console turns into glass over it; with none the panels
     * stay fully opaque, which is exactly the flat console of before.
     */
    private int alpha(int color) {
        return LauncherTheme.withOpacity(color, theme.panelOpacity());
    }

    /**
     * Decode the configured background off the UI thread and fade it in.
     *
     * Reading the file can fail for a reason the user can fix -- no file access
     * yet -- so this is retried from onResume (which also runs after the
     * settings screen the storage button opens) instead of being attempted once
     * and forgotten. The attempts are capped because a genuinely unreadable
     * image should cost a few log lines, not a decode per resume.
     */
    private void applyBackgroundIfNeeded() {
        if (theme == null || theme.background == null || backgroundAttempts >= 3) {
            return;
        }
        backgroundAttempts++;
        final int target = getResources().getDisplayMetrics().widthPixels;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final android.graphics.Bitmap bitmap = theme.decode(target);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (bitmap == null) {
                            logLine("launcher theme: background unreadable or undecodable ("
                                    + theme.background.getAbsolutePath()
                                    + "); keeping the flat palette");
                            return;
                        }
                        backgroundAttempts = 99;
                        backgroundView.setImageBitmap(bitmap);
                        backgroundView.setAlpha(theme.scrim);
                        backgroundView.setVisibility(View.VISIBLE);
                        logLine("launcher theme: background applied "
                                + bitmap.getWidth() + "x" + bitmap.getHeight()
                                + " alpha=" + theme.scrim);
                    }
                });
            }
        }, "dsh-theme").start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyBackgroundIfNeeded();
    }

    /**
     * The harness serves its shell HTML as soon as the web carrier is up, but the
     * client plugin graph is composed asynchronously and injected into that HTML.
     * Loading the page too early yields "HTML did not preload
     * @deepseek-ai/dsh-client-modules/client.js" in the browser, so poll the page
     * until the bootstrap entry is actually present.
     */
    private void awaitClientGraphAndAttach(final String url) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String marker = "@deepseek-ai/dsh-client-modules/client.js";
                String body = null;
                for (int attempt = 1; attempt <= 30; attempt++) {
                    body = fetch(url);
                    boolean tag = body != null && body.contains("script src=\"plugins/");
                    boolean ready = body != null && body.contains(marker) && tag;
                    logLine("page check " + attempt + ": "
                            + (body == null ? "no response" : (body.length() + " bytes"))
                            + " bootstrap-tag=" + tag
                            + (ready ? " [ready]" : ""));
                    if (ready) {
                        break;
                    }
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                dumpPage(body, "url=" + url);
                final String finalUrl = url;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        attachWeb(finalUrl);
                    }
                });
            }
        }, "dsh-pagecheck").start();
    }

    /** Persist the served page for offline inspection. */
    private void dumpPage(String body, String note) {
        File ext = getExternalFilesDir(null);
        if (ext == null) {
            return;
        }
        try {
            File out = new File(ext, "page.html");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
            fos.write((note + "\n=== page (" + (body == null ? 0 : body.length())
                    + " bytes) ===\n").getBytes("UTF-8"));
            if (body != null) {
                fos.write(body.getBytes("UTF-8"));
            }
            fos.close();
            logLine("page written to " + out.getAbsolutePath());
        } catch (Throwable t) {
            logLine("page dump failed: " + t);
        }
    }

    /** GET a URL honouring the harness's token cookie jar. */
    private String fetch(String url) {
        java.net.HttpURLConnection conn = null;
        try {
            String cookie = null;
            for (int hop = 0; hop < 4; hop++) {
                conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(8000);
                if (cookie != null) {
                    conn.setRequestProperty("Cookie", cookie);
                }
                int code = conn.getResponseCode();
                String setCookie = conn.getHeaderField("Set-Cookie");
                if (setCookie != null) {
                    cookie = setCookie.split(";")[0];
                }
                if (code >= 300 && code < 400 && hop < 3) {
                    String location = conn.getHeaderField("Location");
                    conn.disconnect();
                    if (location == null) {
                        return null;
                    }
                    url = new java.net.URL(new java.net.URL(url), location).toString();
                    continue;
                }
                if (code != 200) {
                    return null;
                }
                java.io.InputStream in = conn.getInputStream();
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[1 << 14];
                int n;
                int total = 0;
                while ((n = in.read(buf)) > 0 && total < (1 << 20)) {
                    out.write(buf, 0, n);
                    total += n;
                }
                in.close();
                return out.toString("UTF-8");
            }
            return null;
        } catch (Throwable t) {
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private void attachWeb(String url) {
        if (diagnostics) {
            statusView.setText("诊断模式：运行环境就绪 " + url);
            return;
        }
        if (webAttached) {
            if (web != null) {
                web.loadUrl(url);
            }
            return;
        }
        web = new WebView(this);
        web.clearCache(true);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        if (Build.VERSION.SDK_INT >= 21) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
        web.setWebViewClient(new WebViewClient() {
            /*
             * The client reports "Failed to fetch" with no status, which cannot be
             * told apart from a transport failure -- and a batch of failed plugin
             * fetches at startup was undiagnosable because of that. Record the URL
             * and the error code for every resource the WebView fails to load.
             */
            @Override
            public void onReceivedError(WebView view, android.webkit.WebResourceRequest request,
                    android.webkit.WebResourceError error) {
                if (request == null) {
                    return;
                }
                /*
                 * A batch of plugin fetches fails at every startup and nothing explains
                 * it: the server answers the same URLs with 200 when asked directly, so
                 * it looks like the WebView aborts them while mounting. Recording
                 * whether it is a main-frame request and how long after the WebView
                 * attached separates "transient startup abort" from "persistent
                 * failure" -- which is the only thing missing to root-cause it.
                 */
                long since = webAttachedAt == 0 ? -1 : System.currentTimeMillis() - webAttachedAt;
                logLine("web[load-fail] main=" + request.isForMainFrame()
                        + " t+" + since + "ms " + request.getUrl()
                        + " code=" + (error == null ? "?" : error.getErrorCode())
                        + " " + (error == null ? "" : error.getDescription()));
            }
        });
        /*
         * A WebView ignores `<input type="file">` unless the host answers
         * onShowFileChooser -- tapping 上传图片/上传附件 otherwise does nothing at
         * all, with no error anywhere. Hand the request to the system picker and
         * pass the chosen document back to the page.
         */
        web.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage message) {
                /*
                 * The client reports its failures to the browser console, which an
                 * app log never sees -- an on-device upload failure was therefore
                 * invisible from here. Keep the errors (and anything about uploads)
                 * so they land next to the harness output.
                 */
                String text = message == null ? null : message.message();
                if (text == null) {
                    return true;
                }
                boolean noisy = message.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR
                        || text.contains("[upload]")
                        || text.contains("upload")
                        || text.contains("Failed")
                        || text.contains("failed");
                if (noisy) {
                    logLine("web[" + message.messageLevel() + "] " + text
                            + " (" + message.sourceId() + ":" + message.lineNumber() + ")");
                }
                return true;
            }

            @Override
            public boolean onShowFileChooser(WebView view,
                    android.webkit.ValueCallback<Uri[]> callback,
                    FileChooserParams params) {
                if (pendingFileCallback != null) {
                    pendingFileCallback.onReceiveValue(null);
                }
                pendingFileCallback = callback;
                try {
                    Intent pick = params.createIntent();
                    pick.addCategory(Intent.CATEGORY_OPENABLE);
                    startActivityForResult(pick, REQUEST_FILE_CHOOSER);
                    return true;
                } catch (Throwable t) {
                    logLine("file chooser failed: " + t);
                    pendingFileCallback = null;
                    return false;
                }
            }
        });
        web.setBackgroundColor(theme.bg);
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        webAttached = true;
        webAttachedAt = System.currentTimeMillis();
        web.loadUrl(url);
        logPanel.setVisibility(View.GONE);
        if (logChip != null) {
            logChip.setVisibility(View.VISIBLE);
        }
    }

    private void showLog() {
        if (webAttached) {
            web.setVisibility(View.GONE);
        }
        if (logChip != null) {
            logChip.setVisibility(View.GONE);
        }
        logPanel.setVisibility(View.VISIBLE);
        if (resumeButton != null && webAttached) {
            resumeButton.setVisibility(View.VISIBLE);
        }
    }

    private void showWeb() {
        logPanel.setVisibility(View.GONE);
        if (webAttached) {
            web.setVisibility(View.VISIBLE);
        }
        if (logChip != null) {
            logChip.setVisibility(webAttached ? View.VISIBLE : View.GONE);
        }
    }

    // ------------------------------------------------------- service events

    @Override
    public void onLog(final String line) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (pendingUrl != null) {
                    attachWeb(pendingUrl);
                    pendingUrl = null;
                }
                logView.append(line + "\n");
                int scroll = logView.getLayout() == null ? 0
                        : logView.getLayout().getLineTop(logView.getLineCount()) - logView.getHeight();
                if (scroll > 0) {
                    logView.scrollTo(0, scroll);
                }
            }
        });
    }

    @Override
    public void onReady(final String url, String token) {
        pendingUrl = url;
        ui.post(new Runnable() {
            @Override
            public void run() {
                statusView.setText("已就绪：" + url);
                statusView.setTextColor(theme.ok);
            }
        });
        // Only hand the page to the WebView once its client plugin graph exists.
        awaitClientGraphAndAttach(url);
    }

    @Override
    public void onExit(final int code) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                statusView.setText("运行环境已退出（代码 " + code + "），详见日志");
                statusView.setTextColor(theme.err);
                showLog();
            }
        });
    }

    // ------------------------------------------------------------ back/exit

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (webAttached && web != null && web.canGoBack()) {
                web.goBack();
                return true;
            }
            moveTaskToBack(true);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ------------------------------------------------------- file chooser

    /**
     * Pick an image and make it this screen's background.
     *
     * The theme already supports a zero-config background: any file named
     * bg.jpg/bg.jpeg/bg.png/bg.webp in /sdcard/DSH/launcher. So "quickly change
     * the background" is just a picker plus a copy -- no config file to edit, and
     * the on-screen result is immediate.
     *
     * A DSH plugin could not do this: this console is the native screen that is
     * shown *before* the harness and its plugins exist. Writing the same file is
     * what a plugin would have to do anyway.
     */
    private void pickBackground() {
        try {
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            pick.addCategory(Intent.CATEGORY_OPENABLE);
            pick.setType("image/*");
            startActivityForResult(pick, REQUEST_BACKGROUND);
        } catch (Throwable t) {
            logLine("background picker failed: " + t);
        }
    }

    /** Copy the chosen image to /sdcard/DSH/launcher/bg<ext> and re-apply it. */
    private void applyBackground(Intent data) {
        java.io.InputStream in = null;
        java.io.OutputStream out = null;
        try {
            android.net.Uri source = data == null ? null : data.getData();
            if (source == null) {
                logLine("background: nothing selected");
                return;
            }
            String mime = getContentResolver().getType(source);
            String ext = "jpg";
            if (mime != null) {
                if (mime.contains("png")) {
                    ext = "png";
                } else if (mime.contains("webp")) {
                    ext = "webp";
                } else if (mime.contains("jpeg") || mime.contains("jpg")) {
                    ext = "jpg";
                }
            }
            File dir = new File(LauncherTheme.SHARED_DIR);
            if (!dir.mkdirs() && !dir.isDirectory()) {
                logLine("background: cannot create " + dir);
                return;
            }
            // Other spellings are read too, but leaving them behind would make the
            // old picture win over the new one on the next start.
            for (String name : new String[]{"bg.jpg", "bg.jpeg", "bg.png", "bg.webp"}) {
                File stale = new File(dir, name);
                if (stale.isFile()) {
                    stale.delete();
                }
            }
            File target = new File(dir, "bg." + ext);
            in = getContentResolver().openInputStream(source);
            if (in == null) {
                logLine("background: no stream for " + source);
                return;
            }
            out = new java.io.FileOutputStream(target);
            byte[] buf = new byte[1 << 16];
            long total = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                if (n > 0) {
                    out.write(buf, 0, n);
                    total += n;
                }
            }
            out.flush();
            logLine("background: " + target + " (" + total + " bytes)");
            // Re-read and repaint: the change is visible without restarting.
            theme = LauncherTheme.load(this);
            logLine("launcher theme: " + theme.describe());
            setContentView(buildUiAgain());
        } catch (Throwable t) {
            logLine("background failed: " + t);
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (IOException ignored) {
            }
            try {
                if (out != null) {
                    out.close();
                }
            } catch (IOException ignored) {
            }
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        if (request == REQUEST_BACKGROUND) {
            if (result == RESULT_OK) {
                applyBackground(data);
            } else {
                logLine("background: cancelled");
            }
            return;
        }
        if (request == REQUEST_FILE_CHOOSER) {
            Uri[] chosen = null;
            if (result == RESULT_OK && data != null) {
                java.util.List<Uri> picked = new java.util.ArrayList<Uri>();
                if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    for (int i = 0; i < count; i++) {
                        picked.add(data.getClipData().getItemAt(i).getUri());
                    }
                } else if (data.getData() != null) {
                    picked.add(data.getData());
                }
                java.util.List<Uri> local = new java.util.ArrayList<Uri>();
                for (Uri uri : picked) {
                    local.add(stagePickedFile(uri));
                }
                if (!local.isEmpty()) {
                    chosen = local.toArray(new Uri[0]);
                }
            } else {
                logLine("pick: cancelled or empty result");
            }
            if (pendingFileCallback != null) {
                pendingFileCallback.onReceiveValue(chosen);
                pendingFileCallback = null;
            }
            return;
        }
        super.onActivityResult(request, result, data);
    }

    /**
     * Copy a picked document into the app's own cache and hand the page a
     * file:// URI.
     *
     * A WebView can fail to read a content:// URI straight from the picker: the
     * page then receives an empty File and the upload silently does nothing,
     * with nothing in any log. Reading the stream here (the app already holds
     * storage access) and staging a local copy removes that dependency, and
     * leaves a name and byte count behind for the next diagnosis.
     */
    private Uri stagePickedFile(Uri source) {
        java.io.InputStream in = null;
        java.io.OutputStream out = null;
        try {
            String name = null;
            String mime = getContentResolver().getType(source);
            // Prefer the provider's display name: the raw path segment is often a
            // bare number, and the attachment pipeline keys on the file extension.
            android.database.Cursor cursor = null;
            try {
                cursor = getContentResolver().query(source, null, null, null, null);
                if (cursor != null && cursor.moveToFirst()) {
                    int column = cursor.getColumnIndex("_display_name");
                    if (column >= 0) {
                        name = cursor.getString(column);
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
            if (name == null || name.isEmpty()) {
                name = source.getLastPathSegment();
                if (name != null) {
                    int slash = name.lastIndexOf('/');
                    name = slash >= 0 ? name.substring(slash + 1) : name;
                }
            }
            if (name == null || name.isEmpty()) {
                name = "upload-" + System.currentTimeMillis();
            }
            if (name.indexOf('.') < 0 && mime != null) {
                name = name + "." + mime.substring(mime.indexOf('/') + 1).replace("jpeg", "jpg");
            }
            File dir = new File(getCacheDir(), "uploads");
            if (!dir.mkdirs() && !dir.isDirectory()) {
                logLine("pick: cannot create cache dir");
                return source;
            }
            File target = new File(dir, name);
            in = getContentResolver().openInputStream(source);
            if (in == null) {
                logLine("pick: no stream for " + source);
                return source;
            }
            out = new java.io.FileOutputStream(target);
            byte[] buf = new byte[1 << 16];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
            }
            out.flush();
            logLine("pick: staged " + name + " (" + total + " bytes)");
            return Uri.fromFile(target);
        } catch (Throwable t) {
            logLine("pick: staging failed, passing the original URI (" + t + ")");
            return source;
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (IOException ignored) {
            }
            try {
                if (out != null) {
                    out.close();
                }
            } catch (IOException ignored) {
            }
        }
    }

    @Override
    protected void onDestroy() {
        NodeServer.removeListener(this);
        super.onDestroy();
    }
}
