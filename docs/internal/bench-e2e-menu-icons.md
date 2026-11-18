> **Internal engineering record.** This page is kept for history and may be out of date. See [the internal docs index](README.md).

# Bench end-to-end: main-menu entry, row icons and the status popup (Task 12)

Run 2026-10-24 on the bench MHI2Q (10.20.0.23, `HMI_AU_MIB2_HQC_NAR_G22_C10_180803`). This was the first boot of the
`HMISystem=` takeover as a product (`SdkSystemActivator`), not a spike. Frames are `docs/bench-e2e/menu-icons-*.jpg`
(512×240 keypanel captures) and `*-zoom.png` crops. The R1 log is `docs/bench-e2e/menu-icons-log-r1.txt`.

**Overall: pass after two fixes, with findings.**
- R1 passed for the main menu, the drawer and the row icons. It found three status-popup defects (B1–B3). They are
  fixed in 5340cd3 and confirmed on screen at R2a/R3.
- R2 hit a tool bug (B4, fixed in b619202), then the bench hang. After the owner's power-cycle the run continued:
  - R2a, the safe-mode boot;
  - R2b, `ui-forcefail screen` plus safe mode: the screen-46 fallback works and the drawer Plugins row comes back;
  - R3, normal.
- The unit ends in the required state.
- Open findings: B5 (the `{id,id}` reflection) and B10 (package PNGs are tinted with the app colour).

## Findings

| # | Finding | Status |
|---|---------|--------|
| B1 | **Popup labels were right-aligned.** `SdkStatusPopup` set `setAlignment(3, 4)`, and 3 is `ALIGN_RIGHT` in the stock `Alignment` (`LabelRendererHigh.getAlignmentOffsetX`). Each cell's label box runs to the popup's right padding, so every item's text ended at the same x: `TS ? MQ dn` and `Hello` were drawn on top of each other (`menu-icons-status-car-zoom.png`). | **fixed 5340cd3** (`SdkCarScreenFactoryTest.popupLabelsAreLeftAligned` first). **Verified:** R2a `SAFE` at the left padding; R3 `✕ TS ? MQ ?  ◎ Hello` side by side (`menu-icons-r3-status-zoom.png`) |
| B2 | **Package PNGs were never drawn in the popup.** File icons were set with `setResourceLocator(-1, path, 1)`. For a locator of status 1, `IconRendererHigh.calculateDisplayData` draws the controller's bitmap 0 and never loads the URI. Spike C used `new HMIResourceLocator(path)` (status 0). hello's `hello-20` PNG never showed (nothing at cell 1's icon x in `menu-icons-status-car-zoom.png`). The host test asserted status 1. | **fixed 5340cd3** (`SdkCarActivatorTest.popupModelsAreCreated` now asserts 0 and failed first). **Verified at R3:** hello's PNG is drawn in cell 1 (see B10 for its colour) |
| B3 | **A built-in icon change in the popup showed only at the next screen change.** `AbstractWidget.setBitmaps` only stores the array; nothing marks the icon dirty. "Next status icon" → star: nothing (`menu-icons-status-icon-swap-not-live-zoom.png`), no `getImagePath` for the star. A second press → dot: the dot appeared only after MEDIA was pressed (`menu-icons-status-media.jpg`), and the log has its first request then (`1143491 getImagePath 609105 -> ../mibsdk/icons-sdk/41a75fb1f2765d90.png`). The star was never drawn. | **fixed 5340cd3** (`SdkCarScreenFactoryTest.iconChangeOnABuiltPopupMarksTheIconDirty` first: a changed id icon is `setCompositesDirty(true)`). **Verified at R3:** PNG → star → dot live on the Hello page, with text and cell in place (`menu-icons-r3-swap-*-zoom.png`) |
| B4 | **`mibsdk-dev ui-forcefail` failed on the unit**: `sh: printf: cannot execute`, then `cannot write /mnt/persist/mibsdk/dev/ui.forcefail`. The restart had been chained after it, so R2 booted with safe mode on and no forced failure. | **fixed b619202** (`UiForceFailTest.test_remote_commands_use_no_printf` first; the write uses `echo`). The fixed tool wrote `screen` at R2b (read back before the restart) |
| B5 | **The plugin PNG doubles as its own reflection (`{id, id}`), and that reads as a second icon.** The Hello row draws its ring icon and, under it, a full-opacity copy, so it looks like an "8". The stock rows beside it have faded reflections (`menu-icons-drawer-hello-reflection-zoom.png`). Built-in icons are fine: the star has its real 72×68 faded reflection (`menu-icons-drawer-icon-next.jpg`). | **finding (I1)**: the planned follow-up is a build-generated faded `-r` variant; no change in this run |
| B6 | The unit reset on each `slay -s11 j9` and came back only after the `keypanel up` DHCP kick: R1 took about 12 min (20:42:17 → 20:54:14). After R2 (21:10:32) the unit answered SSH briefly at 21:11:35, then dropped off. `keypanel up` at 21:18 and 21:20 got "still unreachable". | bench hang (as in Spike C Boot B and P3-F12): the owner power-cycled, back at 21:32. R2b and R3 came back after the DHCP kick |
| B7 | S2 not run: the engineering Mem/CPU line (popup 65, `SystemInfo.setFlagMemCPU`) has no entry in this REM (Update / System / Settings / Version information) or in the GEM screens checked (`JavaSys`, `Debugging`). The SDK popup is also not shown on the REM screens (`menu-icons-rem-no-popup.jpg`). Boot B already showed 609600 and 65 side by side in slot 7. | not run |
| B8 | P1 not run: the bench has no PDC ECU, so bike-rack only reaches `waiting for parking value`. `PDC R OFF` cannot appear (as F7 in `docs/bench-e2e-ui.md`). | bench-impossible |
| B9 | Design note: the popup band (x 514..769) sits under the centre of the screen title. The cross icon is drawn above titles such as "Plugins", "Hello" and "Navigation: Enter destination". With the labels now left-aligned, long item text runs into the right half of long titles. The popup is drawn in the top 24 px, above the title text, so there is no direct overlap on the frames seen. | **changed in 0.2.0, verified on the bench** (`bench-e2e-release-0.2.md` B9-1, B9-3, B9-6): the composed line is packed against the right edge of the band (it ends at the end of the 24-character grid, x 6 + 240 inside the popup), so the free space is on the left; the order (indicators first) and the fair-share widths are unchanged (`StatusLayout.of`) |
| B10 | **Package PNGs in the popup are tinted with the app colour, not drawn in their own colours.** hello's white `hello-20.png` (all 172 visible pixels are 255,255,255) draws red on Car and yellow on Media, like the id icons (`menu-icons-r3-status-png-tinted-zoom.png`, `-r3-status-media-zoom.png`). Spike C S2 reported a green file icon keeping its colour. For white monochrome PNGs this matches the stock look, but a coloured plugin PNG would be tinted. I did not investigate the cause. | open (finding; owner decision) |
| B11 | At R1 and R3, `rot 4` from the first row of the Hello page landed on the info row `Selected: …`, not on `Next status icon`; one step back fixed it. This is a keypanel/rotation quirk, not an SDK fault. | recorded (bench) |

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

## R2: safe mode (restart 2, 21:10:32) and the bench hang

- `mibsdk-dev ui-forcefail screen` failed (B4).
- `mibpkg sdk safe-mode on`: `safe mode marker set`. The restart went ahead with safe mode only.
- SSH answered once at 21:11:35, then the unit went off the network. Two `keypanel up` tries after the reset (21:18:41,
  21:20:42) failed, and the run stopped BLOCKED.
- The owner power-cycled the unit, and `keypanel up` at 21:32 brought it back. That boot is R2a and does not count
  as a restart.

## R2a: safe-mode boot after the power-cycle (not a restart)

Log `menu-icons-log-r2a.txt`:
- `safeMode=true … reason=marker`, `ui system=sdk`;
- each plugin `DISABLED safe mode: not loaded`;
- `22328 ui car=sdk`, `23329 popup 609600 built`.

| Check | Result | Evidence |
|---|---|---|
| F3 / SAFE | **pass.** `SAFE` shows top right in 609600, at the popup's left padding. This is B1 confirmed on screen. | `menu-icons-r2a-status-safe.jpg`, `-r2a-status-safe-zoom.png` |
| Takeover in safe mode | The ring keeps Plugins: the System takeover is ACTIVE. Plugins from MENU shows `SDK safe mode: plugins are off (marker)` and every plugin `Off`. The drawer has no Plugins row. | `-r2a-plugins-from-menu-safe.jpg`, `-r2a-drawer.jpg` |
| B2 | Not checkable on this boot: in safe mode no plugin runs, so no package icon is shown. Checked at R3. | — |

## R2b: `ui-forcefail screen` plus safe mode (restart, 21:33:41; back 21:40 after the DHCP kick)

`mibsdk-dev ui-forcefail screen` printed `ui.forcefail=screen written`. Before the restart, `cat` on the unit read
`screen`, and the SAFE_MODE marker was present.

Log `menu-icons-log-r2.txt`:
- `0 WARN … configurator: ui.forcefail=screen`;
- `0 STATUS mibsdk ui system=sdk`: the factory is the SDK's, because `screen` fails only when screen 46 is built;
- `safeMode=true reason=marker`;
- at the first MENU: `289912 ERROR mibsdk ui main menu failed, screen 46 stock for this boot:
  java.lang.IllegalStateException: forced failure: screen`.

| Check | Result | Evidence |
|---|---|---|
| F1 | **pass.** Home and `vol_up` work. The MENU ring is **stock**: it ends at Settings, with no Plugins row and no wrench. Radio (Radio: Presets) and Navigation (Enter destination) open from the ring. | `menu-icons-r2-volume.jpg`, `-r2-ring-stock.jpg`, `-r2-radio.jpg`, `-r2-nav.jpg` |
| F2 | **pass.** Car → drawer now ends with **Plugins (safe mode)**: the row is back once the main menu is FAILED. It opens the Plugins page with the safe-mode notice. | `-r2-drawer-plugins.jpg`, `-r2-drawer-plugins-page.jpg` |
| F3 | **pass.** `SAFE` shows in 609600 on every screen. | `-r2-status-safe-zoom.png` |

## R3: back to normal (restart, 21:41:48; back 21:48)

Before the restart I checked both switches on the unit:
- `mibsdk-dev ui-forcefail off`: the file is gone;
- `mibpkg sdk safe-mode off`: SAFE_MODE is gone, and `boot.state` reads `unhealthyBoots=0 wrapBoots=0`.

Log `menu-icons-log-r3.txt`: `safeMode=false`, `ui system=sdk`, `ui car=sdk`, 5 plugins `ENABLED`,
`popup 609600 built`, `boot healthy`.

| Check | Result | Evidence |
|---|---|---|
| N1 ring | **pass.** The ring ends with Plugins and the wrench. | `menu-icons-r3-ring.jpg` |
| N1 drawer | **pass.** No Plugins row; Bike rack and Hello (PNG icon) are last. | `-r3-drawer.jpg` |
| N1 status | **pass.** Both plugins' items sit side by side: `✕ TS ? MQ ?` (daemon-status, tinted cross) and `◎ Hello` (hello's PNG). B1 and B2 are confirmed on screen; the PNG is tinted (B10). | `-r3-status.jpg`, `-r3-status-zoom.png`, `-r3-status-media-zoom.png` |
| S3 icon swap | **pass (B3 fixed).** On "Next status icon" the PNG was gone at the 1.5 s capture and the star showed at the next one (`getImagePath 609102 -> ../mibsdk/icons-sdk/966b026bec63dfd5.png`). A second press turned the star into a dot. Text and cell stayed in place, with no screen change. The keypanel mirror is lazy, so a frame can lag by a few seconds; what matters is that no screen change was needed, which was the defect. | `-r3-swap-1-png-gone-zoom.png`, `-r3-swap-2-star-zoom.png`, `-r3-swap-3-dot-zoom.png` |
| Step 6 fallback | Not applied: S3 movement was already live at R1. | — |

## Restart times

| Restart | Issued | Back (bridge) | Note |
|---|---|---|---|
| R1 | 20:42:17 | 20:54:14 | DHCP kick needed |
| R2 | 21:10:32 | — | bench hang; the owner power-cycled, back 21:32 (R2a, not counted) |
| R2b | 21:33:41 | 21:40:02 | DHCP kick needed |
| R3 | 21:41:48 | 21:48:04 | |

## Bench state at the end

- mibpkg 0.9.0 supervises.
- Installed:
  - `mibsdk 0.1.0+bench.4` (5340cd3);
  - `hello-menu 1.0.1-bench.1` and `daemon-status 1.0.1-bench.1`;
  - `hello`, `bike-rack`, `crashy 1.1.0` and `carmqtt 0.2.2`.
- All 5 plugins are ENABLED with their grants; the ledger is empty.
- The takeover is ACTIVE: `HMICar=mibsdk.adapter.hqc.ui.SdkCarActivator` and
  `HMISystem=mibsdk.adapter.hqc.ui.SdkSystemActivator`. The ring has Plugins with the wrench.
- Safe mode is off (no SAFE_MODE marker), and `ui.forcefail` is absent.
- `/mnt/app` is read-only (`touch` gives "Read-only file system").
- The unit is on its home screen (Vehicle: Audi drive select, Dynamic; `menu-icons-r3-final-home.jpg`).
- `/mnt/persist/mibsdk-bench/bundles.before` (the pre-run list) is kept as a spare.
