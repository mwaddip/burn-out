#!/usr/bin/env bash
# Sync Gradle app/src/ into patches/05-burnout-app/ for the Soong build.
# Excludes AndroidManifest.xml (Soong variant adds sharedUserId="android.uid.system";
# kept hand-maintained at patches/05-burnout-app/app/AndroidManifest.xml).
#
# Run before regenerating release artifacts or after changing app/src/main/.
# Prints a summary of changed files; idempotent.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="$SCRIPT_DIR/../app/src/main"
DST="$SCRIPT_DIR/../patches/05-burnout-app/app/src/main"

if [[ ! -d "$SRC" ]]; then
    echo "ERROR: $SRC not found" >&2
    exit 1
fi

mkdir -p "$DST"

# rsync everything except the manifest. --delete keeps the mirror clean of removed files.
rsync -a --delete \
    --exclude AndroidManifest.xml \
    --itemize-changes \
    "$SRC/" "$DST/"

echo "=== Sync complete ==="
echo "Soong-side manifest at: $SCRIPT_DIR/../patches/05-burnout-app/app/AndroidManifest.xml"
echo "(hand-maintained — keep aligned with $SRC/AndroidManifest.xml plus sharedUserId)"
