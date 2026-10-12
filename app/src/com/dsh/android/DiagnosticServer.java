package com.dsh.android;

import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Tiny loopback HTTP endpoint that reports runtime state.
 *
 * Why it exists: the app's own logcat is not readable by other tools on a release
 * build, so a failure inside the bundled runtime is otherwise invisible unless
 * someone reads the screen. This serves the same information as plain text on
 * 127.0.0.1 so it can be fetched directly while the app is alive.
 */
final class DiagnosticServer {

    private static final String TAG = "DSH";
    private static final int[] PORTS = {3097, 3098, 3099};
    private static final int MAX_LOG_CHARS = 64 * 1024;

    private final File filesDir;
    private volatile int port = -1;
    private Thread thread;

    DiagnosticServer(File filesDir) {
        this.filesDir = filesDir;
    }

    int port() {
        return port;
    }

    void start() {
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                for (int candidate : PORTS) {
                    ServerSocket server = null;
                    try {
                        server = new ServerSocket(candidate, 4, InetAddress.getByName("127.0.0.1"));
                        port = candidate;
                        Log.i(TAG, "diagnostics on http://127.0.0.1:" + candidate + "/");
                        serve(server);
                        return;
                    } catch (IOException inUse) {
                        Log.i(TAG, "diagnostics port " + candidate + " unavailable");
                        if (server != null) {
                            try {
                                server.close();
                            } catch (IOException ignored) {
                            }
                        }
                    }
                }
                Log.w(TAG, "diagnostics could not bind any port");
            }
        }, "dsh-diag");
        thread.setDaemon(true);
        thread.start();
    }

    private void serve(ServerSocket server) {
        while (!server.isClosed()) {
            Socket socket = null;
            try {
                socket = server.accept();
                socket.setSoTimeout(5000);
                respond(socket);
            } catch (IOException e) {
                if (server.isClosed()) {
                    return;
                }
            } finally {
                if (socket != null) {
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }
    }

    private void respond(Socket socket) throws IOException {
        String head = "HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\n"
                + "Connection: close\r\n\r\n";
        String body = snapshot();
        OutputStream out = socket.getOutputStream();
        out.write(head.getBytes("UTF-8"));
        out.write(body.getBytes("UTF-8"));
        out.flush();
    }

    private String snapshot() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== DSH for Android diagnostics ===\n");
        sb.append("time: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(new Date())).append('\n');
        sb.append("running: ").append(NodeServer.isRunning()).append('\n');
        sb.append("url: ").append(String.valueOf(NodeServer.currentUrl())).append('\n');
        sb.append("diagPort: ").append(port).append('\n');

        File runtime = new File(filesDir, "runtime");
        File node = new File(runtime, "usr/bin/node");
        File entry = new File(runtime, "dsh/node_modules/@deepseek-ai/dsh/lib/bin.js");
        File marker = new File(runtime, ".extracted");
        File state = new File(runtime, ".extract-state");

        sb.append("\n=== runtime layout ===\n");
        sb.append("runtime dir: ").append(runtime.isDirectory()).append('\n');
        String[] top = runtime.list();
        sb.append("top entries: ").append(top == null ? "null" : java.util.Arrays.toString(top))
                .append('\n');
        sb.append("usr/bin/node: ").append(node.isFile())
                .append(" bytes=").append(node.length())
                .append(" exec=").append(node.canExecute()).append('\n');
        sb.append("dsh entry: ").append(entry.isFile())
                .append(" bytes=").append(entry.length()).append('\n');
        sb.append("marker: ").append(marker.isFile())
                .append(" state: ").append(state.isFile() ? read(state) : "(none)").append('\n');

        sb.append("\n=== recent log ===\n");
        sb.append(tail(read(new File(filesDir, "dsh.log")), MAX_LOG_CHARS));
        return sb.toString();
    }

    /**
     * External mirror: /sdcard/Android/data/<pkg>/files/dsh.log is readable
     * without the app running, which matters when a failure kills it early.
     */
    private static File externalLog;

    static void setExternalLog(File target) {
        externalLog = target;
    }

    /** Keep a rolling on-disk copy so a crashed run can still be inspected. */
    static synchronized void appendLog(File filesDir, String line) {
        appendTo(new File(filesDir, "dsh.log"), line, MAX_LOG_CHARS);
        if (externalLog != null) {
            appendTo(externalLog, line, 256 * 1024);
        }
    }

    private static void appendTo(File f, String line, int cap) {
        if (f == null) {
            return;
        }
        PrintWriter w = null;
        try {
            if (f.length() > cap) {
                String existing = read(f);
                w = new PrintWriter(f, "UTF-8");
                w.print(existing.substring(Math.max(0, existing.length() - cap / 2)));
                w.close();
                w = null;
            }
            w = new PrintWriter(new java.io.FileWriter(f, true));
            w.println(line);
        } catch (IOException e) {
            Log.w(TAG, "log write failed for " + f, e);
        } finally {
            if (w != null) {
                w.close();
            }
        }
    }

    private static String read(File f) {
        InputStream in = null;
        try {
            in = new java.io.FileInputStream(f);
            byte[] buf = new byte[(int) Math.min(f.length(), MAX_LOG_CHARS)];
            int n = in.read(buf);
            return n <= 0 ? "" : new String(buf, 0, n, "UTF-8");
        } catch (IOException e) {
            return "";
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static String tail(String s, int max) {
        if (s.length() <= max) {
            return s;
        }
        return "...(trimmed)...\n" + s.substring(s.length() - max);
    }
}
