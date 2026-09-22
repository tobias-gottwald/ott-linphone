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
  install via adb without any release-signing setup. Release signing is a
  separate interactive step (see "Build a release artifact" below).
- `app/google-services.json` (Firebase project `ott-linphone`, FCM) is
  committed; the build enables Firebase automatically when it is present.
- The liblinphone SDK is pinned in `gradle/libs.versions.toml`
  (`linphone = "5.5.16"`) and resolves from `download.linphone.org`.

## CI (GitHub Actions) — where the debug APK download lives

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

## Build a release artifact (unsigned) + sign it

Debug builds skip R8 minification and ship with `android:debuggable`, which
disables ART optimizations — fine for development, not representative for
performance testing or pilot hand-out. Release builds are R8-minified and
deliberately **unsigned**: no keystore is reachable from Gradle, so no
secret ever sits in the repo, the build environment or CI. Signing is a
separate interactive step that prompts in YOUR terminal and cannot be
piped, scripted or driven by an agent — by design.

One-time setup per machine (~2 min, in your own terminal):

1. Create the upload keystore, stored gpg-encrypted OUTSIDE the repo:
   ```
   bash scripts/make-upload-keystore.sh
   ```
   Prompts for a keystore password and a gpg passphrase, writes
   `~/.ott-secrets/ott-upload.jks.gpg`, and verifies the roundtrip.
2. Back both secrets up offline (password manager/paper + a copy of the
   `.gpg` file). With Play App Signing a lost upload key is recoverable via
   a Console key reset, but that support flow costs days — a backup is cheaper.

Build + sign:

```
gradlew.bat bundleRelease --console=plain     # Play upload: AAB
gradlew.bat assembleRelease --console=plain   # pilot hand-out: APK
bash scripts/sign-release.sh                  # signs newest .aab (or .apk)
```

- AAB output: `app/build/outputs/bundle/release/*.aab`
- APK output: `app/build/outputs/apk/release/linphone-android-release-*.apk`
  (install exactly like the debug APK)
- `bash scripts/sign-release.sh <path>` targets a specific artifact;
  `.aab`s are JAR-signed (SHA-256/RSA), `.apk`s via `apksigner` (v2+).

## Google Play distribution (manual Console flow)

No API credentials are needed on this machine at all: uploads happen in
your logged-in browser. That IS the credential-leak avoidance strategy —
nothing an agent can read is worth anything.

One-time console setup:

1. **Developer account** (one-time $25) at play.google.com/console. Note:
   NEW personal accounts (created after 2023-11-13) must run a closed test
   with 12 testers opted in for 14 consecutive days before production
   access; organization accounts (require a D-U-N-S number) skip that.
   The **internal testing track** (up to 100 testers, opt-in link) works
   immediately either way — enough for all staff devices. EEA accounts may
   face an additional trader verification step.
2. **Create app**: All apps → Create app (name e.g. "OTThören Telefon",
   default language, App, Free).
3. **First upload**: Testing → Internal testing → New release → upload the
   signed `.aab` from `scripts/sign-release.sh`. Play enrolls the app in
   **Play App Signing**: accept the default — Google generates and manages
   the app signing key, our keystore becomes the **upload key** only. A
   leaked or lost upload key alone cannot publish updates and is
   recoverable via a Console-side key reset.
4. **"Set up your app" checklist** (required before production): store
   listing (assets exist in `metadata/en-US/` and `metadata/icon-source/`),
   graphics, privacy policy URL, Data safety (the app handles SIP account
   credentials, contacts, call logs), content rating questionnaire, target
   audience, app access, countries/pricing (Free).
5. **versionCode** is hardcoded in `app/build.gradle.kts` (currently
   602006). Every upload needs a strictly higher one — bump + commit per
   release. versionName comes from `git describe` on upstream tags.
6. **GPLv3**: keep the public fork repo link in the listing description
   (satisfies corresponding-source).
7. **Testers**: join via the track's opt-in link (any Google account), or
   add them by email in the console.
8. **Production**: promote a tested release; expect a standard review
   (longer for the very first production submission).

Later automation (optional): a service-account JSON + upload action
(`r0adkll/upload-google-play` or fastlane `supply`) in GitHub Actions with
the JSON as an Actions secret. Skip it while manual browser upload
suffices — zero Play credentials on disk is the win.
