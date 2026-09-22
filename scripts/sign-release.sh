#!/usr/bin/env bash
# Sign a built release artifact with the gpg-encrypted upload keystore:
#   .aab -> jarsigner (Play upload)   .apk -> apksigner (pilot installs)
#
# Secrets (gpg passphrase, keystore password) are read from /dev/tty in
# YOUR terminal. The script deliberately cannot be piped, scripted or
# driven by an agent — that is the point. Agent-driven builds stop at the
# unsigned artifact from `gradlew.bat bundleRelease` / `assembleRelease`.
#
# Usage:
#   bash scripts/sign-release.sh [path/to/artifact.aab|.apk]
#   (default: newest AAB in app/build/outputs/bundle/release/, else
#    newest APK in app/build/outputs/apk/release/)
#
# Env overrides (paths only, never secrets):
#   OTT_KEYSTORE_GPG  encrypted keystore (default ~/.ott-secrets/ott-upload.jks.gpg)
#   OTT_KEY_ALIAS     key alias          (default ott)
set -euo pipefail

SECRETS_DIR="${OTT_SECRETS_DIR:-$HOME/.ott-secrets}"
KEY_GPG="${OTT_KEYSTORE_GPG:-$SECRETS_DIR/ott-upload.jks.gpg}"
ALIAS="${OTT_KEY_ALIAS:-ott}"

command -v jarsigner >/dev/null || { echo "jarsigner (JDK 21) not on PATH" >&2; exit 1; }
[ -f "$KEY_GPG" ] || {
    echo "Encrypted keystore not found: $KEY_GPG" >&2
    echo "Run scripts/make-upload-keystore.sh once first." >&2
    exit 1
}

# Open-probe, not -r: MSYS -r can pass while opening still fails (agent shells).
if ! ( : </dev/tty ) 2>/dev/null; then
    echo "No controlling terminal — run this in your own terminal, not via a pipe/agent." >&2
    exit 1
fi

ARTIFACT="${1:-}"
if [ -z "$ARTIFACT" ]; then
    ARTIFACT="$(ls -t app/build/outputs/bundle/release/*.aab 2>/dev/null | head -n1 || true)"
    [ -z "$ARTIFACT" ] && ARTIFACT="$(ls -t app/build/outputs/apk/release/*.apk 2>/dev/null | head -n1 || true)"
fi
case "$(basename "$ARTIFACT")" in
    *.aab|*.apk) [ -f "$ARTIFACT" ] || { echo "Not a file: $ARTIFACT" >&2; exit 1; } ;;
    *) echo "Usage: $0 [release .aab or .apk]  (found: '${ARTIFACT:-none}')" >&2
       echo "Build first: gradlew.bat bundleRelease  (or assembleRelease)" >&2
       exit 1 ;;
esac

read -rs -p "gpg passphrase for $KEY_GPG: " GPG_PASS </dev/tty; printf '\n' >&2
read -rs -p "keystore password: " KS_PASS </dev/tty; printf '\n' >&2

KEY="$(mktemp "${TMPDIR:-/tmp}/ott-sign-key.XXXXXX")"
trap 'rm -f "$KEY"' EXIT
chmod 600 "$KEY"

if ! printf '%s\n' "$GPG_PASS" | gpg --batch -q --pinentry-mode loopback --passphrase-fd 0 \
        -o "$KEY" "$KEY_GPG"; then
    echo "Decryption failed — wrong gpg passphrase?" >&2
    exit 1
fi
unset GPG_PASS

case "$(basename "$ARTIFACT")" in
    *.aab)
        # AABs are JAR-signed in place; Play accepts SHA-256/RSA without TSA.
        jarsigner -keystore "$KEY" -storepass "$KS_PASS" \
            -sigalg SHA256withRSA -digestalg SHA-256 \
            "$ARTIFACT" "$ALIAS"
        jarsigner -verify "$ARTIFACT" >/dev/null
        ;;
    *.apk)
        SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$LOCALAPPDATA/Android/Sdk}}"
        BT="$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -n1 || true)"
        APKSIGNER="${BT}apksigner.bat"
        [ -f "$APKSIGNER" ] || { echo "apksigner not found under $SDK (set ANDROID_HOME)" >&2; exit 1; }
        OUT="$(mktemp "${TMPDIR:-/tmp}/ott-signed.XXXXXX.apk")"
        trap 'rm -f "$KEY" "$OUT"' EXIT
        OTT_KS_PASS="$KS_PASS" "$APKSIGNER" sign \
            --ks "$KEY" --ks-key-alias "$ALIAS" --ks-pass env:OTT_KS_PASS \
            --out "$OUT" "$ARTIFACT"
        mv -f "$OUT" "$ARTIFACT"
        "$APKSIGNER" verify --print-certs "$ARTIFACT" >/dev/null
        ;;
esac
unset KS_PASS

echo
echo "OK, signed: $ARTIFACT"
echo "Next:"
echo "  Play: https://play.google.com/console -> your app -> Testing -> Internal testing -> New release"
echo "  Pilot: adb install -r \"$ARTIFACT\""
