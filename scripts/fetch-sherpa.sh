#!/usr/bin/env bash
# Fetch the sherpa-onnx Android runtime used by SpeechBridge.
#
# The APK embeds:
#   app/libs/sherpa-onnx-classes.jar   Java/Kotlin API of sherpa-onnx
#   app/libs/kotlin-stdlib.jar         the API is Kotlin bytecode; no compiler needed
#   app/jni/lib/arm64-v8a/*.so         JNI + onnxruntime (bionic, arm64 only)
#
# Sources are pinned by sha256 so a network fetch cannot silently change the APK.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$HERE/app"
AAR_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-2.0.1.aar"
AAR_SHA="a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7"
KOTLIN_URL="https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/2.0.21/kotlin-stdlib-2.0.21.jar"
KOTLIN_SHA="f31cc53f105a7e48c093683bbd5437561d1233920513774b470805641bedbc09"
CLASSES_SHA="0ee4991c56c19ef5a8a573b01891193d7298ffe7ebcc68f42b944361d8869d93"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "== download =="
curl -fsSL -o "$TMP/sherpa.aar" "$AAR_URL"
echo "$AAR_SHA  $TMP/sherpa.aar" | sha256sum -c -
curl -fsSL -o "$TMP/kotlin.jar" "$KOTLIN_URL"
echo "$KOTLIN_SHA  $TMP/kotlin.jar" | sha256sum -c -

echo "== extract =="
mkdir -p "$APP/libs" "$APP/jni/lib"
python3 - "$TMP/sherpa.aar" "$APP" <<'PY'
import os
import sys
import zipfile

aar, app = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(aar) as z:
    data = z.read("classes.jar")
    with open(os.path.join(app, "libs", "sherpa-onnx-classes.jar"), "wb") as out:
        out.write(data)
    n = 0
    for info in z.infolist():
        if not info.filename.startswith("jni/arm64-v8a/"):
            continue
        target = os.path.join(app, info.filename)
        os.makedirs(os.path.dirname(target), exist_ok=True)
        with open(target, "wb") as out:
            out.write(z.read(info))
        n += 1
print(f"classes.jar {len(data)} B, arm64-v8a members {n}")
PY
cp -f "$TMP/kotlin.jar" "$APP/libs/kotlin-stdlib.jar"

echo "$CLASSES_SHA  $APP/libs/sherpa-onnx-classes.jar" | sha256sum -c -
ls -la "$APP/libs/sherpa-onnx-classes.jar" "$APP/libs/kotlin-stdlib.jar" "$APP/jni/lib/arm64-v8a"
echo "ok"
