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
matching, it explicitly reports an "anchor mismatch" **and prints the upstream text
it found there**, instead of silently making an incorrect change.

## Covered

| Number | Patch | File |
|---|---|---|
| P2 | Make `flock` return success immediately on Android | `node-addon-system/lib/flock.js` |
| P3-helper | Add `publishExclusive` (lock + rename + records the holding pid) | `dsh-session-persistence-jsonl/lib/index.js` |
| P3a/P3b | Route both session publications through `publishExclusive` | same file |
| P16a–d | Rebuild a salvaged log from the complete event stream when it is reopened for writing | same file |
| P20b–e | Session write lease moves to `session.lock.d` (`mkdir` arbitration, records the pid) | same file |
| P4 | Tolerate permission errors when calling fsync on a directory | `dsh-attachment-local/lib/index.js` |
| P9 | Resolve bare package names according to the profile | `cordis-plugin-loader/lib/index.js` |
| P11 | Publish newly created files using "lock + rename" (`link` is denied by the app domain) | `dsh-fs-local/lib/index.js` |
| P12 | Add the missing `@vscode/ripgrep-android-arm64` platform package | `node_modules/@vscode/` (new directory) |
| P13 | Let the ptc worker inherit the runtime variables it needs, such as `LD_LIBRARY_PATH` | `dsh-ptc-runtime-node/lib/index.js` |
| P14/P15/P16a | Make the delivery card's "open" work on Android (`dsh-open`) | `host-open-in-app` / `native-command` |
| P17/P17b | `SIGINT` no longer shuts the harness down; `SIGTERM` leaves a trace | `dsh/lib/profile-boot-*.js` (**the filename carries a hash; the script locates it by prefix**) |
| P19a–d | Retry the boot animation's media/directory requests with backoff | `dsh-boot-animation/lib/client.js` |
| P21 | `node-addon-require-builtin` has no Android prebuild → pure-JS stand-in (same package, same exports) | `node-addon-require-builtin/lib/index.js` |
| P22 | The pure-JS sharp cannot encode WebP → for alpha images (Android screenshots) append a PNG candidate and fall back to the original bytes when encoding fails | `dsh-attachment-local/lib/index.js` |
| P23 | A cross-copy `LlmError` is no longer rewritten as `TRANSPORT` (otherwise AUTH / MISSING_CREDENTIAL all become "network errors" and get retried 5 times) | `dsh-llm-deepseek/lib/index.js` |
| P24 | Read the `refs:` of `$DSH_HOME/.credentials.yaml` directly when the credential service is absent or throws (read-only, never into the environment) | `dsh-llm-deepseek-api-key/lib/index.js` |
| P25 | Add `rename` / `copyFile` / `writeFile` to the `fs/promises` import (P3a's publication and P16d's rebuild need them; upstream's import lacks them → a new session dies with `rename is not defined`) | `dsh-session-persistence-jsonl/lib/index.js` |
| P26 | Old WebViews (engine below Chrome 100) lack `Promise.withResolvers` / `AbortSignal.any` / `throwIfAborted` and friends → inject an inline polyfill after `<title>` in `dsh-web-frontend/dist/index.html` (only the APIs the bundles actually use; a no-op on newer engines) | `@deepseek-ai/dsh-web-frontend/dist/index.html` |

## Not Covered Automatically (Manual Changes Required; See [PITFALLS.md](../docs/en/PITFALLS.md))

> P1 (`node-addon-require-builtin` failing to load on Android) is covered by **P21**:
> rather than changing `dsh-app-boot`, the package's `lib/index.js` is replaced with a
> pure-JS implementation.

| Number | Change | Reason |
|---|---|---|
| P5 | Pure-JS image pipeline (replacing `sharp`) | **Already implemented in the payload** (`sharp` was replaced by `0.35.5-purejs`, the original kept as `sharp.glibc-original`) but it is not in the script |
| P10 | Replace static musl busybox with bionic coreutils | Part of payload assembly; see `stage-bionic-assets.sh` |

## Payload-only pieces that must be carried over when replacing `node_modules`

Upgrades replace the whole upstream `node_modules` tree, but the pieces below are **not** in
`@deepseek-ai/dsh`'s dependency closure — npm cannot produce them, so they must be copied from
the old payload (otherwise plugins/images/voice silently disappear):

| Item | Source |
|---|---|
| `dsh-screen-operate` / `dsh-web-mobile` | First-party/third-party plugins, installed separately. `dsh-boot-animation` has been removed from the payload since 1.0.19-beta16 (so P19a–d now no-op with "target file does not exist", which is expected). `dsh-skin-whale-fantasy` (CC-BY-NC-SA-4.0) / `dsh-pro-mode-splash` have been removed since 1.0.19-beta13 (license compliance + unnecessary); `dsh-web-mobile` (MIT) was dropped by mistake in beta13/beta14 and has been back since beta15 (mobile UI adaptation, not optional) |
| `sharp` (`0.35.5-purejs`) + `sharp.glibc-original` | P5 hand-written implementation + the original kept on file |
| `node-addon-require-builtin-linux-arm64-musl` | Generated during assembly by `stage-app-assets.sh` |
| `@vscode/ripgrep-android-arm64` | Generated by P12 (the script rebuilds it) |

Also: the payload is a **linux/arm64** install tree (running npm with bionic node makes npm
think the host is `os=android`, so every `linux` platform package is skipped). Installation must
therefore pass `--os=linux --cpu=arm64` explicitly, otherwise `@koromix/koffi-linux-arm64`,
`sherpa-onnx-linux-arm64` and `node-addon-require-builtin-linux-arm64-*` are all missing.
