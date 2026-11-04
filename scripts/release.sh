#!/usr/bin/env bash
# Builds every part of OpenTV and publishes it as a GitHub release, which OpenTV Installer downloads from.
#   scripts/release.sh 1.1 "What's new in this release"
# Needs: JDK 17, the Android SDK, Xcode, xcodegen and gh (signed in). The TV apps are signed with this Mac's
# Android debug key (~/.android/debug.keystore); keep using the same key so installed TVs update in place.
set -euo pipefail
cd "$(dirname "$0")/.."
VERSION="${1:?usage: scripts/release.sh <version> [notes]}"
NOTES="${2:-OpenTV $VERSION}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17 2>/dev/null || echo /opt/homebrew/opt/openjdk@17)}"
export ANDROID_HOME="${ANDROID_HOME:-$([ -d ~/Library/Android/sdk ] && echo ~/Library/Android/sdk || echo /opt/homebrew/share/android-commandlinetools)}"

echo "▸ TV apps"
(cd launcher-source && ./gradlew -q assembleRelease -PotvVersion="$VERSION")
cp launcher-source/app/build/outputs/apk/release/app-release.apk "$OUT/OtvOS.apk"
cp launcher-source/otvlive/build/outputs/apk/release/otvlive-release.apk "$OUT/IPTV.apk"

echo "▸ Mac apps"
DD="$OUT/dd"
(cd tv-remote-apps && xcodegen -q)
for scheme in TVRemoteMac OpenTVInstaller; do
  xcodebuild -project tv-remote-apps/TVRemote.xcodeproj -scheme "$scheme" -configuration Release \
    -derivedDataPath "$DD" -destination 'platform=macOS' MARKETING_VERSION="$VERSION" -quiet build
done
ditto -c -k --keepParent "$DD/Build/Products/Release/OpenTV Companion.app" "$OUT/OpenTV-Companion.zip"
ditto -c -k --keepParent "$DD/Build/Products/Release/OpenTV Installer.app" "$OUT/OpenTV-Installer.zip"

echo "▸ iPhone remote (unsigned IPA, for AltStore or Sideloadly)"
xcodebuild -project tv-remote-apps/TVRemote.xcodeproj -scheme TVRemote -configuration Release -sdk iphoneos \
  -destination 'generic/platform=iOS' -derivedDataPath "$DD" MARKETING_VERSION="$VERSION" \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO -quiet build
mkdir -p "$OUT/ipa/Payload"
cp -R "$DD/Build/Products/Release-iphoneos/TVRemote.app" "$OUT/ipa/Payload/"
(cd "$OUT/ipa" && zip -qry ../Otv-Remote.ipa Payload)

echo "▸ Release v$VERSION"
gh release create "v$VERSION" --title "OpenTV $VERSION" --notes "$NOTES" \
  "$OUT/OpenTV-Installer.zip" "$OUT/OpenTV-Companion.zip" "$OUT/OtvOS.apk" "$OUT/IPTV.apk" "$OUT/Otv-Remote.ipa"
echo "Done: https://github.com/harkhowalia-1/OpenTV/releases/tag/v$VERSION"
