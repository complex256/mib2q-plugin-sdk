# Spike A (Plan 2, Task 1): Car drawer rows, labels, presses, popup-65 status line

A throwaway bench spike. It is not part of the distribution. The results are in
`docs/research/2026-10-20-hmi-ui-injection.md` §8.

It needs a unit that already runs the SDK, because it logs through `SdkGlobals.LOG` into `/tmp/mibsdk.log`.

```
./gradlew :spikes:hmi-ui:carJar           # v48, linkCheck -> build/libs/mibsdk-spike-ui.jar
spikes/hmi-ui/run.sh install              # jar to lsd/jars/, HMICar= -> mibsdk.spike.ui.SpikeCarActivator
installer/mibsdk-dev restart              # 4-5 min; `ssh rpi-bench keypanel up` if the unit gets no lease
spikes/hmi-ui/run.sh log | grep spike.ui
spikes/hmi-ui/run.sh uninstall && installer/mibsdk-dev restart
```

- `SpikeCarActivator` mirrors the stock Car activator, with `SpikeBank` (11 models at 602738..602748) and
  `SpikeFactory` (9 ACTION rows appended to drawer 600043, texts 609000..609008).
- `SpikeProbes` runs its sequence 120 s after the activator is constructed:
  - T+0: row 1 is shown on even rounds and hidden on odd rounds.
  - T+10: row 0 gets new text.
  - T+20..100: popup 65 shows strings of 10/15/20/25/30 chars.
  - T+120: one `RunnableEvent` that throws.
  - T+140: popup 65 is hidden.
- `ssh ... touch /tmp/mibsdk-spike-ui.rerun` runs the sequence again without a restart, so you can watch it with
  the drawer open.
- `run.sh` is self-contained: it keeps its own copy of the push conventions of the pre-mibpkg
  `installer/mibsdk-dev` (which now delegates every unit write to mibpkg) and sources nothing. The stock bundles file uses CRLF line endings, and `run.sh` keeps the CR.

## Spike B (Plan 2, Task 2): SDK pages

Same install/uninstall. Drawer row 0 shows `SpikePage` with `FwHMI.showScreen` (S2a), row 1 fires Car event 600023 and
`SpikeFactory.createScreen` returns the page for the next Car screen (S2b), row 2 only logs (re-press check) and has no bitmaps (S1f: does a row without an icon render?). The
probe sequence no longer runs by itself; `SpikeProbes` polls command files `/tmp/mibsdk-spike-ui.<cmd>` every 100 ms:
`rerun`, `row1-show`, `row1-hide`, `back-reshow`, `back-event`, `back-stock`, `reset-sel-on`, `reset-sel-off`,
`nolabel-on`, and `hijack` (the file holds an event id).

## Spike C: row icons, SDK status popup, glyphs, main menu

Plan: `docs/research/2026-10-24-main-menu-and-icons.md` §8. `run.sh` now works on a unit whose SDK was installed by
mibpkg (there is no `mibsdk/stock/` any more): it expects `HMICar=mibsdk.adapter.hqc.ui.SdkCarActivator` and the stock
`HMISystem=` line, saves a byte copy of the live bundles file to `/mnt/persist/mibsdk-spike/bundles.before`, and
`uninstall` writes that copy back (`cmp`-checked).
After the main-menu feature (`2026-10-24-menu-and-icons`), `run.sh install` refuses by design on a unit with the SDK
installed, because the SDK package now owns `HMISystem=` and the line is no longer stock.

```
./gradlew :spikes:hmi-ui:carJar && python3 -I spikes/hmi-ui/icons/make_icons.py
spikes/hmi-ui/run.sh install --car-only   # Boot A: HMICar= only (drawer icons, popup 609600, glyphs, logo)
spikes/hmi-ui/run.sh add-system           # Boot B on top of A: HMISystem= -> SpikeSystemActivator (main menu)
spikes/hmi-ui/run.sh install              # or both lines at once
spikes/hmi-ui/run.sh status
spikes/hmi-ui/run.sh uninstall && installer/mibsdk-dev restart
```

- Car side (`SpikeFactory`): drawer row 0 has bitmaps `{609100, 609101}`, served by `getImagePath` from
  `lsd/mibsdk-spike/icons/` (I1); row 1 has System stock `{422, 423}` (I2); row 2 none. `getPartialPopupStubs` adds
  popup 609600 (model 602751), built by `SpikePopup`: backdrop, icons `{609110}`, `{609111}`, one on the
  `ResourceLocatorModel` 602752, and a label on 602753.
- System side (`SpikeSystemActivator`, `SpikeSystemFactory`, `SpikeWizardKeyHandler`): appends rows "SDK w10",
  "SDK w11 wrench", "SDK w12", "SDK w14" (widgetIDs 10, 11, 12, 14) to the main-menu ring (screen 46); SELECT opens
  the spike page. Stock factory in `RUN_MODE=swdl` and on any `Throwable`. Popup 65 gets two icons only when
  `/tmp/mibsdk-spike-ui.p65icons` exists when it is built.
- Command files `/tmp/mibsdk-spike-ui.<cmd>`: `pop-on`, `pop-off`, `pop-file`, `icon-next`, `glyphs1`, `glyphs2`,
  `glyph-off`, `logo-on`, `logo-off`; Boot B: `wrench` (atlas mapping of widgetID 11 := `{16, 9}`), `wz-hide`,
  `wz-show`, `wz-label`.
- Emergency revert (blank HMI): `mount -uw /mnt/app; cp /mnt/persist/mibsdk-spike/bundles.before
  /mnt/app/eso/hmi/lsd/bundles_dsitracer_enabled.properties; chmod 777 …; sync; mount -ur /mnt/app; slay -s11 j9`.

## Spike D: native input widgets on SDK pages

Plan and results: `docs/research/2026-10-25-native-inputs.md` §6 and "Spike D results". Boot A path only, on a unit
whose SDK owns both `HMICar=` and `HMISystem=` (mibpkg takeover): `install --car-only` now swaps `HMICar=` only and
keeps the live `HMISystem=` line byte for byte, whatever it is. The backup is `/mnt/persist/mibsdk-spike/bundles.before-d`
(spike C's `bundles.before` is never reused), and `uninstall` refuses unless the live file with the spike line put back
equals that backup byte for byte (host `cmp`). Do not use the MENU ring's Plugins row while the spike is installed.

```
./gradlew :spikes:hmi-ui:carJar && python3 -I spikes/hmi-ui/icons/make_icons.py
spikes/hmi-ui/run.sh install --car-only
installer/mibsdk-dev restart
ssh ... touch /tmp/mibsdk-spike-ui.page          # or Car -> drawer row 0
spikes/hmi-ui/run.sh uninstall && installer/mibsdk-dev restart
```

- `SpikePage` list (`ListModel` 602749, 5 columns: text, int value, kind, enabled, value text) with
  `setRecordSetColumn(2)` and `setEnabledColumn(3)`: Action A/B, Checkbox 1-3, Checkbox off (disabled), Radio A-C,
  Progress (60), Language/English (value column), Icon row. Kinds 0 action, 1 checkbox, 2 radio, 3 progress, 4 value,
  5 icon. After the list, three typed rows: type 8 on ChoiceModel 602754, type 64 combo on ChoiceModel 602755, type 128
  on RangeModel 602756 (SELECT opens the slider screen).
- `SpikeScreens`: slider 609501 (RotaryController + RotaryBigRendererHigh on 602756), confirm 609502
  (InstructionTextContoller + Yes/No list on 602758), speller 609503 (TouchController + inline SpellerController on
  SpellerModel 602759). BACK on each re-shows the page.
- `SpikeInputs`: the models and their logging listeners (each sets the value itself).
- Popup 609600 now uses slot 7 (the SDK's), so the toast and the SDK status line (popup 65) show together.
- Command files `/tmp/mibsdk-spike-ui.<cmd>`: `page`, `cb-set`, `cb-clear`, `prog-next`, `kind-swap`, `rs-off`/`rs-on`,
  `typed-off`/`typed-on`, `label-on`, `slider`, `confirm`, `speller`, `slider-fwd`, `slider-focus`, `range-set` (file
  holds the value), `toast` ("Saved" for 3 s), `dump`.
- Emergency revert (blank HMI): as for spike C, but from `bundles.before-d`.
