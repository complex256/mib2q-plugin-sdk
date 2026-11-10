> **Internal engineering record.** This page is kept for history and may be out of date. See [the internal docs index](README.md).

# Bench end-to-end: the Plugins ring (mibsdk 0.8.0, api 8)

Run 2026-11-09 on the bench MHI2Q (10.20.0.23, `HMI_AU_MIB2_HQC_NAR_G22_C10_180803`), from the working tree
that shipped as mibsdk 0.8.0 (`0.8.0+bench.6` on the unit). mibpkg 0.11.4 wrote the package catalog. Frames
are `/tmp/bench-run/*.jpg` keypanel captures (512×240).

**Overall: pass.** The Plugins page draws as the car's own 3D ring, every item opens its list, BACK returns to
the ring on the item you left, and BACK/MENU leave it. No `ERROR` line and no ring fallback in the log.

## What was checked

| # | Check | Result |
|---|---|---|
| R1 | MENU → Plugins: the page is the stock 3D wizard, not a list | **pass.** The five items with the atlas glyphs: Installed (wrench), Updates (service wrench with rings), Available (Audi flower), Networking (globe), Settings (gears), labels from the factory's `getText` |
| R2 | Knob rotation | **pass.** Focus and bracket move item to item; the highlighted cell comes from the atlas's second row |
| R3 | OK on Installed | **pass.** The plugin list (Bike Rack, MQTT, Crashy, Hello Menu, Hello, Inputs Demo …) |
| R4 | BACK from a list | **pass.** The ring draws again — a second wizard build after the first one's `disconnect()`, which clears the renderer's JVM-wide Kanzi node statics; it reloads them cleanly |
| R5 | OK on Updates | **pass.** `carmqtt 0.2.2 -> 0.3.3`, `hello-menu 1.4.0 -> 1.4.3`, `inputs-demo 1.0.0 -> 1.0.3` from the live catalog |
| R6 | OK on Networking, then BACK | **pass.** The Internet page, then the ring with **Networking** focused |
| R7 | OK on Settings, then BACK | **pass.** The Settings page (API 8, adapter, Internet, safe mode, boot guard, status bar, dwell), then the ring with **Settings** focused |
| R8 | BACK on the ring | **pass.** Screen 46, Plugins focused |
| R9 | MENU on the ring | **pass.** Screen 46 |

## Findings

| # | Finding | Status |
|---|---|---|
| R-a | MENU did nothing on the ring: `ScreenWidgetEVO` has no menu to consume BACK, and MENU is an app-change hard key that only `doesHKFilterContainsKey` sees. Fixed with a `RingScreenWidget` subclass (MENU filter, BACK consumed → `backPressed`) | **fixed and re-checked (R8, R9)** |
| R-b | The ring always opened on Installed. The stock wizard restores its focus from the model it writes on every turn, so ring rows now carry the atlas cell as their **widget id** (which also picks the glyph) and the wizard's own restore path brings the focus back | **fixed and re-checked (R6, R7)** |
| R-c | A ring item whose glyph is a plugin's own PNG shows the fallback cell (the Audi flower): the wizard draws atlas cells only. Documented in `StockIcons` and the ring guide | by design |

## Notes

- The ring screen is id 609504; it uses its own `ChoiceModel` (`MODEL_BASE + 42`), never model 138, so the
  main menu's last-application state is untouched.
- The icon/decorator mapping is set on the ring's own `MainWizardController` instance (21 entries, including the
  unused wrench cell 16); the layout's shared array is not modified, so the stock ring keeps its own.
- A third-party `PageSpec.ring` page was not on the bench: the SDK's own Plugins page uses the same path, and
  the API-level rules are covered by `PageSpecTest`.
