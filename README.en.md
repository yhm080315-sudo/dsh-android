English | [中文](README.md)

# DSH for Android

Package [DeepSeek Harness](https://www.npmjs.com/package/@deepseek-ai/dsh) (DSH below) as a **standalone Android app**.

Install one APK and start using it: **no Termux, no proot, no root, and no computer required**. The entire runtime (Node, bash, ripgrep, coreutils) is embedded in the APK.

> **Status**: Working. Verified on a Xiaomi 2509FPN0BC / Android 17 (screenshots, taps, file reads and writes, image uploads, and the plugin system all work).
> **Tradeoff**: `targetSdk` must be 28, so the app **cannot be published on Google Play**. See [docs/en/PITFALLS.md](docs/en/PITFALLS.md) for the reasons.

---

## What it looks like

- **The WebView runs the actual DSH web interface**—built from the same source code, rather than a rewrite
- The main interface, plugins, themes, and agents are all the same as on desktop
- An additional built-in **device control interface** (screenshots / taps / swipes / text input / OCR / launching apps) runs through `su`
- Sessions, attachments, and credentials are stored in the app's private directory and survive upgrades
- The **native startup console also supports custom colors and background images**; just edit a text file on the phone, with no need to rebuild the APK

---

## Startup screen appearance

On launch, the app first displays a native console (title / status / buttons / runtime logs), which reads its configuration from the phone:

```ini
# /sdcard/DSH/launcher/theme.conf
background   = /sdcard/Pictures/wallpaper.jpg   # Or just place bg.jpg in the same directory
scrim        = 0.62      # Background image opacity (darkening overlay)
card_opacity = 0.88      # Panel/card opacity; only relevant with a background image
bg           = #0B1220   # Base color, also used as the image fallback
card         = #16223C
primary      = #4D6BFE
text         = #EAF0FA
```

Changes take effect **the next time the app starts**. If permission has not been granted or the image cannot be read, the app automatically falls back to solid colors and continues working normally.
On first launch, the app writes a `theme.example.conf` containing all keys and their default values for you to copy.
For the full list of keys, fallback behavior, and troubleshooting table, see **[docs/en/APPEARANCE.md](docs/en/APPEARANCE.md)**.

---

## Quick start

### Prerequisites

- A Linux build machine (x86_64 or aarch64)
- JDK 21
- `android-sdk/build-tools/29.0.3` (provides `aapt2` / `zipalign` / `apksigner` / `dalvik-exchange`)
- Android platform jar (**API 30**)
- Node.js (used by `install-dsh.sh` to download packages)

**Note: Gradle is not required.** This project packages the app manually with `aapt2 → javac → dx → zipalign → apksigner`.

### Build in three steps

```bash
# 1. Clean-install DSH (download the npm package into bionic-dsh/)
bash scripts/install-dsh.sh

# 2. Assemble the embedded runtime (Termux bionic binaries + DSH + patches)
bash scripts/stage-bionic-assets.sh

# 3. Build the APK
bash scripts/build-app.sh
```

Output: `DSH-Android.apk`, signed with `keystore.jks` (generated on the script's first run; **keep it safe—if you lose it, you cannot install future upgrades over the existing app**).

### Released APKs and version numbers

Version numbers are defined in exactly one place: the **`VERSION`** file at the repository root. Build scripts, APK filenames,
`AndroidManifest.xml`, and documentation all read from it, instead of maintaining duplicate definitions.

```
APP_VERSION=1.0.1            This project's version (corresponding to Git tag v1.0.1)
APP_VERSION_CODE=10001       Android versionCode (= 1*10000 + 0*100 + 1)
DSH_VERSION=0.2.0-rc.2       Embedded DeepSeek Harness version
```

The APK's `versionName` includes both versions: **`1.0.1+dsh.0.2.0-rc.2`**,
and the output filename is `DSH-Android-v1.0.1-dsh0.2.0-rc.2.apk`.
The app's startup log also prints a line in the form `versions: app … · dsh …`—
when reporting a bug, this line identifies the version of each layer.

**SHA-256 signing certificate fingerprint for released APKs (to verify the authenticity of downloads):**

```
76:F0:49:3B:E7:75:8E:94:4F:30:A0:6D:65:95:DE:70:B7:24:FF:B0:D1:C4:B4:8F:90:FD:CF:B5:11:D5:4B:B9
```

```bash
apksigner verify --print-certs DSH-Android-v1.0.1-dsh0.2.0-rc.2.apk | grep "SHA-256 digest"
```

⚠️ **If you installed an official APK, you cannot upgrade it in place with an APK you build yourself**—the signatures differ, so Android will refuse the installation.
You must uninstall it first (which clears the app's data). If you plan to use your own builds long term, use your own keystore from the very first installation.

### Patching the embedded payload

DSH was written for Linux/macOS, so several issues must be addressed on Android (hard links, seccomp, native modules, and more).
These changes are maintained as patches:

```bash
bash patches/apply-payload-patches.sh <runtime directory>
```

Each patch can be applied repeatedly (it matches anchor strings and skips patches that are already present). See [patches/README.en.md](patches/README.en.md) for details.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ MainActivity                                                                │
│  ├─ Log panel / Chinese control buttons / WebView host                      │
│  ├─ LauncherTheme   Console colors + background image (/sdcard config)      │
│  ├─ onShowFileChooser → System picker → Local staging → Page                │
│  └─ onConsoleMessage → Write browser console messages to app logs           │
├─────────────────────────────────────────────────────────────────────────────┤
│ NodeServer (foreground service)                                             │
│  ├─ prepare()      Unpack payload (rename old tree; never delete in place)  │
│  ├─ prepareProfile() Create the profile's node_modules view                 │
│  ├─ Start dsh web; mirror stdout/stderr to /sdcard logs                     │
│  └─ Environment: PATH / HOME / TMPDIR / LD_LIBRARY_PATH …                   │
├─────────────────────────────────────────────────────────────────────────────┤
│ TarExtractor   Handwritten tar parser (PAX / GNU long names / checkpoints)  │
│ DeviceServer   127.0.0.1 device control (su channel, argument allowlist)    │
│ DiagnosticServer  Read-only status endpoint (inside the app)                │
│ SelfUpdater / UpdateProvider  Self-update (optional)                        │
└─────────────────────────────────────────────────────────────────────────────┘
          ↓ Embedded payload (assets/runtime.tar.gz)
   usr/bin/{node,bash,rg,cat,ls,cp,…}   bionic binaries
   dsh/node_modules/@deepseek-ai/…      DSH and plugins
```

### Startup sequence

1. Unpack `assets/runtime.tar.gz` into `files/runtime/` (about 600 MB after extraction)
2. Set up `$DSH_HOME/profiles/web/node_modules` (a real directory with a view built from symbolic links)
3. Sideload `/sdcard/DSH/plugins/*.tgz` (optional plugins)
4. Start `node dsh/lib/bin.js web --port 3220`
5. Poll the page until the client-side plugin graph has been injected, then attach the WebView

---

## Key design decisions

### Why bionic instead of glibc or musl

The seccomp filter for Android apps kills any runtime that registers `rseq` at startup:

| Runtime | Result |
|---|---|
| glibc (static / dynamic) | `exit=159` (128+SIGSYS), **cannot start at all** |
| musl | `--version` works, but running JS immediately exits with 159 |
| **bionic (Termux build)** | ✅ Everything works |

All embedded runtime components therefore come from Termux's bionic builds. This is the project's most fundamental finding.

### Why targetSdk is 28

Android 10+'s W^X policy prevents apps from executing files they have written themselves—which is exactly what this approach does: "unpack binaries into the app directory, then execute them."
`targetSdk ≤ 28` is the only permitted way around this policy (without root).

**Tradeoff**: The app cannot be published on Google Play (which sets a minimum targetSdk requirement). This is an inherent tradeoff of the approach, not a fixable bug.

### Why cleanup renames the old tree instead of deleting it

The entire payload is replaced during upgrades. The old tree may contain root-owned files (left over from early experiments),
which the app can neither modify nor delete—deleting in place fails silently, and extraction then gets stuck on a file with `EACCES`.

**Renaming only requires write permission on the parent directory**, so the old tree is first moved with `rename` to `runtime.stale-<timestamp>`,
then deleted (it does not matter if deletion fails). Extraction always starts with an empty directory.

### Why extraction does not resume

Resumable extraction was implemented once (recording the entry number and offset). But a checkpoint is only valid for **the archive that created it**—
resume with a different archive and it reads garbage, failing repeatedly on the same file.

**Extraction now starts from an empty tree every time** (about 6 seconds). Saving 6 seconds is not worth a pile of stale state.

---

## How this compares with other mobile approaches
## How this compares with other mobile approaches

There are currently two ways to run dsh on Android, each with its own costs. Explaining those costs is more useful than labeling one another—neither replaces the other;
they simply put the costs in different places.

|  | Container approach (proot + full glibc rootfs) | This project (Termux bionic) |
|---|---|---|
| **Approach** | proot/proroot + a full distribution rootfs | Run Termux packages directly on Android bionic |
| **Installation** | Just install the APK | Just install the APK (this project already packages everything) |
| **Environment** | Full Ubuntu; use `apt` freely; native modules work directly | Requires **patching/rebuilding each component** for bionic |
| **Runtime overhead** | Container layer (proroot has already eliminated ptrace overhead) | No container layer; theoretically the fastest |
| **Installing new software** | `apt install` | Build a bionic version yourself, or bundle it into the payload |
| **Size** | An entire distribution | Only the few binaries actually needed |
| **Sandbox** | Both approaches are restricted | Both approaches are restricted |
| **Keeping up with upstream** | Replace the rootfs / reinstall packages, at **almost no cost** | **Reapply patches whenever upstream changes** |

### An honest look at the tradeoffs

**This project moves the cost from runtime to maintenance.** The payload currently has more than a dozen patches (`patches/apply-payload-patches.sh`),
each addressing a difference in bionic or the app sandbox: unavailable `flock`, rejected `link()` calls, directory `fsync` reaching the root,
glibc native modules failing to load, `dx` silently dropping classes, and so on. **These patches are an ongoing cost**: whenever upstream changes the relevant code,
they must be located, reapplied, and verified again (`docs/en/PITFALLS.md` and the pre-release checks exist for this reason).

**If the goal is to keep up with upstream over the long term**, the container approach is easier in this situation;
**if the goal is a smaller footprint, fast startup, and no dependency on root**, this project is a better fit.

### Device capabilities: both approaches need elevated privileges

This project's device capabilities (screen reading, taps, notifications, clipboard, sensors) are exposed through interfaces provided by the app itself. The equivalent operations in the container approach
usually call `su` directly—**but this is not a difference in whether root is required; both need it**:

```
screencap    Only shell or root can call this; ordinary apps cannot
input        Same as above
```

This project's screen-operation plugin and device bridge currently use `su -c screencap` / `su -c input`.
To eliminate the dependency on root, one of the following two approaches would be needed; **neither has been implemented in this project**:

- **Shizuku**: The user grants permission once to obtain adb-level privileges. `screencap` / `input` still work, without root.
- **MediaProjection + an accessibility service**: Entirely at the app level, with separate one-time authorization for frame capture and input injection,
  but injection is subject to accessibility restrictions, and frame capture requires an ongoing screen-capture session.

In other words, **both approaches currently require elevated privileges**; the difference is the form of elevation and the steps the user must take.


## Why not use Termux

The Termux route (Termux + proot-distro) can run any Linux program, but comes with these costs:

1. Users must install Termux, change package mirrors, install a distribution, and install Node—**too high a barrier for ordinary users**
2. proot translates system calls in user space, noticeably slowing I/O
3. Users must maintain the environment manually inside Termux

This project **embeds the entire runtime in the APK**, so users only need to install one package.

The tradeoffs are that binaries must be extracted before execution (so `targetSdk` must be 28), only bionic binaries can be used,
and there are **many platform pitfalls**—all documented in [docs/en/PITFALLS.md](docs/en/PITFALLS.md).

---

## Frequently asked questions

**Q: Why is `targetSdk` 28? Can it be changed?**
No. Android 10+'s W^X policy prevents apps from executing files they have written themselves, and this approach does exactly that: "extract into the app directory, then execute."
`targetSdk ≤ 28` is the only permitted workaround. The tradeoff is that the app cannot be published on Google Play.

**Q: Why is the APK so large?**
It embeds the full Node.js runtime (45 MB), DSH and its 177 dependencies, plus a set of coreutils.
The extracted size is about 600 MB, so the first launch takes a few seconds to unpack it.

**Q: Can iOS be supported?**
No. iOS does not allow fork/exec or execution of dynamically generated code, so the premise of this approach does not hold.

**Q: Why can images be displayed while some image-processing features are unavailable?**
Native image libraries (`sharp` / libvips) have no Android version. This project uses pure JS to parse image headers,
and sends the original image when resizing would otherwise be needed (the vision API's per-image limit is 32 MiB, which is sufficient).
Actual image editing (cropping, filters, format conversion) is unavailable in this environment.

**Q: Where are plugins installed? Will an upgrade remove them?**
They are installed in `files/home/.dsh/profiles/web/node_modules` (a **real directory** in the user data area).
Runtime packages are linked into it with symbolic links, so replacing the runtime during an upgrade does not affect your plugins.

**Q: How do I install third-party plugins?**
Place npm tarballs in `/sdcard/DSH/plugins/` and restart the app to install them automatically.
Check each plugin's license—**some themes prohibit commercial use** (see [THIRD-PARTY.md](THIRD-PARTY.md)).

---

## Credential mirroring (security tradeoff)

The app mirrors `.credentials.yaml` to `/sdcard/DSH/.dsh-state/`, so **you do not have to re-enter your API key after uninstalling and reinstalling**.

The tradeoff is that this mirror lives in shared storage and can be read by other apps with storage permission.
(Also, the `chmod 600` line is cosmetic on FUSE—the displayed mode is not access control.)

**To actually disable this behavior, create a flag file** (the mirror is recreated at every startup, so deleting the directory alone does not help;
it will return on the next launch):

```bash
mkdir -p /sdcard/DSH/.dsh-state && touch /sdcard/DSH/.dsh-state/NO-BACKUP
```

When this file exists, the app neither mirrors nor restores credentials (both `restoreUserState` / `backupUserState` are skipped),
so you will need to re-enter your API key after uninstalling and reinstalling. Delete the flag file to restore mirroring.

## Known limitations

| Limitation | Explanation |
|---|---|
| **No iOS** | iOS does not allow fork/exec, making this approach fundamentally unsuitable |
| **Cannot publish on Play** | See targetSdk 28 above |
| **Large APK** | About 130–180 MB (embeds the entire Node runtime) |
| **Native modules** | npm packages that depend on precompiled `.node` files have no Android version and must be handled manually |
| **Slow first launch** | Extraction takes about 6 seconds (one-time) |

---

## Platform pitfalls

**This section is the most valuable part of the project.** See [docs/en/PITFALLS.md](docs/en/PITFALLS.md) for the full account,
including the **symptoms, root cause, verification method, and fix** for every issue:

- seccomp differences between glibc / musl / bionic (including the `tsys.bin` syscall troubleshooting tool)
- Unsupported `flock` → correct fallback in a single-process environment
- Rejected `link()` → atomic publication must use "lock + rename", **never copyFile**
- Directory `fsync` syncs all the way to the root → `EACCES`
- Native image libraries such as `sharp` fail to load → replaced with pure JS header parsing
- WebView does not handle `<input type=file>` by default (and the result must be returned, or it stays stuck indefinitely)
- `dx` **silently drops** an entire class if it references newer APIs (successful compilation ≠ code included in the package)
- Empty client-side plugin graph → the page is only 3709 bytes

---

## License

- **This project's code** (`app/`, `scripts/`, `patches/`, `tools/`): MIT; see [LICENSE](LICENSE)
- **Embedded DSH**: MIT (`@deepseek-ai/dsh` and its 177 dependencies)
- **Embedded Termux binaries**: Their respective licenses, including **GPL** components (coreutils, bash, busybox)
- See [THIRD-PARTY.md](THIRD-PARTY.md) for the third-party inventory and obligations

⚠️ **This repository contains no third-party plugins**. Theme plugins often have restrictive licenses
(for example, the whale theme uses `CC-BY-NC-SA-4.0`, prohibiting commercial use and requiring ShareAlike). Evaluate the license yourself before installing.

---

## Acknowledgments

- [DeepSeek Harness](https://www.npmjs.com/package/@deepseek-ai/dsh) — the software being ported
- [Termux](https://termux.dev) — provides the only bionic runtime that survives inside the Android app sandbox
- [xAI grok-build](https://github.com/xai-org/grok-build), [PhoneBuddy SDK](https://github.com/APUS-AI-Lab/PhoneBuddySDK) — another approach to mobile agent engines (pure Rust, zero child processes). Their tradeoffs are the opposite of this project's and make a useful comparison
