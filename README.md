# Nordic Step Counter

A minimalist, privacy-first Android step counter built with Jetpack Compose, Room, and DataStore.

## Highlights

- **Zero Network & Zero Trackers:** No `INTERNET` permission, no ads, no analytics SDKs, and no GPS location permissions.
- **Hardware-Batched Counting:** Uses Android's low-power `Sensor.TYPE_STEP_COUNTER` (60s batching) inside a `health` foreground service (`StepCounterService`) with reboot and pause/resume handling.
- **Swiss / Nordic UI & 8 Themes:** Clean typographic layout across 8 palettes (`Nordic`, `Gruvbox`, `Catppuccin`, and `TokyoNight` in Dark and Light).
- **Analytics & Calibration:** 24-hour, 7-day, and 30-day step charts, all-time personal best, customizable daily goals, and height/weight/stride calibration.
- **Zero-Permission Backup & Restore:** Hardened Android Auto Backup plus manual export/restore (plain JSON or AES-256-GCM passphrase-encrypted) through Android's Storage Access Framework (Google Drive, Nextcloud, or local files) with conflict-free step merging.

## Requirements

- Android 8.0+ (`minSdk 26`, `targetSdk 35`)
- Hardware step counter sensor (`android.hardware.sensor.stepcounter`)

## Build & Test

```bash
# Run unit tests and Android lint
./gradlew testDebugUnitTest lintDebug

# Build debug APK
./gradlew assembleDebug

# Build signed release APK (configure keystore.properties or SIGNING_* env vars)
./gradlew assembleRelease
```
