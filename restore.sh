#!/usr/bin/env bash
# Put the Chromecast HD back to stock Google TV: re-enable everything that was disabled,
# make the Google TV home screen the default again and turn the ambient screensaver back on.
#
# Connect first, either by USB-C cable (simplest) or wireless debugging:
#   adb devices                      # USB: the Chromecast should be listed
#   adb connect 192.168.1.50:<port>  # wireless: port is shown under Developer options > Wireless debugging
# Then: ./restore.sh [serial]        # serial only needed if more than one device is connected
set -euo pipefail
cd "$(dirname "$0")"
ADB="${ADB:-$(command -v adb || echo /opt/homebrew/share/android-commandlinetools/platform-tools/adb)}"
SERIAL="${1:-}"
a() { if [ -n "$SERIAL" ]; then "$ADB" -s "$SERIAL" "$@"; else "$ADB" "$@"; fi; }

for pkg in $(grep -vE '^\s*(#|$)' debloat.txt | sed -E 's/\s+#.*$//'); do
  a shell pm enable "$pkg"
done
a shell pm enable com.google.android.tungsten.setupwraith
a shell pm enable com.google.android.apps.tv.launcherx
a shell cmd package set-home-activity --user 0 com.google.android.apps.tv.launcherx/.home.HomeActivity
a shell cmd appops set com.android.vending RUN_ANY_IN_BACKGROUND allow
a shell cmd appops set com.android.vending RUN_IN_BACKGROUND allow
a shell settings put secure screensaver_components com.google.android.apps.tv.dreamx/.service.Backdrop
# Launcher extras: Control Center's Sleep permission and the hold-Home watcher.
a shell dpm remove-active-admin --user 0 com.gathin.tvhome/.SleepAdmin || true
a shell settings delete secure enabled_accessibility_services
a shell cmd notification disallow_dnd com.gathin.tvhome || true
a shell cmd notification disallow_listener com.gathin.tvhome/com.gathin.tvhome.MediaListener || true
a shell input keyevent KEYCODE_HOME
echo "Stock Google TV restored. To also remove the new apps: adb uninstall com.gathin.tvhome; adb uninstall local.cloudgaming; adb uninstall io.github.jqssun.airplay"
