# burn-out

**Public ROM-adoptable OLED burn-in correction.**

Software per-pixel correction shader, packaged as a LineageOS-adjacent project. ROM developers apply the patches to their tree, rebuild, and ship a ROM with burn-in correction working out of the box. End users mark + tune their burn-in via a bundled system app, no root required.

**Status: Phase 1 — Mechanical Rename Port (OP12-targeted).**

Phase 1 ports the OP12-personal burn-in implementation under the new `burnout` naming. Multi-device generalization, the system app, the desktop editor, and the shapes editing are all Phases 2+.

See `docs/superpowers/specs/2026-04-28-burnout-mvp-design.md` (in the predecessor OP12 repo) for the full architectural design.

## Quickstart (Phase 1, OP12-only)

1. `git clone <this repo> ~/projects/burn-out`
2. `cd ~/android/lineage` (your LineageOS source tree)
3. `bash ~/projects/burn-out/scripts/apply-patches.sh`
4. Standard LineageOS build: `source build/envsetup.sh && lunch lineage_<device>-bp2a-user && mka bacon`

Currently targets OnePlus 12 (`waffle`). Adaptation for other devices is Phase 2 work.
