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

## Connect the phone over Wi-Fi (wireless ADB) cheatsheet

Android 11+, phone and PC on the same network:

1. Phone: Settings → Developer options → **Wireless debugging** → on.
2. Phone: tap **Pair device with pairing code** — it shows
   `IP:PORT` and a 6-digit code (the pairing port is NOT the connect port).
3. PC:
   ```
   adb pair 10.221.x.x:PAIR_PORT   # then enter the 6-digit code
   adb connect 10.221.x.x:CONNECT_PORT
   adb devices                      # must list the phone as "device"
   ```
   The connect port is on the main Wireless-debugging screen; both ports can
   change when Wi-Fi reconnects — re-check the screen and `adb connect` again.
4. `adb disconnect` when done.

Fallbacks: plain USB always works; on older Androids use
`adb tcpip 5555 && adb connect IP:5555` once via USB. If `adb pair` can't
find the device, update the platform-tools (`sdkmanager --install
"platform-tools"`).

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

To push the same APK to **every** connected device in one go, one parallel
streamed install per device (APK path is the argument). Git Bash — define
once, then call:

```
adb-install-all() { adb devices | awk 'NR>1 && $2=="device" {print $1}' | xargs -r -P0 -I{} adb -s {} install -r "$1"; }
adb-install-all app/build/outputs/apk/debug/linphone-android-debug-*.apk
```

PowerShell 7+ (`ForEach-Object -Parallel`):

```
function adb-install-all($apk) { adb devices | Select-String "device$" | ForEach-Object { $_.Line.Split()[0] } | ForEach-Object -Parallel { adb -s $_ install -r $using:apk } -ThrottleLimit 16 }
adb-install-all app/build/outputs/apk/debug/linphone-android-debug-*.apk
```

Only serials in `device` state are targeted (`offline`/`unauthorized` are
skipped); the installs run concurrently and their output streams interleave.

## Build a release APK (realistic performance, pilot builds)

Debug builds skip R8 minification and ship with `android:debuggable`, which
disables ART optimizations — fine for development, not representative for
performance testing or pilot hand-out. Release builds need our own keystore:

1. Create the release keystore (once, OUTSIDE version control — the path is
   already gitignored):
   ```
   keytool -genkeypair -v -keystore app/bc-android.keystore -alias ott \
     -keyalg RSA -keysize 4096 -validity 10000
   ```
2. Fill in `keystore.properties` in the repo root (committed placeholders —
   keep your real passwords OUT of git):
   ```
   storeFile=bc-android.keystore
   storePassword=...
   keyAlias=ott
   keyPassword=...

   Modern keystores are PKCS12, which requires `keyPassword` to equal
   `storePassword` (keytool silently ignores a differing key password).
   The file is tracked, so after filling it in run
   `git update-index --skip-worktree keystore.properties` to keep git from
   ever staging your passwords (undo with `--no-skip-worktree`).
3. Build:
   ```
   gradlew.bat assembleRelease --console=plain
   ```
   Output: `app/build/outputs/apk/release/linphone-android-release-*.apk`,
   R8-minified and signed. Install exactly like the debug APK.

For Google Play an **AAB** (not APK) is required: `gradlew.bat bundleRelease`
→ `app/build/outputs/bundle/release/linphone-android-release-*.aab`.

## CI (GitHub Actions) — where the APK download lives

`.github/workflows/android.yml` builds the debug APK on every push to
`master` or `ott/master` (and on PRs) and uploads it as an artifact
(`otthoeren-debug-apk`). Public repos run Actions for free.

**Artifacts are only downloadable when signed in to GitHub** (any account
with read access to the repo — anonymous visitors get nothing, not even a
button). To fetch the CI APK:

- Browser: repo → **Actions** tab → click the latest "Android CI" run →
  scroll to the **Artifacts** box at the bottom → `otthoeren-debug-apk`
  (a zip containing the APK). Retention is 90 days.
- CLI (once `gh auth login` has been done):
  ```
  gh run list --workflow "Android CI" --limit 1
  gh run download <run-id> -n otthoeren-debug-apk -D ci-apk
  ```
- For login-free distribution later (testers, pilot): attach the APK to a
  GitHub **Release** — release assets are publicly downloadable when the
  repo is public. Not set up yet.

In practice the local `assembleDebug` build is identical to the CI one, so
the artifact mainly proves CI is green (and will matter once CI also builds
release/Play artifacts).

## Google Play distribution (future — nothing here is set up yet)

Checklist of what a Play rollout needs (manual Console work first, CI upload
automation only after the manual flow works):

- A Google Play developer account (one-time $25). Note: NEW personal accounts
  must run a closed test with 20 testers for 14 days before production
  access; organization accounts skip that. For the pilot, the **internal
  testing track** (up to 100 testers) works immediately either way.
- The release keystore + `keystore.properties` from the section above
  (becomes the Play App Signing **upload key**).
- A signed `bundleRelease` AAB, uploaded manually via the Play Console the
  first time (create app → internal testing → upload).
- Our own version scheme: the build currently derives versionName/versionCode
  from upstream tags/git (e.g. `6.3.0-alpha.58+<hash>`); before the first
  Play upload we must pin our own monotonic versionCode and an OTT-branded
  versionName.
- GPLv3: Play listing must offer corresponding source (link to our public
  repo, or source on request).
- Later, CI upload automation: a Google service account JSON (created in Play
  Console → Setup → API access), stored as a GitHub secret, plus an upload
  action such as `r0adkll/upload-google-play`. Not needed for the pilot.
