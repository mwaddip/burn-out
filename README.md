# burn-out

**Public ROM-adoptable OLED burn-in correction.**

Software per-pixel correction shader, packaged as a LineageOS-adjacent project. ROM developers apply the patches to their tree, rebuild, and ship a ROM with burn-in correction working out of the box. End users mark + tune their burn-in via a bundled system app — no root required.

## What's shipping

- AOSP patches: `BurnoutFilter` SkSL post-pass on `SkiaRenderEngine` (up to 12 zones, per-channel dim), sepolicy, Quick Settings tile, multi-device bake-in template at `vendor/lineage/burnout/`.
- Bundled platform-signed system app (`Burn-out`, package `app.burnout`) at `/system/app/Burnout/`, with on-device Mark + Calibrate UIs.
- Desktop pixel editor at `desktop-editor/editor.py` (PyQt5) for power-user mask tuning. Connects to the on-device Calibrate via `adb forward localabstract:burnout-mask`. Drag-shape primitives: rect, circle, triangle (8 orientations).

## What's next

- Pre-built desktop-editor bundles (`.dmg` / `.exe` / `.AppImage` via PyInstaller) so users don't need `pip install PyQt5`.
- End-to-end ROM validation on a non-OP12 device.
- USER-GUIDE / XDA forum post for end-user onboarding.

## ROM developer quickstart

See **[docs/ROM-DEV-GUIDE.md](docs/ROM-DEV-GUIDE.md)** for the integration guide.

In short: apply four patches + the `patches/05-burnout-app/` source mirror via `scripts/apply-patches.sh`, set `BURNOUT_FB_*` framebuffer dims and `include vendor/lineage/burnout/burnout.mk` in your device's `device.mk`, then build normally.

## Repo layout

- `app/` — Gradle source tree for the Burn-out system app (development environment)
- `desktop-editor/` — PyQt5 desktop pixel editor (`editor.py`, `shapes.py`, tests, README)
- `patches/01-04*.patch` — AOSP patches against `frameworks/native`, `system/sepolicy`, `frameworks/base`, `vendor/lineage`
- `patches/05-burnout-app/` — Soong-side mirror of `app/src/main/` (kept in sync via `scripts/sync-app-mirror.sh`) plus the Soong `Android.bp` and platform-signing manifest
- `patches/factory-burnout/` — bundled blank factory assets (1440x3216 mask + empty zones config)
- `scripts/` — `apply-patches.sh`, `sync-app-mirror.sh`, `check-mirror.sh`, `generate-blank-burnout-assets.sh`
- `docs/ROM-DEV-GUIDE.md` — ROM developer integration guide
