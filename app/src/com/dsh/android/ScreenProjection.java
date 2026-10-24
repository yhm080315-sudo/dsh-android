package com.dsh.android;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Parcel;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;

/**
 * Zero-install screen capture through MediaProjection.
 *
 * Why this exists: `screencap` needs uid 2000 (Shizuku) or uid 0 (root).
 * MediaProjection needs only a one-time user consent dialog, so it is the
 * capture channel that works on an untouched phone -- and therefore the first
 * entry in the capture routing table (see DeviceShell).
 *
 * Session model, and why it is not "call getMediaProjection per screenshot":
 *
 *   - The consent Intent is the expensive thing (a system dialog). It is used
 *     once to create one MediaProjection, and that projection keeps a
 *     VirtualDisplay + ImageReader alive, so every later capture is a frame
 *     read, not a new consent.
 *   - The consent is also written to disk, so a rebuild of the session after
 *     an activity restart can try to reuse it without bothering the user.
 *   - Android 14 (and later) makes the consent token single-use: reusing that
 *     Intent in a *new* process throws. That case is caught, the stale consent
 *     is discarded, and the status becomes "re-authorise" -- never a crash and
 *     never a fake empty screenshot.
 *
 * Failure policy: every entry point swallows Throwable and records a reason in
 * {@link #error()}. A capture that cannot happen returns null, and the caller
 * turns that into an explicit error string.
 */
final class ScreenProjection {

    private static final String TAG = "DSH";
    /** resultCode + base64(Parcel of the consent Intent). */
    private static final String CONSENT_FILE = "projection-consent.txt";
    private static final long FRAME_TIMEOUT_MS = 3000;

    private static final Object LOCK = new Object();

    private static Context context;
    private static File filesDir;

    private static MediaProjection projection;
    private static VirtualDisplay display;
    private static ImageReader reader;
    private static HandlerThread thread;
    private static Handler handler;

    private static int width;
    private static int height;
    private static int density;

    private static volatile String error;
    /** Set when a consent Intent was handed to the system but not answered yet. */
    private static volatile boolean awaitingConsent;

    private ScreenProjection() {
    }

    static synchronized void init(Context appContext, File dir) {
        if (appContext != null && context == null) {
            context = appContext.getApplicationContext();
        }
        if (dir != null) {
            filesDir = dir;
        }
    }

    // ------------------------------------------------------------- consent

    private static MediaProjectionManager manager() {
        Context ctx = context;
        if (ctx == null) {
            return null;
        }
        return (MediaProjectionManager) ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
    }

    /** The Intent the console row starts; null only if the service is missing. */
    static Intent requestIntent() {
        MediaProjectionManager mgr = manager();
        if (mgr == null) {
            error = "MediaProjectionManager 不可用";
            return null;
        }
        awaitingConsent = true;
        try {
            return mgr.createScreenCaptureIntent();
        } catch (Throwable t) {
            awaitingConsent = false;
            error = "创建投屏授权请求失败：" + t;
            return null;
        }
    }

    /**
     * Handle the consent dialog's answer.
     *
     * Called from the Activity (onActivityResult) so the projection object is
     * created while the activity result is still fresh; the returned text goes
     * straight to the console log.
     */
    static String onConsentResult(int resultCode, Intent data) {
        awaitingConsent = false;
        if (resultCode != android.app.Activity.RESULT_OK || data == null) {
            error = "投屏授权被取消";
            DeviceShell.log("projection: consent denied (resultCode=" + resultCode + ")");
            return "投屏授权被取消，需要时再点一次「投屏」那一行。";
        }
        synchronized (LOCK) {
            dropSessionLocked();
            saveConsentLocked(resultCode, data);
        }
        String opened = openSession(resultCode, data);
        if (opened != null) {
            return opened;
        }
        DeviceShell.log("projection: authorised, capture=MediaProjection");
        DeviceShell.onProjectionChanged("projection: consent granted");
        return "投屏已授权，截图走 MediaProjection（本次会话不需要再确认）。";
    }

    /** True when a capture can be attempted without a new dialog. */
    static boolean authorized() {
        synchronized (LOCK) {
            if (projection != null) {
                return true;
            }
            return readConsent() != null;
        }
    }

    static boolean awaitingConsent() {
        return awaitingConsent;
    }

    /** Console wording, including why it is unusable when a reason is known. */
    static String status() {
        synchronized (LOCK) {
            if (projection != null) {
                return "已授权";
            }
            if (readConsent() != null) {
                return "已授权（会话待重建）";
            }
        }
        if (awaitingConsent) {
            return "等待授权";
        }
        String reason = error;
        return reason == null ? "未授权" : "未授权（" + reason + "）";
    }

    /** Why the last capture or consent failed; null when nothing is wrong. */
    static String error() {
        return error;
    }

    // ------------------------------------------------------------- session

    /**
     * Build projection + virtual display + reader from a consent.
     * Returns null on success, or a human-readable failure.
     */
    private static String openSession(int resultCode, Intent data) {
        MediaProjectionManager mgr = manager();
        Context ctx = context;
        if (mgr == null || ctx == null) {
            error = "MediaProjectionManager 不可用";
            return "投屏失败：MediaProjectionManager 不可用。";
        }
        MediaProjection created;
        try {
            created = mgr.getMediaProjection(resultCode, data);
        } catch (Throwable t) {
            // Android 14+: a consent Intent is single-use; an old one throws here.
            error = "授权数据不能复用（Android 14+ 只允许一次）：" + shortName(t);
            dropConsentFile();
            return "投屏用不了：" + error + "。请再点一次「投屏：未授权」重新授权。";
        }
        if (created == null) {
            error = "getMediaProjection 返回 null（授权已被系统收回）";
            dropConsentFile();
            return "投屏用不了：" + error + "。请重新授权。";
        }
        DisplayMetrics metrics = new DisplayMetrics();
        try {
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            wm.getDefaultDisplay().getRealMetrics(metrics);
        } catch (Throwable t) {
            error = "读不到屏幕尺寸：" + shortName(t);
            return "投屏失败：" + error;
        }
        int w = metrics.widthPixels;
        int h = metrics.heightPixels;
        int dpi = metrics.densityDpi > 0 ? metrics.densityDpi : 320;
        synchronized (LOCK) {
            dropSessionLocked();
            try {
                startThreadLocked();
                reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
                projection = created;
                projection.registerCallback(callback, handler);
                display = projection.createVirtualDisplay("dsh-capture", w, h, dpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        reader.getSurface(), null, handler);
            } catch (Throwable t) {
                // Android 14+ wants a foreground service with type mediaProjection,
                // and refuses a size that differs from the real display. Both land
                // here; report instead of crashing the app.
                error = "建立投屏会话失败：" + t.getClass().getSimpleName() + ": "
                        + String.valueOf(t.getMessage());
                dropSessionLocked();
                return "投屏失败：" + error;
            }
            width = w;
            height = h;
            density = dpi;
        }
        error = null;
        Log.i(TAG, "projection: virtual display " + w + "x" + h + "@" + dpi);
        return null;
    }

    private static final MediaProjection.Callback callback = new MediaProjection.Callback() {
        @Override
        public void onStop() {
            DeviceShell.log("projection: stopped by the system or the user");
            synchronized (LOCK) {
                dropSessionLocked();
                dropConsentFile();
            }
            error = "投屏已被系统或用户停止";
            DeviceShell.onProjectionChanged("projection: stopped");
        }
    };

    private static void startThreadLocked() {
        if (handler != null) {
            return;
        }
        thread = new HandlerThread("dsh-projection");
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    private static void dropSessionLocked() {
        if (display != null) {
            try {
                display.release();
            } catch (Throwable ignored) {
            }
            display = null;
        }
        if (reader != null) {
            try {
                reader.close();
            } catch (Throwable ignored) {
            }
            reader = null;
        }
        if (projection != null) {
            try {
                projection.unregisterCallback(callback);
            } catch (Throwable ignored) {
            }
            try {
                projection.stop();
            } catch (Throwable ignored) {
            }
            projection = null;
        }
    }

    /** Release everything; used when the user disables the channel. */
    static void release() {
        synchronized (LOCK) {
            dropSessionLocked();
        }
    }

    // -------------------------------------------------------------- capture

    /**
     * Grab one frame as PNG bytes.
     *
     * Returns null when the channel cannot deliver a frame; {@link #error()} then
     * says why (no consent, single-use token consumed, session stopped, frame
     * timeout). A non-null result is always a complete PNG.
     */
    static byte[] capturePng() {
        Context ctx = context;
        if (ctx == null) {
            error = "应用上下文还没准备好";
            return null;
        }
        synchronized (LOCK) {
            if (projection == null) {
                Consent consent = readConsent();
                if (consent == null) {
                    error = "投屏未授权（需要用户点一次「投屏：未授权」并允许录屏）";
                    return null;
                }
                String failure = openSession(consent.resultCode, consent.data);
                if (failure != null) {
                    return null;
                }
            }
            if (reader == null || display == null) {
                error = "投屏会话已失效，需要重新授权";
                return null;
            }
        }
        Image image = null;
        long deadline = System.currentTimeMillis() + FRAME_TIMEOUT_MS;
        try {
            // A brand-new virtual display needs a frame or two before the reader
            // produces anything; a long-lived one has the latest frame at once.
            while (image == null && System.currentTimeMillis() < deadline) {
                try {
                    image = reader.acquireLatestImage();
                } catch (Throwable t) {
                    error = "读帧失败：" + shortName(t);
                    return null;
                }
                if (image == null) {
                    try {
                        Thread.sleep(30);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            if (image == null) {
                error = "投屏 " + FRAME_TIMEOUT_MS + " ms 内没有出帧";
                return null;
            }
            Bitmap bitmap = toBitmap(image);
            if (bitmap == null) {
                error = "帧数据转成位图失败";
                return null;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);
            boolean ok = bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            bitmap.recycle();
            if (!ok) {
                error = "PNG 编码失败";
                return null;
            }
            error = null;
            return out.toByteArray();
        } finally {
            if (image != null) {
                try {
                    image.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** RGBA_8888 Image -> Bitmap, honouring the row padding the GPU adds. */
    private static Bitmap toBitmap(Image image) {
        Image.Plane[] planes = image.getPlanes();
        if (planes.length == 0) {
            return null;
        }
        Image.Plane plane = planes[0];
        ByteBuffer buffer = plane.getBuffer();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int w = image.getWidth();
        int h = image.getHeight();
        int padded = rowStride / Math.max(1, pixelStride);
        int rowPadding = padded - w;
        Bitmap raw = Bitmap.createBitmap(w + Math.max(0, rowPadding), h, Bitmap.Config.ARGB_8888);
        try {
            raw.copyPixelsFromBuffer(buffer);
        } catch (Throwable t) {
            raw.recycle();
            return null;
        }
        if (rowPadding <= 0) {
            return raw;
        }
        Bitmap cropped = Bitmap.createBitmap(raw, 0, 0, w, h);
        raw.recycle();
        return cropped;
    }

    static String sizeText() {
        synchronized (LOCK) {
            return width + "x" + height;
        }
    }

    // ----------------------------------------------------------- persistence

    private static final class Consent {
        final int resultCode;
        final Intent data;

        Consent(int resultCode, Intent data) {
            this.resultCode = resultCode;
            this.data = data;
        }
    }

    private static void saveConsentLocked(int resultCode, Intent data) {
        if (filesDir == null || data == null) {
            return;
        }
        Parcel parcel = Parcel.obtain();
        try {
            data.writeToParcel(parcel, 0);
            String encoded = android.util.Base64.encodeToString(parcel.marshall(),
                    android.util.Base64.NO_WRAP);
            File file = new File(filesDir, CONSENT_FILE);
            FileOutputStream out = new FileOutputStream(file);
            try {
                out.write((resultCode + "\n" + encoded).getBytes("UTF-8"));
            } finally {
                out.close();
            }
            try {
                new ProcessBuilder("/system/bin/chmod", "600", file.getAbsolutePath())
                        .redirectErrorStream(true).start();
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            DeviceShell.log("projection: cannot persist consent: " + t);
        } finally {
            parcel.recycle();
        }
    }

    /** The stored consent, or null. Never throws: a broken file is just deleted. */
    private static Consent readConsent() {
        if (filesDir == null) {
            return null;
        }
        File file = new File(filesDir, CONSENT_FILE);
        if (!file.isFile()) {
            return null;
        }
        try {
            byte[] raw = new byte[(int) Math.min(file.length(), 8192)];
            FileInputStream in = new FileInputStream(file);
            int n;
            try {
                n = in.read(raw);
            } finally {
                in.close();
            }
            String text = new String(raw, 0, Math.max(0, n), "UTF-8").trim();
            int nl = text.indexOf('\n');
            if (nl < 0) {
                dropConsentFile();
                return null;
            }
            int code = Integer.parseInt(text.substring(0, nl).trim());
            byte[] marshalled = android.util.Base64.decode(text.substring(nl + 1),
                    android.util.Base64.NO_WRAP);
            Parcel parcel = Parcel.obtain();
            Intent data;
            try {
                parcel.unmarshall(marshalled, 0, marshalled.length);
                parcel.setDataPosition(0);
                data = Intent.CREATOR.createFromParcel(parcel);
            } finally {
                parcel.recycle();
            }
            return data == null ? null : new Consent(code, data);
        } catch (Throwable t) {
            DeviceShell.log("projection: stored consent unreadable (" + t + ")");
            dropConsentFile();
            return null;
        }
    }

    private static void dropConsentFile() {
        if (filesDir == null) {
            return;
        }
        try {
            new File(filesDir, CONSENT_FILE).delete();
        } catch (Throwable ignored) {
        }
    }

    private static String shortName(Throwable t) {
        String message = t.getMessage();
        return t.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    /** Unused today, kept so a future revision can report the SDK it runs on. */
    static int sdk() {
        return Build.VERSION.SDK_INT;
    }
}
