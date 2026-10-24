English | [中文](README.md)

# Payload Patches

`apply-payload-patches.sh` modifies the DSH payload so it can run inside an Android
app sandbox.

```bash
bash patches/apply-payload-patches.sh            # Defaults to ./bionic-dsh/node_modules
bash patches/apply-payload-patches.sh <directory> # Or specify any runtime directory
```

The script **can be run repeatedly**: it matches anchor strings and skips patches
that have already been applied. If upstream code changes cause an anchor to stop
matching, it explicitly reports an "anchor mismatch" instead of silently making
an incorrect change.

## Covered

| Number | Patch | File |
|---|---|---|
| P2 | Make `flock` return success immediately on Android | `node-addon-system/lib/flock.js` |
| P3 | Use "lock + rename" for atomic session publication | `dsh-session-persistence-jsonl/lib/index.js` |
| P4 | Tolerate permission errors when calling fsync on a directory | `dsh-attachment-local/lib/index.js` |
| P9 | Resolve bare package names according to the profile | `cordis-plugin-loader/lib/index.js` |
| P11 | Publish newly created files using "lock + rename" (`link` is denied by the app domain) | `dsh-fs-local/lib/index.js` |
| P12 | Add the missing `@vscode/ripgrep-android-arm64` platform package | `node_modules/@vscode/` (new directory) |

## Not Covered Automatically (Manual Changes Required; See [PITFALLS.md](../docs/en/PITFALLS.md))

| Number | Change | Reason |
|---|---|---|
| P1 | Fall back when `node-addon-require-builtin` fails to load | Involves exception handling during `dsh-app-boot` startup and requires broader changes to the surrounding code. |
| P5 | Parse image headers in pure JS (replacing `sharp`) | The implementation is lengthy and needs adjustments for each upstream version. |
| P10 | Replace static musl busybox with bionic coreutils | Part of payload assembly; see `stage-bionic-assets.sh`. |

These three items are handled in `stage-bionic-assets.sh` / during payload
assembly, or you can implement them yourself by following
[PITFALLS](../docs/en/PITFALLS.md).
