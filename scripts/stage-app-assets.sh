#!/usr/bin/env bash
# Assemble every byte that ships inside the APK.
#
#   assets/runtime.tar.gz  ->  filesDir/runtime/{usr/{bin,lib},dsh/{bin,lib/node_modules}}
#
# The tar members are already patched: interpreters point at the bundled
# ld-linux-aarch64.so.1 and libraries resolve through one flat usr/lib.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
# The bionic sandbox refuses glibc outright (SIGSYS at startup: glibc registers
# rseq, the app seccomp filter kills it on syscall 31). musl does not, so the
# shipped runtime is musl-based: official linux-arm64-musl Node + Alpine musl
# loader/libs + the musl build of the one native addon the harness needs.
RT="${RUNTIME_DIR:-$HERE/musl-run}"
SHELLTOOLS="${SHELLTOOLS_DIR:-$HERE/shelltools/x}"
DSH_INSTALL="${DSH_INSTALL:-$HERE/dsh-install}"
STAGE="${STAGE_DIR:-$HERE/app/asset-stage}"
LOADER_NAME="ld-musl-aarch64.so.1"

# busybox supplies the shell toolchain as one static musl binary; its applets are
# linked at first launch with an absolute path (busybox refuses a relative argv[0]).
BINS=(
  bash busybox rg
)

echo "== staging payload into $STAGE =="
echo "   device prefix: ${DEVICE_PREFIX:-/data/user/0/com.dsh.android/files/runtime/usr}"
rm -rf "$STAGE"
mkdir -p "$STAGE/runtime/usr/bin" "$STAGE/runtime/usr/lib" "$STAGE/runtime/tmp" \
         "$STAGE/runtime/home" "$STAGE/runtime/dsh"

LIBDIR="$STAGE/runtime/usr/lib"

# ---- 1. collect libraries + node, breadth-first over DT_NEEDED -------------
BINPATHS=("$RT/usr/bin/node")
for b in "${BINS[@]}"; do
  for cand in "$RT/usr/bin/$b" "$SHELLTOOLS/usr/bin/$b" "$SHELLTOOLS/bin/$b"; do
    [ -f "$cand" ] && { BINPATHS+=("$cand"); break; }
  done
done

# musl resolves libraries through LD_LIBRARY_PATH; the loader and libs are staged
# from the runtime tree rather than collected from the host.
mkdir -p "$LIBDIR"
for f in "$RT/usr/lib/"*; do
  [ -e "$f" ] && cp -L "$f" "$LIBDIR/$(basename "$f")"
done
echo "  libs: $(ls "$LIBDIR" | tr '\n' ' ')"
mkdir -p "$STAGE/runtime/usr/bin"
for b in "${BINPATHS[@]}"; do
  cp -L "$b" "$STAGE/runtime/usr/bin/"
done

rm -rf "$STAGE/runtime-root"

# ---- 2. point every ELF executable at the bundled musl loader --------------
LOADER_ABS="${DEVICE_PREFIX:-/data/user/0/com.dsh.android/files/runtime/usr}/lib/$LOADER_NAME"
for f in "$STAGE/runtime/usr/bin/"*; do
  if [ -f "$f" ]; then
    case "$(basename "$f")" in
      busybox) echo "  static, left alone: busybox" ;;
      bash|rg) echo "  bionic, left on /system/bin/linker64: $(basename "$f")" ;;
      *) patchelf --set-interpreter "$LOADER_ABS" "$f" 2>/dev/null \
           && echo "  interp <- $LOADER_NAME  $(basename "$f")" \
           || echo "  !! patch failed: $(basename "$f")" ;;
    esac
  fi
done
chmod 755 "$STAGE/runtime/usr/bin/"* "$LIBDIR/"* 2>/dev/null || true

# ---- 3. the harness -------------------------------------------------------
echo "== copying harness ($(du -sh "$DSH_INSTALL/node_modules" | cut -f1)) =="
cp -aL "$DSH_INSTALL/node_modules" "$STAGE/runtime/dsh/node_modules"

# Drop the LibreOffice WASM bundle (145 MB of soffice.wasm + soffice.data). It is
# only pulled in lazily for Office-to-PDF / office-skill work; without it the
# harness boots and serves its whole UI normally, which is what a phone needs.
# Re-enabling those skills means restoring these two files next to soffice.cjs.
WASM="$STAGE/runtime/dsh/node_modules/@deepseek-ai/libreoffice-kit-wasm/assets"
if [ -d "$WASM" ]; then
  before=$(du -sm "$STAGE/runtime/dsh" | cut -f1)
  rm -f "$WASM/soffice.wasm" "$WASM/soffice.data"
  after=$(du -sm "$STAGE/runtime/dsh" | cut -f1)
  echo "  dropped libreoffice wasm: ${before}M -> ${after}M"
fi
cp -a "$DSH_INSTALL/package.json" "$STAGE/runtime/dsh/" 2>/dev/null || true

# The musl build of node-addon-require-builtin is not published as an npm
# optionalDependency for android; it is staged explicitly because the loader
# resolves the binary by detecting musl from the Node interpreter.
MUSL_ADDON="$STAGE/runtime/dsh/node_modules/node-addon-require-builtin-linux-arm64-musl"
mkdir -p "$MUSL_ADDON/prebuilt"
cp -f /tmp/musladdon/package/prebuilt/linux-arm64-musl-napi-v9.node "$MUSL_ADDON/prebuilt/" 2>/dev/null \
  || echo "  !! musl addon prebuild missing"
cat > "$MUSL_ADDON/index.js" <<'ADDONEOF'
'use strict';
module.exports = require('./prebuilt/linux-arm64-musl-napi-v9.node');
ADDONEOF
cat > "$MUSL_ADDON/package.json" <<'ADDONEOF'
{ "name": "node-addon-require-builtin-linux-arm64-musl", "version": "0.1.9", "main": "index.js" }
ADDONEOF
chmod 755 "$MUSL_ADDON/prebuilt/"*.node 2>/dev/null || true
echo "  musl addon staged: $(ls "$MUSL_ADDON/prebuilt/" 2>/dev/null | head -1)"

echo "== sizes =="
du -sh "$STAGE/runtime" "$STAGE/runtime/usr" "$STAGE/runtime/dsh" 2>/dev/null
echo OK
