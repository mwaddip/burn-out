#!/usr/bin/env bash
# Local Linux build helper for the burn-out desktop editor PyInstaller bundle.
# Run from anywhere; resolves to repo root.
#
# Output:
#   - dist/burnout-editor (single onefile executable)
#   - burnout-editor-linux-<arch>.tar.gz at repo root
#
# Prereq: pip install -r desktop-editor/requirements-dev.txt

set -euo pipefail

# Resolve repo root from this script's location
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."

if ! python3 -c "import PyInstaller" 2>/dev/null; then
    echo "Error: PyInstaller not installed."
    echo "Run: pip install -r desktop-editor/requirements-dev.txt"
    exit 1
fi

rm -rf build/ dist/ burnout-editor.spec

python3 -m PyInstaller \
    --noconfirm \
    --onefile \
    --name burnout-editor \
    desktop-editor/editor.py

if [ ! -x dist/burnout-editor ]; then
    echo "Error: PyInstaller did not produce dist/burnout-editor"
    exit 1
fi

ARCH="$(uname -m)"
TARBALL="burnout-editor-linux-${ARCH}.tar.gz"
tar czf "$TARBALL" -C dist burnout-editor

echo
echo "Bundle built: $TARBALL"
echo "Smoke-test: ./dist/burnout-editor (should launch the GUI)"
