package com.dsh.android;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Pulls a newer APK from the build machine over loopback/HTTP and hands it to the
 * package installer.
 *
 * Android does not let an app install a package without a user confirmation tap;
 * ACTION_VIEW with the FileProvider URI is the supported way to raise that dialog.
 * The fetch itself is fully automatic, which is what makes iterating on a device
 * you cannot sideload to otherwise.
 */
final class SelfUpdater {

    private static final String TAG = "DSH";
    private static final int TIMEOUT_MS = 20000;

    interface Callback {
        void onProgress(String message);

        void onReady(File apk);

        void onFailed(String reason);
    }

    private SelfUpdater() {
    }

    /** Base URL of the update feed; the page lists the newest APK name. */
    static String feedUrl(File filesDir) {
        File f = new File(filesDir, "update-url.txt");
        if (f.isFile()) {
            String s = read(f).trim();
            if (s.length() > 0) {
                return s;
            }
        }
        return "http://10.127.20.238:8099/latest.txt";
    }

    /**
     * Fetch the update feed; if it names an APK, download it into cacheDir.
     * Runs on a background thread.
     */
    static void checkAndDownload(final File filesDir, final File cacheDir, final Callback cb) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String feed = fetchText(feedUrl(filesDir));
                    if (feed == null) {
                        cb.onFailed("update feed unreachable");
                        return;
                    }
                    String apkName = null;
                    for (String line : feed.split("\n")) {
                        line = line.trim();
                        if (line.endsWith(".apk")) {
                            apkName = line;
                            break;
                        }
                    }
                    if (apkName == null) {
                        cb.onFailed("feed lists no apk");
                        return;
                    }
                    String base = feedUrl(filesDir);
                    base = base.substring(0, base.lastIndexOf('/') + 1);
                    String url = base + apkName;
                    File out = new File(cacheDir, apkName);
                    cb.onProgress("downloading " + apkName + " ...");
                    long bytes = download(url, out, cb);
                    if (bytes <= 0) {
                        cb.onFailed("download failed");
                        return;
                    }
                    cb.onProgress("downloaded " + (bytes / (1024 * 1024)) + " MB");
                    cb.onReady(out);
                } catch (Throwable t) {
                    Log.w(TAG, "self update failed", t);
                    cb.onFailed(String.valueOf(t.getMessage()));
                }
            }
        }, "dsh-update").start();
    }

    private static long download(String url, File out, Callback cb) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        long total = conn.getContentLength();
        InputStream in = conn.getInputStream();
        OutputStream os = new FileOutputStream(out);
        long done = 0;
        try {
            byte[] buf = new byte[1 << 16];
            int n;
            int tick = 0;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
                done += n;
                if (++tick % 200 == 0) {
                    cb.onProgress("downloading ... " + (done / (1024 * 1024))
                            + (total > 0 ? " / " + (total / (1024 * 1024)) : "") + " MB");
                }
            }
        } finally {
            os.close();
            in.close();
            conn.disconnect();
        }
        return done;
    }

    private static String fetchText(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            InputStream in = conn.getInputStream();
            byte[] buf = new byte[8192];
            int n = in.read(buf);
            in.close();
            return n <= 0 ? "" : new String(buf, 0, n, "UTF-8");
        } catch (IOException e) {
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** Raise the system install dialog for a downloaded APK. */
    static void install(Activity activity, File apk) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        Uri uri = UpdateProvider.uriFor(apk);
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity.startActivity(intent);
    }

    private static String read(File f) {
        InputStream in = null;
        try {
            in = new java.io.FileInputStream(f);
            byte[] buf = new byte[(int) f.length()];
            int n = in.read(buf);
            return new String(buf, 0, Math.max(0, n), "UTF-8");
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

    static {
        // keep the compiler from stripping the provider reference in release-ish builds
        if (Build.VERSION.SDK_INT < 1) {
            UpdateProvider.uriFor(new File("/"));
        }
    }
}
