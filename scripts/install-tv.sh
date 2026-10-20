#!/usr/bin/env bash
# For development: builds the TV apps and installs them on the TV connected over adb (USB or Wi-Fi), with
# everything OtvOS needs. Most people should use OpenTV Installer instead.
#   scripts/install-tv.sh [adb serial]
set -euo pipefail
cd "$(dirname "$0")/.."
ADB="${ADB:-$(command -v adb || echo /opt/homebrew/share/android-commandlinetools/platform-tools/adb)}"
a() { if [ -n "${SERIAL:-}" ]; then "$ADB" -s "$SERIAL" "$@"; else "$ADB" "$@"; fi; }
SERIAL="${1:-}"
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17 2>/dev/null || echo /opt/homebrew/opt/openjdk@17)}"
export ANDROID_HOME="${ANDROID_HOME:-$([ -d ~/Library/Android/sdk ] && echo ~/Library/Android/sdk || echo /opt/homebrew/share/android-commandlinetools)}"
(cd launcher-source && ./gradlew -q assembleRelease)
P=com.gathin.tvhome
a install -r -g launcher-source/app/build/outputs/apk/release/app-release.apk
a install -r -g launcher-source/otvlive/build/outputs/apk/release/otvlive-release.apk
for c in \
  "settings put secure enabled_accessibility_services $P/$P.HomeKeyService" \
  "settings put secure accessibility_enabled 1" \
  "dpm set-active-admin --user 0 $P/.SleepAdmin" \
  "cmd notification allow_dnd $P" \
  "cmd notification allow_listener $P/$P.MediaListener" \
  "appops set $P TURN_SCREEN_ON allow" \
  "pm grant $P android.permission.GET_ACCOUNTS" \
  "cmd package set-home-activity --user 0 $P/.MainActivity"; do
  a shell "$c" || true
done
for home in com.google.android.apps.tv.launcherx com.google.android.tungsten.setupwraith com.google.android.tvlauncher com.google.android.leanbacklauncher; do
  a shell pm list packages | grep -q "package:$home$" && a shell pm disable-user --user 0 "$home" >/dev/null || true
done
a shell input keyevent KEYCODE_HOME
echo "Installed. OtvOS is the home screen."
