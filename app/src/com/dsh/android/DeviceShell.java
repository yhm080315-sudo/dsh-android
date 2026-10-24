package com.dsh.android;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Privileged device operations, resolved per capability instead of globally.
 *
 * Why per capability: the operations this app needs have different privileged
 * implementations with different availability. Tap/swipe/type can come from an
 * AccessibilityService (no install at all) or from `input` (Shizuku or root);
 * screen capture from MediaProjection (no install) or `screencap` (Shizuku or
 * root). Interface listing, app launching and dumpsys only exist on a shell. So
 * there is no single "privilege source": there is a routing table from
 * capability to backend, and this class owns it.
 *
 *     capture  -> Backend.capture()          (MediaProjection, else screencap)
 *     input    -> Backend.tap/swipe/key/text (accessibility, else `input`)
 *     ui       -> Backend.readUi()           (accessibility only)
 *     shell    -> Backend.exec()             (Shizuku / su only)
 *
 * A backend declares which capabilities it serves, and unserved operations
 * answer Result.unsupported(), never a silent success. Candidates are tried in
 * this order -- cheapest requirement first, root last, because the zero-install
 * path is what a fresh phone actually has:
 *
 *     accessibility / MediaProjection   -> user flips one system switch
 *     Shizuku (authorised, running)     -> install one app, grant once, uid 2000
 *     su / root                         -> uid 0, the highest bar
 *     none                              -> NO_PRIVILEGE with actionable guidance
 *
 * Per-operation fall-through: a channel that answers UNSUPPORTED (not failed)
 * hands the operation to the next channel. That is what lets accessibility
 * handle taps while `enter` still goes to `input keyevent`, and what keeps a
 * missing text field from turning into a dead end on a rooted phone.
 *
 * The chosen route per capability is logged once per routing change as
 * `device: ...` and exposed per capability for the console and /device/status.
 */
final class DeviceShell {

    private static final String TAG = "DSH";

    /**
     * Pins the routing for diagnostics and tests.
     *
     * Content is a comma-separated allow-list of backend ids (`shizuku`, `su`,
     * `accessibility`, `projection`); `none` or an empty file allows nothing.
     * Private-dir only, so the "no privilege" path can be exercised on a phone
     * that does have root without uninstalling anything.
     */
    private static final String FORCE_FILE = "device-shell-force";

    static final int CAP_CAPTURE = 1;
    static final int CAP_INPUT = 2;
    static final int CAP_SHELL = 4;
    /** Reading the active window's node tree; accessibility is the only source. */
    static final int CAP_UI = 8;
    static final int CAP_ALL = CAP_CAPTURE | CAP_INPUT | CAP_SHELL | CAP_UI;

    /**
     * "This backend cannot serve this operation", as opposed to "it tried and
     * failed". The router falls through to the next channel on this code only.
     */
    static final int UNSUPPORTED = 126;

    /** Guidance shown whenever a privileged operation has nowhere to run. */
    static final String NO_PRIVILEGE =
            "NO_PRIVILEGE: 没有可用的提权通道。/ No usable privilege channel.\n"
            + "  · 开启无障碍（点按 / 滑动 / 输入 / 读界面）：" + DshAccessibilityService.enablePath() + "\n"
            + "  · 授权投屏（截图）：控制台「投屏」那一行点一下，在系统弹窗里选「立即开始」\n"
            + "  · 其余能力要装 Shizuku（https://shizuku.rikka.app/）并授权，或用 Magisk/KernelSU 给本应用 root\n"
            + "Turn on Accessibility (Settings → Accessibility → Installed apps → DeepSeek Harness) "
            + "for tap/swipe/text, or authorise screen capture for screenshots; Shizuku or root is "
            + "needed for anything else.";

    // ------------------------------------------------------------------ result

    static final class Result {
        final int code;
        final byte[] out;
        final byte[] err;
        /** Backend id that produced this result: accessibility / MediaProjection / shizuku / su / none. */
        final String backend;
        /** Non-null when the operation could not run at all (no privilege, spawn error). */
        final String failure;

        Result(int code, byte[] out, byte[] err, String backend, String failure) {
            this.code = code;
            this.out = out == null ? new byte[0] : out;
            this.err = err == null ? new byte[0] : err;
            this.backend = backend;
            this.failure = failure;
        }

        static Result ok(byte[] out, byte[] err, String backend) {
            return new Result(0, out, err, backend, null);
        }

        static Result okText(String text, String backend) {
            return new Result(0, text.getBytes(java.nio.charset.Charset.forName("UTF-8")),
                    null, backend, null);
        }

        static Result fail(int code, String failure, String backend) {
            return new Result(code, null, null, backend, failure);
        }

        static Result unsupported(String what, String backend) {
            return new Result(UNSUPPORTED, null, null, backend,
                    "UNSUPPORTED: " + what + "（backend=" + backend + "）");
        }

        boolean ok() {
            return failure == null && code == 0;
        }

        boolean unsupported() {
            return code == UNSUPPORTED;
        }

        String text() {
            if (failure != null) {
                return failure;
            }
            String o = new String(out, 0, out.length);
            String e = new String(err, 0, err.length);
            if (o.length() == 0) {
                return e;
            }
            return e.length() == 0 ? o : o + "\n" + e;
        }

        String errText() {
            if (failure != null) {
                return failure;
            }
            return new String(err, 0, err.length);
        }
    }

    // --------------------------------------------------------------- backends

    /**
     * One way of actually performing privileged work. Only the capabilities a
     * backend returns true for in supports() are ever routed to it.
     */
    interface Backend {
        /** Stable id used in logs and /device/status. */
        String id();

        /** Human-readable identity of the uid the backend runs as, or why it cannot. */
        String detail();

        boolean supports(int capability);

        boolean available();

        Result capture();

        Result tap(int x, int y);

        Result swipe(int x1, int y1, int x2, int y2, int ms);

        Result key(String keycode);

        /** Raw text; a shell backend escapes it itself. */
        Result text(String value);

        /** Readable summary of the active window's node tree. */
        Result readUi(int maxNodes);

        /** Run one shell command line. Only CAP_SHELL backends are asked. */
        Result exec(String command, long timeoutMs);
    }

    /**
     * Shared plumbing for backends that own a shell: all operations become a
     * command line, and the only difference is how the process is started.
     */
    abstract static class ShellBackend implements Backend {
        private volatile String detail = "unprobed";

        String describeDetail() {
            return detail;
        }

        void setDetail(String value) {
            detail = value;
        }

        @Override
        public String detail() {
            return detail;
        }

        @Override
        public boolean supports(int capability) {
            return (capability & (CAP_CAPTURE | CAP_INPUT | CAP_SHELL)) != 0;
        }

        abstract Process start(String command) throws Exception;

        abstract boolean probe() throws Exception;

        @Override
        public Result capture() {
            // PNG straight to stdout: a temp file would have to be writable by the
            // shell uid, and the app's private dir is not (only su can write there).
            return run("screencap -p", 45000, true);
        }

        @Override
        public Result tap(int x, int y) {
            return run("input tap " + x + " " + y, 30000, false);
        }

        @Override
        public Result swipe(int x1, int y1, int x2, int y2, int ms) {
            return run("input swipe " + x1 + " " + y1 + " " + x2 + " " + y2 + " " + ms, 30000, false);
        }

        @Override
        public Result key(String keycode) {
            if (keycode == null || !keycode.matches("[A-Za-z0-9_]{1,40}")) {
                return Result.fail(2, "invalid key code", id());
            }
            return run("input keyevent " + keycode, 30000, false);
        }

        @Override
        public Result text(String value) {
            String typeable = shellText(value);
            if (typeable == null) {
                // `input text` has no non-ASCII path: say so instead of typing a
                // mangled prefix and reporting success.
                return Result.unsupported("`input text` 只能输 ASCII，这段文字要无障碍通道", id());
            }
            return run("input text " + typeable, 30000, false);
        }

        @Override
        public Result readUi(int maxNodes) {
            // The a11y tree is the only structured source; `uiautomator dump` would
            // add a second parser for the same data with worse output.
            return Result.unsupported("读界面结构", id());
        }

        @Override
        public Result exec(String command, long timeoutMs) {
            return run(command, timeoutMs, true);
        }

        Result run(String command, long timeoutMs, boolean binary) {
            Process process = null;
            try {
                process = start(command);
            } catch (Throwable t) {
                return Result.fail(-2, "cannot start privileged process (" + id() + "): " + t, id());
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            Thread to = pump(process.getInputStream(), out);
            Thread te = pump(process.getErrorStream(), err);
            int code = -1;
            boolean exited = false;
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (!exited) {
                try {
                    code = process.exitValue();
                    exited = true;
                } catch (IllegalThreadStateException alive) {
                    if (System.currentTimeMillis() > deadline) {
                        try {
                            process.destroy();
                        } catch (Throwable ignored) {
                        }
                        return new Result(-1, out.toByteArray(), err.toByteArray(), id(),
                                "timed out after " + timeoutMs + " ms: " + command);
                    }
                    try {
                        Thread.sleep(40);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            try {
                to.join(4000);
                te.join(4000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return new Result(code, out.toByteArray(), err.toByteArray(), id(), null);
        }

        private static Thread pump(final InputStream in, final ByteArrayOutputStream into) {
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    byte[] buf = new byte[16384];
                    try {
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            into.write(buf, 0, n);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }, "dsh-shell-pump");
            thread.setDaemon(true);
            thread.start();
            return thread;
        }
    }

    /**
     * `input text` splits on spaces and has no non-ASCII path, so the string is
     * filtered rather than quoted: a crafted value can never reach the shell.
     * Returns null when nothing typeable is left.
     */
    static String shellText(String value) {
        if (value == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ' ') {
                sb.append("%s");
            } else if (c == '%') {
                sb.append("%%");
            } else if (c > 0x20 && c < 0x7f && "\\'\"`$&|;<>(){}[]*?~!#\n".indexOf(c) < 0) {
                sb.append(c);
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** Runs commands as adb shell (uid 2000) through the Shizuku binder. */
    static final class ShizukuBackend extends ShellBackend {
        @Override
        public String id() {
            return "shizuku";
        }

        @Override
        Process start(String command) throws Exception {
            return rikka.shizuku.Shizuku.newProcess(
                    new String[]{"/system/bin/sh", "-c", command}, null, null);
        }

        @Override
        boolean probe() {
            try {
                if (!rikka.shizuku.Shizuku.pingBinder()) {
                    setDetail("service not running");
                    return false;
                }
                if (rikka.shizuku.Shizuku.checkSelfPermission()
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    setDetail("not authorised");
                    return false;
                }
                int uid = rikka.shizuku.Shizuku.getUid();
                setDetail("adb uid=" + uid);
                return true;
            } catch (Throwable t) {
                // Missing Shizuku, revoked permission, dead binder: degrade, never crash.
                setDetail("unavailable: " + t.getClass().getSimpleName());
                return false;
            }
        }

        @Override
        public boolean available() {
            return describeDetail().startsWith("adb uid=");
        }
    }

    /** Runs commands through `su -c`, i.e. a rooted device. */
    static final class SuBackend extends ShellBackend {
        @Override
        public String id() {
            return "su";
        }

        @Override
        Process start(String command) throws Exception {
            return new ProcessBuilder("/system/bin/su", "-c", command).start();
        }

        @Override
        boolean probe() {
            Result result = run("id", 8000, false);
            String text = result.text().trim();
            if (result.failure == null && result.code == 0 && text.contains("uid=0")) {
                setDetail("uid=0");
                return true;
            }
            setDetail("no root (" + (text.isEmpty() ? "su rc=" + result.code : text.replace('\n', ' ')) + ")");
            return false;
        }

        @Override
        public boolean available() {
            return "uid=0".equals(describeDetail());
        }
    }

    /**
     * Zero-install input channel: gestures and text through the in-app
     * AccessibilityService. Nothing routes here until the user enables the
     * service, so `available()` is the on/off switch.
     */
    static final class AccessibilityBackend implements Backend {
        @Override
        public String id() {
            return "accessibility";
        }

        @Override
        public String detail() {
            return DshAccessibilityService.connected() ? "服务已连接" : "服务未开启";
        }

        @Override
        public boolean supports(int capability) {
            return (capability & (CAP_INPUT | CAP_UI)) != 0;
        }

        @Override
        public boolean available() {
            return DshAccessibilityService.connected();
        }

        @Override
        public Result capture() {
            return Result.unsupported("截图（无障碍拿不到帧缓冲，要用投屏）", id());
        }

        @Override
        public Result tap(int x, int y) {
            return map(DshAccessibilityService.tap(x, y), "tap");
        }

        @Override
        public Result swipe(int x1, int y1, int x2, int y2, int ms) {
            return map(DshAccessibilityService.swipe(x1, y1, x2, y2, ms), "swipe");
        }

        @Override
        public Result key(String keycode) {
            return map(DshAccessibilityService.key(keycode), "key " + keycode);
        }

        @Override
        public Result text(String value) {
            return map(DshAccessibilityService.text(value), "text");
        }

        @Override
        public Result readUi(int maxNodes) {
            DshAccessibilityService.Outcome outcome = DshAccessibilityService.uiSummary(maxNodes);
            if (outcome.ok) {
                return Result.okText(outcome.message, id());
            }
            return outcome.unsupported
                    ? Result.unsupported(outcome.message, id())
                    : Result.fail(1, outcome.message, id());
        }

        @Override
        public Result exec(String command, long timeoutMs) {
            return Result.unsupported("shell 命令", id());
        }

        private Result map(DshAccessibilityService.Outcome outcome, String what) {
            if (outcome.ok) {
                return Result.ok(null, null, id());
            }
            if (outcome.unsupported) {
                return Result.unsupported(what + ": " + outcome.message, id());
            }
            return Result.fail(1, "accessibility " + what + " failed: " + outcome.message, id());
        }
    }

    /**
     * Zero-install capture: MediaProjection. Screenshots only -- it cannot inject
     * input -- so it serves CAP_CAPTURE and answers unsupported for everything
     * else, which lets input fall through to the next channel.
     */
    static final class ProjectionBackend implements Backend {
        @Override
        public String id() {
            return "MediaProjection";
        }

        @Override
        public String detail() {
            return ScreenProjection.status() + " " + ScreenProjection.sizeText();
        }

        @Override
        public boolean supports(int capability) {
            return (capability & CAP_CAPTURE) != 0;
        }

        @Override
        public boolean available() {
            return ScreenProjection.authorized();
        }

        @Override
        public Result capture() {
            byte[] png = ScreenProjection.capturePng();
            if (png == null || png.length == 0) {
                String reason = ScreenProjection.error();
                return Result.fail(1, "capture via MediaProjection failed: "
                        + (reason == null ? "unknown reason" : reason)
                        + "（控制台「投屏」那一行可以重新授权）", id());
            }
            return Result.ok(png, null, id());
        }

        @Override
        public Result tap(int x, int y) {
            return Result.unsupported("点按", id());
        }

        @Override
        public Result swipe(int x1, int y1, int x2, int y2, int ms) {
            return Result.unsupported("滑动", id());
        }

        @Override
        public Result key(String keycode) {
            return Result.unsupported("按键", id());
        }

        @Override
        public Result text(String value) {
            return Result.unsupported("输入文字", id());
        }

        @Override
        public Result readUi(int maxNodes) {
            return Result.unsupported("读界面结构（投屏只有像素）", id());
        }

        @Override
        public Result exec(String command, long timeoutMs) {
            return Result.unsupported("shell 命令", id());
        }
    }

    /** Last resort: refuses every operation with an actionable message. */
    static final class NoneBackend implements Backend {
        @Override
        public String id() {
            return "none";
        }

        @Override
        public String detail() {
            return "无通道（开无障碍 / 授权投屏 / Shizuku / root）";
        }

        @Override
        public boolean supports(int capability) {
            return true;
        }

        @Override
        public boolean available() {
            return true;
        }

        private Result refuse() {
            return Result.fail(127, NO_PRIVILEGE, "none");
        }

        @Override
        public Result capture() {
            return refuse();
        }

        @Override
        public Result tap(int x, int y) {
            return refuse();
        }

        @Override
        public Result swipe(int x1, int y1, int x2, int y2, int ms) {
            return refuse();
        }

        @Override
        public Result key(String keycode) {
            return refuse();
        }

        @Override
        public Result text(String value) {
            return refuse();
        }

        @Override
        public Result readUi(int maxNodes) {
            return refuse();
        }

        @Override
        public Result exec(String command, long timeoutMs) {
            return refuse();
        }
    }

    // ---------------------------------------------------------------- routing

    private static final Backend A11Y = new AccessibilityBackend();
    private static final Backend PROJECTION = new ProjectionBackend();
    private static final Backend SHIZUKU = new ShizukuBackend();
    private static final Backend SU = new SuBackend();
    private static final Backend NONE = new NoneBackend();

    /*
     * Candidate order per capability, cheapest requirement first. Root is last on
     * purpose: the zero-install channels are what an untouched phone has, and a
     * rooted phone must not hide whether accessibility / MediaProjection work
     * behind `su` doing everything.
     */
    private static Backend[] candidates(int capability) {
        if (capability == CAP_CAPTURE) {
            return new Backend[]{PROJECTION, SHIZUKU, SU, NONE};
        }
        if (capability == CAP_INPUT) {
            return new Backend[]{A11Y, SHIZUKU, SU, NONE};
        }
        if (capability == CAP_UI) {
            return new Backend[]{A11Y, NONE};
        }
        return new Backend[]{SHIZUKU, SU, NONE};
    }

    private static volatile Backend captureBackend = NONE;
    private static volatile Backend inputBackend = NONE;
    private static volatile Backend shellBackend = NONE;
    private static volatile Backend uiBackend = NONE;
    private static volatile String forced = null;
    /**
     * In-memory routing pin, set by /device/route. Volatile on purpose: a test
     * that restricts the channels must not survive a restart, or a forgotten pin
     * would leave the app crippled. It can only ever *remove* candidates.
     */
    private static volatile String pinOverride = null;
    private static volatile long lastProbeMs = 0;
    private static volatile boolean listening = false;
    private static volatile String lastLine = null;
    private static File filesDir;

    // Cached probe outcome, so a channel change re-routes without re-running `su`
    // (which can sit on a consent dialog for seconds).
    private static volatile boolean shizukuOk = false;
    private static volatile boolean suOk = false;

    private DeviceShell() {
    }

    /** Give the router its app context; safe to call repeatedly. */
    static synchronized void init(Context context, File dir) {
        if (dir != null) {
            filesDir = dir;
        }
        ScreenProjection.init(context, dir);
        // Never probe on the caller's thread: on a rooted phone `su` can sit on a
        // consent dialog for seconds, and init() runs from Activity.onCreate.
        Thread probe = new Thread(new Runnable() {
            @Override
            public void run() {
                refresh(true);
            }
        }, "dsh-shell-probe");
        probe.setDaemon(true);
        probe.start();
        if (!listening) {
            listening = true;
            try {
                // Sticky: Shizuku may hand the binder over before this line runs.
                rikka.shizuku.Shizuku.addBinderReceivedListenerSticky(
                        new rikka.shizuku.Shizuku.OnBinderReceivedListener() {
                            @Override
                            public void onBinderReceived() {
                                log("shizuku: binder received");
                                refresh(true);
                            }
                        });
            } catch (Throwable t) {
                log("shizuku: listener not registered (" + t + ")");
            }
        }
    }

    /**
     * Wait (briefly) for the first probe to land.
     *
     * The startup probe runs off the UI thread; the instructions writer needs the
     * routing to describe the device correctly, but must not hang if `su` is
     * sitting on a consent dialog. Bounded, and a timeout just means "unknown".
     */
    static void awaitProbe(long maxMs) {
        long deadline = System.currentTimeMillis() + maxMs;
        while (lastProbeMs == 0 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Resolve the primary backend for one capability (for status and probing). */
    static Backend pick(int capability) {
        Backend chosen = backendFor(capability);
        if (chosen != NONE) {
            return chosen;
        }
        if (System.currentTimeMillis() - lastProbeMs > 5000) {
            refresh(false);
            chosen = backendFor(capability);
        }
        return chosen;
    }

    private static Backend backendFor(int capability) {
        if (capability == CAP_CAPTURE) {
            return captureBackend;
        }
        if (capability == CAP_INPUT) {
            return inputBackend;
        }
        if (capability == CAP_UI) {
            return uiBackend;
        }
        return shellBackend;
    }

    /**
     * Re-probe the channels that can change under us, then re-route.
     *
     * `force` exists for diagnostics and tests; see FORCE_FILE. It is private-dir
     * only, and a pinned routing is logged as forced.
     */
    static synchronized void refresh(boolean force) {
        if (!force && System.currentTimeMillis() - lastProbeMs < 2000) {
            return;
        }
        lastProbeMs = System.currentTimeMillis();
        forced = effectivePin();
        boolean shizuku = false;
        boolean su = false;
        String shizukuDetail = null;
        String suDetail = null;
        if (allowed(SHIZUKU)) {
            shizuku = probeQuietly(SHIZUKU);
            shizukuDetail = SHIZUKU.detail();
        }
        if (!shizuku && allowed(SU)) {
            su = probeQuietly(SU);
            suDetail = SU.detail();
        }
        shizukuOk = shizuku;
        suOk = su;
        recompute();
        if (!shizuku && shizukuDetail != null && !"service not running".equals(shizukuDetail)
                && !shizukuDetail.startsWith("unavailable")) {
            log("shizuku: " + shizukuDetail);
        }
        if (!su && suDetail != null && forced == null) {
            log("su: " + suDetail);
        }
    }

    /**
     * Re-route from cached probe results and live accessibility/projection state.
     *
     * Called when the user enables the accessibility service or authorises the
     * projection: neither needs an `su` probe, and both should take effect
     * immediately rather than after the next full refresh.
     */
    static synchronized void recompute() {
        captureBackend = primary(CAP_CAPTURE);
        inputBackend = primary(CAP_INPUT);
        shellBackend = primary(CAP_SHELL);
        uiBackend = primary(CAP_UI);
        String summary = line();
        if (forced != null) {
            summary = summary + " [forced by " + FORCE_FILE + "="
                    + (forced.isEmpty() ? "none" : forced) + "]";
        }
        if (!summary.equals(lastLine)) {
            lastLine = summary;
            Log.i(TAG, summary);
            log(summary);
            for (String channel : channels()) {
                log("  " + channel);
            }
        }
    }

    /** The first usable backend for a capability, NONE at worst. */
    private static Backend primary(int capability) {
        Backend[] chain = candidates(capability);
        for (int i = 0; i < chain.length; i++) {
            if (usable(chain[i])) {
                return chain[i];
            }
        }
        return NONE;
    }

    /** The pinned allow-list gate. `forced == null` means "nothing is pinned". */
    private static boolean allowed(Backend backend) {
        String pin = forced;
        if (pin == null) {
            return true;
        }
        if (pin.isEmpty()) {
            return backend == NONE;
        }
        String[] parts = pin.split(",");
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].trim().equalsIgnoreCase(backend.id())) {
                return true;
            }
        }
        return backend == NONE;
    }

    private static boolean usable(Backend backend) {
        if (backend == NONE) {
            return true;
        }
        if (!allowed(backend)) {
            return false;
        }
        try {
            return backend.available();
        } catch (Throwable t) {
            log("probe " + backend.id() + " failed: " + t);
            return false;
        }
    }

    private static boolean probeQuietly(Backend backend) {
        try {
            if (backend instanceof ShellBackend) {
                return ((ShellBackend) backend).probe();
            }
            return backend.available();
        } catch (Throwable t) {
            log("probe " + backend.id() + " failed: " + t);
            return false;
        }
    }

    private static String effectivePin() {
        String live = pinOverride;
        return live != null ? live : readForced();
    }

    /**
     * Pin the routing for a test run, or clear it with `auto`/empty.
     *
     * This is how the four channel states are exercised on a phone that has root:
     * asking `su` to be unavailable is not something a test can do, but the app can
     * decide not to use it. Returns the resulting one-line routing.
     */
    static synchronized String setPin(String request) {
        if (request == null || request.trim().isEmpty()
                || request.trim().equalsIgnoreCase("auto")
                || request.trim().equalsIgnoreCase("off")) {
            pinOverride = null;
        } else {
            pinOverride = normalisePin(request);
        }
        refresh(true);
        return line();
    }

    private static String normalisePin(String request) {
        String value = request.trim().toLowerCase();
        if (value.equals("none") || value.equals("-")) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        String[] parts = value.split("[,\\s]+");
        for (int i = 0; i < parts.length; i++) {
            String id = canonical(parts[i]);
            if (id == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(id);
        }
        return sb.toString();
    }

    static boolean isPinned() {
        return effectivePin() != null;
    }

    /**
     * Parse the pinned allow-list. Returns null when nothing is pinned, "" when
     * nothing may be used, otherwise a comma-separated id list.
     */
    private static String readForced() {
        if (filesDir == null) {
            return null;
        }
        File file = new File(filesDir, FORCE_FILE);
        if (!file.isFile()) {
            return null;
        }
        try {
            byte[] buf = new byte[128];
            FileInputStream in = new FileInputStream(file);
            int n;
            try {
                n = in.read(buf);
            } finally {
                in.close();
            }
            String value = new String(buf, 0, Math.max(0, n), "UTF-8").trim().toLowerCase();
            if (value.isEmpty() || value.equals("none")) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            String[] parts = value.split("[,\\s]+");
            for (int i = 0; i < parts.length; i++) {
                String id = canonical(parts[i]);
                if (id == null) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(id);
            }
            return sb.toString();
        } catch (Throwable t) {
            log("cannot read " + FORCE_FILE + ": " + t);
        }
        return null;
    }

    private static String canonical(String token) {
        if (token.equals("shizuku")) {
            return "shizuku";
        }
        if (token.equals("su") || token.equals("root")) {
            return "su";
        }
        if (token.equals("accessibility") || token.equals("a11y")) {
            return "accessibility";
        }
        if (token.equals("projection") || token.equals("mediaprojection")
                || token.equals("screen")) {
            return "MediaProjection";
        }
        return null;
    }

    // ------------------------------------------------------------ operations

    /** One attempt at an operation on a given backend. */
    private interface Op {
        Result run(Backend backend);
    }

    /**
     * Walk the capability's candidate chain, falling through on UNSUPPORTED.
     *
     * A specific "there is a channel, it just cannot do this" answer wins over the
     * generic NO_PRIVILEGE, because both are true and only one is actionable.
     */
    private static Result route(int capability, Op op) {
        Backend[] chain = candidates(capability);
        Result unsupported = null;
        for (int i = 0; i < chain.length; i++) {
            Backend backend = chain[i];
            if (!usable(backend)) {
                continue;
            }
            Result result = op.run(backend);
            if (result == null) {
                continue;
            }
            if (result.unsupported()) {
                unsupported = result;
                continue;
            }
            if (backend == NONE && unsupported != null) {
                return unsupported;
            }
            return result;
        }
        return Result.fail(127, NO_PRIVILEGE, "none");
    }

    static Result capture() {
        return route(CAP_CAPTURE, new Op() {
            @Override
            public Result run(Backend backend) {
                return backend.capture();
            }
        });
    }

    static Result tap(int x, int y) {
        return route(CAP_INPUT, new Op() {
            @Override
            public Result run(Backend backend) {
                return backend.tap(x, y);
            }
        });
    }

    static Result swipe(int x1, int y1, int x2, int y2, int ms) {
        return route(CAP_INPUT, new Op() {
            @Override
            public Result run(Backend backend) {
                return backend.swipe(x1, y1, x2, y2, ms);
            }
        });
    }

    static Result key(String keycode) {
        return route(CAP_INPUT, new Op() {
            @Override
            public Result run(Backend backend) {
                return backend.key(keycode);
            }
        });
    }

    /** Raw text: the accessibility channel takes Unicode, a shell one filters it. */
    static Result text(String value) {
        return route(CAP_INPUT, new Op() {
            @Override
            public Result run(Backend backend) {
                return backend.text(value);
            }
        });
    }

    static Result readUi(int maxNodes) {
        return route(CAP_UI, new Op() {
            @Override
            public Result run(Backend backend) {
                return backend.readUi(maxNodes);
            }
        });
    }

    static Result exec(String command, long timeoutMs) {
        Result result = pick(CAP_SHELL).exec(command, timeoutMs);
        if (result.failure != null && result.code == 127
                && System.currentTimeMillis() - lastProbeMs > 30000) {
            // A grant that arrived after startup must be picked up without a restart,
            // without making every refused command pay for an `su` probe.
            refresh(true);
        }
        return result;
    }

    static boolean isRoot() {
        return backendFor(CAP_SHELL) == SU;
    }

    static boolean isShizuku() {
        return backendFor(CAP_SHELL) == SHIZUKU;
    }

    static boolean accessibilityOn() {
        return DshAccessibilityService.connected();
    }

    static boolean projectionOn() {
        return ScreenProjection.authorized();
    }

    /**
     * A zero-install channel changed state (accessibility on/off, projection
     * authorised/stopped). Re-route without probing `su`, then log it.
     */
    static void onAccessibilityChanged(String why) {
        log(why);
        recompute();
    }

    static void onProjectionChanged(String why) {
        log(why);
        recompute();
    }

    /** Ask Shizuku to show its permission dialog. Must be called from an Activity. */
    static String requestShizukuPermission(int requestCode) {
        try {
            if (!rikka.shizuku.Shizuku.pingBinder()) {
                return "Shizuku 服务没在运行：先打开 Shizuku，用 root 或无线调试启动服务。";
            }
            if (rikka.shizuku.Shizuku.checkSelfPermission()
                    == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                refresh(true);
                return "Shizuku 已经授权：" + line();
            }
            rikka.shizuku.Shizuku.requestPermission(requestCode);
            return "已向 Shizuku 发送授权请求，请在弹窗里点「允许」。";
        } catch (Throwable t) {
            return "Shizuku 授权请求失败：" + t;
        }
    }

    static void onShizukuPermissionResult(int requestCode, int grantResult) {
        boolean granted = grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED;
        log("shizuku: permission result request=" + requestCode + " granted=" + granted);
        refresh(true);
    }

    // --------------------------------------------------------------- status

    /**
     * Per-capability detail, the same wording the console shows. Kept as a list so
     * a mixed table (MediaProjection + accessibility + su) renders as-is.
     */
    static List<String> channels() {
        List<String> out = new ArrayList<String>(4);
        out.add("capture=" + describe(CAP_CAPTURE));
        out.add("input=" + describe(CAP_INPUT));
        out.add("shell=" + describe(CAP_SHELL));
        out.add("ui=" + describe(CAP_UI));
        return out;
    }

    private static String describe(int capability) {
        Backend backend = backendFor(capability);
        return backend.id() + "（" + backend.detail() + "）";
    }

    /** The compact line: `device: capture=MediaProjection | input=accessibility | shell=su`. */
    static String line() {
        return "device: capture=" + backendFor(CAP_CAPTURE).id()
                + " | input=" + backendFor(CAP_INPUT).id()
                + " | shell=" + backendFor(CAP_SHELL).id()
                + " | ui=" + backendFor(CAP_UI).id();
    }

    /** Short console label: what the input channel actually is right now. */
    static String label() {
        Backend input = backendFor(CAP_INPUT);
        if (input == A11Y) {
            return "无障碍 ✓（零安装，点按/滑动/输入可用）";
        }
        if (input == SHIZUKU) {
            return "Shizuku ✓（" + input.detail() + "）";
        }
        if (input == SU) {
            return "root ✓（uid=0）";
        }
        return "无 ✗（点这里去看怎么开）";
    }

    static String hint() {
        if (accessibilityOn()) {
            return "点按 / 滑动 / 输入走无障碍（不用装东西）；截图"
                    + (projectionOn() ? "走投屏。" : "还要点「投屏：未授权」授权一次。");
        }
        return "零安装：设置 → 无障碍 → 已安装的应用 → DeepSeek Harness，开完点按/输入就能用；"
                + "截图再点控制台的「投屏：未授权」授权一次。"
                + "要 shell / 启动应用再装 Shizuku（shizuku.rikka.app）或给 root。";
    }

    static void log(String message) {
        Log.i(TAG, message);
        if (filesDir != null) {
            DiagnosticServer.appendLog(filesDir, message);
        }
    }
}
