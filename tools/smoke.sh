#!/usr/bin/env bash
# adb-driven smoke-test helper for AlertPower ("hands and eyes" for a native Compose app).
#
#   tools/smoke.sh launch                 start MainActivity
#   tools/smoke.sh shot [name]            screenshot -> build/smoke/<name>.png
#   tools/smoke.sh dump                   list visible texts / content-descs with bounds
#   tools/smoke.sh tap "<text>"           tap the first node whose text or content-desc contains <text>
#   tools/smoke.sh tapxy <x> <y>          tap raw coordinates
#   tools/smoke.sh back | home | scroll [down|up]
#   tools/smoke.sh logs [n]               last n lines from AlertPower's log tags
#   tools/smoke.sh a11y                   accessibility service state as Android sees it
#   tools/smoke.sh fg                     package of the app in the foreground
#   tools/smoke.sh install                install app/build/outputs/apk/release/app-release.apk
#   tools/smoke.sh still | motion         set the system animator scale to 0 / 1. AlertPower's pulse ring
#                                         respects it; without 'still', dump fails while AlertPower is armed.
set -euo pipefail
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
PKG=app.alertpower
OUT="${SMOKE_OUT:-$(dirname "$0")/../build/smoke}"
mkdir -p "$OUT"

dump_xml() {
  # uiautomator prints the XML followed by a trailer line; keep only the XML.
  "$ADB" exec-out uiautomator dump /dev/tty 2>/dev/null | sed 's/UI hierchary dumped to: \/dev\/tty//'
}

nodes() {
  # uiautomator refuses to dump while the UI is animating ("could not get idle state"); retry a few times.
  local attempt out
  for attempt in 1 2 3; do
    out=$(dump_xml)
    if [[ "$out" == *"<hierarchy"* ]]; then
      printf '%s' "$out" | python3 "$(dirname "$0")/ui_nodes.py"
      return 0
    fi
    "$ADB" shell sleep 0.7
  done
  echo "dump failed: ${out:0:160}" >&2
  return 1
}

cmd="${1:-help}"; shift || true
case "$cmd" in
  launch)  "$ADB" shell am start -W -n "$PKG/.MainActivity" | grep -E 'Status|TotalTime' ;;
  shot)    f="$OUT/${1:-shot}.png"; "$ADB" exec-out screencap -p > "$f"; echo "$f" ;;
  dump)    nodes ;;
  tap)     line=$(dump_xml | python3 "$(dirname "$0")/ui_nodes.py" "$1" || true)
           [ -z "$line" ] && { echo "no node matching '$1'"; exit 1; }
           x=$(cut -f1 <<<"$line"); y=$(cut -f2 <<<"$line")
           echo "tap ($x,$y) -> $(cut -f4 <<<"$line")"; "$ADB" shell input tap "$x" "$y" ;;
  tapxy)   "$ADB" shell input tap "$1" "$2" ;;
  back)    "$ADB" shell input keyevent KEYCODE_BACK ;;
  scroll)  if [ "${1:-down}" = up ]; then "$ADB" shell input swipe 540 700 540 1700 300; else "$ADB" shell input swipe 540 1700 540 700 300; fi ;;
  home)    "$ADB" shell input keyevent KEYCODE_HOME ;;
  logs)    "$ADB" logcat -d -s SliderWakeService:V ScreenActionService:V ScreenWaker:V FloatingButton:V BootReceiver:V A11yToggle:V ForegroundWatcher:V PaymentPause:V PayModeTile:V PayLaunchActivity:V MainActivity:V | tail -n "${1:-40}" ;;
  a11y)    "$ADB" shell dumpsys accessibility | grep -E 'Bound services|Enabled services'
           echo -n "enabled_accessibility_services="; "$ADB" shell settings get secure enabled_accessibility_services ;;
  fg)      "$ADB" shell dumpsys activity activities | grep -m1 -oE 'topResumedActivity=.*' || "$ADB" shell dumpsys window | grep -m1 mCurrentFocus ;;
  install) "$ADB" install -r "$(dirname "$0")/../app/build/outputs/apk/release/app-release.apk" ;;
  still)   "$ADB" shell settings put global animator_duration_scale 0 && echo "animations off (restore with: smoke.sh motion)" ;;
  motion)  "$ADB" shell settings put global animator_duration_scale 1 && echo "animations on" ;;
  *)       sed -n '2,16p' "$0" ;;
esac
