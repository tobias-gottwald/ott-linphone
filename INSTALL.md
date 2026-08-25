# OTT Linphone — build & install on a device

Everything below runs from the repo root (`C:/Code/ott-linphone`).
Prerequisites: JDK 21 (Temurin) on PATH and an Android SDK via `ANDROID_HOME`
(platform 37 + build-tools auto-install on first run when licenses are
accepted — see `licenses/android-sdk-license` in the SDK dir).

## Build a debug APK (locally, no CI needed)

Windows (Git Bash / cmd / PowerShell):

```
gradlew.bat assembleDebug --console=plain
```

The APK lands in:

```
app/build/outputs/apk/debug/linphone-android-debug-<version>.apk
```

Notes:
- Debug builds are signed with the standard Android debug keystore, so they
  install via adb without any release-signing setup. Release/Play builds use
  an own keystore OUTSIDE the repo via `keystore.properties` (see README).
- `app/google-services.json` (Firebase project `ott-linphone`, FCM) is
  committed; the build enables Firebase automatically when it is present.
- The liblinphone SDK is pinned in `gradle/libs.versions.toml`
  (`linphone = "5.5.16"`) and resolves from `download.linphone.org`.

## Install on a device via adb

Phone connected via USB with USB debugging enabled (or over Wi-Fi after
pairing):

```
adb devices                 # confirm the device shows up
adb install -r app/build/outputs/apk/debug/linphone-android-debug-*.apk
```

- `-r` reinstalls/updates an existing build, keeping app data
  (accounts, synced CardDAV lists).
- The app id is `de.otthoeren.linphone`, so it installs **next to** any stock
  Linphone (`org.linphone`) — no conflict.
- Useful companions:
  - `adb logcat -s Linphone` — core/app logs (wake chain, provisioning,
    CardDAV sync all log there)
  - `adb shell am force-stop de.otthoeren.linphone` — simulate an idle/killed
    app before testing the push-wake flow
  - `adb uninstall de.otthoeren.linphone` — clean slate for fresh-install QR
    onboarding tests

## CI (GitHub Actions)

`.github/workflows/android.yml` builds the debug APK on every push to
`master` or `ott/master` (and on PRs) and uploads it as an artifact
(`otthoeren-debug-apk`). Public repos run Actions for free.
