#!/usr/bin/env bash
# Tambal APK Studio Yanz dengan fitur ekstensi Dola, lalu zipalign + tanda tangan ulang.
#
# Pakai:
#   SY_KEYSTORE=/path/studio-yanz-release.jks SY_KEYSTORE_PASSWORD=... \
#   TOOLS=/opt/tools ./build.sh input.apk output.apk
#
# TOOLS berisi: apktool.jar, r8.jar, bt/<versi>/ (build-tools: zipalign, apksigner, core-lambda-stubs.jar),
# pf/android-34/android.jar, d2j/<versi>/ (dex2jar).
set -euo pipefail

IN=$(realpath "$1")
OUT=$(realpath -m "$2")
HERE=$(cd "$(dirname "$0")" && pwd)
TOOLS=${TOOLS:-/opt/tools}
BT=$(ls -d "$TOOLS"/bt/*/ | head -1)
ANDROID_JAR="$TOOLS/pf/android-34/android.jar"
D2J=$(ls -d "$TOOLS"/d2j/*/ | head -1)
: "${SY_KEYSTORE:?set SY_KEYSTORE}"
: "${SY_KEYSTORE_PASSWORD:?set SY_KEYSTORE_PASSWORD}"
ALIAS=${SY_KEY_ALIAS:-studioyanz}

W=$(mktemp -d)
echo "work: $W"

echo "== 1. dex asli -> jar (classpath kompilasi)"
mkdir -p "$W/orig"
unzip -q -o "$IN" 'classes*.dex' -d "$W/orig"
for d in "$W"/orig/classes*.dex; do sh "$D2J/d2j-dex2jar.sh" -f -o "${d%.dex}.jar" "$d" >/dev/null 2>&1; done
CP=$(ls "$W"/orig/classes*.jar | tr '\n' ':')

echo "== 2. kompilasi com.studioyanz.app.ext -> classes3.dex"
mkdir -p "$W/cls" "$W/d8"
javac -nowarn -source 8 -target 8 -bootclasspath "$ANDROID_JAR:${BT}core-lambda-stubs.jar" -cp "$CP" \
  -d "$W/cls" $(find "$HERE/src" -name '*.java') 2>&1 | grep -v -E 'JAVA_TOOL|bootstrap|^warning|^[0-9]+ warning|^Note' || true
java -cp "$TOOLS/r8.jar" com.android.tools.r8.D8 --release --min-api 24 --lib "$ANDROID_JAR" \
  $(for j in "$W"/orig/classes*.jar; do echo --classpath "$j"; done) \
  --output "$W/d8" $(find "$W/cls" -name '*.class')

echo "== 3. kait smali (MainActivity + DolaWebActivity) -> classes.dex baru"
java -jar "$TOOLS/apktool.jar" d -r -f -o "$W/sm" "$IN" >/dev/null 2>&1
python3 -I "$HERE/smali-patches/patch_smali.py" "$W/sm/smali/com/studioyanz/app"
java -jar "$TOOLS/apktool.jar" b -f "$W/sm" -o "$W/sm.apk" >/dev/null 2>&1

echo "== 4. rakit APK"
python3 -I "$HERE/repack.py" "$IN" "$W/unaligned.apk" \
  classes.dex="$W/sm/build/apk/classes.dex" \
  classes3.dex="$W/d8/classes.dex" \
  assets/sy_ext_runtime.js="$HERE/assets/sy_ext_runtime.js" \
  assets/public/js/dola.js="$HERE/web/js/dola.js" \
  assets/public/css/app.css="$HERE/web/css/app.css"

echo "== 5. zipalign + tanda tangan"
"${BT}zipalign" -p -f 4 "$W/unaligned.apk" "$W/aligned.apk"
"${BT}apksigner" sign --ks "$SY_KEYSTORE" --ks-key-alias "$ALIAS" \
  --ks-pass env:SY_KEYSTORE_PASSWORD --key-pass env:SY_KEYSTORE_PASSWORD \
  --out "$OUT" "$W/aligned.apk"
"${BT}apksigner" verify -v "$OUT" | head -5
echo "OK -> $OUT"
