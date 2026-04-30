# Burn-out Mask Editor

Laptop-side pixel-level editor for fine-tuning the on-device burn-in mask.

Connects to the Burn-out app's Calibrate activity over `adb forward` (TCP → Unix abstract socket on phone).

## Setup

End-user (just want to run the editor from source):

```
pip install -r requirements.txt
```

Developer (need PyInstaller for local bundle builds):

```
pip install -r requirements-dev.txt
```

PyQt5 may also be installable from your distro's package manager (`apt install python3-pyqt5` on Debian/Ubuntu, `brew install pyqt@5` on macOS).

## Releases

Pre-built bundles are attached to each tagged release on the [GitHub Releases page](https://github.com/REPLACE-WITH-REPO/releases) (URL fixed up at first push):

| OS | Asset | Notes |
|---|---|---|
| macOS | `burnout-editor-macos.dmg` | First-launch Gatekeeper warning expected (unsigned). Right-click → Open to bypass. |
| Windows | `burnout-editor-windows.exe` | First-launch SmartScreen warning expected (unsigned). Click "More info" → "Run anyway". |
| Linux | `burnout-editor-linux-x86_64.tar.gz` | Untar, `chmod +x burnout-editor`, run. |

Bundles are produced by `.github/workflows/editor-bundles.yml` on tag push. Code-signing is deferred — see spec §9.5 (or upcoming USER-GUIDE.md) for the full rationale.

To build locally on Linux: `bash scripts/build-editor-bundle.sh` from the repo root.

## Usage

1. On the phone, open the Burn-out app. Paint at least one mark via **Mark** mode (if no zones exist yet), then switch to **Calibrate**.
2. Set up the adb-forward connection from your laptop:
   ```
   adb forward tcp:8765 localabstract:burnout-mask
   ```
   The forward gets dropped on `am force-stop` (so anytime Calibrate is killed/relaunched). Re-run if connection fails.
3. Launch the editor:
   ```
   python3 editor.py
   ```

The zone list on the left shows all detected zones with their channel mask + bbox. Click a zone to load its crop into the editor pane on the right.

## Editor controls

| Action | Effect |
|---|---|
| L-click | Set pixel to selected severity (in zone's channel(s)) |
| L-drag | Set rect-fill area to selected severity |
| R-click | Clear pixel (in zone's channel(s)) |
| R-drag | Clear rect-fill area |
| Wheel | Zoom (anchored at cursor) |
| Fit | Reset zoom to fit zone in pane |
| Refresh | Re-fetch mask + zones from phone |
| Save | Commit mask + zones JSON to `/data/burnout/` on phone |

Severity 1-4 selector controls the painted channel value (top-of-bucket encoding, matches Mark mode):

| Severity | Pixel value |
|---|---|
| 1 | 63 |
| 2 | 127 |
| 3 | 191 |
| 4 | 255 |

The editor pushes one pixel-edit per active channel of the selected zone. A G-only zone gets G painted; a multi-channel (R+G) zone gets both painted in lockstep.

## What this editor doesn't do

- **Zone management** (Add / Delete / Split): use the Burn-out app's Calibrate UI on the phone.
- **Zone-level params** (per-zone dim, falloff radius, per-channel global dim): tune via Calibrate's sliders.
