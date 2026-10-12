#!/usr/bin/env bash
# Assemble the Android/bionic runtime prefix that ships inside the APK.
#
# Output layout (mirrors a Termux $PREFIX so upstream binaries resolve):
#   $OUT/bin        node, bash, rg, ...
#   $OUT/lib        shared objects (DT_NEEDED names, unpacked symlinks)
#   $OUT/etc        terminfo, resolv.conf
#   $OUT/lib/node_modules/dsh   the harness itself
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
DEBS="${DEBS_DIR:-/tmp/deb/debs}"
SRC_PREFIX="${PREFIX_DIR:-/tmp/deb/rootfs/data/data/com.termux/files/usr}"
OUT="${RUNTIME_DIR:-$HERE/runtime}"
DSH_SRC="${DSH_SRC:-/usr/local/lib/node_modules/@deepseek-ai/dsh}"

echo "== assembling runtime into $OUT =="
rm -rf "$OUT"
mkdir -p "$OUT/bin" "$OUT/lib" "$OUT/etc" "$OUT/tmp" "$OUT/lib/node_modules"

copy_lib() {  # copy_lib <soname> : resolve the real file behind symlinks/versions
  local name
  local real
  name="$1"
  real="$SRC_PREFIX/lib/$name"
  # exact match on the requested soname, else the versioned file it points at
  if [ ! -e "$real" ]; then
    real="$(ls -1 "$SRC_PREFIX/lib/${name}."* 2>/dev/null | head -1 || true)"
  fi
  if [ -n "${real:-}" ] && [ -e "$real" ]; then
    cp -L "$real" "$OUT/lib/$name"
    chmod 755 "$OUT/lib/$name"
  else
    echo "  !! missing lib: $name"
  fi
}

copy_bin() {
  local name
  local src
  name="$1"
  src="$SRC_PREFIX/bin/$name"
  if [ -e "$src" ]; then
    cp -L "$src" "$OUT/bin/$name"
    chmod 755 "$OUT/bin/$name"
  else
    echo "  !! missing bin: $name"
  fi
}

# --- every DT_NEEDED soname the packed binaries ask for, resolved to real files ---
LIBS=(
  libc++_shared.so libcares.so libcrypto.so.3 libssl.so.3
  libicui18n.so.78 libicuuc.so.78 libicudata.so.78 libsqlite3.so.3.54.0
  libz.so.1.3.2 libz.so
  libandroid-support.so libiconv.so libncursesw.so.6 libreadline.so.8
  libpcre2-8.so libandroid-selinux.so libgmp.so libbz2.so.1.0 liblz4.so
  libncursesw.so.6 libreadline.so.8 libsqlite3.so libz.so.1
)
for l in "${LIBS[@]}"; do copy_lib "$l"; done

# DT_NEEDED asks for bare sonames such as libz.so.1 and libicuuc.so.78; make sure
# each requested name exists as a real file next to its versioned sibling.
for alias in "libsqlite3.so:libsqlite3.so.3.54.0" "libz.so:libz.so.1.3.2" \
             "libz.so.1:libz.so.1.3.2" "libicui18n.so:libicui18n.so.78" \
             "libicuuc.so:libicuuc.so.78" "libicudata.so:libicudata.so.78" \
             "libncursesw.so.6:libncursesw.so.6.5" \
             "libreadline.so.8:libreadline.so.8.3" "libbz2.so.1.0:libbz2.so.1.0"; do
  want="${alias%%:*}"; have="${alias##*:}"
  [ -f "$OUT/lib/$have" ] && [ ! -e "$OUT/lib/$want" ] && cp "$OUT/lib/$have" "$OUT/lib/$want"
done

BINS=(node bash rg sh)
for b in "${BINS[@]}"; do
  if [ "$b" = "sh" ]; then
    ln -sf bash "$OUT/bin/sh"
  else
    copy_bin "$b"
  fi
done

# --- terminfo / resolver config ---
if [ -d "$SRC_PREFIX/share/terminfo" ]; then
  mkdir -p "$OUT/share"
  cp -r "$SRC_PREFIX/share/terminfo" "$OUT/share/terminfo"
fi
[ -f "$SRC_PREFIX/etc/resolv.conf" ] && cp "$SRC_PREFIX/etc/resolv.conf" "$OUT/etc/resolv.conf" || true

# --- the harness itself ---
echo "== copying dsh ($(du -sh "$DSH_SRC" | cut -f1)) =="
cp -a "/usr/local/lib/node_modules/@deepseek-ai" "$OUT/lib/node_modules/@deepseek-ai"

echo "== sizes =="
du -sh "$OUT" "$OUT/bin" "$OUT/lib" "$OUT/lib/node_modules" 2>/dev/null
echo "OK"
