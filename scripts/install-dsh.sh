#!/usr/bin/env bash
# Install a clean, self-contained harness into runtime/dsh using the bundled
# glibc Node/npm, then freeze it as the APK payload.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
RT="${RUNTIME_DIR:-$HERE/runtime-ub}"
NPM="$RT/npm/bin/npm-cli.js"
NODE="$RT/usr/bin/node"
DSH_VERSION="${DSH_VERSION:-0.2.0-rc.2}"
TARGET="${TARGET_DIR:-$HERE/dsh-install}"

export LD_LIBRARY_PATH="$RT/usr/lib"
export HOME="${HOME_OVERRIDE:-/tmp/dsh-install-home}"
mkdir -p "$HOME"

# npm ships as a shell script that execs node by name, so give it our node first
NPMDIR="$RT/npm"
mkdir -p "$NPMDIR/bin"
[ -x "$NODE" ] || { echo "node missing at $NODE" >&2; exit 1; }
if [ ! -f "$NPM" ]; then
  echo "== fetching npm tarball =="
  tmp="$(mktemp -d)"
  curl -s --max-time 300 -o "$tmp/npm.tgz" \
      "https://registry.npmjs.org/npm/-/npm-11.20.0.tgz"
  tar -xzf "$tmp/npm.tgz" -C "$tmp"
  cp -a "$tmp/package/." "$NPMDIR/"
  rm -rf "$tmp"
fi
[ -f "$NPM" ] || { echo "npm cli missing at $NPM" >&2; exit 1; }

echo "== node/npm =="
"$NODE" -v
"$NODE" "$NPM" -v

echo "== installing @deepseek-ai/dsh@$DSH_VERSION =="
rm -rf "$TARGET"
mkdir -p "$TARGET"
cd "$TARGET"
cat > package.json <<EOF
{
  "name": "dsh-mobile-bundle",
  "private": true,
  "version": "1.0.0",
  "dependencies": { "@deepseek-ai/dsh": "$DSH_VERSION" }
}
EOF

"$NODE" "$NPM" install --omit=dev --no-audit --no-fund 2>&1 | tail -15

echo "== installed =="
du -sh "$TARGET/node_modules" 2>/dev/null || true
ls "$TARGET/node_modules/@deepseek-ai/dsh/lib/bin.js" && echo "entry present"
