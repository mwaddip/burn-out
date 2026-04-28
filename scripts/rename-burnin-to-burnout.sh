#!/usr/bin/env bash
# Mechanically rename burnin → burnout in a file (in place) or stream.
#
# Substitutions, applied in order to avoid clobbering:
#   1. Burnin  → Burnout    (PascalCase identifiers: BurninFilter, BurninTile, etc.)
#   2. BURNIN  → BURNOUT    (uppercase constants: BURNIN_TILE_SPEC)
#   3. burnin  → burnout    (lowercase: paths, props, sepolicy types, identifiers)
#   4. Burn-in → Burn-out   (capitalized hyphenated display strings: "Burn-in Fix")
#   5. burn-in → burn-out   (lowercase hyphenated)
#
# Substitutions are case-sensitive. The lowercase 'burnin' substitution is safe
# for the OP12 patch set because no false-positive contexts (e.g. 'burning',
# 'burninfo') appear — verified 2026-04-28 via:
#   grep -ohE 'burnin.' patches/05-08-*.patch | sort -u
# All trailing characters were _ - . / " ( ) , space, or 'T' (which only ever
# preceded "Tile" or similar identifiers that get the case-sensitive Burnin sweep).
#
# Usage:
#   rename-burnin-to-burnout.sh <file>            # in-place sed
#   rename-burnin-to-burnout.sh < input > output  # stream mode

set -euo pipefail

SED_SCRIPT='
    s|Burnin|Burnout|g
    s|BURNIN|BURNOUT|g
    s|burnin|burnout|g
    s|Burn-in|Burn-out|g
    s|burn-in|burn-out|g
'

if [[ $# -eq 0 ]]; then
    sed -E "$SED_SCRIPT"
elif [[ -f "$1" ]]; then
    sed -i -E "$SED_SCRIPT" "$1"
else
    echo "ERROR: not a file: $1" >&2
    exit 1
fi
