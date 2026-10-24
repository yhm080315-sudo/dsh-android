English | [中文](../PITFALLS.md)
# Platform Pitfalls

Running a Linux desktop program inside an Android app sandbox brings a set of problems that **are invisible at compile time, fail only at runtime, and produce deeply misleading error messages**.

This document records each issue as “symptom → root cause → verification → fix.” Every one was reproduced on the same physical device.

---

## 1. glibc and musl binaries are killed outright by seccomp

**Symptom**

```
$ ./node --version
Killed
$ echo $?
159            # 128 + 31 = SIGSYS
```

**Root cause**

The Android app's seccomp filter does not allow registration through the `rseq` system call. glibc registers `rseq` during startup (early in `__libc_start_main`),
so **any glibc binary, static or dynamic, is killed before main**.

The musl case is less obvious: paths such as `--version` that do not enter V8 work, but **executing any JS kills the process**—because V8 initialization triggers it.

**How to verify**

Do not guess. The repository's `tools/check-runtime.sh` checks any binary
and probes the relevant system calls individually (`rseq`, `clone3`, `madvise`, `membarrier`, …):

```bash
bash tools/check-runtime.sh path/to/node
#   exit 159 → this runtime's libc registers rseq and is killed by seccomp
#   exit 0   → it can run in this sandbox
```

(Previously, this used a one-off probe binary that was not included in the repository. It has now been replaced with a readable, editable, reproducible script.)

> **The criterion is “does it register rseq,” not the libc brand.**
> The conclusions here apply only to **runtimes that register rseq**—glibc registers it during startup; musl's node registers it during V8 initialization.
> A non-bionic binary **can still work**: in testing, `@vscode/ripgrep-linux-arm64` (non-bionic)
> performed recursive searches in the app sandbox without any problems, because ripgrep does not touch rseq.
> Do not use this rule to reject a binary that actually works—**running it once is more reliable than judging its brand**.

**Conclusion**

| Runtime | Result |
|---|---|
| Static glibc | ❌ 159 |
| Dynamic glibc | ❌ 159 |
| musl | ❌ 159 when executing JS |
| **bionic** | ✅ Works |

**Fix**: use **Termux's bionic builds** for every embedded runtime. This is the foundation of the entire project.

---

## 2. `flock` is not supported on Android

**Symptom**

```
本轮运行失败  flock is not supported on android-arm64
```

The Chinese prefix means “This run failed.”

This comes from `@deepseek-ai/node-addon-system/lib/flock.js`: it recognizes only `linux` and `darwin`.

**Root cause**: the module reads `process.platform`, which is `android` on Android, and rejects it outright.

**Fix**: return immediately at the start of `tryLockExclusive()` when `platform === 'android'` (degrading to immediate success).
This matches DSH's own handling of browser workers—**in a single-process runtime, an in-process mutex is sufficient**.

> ⚠️ If a multi-process use case is introduced later, this fallback will lose cross-process exclusion. The proper fix is to build an Android version of the addon.

---

## 3. `link()` is denied—**do not fall back to copyFile**

**Symptom**

```
EACCES: permission denied, link '/data/.../session.v4.jsonl.zstd.<tmp>.tmp'
     -> '/data/.../session.v4.jsonl.zstd'
```

**Root cause**: some Android app storage volumes deny `link(2)`. DSH uses hard links for “atomic publication” (temporary file → target name).

**Why `copyFile` is not a valid fallback** (the most expensive lesson in this project)

`link` provides **two** guarantees: **exclusivity** (it fails if the target already exists) and **atomicity** (readers never see a partially written file).
`copyFile` provides neither—it first truncates the target, then writes it incrementally.

The actual consequence: a session write was interrupted, leaving a gap in the middle of the log, and **every subsequent attempt to load that session failed**:

```
corrupt session log: invalid committed event at line 991:
released v2 row 990 has seq gap (expected 990, got 986)
```

Even in “recoverable mode,” DSH's session reader throws when the bad row is followed by a `turn/end` event—
so **the entire session becomes unusable and cannot be loaded**.

**Correct fix**: a lock file + `rename`

```js
// Exclusivity: acquire a lock file with O_EXCL (retry briefly under contention)
// Atomicity: rename is atomic; readers cannot see a partially written file
handle = await open(lock, 'wx');
try {
    if (await exists(target)) return false;   // Target exists → this is not ours to create
    await rename(staged, target);
    return true;
} finally {
    await handle.close();
    await rm(lock, { force: true });
}
```

If the semantics require “the original must be retained” (for example, an alias in content-addressed storage), use **copyFile to a temporary name → verify sha256 → rename**.
**Never unlink the original**.

**Affected locations** (inside DSH)

- `dsh-session-persistence-jsonl`: `publishCurrentExclusive` / `materializePosix`
- `dsh-attachment-local`: `publishStagedObject` (consumable → rename) / `publishImmutableAlias` (original → copy+verify+rename)

---

## 4. Directory `fsync` walks all the way to the filesystem root

**Symptom**: attachment uploads fail with an internal `EACCES` pointing to `/data/user/0` or a directory above it.

**Root cause**: `ensureDurableHome()` in `dsh-attachment-local` calls fsync on **every ancestor directory** along the path,
all the way to `/`. But the app **cannot open any directory above its own data directory**.

**Fix**: make directory synchronization **tolerate permission errors** (skip that level). The file contents have already been fsynced during the write;
durability of ancestor directory entries can only be best-effort on Android:

```js
try { /* open + sync + close */ }
catch (error) {
    if (!["EACCES","EPERM","ENOTSUP","ENOENT"].includes(error?.code)) throw error;
}
```

---

## 5. Native modules (`sharp`, etc.) cannot load → every image is “corrupt”

**Symptom**: after an image is uploaded, the agent says it “cannot be decoded as a supported image,” then claims it has no vision capability.

**Root cause**: DSH runs every incoming image through `sharp` for decoding validation and variant generation.
But the payload's `@img/sharp-linux-arm64` is a **glibc Linux native library**—it cannot load on bionic.
Every image is therefore classified as `INVALID_IMAGE`; **the image never enters the request**, and the model receives only text.

> Beware the misleading error: it looks like “the image is broken,” but the actual problem is **the decoder is missing**.

**Fix**: bypass the native library

1. **Parse image headers in pure JS** (dimensions, format, and alpha for PNG / JPEG / GIF / WebP can all be read from the header bytes).
2. **When resizing is needed but unavailable, send the original image directly**—
   DeepSeek's vision API allows up to 32 MiB of base64 per image; phone screenshots are nowhere near needing resizing.

In testing, the parser's output for real files matched the header ground truth (including all three WebP containers: VP8 / VP8L / VP8X).

---

## 6. WebView is completely silent about `<input type="file">`

**Symptom**: tap “Upload image” and **nothing happens**. No dialog, no error, no logs.

**Root cause**: WebView does not handle file selection by default. The host must implement `WebChromeClient.onShowFileChooser`.

**Two common mistakes**

1. Implementing only `onShowFileChooser` and **forgetting to return the result through `onActivityResult`** → the picker opens, the user selects a file, then it hangs forever (the callback was never answered).
2. Passing the picker's `content://` URI straight to the page → WebView may read it as an **empty file**, causing the upload to fail silently.

**Fix**

```java
// onShowFileChooser: save the callback + launch the system picker
pendingFileCallback = callback;
startActivityForResult(params.createIntent(), REQUEST_FILE_CHOOSER);

// onActivityResult: copy the file into the app cache and return a file:// URI
// Cancellation must also return null, or the next tap will hang forever
pendingFileCallback.onReceiveValue(chosen);   // chosen may be null
```

It is also a good idea to wire up `onConsoleMessage`—**browser-side errors are completely invisible by default**,
and DSH happens to log its client-side failure messages only to the console.

---

## 7. `dx` silently drops entire classes (a successful compile ≠ code included in the package)

**Symptom**: Java changes compile successfully and the `.class` files in `classes/` have changed, but those changes are missing from the APK's `classes.dex`.

**Root cause**: when `dalvik-exchange` (dx) encounters certain newer framework references,
it **drops the entire class containing the reference**, with **no error, no warning, and exit code 0**.

An actual case: using `android.provider.OpenableColumns.DISPLAY_NAME` caused
the entire `MainActivity` to disappear from dex; replacing it with the string literal `"_display_name"` fixed it.

**Fix**

- Avoid newer framework constants where possible; use literals instead.
- **Verify the dex contents after building**, for example by checking each key string with `grep`:

```bash
python3 -c "
import zipfile
d = zipfile.ZipFile('DSH-Android.apk').read('classes.dex')
for s in [b'onShowFileChooser', b'_display_name', b'parked the old runtime tree']:
    print(('OK  ' if s in d else 'MISS'), s.decode())
"
```

> Lesson: **“it compiled” and “the code is in the package” are two different things**. The step between them can fail silently.

---

## 8. An empty client plugin graph → a page of only 3709 bytes

**Symptom**: the UI opens, but reports:

```
Failed to load plugins
client-modules: HTML did not preload @deepseek-ai/dsh-client-modules/client.js
```

The page size is always 3709 bytes (normally it should be 34–36 KB).

**Root cause**: the client plugin scanner resolves `node_modules` from the **profile directory**.
If the profile has no `node_modules` (or it points to a nonexistent location), the scan is empty,
the entries in `__DSH_BOOT__` are `[]`, and the page has no bootstrap script.

**Fix**: before startup, ensure that `$DSH_HOME/profiles/web/node_modules` exists and can resolve the bundled plugin packages.

**Verification**: check the byte count in the log's `page check`—**34846 bytes = normal; 3709 bytes = empty plugin graph**.

---

## 9. Bare package names resolve from “the loader's own location,” not the profile

**Symptom**: a plugin is installed in the profile and can be resolved, but startup reports:

```
dsh-whale-widget (dsh-whale-widget): failed to import
```

**Root cause**: when **Node's internal loader is unavailable** (as on Android), `cordis-plugin-loader` takes this branch:

```js
else return await import(name);   // Bare package name → resolved relative to this file's location
```

As a result, `import("some-plugin")` searches from **the runtime installation directory**.
It “happens” to work when the plugin is installed in the runtime; once the plugin is installed in the profile (a safer location), it cannot be found.

**Fix**: resolve relative to the loader's `baseUrl` (the profile directory):

```js
const resolved = pathToFileURL(createRequire(baseUrl).resolve(name)).href;
```

---

## 10. Static musl busybox gets killed

**Symptom**: `cat` / `ls` / `tar` / `head` all report `Unknown signal 31`.

**Root cause**: the embedded busybox is a **static musl** build—the same problem as item 1: `rseq` → SIGSYS.

**Fix**: replace it with bionic coreutils. Be aware that the Termux busybox launcher **hardcodes its library path to**
`/data/data/com.termux/...`, so it cannot load in a custom runtime. Use **standalone coreutils binaries** directly
(`cat`, `ls`, `cp`, `mv`, `rm`, `head`, `tail`, `wc`, `sort`, `uniq`, `cut`, `tr`, …);
they make no path assumptions. `rg` covers `grep` / `find`.

---

## 11. Other inevitable pitfalls (quick reference)

| Symptom | Root cause | Fix |
|---|---|---|
| `node` cannot find `node-addon-require-builtin` | The addon has no Android version | Make `dsh-app-boot` degrade gracefully when loading fails (try/catch) |
| `credentials-local: .credentials.yaml is readable beyond its owner (mode 755)` | Android gives files group-read permission | Run `chmod 600` before startup, and prepopulate `{}` if the file is missing |
| `Cannot find package 'semver'`, etc. | The profile's module view is missing | See item 8 |
| OpenSSL reports `fopen(/data/data/com.termux/.../openssl.cnf): Permission denied` | Termux's node has this path compiled in | Set `OPENSSL_CONF` / `SSL_CERT_FILE` / `SSL_CERT_DIR` to point to bundled copies |
| User plugins disappear after an upgrade | Plugins were installed in the runtime directory, which is replaced in full | Use a **real directory** for the profile's `node_modules` (in user data), with symlinks to runtime packages; also support sideloading from `/sdcard/.../plugins/*.tgz` |
| API keys disappear after an upgrade | Uninstalling clears app data | Mirror critical state to shared storage and restore it on first launch |
| Extraction gets stuck on the same file (`EACCES`) | The old tree contains files the app cannot delete (owned by root) | **Rename the old tree out of the way** before extraction; see “Key design decisions” in the [README](../../README.en.md) |
| Extraction reads garbage and repeatedly fails | The resume checkpoint belongs to a different archive | Disable resuming and extract into an empty tree every time |
| `HOME` and state files disagree | State handling uses `homeDir()`, but startup passes an old path | Consolidate paths into a single accessor function; do not spell out the path in a second location |

---

## 12. Binding to 127.0.0.1 does not provide isolation (loopback is shared)

**Symptom**: a local HTTP endpoint bound only to `127.0.0.1` can be accessed by **any** app on the same device.
Worse, web pages can reach it too: placing
`<img src="http://127.0.0.1:3091/device/tap?x=500&y=1200">`
on any page can send a blind request (`<img>` does not trigger a CORS preflight, so there is no dialog or error).

**Root cause**: on Android, apps **share the same loopback interface**—`127.0.0.1` is not a separate sandbox
for each app. “Bind only to localhost” is therefore just a choice of network location, **not a security boundary**.

If these endpoints also execute through previously authorized `su` (as this project's device control does),
the attacker gains **root-level input injection and screenshots**, with no prompts at any point.

**Fixes** (ordered by cost-effectiveness)

1. **Require a token**. Generate a random value on first run, store it with mode 0600, and require callers to include it (in a query parameter or request header).
   The crucial point is to **deny service if the token cannot be obtained, rather than falling back to open access**—fail closed.
2. **Reject browser requests**. Reject any request carrying `Origin` or a cross-site `Sec-Fetch-Site` value.
   One line of code can block an entire class of CSRF.
3. **Use POST for endpoints with side effects**. Stop using `GET` for actions such as tap / text / start.

Use a **constant-time comparison** for tokens, not `equals`.

> An external reviewer found this issue—**while running inside the app's own process**.
> When the code was closed, “bound to 127.0.0.1, with allowlisted parameters” was merely a claim;
> after open-sourcing, it became an assertion that anyone could disprove or confirm with a single `curl` request.

## Appendix: Pitfalls this project created for itself

The entries above cover problems imposed by the platform. But the hardest ones to track down are often **self-inflicted**—they look like platform issues,
so the investigation keeps heading in the wrong direction. This section records them candidly.

### A. Falling back to copyFile corrupted a user's session

Keeping `link()`'s semantics unchanged and replacing only its failure path with `copyFile` looked like a “minimal change.”
In fact, it replaced **atomicity**—`copyFile` first truncates the target, then writes it incrementally.

The result: an interrupted write left a gap in the middle of the log, and the session could never be opened again
(`seq gap (expected 990, got 986)`).

**Lesson**: when replacing a system call, first ask “what guarantees does it provide to its caller,”
not “what action does it perform.” The value of `link` is exclusivity **and** atomicity; losing either changes the semantics.

### B. Resume checkpoints did not record “which archive they belong to”

The checkpoint stored an entry number and offset, but no identifier for the archive. Resuming after switching archives
meant reading a new byte stream at an old offset—the result was garbage, and **extraction repeatedly got stuck on the same file**.

Worse, each attempt wrote a new checkpoint, perpetually “legitimizing” the bad state.

**Lesson**: caches and checkpoints must include the identity of the input they depend on. Saving 6 seconds cost a string of inexplicable errors.

### C. The home directory path was written out separately in two places

State handling used `homeDir()` (the new, safe location), while harness startup still received the old `runtime/home`.
The two went their separate ways, so “I clearly ran chmod 600” and “the plugin refuses to start because of mode 755” were **both true**.

**Lesson**: a path must have a single source. Finding a second place that assembles it means finding the seed of a bug.

### D. Deleting undeletable files in place → silent failure

The old tree contained root-owned files that the app could neither modify nor delete. `deleteQuietly` failed silently,
and extraction got stuck on the same file with `EACCES`—even though the code at the time “looked” as if it had cleared the old tree.

**Lesson**: **renaming the old directory out of the way to start with an empty one** is more reliable than deletion (it requires only write permission on the parent).
Also, every `deleteQuietly`-style silent fallback must answer “what happens if this fails?”

### E. Java string replacements had no assertions, so changes silently vanished

When `String.replace(anchor, patch)` cannot find its anchor, it **returns the original string** without an error.
As a result, the `onActivityResult` change never made it into the code, even though compilation was “successful”—the form's picker opened,
a file was selected, and it hung forever, without a word in the logs.

**Lesson**: every code change based on “find and replace” must assert that it matched.
(The repository's `patches/apply-payload-patches.sh` was written with this lesson in mind: anchor mismatches are explicitly reported.)

### F. The source in the open-source repository was not the source of the released APK

Two lineages got mixed up: the repository version had device bridge authentication but no cleanup or plugin sideloading;
the packaged version had cleanup and sideloading but no authentication. **Anyone building from the repository would get a different app.**

**Lesson**: in an open-source project, “source code” means **the files that can build the released binary**.
Before release, there must be a verification step that “builds completely from repository source and compares against the original package.”
Otherwise, open-sourcing is merely decorative—or worse, because people will file issues based on the wrong code.

### G. The device bridge had no authentication (found by an external reviewer)

See item 12. I missed this pitfall myself because the code comment “bound only to 127.0.0.1” felt reassuring.

**Lesson**: **security claims in comments do not count**. To say an interface is secure, you must be able to explain what it uses to stop whom—
if you cannot, it is undefended.

## 13. Hard links denied again, this time in the file tools (Write cannot create new files)

**Symptom**: every attempt by the agent to create a new file with the Write tool fails, on both `/sdcard` and the app's private directory:

```
EACCES: permission denied, link
  '/sdcard/x/.probe.txt.22272.<uuid>.tmpdir/probe.txt.tmp' -> '/sdcard/x/probe.txt'
```

Edit and Write operations on **existing files** work—so it looks like a “permissions problem,”
but in fact only the “create new” path is broken. Meanwhile, the system prompt still tells the model to “prefer writing files over long replies.”

**Root cause**: `writeFileAtomic()` in `dsh-fs-local` has two publication paths:

| Branch | Publication method | Android |
|---|---|---|
| `createIfAbsent` (create new; do not overwrite) | `link(2)` | ❌ Denied in the app domain |
| Overwrite an existing file | `rename(2)` | ✅ |

Item 3 records the same denied system call, but the fix at the time covered only the session and attachment modules;
the file tool's path was never patched. This is not a permission configuration issue: running the same operation on the same path with `su` succeeds
(`u:r:ksu` versus `u:r:untrusted_app_27`).

**Fix**: a lock file (exclusivity) + `rename` (atomicity), with the same cause as item 3 (patch P11):
acquire the lock with `open(lock, "wx")`, check that the target does not exist, then publish with `rename`.
Keep both guarantees—**do not degrade to `copyFile`**; that is exactly the mistake explained in detail in item 3.

**Lesson**: when a platform denies a system call, that conclusion **applies to every call site on that platform**.
After fixing the location that currently reports an error, search the code for other uses of the same call—
when item 3 was fixed, this file-tool call site was already a ticking time bomb.

---

## 14. A missing platform optionalDependency silently disables an entire set of tools (grep / glob)

> **Update (tested on a physical device)**: after replacing toybox grep with busybox grep, `grep 'a\|b'` behaved **exactly the same**—
> it still returned zero matches with exit code 0. So “switching coreutils” does not fix this silent false negative:
>
> | Command | Result |
> |---|---|
> | `grep 'alpha\|beta'` (toybox) | 0 matches, exit 0 |
> | `busybox grep 'alpha\|beta'` | 0 matches, exit 0 |
> | `grep -E 'alpha\|beta'` | 2 matches |
>
> A real fix would require a `grep` shim (forwarding to `rg`, or making `-E` the default).
> **This project does not ship that shim**: the shim itself depends on busybox's dynamic linking; if linking fails,
> it breaks `grep` completely—worse than the current silent false negative—and it cannot be reliably verified in the container.
> The current response is to tell the agent honestly in `AGENTS.md` to “use `grep -E` or `rg`,”
> preventing the model from being misled without concealing the root cause.

**Symptom**: the Grep and Glob tools fail on every call, with no other clues:

```
grep could not start its search command (ripgrep launch failed)
```

**Root cause**: DSH bundles ripgrep (“no dependency on the system `rg`”) and resolves it through `@vscode/ripgrep`:

```js
const platformPkg = `@vscode/ripgrep-${process.platform}-${arch}`;
```

On Android, this evaluates to **`@vscode/ripgrep-android-arm64`, a platform package not published on npm**,
so `import("@vscode/ripgrep")` immediately throws:

```
Could not find @vscode/ripgrep-android-arm64.
Ensure optionalDependencies are installed for this platform (android-arm64).
```

`resolveRgPath()` then rejects, making both grep / glob tools completely unusable on the device.
Yet the payload actually contains two working rg binaries: `@vscode/ripgrep-linux-arm64/bin/rg` (tested on-device: `ripgrep 15.0.0`)
and `usr/bin/rg` (Termux bionic, 15.2.0)—**neither was ever used, because resolution threw first.**

**Fix** (patch P12): install a working arm64 rg as the missing platform package:

```
node_modules/@vscode/ripgrep-android-arm64/bin/rg          ← copied from usr/bin/rg
node_modules/@vscode/ripgrep-android-arm64/package.json    ← do not add an exports field
```

**Two pitfalls**

1. It must be created under the **real** `node_modules/@vscode/`. Node uses the package's
   realpath when resolving subpaths; adding it to the profile's symlink view does not work—another instance of the “resolution base” issue in item 9.
2. `npm install` on the build machine installs platform packages for **the build machine**. If the build machine is x86_64, the payload contains only
   `ripgrep-linux-x64`, whose binary cannot run on a phone. P12 therefore checks the ELF header
   (`e_machine == 183`, AArch64) and falls back to the payload's bundled bionic `rg` if the binary does not qualify.

**Lesson**: the design “does not depend on system binaries” carries an implicit assumption: the bundled binary **exists for the target platform**.
When cross-packaging, `optionalDependencies` are selected for the build machine, but execution happens on a phone—
this kind of error does not report “unsupported platform”; it only reports “failed to start.”

---

## Troubleshooting methodology

Most entries in this list **took several rounds to track down**. Three lessons recur:

1. **The log line that never appears often tells you more than the ones that do.**
   (Example: the plugin page says “not running,” but the logs contain no import error → the request or loading process never reached that step.)

2. **First determine which layer the request reached, then instrument it.** Adding logs only on the server when the failure happens before the gateway wastes an entire round.

3. **If you can reproduce it locally, do not test it on the physical device.**
   Run the same runtime + the same profile layout in the container;
   this often reproduces the issue and verifies the fix in one attempt (that is how item 9 was diagnosed).
