#!/usr/bin/env bash
# Generate blank factory assets for burn-out at a given framebuffer resolution.
#
# Usage: generate-blank-burnout-assets.sh <width> <height> <output_dir>
#
# Output:
#   <output_dir>/mask.png    — RGB PNG, all zeros, <width> x <height>
#   <output_dir>/zones.json  — empty config per spec §8.3 (no zones, globalDim=0)
#
# With globalDim=0 and no zones, the BurnoutFilter post-pass is a no-op even
# when persist.sys.burnout.enabled=1. Users see no effect until they paint a
# mask + Calibrate Save (Phase 3 system app, or push-burnout-config.sh).

set -euo pipefail

if [[ $# -ne 3 ]]; then
    echo "Usage: $0 <width> <height> <output_dir>" >&2
    exit 1
fi

WIDTH=$1
HEIGHT=$2
OUTDIR=$3

if ! [[ "$WIDTH" =~ ^[0-9]+$ ]] || ! [[ "$HEIGHT" =~ ^[0-9]+$ ]]; then
    echo "ERROR: width and height must be positive integers" >&2
    exit 1
fi

mkdir -p "$OUTDIR"

# Generate blank mask via Python's PIL (Pillow). Falls back to ImageMagick if PIL is unavailable.
if python3 -c 'import PIL' 2>/dev/null; then
    python3 - <<PYEOF
from PIL import Image
img = Image.new("RGB", ($WIDTH, $HEIGHT), (0, 0, 0))
img.save("$OUTDIR/mask.png", optimize=True)
PYEOF
elif command -v convert >/dev/null 2>&1; then
    convert -size "${WIDTH}x${HEIGHT}" xc:black "$OUTDIR/mask.png"
else
    echo "ERROR: neither python3+PIL nor ImageMagick (convert) is available" >&2
    echo "       install one: pip install Pillow   OR   apt install imagemagick" >&2
    exit 1
fi

# Generate empty zones.json per spec §8.3.
cat > "$OUTDIR/zones.json" <<JSON
{
  "mask_w": $WIDTH,
  "mask_h": $HEIGHT,
  "falloff_radius_px": 0,
  "global_dim": 0,
  "zones": [],
  "max_wear_per_channel": {"r": 0, "g": 0, "b": 0}
}
JSON

echo "Generated blank burn-out assets at ${WIDTH}x${HEIGHT}:"
ls -la "$OUTDIR/mask.png" "$OUTDIR/zones.json"
