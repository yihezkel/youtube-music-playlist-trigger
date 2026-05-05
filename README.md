# YTM Trigger

Android app that automatically opens YouTube Music and plays a randomly chosen playlist on a schedule. Designed for a phone dedicated to music playback (e.g. as a kitchen/bedroom alarm-clock-radio).

## Features

- **Schedule-based triggering** with day-of-week + time picker.
- **Multiple playlists per schedule**, randomly chosen (with rolling "don't repeat last 3" history).
- **Default playlists** applied to new schedules; per-schedule overrides.
- **Robust playback** via deep-link intent + AccessibilityService:
  - Wakes screen, dismisses keyguard.
  - Presses Play on the playlist page.
  - Enables shuffle and skips one track so the first song is random.
  - Dismisses Premium upsells / "Try X" / "Maybe later" dialogs automatically.
  - Skips if a phone call is active.
  - Retries the whole flow once on verification failure.
  - Dumps the YouTube Music window tree to logs on failure.
- **Setup checklist + self-test** with vendor-specific advice (Samsung, Xiaomi, Huawei, Oppo, Vivo, OnePlus, Pixel, etc.) for "Sleeping apps" / "Auto-launch" / "Protected apps" systems.
- **Manual trigger** from the home screen and a home-screen widget.
- **Persistent logs** with in-app viewer, level filter, search, copy-to-clipboard, share-as-file (14-day retention).

## Build

Open in Android Studio (Hedgehog or newer) and run, or:

```sh
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Min SDK: Android 14 (API 34). Target SDK: Android 15 (API 35).

## Releases

Tagged commits matching `v*` build a release APK via GitHub Actions and attach it to the GitHub Release. Download the APK from the [Releases page](../../releases).

## Permissions / setup

After install, the app's home screen shows a setup checklist. You'll need to:

1. Allow exact alarms.
2. Enable the **YTM Trigger Helper** Accessibility service.
3. Disable battery optimization for this app.
4. (Vendor-specific) follow the steps in **Self-test** for your device's "Sleeping apps" / "Auto-launch" / "Protected apps" system.

## License

MIT
