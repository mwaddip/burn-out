#!/usr/bin/env bash
# Apply burn-out patches to a LineageOS source tree.
# Idempotent: safe to re-run; will skip already-applied patches.

set -euo pipefail

LINEAGE=${LINEAGE:-~/android/lineage}
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PATCHES="$SCRIPT_DIR/../patches"

if [[ ! -d "$LINEAGE" ]]; then
    echo "ERROR: $LINEAGE not found (set LINEAGE env var if elsewhere)" >&2
    exit 1
fi

apply_patch() {
    local repo="$1"
    local patch="$2"
    local label="$3"

    cd "$LINEAGE/$repo"

    if git apply --check --reverse "$PATCHES/$patch" 2>/dev/null; then
        echo "[skip] $label - already applied"
        return 0
    fi

    if git apply --check "$PATCHES/$patch" 2>/dev/null; then
        git apply "$PATCHES/$patch"
        echo "[ok]   $label - applied"
    else
        echo "[FAIL] $label - cannot apply (conflict?)" >&2
        echo "       Run manually: cd $LINEAGE/$repo && git apply $PATCHES/$patch" >&2
        return 1
    fi
}

echo "=== Applying burn-out patches ==="
apply_patch "frameworks/native"       "01-burnout-renderengine.patch"  "BurnoutFilter + RenderEngine hook + HWC bypass"
apply_patch "system/sepolicy"         "02-burnout-sepolicy.patch"      "Sepolicy: burnout_data_file + burnout_prop"
apply_patch "frameworks/base"         "03-burnout-qstile.patch"        "QS tile: Burn-out Fix"
apply_patch "vendor/lineage"          "04-burnout-bake-in.patch"       "Multi-device bake-in template at vendor/lineage/burnout/burnout.mk"

# Copy factory burnout assets into the bake-in template's factory/ subdir so PRODUCT_COPY_FILES finds them.
BURNOUT_DEST="$LINEAGE/vendor/lineage/burnout/factory"
mkdir -p "$BURNOUT_DEST"
cp "$PATCHES/factory-burnout/mask.png" "$BURNOUT_DEST/mask.png"
cp "$PATCHES/factory-burnout/zones.json" "$BURNOUT_DEST/zones.json"
echo "[ok]   Factory assets - copied to $BURNOUT_DEST/"

# Copy 05-burnout-app/ tree into vendor/lineage/burnout/app/ (Soong build module).
# Applied via rsync, NOT git apply: this is a fresh-tree-only addition with no
# upstream files to merge against, and the source tree carries 25+ files.
APP_SRC="$PATCHES/05-burnout-app/app"
APP_DEST="$LINEAGE/vendor/lineage/burnout/app"
if [[ -d "$APP_SRC" ]]; then
    mkdir -p "$APP_DEST"
    rsync -a --delete "$APP_SRC/" "$APP_DEST/"
    echo "[ok]   Soong app mirror - copied to $APP_DEST/"
else
    echo "[warn] $APP_SRC not found; Soong build module not installed" >&2
fi

echo "=== All patches applied ==="
