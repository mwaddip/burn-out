#!/usr/bin/env bash
# Verify patches/05-burnout-app/app/src/main/ is in sync with app/src/main/.
# The two trees must match except for AndroidManifest.xml: the Soong build needs
# sharedUserId="android.uid.system" (which requires platform signing), while the
# Gradle debug APK can't carry that. Both manifests are hand-maintained; everything
# else is mirrored via scripts/sync-app-mirror.sh.
#
# Exits 0 if synced, 1 if divergent (with hint), 2 on missing input dirs.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$SCRIPT_DIR/.."
GRADLE_SRC="$ROOT/app/src/main"
SOONG_SRC="$ROOT/patches/05-burnout-app/app/src/main"

if [[ ! -d "$GRADLE_SRC" ]]; then
    echo "ERROR: $GRADLE_SRC not found" >&2
    exit 2
fi
if [[ ! -d "$SOONG_SRC" ]]; then
    echo "ERROR: $SOONG_SRC not found" >&2
    exit 2
fi

if diff -r --brief --exclude=AndroidManifest.xml "$GRADLE_SRC" "$SOONG_SRC"; then
    echo "OK: app/src/main/ and patches/05-burnout-app/app/src/main/ are in sync."
    exit 0
fi

echo "" >&2
echo "FAIL: Soong source mirror has diverged from Gradle source." >&2
echo "      Run: bash scripts/sync-app-mirror.sh" >&2
echo "      then commit the result." >&2
exit 1
