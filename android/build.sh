#!/usr/bin/env bash
set -euo pipefail

QIBLA_PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
QIBLA_JDK_PATH="${QIBLA_JDK_PATH:-${JAVA_HOME:-$HOME/.local/share/jdk-21}}"
QIBLA_SDK_PATH="${QIBLA_SDK_PATH:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
QIBLA_BUILD_TOOLS="$QIBLA_SDK_PATH/build-tools/35.0.0"
QIBLA_ANDROID_JAR="$QIBLA_SDK_PATH/platforms/android-35/android.jar"
QIBLA_WORK_DIR="$QIBLA_PROJECT_DIR/android/.build"
QIBLA_SIGN_DIR="$QIBLA_PROJECT_DIR/android/.signing"
QIBLA_OUTPUT_DIR="$QIBLA_PROJECT_DIR/dist"

if [[ ! -x "$QIBLA_JDK_PATH/bin/javac" || ! -f "$QIBLA_ANDROID_JAR" || ! -x "$QIBLA_BUILD_TOOLS/aapt2" ]]; then
  echo "Missing Android build tools. Set JAVA_HOME and ANDROID_HOME, or QIBLA_JDK_PATH and QIBLA_SDK_PATH." >&2
  exit 1
fi

mkdir -p "$QIBLA_WORK_DIR/assets" "$QIBLA_WORK_DIR/classes" "$QIBLA_WORK_DIR/dex" "$QIBLA_WORK_DIR/resources" "$QIBLA_OUTPUT_DIR" "$QIBLA_SIGN_DIR"
chmod 700 "$QIBLA_SIGN_DIR"
cp "$QIBLA_PROJECT_DIR/index.html" "$QIBLA_WORK_DIR/assets/index.html"

"$QIBLA_JDK_PATH/bin/javac" -source 8 -target 8 -classpath "$QIBLA_ANDROID_JAR" \
  -d "$QIBLA_WORK_DIR/classes" "$QIBLA_PROJECT_DIR/android/src/org/qibla/offline/MainActivity.java"

PATH="$QIBLA_JDK_PATH/bin:$PATH" "$QIBLA_BUILD_TOOLS/d8" --min-api 26 \
  --lib "$QIBLA_ANDROID_JAR" --output "$QIBLA_WORK_DIR/dex" \
  "$QIBLA_WORK_DIR/classes/org/qibla/offline/"*.class

rm -f "$QIBLA_WORK_DIR/resources/"*.flat
"$QIBLA_BUILD_TOOLS/aapt2" compile --dir "$QIBLA_PROJECT_DIR/android/res" -o "$QIBLA_WORK_DIR/resources"
"$QIBLA_BUILD_TOOLS/aapt2" link -o "$QIBLA_WORK_DIR/base.apk" \
  -R "$QIBLA_WORK_DIR/resources/"*.flat \
  --manifest "$QIBLA_PROJECT_DIR/android/AndroidManifest.xml" \
  -I "$QIBLA_ANDROID_JAR" -A "$QIBLA_WORK_DIR/assets"

cp "$QIBLA_WORK_DIR/base.apk" "$QIBLA_WORK_DIR/with-dex.apk"
"$QIBLA_JDK_PATH/bin/jar" uf "$QIBLA_WORK_DIR/with-dex.apk" -C "$QIBLA_WORK_DIR/dex" classes.dex
"$QIBLA_BUILD_TOOLS/zipalign" -f 4 "$QIBLA_WORK_DIR/with-dex.apk" "$QIBLA_WORK_DIR/aligned.apk"

if [[ ! -f "$QIBLA_SIGN_DIR/password" ]]; then
  openssl rand -hex 32 > "$QIBLA_SIGN_DIR/password"
  chmod 600 "$QIBLA_SIGN_DIR/password"
fi
QIBLA_KEY_PASSWORD="$(cat "$QIBLA_SIGN_DIR/password")"
if [[ ! -f "$QIBLA_SIGN_DIR/qibla.keystore" ]]; then
  "$QIBLA_JDK_PATH/bin/keytool" -genkeypair -noprompt -keystore "$QIBLA_SIGN_DIR/qibla.keystore" \
    -storepass "$QIBLA_KEY_PASSWORD" -keypass "$QIBLA_KEY_PASSWORD" \
    -alias qibla -keyalg RSA -keysize 3072 \
    -validity 10000 -dname 'CN=Qibla Offline'
fi
chmod 600 "$QIBLA_SIGN_DIR/qibla.keystore"

PATH="$QIBLA_JDK_PATH/bin:$PATH" "$QIBLA_BUILD_TOOLS/apksigner" sign \
  --ks "$QIBLA_SIGN_DIR/qibla.keystore" --ks-key-alias qibla \
  --ks-pass "file:$QIBLA_SIGN_DIR/password" \
  --out "$QIBLA_OUTPUT_DIR/qibla-offline.apk" "$QIBLA_WORK_DIR/aligned.apk"
PATH="$QIBLA_JDK_PATH/bin:$PATH" "$QIBLA_BUILD_TOOLS/apksigner" verify "$QIBLA_OUTPUT_DIR/qibla-offline.apk"
echo "$QIBLA_OUTPUT_DIR/qibla-offline.apk"
