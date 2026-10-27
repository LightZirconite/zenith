#!/usr/bin/env bash
# Our Lufop base lives on the public "data" release, encrypted (AES-256, key in secret LUFOP_DB_KEY).
#   lufop_db.sh fetch   <lufop.json>   download + decrypt; nothing if the release has no base yet
#   lufop_db.sh publish <lufop.json>   encrypt + upload, then drop any old plaintext copy
# Needs GH_TOKEN and LUFOP_DB_KEY. A base that exists must be read: any failure stops the job,
# otherwise the harvest would restart from zero and overwrite the accumulated base.
set -euo pipefail

mode=$1 path=$2
enc=lufop.json.enc
crypt() { openssl enc -aes-256-cbc -pbkdf2 -iter 200000 -md sha256 -salt -pass env:LUFOP_DB_KEY "$@"; }
need_key() { [ -n "${LUFOP_DB_KEY:-}" ] || { echo "::error::secret LUFOP_DB_KEY manquant"; exit 1; }; }

assets=$(gh release view data --json assets -q '.assets[].name' 2>/dev/null || true)
has() { grep -qx "$1" <<< "$assets"; }
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

case $mode in
  fetch)
    if has "$enc"; then
      need_key
      gh release download data -p "$enc" -D "$tmp"
      crypt -d -in "$tmp/$enc" -out "$path"
    elif has lufop.json; then
      # Migration from the former plaintext base.
      gh release download data -p lufop.json -D "$tmp"
      mv "$tmp/lufop.json" "$path"
    else
      echo "pas encore de base Lufop"
    fi
    ;;
  publish)
    need_key
    [ -f "$path" ] || { echo "pas de base Lufop à publier"; exit 0; }
    crypt -in "$path" -out "$tmp/$enc"
    gh release upload data "$tmp/$enc" --clobber
    if has lufop.json; then gh release delete-asset data lufop.json -y; fi
    ;;
  *)
    echo "usage: $0 fetch|publish <lufop.json>" >&2; exit 2 ;;
esac
