# AlertPower

Makes a OnePlus 9 Pro usable with a dead power button, using only the alert slider.
The volume buttons are left completely alone.

| Power button job | AlertPower replacement |
|---|---|
| Wake the screen | Screen off: move the slider one notch |
| Screen off (short press) | Screen on: flick the slider and bring it straight back |
| Power menu (long press) | Screen on: flick, back, flick |
| Restart | "Restart phone" button in the app. It opens the power menu and performs the two-finger swipe up (OxygenOS) or taps Restart |
| Screen off / power menu by button | Android's accessibility button: tap = screen off, double tap = power menu. AlertPower's own floating button: tap = screen off, press and hold = power menu |
| Pay with Paytm etc. | Pay mode: open the payment app from AlertPower's chips, a home screen shortcut or the quick settings tile. The accessibility service is off while you pay and back on afterwards |
| Power off | Deliberately not offered. A powered-off phone cannot be turned on without the button |
| Hard reset / boot from off | Not possible in software |

A single slider move on its own does nothing extra, so the slider keeps working as the normal
ring / vibrate / silent switch.

Native Kotlin, Jetpack Compose with Material 3, no root, no Play Store.
Target: OnePlus 9 Pro LE2121, Android 14 (OxygenOS 14.0.0.1902).

## How it works

- `SliderWakeService` is a foreground service. The slider is a hardware key
  (`oplus,hall_tri_state_key`), so moving it wakes the kernel even from deep sleep. OxygenOS
  then changes the ringer mode or the Do-Not-Disturb filter, which fires `RINGER_MODE_CHANGED` /
  `INTERRUPTION_FILTER_CHANGED`. The service collapses those into "moves" and counts them per
  gesture:
  - screen off, any move: wake with a full wake lock, then ignore moves for 2.5 s so the slider
    can be flicked back without locking again. If the screen is still off 700 ms after the wake
    lock, an invisible `WakeActivity` with `turnScreenOn` is launched as a fallback.
  - screen on, 1 move: nothing.
  - screen on, 2 moves within 1.2 s of each other: screen off.
  - screen on, 3 or more moves: power menu.
- `ScreenActionService` is an accessibility service that performs the lock-screen and
  power-dialog global actions. For "Restart phone" it opens the power dialog and, for up to
  8 seconds, either performs a two-finger swipe UP through the accessibility gesture API
  (OxygenOS 13/14 shows "Swipe up with two fingers to restart your device", there is no button)
  or taps any button labelled Restart or Reboot on stock-style menus, including a confirmation
  step. It never swipes down and never taps anything containing "power off", "shut",
  "emergency", "lockdown" or "cancel". Window content is only inspected while a restart you
  requested is in progress, and the labels seen are logged under the `ScreenActionService`
  tag so a mismatch is easy to diagnose.
- Two on-screen buttons, both handled by `ScreenActionService`:
  - Android's accessibility button (Settings, Accessibility, Accessibility button, assign
    AlertPower). The service declares `flagRequestAccessibilityButton`, so taps arrive through
    `AccessibilityButtonController`. Android only reports taps, never press-and-hold, so
    tap = screen off (after a 400 ms double-tap window) and double tap = power menu.
  - `FloatingButton`, a draggable round button drawn as a `TYPE_ACCESSIBILITY_OVERLAY` window,
    which needs no extra permission. Tap = screen off, hold 500 ms = power menu, drag to move
    (snaps to the nearest side, position remembered). Hidden while the screen is off or locked.
- `PaymentPause` (pay mode) turns `ScreenActionService` off while a payment app is open and
  back on afterwards, see below. `ForegroundWatcher` (owned by `SliderWakeService`) polls
  `UsageStatsManager` for the app in front while the screen is on, `PayLaunchActivity` is the
  invisible trampoline behind the pause-then-launch chips and shortcuts, and `PayModeTile` is
  the quick settings tile.
- `BootReceiver` restarts the slider service after a reboot or an app update.
- The UI (`ui/`) is Compose: a hero card with the master switch and an animated status ring,
  one card per gesture, Restart and Test actions, and a setup screen with a progress bar.

## Build

Gradle 8.14 needs JDK 17 to 24. The system JDK on this Mac is 26, so point `JAVA_HOME` at
Android Studio's bundled JDK, or just open the folder in Android Studio.

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleRelease     # small R8-shrunk APK, signed with the debug key
# -> app/build/outputs/apk/release/app-release.apk  (about 1.8 MB)
./gradlew assembleDebug       # unshrunk, about 57 MB, useful for Layout Inspector
```

`local.properties` holds the SDK path for this machine and is git-ignored.

## Install on the phone

1. Enable Developer options and USB debugging on the phone, plug it in, then:
   ```sh
   adb install -r AlertPower-0.4.0.apk
   adb shell dumpsys deviceidle whitelist +app.alertpower   # battery whitelist, works over adb
   ```
   Installing through adb avoids Android 13's "restricted settings" block on accessibility.
   If you sideload from a file manager instead, go to App info, tap the three-dot menu and
   choose "Allow restricted settings" before step 3.
2. Open AlertPower. Tap **Finish setup**.
3. Allow notifications, open accessibility settings and turn on **AlertPower**, allow
   unrestricted battery. The progress bar fills as you go.
4. Back on the home screen, turn on the master switch in the hero card. It shows **Armed**.
5. Tap **Test wake**. The screen goes dark and should come back on after 8 seconds.
6. With the screen on, flick the slider and back. The screen should turn off.
7. Wait a few seconds, move the slider one notch. The lock screen should appear. Move it back.
8. Tap **Restart phone**, confirm. The phone should reboot and AlertPower should be armed
   again after boot.

Leave **Display over other apps** off unless the normal wake fails and you need the activity
fallback: Google Pay refuses to run while a sideloaded app holds that permission.

OxygenOS blocks `pm grant`, `appops set` and `settings put secure` over adb unless
"Disable permission monitoring" is turned on in Developer options. With it on, all of the
setup can be scripted.

## Pay mode (Paytm and other payment apps)

Paytm, Google Pay, BHIM and several Indian bank apps refuse to work while *any* accessibility
service from a sideloaded (non-Play) app is enabled, whatever it does. `ScreenActionService` is
such a service. Paytm 10.86 on this phone starts and scans fine but stops at the enter-amount
screen with "Important Security Alert, Suspicious App Detected", naming AlertPower. The check is a
checkpoint, not a lock: with the service off, "Ok, I Understand" dismisses it and the same screen
continues. Other apps (Google Pay, reportedly) check at launch instead. The slider wake never
needed the service, so AlertPower turns it genuinely off while you pay and back on afterwards.

How it works:

- **Auto-pause.** With "Pause automatically when a payment app opens" on, the accessibility
  service turns itself off the moment it sees a selected app's window come to the front, and the
  slider service polls the app in front every 0.5 s while the screen is on as a second pair of
  eyes. For Paytm that is long before the amount screen, so opening it from the launcher just
  works. Auto-pause only runs when the restore permission below is granted, otherwise you would
  be left turning the service on in Settings by hand.
- **Pause then launch.** The home screen also shows a chip for each selected payment app that is
  installed. Tapping it turns the service off first and then opens the app, which also covers
  apps that check at launch, before any watcher could react. The pin button on a chip puts the
  same thing on your launcher as a shortcut, and AlertPower publishes long-press shortcuts for
  up to four apps.
- **Restore.** 4 s after a non-payment app is in front, the service is turned back on. A pause
  that nothing is watching ends after 15 minutes. After a manual pause (tile or notification)
  AlertPower waits up to 90 s for the payment app before an unrelated app counts as "left".
- **Slider wake is unaffected.** While paused the hero card says "Paused for payments", the
  notification says which app, and the quick settings tile shows "Paused". The screen-off and
  power-menu gestures still work: the service is turned on for the one action and off again
  right after (under a second for screen off, about 12 s for the power menu).
- **Manual.** The notification's **Pay mode** action, the **Pay mode** quick settings tile
  ("Add quick settings tile" on the home screen, or drag it in from the tile editor) and the
  **Pause now / Resume** buttons all toggle the pause. Choose which apps count under
  **Choose apps…**; the list is seeded with the known payment apps that are installed.

Setup, once, from a computer. Turning the service off needs no permission; turning it back on
and watching the foreground do:

```sh
adb shell pm grant app.alertpower android.permission.WRITE_SECURE_SETTINGS   # turn the service back on
adb shell appops set app.alertpower GET_USAGE_STATS allow                    # or Settings > Apps > Special app access > Usage access
```

On OxygenOS both commands are refused with a permission error until Developer options >
**Disable permission monitoring** is on. The setup screen warns about this while it is still
off. Without the first grant, the manual pause (tile, notification, chips) still turns the
service off, but you have to turn it on again in Settings; auto-pause stays out of the way until
AlertPower knows it can restore.

Privacy: to recognise a payment app the accessibility service now looks at the *package name*
of newly focused windows. It never reads window content outside a restart you requested, same
as before. The foreground watcher reads package names only as well.

## OxygenOS battery settings (important)

OxygenOS kills background apps during long sleep. This is the same reason double-tap to wake
stops working after a while. Do all of these:

- App info for AlertPower, Battery usage: allow background activity, allow auto-launch.
- Settings, Battery, More settings: turn off "Sleep standby optimisation".
- Recents: long-press the AlertPower card and choose Lock.
- Keep "Unrestricted battery" granted.

Menu names differ slightly between OxygenOS builds.

## Tuning

Constants at the top of `SliderWakeService.kt`:

| Constant | Default | Meaning |
|---|---|---|
| `GESTURE_SETTLE_MS` | 1200 | Quiet time that ends a gesture. Raise it if your flick-and-return is slow |
| `POST_WAKE_GRACE_MS` | 2500 | After a wake, moves are ignored this long so you can return the slider |
| `MIN_MOVE_SPACING_MS` | 150 | Broadcasts closer than this are one physical move |

And in `ScreenActionService.kt`, `RESTART_WORDS` / `FORBIDDEN_WORDS` if the power menu on
your build uses different labels.

## Known limits

- If the phone is fully powered off, nothing here can boot it. On the 9 Pro the only way back
  without the button is EDL mode (hold both volume keys while plugging in USB) and the MSM
  Download Tool, which wipes the phone. Always use Restart, never Power off.
- If the OS freezes, the 10 s hard-reset is a hardware function and cannot be replaced.
- A slider move changes your ring mode. Flick-and-return leaves it where it was; the
  three-move power-menu gesture leaves it one notch off, move it back afterwards.
- A ring-mode change from anywhere else (a DND schedule, a settings toggle) counts as one move
  and therefore does nothing.
- The wake lock API is deprecated. It still works on Android 14. If a future update breaks it,
  the activity fallback with "Display over other apps" takes over automatically.
