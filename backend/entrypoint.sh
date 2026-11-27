#!/bin/sh
set -eu

if [ "$#" -eq 0 ] && [ "${SEED_DEMO_CATALOG:-false}" = "true" ] &&
   [ ! -e "${SEARCH_SNAPSHOTS_DIR:-data/snapshots}/active" ]; then
    CATALOG_PATH=/app/demo.xml java -jar /app/app.jar import
fi

exec java -jar /app/app.jar "$@"
