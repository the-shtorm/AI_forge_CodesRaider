#!/usr/bin/env bash
set -euo pipefail

if (( $# != 1 )); then
    printf 'Usage: bash import-catalog.sh <path-to-catalog.xml>\n' >&2
    exit 2
fi

cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
catalog=$1
if [[ $catalog != /* ]]; then
    catalog="$PWD/$catalog"
fi
if [[ ! -f $catalog || ! -r $catalog ]]; then
    printf 'Cannot read the catalog file: %s\n' "$catalog" >&2
    exit 1
fi

compose=(docker compose --profile ml)
"${compose[@]}" up -d --build --wait model
"${compose[@]}" run --rm --build --no-deps --volume "$catalog:/catalog.xml:ro" app import --embeddings
"${compose[@]}" up -d --no-deps --force-recreate --wait app
