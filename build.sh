#!/usr/bin/env bash
# 悬浮音高 PitchFloat — 无 Gradle 构建脚本 (aapt2 + javac + d8 + zipalign + apksigner)
# 依赖：JDK 17、Android SDK（platforms;android-35 与 build-tools;35.0.0）、Python 3
# 环境变量：JDK_HOME（JDK17 路径）、ANDROID_SDK（SDK 根目录，默认 ./sdk）
set -euo pipefail
cd "$(dirname "$0")"

SDKROOT="${ANDROID_SDK:-sdk}"
BT="$SDKROOT/build-tools/35.0.0"
PLATFORM="$SDKROOT/platforms/android-35/android.jar"

if [ ! -f "$PLATFORM" ]; then
  echo "ERROR: 缺少 $PLATFORM（安装: sdkmanager \"platforms;android-35\" \"build-tools;35.0.0\"）"
  exit 1
fi

if [ -z "${JDK_HOME:-}" ]; then
  for d in "$PWD"/jdk/jdk-17* "/c/Program Files/Eclipse Adoptium"/jdk-17* \
           "/c/Program Files/Microsoft"/jdk-17*; do
    [ -d "$d" ] && JDK_HOME="$d" && break
  done
fi
if [ -z "${JDK_HOME:-}" ]; then echo "ERROR: 找不到 JDK 17（set JDK_HOME=...）"; exit 1; fi
export JAVA_HOME="$(cygpath -w "$JDK_HOME" 2>/dev/null || echo "$JDK_HOME")"
JC="$JDK_HOME/bin/javac.exe"
KT="$JDK_HOME/bin/keytool.exe"
echo "JDK: $JDK_HOME  SDK: $SDKROOT"

rm -rf build
mkdir -p build/gen build/obj build/dex

echo "[1/7] 生成图标"
python gen_icons.py

echo "[2/7] aapt2 编译/链接资源"
"$BT/aapt2.exe" compile --dir res -o build/res.zip
"$BT/aapt2.exe" link -o build/app.base.apk -I "$PLATFORM" \
  --manifest AndroidManifest.xml --java build/gen \
  --min-sdk-version 29 --target-sdk-version 35 \
  --version-code "${VER_CODE:-1}" --version-name "${VER_NAME:-1.0}" \
  build/res.zip

echo "[3/7] javac 编译"
find src build/gen -name "*.java" > build/sources.txt
"$JC" -encoding UTF-8 -source 1.8 -target 1.8 -nowarn \
  -bootclasspath "$PLATFORM" -classpath "$PLATFORM" \
  -d build/obj @build/sources.txt

echo "[4/7] d8 转 dex"
find build/obj -name "*.class" > build/classes.txt
"$BT/d8.bat" --release --lib "$PLATFORM" --min-api 29 --output build/dex \
  $(tr '\n' ' ' < build/classes.txt)

echo "[5/7] 打包 classes.dex 进 APK"
python - <<'EOF'
import shutil, zipfile
shutil.copy('build/app.base.apk', 'build/app.withdex.apk')
with zipfile.ZipFile('build/app.withdex.apk', 'a') as z:
    z.write('build/dex/classes.dex', 'classes.dex', compress_type=zipfile.ZIP_DEFLATED)
EOF

echo "[6/7] zipalign"
"$BT/zipalign.exe" -f -p 4 build/app.withdex.apk build/app.aligned.apk

echo "[7/7] 签名"
if [ ! -f debug.keystore ]; then
  "$KT" -genkeypair -keystore debug.keystore -alias pitch \
    -storepass android -keypass android -keyalg RSA -keysize 2048 \
    -validity 10950 -dname "CN=PitchFloat,O=PitchFloat,C=CN" > /dev/null 2>&1
fi
"$BT/apksigner.bat" sign --ks debug.keystore --ks-key-alias pitch \
  --ks-pass pass:android --key-pass pass:android \
  --out PitchFloat.apk build/app.aligned.apk
"$BT/apksigner.bat" verify PitchFloat.apk
echo "DONE → $(pwd)/PitchFloat.apk"
