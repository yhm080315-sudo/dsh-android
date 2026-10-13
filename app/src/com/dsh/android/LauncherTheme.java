package com.dsh.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;

/**
 * Appearance of the native launcher console: its palette and an optional
 * background image.
 *
 * Every colour used to be a {@code private static final int} inside
 * {@link MainActivity}, so changing one meant editing Java, rebuilding the APK
 * and reinstalling it -- for a screen that is only on display while the runtime
 * unpacks. The console now reads a small text file from shared storage instead,
 * which makes the look changeable from the phone itself:
 *
 * <pre>
 *   /sdcard/DSH/launcher/theme.conf    key = value, '#' at line start comments
 *   /sdcard/DSH/launcher/bg.jpg        picked up with no config file at all
 * </pre>
 *
 * The app's own external files directory is searched too, so a config and an
 * image also work before file access has been granted. Values that cannot be
 * parsed are ignored rather than fatal: a broken config must never keep this
 * screen from starting, because this screen is what reports the breakage.
 */
final class LauncherTheme {

    /** Shared-storage directory; readable once file access is granted. */
    static final String SHARED_DIR = "/sdcard/DSH/launcher";
    private static final String CONFIG_NAME = "theme.conf";
    private static final String EXAMPLE_NAME = "theme.example.conf";
    /** Tried, in order, when the config names no image of its own. */
    private static final String[] IMAGE_NAMES = {"bg.jpg", "bg.jpeg", "bg.png", "bg.webp"};

    // ---- defaults: a deep-blue console, a shade brighter than the Web shell --
    int bg = 0xFF0B1220;
    int card = 0xFF16223C;
    int cardPressed = 0xFF1E2C4A;
    int stroke = 0xFF2A3A5C;
    int primary = 0xFF4D6BFE;   // DeepSeek blue
    int text = 0xFFEAF0FA;
    int dim = 0xFF93A4BF;
    int ok = 0xFF4ADE80;
    int warn = 0xFFFBBF24;
    int err = 0xFFF87171;
    int log = 0xFFA8C0DC;

    /**
     * Opacity of the background image.
     *
     * This used to default to 0.62, which dimmed the picture into the dark base
     * colour and read as "too dark" no matter how bright the photo was. Legibility
     * now comes from a top-heavy veil and from the glass surfaces on top, so the
     * image itself can stay nearly opaque.
     */
    float scrim = 0.96f;
    /** Multiplier applied to the image's own brightness (1 = untouched). */
    float brightness = 1.14f;
    /** Strength of the vertical veil over the image, for text legibility. */
    float veil = 0.5f;
    /** Opacity of the panels drawn over that image; 1 = opaque. */
    float cardOpacity = 0.88f;
    /** Corner radius of the cards, in dp. */
    float radius = 14f;
    /** Console title; kept identical to the app name unless overridden. */
    String title = "DeepSeek Harness";
    /** Resolved image, or null when the console should stay a flat colour. */
    File background;
    /** Config actually read, or the location it would be read from. */
    File config;
    /** Directories searched for the config and for an unnamed image. */
    private ArrayList<File> dirs = new ArrayList<File>();

    private LauncherTheme() {
    }

    /** Read the config (if any) and resolve which image, if any, to draw. */
    static LauncherTheme load(Context context) {
        LauncherTheme theme = new LauncherTheme();
        ArrayList<File> dirs = candidateDirs(context);
        theme.dirs = dirs;
        File chosen = null;
        for (int i = 0; i < dirs.size(); i++) {
            File conf = new File(dirs.get(i), CONFIG_NAME);
            if (conf.isFile()) {
                chosen = conf;
                break;
            }
        }
        theme.config = chosen != null ? chosen : new File(dirs.get(0), CONFIG_NAME);
        if (chosen != null) {
            theme.parse(chosen);
        }
        theme.resolveBackground(chosen != null ? chosen.getParentFile() : null, dirs);
        return theme;
    }

    /**
     * Shared storage first, then the app's own external directory. Creating the
     * first one needs the file-access grant, so a failure here is expected on a
     * fresh install and simply falls through to the second.
     */
    private static ArrayList<File> candidateDirs(Context context) {
        ArrayList<File> dirs = new ArrayList<File>();
        File shared = new File(SHARED_DIR);
        try {
            if (shared.isDirectory() || shared.mkdirs()) {
                dirs.add(shared);
            }
        } catch (Throwable ignored) {
        }
        try {
            File ext = context.getExternalFilesDir(null);
            if (ext != null) {
                File own = new File(ext, "launcher");
                if (own.isDirectory() || own.mkdirs()) {
                    dirs.add(own);
                }
            }
        } catch (Throwable ignored) {
        }
        if (dirs.isEmpty()) {
            dirs.add(new File(SHARED_DIR));
        }
        return dirs;
    }

    /**
     * {@code key = value} per line, {@code #} only at the start of a line --
     * values are hex colours, so a '#' inside one must stay part of the value.
     */
    private void parse(File conf) {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(conf), "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                // Named "trimmed", not "text": the field of that name is one of
                // the keys being parsed below.
                String trimmed = line.trim();
                if (trimmed.length() == 0 || trimmed.startsWith("#")) {
                    continue;
                }
                int eq = trimmed.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = trimmed.substring(0, eq).trim().toLowerCase();
                String value = unquote(trimmed.substring(eq + 1).trim());
                if (key.equals("background") || key.equals("bg_image")) {
                    background = new File(value);
                } else if (key.equals("bg")) {
                    bg = color(value, bg);
                } else if (key.equals("card")) {
                    card = color(value, card);
                } else if (key.equals("card_pressed")) {
                    cardPressed = color(value, cardPressed);
                } else if (key.equals("stroke")) {
                    stroke = color(value, stroke);
                } else if (key.equals("primary")) {
                    primary = color(value, primary);
                } else if (key.equals("text")) {
                    text = color(value, text);
                } else if (key.equals("dim")) {
                    dim = color(value, dim);
                } else if (key.equals("ok")) {
                    ok = color(value, ok);
                } else if (key.equals("warn")) {
                    warn = color(value, warn);
                } else if (key.equals("err")) {
                    err = color(value, err);
                } else if (key.equals("log")) {
                    log = color(value, log);
                } else if (key.equals("scrim")) {
                    scrim = opacity(value, scrim);
                } else if (key.equals("brightness")) {
                    brightness = number(value, brightness);
                } else if (key.equals("veil")) {
                    veil = opacity(value, veil);
                } else if (key.equals("card_opacity")) {
                    cardOpacity = opacity(value, cardOpacity);
                } else if (key.equals("radius")) {
                    radius = number(value, radius);
                } else if (key.equals("title")) {
                    if (value.length() > 0) {
                        title = value;
                    }
                }
            }
        } catch (Throwable t) {
            // A missing or unreadable config is normal; the defaults stand.
        } finally {
            closeQuietly(reader);
        }
    }

    private void resolveBackground(File configDir, ArrayList<File> dirs) {
        if (background != null) {
            File named = background;
            if (!named.isAbsolute() && configDir != null) {
                named = new File(configDir, background.getPath());
            }
            background = named.isFile() ? named : null;
            if (background != null) {
                return;
            }
        }
        for (int d = 0; d < dirs.size(); d++) {
            for (int n = 0; n < IMAGE_NAMES.length; n++) {
                File candidate = new File(dirs.get(d), IMAGE_NAMES[n]);
                if (candidate.isFile()) {
                    background = candidate;
                    return;
                }
            }
        }
    }

    /** Opacity for panel fills: 1 whenever no image sits underneath them. */
    float panelOpacity() {
        return background == null ? 1f : cardOpacity;
    }

    /**
     * Decode the background, downsampled to roughly {@code targetWidth} so a
     * 12-megapixel photo cannot cost 48 MB of heap. Returns null (never throws)
     * when the file is unreadable, which is what happens before the file-access
     * grant, and is why callers must be able to fall back to a flat colour.
     */
    Bitmap decode(int targetWidth) {
        File file = background;
        if (file == null) {
            return null;
        }
        Bitmap bitmap = null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null;
            }
            int sample = 1;
            if (targetWidth > 0) {
                while (bounds.outWidth / (sample * 2) >= targetWidth) {
                    sample *= 2;
                }
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        } catch (Throwable t) {
            return null;
        }
        return bitmap == null ? null : orient(bitmap);
    }

    /**
     * Phone photos carry their rotation in EXIF rather than in the pixels, so a
     * portrait shot decoded as-is lands on its side. Nothing else here depends
     * on EXIF: no rotation found means the bitmap is already correct.
     */
    private Bitmap orient(Bitmap bitmap) {
        int degrees = 0;
        boolean mirror = false;
        try {
            ExifInterface exif = new ExifInterface(background.getAbsolutePath());
            int value = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL);
            if (value == ExifInterface.ORIENTATION_ROTATE_90) {
                degrees = 90;
            } else if (value == ExifInterface.ORIENTATION_ROTATE_180) {
                degrees = 180;
            } else if (value == ExifInterface.ORIENTATION_ROTATE_270) {
                degrees = 270;
            } else if (value == ExifInterface.ORIENTATION_FLIP_HORIZONTAL) {
                mirror = true;
            } else if (value == ExifInterface.ORIENTATION_FLIP_VERTICAL) {
                mirror = true;
                degrees = 180;
            } else if (value == ExifInterface.ORIENTATION_TRANSPOSE) {
                mirror = true;
                degrees = 90;
            } else if (value == ExifInterface.ORIENTATION_TRANSVERSE) {
                mirror = true;
                degrees = 270;
            }
        } catch (Throwable ignored) {
            // Not a JPEG, no EXIF block, or unreadable: use the pixels as-is.
        }
        if (degrees == 0 && !mirror) {
            return bitmap;
        }
        try {
            Matrix matrix = new Matrix();
            if (mirror) {
                matrix.postScale(-1f, 1f);
            }
            if (degrees != 0) {
                matrix.postRotate(degrees);
            }
            Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0,
                    bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            if (rotated != bitmap) {
                bitmap.recycle();
            }
            return rotated;
        } catch (Throwable t) {
            return bitmap;
        }
    }

    /**
     * Drop an annotated example next to where the config is expected, once. The
     * file is never read (only {@code theme.conf} is), so it cannot quietly
     * change the console; it exists to make the keys discoverable from a phone
     * file manager, without opening the README.
     */
    void writeExample() {
        File dir = config != null && config.getParentFile() != null
                ? config.getParentFile()
                : (dirs.isEmpty() ? new File(SHARED_DIR) : dirs.get(0));
        File out = new File(dir, EXAMPLE_NAME);
        if (out.exists()) {
            return;
        }
        FileOutputStream stream = null;
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("# DSH launcher console appearance.\n");
            sb.append("# Copy to theme.conf and edit; values below are the defaults\n");
            sb.append("# in force right now. Delete a line to fall back to its default.\n");
            sb.append("# Colours are #RRGGBB or #AARRGGBB. Opacities are 0..1.\n");
            sb.append("#\n");
            sb.append("# Background image: an absolute path, a path relative to this\n");
            sb.append("# directory, or leave 'background' out and simply drop bg.jpg\n");
            sb.append("# (bg.jpeg / bg.png / bg.webp) next to this file.\n");
            sb.append("\n");
            sb.append("# background = /sdcard/Pictures/wallpaper.jpg\n");
            sb.append("scrim = ").append(trim(scrim)).append("\n");
        sb.append("brightness = ").append(trim(brightness)).append("\n");
        sb.append("veil = ").append(trim(veil)).append("\n");
            sb.append("card_opacity = ").append(trim(cardOpacity)).append("\n");
            sb.append("radius = ").append(trim(radius)).append("\n");
            sb.append("title = ").append(title).append("\n");
            sb.append("\n");
            sb.append("bg = ").append(hex(bg)).append("\n");
            sb.append("card = ").append(hex(card)).append("\n");
            sb.append("card_pressed = ").append(hex(cardPressed)).append("\n");
            sb.append("stroke = ").append(hex(stroke)).append("\n");
            sb.append("primary = ").append(hex(primary)).append("\n");
            sb.append("text = ").append(hex(text)).append("\n");
            sb.append("dim = ").append(hex(dim)).append("\n");
            sb.append("ok = ").append(hex(ok)).append("\n");
            sb.append("warn = ").append(hex(warn)).append("\n");
            sb.append("err = ").append(hex(err)).append("\n");
            sb.append("log = ").append(hex(log)).append("\n");
            stream = new FileOutputStream(out);
            stream.write(sb.toString().getBytes("UTF-8"));
            stream.flush();
        } catch (Throwable ignored) {
            // Read-only storage or no grant yet: an example is a convenience.
        } finally {
            closeQuietly(stream);
        }
    }

    /** One line for the runtime log: which config and image are in force. */
    String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("config=").append(config == null ? "none" : config.getAbsolutePath());
        sb.append(" background=").append(background == null ? "none" : background.getAbsolutePath());
        sb.append(" scrim=").append(trim(scrim));
        sb.append(" card_opacity=").append(trim(cardOpacity));
        return sb.toString();
    }

    // ---- value parsing ------------------------------------------------------

    /** {@code #RRGGBB}, {@code #AARRGGBB}, with or without the hash. */
    static int color(String value, int fallback) {
        try {
            String hex = value.trim();
            if (hex.startsWith("#")) {
                hex = hex.substring(1);
            } else if (hex.startsWith("0x") || hex.startsWith("0X")) {
                hex = hex.substring(2);
            }
            if (hex.length() == 6) {
                hex = "FF" + hex;
            }
            if (hex.length() != 8) {
                return fallback;
            }
            return (int) Long.parseLong(hex, 16);
        } catch (Throwable t) {
            return fallback;
        }
    }

    /** {@code 0..1}, or {@code 0..255} when the value is clearly an alpha. */
    static float opacity(String value, float fallback) {
        float parsed = number(value, -1f);
        if (parsed < 0f) {
            return fallback;
        }
        if (parsed > 1f) {
            parsed = parsed / 255f;
        }
        if (parsed > 1f) {
            parsed = 1f;
        }
        return parsed;
    }

    static float number(String value, float fallback) {
        try {
            return Float.parseFloat(value.trim());
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1).trim();
            }
        }
        return value;
    }

    private static String hex(int color) {
        String digits = Integer.toHexString(color & 0xFFFFFF).toUpperCase();
        while (digits.length() < 6) {
            digits = "0" + digits;
        }
        return "#" + digits;
    }

    private static String trim(float value) {
        if (value == Math.round(value)) {
            return Integer.toString(Math.round(value));
        }
        return Float.toString(value);
    }

    static void closeQuietly(java.io.Closeable stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (Throwable ignored) {
        }
    }

    /** Compose a copy of {@code color} at {@code opacity} (0..1). */
    static int withOpacity(int color, float opacity) {
        int alpha = Math.round(opacity * 255f);
        if (alpha < 0) {
            alpha = 0;
        }
        if (alpha > 255) {
            alpha = 255;
        }
        return (color & 0x00FFFFFF) | (alpha << 24);
    }
}
