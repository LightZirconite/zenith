#!/usr/bin/env bash
# One-time: creates the release signing key and stores it in the GitHub repo secrets.
# Keep the generated keystore backup: losing it means reinstalling the app from scratch.
set -euo pipefail

repo="LightZirconite/zenith"
dir="${ZENITH_KEY_DIR:-$HOME/.zenith}"
keystore="$dir/zenith-release.jks"
alias="zenith"

if [ -e "$keystore" ]; then
  echo "La clé existe déjà : $keystore (rien n'est écrasé)." >&2
  exit 1
fi
command -v keytool >/dev/null || { echo "keytool introuvable (installe un JDK ou mets-le dans le PATH)." >&2; exit 1; }

mkdir -p "$dir" && chmod 700 "$dir"
password="$(head -c 32 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 32)"

keytool -genkeypair -keystore "$keystore" -storetype PKCS12 -alias "$alias" \
  -keyalg RSA -keysize 4096 -validity 36500 -dname "CN=Zenith" \
  -storepass "$password" -keypass "$password" >/dev/null
chmod 600 "$keystore"
printf '%s\n' "$password" > "$dir/zenith-release.password"
chmod 600 "$dir/zenith-release.password"

base64 -w0 "$keystore" | gh secret set ZENITH_KEYSTORE_BASE64 -R "$repo"
printf '%s' "$password" | gh secret set ZENITH_KEYSTORE_PASSWORD -R "$repo"
printf '%s' "$password" | gh secret set ZENITH_KEY_PASSWORD -R "$repo"
printf '%s' "$alias" | gh secret set ZENITH_KEY_ALIAS -R "$repo"

echo "Clé créée dans $dir et secrets GitHub posés. Sauvegarde ce dossier."
