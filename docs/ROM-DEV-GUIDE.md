# ROM Developer Guide

How to integrate burn-out into your LineageOS-based ROM.

## What this gets you

After applying burn-out's patches and rebuilding your ROM:

- A `BurnoutFilter` post-pass on `SkiaRenderEngine` that runs per-frame and dims per-pixel/per-zone to compensate for OLED burn-in. Up to 12 zones, per-channel dim. Single-pass shader; no measurable framerate impact.
- A bundled platform-signed system app (`Burn-out`, package `app.burnout`) at `/system/app/Burnout/`. Runs as the `system` UID via `sharedUserId="android.uid.system"` and writes directly to `/data/burnout/`. Gives end users on-device Mark + Calibrate UIs — no Magisk, no root prompt.
- A Quick Settings tile (`Burn-out Fix`) that toggles `persist.sys.burnout.enabled`.
- Sepolicy: `burnout_data_file`, `burnout_prop`, the operative `burnout_app` domain with `seapp_contexts` mapping, and the private `burnout_app_data_file` type for `/data/data/app.burnout/`.
- Factory bake-in: blank default mask + empty zones config at `/system/etc/burnout/`. Users see no correction effect until they paint a mask via the Burn-out app.

## Adoption steps

1. Clone burn-out:
   ```
   git clone <burn-out repo> ~/projects/burn-out
   ```

2. From the LineageOS source tree root:
   ```
   cd ~/android/lineage
   bash ~/projects/burn-out/scripts/apply-patches.sh
   ```
   This applies four patches (BurnoutFilter, sepolicy, QS tile, multi-device bake-in template), rsyncs the Burn-out system app source tree from `patches/05-burnout-app/` into `vendor/lineage/burnout/app/`, and copies the bundled blank factory assets to `vendor/lineage/burnout/factory/`.

3. Edit your device's `device.mk` to include the bake-in template:
   ```makefile
   # Burn-out compensation
   BURNOUT_FB_WIDTH  := <your framebuffer width>
   BURNOUT_FB_HEIGHT := <your framebuffer height>
   include vendor/lineage/burnout/burnout.mk
   ```
   The bundled factory assets are sized 1440x3216 (OnePlus 12). If your framebuffer is different, regenerate before building:
   ```
   bash ~/projects/burn-out/scripts/generate-blank-burnout-assets.sh \
     <width> <height> ~/android/lineage/vendor/lineage/burnout/factory/
   ```

4. Build the ROM normally (`mka bacon` or your usual flow). The bake-in `include` adds `Burnout` to `PRODUCT_PACKAGES`, so the system app gets compiled by Soong and bundled into `/system/app/Burnout/` automatically. After flashing, the Burn-out app appears in the launcher and the Burn-out Fix tile is in the Quick Settings Edit picker.

## End-user tooling (not part of the ROM)

The bundled Burn-out system app (Mark + Calibrate) is the primary end-user UX. For power users who want pixel-precision mask edits from a laptop, the project includes a separate desktop editor at `desktop-editor/editor.py` (PyQt5). It connects to the on-device Calibrate via `adb forward tcp:<port> localabstract:burnout-mask` and supports drag-shape primitives (rect, circle, triangle in 8 orientations). See `desktop-editor/README.md` for setup.

ROM devs don't need to ship the editor — it's a user-installed laptop tool, separate from the ROM.

## What's not yet shipped

- Pre-built `.dmg` / `.exe` / `.AppImage` bundles of the desktop editor. Currently distributed as Python source (`pip install -r desktop-editor/requirements.txt`).

## Verification

After a clean ROM build + flash:

- `getprop persist.sys.burnout.enabled` returns empty (default-on; only explicit "0" disables).
- `ls /system/etc/burnout/` shows `mask.png` + `zones.json`.
- `ls /system/app/Burnout/` shows `Burnout.apk` (~7.5 MB, platform-signed).
- The Burn-out app appears in the launcher.
- Quick Settings → Edit → "Burn-out Fix" tile is available. Tapping it flips `persist.sys.burnout.enabled`; logcat shows `BurnoutFilter` lines on each transition.
- Paint a few marks via Mark mode → return → open Calibrate → tap Save. Logcat shows `commit direct: wrote /data/burnout/{mask.png,zones.json}` (the `burnout_app`-domain direct-IO path; if it falls back to su, Save will fail on a non-Magisk ROM).
- AVC denials for `burnout_data_file` / `burnout_prop` / `burnout_app` should NOT appear in `dmesg | grep avc`. If they do, sepolicy is misapplied.
