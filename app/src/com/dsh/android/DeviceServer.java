package com.dsh.android;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;

/**
 * Loopback HTTP bridge that lets the bundled runtime (and therefore the agent
 * running inside the app) capture the screen and inject input events.
 *
 * Why it lives in the app: capturing the framebuffer and injecting events need
 * CAPTURE_VIDEO_OUTPUT / INJECT_EVENTS, which are signature-level permissions an
 * ordinary app can never hold. The work is delegated to DeviceShell, which routes
 * each capability to Shizuku (adb shell uid 2000, no root needed), to `su`, or to
 * a refusal with instructions -- see that class for the routing table.
 *
 * Security posture: bound to 127.0.0.1, token-authenticated, and every argument is strictly
 * validated so no shell metacharacter reaches the command line, and there is
 * deliberately no "run arbitrary command" endpoint.
 */
final class DeviceServer {

    /**
     * Application context, injected by whoever starts the server.
     *
     * The bridge needs it to start a chooser Activity for /device/open. The server
     * itself is a plain object holding no Context, so this is set once at startup.
     */
    private static android.content.Context appContext;

    static void setContext(android.content.Context context) {
        appContext = context.getApplicationContext();
    }

    private static final String TAG = "DSH";
    private static final int[] PORTS = {3090, 3091, 3092, 3093, 3094, 3095, 3096};
    private static final int MAX_BODY = 8 * 1024 * 1024;

    private static volatile DeviceServer instance;

    private final File filesDir;
    private volatile int port = -1;
    private final String token;

    private DeviceServer(File filesDir) {
        this.filesDir = filesDir;
        this.token = loadOrCreateToken(filesDir);
    }

    /** Idempotent: a WebView reload recreates the activity but must not rebind. */
    static synchronized void startOnce(File filesDir) {
        if (instance != null) {
            return;
        }
        DeviceServer server = new DeviceServer(filesDir);
        instance = server;
        server.start();
    }

    static int currentPort() {
        DeviceServer server = instance;
        return server == null ? -1 : server.port;
    }

    static boolean rootAvailable() {
        return DeviceShell.isRoot();
    }

    private void start() {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                for (int candidate : PORTS) {
                    ServerSocket server = null;
                    try {
                        server = new ServerSocket(candidate, 8, InetAddress.getByName("127.0.0.1"));
                        port = candidate;
                        DeviceShell.init(appContext, filesDir);
                        Log.i(TAG, "device bridge on http://127.0.0.1:" + candidate + "/");
                        DiagnosticServer.appendLog(filesDir,
                                "device bridge: http://127.0.0.1:" + candidate + "/" + " | "
                                        + DeviceShell.line());
                        serve(server);
                        return;
                    } catch (Throwable inUse) {
                        if (server != null) {
                            try {
                                server.close();
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
                Log.w(TAG, "device bridge could not bind any port");
                DiagnosticServer.appendLog(filesDir, "device bridge: could not bind any port");
            }
        }, "dsh-device");
        thread.setDaemon(true);
        thread.start();
    }

    private void serve(ServerSocket server) {
        while (!server.isClosed()) {
            Socket socket = null;
            try {
                socket = server.accept();
                socket.setSoTimeout(120000);
                handle(socket);
            } catch (Throwable e) {
                if (server.isClosed()) {
                    return;
                }
            } finally {
                if (socket != null) {
                    try {
                        socket.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ request

    private static final class Request {
        String method = "GET";
        String path = "/";
        final Map<String, String> query = new HashMap<String, String>();
        final Map<String, String> headers = new HashMap<String, String>();

        String header(String name) {
            return headers.get(name.toLowerCase(java.util.Locale.US));
        }
    }

    private void handle(Socket socket) throws Exception {
        InputStream in = socket.getInputStream();
        String rawHead = readHead(in);
        if (rawHead == null || rawHead.isEmpty()) {
            return;
        }
        String[] lines = rawHead.split("\r\n");
        String[] first = lines[0].split(" ");
        Request req = new Request();
        if (first.length >= 2) {
            req.method = first[0];
            String target = first[1];
            int q = target.indexOf('?');
            if (q >= 0) {
                req.path = target.substring(0, q);
                parseQuery(target.substring(q + 1), req.query);
            } else {
                req.path = target;
            }
        }
        int contentLength = 0;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            int c = line.indexOf(':');
            if (c > 0) {
                req.headers.put(line.substring(0, c).trim().toLowerCase(java.util.Locale.US),
                        line.substring(c + 1).trim());
            }
        }
        for (String line : lines) {
            int c = line.indexOf(':');
            if (c > 0 && line.substring(0, c).trim().equalsIgnoreCase("Content-Length")) {
                try {
                    contentLength = Integer.parseInt(line.substring(c + 1).trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        for (int i = 0; i < contentLength; i++) {
            if (in.read() < 0) {
                break;
            }
        }
        if (!authorized(req, socket)) {
            return;
        }
        dispatch(socket, req);
    }

    /**
     * Authenticate the caller before any endpoint runs.
     *
     * Binding to 127.0.0.1 is not isolation on Android: every app on the device
     * shares the loopback interface, so `curl http://127.0.0.1:<port>/device/tap`
     * worked from anywhere. Worse, a plain web page can drive the endpoints with
     * `<img src="http://127.0.0.1:.../device/tap?x=..&y=..">` -- no CORS, no
     * prompt -- and every one of them runs through the authorised `su` channel.
     *
     * Three checks, cheapest first:
     *   1. fail closed when no token exists (never serve unauthenticated);
     *   2. refuse anything a browser sent (Origin / cross-site Sec-Fetch-Site) --
     *      no legitimate client here is a browser;
     *   3. require the per-install token, compared without early exit.
     */
    private boolean authorized(Request req, Socket socket) throws Exception {
        String token = this.token;
        String deny = null;
        if (token == null || token.isEmpty()) {
            deny = "device bridge is not configured (no token)";
        } else if (req.header("origin") != null) {
            deny = "browser-originated requests are not accepted";
        } else {
            String site = req.header("sec-fetch-site");
            if (site != null && !site.equalsIgnoreCase("same-origin") && !site.equalsIgnoreCase("none")) {
                deny = "cross-site requests are not accepted";
            }
        }
        String presented = req.query.get("token");
        if (deny == null && presented == null) {
            presented = req.header("x-device-token");
        }
        if (deny == null && !constantTimeEquals(token, presented)) {
            deny = "bad or missing token";
        }
        if (deny != null) {
            send(socket, 401, "text/plain; charset=utf-8",
                    ("unauthorized: " + deny + "\n").getBytes("UTF-8"));
            return false;
        }
        return true;
    }

    /** Compare without leaking length or position through timing. */
    private static boolean constantTimeEquals(String expected, String actual) {
        if (actual == null) {
            return false;
        }
        byte[] a = expected.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] b = actual.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int diff = a.length ^ b.length;
        for (int i = 0; i < a.length && i < b.length; i++) {
            diff |= a[i] ^ b[i];
        }
        return diff == 0;
    }

    /** The per-install token, for the in-app agent to read back. */
    static String token() {
        DeviceServer server = instance;
        return server == null ? null : server.token;
    }

    /**
     * Load the bridge token, creating it on first run with 0600.
     *
     * Returning null means "do not serve": a bridge that cannot authenticate must
     * stay closed rather than fall back to being open.
     */
    private static String loadOrCreateToken(File filesDir) {
        File file = new File(filesDir, ".device-token");
        try {
            if (file.isFile()) {
                byte[] buf = new byte[256];
                java.io.FileInputStream in = new java.io.FileInputStream(file);
                int n = in.read(buf);
                in.close();
                String existing = new String(buf, 0, Math.max(0, n), "UTF-8").trim();
                if (!existing.isEmpty()) {
                    return existing;
                }
            }
            byte[] random = new byte[24];
            new java.security.SecureRandom().nextBytes(random);
            StringBuilder hex = new StringBuilder();
            for (byte value : random) {
                hex.append(String.format("%02x", value));
            }
            String token = hex.toString();
            java.io.FileOutputStream out = new java.io.FileOutputStream(file);
            out.write(token.getBytes("UTF-8"));
            out.close();
            try {
                new ProcessBuilder("/system/bin/chmod", "600", file.getAbsolutePath())
                        .redirectErrorStream(true).start();
            } catch (Throwable ignored) {
            }
            Log.i(TAG, "device bridge token created at " + file.getAbsolutePath());
            return token;
        } catch (Throwable t) {
            Log.w(TAG, "device bridge token unavailable; the bridge stays closed", t);
            return null;
        }
    }

    /** Read up to the end of headers; null when the peer sent nothing. */
    private static String readHead(InputStream in) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int match = 0;
        int b;
        while ((b = in.read()) >= 0) {
            buf.write(b);
            if ((match == 0 || match == 2) && b == '\r') {
                match++;
            } else if ((match == 1 || match == 3) && b == '\n') {
                match++;
                if (match == 4) {
                    break;
                }
            } else {
                match = 0;
            }
            if (buf.size() > 64 * 1024) {
                break;
            }
        }
        return buf.size() == 0 ? null : new String(buf.toByteArray(), "UTF-8");
    }

    private static void parseQuery(String q, Map<String, String> into) {
        for (String pair : q.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            try {
                String k = eq < 0 ? pair : pair.substring(0, eq);
                String v = eq < 0 ? "" : pair.substring(eq + 1);
                into.put(URLDecoder.decode(k, "UTF-8"), URLDecoder.decode(v, "UTF-8"));
            } catch (Exception ignored) {
            }
        }
    }

    // ----------------------------------------------------------------- dispatch

    private void dispatch(Socket socket, Request req) throws Exception {
        String path = req.path;
        try {
            if (path.equals("/device/open")) {
                handleOpen(req, socket);
                return;
            }
            if (path.equals("/") || path.equals("/device/status")) {
                status(socket);
            } else if (path.equals("/device/shot")) {
                shot(socket, req.query);
            } else if (path.equals("/device/tap")) {
                simple(socket, DeviceShell.tap(Integer.parseInt(num(req, "x")),
                        Integer.parseInt(num(req, "y"))));
            } else if (path.equals("/device/swipe")) {
                simple(socket, DeviceShell.swipe(Integer.parseInt(num(req, "x1")),
                        Integer.parseInt(num(req, "y1")), Integer.parseInt(num(req, "x2")),
                        Integer.parseInt(num(req, "y2")), Integer.parseInt(duration(req))));
            } else if (path.equals("/device/key")) {
                simple(socket, DeviceShell.key(keyArg(req)));
            } else if (path.equals("/device/text")) {
                simple(socket, DeviceShell.text(textArg(req)));
            } else if (path.equals("/device/ui")) {
                simple(socket, DeviceShell.readUi(uiLimit(req)));
            } else if (path.equals("/device/route")) {
                route(socket, req);
            } else if (path.equals("/device/start")) {
                simple(socket, DeviceShell.exec("am start -n " + componentArg(req), 60000));
            } else if (path.equals("/device/shell")) {
                shell(socket, req.query);
            } else if (path.equals("/device/ocr")) {
                ocr(socket, req.query);
            } else if (path.equals("/device/top")) {
                simple(socket, DeviceShell.exec(
                        "dumpsys activity activities | grep -m1 topResumedActivity", 30000));
            } else {
                send(socket, 404, "text/plain; charset=utf-8",
                        "unknown endpoint\n".getBytes("UTF-8"));
            }
        } catch (BadRequest bad) {
            send(socket, 400, "text/plain; charset=utf-8",
                    ("bad request: " + bad.getMessage() + "\n").getBytes("UTF-8"));
        } catch (Throwable t) {
            Log.w(TAG, "device bridge error on " + path, t);
            send(socket, 500, "text/plain; charset=utf-8",
                    ("error: " + t + "\n").getBytes("UTF-8"));
        }
    }

    private void status(Socket socket) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("dsh device bridge\n");
        sb.append("port: ").append(port).append('\n');
        // One line per route, so a bug report says which channel each capability
        // actually took instead of guessing from the presence of root.
        sb.append(DeviceShell.line()).append('\n');
        sb.append("device shell: ").append(DeviceShell.isShizuku() ? "Shizuku"
                : DeviceShell.isRoot() ? "su" : "none").append('\n');
        for (String channel : DeviceShell.channels()) {
            sb.append("  ").append(channel).append('\n');
        }
        sb.append("endpoints:\n");
        sb.append("  GET /device/shot[?out=NAME]                PNG bytes of the current screen\n");
        sb.append("  GET /device/tap?x=&y=                      inject a tap\n");
        sb.append("  GET /device/swipe?x1=&y1=&x2=&y2=[&ms=]    inject a swipe\n");
        sb.append("  GET /device/key?name=home|back|enter|volup|voldown|power|CODE\n");
        sb.append("  GET /device/text?value=...                 type text (Unicode via accessibility)\n");
        sb.append("  GET /device/ui[?max=60]                    readable node-tree summary (accessibility)\n");
        sb.append("  GET /device/route[?pin=a11y|projection|su|none|auto]  pin the routing (tests)\n");
        sb.append("  GET /device/start?component=pkg/.Act       launch an activity\n");
        sb.append("  GET /device/shell?cmd=[&timeout=SEC]       run a whitelisted command line\n");
        sb.append("                                             (raw stdout, X-Exit-Code header)\n");
        sb.append("  GET /device/top                            current foreground activity\n");
        sb.append("  GET /device/ocr[?lang=chi_sim+eng]         OCR the screen (needs OCR runtime)\n");
        sb.append("  GET /device/status                         this text\n");
        send(socket, 200, "text/plain; charset=utf-8", sb.toString().getBytes("UTF-8"));
    }

    /** Capture the screen and return raw PNG bytes so callers need no decoder. */
    private void shot(Socket socket, Map<String, String> query) throws Exception {
        // `screencap -p` writes to stdout, not to a file: under Shizuku the process
        // runs as uid 2000 and cannot write this app's private directory, while a
        // file-writing variant would work only for root. The app writes the file.
        DeviceShell.Result capture = DeviceShell.capture();
        if (!capture.ok() || capture.out.length == 0) {
            send(socket, 500, "text/plain; charset=utf-8",
                    ("capture failed: " + capture.text() + "\n").getBytes("UTF-8"));
            return;
        }
        File out = new File(filesDir, "screen.png");
        String named = query.get("out");
        if (named != null && named.matches("[A-Za-z0-9_.-]{1,64}")) {
            out = new File(filesDir, named);
        }
        writeBytes(out, capture.out);
        Map<String, String> headers = new HashMap<String, String>();
        headers.put("X-Device-Backend", capture.backend == null ? "?" : capture.backend);
        headers.put("X-Png-Bytes", String.valueOf(capture.out.length));
        send(socket, 200, "image/png", capture.out, headers);
    }

    /**
     * OCR via the tesseract that ships inside the companion runtime, when that
     * runtime is present. Shells out through proot because tesseract is a glibc
     * binary while the app process is bionic.
     */
    private void ocr(Socket socket, Map<String, String> query) throws Exception {
        String lang = query.get("lang");
        if (lang == null || !lang.matches("[a-z_+]{1,64}")) {
            lang = "chi_sim+eng";
        }
        DeviceShell.Result capture = DeviceShell.capture();
        if (!capture.ok() || capture.out.length == 0) {
            send(socket, 500, "text/plain; charset=utf-8",
                    ("capture failed: " + capture.text() + "\n").getBytes("UTF-8"));
            return;
        }
        // The OCR side runs in a shell, so the image has to live somewhere that uid
        // can read. Root can read the private dir; Shizuku (uid 2000) cannot, so use
        // the app's external files dir, which shell can read on every release.
        File dir = ocrWorkDir();
        File shot = new File(dir, "screen.png");
        writeBytes(shot, capture.out);
        File scriptFile = new File(dir, "ocr.sh");
        writeFile(scriptFile, ocrScript(shot, lang));
        DeviceShell.Result shell = DeviceShell.exec(
                "sh " + shellQuote(scriptFile.getAbsolutePath()), 120000);
        String text = shell.text();
        if (text.startsWith("OCR_RUNTIME_MISSING")) {
            send(socket, 501, "text/plain; charset=utf-8", text.getBytes("UTF-8"));
            return;
        }
        send(socket, 200, "text/plain; charset=utf-8", text.getBytes("UTF-8"));
    }

    /**
     * A directory the privileged shell can read.
     *
     * Root (`su`) reads the private dir fine. Shizuku runs as uid 2000, which is
     * denied on /data/data/<pkg> but allowed on the app's external files dir, so
     * that is used whenever the external volume is mounted.
     */
    private File ocrWorkDir() {
        if (!DeviceShell.isRoot()) {
            File external = appContext == null ? null : appContext.getExternalFilesDir("ocr");
            if (external != null && (external.isDirectory() || external.mkdirs())) {
                return external;
            }
        }
        return filesDir;
    }

    /**
     * Write bytes without assuming the target is writable.
     *
     * An upgraded install can still have a `screen.png` owned by root: every
     * previous build captured through `su`, so the file it left behind is root:600
     * and the app cannot reopen it (EACCES). Writing a sibling temp file and
     * renaming it into place only needs write permission on the directory, which
     * the app always has; if rename still fails, drop the old file first.
     */
    private static void writeBytes(File file, byte[] data) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory()) {
            parent.mkdirs();
        }
        File tmp = new File(parent, file.getName() + ".tmp-"
                + android.os.Process.myPid() + "-" + System.nanoTime());
        java.io.FileOutputStream out = new java.io.FileOutputStream(tmp);
        try {
            out.write(data);
        } finally {
            out.close();
        }
        if (tmp.renameTo(file)) {
            return;
        }
        file.delete();
        if (!tmp.renameTo(file)) {
            java.io.FileOutputStream direct = new java.io.FileOutputStream(file);
            try {
                direct.write(data);
            } finally {
                direct.close();
            }
            tmp.delete();
        }
    }

    /**
     * The OCR runtime is the proot Ubuntu rootfs owned by the companion client
     * app. Those paths are not part of this app's contract, so probe for them and
     * report honestly instead of failing obscurely.
     */
    private static String ocrScript(File shot, String lang) {
        String shim = "/data/local/tmp/prootshim";
        String rootfs = "/data/user/0/com.dsh.client/files/linux/ubuntu";
        File parent = shot.getParentFile();
        String dir = parent == null ? "/data/local/tmp" : parent.getAbsolutePath();
        return "#!/system/bin/sh\n"
                + "U=" + shellQuote(rootfs) + "\n"
                + "S=" + shellQuote(shim) + "\n"
                + "[ -d \"$U\" ] || { echo \"OCR_RUNTIME_MISSING: no ubuntu rootfs at $U\"; exit 0; }\n"
                + "[ -x \"$S/libproroot.so\" ] || { echo \"OCR_RUNTIME_MISSING: no proroot shim at $S\"; exit 0; }\n"
                + "export LD_LIBRARY_PATH=$S\n"
                + "exec $S/libproroot.so --static-loader -0 -r $U -w /root \\\n"
                + "  -b /dev:/dev -b /proc:/proc -b /sys:/sys -b " + shellQuote(dir) + ":/hostshot \\\n"
                + "  /bin/bash -lc " + shellQuote("tesseract /hostshot/" + shot.getName()
                        + " stdout -l " + lang + " 2>/dev/null") + "\n";
    }

    // ------------------------------------------------------------------- shell

    /**
     * Run one whitelisted command line through the routing table.
     *
     * Response shape: stdout is the body, exit code is X-Exit-Code, stderr is
     * X-Stderr-B64 (base64, because a header cannot carry newlines or NULs). This
     * is what the screen-operate plugin calls instead of `su -c`; the privilege
     * decision is made here, once, and the plugin never knows which one ran.
     */
    private void shell(Socket socket, Map<String, String> query) throws Exception {
        String command = shellCommand(query.get("cmd"));
        long timeoutMs = 60000;
        String timeout = query.get("timeout");
        if (timeout != null && timeout.matches("[0-9]{1,3}")) {
            timeoutMs = Math.max(1, Math.min(120, Integer.parseInt(timeout))) * 1000L;
        }
        DeviceShell.Result result = DeviceShell.exec(command, timeoutMs);
        if (result.failure != null) {
            // Never answer 200 with an empty body for a command that never ran: the
            // caller would report "exit 1, 0 bytes" and lose the reason (NO_PRIVILEGE,
            // timeout, spawn failure). 503 + the message is what surfaces in the tool.
            send(socket, 503, "text/plain; charset=utf-8",
                    (result.failure + "\n").getBytes("UTF-8"));
            return;
        }
        String stderr = result.errText();
        if (stderr.length() > 4000) {
            stderr = stderr.substring(0, 4000);
        }
        Map<String, String> headers = new HashMap<String, String>();
        headers.put("X-Exit-Code", String.valueOf(result.code));
        headers.put("X-Device-Shell", result.backend);
        headers.put("X-Stderr-B64", android.util.Base64.encodeToString(
                stderr.getBytes("UTF-8"), android.util.Base64.NO_WRAP));
        send(socket, 200, "application/octet-stream", result.out, headers);
    }

    /**
     * Commands this endpoint will run. The bridge used to expose only fixed
     * operations and no command runner; the plugin needs the same command strings
     * it used to send to `su -c` (pipes to grep included), so the check is on the
     * first program, not on the whole line.
     *
     * Access is unchanged from the su channel it replaces: loopback only, browser
     * origins refused, per-install token required, and the caller is the app's own
     * runtime. Anything outside this list is rejected instead of executed.
     */
    private static final java.util.Set<String> SHELL_ALLOWED = new java.util.HashSet<String>(
            java.util.Arrays.asList("screencap", "input", "wm", "dumpsys", "am", "cmd",
                    "pm", "settings", "getprop", "id", "uiautomator", "ls", "cat", "ps",
                    "date", "df", "top", "pidof", "dumpsys"));

    private static String shellCommand(String cmd) throws BadRequest {
        if (cmd == null || cmd.trim().isEmpty()) {
            throw new BadRequest("missing 'cmd'");
        }
        if (cmd.length() > 8192) {
            throw new BadRequest("'cmd' too long");
        }
        if (cmd.indexOf('\0') >= 0) {
            throw new BadRequest("NUL in 'cmd'");
        }
        String first = cmd.trim().split("\\s+")[0];
        String program = first.substring(first.lastIndexOf('/') + 1);
        if (!SHELL_ALLOWED.contains(program)) {
            throw new BadRequest("command not allowed: " + program);
        }
        return cmd;
    }

    private void simple(Socket socket, DeviceShell.Result result) throws Exception {
        String body = "code=" + result.code + "\n" + result.text();
        // Which channel actually served this call. The routing line in
        // /device/status says what *would* be used; this says what did, which is
        // the only way to prove a tap really went through accessibility.
        Map<String, String> headers = new HashMap<String, String>();
        headers.put("X-Device-Backend", result.backend == null ? "?" : result.backend);
        send(socket, result.code == 0 ? 200 : 500, "text/plain; charset=utf-8",
                body.getBytes("UTF-8"), headers);
    }

    /**
     * Pin the routing table for a test run: `?pin=accessibility` allows only the
     * accessibility channel, `?pin=none` allows none, `?pin=auto` clears it.
     *
     * The pin lives in memory and can only remove candidates, never add one, so it
     * cannot be used to gain access the app did not already have. It exists because
     * the four channel states (zero-install / projection / Shizuku / root / none)
     * otherwise cannot be exercised on a phone that happens to have root.
     */
    private void route(Socket socket, Request req) throws Exception {
        String pin = req.query.get("pin");
        String line = DeviceShell.setPin(pin);
        StringBuilder sb = new StringBuilder();
        sb.append("pin: ").append(pin == null ? "auto" : pin).append('\n');
        sb.append(line).append('\n');
        for (String channel : DeviceShell.channels()) {
            sb.append("  ").append(channel).append('\n');
        }
        send(socket, 200, "text/plain; charset=utf-8", sb.toString().getBytes("UTF-8"));
    }

    // -------------------------------------------------------------- validation

    private static final class BadRequest extends Exception {
        BadRequest(String message) {
            super(message);
        }
    }

    private static String num(Request req, String key) throws BadRequest {
        String v = req.query.get(key);
        if (v == null || !v.matches("-?[0-9]{1,6}")) {
            throw new BadRequest("missing or invalid '" + key + "'");
        }
        return v;
    }

    private static String duration(Request req) throws BadRequest {
        String v = req.query.get("ms");
        if (v == null) {
            return "300";
        }
        if (!v.matches("[0-9]{1,6}")) {
            throw new BadRequest("invalid 'ms'");
        }
        return v;
    }

    /** Named keys keep the common cases readable; numeric codes stay allowed. */
    private static String keyArg(Request req) throws BadRequest {
        String v = req.query.get("name");
        if (v == null) {
            v = req.query.get("code");
        }
        if (v == null) {
            throw new BadRequest("missing 'name' or 'code'");
        }
        if (v.matches("[0-9]{1,4}")) {
            return v;
        }
        String lower = v.toLowerCase();
        if (lower.equals("home")) {
            return "KEYCODE_HOME";
        }
        if (lower.equals("back")) {
            return "KEYCODE_BACK";
        }
        if (lower.equals("enter")) {
            return "KEYCODE_ENTER";
        }
        if (lower.equals("volup")) {
            return "KEYCODE_VOLUME_UP";
        }
        if (lower.equals("voldown")) {
            return "KEYCODE_VOLUME_DOWN";
        }
        if (lower.equals("power")) {
            return "KEYCODE_POWER";
        }
        if (lower.equals("menu")) {
            return "KEYCODE_MENU";
        }
        if (lower.equals("appswitch")) {
            return "KEYCODE_APP_SWITCH";
        }
        if (v.matches("KEYCODE_[A-Z0-9_]{1,40}")) {
            return v;
        }
        throw new BadRequest("unknown key name");
    }

    /**
     * Text for /device/text, passed through as written.
     *
     * Two channels consume it and only one needs escaping: ACTION_SET_TEXT takes
     * Unicode directly (so Chinese finally works), while `input text` is built into
     * a command line and escapes itself in DeviceShell.shellText(). Filtering here
     * would throw away every non-ASCII character for both of them.
     */
    private static String textArg(Request req) throws BadRequest {
        String v = req.query.get("value");
        if (v == null) {
            throw new BadRequest("missing 'value'");
        }
        if (v.length() > 2000) {
            throw new BadRequest("'value' too long");
        }
        if (v.isEmpty()) {
            throw new BadRequest("'value' is empty");
        }
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == 0 || c == '\n' || c == '\r') {
                throw new BadRequest("'value' must be a single line");
            }
        }
        return v;
    }

    /** Node budget for /device/ui: small by default, because dumps end up in prompts. */
    private static int uiLimit(Request req) throws BadRequest {
        String v = req.query.get("max");
        if (v == null) {
            return 60;
        }
        if (!v.matches("[0-9]{1,4}")) {
            throw new BadRequest("invalid 'max'");
        }
        int n = Integer.parseInt(v);
        return Math.max(1, Math.min(400, n));
    }

    private static String componentArg(Request req) throws BadRequest {
        String v = req.query.get("component");
        if (v == null || !v.matches("[A-Za-z0-9_.]{1,120}/[A-Za-z0-9_.$]{1,120}")) {
            throw new BadRequest("'component' must look like pkg/.Activity");
        }
        return v;
    }

    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    // -------------------------------------------------------------------- http


    /**
     * Ask Android to open a file in another app.
     *
     * There is no desktop here, so "open in an external application" is the system
     * share sheet: the file is handed over as a content:// URI (a file:// URI would
     * be refused on API 24+) and the user picks a viewer. This is what the harness'
     * open-in-app host plugin ends up calling through usr/bin/dsh-open.
     */
    private void handleOpen(Request req, Socket socket) throws Exception {
        String filePath = req.query.get("path");
        if (filePath == null || filePath.isEmpty()) {
            send(socket, 400, "application/json", "{\"error\":\"path required\"}".getBytes("UTF-8"));
            return;
        }
        File file = new File(filePath);
        if (!file.isAbsolute()) {
            send(socket, 400, "application/json", "{\"error\":\"path must be absolute\"}".getBytes("UTF-8"));
            return;
        }
        if (file.isDirectory()) {
            // A folder cannot be handed to another app as a shared stream, so be
            // explicit instead of pretending: the bridge answers, dsh-open exits
            // non-zero, and the UI no longer reports a success that did not happen.
            send(socket, 400, "application/json",
                    "{\"error\":\"directory: open it with a file manager\"}".getBytes("UTF-8"));
            return;
        }
        if (!file.isFile()) {
            send(socket, 404, "application/json", "{\"error\":\"no such file\"}".getBytes("UTF-8"));
            return;
        }
        try {
            String type = new FileBridge().getType(FileBridge.uriFor(file));
            android.content.Intent sendIntent = new android.content.Intent(android.content.Intent.ACTION_SEND);
            sendIntent.setType(type);
            sendIntent.putExtra(android.content.Intent.EXTRA_STREAM, FileBridge.uriFor(file));
            sendIntent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (appContext == null) {
                send(socket, 500, "application/json", "{\"error\":\"no context\"}".getBytes("UTF-8"));
                return;
            }
            android.content.Intent chooser =
                    android.content.Intent.createChooser(sendIntent,
                            appContext.getString(R.string.open_file, file.getName()));
            chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            appContext.startActivity(chooser);
            send(socket, 200, "application/json",
                    ("{\"opened\":\"" + file.getName().replace("\"", "") + "\"}").getBytes("UTF-8"));
        } catch (Throwable t) {
            send(socket, 500, "application/json",
                    ("{\"error\":\"could not start an activity: " + String.valueOf(t.getMessage()).replace("\"", "'") + "\"}")
                            .getBytes("UTF-8"));
        }
    }

    private static void send(Socket socket, int code, String type, byte[] body) throws Exception {
        send(socket, code, type, body, null);
    }

    private static void send(Socket socket, int code, String type, byte[] body,
                             Map<String, String> headers) throws Exception {
        String reason = code == 200 ? "OK" : code == 400 ? "Bad Request"
                : code == 503 ? "Service Unavailable"
                : code == 404 ? "Not Found" : code == 501 ? "Not Implemented"
                : "Internal Server Error";
        OutputStream out = socket.getOutputStream();
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n");
        head.append("Content-Type: ").append(type).append("\r\n");
        head.append("Content-Length: ").append(body.length).append("\r\n");
        head.append("Cache-Control: no-store\r\n");
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                head.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
            }
        }
        head.append("Connection: close\r\n\r\n");
        out.write(head.toString().getBytes("UTF-8"));
        out.write(body);
        out.flush();
    }

    private static byte[] readFile(File f, int cap) throws Exception {
        InputStream in = new java.io.FileInputStream(f);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > cap) {
                    break;
                }
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private static void writeFile(File f, String content) throws Exception {
        java.io.FileOutputStream out = new java.io.FileOutputStream(f);
        try {
            out.write(content.getBytes("UTF-8"));
        } finally {
            out.close();
        }
    }
}
