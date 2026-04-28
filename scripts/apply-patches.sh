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
apply_patch "device/oneplus/waffle"   "04-burnout-bake-in.patch"       "Bake-in factory burnout assets (Phase 1: OP12-specific)"

# Copy factory burnout assets into the device tree so PRODUCT_COPY_FILES (patch 04) finds them.
BURNOUT_DEST="$LINEAGE/device/oneplus/waffle/burnout"
mkdir -p "$BURNOUT_DEST"
cp "$PATCHES/factory-burnout/mask.png" "$BURNOUT_DEST/mask.png"
cp "$PATCHES/factory-burnout/zones.json" "$BURNOUT_DEST/zones.json"
echo "[ok]   Factory assets - copied to $BURNOUT_DEST/"

echo "=== All patches applied ==="
