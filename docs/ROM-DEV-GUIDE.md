# ROM Developer Guide

How to integrate burn-out into your LineageOS-based ROM.

## What this gets you

After applying burn-out's patches and rebuilding your ROM:

- A `BurnoutFilter` post-pass on `SkiaRenderEngine` that runs per-frame and can dim per-pixel/per-zone areas to compensate for OLED burn-in.
- A Quick Settings tile (`Burn-out Fix`) that toggles `persist.sys.burnout.enabled`.
- Sepolicy types (`burnout_data_file`, `burnout_prop`, `burnout_app` stub) and rules.
- Factory bake-in: blank default mask + empty zones config at `/system/etc/burnout/`. Users see no correction effect until they paint a mask (Phase 3 system app, or manually pushed via tooling).

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
   This applies four patches and copies the bundled blank factory assets to `vendor/lineage/burnout/factory/`.

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

4. Build the ROM normally (`mka bacon` or your usual flow). The Burn-out QS tile will appear in the Edit picker.

## What's not yet shipped (as of Phase 2)

- The Burn-out system app (Mark + Calibrate modes). Phase 3+. End users have no on-device way to paint or tune their mask yet.
- The desktop editor. Phase 7+.
- The `burnout_app` SELinux domain has a type stub but no `seapp_contexts` mapping. Phase 3+.

## Verification

After a clean ROM build + flash:

- `getprop persist.sys.burnout.enabled` returns empty (default-on; only explicit "0" disables).
- `ls /system/etc/burnout/` shows `mask.png` + `zones.json`.
- Quick Settings → Edit → "Burn-out Fix" tile is available.
- Tapping the tile flips the prop. Logcat shows `BurnoutFilter` lines on each transition.
- AVC denials for `burnout_data_file` / `burnout_prop` should NOT appear in `dmesg | grep avc`. If they do, sepolicy is misapplied.
