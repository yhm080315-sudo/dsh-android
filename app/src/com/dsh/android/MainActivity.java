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
    /** Vertical veil over the photo: keeps light text readable while leaving the
     *  picture bright. Applied above the image, below everything else. */
    private View veilView;
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
    /**
     * Time the page gets to answer the readiness probe after a resume. Five
     * seconds is well past a loopback load, and short enough that a blank
     * console heals itself before the user has to do anything about it.
     */
    private static final long PAGE_READY_DELAY_MS = 5000;
    /**
     * Failures a URL is allowed before it is reported as an error rather than a
     * retry. The page retries the flaky kinds itself -- the boot animation's
     * media and the plugin event stream -- so one failure there is normal
     * traffic; reporting it as a startup failure sent the P18 investigation
     * after a bug that was not there.
     */
    private static final int WEB_RETRY_LIMIT = 3;
    /**
     * Failures per URL since the current page load started (cleared by
     * onPageStarted). Scoped to one page load on purpose: a URL that fails once
     * per reload is flaky and must not accumulate into a permanent verdict.
     */
    private final java.util.Map<String, Integer> webFailures
            = new java.util.HashMap<String, Integer>();
    /** URL whose client-graph handshake is in flight or already done. */
    private String pageCheckUrl;
    /** One self-heal reload per resume; see checkPageReady. */
    private boolean webReloadTried;
    /** Invalidates the readiness probe of a resume that is no longer current. */
    private int readyCheckGeneration;

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
        DeviceServer.setContext(this);
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
        statusView.setText(getString(R.string.checking_for_updates));
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
                        updateButton.setText(getString(R.string.install_update, apk.getName()));
                        statusView.setText(getString(R.string.update_ready, apk.getName()));
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
                        statusView.setText(getString(R.string.update_failed, reason));
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
                        statusView.setText(getString(R.string.self_check_finished));
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

    /**
     * iOS 26-ish "liquid glass" surface: a translucent body, a top sheen and a
     * bright rim, all sharing one large corner radius.
     *
     * Android cannot backdrop-blur the content behind a view (RenderEffect needs
     * API 31, and this app targets 28), so the frosted look is approximated with
     * layers: a translucent body showing the photo through, a white-to-transparent
     * vertical gradient standing in for the specular highlight, and a thin bright
     * stroke for the rim light. Stacked and rounded together, the three read as one
     * piece of glass rather than three rectangles.
     */
    /**
     * One piece of iOS-26-style glass, painted directly.
     *
     * A stack of GradientDrawables could not express what makes the material read
     * as glass: the sheen has to be concentrated in the top half (a full-height
     * gradient just looks washed out), the rim has to be bright at the top and fade
     * towards the bottom, and there is a faint inner shading along the lower edge.
     * Those are stop positions and per-edge alphas, so this paints them by hand.
     */
    private static final class GlassDrawable extends android.graphics.drawable.Drawable {
        private final android.graphics.Paint body = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint sheen = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint shade = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint rim = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final float radius;
        private final float rimWidth;
        private final int bodyColor;
        private final boolean pressed;

        GlassDrawable(int bodyColor, float radius, float rimWidth, boolean pressed) {
            // Pressing lifts the whole surface, not just its highlights: the body
            // gains white as well, which is what makes it read as "lit up".
            this.bodyColor = pressed ? brighter(bodyColor) : bodyColor;
            this.radius = radius;
            this.rimWidth = rimWidth;
            this.pressed = pressed;
            body.setStyle(android.graphics.Paint.Style.FILL);
            sheen.setStyle(android.graphics.Paint.Style.FILL);
            shade.setStyle(android.graphics.Paint.Style.FILL);
            rim.setStyle(android.graphics.Paint.Style.STROKE);
            rim.setStrokeWidth(rimWidth);
        }

        /** Add ~14% white to a colour, keeping its alpha. */
        private static int brighter(int color) {
            int a = (color >>> 24) & 0xFF;
            int r = Math.min(255, (int) (((color >> 16) & 0xFF) * 0.86f + 255 * 0.14f));
            int g = Math.min(255, (int) (((color >> 8) & 0xFF) * 0.86f + 255 * 0.14f));
            int b = Math.min(255, (int) ((color & 0xFF) * 0.86f + 255 * 0.14f));
            return (a << 24) | (r << 16) | (g << 8) | b;
        }

        @Override
        public void draw(android.graphics.Canvas canvas) {
            android.graphics.RectF r = new android.graphics.RectF(
                    rimWidth / 2f, rimWidth / 2f,
                    getBounds().width() - rimWidth / 2f, getBounds().height() - rimWidth / 2f);
            canvas.save();
            canvas.translate(getBounds().left, getBounds().top);

            // 1) the body: translucent, so the photo shows through
            body.setColor(bodyColor);
            canvas.drawRoundRect(r, radius, radius, body);

            // 2) specular sheen: concentrated in the top 45%, fading out fast
            sheen.setShader(new android.graphics.LinearGradient(
                    0, r.top, 0, r.bottom,
                    new int[]{pressed ? 0x5CFFFFFF : 0x3DFFFFFF, 0x14FFFFFF, 0x08FFFFFF, 0x00FFFFFF},
                    new float[]{0f, 0.32f, 0.62f, 1f},
                    android.graphics.Shader.TileMode.CLAMP));
            canvas.drawRoundRect(r, radius, radius, sheen);
            sheen.setShader(null);

            // 3) faint inner shading along the lower edge, like real glass
            shade.setShader(new android.graphics.LinearGradient(
                    0, r.top + r.height() * 0.55f, 0, r.bottom,
                    new int[]{0x00000000, 0x14000000},
                    null, android.graphics.Shader.TileMode.CLAMP));
            canvas.drawRoundRect(r, radius, radius, shade);
            shade.setShader(null);

            // 4) rim light: brighter at the top, dimmer at the bottom
            rim.setShader(new android.graphics.LinearGradient(
                    0, r.top, 0, r.bottom,
                    new int[]{pressed ? 0xA6FFFFFF : 0x73FFFFFF, 0x2EFFFFFF},
                    null, android.graphics.Shader.TileMode.CLAMP));
            canvas.drawRoundRect(r, radius, radius, rim);
            rim.setShader(null);

            canvas.restore();
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter filter) {
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    /** Glass that brightens under the finger, like the real material. */
    private android.graphics.drawable.Drawable glassSurface(int bodyColor, float radiusDp) {
        float d = getResources().getDisplayMetrics().density;
        float r = radiusDp * d;
        float rimW = Math.max(1f, d);
        final GlassDrawable normal = new GlassDrawable(bodyColor, r, rimW, false);
        final GlassDrawable down = new GlassDrawable(bodyColor, r, rimW, true);
        android.graphics.drawable.StateListDrawable states = new android.graphics.drawable.StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, down);
        states.addState(new int[]{}, normal);
        return states;
    }

    /** Capsule radius for buttons: half the height, like the iOS 26 control shapes. */
    private float capsuleRadius() {
        return 23f;
    }

    private Button action(String label, boolean primary) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(15f);
        b.setTextColor(primary ? 0xFFFFFFFF : theme.text);
        b.setTypeface(android.graphics.Typeface.DEFAULT, primary
                ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        // Tinted glass for the primary action, neutral glass for the rest.
        // Tinted glass for the primary action, neutral glass for the rest. The body
        // is deliberately light: on a dark photo, translucent white reads as frosted.
        b.setBackground(glassSurface(primary
                ? (alpha(theme.primary) & 0x00FFFFFF) | 0x4D000000
                : 0x1FFFFFFF, capsuleRadius()));
        b.setPadding(0, 0, 0, 0);
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            b.setElevation(6 * getResources().getDisplayMetrics().density);
        }
        b.setShadowLayer(6 * getResources().getDisplayMetrics().density, 0, 0, 0x66000000);
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
        veilView = new View(this);
        veilView.setVisibility(View.GONE);
        root.addView(veilView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        backgroundView = new ImageView(this);
        backgroundView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        // Re-add the veil so it sits ABOVE the image (FrameLayout stacks in
        // insertion order, and the image was added after the veil above).
        root.removeView(veilView);
        root.addView(veilView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        backgroundView.setVisibility(View.GONE);
        // Above the veil: FrameLayout stacks in insertion order, so add the image
        // first and re-add the veil on top of it.
        root.addView(backgroundView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        logPanel = new LinearLayout(this);
        logPanel.setOrientation(LinearLayout.VERTICAL);
        // No background here. This container holds the whole console, so an opaque
        // fill covered most of the photo *and* was the blue block visible below the
        // log card. The photo is the background; everything on top of it is glass.
        logPanel.setBackgroundColor(0x00000000);
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
            version.setText(getString(R.string.version, info.versionName));
        } catch (Throwable t) {
            version.setText(getString(R.string.version_unknown));
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
        statusView.setBackground(glassSurface((alpha(theme.card) & 0x00FFFFFF) | 0x42000000, 18f));
        int sp = (int) (12 * d);
        statusView.setPadding(sp, sp, sp, sp);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.bottomMargin = (int) (16 * d);
        statusView.setText(getString(R.string.starting_runtime));
        logPanel.addView(statusView, slp);

        resumeButton = action(getString(R.string.back_to_app), true);
        resumeButton.setVisibility(View.GONE);
        resumeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showWeb();
            }
        });
        logPanel.addView(resumeButton);

        Button bgButton = action(getString(R.string.change_background), false);
        bgButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickBackground();
            }
        });
        logPanel.addView(bgButton);

        rootButton = action(getString(R.string.grant_root_access), false);
        rootButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                logLine("root status: bridge=" + DeviceServer.currentPort()
                        + " root=" + DeviceServer.rootAvailable());
                reportRoot(true);
            }
        });
        logPanel.addView(rootButton);

        storageButton = action(getString(R.string.grant_file_access), false);
        storageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestStorageAccess();
                reportStorageAccess();
            }
        });
        logPanel.addView(storageButton);

        probeButton = action(getString(R.string.run_self_check), false);
        probeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runProbe();
            }
        });
        logPanel.addView(probeButton);

        updateButton = action(getString(R.string.check_for_updates), false);
        updateButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkForUpdate();
            }
        });
        logPanel.addView(updateButton);

        Button copy = action(getString(R.string.copy_log), false);
        copy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                        getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("dsh log", logView.getText()));
                statusView.setText(getString(R.string.log_copied));
            }
        });
        logPanel.addView(copy);

        TextView logLabel = new TextView(this);
        logLabel.setText(getString(R.string.run_log));
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
        // Light text over glass over a photo needs its own contrast: the glass is
        // deliberately too transparent to provide it.
        logView.setShadowLayer(4f, 0f, 1f, 0x99000000);
        logView.setTypeface(android.graphics.Typeface.MONOSPACE);
        logView.setLineSpacing(0f, 1.15f);
        logView.setTextIsSelectable(true);
        // Same glass as the buttons: on a photo, a flat dark card is what made the
        // log look like a different app bolted onto this screen.
        // Same family as the buttons (translucent white), not the dark card colour --
        // "consistent with the buttons above" is exactly this: one material, not two.
        logView.setBackground(glassSurface(0x1FFFFFFF, 20f));
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            logView.setElevation(4 * getResources().getDisplayMetrics().density);
        }
        logView.setPadding(sp, sp, sp, sp);
        logView.setMovementMethod(new ScrollingMovementMethod());
        ScrollView logCard = new ScrollView(this);
        // Transparent wrapper: the glass is painted by logView inside it. Leaving the
        // old opaque card here is what kept the log a dark block while every other
        // surface had already turned to glass.
        logCard.setBackgroundColor(0x00000000);
        logCard.setClipToPadding(false);
        logCard.addView(logView);
        logPanel.addView(logCard, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        /*
         * Cap the content width. Everything here is in dp, so the console already
         * scales across phone sizes and densities -- but there was no upper bound,
         * so on a tablet or in landscape the buttons stretched across the whole
         * width and the log lines became unreadably long. On a phone the cap is
         * never reached and this changes nothing.
         */
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int maxContent = (int) (640 * d);
        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(
                Math.min(screenWidth, maxContent), ViewGroup.LayoutParams.MATCH_PARENT);
        panelLp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        root.addView(logPanel, panelLp);

        // a chip that brings the log back while the UI is on screen
        logChip = new TextView(this);
        logChip.setText(getString(R.string.log));
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
                        // Lift the photo itself instead of dimming it: a plain alpha
                        // over the dark base was what made every wallpaper look dull.
                        android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix();
                        // A POSITIVE offset lifts the midtones. The first version used
                        // 255*(1-brightness), which is negative for any boost -- it
                        // brightened only the highlights and made midtones DARKER
                        // (128 * 1.14 - 35.7 = 110). That is why every wallpaper still
                        // looked dim.
                        float lift = 255f * (theme.brightness - 1f);
                        cm.set(new float[]{
                                theme.brightness, 0, 0, 0, lift,
                                0, theme.brightness, 0, 0, lift,
                                0, 0, theme.brightness, 0, lift,
                                0, 0, 0, 1, 0
                        });
                        backgroundView.setColorFilter(
                                new android.graphics.ColorMatrixColorFilter(cm));
                        // Top-heavy veil: enough contrast for the title and status at
                        // the top, almost nothing by the time the log reaches the
                        // bottom, so the photo stays bright where it can be seen.
                        android.graphics.drawable.GradientDrawable veil =
                                new android.graphics.drawable.GradientDrawable(
                                        android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                                        new int[]{0x8C000000, 0x3D000000, 0x0F000000});
                        veilView.setBackground(veil);
                        veilView.setAlpha(Math.max(0f, Math.min(1f, theme.veil)));
                        veilView.setVisibility(View.VISIBLE);
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
        /*
         * Coming back to the foreground is a view event, not a harness event.
         *
         * The harness may be running, adopted, or being started right now; none
         * of that decides whether this Activity's WebView ever navigated. It was
         * decided by the service's callbacks alone, and when the service found a
         * harness already running it reported nothing, so a recreated Activity
         * kept a WebView that had never loaded anything -- a blank console until
         * the user cleared the app. Load the view on resume, always, and let the
         * probe below catch a page that still does not come up.
         */
        webReloadTried = false;
        ensureViewLoaded("resume");
        final int generation = ++readyCheckGeneration;
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (generation == readyCheckGeneration) {
                    checkPageReady();
                }
            }
        }, PAGE_READY_DELAY_MS);
    }

    // ------------------------------------------------------ view loading (P20)

    /**
     * Make sure the WebView is showing the GUI, whatever the harness did.
     *
     * The URL is read from the service rather than waited for: a harness that is
     * already running will not report itself again, and an Activity that only
     * listens for that report never loads its view.
     */
    private void ensureViewLoaded(String trigger) {
        if (diagnostics) {
            return;
        }
        String url = NodeServer.currentUrl();
        if (url == null) {
            url = pendingUrl;
        }
        if (url == null) {
            logLine("view load deferred: no harness url yet (" + trigger + ")");
            return;
        }
        requestViewLoad(url, trigger);
    }

    /**
     * Load `url`'s page unless this WebView is already on it.
     *
     * The log says which of the two happened, because "nothing to do" left no
     * way to tell a console that was already loaded from one that never would
     * be.
     */
    private void requestViewLoad(final String url, String trigger) {
        if (diagnostics || url == null) {
            return;
        }
        if (webAttached && web != null) {
            String gui = guiPrefix(url);
            String current = web.getUrl();
            if (current != null && current.startsWith(gui)) {
                logLine("view load skipped: already at " + current + " (" + trigger + ")");
                return;
            }
            loadView(url, trigger);
            return;
        }
        if (url.equals(pageCheckUrl)) {
            logLine("view load pending: page handshake for " + url
                    + " is already in flight (" + trigger + ")");
            return;
        }
        pageCheckUrl = url;
        logLine("view load scheduled: " + url + " (" + trigger + ")");
        awaitClientGraphAndAttach(url);
    }

    /** Navigate and record it; every load should be visible in the log. */
    private void loadView(String url, String trigger) {
        /*
         * The load is what the handshake guard was waiting for, so it ends here.
         *
         * pageCheckUrl was set when the handshake started and never cleared, so
         * the guard that exists to stop a second handshake also stopped every
         * later one: once the first load succeeded, each following resume matched
         * the stale value and returned "already in flight". A recreated Activity
         * then kept a WebView that had never navigated -- a blank console, and
         * the more the user switched away and back, the more certain it was.
         */
        pageCheckUrl = null;
        web.loadUrl(url);
        logLine("view load performed: " + url + " (" + trigger + ")");
    }

    /**
     * The part of `url` that identifies the app: scheme, host and port. The
     * token lives in the query string and the client rewrites the path as the
     * user navigates, so only this prefix can be compared.
     */
    private static String guiPrefix(String url) {
        int scheme = url.indexOf("//");
        int slash = url.indexOf('/', scheme < 0 ? 0 : scheme + 2);
        return slash < 0 ? url : url.substring(0, slash + 1);
    }

    /**
     * White-screen self-heal: ask the page whether it booted.
     *
     * A blank console after a resume is the page never having loaded, and the
     * app had no way to notice -- it logged nothing and waited for the user to
     * clear the app. The served document carries the boot manifest as
     * `window.__DSH_BOOT__`; a WebView that never navigated cannot answer with
     * it. One reload per resume, so a genuinely broken page cannot loop.
     */
    private void checkPageReady() {
        if (diagnostics) {
            return;
        }
        if (!webAttached || web == null) {
            logLine("web[ready] no page attached " + (PAGE_READY_DELAY_MS / 1000)
                    + "s after resume; view load re-checked");
            ensureViewLoaded("ready-check");
            return;
        }
        /*
         * A page that is still fetching is not the failure this probe looks for:
         * reloading it would restart a load that is going fine, and add back the
         * race the handshake above exists to avoid. The page's own document says
         * which it is -- `__DSH_BOOT__` is injected into the served HTML, so a
         * document that finished loading without it is a blank console, while a
         * document that reports `ready=loading` is simply not there yet.
         */
        try {
            web.evaluateJavascript(
                    "(function(){try{return (window.__DSH_BOOT__?\"boot\":\"noboot\")"
                    + "+\" ready=\"+(document.readyState||\"?\")"
                    + "+\" nodes=\"+(document.body?document.body.childElementCount:-1);}"
                    + "catch(e){return \"err:\"+e;}})()",
                    new android.webkit.ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String value) {
                            onPageReadyProbe(value);
                        }
                    });
        } catch (Throwable t) {
            logLine("web[ready] probe failed: " + t);
        }
    }

    private void onPageReadyProbe(String value) {
        String answer = value == null ? "null" : value.replace("\"", "").replace("\\", "");
        logLine("web[ready] probe " + answer + " at=" + stamp());
        if (answer.startsWith("boot")) {
            return;
        }
        if (webReloadTried) {
            logLine("web[reload] the page is still blank after the reload; not retrying");
            return;
        }
        if (answer.contains("ready=loading")) {
            logLine("web[ready] the document is still loading; not reloading it");
            return;
        }
        webReloadTried = true;
        if (webAttached && web != null) {
            web.reload();
            logLine("web[reload] no boot manifest " + (PAGE_READY_DELAY_MS / 1000)
                    + "s after resume; reloaded once");
        }
    }

    /** Wall clock for a log line: `t+…ms` alone cannot be aligned across logs. */
    private static String stamp() {
        return new java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
                .format(new java.util.Date());
    }

    // ----------------------------------------------- web failure account (P18)

    /** Count this failure and return which attempt it is, since the page started. */
    private int countFailure(String url) {
        Integer seen = webFailures.get(url);
        int attempt = (seen == null ? 0 : seen) + 1;
        webFailures.put(url, attempt);
        return attempt;
    }

    /**
     * Resources the page keeps retrying on its own.
     *
     * A failed request hands the app no content type -- `onReceivedError` reports
     * an error code, not a response -- so the endpoint has to be recognised by
     * URL. Both patterns are the ones this app has measured failing from the
     * reload race: the boot animation's media stream and the plugin event stream
     * (`/plugins/events` is an EventSource, which reconnects by itself).
     */
    private static boolean retryableResource(String url) {
        String lower = url.toLowerCase(java.util.Locale.US);
        if (lower.contains("/dsh-boot-animation/media/") || lower.contains("/plugins/events")) {
            return true;
        }
        int query = lower.indexOf('?');
        String path = query < 0 ? lower : lower.substring(0, query);
        return path.endsWith(".mp4") || path.endsWith(".webm") || path.endsWith(".m4v")
                || path.endsWith(".mov") || path.endsWith(".mp3") || path.endsWith(".m4a")
                || path.endsWith(".ogg") || path.endsWith(".wav") || path.endsWith(".aac")
                || path.endsWith(".flac");
    }

    /**
     * The harness serves its shell HTML as soon as the web carrier is up, but the
     * client plugin graph is composed asynchronously and injected into that HTML.
     * Loading the page too early yields "HTML did not preload
     * @deepseek-ai/dsh-client-modules/client.js" in the browser, so poll the page
     * until the bootstrap entry is actually present.
     */
    private void awaitClientGraphAndAttach(final String url) {
        /*
         * The "already in flight" guard lives in requestViewLoad, which owns
         * pageCheckUrl. Repeating it here cancelled it out: requestViewLoad sets
         * the field and then calls this method, so the check matched the value it
         * had just written and the handshake never started -- the console stayed
         * on screen with a live harness behind it and a known URL in the status
         * bar, which looks exactly like a hang.
         */
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
            statusView.setText(getString(R.string.diagnostic_runtime_ready, url));
            return;
        }
        if (webAttached) {
            if (web != null) {
                loadView(url, "attach");
            } else {
                pageCheckUrl = null;
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
            /**
             * One page load, one set of failure counts.
             *
             * The failures this file records are almost all transient, and a
             * URL that fails once per reload would otherwise climb past the
             * retry limit after a few reloads and be reported as a dead
             * resource -- a wrong verdict built out of unrelated events.
             */
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                webFailures.clear();
            }

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
                 *
                 * One failure is not a verdict (P18). The boot animation's media and
                 * the plugin event stream are retried by the page itself, twice in a
                 * reload in the measurements on this device, and calling each attempt
                 * a "load-fail" pointed every investigation at a failure that healed
                 * by itself. A URL is only reported as an error once its attempts are
                 * spent; until then it is a retry, at info level.
                 */
                String url = request.getUrl() == null ? "?" : request.getUrl().toString();
                boolean main = request.isForMainFrame();
                int attempt = countFailure(url);
                long since = webAttachedAt == 0 ? -1 : System.currentTimeMillis() - webAttachedAt;
                String where = "at=" + stamp() + " main=" + main + " t+" + since + "ms " + url
                        + " code=" + (error == null ? "?" : error.getErrorCode())
                        + " " + (error == null ? "" : error.getDescription())
                        + " attempt " + attempt + "/" + WEB_RETRY_LIMIT;
                if (main) {
                    // The page itself never loaded: that is a blank console, not a retry.
                    logLine("web[load-fail] " + where + " (main frame)");
                } else if (retryableResource(url)) {
                    // Retried by the page; kept out of the startup failure count.
                    logLine("web[retry] " + where + (attempt < WEB_RETRY_LIMIT
                            ? " (media/event stream: retried by the page)"
                            : " (media/event stream: retries exhausted, not a startup failure)"));
                } else if (attempt < WEB_RETRY_LIMIT) {
                    logLine("web[retry] " + where + " (retried by the page)");
                } else if (attempt == WEB_RETRY_LIMIT) {
                    logLine("web[load-fail] " + where + " (retries exhausted)");
                } else {
                    logLine("web[retry] " + where + " (still failing; already reported at "
                            + WEB_RETRY_LIMIT + "/" + WEB_RETRY_LIMIT + ")");
                }
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
        loadView(url, "attach");
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
                    String url = pendingUrl;
                    pendingUrl = null;
                    // Through the same door as every other load: the client graph
                    // has to exist before the page is shown, or the shell reports
                    // "HTML did not preload …/client.js" instead of the console.
                    requestViewLoad(url, "service log");
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
                statusView.setText(getString(R.string.runtime_ready, url));
                statusView.setTextColor(theme.ok);
                // The service reports the endpoint on a worker thread; the view is
                // this Activity's own business, so the load is driven from here.
                requestViewLoad(url, "service ready");
            }
        });
    }

    @Override
    public void onExit(final int code) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                statusView.setText(getString(R.string.runtime_exited, code));
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
        // A probe queued for this resume must not touch a torn-down WebView.
        readyCheckGeneration++;
        /*
         * Destroy the WebView with the Activity.
         *
         * An explicitly started Activity ("am start -n") can pile up instances,
         * each holding its own WebView and its renderer; after five or six of them
         * the console degenerated into an empty session -- a white screen. Reusing
         * the instance (launchMode=singleTask) is the cure; this makes the leak
         * survivable if an instance is ever torn down again.
         */
        if (web != null) {
            try {
                web.destroy();
            } catch (Throwable ignored) {
            }
            web = null;
            webAttached = false;
        }
        NodeServer.removeListener(this);
        super.onDestroy();
    }
}
