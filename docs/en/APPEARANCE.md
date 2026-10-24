English | [中文](../APPEARANCE.md)

# Launcher Appearance (Colors / Background Image)

When the app starts, it first displays the **native console** (title, version,
`Starting the bundled runtime…`, five buttons, and runtime
logs). Once the embedded runtime is up, the DSH interface in the WebView replaces
it.

This interface consists of native Views built programmatically in `MainActivity`;
**it is not a web page**. Its colors used to be constants hardcoded in Java, so
changing a color meant editing the code, rebuilding, and reinstalling the APK.

It now reads a text file on the phone:

```
/sdcard/DSH/launcher/theme.conf      ← Colors + background image path
/sdcard/DSH/launcher/bg.jpg          ← No configuration: put it here to use it as the background automatically
```

Changes **take effect on the next launch, with no rebuild required**. That is a
reasonable iteration cost for an interface that only appears during those few
seconds of unpacking.

---

## 1. Quickest Setup: Add a Background Image

Save any image as `/sdcard/DSH/launcher/bg.jpg` (`bg.jpeg`, `bg.png`, and `bg.webp`
are also recognized), then restart the app. No configuration file is needed.

The console automatically takes on a "frosted glass" appearance: panels, cards,
and buttons become slightly transparent to let the image show through. A dark
overlay (`scrim`) covers the whole image to keep white text readable.

A confirmation line appears in the log:

```
launcher theme: config=/sdcard/DSH/launcher/theme.conf background=/sdcard/DSH/launcher/bg.jpg scrim=0.62 card_opacity=0.88
```

## 2. Configuration File

On its first run, the app writes an annotated `theme.example.conf` to the same
directory. This is only an example and **is not read**. To change the colors,
copy it to `theme.conf` and edit it:

```bash
cp /sdcard/DSH/launcher/theme.example.conf /sdcard/DSH/launcher/theme.conf
```

Format: one `key = value` per line. A `#` **at the start of a line** marks a
comment (`#` in a color value is part of the value and is unaffected). Unknown
keys and values that cannot be parsed are ignored. An invalid configuration
**will not prevent the launcher from starting**, because this interface needs to
remain available to display logs when something goes wrong.

```ini
# Background image: an absolute path, or a path relative to this directory
background = /sdcard/Pictures/wallpaper.jpg

scrim        = 0.62     # Overall background image opacity (dark overlay), 0..1
card_opacity = 0.88     # Panel/card opacity, 0..1 (only applies with a background image)
radius       = 14       # Card corner radius, dp
title        = DeepSeek Harness

bg           = #0B1220  # Window/status bar/navigation bar background; also the image fallback color
card         = #16223C  # Cards, buttons, log box
card_pressed = #1E2C4A
stroke       = #2A3A5C  # Card border
primary      = #4D6BFE  # Primary button (DeepSeek blue)
text         = #EAF0FA  # Title and button text
dim          = #93A4BF  # Version number and "Run log" label
ok           = #4ADE80  # "Ready"
warn         = #FBBF24  # "Starting…"
err          = #F87171  # "Exited", update failure
log          = #A8C0DC  # Log body text
```

Color formats: `#RRGGBB` or `#AARRGGBB` (the `#` may also be omitted or replaced
with a `0x` prefix). Opacity formats: a decimal from `0..1`, or an integer from
`0..255` (values greater than 1 are interpreted on a scale of 255).

For solid colors without an image, remove the `background` line and make sure
there are no `bg.*` files in the directory. In this case, `card_opacity` is ignored
(panels remain opaque), giving exactly the same appearance as before.

## 3. Search Order

Both the configuration file and the background image used when no path is
specified are searched for in this order. The first match wins:

1. `/sdcard/DSH/launcher/` — shared storage; requires "Grant file access".
2. `/sdcard/Android/data/com.dsh.android/files/launcher/` — the app's own external
   directory; **requires no permissions**, so the feature can be verified before
   access is granted.

A relative `background` path is resolved against **the directory where the
configuration was found**.

## 4. Permissions and Fallback Behavior

Reading shared storage requires permission. The app's "Grant file access" button
opens the system's "All files access" settings. If access has not been granted:

- The interface still appears as usual, falling back to a solid background. It
  will not crash, show a blank screen, or hang.
- The log includes `launcher theme: background unreadable or undecodable (...)`.
- After access is granted, **returning to the app automatically retries**
  (triggered by `onResume`, with a maximum of 3 attempts to avoid decoding the
  image every time the app comes to the foreground).

## 5. Background Image Implementation Details

- **Automatic downsampling**: decoding uses `inSampleSize` based on the screen
  width, so a 12-megapixel photo will not consume tens of MB of heap memory.
- **Automatic orientation correction**: phone photos store rotation information
  in EXIF, which is used to orient them correctly (otherwise portrait photos
  would appear sideways). Non-JPEG images and images without EXIF are used with
  their pixels as stored.
- **Scaling**: `CENTER_CROP` fills the screen and crops the excess without
  stretching or distortion.
- **Status bar / navigation bar**: these still use the opaque, solid `bg` color.
  Blending them into the image would require changes to the window inset logic,
  which is outside the scope of this change.

## 6. Troubleshooting

| Symptom | What to Check First |
|---|---|
| The image does not appear | Look for the `launcher theme:` line in the log: `background=none` means no image was found (check the path / filename / permissions). |
| The log contains `background=…`, but the interface still has a solid background | The next line will say `unreadable or undecodable`: the image is damaged, its format is unsupported, or access has not yet been granted. |
| The colors have not changed | Values must be `#RRGGBB` or `#AARRGGBB`; invalid keys/values are silently ignored (by design). |
| The interface is entirely white/light | `bg` and `card` are set to light colors, while `text`/`log` are still light → insufficient contrast; change the text to a darker color. |
| Edits have no effect | The configuration is read once **at launch**; restart the app to apply changes. |

> To confirm that your changes really made it into the APK after packaging (`dx`
> can silently drop classes; see item 7 in [`PITFALLS.md`](PITFALLS.md)):
>
> ```bash
> python3 -c "
> import zipfile
> d = zipfile.ZipFile('DSH-Android.apk').read('classes.dex')
> for s in [b'theme.conf', b'launcher theme: ', b'ExifInterface', b'bg.jpeg']:
>     print(('OK  ' if s in d else 'MISS'), s.decode())
> "
> ```

## 7. Related Code

| File | Purpose |
|---|---|
| `app/src/com/dsh/android/LauncherTheme.java` | Reads the configuration, parses colors/opacity, and decodes and corrects the orientation of the background image. |
| `app/src/com/dsh/android/MainActivity.java` | `buildUi()` builds the interface using the theme; `applyBackgroundIfNeeded()` loads the background asynchronously. |
