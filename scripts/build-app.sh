#!/usr/bin/env bash
# Build the installable DSH-for-Android APK.
#
#   1. pack the staged runtime into assets/runtime.tar.gz (with a build stamp)
#   2. compile + dex + package + align + sign using JDK and android build-tools
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$HERE/app"
BT="${BUILD_TOOLS:-/usr/lib/android-sdk/build-tools/29.0.3}"
PLATFORM_JAR="${ANDROID_JAR:-/tmp/plat30/android-11/android.jar}"
KEYSTORE="${KEYSTORE:-$HERE/keystore.jks}"
# No default password: this keystore's password is the only thing protecting the
# app's update chain (Android trusts applicationId + signing certificate). A
# default that ships in the repository means everyone's key has the same,
# publicly known password.
KS_PASS="${KS_PASS:-}"
if [ -z "$KS_PASS" ]; then
    if [ -f "$HERE/keystore.pass" ]; then
        KS_PASS="$(cat "$HERE/keystore.pass")"
    else
        echo "KS_PASS is not set." >&2
        echo "  First build:  KS_PASS='<pick a strong password>' bash scripts/build-app.sh" >&2
        echo "  The keystore is created on first run and its password is written to keystore.pass (0600)." >&2
        echo "  Losing that file means you can never publish an update for the same app id." >&2
        exit 1
    fi
fi

KS_ALIAS="${KS_ALIAS:-dshandroid}"
STAGE="${STAGE_DIR:-$APP/asset-stage}"
ASSETS="$APP/assets"
OUT="${OUT:-$HERE/DSH-Android-v${APP_VERSION:-unknown}-dsh${DSH_VERSION:-unknown}.apk}"

if [ ! -d "$STAGE/runtime" ]; then
  echo "no staged payload at $STAGE/runtime — run stage-app-assets.sh first" >&2
  exit 1
fi

STAMP="$(date +%Y%m%d-%H%M%S)-$(cd "$STAGE/runtime" && find . -type f | wc -l)"

echo "== 1/2 pack payload =="
mkdir -p "$ASSETS/probes"
cp -f "$HERE/probe-assets/tsys.bin" "$ASSETS/probes/" 2>/dev/null || true
rm -f "$ASSETS/runtime.tar.gz"
ls -la "$ASSETS/probes" 2>/dev/null | tail -3
# Pack the *contents* of runtime/, not the directory itself: the app extracts
# straight into filesDir/runtime, so a leading "runtime/" member would nest it.
tar -C "$STAGE/runtime" -czf "$ASSETS/runtime.tar.gz" .
ls -la "$ASSETS/runtime.tar.gz"

echo "== 2/2 build apk =="
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/classes" "$WORK/dex" "$WORK/gen"

cat > "$WORK/gen/BuildConfig.java" <<EOF
package com.dsh.android;
final class BuildConfig {
    static final String RUNTIME_STAMP = "$STAMP";
    static final String APP_VERSION = "${APP_VERSION:-unknown}";
    static final String DSH_VERSION = "${DSH_VERSION:-unknown}";
    private BuildConfig() {}
}
EOF
cat > "$WORK/gen/BuildConfig.java.src" <<EOF
stamp: $STAMP
EOF
echo "   stamp: $STAMP"

if [ -d "$APP/res" ]; then
  "$BT/aapt2" compile --dir "$APP/res" -o "$WORK/res.zip" 2>/dev/null
  RES_ARGS=("$WORK/res.zip")
else
  RES_ARGS=()
fi

# Version numbers come from ./VERSION -- one place, used by the manifest, the
# APK name and the docs. versionName carries the embedded DSH version so a bug
# report can identify both layers at a glance.
if [ -f "$HERE/VERSION" ]; then
    # shellcheck disable=SC1091
    . "$HERE/VERSION"
fi
CODE="${APP_VERSION_CODE:-$(grep -o 'android:versionCode="[0-9]*"' "$APP/AndroidManifest.xml" | grep -o '[0-9]*')}"
NAME="${APP_VERSION:?VERSION must define APP_VERSION}+dsh.${DSH_VERSION:?VERSION must define DSH_VERSION}"
echo "   version: $NAME (code $CODE)  [app $APP_VERSION / dsh $DSH_VERSION]"
# aapt2's --version-name does not override a value already in the manifest, so
# rewrite the manifest itself: versionCode stays the single source of truth.
cp "$APP/AndroidManifest.xml" "$WORK/AndroidManifest.xml"
sed -i "s|android:versionName=\"[^\"]*\"|android:versionName=\"$NAME\"|" "$WORK/AndroidManifest.xml"
MANIFEST="$WORK/AndroidManifest.xml"

"$BT/aapt2" link -o "$WORK/base.apk" -I "$PLATFORM_JAR" \
    --manifest "$MANIFEST" \
    -A "$ASSETS" \
    --java "$WORK/r" \
    --version-code "$CODE" --version-name "$NAME" \
    --min-sdk-version 24 --target-sdk-version 28 \
    "${RES_ARGS[@]+"${RES_ARGS[@]}"}"

# Shizuku client library. There is no Gradle here, so the four AARs were unpacked
# once and their classes.jar kept in app/libs; they go into javac's classpath and
# into the dex. See docs/PITFALLS.md #17 for why the dex step is D8, not dx.
LIB_JARS=()
for jar in "$APP"/libs/shizuku-*.jar; do
    [ -f "$jar" ] && LIB_JARS+=("$jar")
done
if [ ${#LIB_JARS[@]} -eq 0 ]; then
    echo "no app/libs/shizuku-*.jar -- unpack dev.rikka.shizuku:{api,provider,aidl,shared}" >&2
    echo "13.1.0 AARs and drop their classes.jar into app/libs/ (see docs/PITFALLS.md #17)" >&2
    exit 1
fi
CLASSPATH_ALL="$PLATFORM_JAR"
for jar in "${LIB_JARS[@]}"; do
    CLASSPATH_ALL="$CLASSPATH_ALL:$jar"
done

# aapt2 writes R.java under $WORK/r (--java above); it must be compiled with the
# app, or every getString(R.string.*) site fails to build.
find "$APP/src" "$WORK/gen" "$WORK/r" -name '*.java' > "$WORK/sources.txt"
javac -source 8 -target 8 -encoding UTF-8 -nowarn \
      -bootclasspath "$PLATFORM_JAR" -classpath "$CLASSPATH_ALL" \
      -d "$WORK/classes" @"$WORK/sources.txt"

# dx cannot translate Shizuku's Java 8 lambdas at minSdk 24 ("invalid opcode ba -
# invokedynamic requires --min-sdk-version >= 26") and raising minSdk would drop
# Android 7. D8 desugars them; it needs --lib and jars, not directories.
( cd "$WORK/classes" && jar cf "$WORK/app-classes.jar" . )
if [ -x "$BT/d8" ]; then
    D8_CMD=("$BT/d8")
elif [ -n "${R8_JAR:-}" ]; then
    D8_CMD=(java -cp "$R8_JAR" com.android.tools.r8.D8)
elif command -v d8 >/dev/null 2>&1; then
    D8_CMD=(d8)
else
    echo "no d8 and no R8_JAR: dx rejects Shizuku's invokedynamic (docs/PITFALLS.md #17)" >&2
    exit 1
fi
"${D8_CMD[@]}" --min-api "24" --lib "$PLATFORM_JAR" --output "$WORK/dex" \
    "$WORK/app-classes.jar" "${LIB_JARS[@]}"

cp "$WORK/base.apk" "$WORK/unsigned.apk"
( cd "$WORK/dex" && zip -q -X "$WORK/unsigned.apk" classes.dex )

if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -keystore "$KEYSTORE" -storepass "$KS_PASS" -keypass "$KS_PASS" \
      -alias "$KS_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
      -dname "CN=DSH Android, OU=dev, O=dsh, C=CN" >/dev/null 2>&1
fi

rm -f "$OUT"
"$BT/zipalign" -p -f 4 "$WORK/unsigned.apk" "$OUT"
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" \
    --key-pass "pass:$KS_PASS" --ks-key-alias "$KS_ALIAS" "$OUT"
"$BT/apksigner" verify "$OUT" >/dev/null && echo "   signature ok"

echo
echo "APK: $OUT"
ls -la "$OUT"
