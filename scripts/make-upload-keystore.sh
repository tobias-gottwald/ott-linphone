#!/usr/bin/env bash
# One-time per machine: create the Play upload keystore and store it
# gpg-encrypted OUTSIDE the repo (~/.ott-secrets/ott-upload.jks.gpg).
#
# Two secrets are prompted on /dev/tty (your terminal only):
#   1. keystore+key password (PKCS12: one password for both)
#   2. gpg passphrase encrypting the keystore file
# Neither is ever written to disk unencrypted, passed via argv or env.
set -euo pipefail

SECRETS_DIR="${OTT_SECRETS_DIR:-$HOME/.ott-secrets}"
DEST="$SECRETS_DIR/ott-upload.jks.gpg"
ALIAS="${OTT_KEY_ALIAS:-ott}"
command -v keytool >/dev/null || { echo "keytool (JDK 21) not on PATH" >&2; exit 1; }
command -v gpg >/dev/null || { echo "gpg not on PATH (git-for-windows ships one)" >&2; exit 1; }

if [ -e "$DEST" ]; then
    echo "Refusing to overwrite existing $DEST" >&2
    echo "(move it away first if you really want a new key)" >&2
    exit 1
fi

if ! ( : </dev/tty ) 2>/dev/null; then
    echo "No controlling terminal — run this in your own terminal, not via a pipe/agent." >&2
    exit 1
fi

prompt_secret() { # $1=variable name, $2=prompt text, $3=min length
    local __var="$1" __min="${3:-1}" a b
    while :; do
        read -rs -p "$2: " a </dev/tty; printf '\n' >&2
        if [ "${#a}" -lt "$__min" ]; then
            echo "Must be at least $__min characters, retry." >&2
            continue
        fi
        read -rs -p "$2 (again): " b </dev/tty; printf '\n' >&2
        if [ "$a" = "$b" ]; then
            printf -v "$__var" '%s' "$a"
            return 0
        fi
        echo "Entries differ, retry." >&2
    done
}

echo "Creating upload keystore (alias '$ALIAS', RSA-4096, PKCS12) ..."
prompt_secret KS_PASS "Keystore+key password" 6
prompt_secret GPG_PASS "gpg passphrase for $DEST" 8

TMPDIR_KEY="$(mktemp -d "${TMPDIR:-/tmp}/ott-upload-key.XXXXXX")"
TMP="$TMPDIR_KEY/upload.jks"
trap 'rm -rf "$TMPDIR_KEY"' EXIT
chmod 700 "$TMPDIR_KEY"

keytool -genkeypair \
    -keystore "$TMP" -alias "$ALIAS" -storetype PKCS12 \
    -keyalg RSA -keysize 4096 -validity 10000 \
    -storepass "$KS_PASS" \
    -dname "CN=OTThoeren Upload Key, O=OTThoeren, C=DE"

mkdir -p "$SECRETS_DIR"
chmod 700 "$SECRETS_DIR"

# Passphrase goes in via stdin pipe (fd 3 herestrings are unsupported on MSYS)
printf '%s\n' "$GPG_PASS" | gpg --batch --yes --pinentry-mode loopback --passphrase-fd 0 \
    --symmetric --cipher-algo AES256 --armor \
    -o "$DEST" "$TMP"

# roundtrip verification: decrypt to a temp file and list the key
printf '%s\n' "$GPG_PASS" | gpg --batch -q --pinentry-mode loopback --passphrase-fd 0 \
    -o "$TMP.verify" "$DEST"
keytool -list -keystore "$TMP.verify" -storepass "$KS_PASS" -alias "$ALIAS" >/dev/null

echo
echo "OK: encrypted upload keystore written to $DEST"
echo "Back BOTH secrets up offline now (password manager / paper):"
echo "  - keystore password (just entered)"
echo "  - gpg passphrase (just entered)"
echo "  - a copy of $DEST itself"
echo "With Play App Signing a lost upload key is recoverable via a Console"
echo "key reset, but that support flow costs days — a backup is cheaper."
