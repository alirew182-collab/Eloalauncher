#!/usr/bin/env bash
# Builds small but real APK fixtures for domain:bedrock tests using Android build-tools.
# Usage: ANDROID_HOME=/path/to/sdk scripts/make_apk_fixtures.sh
set -euo pipefail
BT="$ANDROID_HOME/build-tools/36.0.0"
JAR="$ANDROID_HOME/platforms/android-36/android.jar"
OUT="$(cd "$(dirname "$0")/.." && pwd)/domain/bedrock/src/test/resources/apk"
W="$(mktemp -d)"
mkdir -p "$OUT"
keytool -genkeypair -keystore "$W/a.jks" -storepass password -keypass password -alias a -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Fixture A" -noprompt >/dev/null 2>&1
keytool -genkeypair -keystore "$W/b.jks" -storepass password -keypass password -alias b -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Fixture B" -noprompt >/dev/null 2>&1

build() { # name manifest-xml libabi
  local name=$1 abi=$3
  printf '%s' "$2" > "$W/$name.xml"
  "$BT/aapt2" link --manifest "$W/$name.xml" -I "$JAR" -o "$W/$name.unaligned.apk"
  if [ -n "$abi" ]; then
    mkdir -p "$W/$name/lib/$abi" && printf '\x7fELFfixture' > "$W/$name/lib/$abi/libfixture.so"
    (cd "$W/$name" && zip -q -0 -r "$W/$name.unaligned.apk" lib)
  fi
  "$BT/zipalign" -f -p 4 "$W/$name.unaligned.apk" "$W/$name.apk"
}
sign() { "$BT/apksigner" sign --ks "$W/$2.jks" --ks-pass pass:password --v4-signing-enabled false --out "$OUT/$3" "$W/$1.apk"; }

BASE='<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.mojang.minecraftpe" android:versionCode="1234" android:versionName="1.21.0.03"><uses-sdk android:minSdkVersion="26" android:targetSdkVersion="34"/><application android:hasCode="false"/></manifest>'
SPLIT='<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.mojang.minecraftpe" android:versionCode="1234" split="config.arm64_v8a"><application android:hasCode="false"/></manifest>'
NEWER='<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.mojang.minecraftpe" android:versionCode="1300" android:versionName="1.21.10"><uses-sdk android:minSdkVersion="28"/><application android:hasCode="false"/></manifest>'
OTHER='<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="org.example.other" android:versionCode="7" android:versionName="7.0"><uses-sdk android:minSdkVersion="35"/><application android:hasCode="false"/></manifest>'

build base "$BASE" ""
build split "$SPLIT" arm64-v8a
build newer "$NEWER" x86_64
build other "$OTHER" ""
sign base a base.apk
sign split a split-arm64.apk
sign split b split-arm64-otherkey.apk
sign newer a newer-x86_64.apk
sign other b other-minsdk35.apk
cp "$W/base.apk" "$OUT/base-unsigned.apk"
rm -rf "$W"
ls -la "$OUT"
