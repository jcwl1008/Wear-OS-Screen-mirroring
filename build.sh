#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK path}"
: "${JAVA_HOME:?Set JAVA_HOME to the JDK path}"
: "${WEAR_MIRROR_KEYSTORE:?Set WEAR_MIRROR_KEYSTORE to the signing keystore path}"
: "${WEAR_MIRROR_STORE_PASSWORD:?Set the signing keystore password}"
: "${ZXING_CORE_JAR:?Set ZXING_CORE_JAR to the ZXing core 3.5.3 JAR path}"
export WEAR_MIRROR_STORE_PASSWORD
build_tools="$ANDROID_HOME/build-tools/35.0.0"
android_jar="$ANDROID_HOME/platforms/android-35/android.jar"
java="$JAVA_HOME/bin"
version="$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' AndroidManifest.xml)"
artifact="../deliverables/WearMirror_v$version"
mkdir -p build/classes build/dex ../deliverables
"$java/javac" --release 8 -classpath "$android_jar:$ZXING_CORE_JAR" -d build/classes src/hk/jimmy/wearmirror/*.java
"$java/jar" cf build/classes.jar -C build/classes .
"$build_tools/d8" --lib "$android_jar" --min-api 30 --output build/dex build/classes.jar "$ZXING_CORE_JAR"
"$build_tools/aapt2" compile --dir res -o build/resources.zip
"$build_tools/aapt2" link -I "$android_jar" --manifest AndroidManifest.xml -A assets -o build/raw.apk build/resources.zip
(cd build/dex && zip -q ../raw.apk classes.dex)
"$build_tools/zipalign" -f 4 build/raw.apk build/aligned.apk
"$build_tools/apksigner" sign --ks "$WEAR_MIRROR_KEYSTORE" --ks-pass env:WEAR_MIRROR_STORE_PASSWORD --ks-key-alias wearmirror --out "$artifact.apk" build/aligned.apk
"$build_tools/apksigner" verify --verbose "$artifact.apk"
"$build_tools/aapt2" dump badging "$artifact.apk"
if [[ -n "${BUNDLETOOL_CP:-}" ]]; then
    "$build_tools/aapt2" link --proto-format -I "$android_jar" --manifest AndroidManifest.xml -A assets -o build/proto.apk build/resources.zip
    mkdir -p build/base/manifest build/base/dex
    unzip -p build/proto.apk AndroidManifest.xml > build/base/manifest/AndroidManifest.xml
    unzip -p build/proto.apk resources.pb > build/base/resources.pb
    unzip -qo build/proto.apk 'assets/*' 'res/*' -d build/base
    cp build/dex/classes.dex build/base/dex/
    rm -f build/base.zip
    (cd build/base && zip -qr ../base.zip manifest dex resources.pb assets res)
    "$java/java" -cp "$BUNDLETOOL_CP" com.android.tools.build.bundletool.BundleToolMain build-bundle --modules=build/base.zip --output="$artifact.aab" --overwrite
    "$java/jarsigner" -keystore "$WEAR_MIRROR_KEYSTORE" -storepass:env WEAR_MIRROR_STORE_PASSWORD "$artifact.aab" wearmirror
    "$java/java" -cp "$BUNDLETOOL_CP" com.android.tools.build.bundletool.BundleToolMain validate --bundle="$artifact.aab"
fi
