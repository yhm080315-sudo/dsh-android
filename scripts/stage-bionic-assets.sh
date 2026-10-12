#!/usr/bin/env bash
# Stage the bionic (Android-libc) runtime that the app ships.
#
# Why bionic and not glibc/musl: this device's app sandbox runs bionic binaries
# happily, kills glibc ones outright (SIGSYS while glibc registers rseq), and lets
# a musl Node start but not execute JavaScript (V8 initialisation hits a syscall
# the filter refuses). Termux's Node is built for Android's libc, so it is the one
# runtime that actually executes JS inside the sandbox.
#
# Because bionic binaries need no interpreter rewrite, staging is mostly a copy.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
TERMUX="${TERMUX_PREFIX:-/tmp/deb/rootfs/data/data/com.termux/files/usr}"
STAGE="${STAGE_DIR:-$HERE/app/asset-stage}"
DSH_SRC="${DSH_SRC:-$HERE/bionic-dsh}"

BINS=(node bash rg)
LIBS=(
  # node
  libc++_shared.so libcares.so libz.so.1 libsqlite3.so libsqlite3.so.3.54.0
  libcrypto.so.3 libssl.so.3 libicui18n.so.78 libicuuc.so.78 libicudata.so.78
  # bash
  libreadline.so.8 libncursesw.so.6 libtinfo.so.6 libandroid-support.so libiconv.so
  # ripgrep
  libpcre2-8.so
)

echo "== staging bionic payload into $STAGE =="
# A stale tree can hold files this container may not unlink (a directory whose
# on-disk owner does not map to our uid), and `rm -rf` then fails under `set -e`,
# aborting staging halfway and leaving a tree that still looks buildable. Move
# such a tree aside instead: renaming only needs write access to the parent, and
# the build then starts from an empty directory either way.
if [ -e "$STAGE" ] && ! rm -rf "$STAGE" 2>/dev/null; then
  STALE="$STAGE.stale-$(date +%Y%m%d-%H%M%S)"
  echo "  note: some files could not be removed; moving the old tree to $STALE"
  mv "$STAGE" "$STALE" || { echo "cannot clear $STAGE" >&2; exit 1; }
fi
mkdir -p "$STAGE/runtime/usr/bin" "$STAGE/runtime/usr/lib" \
         "$STAGE/runtime/dsh" "$STAGE/runtime/tmp" "$STAGE/runtime/home"

for b in "${BINS[@]}"; do
  cp -L "$TERMUX/bin/$b" "$STAGE/runtime/usr/bin/"
  echo "  bin  $b"
done
# busybox supplies ls/grep/sed/find/... as one static binary; its applets are
# linked at first launch with an absolute path.
if [ -f "$HERE/probe-assets/busybox.static" ]; then
  cp -L "$HERE/probe-assets/busybox.static" "$STAGE/runtime/usr/bin/busybox"
  echo "  bin  busybox (static)"
fi

for l in "${LIBS[@]}"; do
  if [ -e "$TERMUX/lib/$l" ]; then
    cp -L "$TERMUX/lib/$l" "$STAGE/runtime/usr/lib/$l"
  else
    echo "  !! missing lib: $l"
  fi
done
chmod 755 "$STAGE/runtime/usr/bin/"* "$STAGE/runtime/usr/lib/"* 2>/dev/null || true

# Termux's Node has these paths compiled in as /data/data/com.termux/...; the app
# must supply its own copies and point OPENSSL_CONF / SSL_CERT_FILE at them, or
# OpenSSL aborts with EACCES on the missing absolute path.
mkdir -p "$STAGE/runtime/usr/etc/tls"
for f in cert.pem openssl.cnf; do
  if [ -f "$TERMUX/etc/tls/$f" ]; then
    cp -L "$TERMUX/etc/tls/$f" "$STAGE/runtime/usr/etc/tls/$f"
    echo "  etc  tls/$f"
  fi
done
chmod 644 "$STAGE/runtime/usr/etc/tls/"* 2>/dev/null || true

echo "== harness =="
cp -aL "$DSH_SRC/node_modules" "$STAGE/runtime/dsh/node_modules"

# The LibreOffice WASM bundle (145 MB) is only pulled in lazily for Office-to-PDF
# work; dropping it keeps the APK ~30 MB smaller and changes nothing else.
WASM="$STAGE/runtime/dsh/node_modules/@deepseek-ai/libreoffice-kit-wasm/assets"
if [ -d "$WASM" ]; then
  rm -f "$WASM/soffice.wasm" "$WASM/soffice.data"
  echo "  dropped libreoffice wasm"
fi

echo "== sizes =="
du -sh "$STAGE/runtime" "$STAGE/runtime/usr" "$STAGE/runtime/dsh" 2>/dev/null
ls "$STAGE/runtime/usr/bin" "$STAGE/runtime/usr/lib" | head -30
echo OK

# Prove the payload is complete before anyone packs it: a partial tree is
# otherwise indistinguishable from a good one to the packer.
for sentinel in \
    "$STAGE/runtime/dsh/node_modules/@deepseek-ai/dsh/lib/bin.js" \
    "$STAGE/runtime/dsh/node_modules/@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js" \
    "$STAGE/runtime/usr/bin/node"; do
  if [ ! -f "$sentinel" ]; then
    echo "staging incomplete: $sentinel is missing" >&2
    exit 1
  fi
done
echo "  payload complete: $(find "$STAGE/runtime" -type f | wc -l) files"

# dshdev: 设备桥客户端。/usr/bin/env 在 Android 上不存在，所以 node 脚本不能靠
# shebang 直接执行 —— 装成 usr/bin/dshdev.js 再加一个 sh 启动器。
if [ -f "$HERE/tools/dshdev.js" ]; then
  cp -f "$HERE/tools/dshdev.js" "$STAGE/runtime/usr/bin/dshdev.js"
  cp -f "$HERE/tools/dshdev-launcher.sh" "$STAGE/runtime/usr/bin/dshdev"
  chmod 755 "$STAGE/runtime/usr/bin/dshdev" "$STAGE/runtime/usr/bin/dshdev.js"
  echo "  bin  dshdev (device bridge client)"
fi
