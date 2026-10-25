# Bench end-to-end: main-menu entry, row icons and the status popup (Task 12)

Run 2026-10-24 on the bench MHI2Q (10.20.0.23, `HMI_AU_MIB2_HQC_NAR_G22_C10_180803`). This was the first boot of the
`HMISystem=` takeover as a product (`SdkSystemActivator`), not a spike. Frames are `docs/bench-e2e/menu-icons-*.jpg`
(512×240 keypanel captures) and `*-zoom.png` crops. The R1 log is `docs/bench-e2e/menu-icons-log-r1.txt`.

**Overall: BLOCKED after R2.**
- R1 passed for the main menu, the drawer and the row icons.
- R1 found three defects in status popup 609600 (S1/S3). They are fixed in 5340cd3 and deployed as `mibsdk 0.1.0+bench.4`,
  but not yet seen on screen.
- R2 ran without the forced failure: `mibsdk-dev ui-forcefail` could not write on the unit (fixed in b619202).
- After the R2 restart the unit did not come back. Two `keypanel up` tries after the reset failed: the known bench
  hang, which needs the owner to power-cycle. R2's checks and R3 were not run.

## Findings

| # | Finding | Status |
|---|---------|--------|
| B1 | **Popup labels were right-aligned.** `SdkStatusPopup` set `setAlignment(3, 4)`, and 3 is `ALIGN_RIGHT` in the stock `Alignment` (`LabelRendererHigh.getAlignmentOffsetX`). Each cell's label box runs to the popup's right padding, so every item's text ended at the same x: `TS ? MQ dn` and `Hello` were drawn on top of each other (`menu-icons-status-car-zoom.png`). | **fixed 5340cd3** (`SdkCarScreenFactoryTest.popupLabelsAreLeftAligned` first); deployed as bench.4, **not yet seen on the unit** |
| B2 | **Package PNGs were never drawn in the popup.** File icons were set with `setResourceLocator(-1, path, 1)`. For a locator of status 1, `IconRendererHigh.calculateDisplayData` draws the controller's bitmap 0 and never loads the URI. Spike C used `new HMIResourceLocator(path)` (status 0). hello's `hello-20` PNG never showed (nothing at cell 1's icon x in `menu-icons-status-car-zoom.png`). The host test asserted status 1. | **fixed 5340cd3** (`SdkCarActivatorTest.popupModelsAreCreated` now asserts 0 and failed first); not yet seen on the unit |
| B3 | **A built-in icon change in the popup showed only at the next screen change.** `AbstractWidget.setBitmaps` only stores the array; nothing marks the icon dirty. "Next status icon" → star: nothing (`menu-icons-status-icon-swap-not-live-zoom.png`), no `getImagePath` for the star. A second press → dot: the dot appeared only after MEDIA was pressed (`menu-icons-status-media.jpg`), and the log has its first request then (`1143491 getImagePath 609105 -> ../mibsdk/icons-sdk/41a75fb1f2765d90.png`). The star was never drawn. | **fixed 5340cd3** (`SdkCarScreenFactoryTest.iconChangeOnABuiltPopupMarksTheIconDirty` first: a changed id icon is `setCompositesDirty(true)`); not yet seen on the unit |
| B4 | **`mibsdk-dev ui-forcefail` failed on the unit**: `sh: printf: cannot execute`, then `cannot write /mnt/persist/mibsdk/dev/ui.forcefail`. The restart had been chained after it, so R2 booted with safe mode on and no forced failure. | **fixed b619202** (`UiForceFailTest.test_remote_commands_use_no_printf` first; the write uses `echo`). The screen-46 failure path was **not exercised on the unit** |
| B5 | **The plugin PNG doubles as its own reflection (`{id, id}`), and that reads as a second icon.** The Hello row draws its ring icon and, under it, a full-opacity copy, so it looks like an "8". The stock rows beside it have faded reflections (`menu-icons-drawer-hello-reflection-zoom.png`). Built-in icons are fine: the star has its real 72×68 faded reflection (`menu-icons-drawer-icon-next.jpg`). | **finding (I1)**: the planned follow-up is a build-generated faded `-r` variant; no change in this run |
| B6 | The unit reset on each `slay -s11 j9` and came back only after the `keypanel up` DHCP kick: R1 took about 12 min (20:42:17 → 20:54:14). After R2 (21:10:32) the unit answered SSH briefly at 21:11:35, then dropped off. `keypanel up` at 21:18 and 21:20 got "still unreachable". | **BLOCKED** (bench hang, as in Spike C Boot B and P3-F12) |
| B7 | S2 not run: the engineering Mem/CPU line (popup 65, `SystemInfo.setFlagMemCPU`) has no entry in this REM (Update / System / Settings / Version information) or in the GEM screens checked (`JavaSys`, `Debugging`). The SDK popup is also not shown on the REM screens (`menu-icons-rem-no-popup.jpg`). Boot B already showed 609600 and 65 side by side in slot 7. | not run |
| B8 | P1 not run: the bench has no PDC ECU, so bike-rack only reaches `waiting for parking value`. `PDC R OFF` cannot appear (as F7 in `docs/bench-e2e-ui.md`). | bench-impossible |
| B9 | Design note: the popup band (x 514..769) sits under the centre of the screen title. The cross icon is drawn above titles such as "Plugins", "Hello" and "Navigation: Enter destination". With the labels now left-aligned, long item text runs into the right half of long titles. The popup is drawn in the top 24 px, above the title text, so there is no direct overlap on the frames seen. | recorded (owner review) |

## Builds and deploys

- **Host dry run (ruling A), before the unit was touched.** I built mibpkg 0.9.0 (`feat/hmisystem` 861b995) for the
  host and cross-built it for QNX (`scripts/build.sh`). `cargo test` passed (one test failed once while the Docker
  cross-build ran alongside; it passed on two reruns).
  - The fake unit tree was a byte copy of the unit's own files: mibpkg state, backups, installed packages, the live
    bundles file (10231 bytes, `cmp`-equal to the host copy), `DSITracer.jar` and the SDK files. The backups had the
    unit's modes.
  - `mibpkg --root <fake> install-file mibsdk` (0.1.0+bench.3): `updated mibsdk 0.1.0+bench.2 -> 0.1.0+bench.3`.
    png_check accepted all **24** SDK icons (`icons-sdk/index.properties`: 8 names × `-20`/`-72`/`-72r`).
  - The bundles file differed from the pre copy only in line 62: `HMISystem=de.audi.tghu.system.hmi.Activator\r` →
    `HMISystem=mibsdk.adapter.hqc.ui.SdkSystemActivator\r`. CR count 266 before and after, mode 0777.
  - hello-menu: exit 3 with `approve with: --approve=ui.slot.statusbar`, then updated. Its icons are
    `hello=e95717048223f737,72,72` and `hello-20=270d239ac4deaad9,20,20`. daemon-status updated (no package icons).
  - Uninstall: `remove mibsdk` first refuses (`remove these plugins first`). After the 5 plugins were removed, it gave
    a live list `cmp`-identical to mibpkg's stock backup (10171 bytes, 0777).
  - Emergency path via mibpkg: `rollback mibsdk` alone refuses (`daemon-status needs plugin api 3`). `rollback
    hello-menu`, `rollback daemon-status`, then `rollback mibsdk` give a list `cmp`-identical to the pre-run file.
  - No fix was needed.
- **Persist safety copy (ruling E):** `/mnt/persist/mibsdk-bench/bundles.before` (10231 bytes). Read back to the host
  and `cmp`-equal to the pre copy.
- **On the unit:**
  - `mibpkg-0.9.0.mpk` (1144338 bytes, read back and compared) → `updated mibpkg 0.8.1+bench.1 -> 0.9.0`. Then
    `supervisor 0.9.0 … (running)`.
  - `mibsdk-0.1.0+bench.3.mpk` → `updated mibsdk 0.1.0+bench.2 -> 0.1.0+bench.3`.
  - `hello-menu-1.0.1-bench.1.mpk`: exit 3, then installed with `--approve=ui.slot.statusbar`.
  - `daemon-status-1.0.1-bench.1.mpk`.
  - Bench-only versions: `plugin.json` must be semver without `+build`, so I used `1.0.1-bench.1` from an
    uncommitted edit, now reverted.
  - Before the restart both plugins reported `loaded: BLOCKED_INVALID … unsupported api 3, this SDK implements api
    1..2`. The running runtime was still bench.2, as expected.
- **Pre-R1 checks:**
  - `mibpkg sdk status`: `HMICar=mibsdk.adapter.hqc.ui.SdkCarActivator`,
    `HMISystem=mibsdk.adapter.hqc.ui.SdkSystemActivator`, `sdk: 0.1.0+bench.3`, `api: 1..3`; grants
    `hello-menu=ui.slot.carmenu,ui.slot.statusbar`.
  - The live list differs from `bundles.pre.properties` only in line 62.
  - `icons/hello-menu/` has two `<sha16>.png` files and `index.properties`, and `icons-sdk/` has 24 files and
    `index.properties`.
  - `/mnt/app` was read-only (`touch`: "Read-only file system"). `spikes/hmi-ui/run.sh status`: no jar, no icons.
- **After R1:** fix 5340cd3, then `./gradlew check`, `:build-logic:test` and the installer tests all passed.
  `mibsdk-0.1.0+bench.4.mpk` was dry-run on the fake tree (bundles unchanged), then installed on the unit
  (`updated mibsdk 0.1.0+bench.3 -> 0.1.0+bench.4`; live bundles unchanged).

## R1: happy path (restart 1, 20:42:17; back 20:54:14 after the DHCP kick)

Log order (`menu-icons-log-r1.txt`; ms since the runtime started):
- `0 STATUS mibsdk ui system=sdk`; there is no `ui system=stock`.
- `5564 STATUS mibsdk ui car=sdk` and `5564 … ui status popup 609600 registered`.
- The plugins were ENABLED at 32257.
- `33257 … ui status popup 609600 built`, then `33257 getImagePath 609101 -> ../mibsdk/icons-sdk/b22e926cb7632baa.png`
  (daemon-status's cross).

The unit was unreachable until 20:54, and the home screen with the popup was already up at the first capture (20:55).
So the popup was built about 1 s after the plugins' first status items. The log has no line for the first screen, so
"popup built vs first screen" is bounded only by these lines. The line was drawn in 609600 (S1 shows it, with B1–B3).

| Check | Result | Evidence |
|---|---|---|
| C0 | **pass.** The unit booted to its last screen (Car drive select) with status bar and clock. `vol_up` showed the volume popup. Log has `ui system=sdk` and `ui car=sdk`, and no `ui system=stock`. | `menu-icons-home.jpg`, `-volume.jpg` |
| M1 | **pass.** The ring ends …Audi smartphone interface, Settings, then **Plugins** with the grey wrench. Focused, it shows the red wrench. The wrench was on the ring at the first view of the end of the ring (about 4 s after MENU), so there was no visible latency. | `menu-icons-ring.jpg`, `-ring-focus.jpg` |
| M2 | **pass.** `knob_select` opened the Plugins page (Bike Rack/Crashy/Daemon Status/Hello Menu/Hello: On, SDK). MENU on the page is a no-op (an identical frame). `back` returned to the ring with focus on Plugins. | `-plugins-from-menu.jpg`, `-menu-on-page.jpg`, `-back-to-ring.jpg` |
| M3 | **pass.** Car → drawer: the stock rows, then Bike rack and Hello; **no** Plugins row while the takeover is ACTIVE. | `-drawer.jpg` |
| I1 | **pass, with finding B5.** The Hello row shows its PNG icon. Log `900414 getImagePath 609100 -> ../mibsdk/icons/hello-menu/e95717048223f737.png`, one id for icon and reflection. The `{id, id}` reflection reads as a second, full-opacity icon. | `-drawer-hello-icon.jpg`, `-drawer-hello-reflection-zoom.png` |
| I2 | **pass.** After "Next icon", Media → Car → drawer: the row shows the built-in star with its faded reflection (`968440 getImagePath 609102/609103`). Icon removal: the PNG row was replaced by a fresh row whose star and reflection look like a stock row. | `-drawer-icon-next.jpg` |
| S1 | **fail → fixed (B1, B2), unverified.** The popup shows top right on Car, Media, the ring and every app screen. The daemon-status cross is tinted with the app colour (red/yellow/blue). The texts overlap at the right edge (B1), and hello's PNG is missing (B2). | `-status-car.jpg`, `-status-car-zoom.png`, `-status-media.jpg`, `-status-ring.jpg` |
| S2 | **not run (B7).** | `-rem-no-popup.jpg` |
| S3 icon swap | **fail → fixed (B3), unverified.** | `-status-icon-swap-not-live-zoom.png`, `-status-media.jpg` |
| S3 movement | **pass (live).** `tailscale.sh start` turned daemon-status from `TS ?` to `TS dn` (TS is `NeedsLogin`) without a screen change. hello's dot (cell 1) moved right by one character (about 10 px in popup coordinates: rel. x ≈144 → ≈154). Bounds changes are live (`AbstractWidgetController.setBounds` marks the widget dirty), so the Task 5 Step 6 hide/re-show fallback is **not needed** and was not applied. Because of B1 the text cells cannot be checked for stale cells. | `-status-relayout-zoom.png` |
| P1 | **bench-impossible (B8).** | — |
| Stock | **pass.** Radio opened from the ring (Radio: FM), Navigation opened from the ring (Navigation: Enter destination), and the NAV, MEDIA and CAR hard keys worked. | `-stock-radio.jpg`, `-stock-nav.jpg` |

## R2: safe mode (restart 2, 21:10:32) — BLOCKED

- `mibsdk-dev ui-forcefail screen` failed (B4).
- `mibpkg sdk safe-mode on`: `safe mode marker set`. The restart went ahead with safe mode only.
- `keypanel up` at 21:10:47 answered before the unit reset. SSH answered once at 21:11:35, then the unit went off the
  network. Two `keypanel up` tries after the reset (21:18:41, 21:20:42) failed with "still unreachable". I stopped
  there, per the bench rule.
- Not run: F1 (stock ring), F2 (drawer Plugins row back), F3 (`SAFE` in 609600, which would also have been the first
  on-screen check of B1–B3), and all of R3.
- The emergency rule did not apply: SSH did not answer, which is the bench hang and not a blank HMI.

## Bench state at the stop

- mibpkg 0.9.0 supervises. Installed: `mibsdk 0.1.0+bench.4` (5340cd3, previous bench.3),
  `hello-menu 1.0.1-bench.1`, `daemon-status 1.0.1-bench.1`, and `hello`, `bike-rack`, `crashy 1.1.0` and
  `carmqtt 0.2.2` as before.
- The live bundles list has `HMICar=…SdkCarActivator` and `HMISystem=…SdkSystemActivator`, 10238 bytes. The stock
  backup is in mibpkg's records, and `/mnt/persist/mibsdk-bench/bundles.before` holds the pre-run list.
- **Safe mode is ON** (marker set). `ui.forcefail` is absent. `/mnt/app` was read-only at the last check.
- The unit is unreachable (bench hang). The next boot after a power-cycle will be in safe mode with the takeover.
  Still to do:
  1. R2's F3 (SAFE in the fixed popup) and B1–B3 on screen, on that boot.
  2. `mibsdk-dev ui-forcefail screen` (fixed tool) for the screen-46 path, if the budget is re-granted.
  3. `mibpkg sdk safe-mode off` and `mibsdk-dev ui-forcefail off`, a restart, then R3 (N1, S1/S3 swap on the fixed
     popup).
