# Bench end-to-end: status icon colour and drawer reflections (Task 6)

Run 2026-10-25 on the bench MHI2Q (10.20.0.23, `HMI_AU_MIB2_HQC_NAR_G22_C10_180803`), branch `feat/icon-colour`
at 4fd678f. The plan is `docs/superpowers/plans/2026-10-25-icon-colour-and-reflection.md` (Task 6); the spec is
`docs/research/2026-10-25-icon-colour-and-reflection.md` (bench checks B1–B5). Frames are
`docs/bench-e2e/icon-colour-*.jpg` (512×240 keypanel framebuffer captures: they show colours and icons, not the
backlight) and `*-zoom.png` host crops. The R1 log is `docs/bench-e2e/icon-colour-log-r1.txt`.

**Overall: pass for H1–H3, B1 and B3; B2 [spike] fails (finding C1); B4 cannot be reached on this HMI (finding C2).**

**Update (B2 re-run, below): with the Task 7 fix (78225f9) B2 passes and C1 is verified fixed.**
One restart (R1) was used out of a budget of 1 (2 at most). The unit ends in the required state.

## Findings

| # | Finding | Status |
|---|---------|--------|
| C1 | **B2: a colour change on a built popup is not drawn live.** On the Hello page, with the popup up, "Status icon colour" stores the new value at once (`data/hello-menu.properties` reads `colour=own`/`tinted` right after each press), but the cell keeps the old colours. Each press's own redraw shows the *previous* stored value (press 2 at +1.2 s showed press 1's `own`). With no key pressed, the new colours appeared only on a later, unrelated redraw: about 32 s after press 3, and between ~4 s and ~20 s after press 2. Any screen change (MEDIA, CAR, MENU, even CAR on the Hello page with no visible change) draws the new colours at once (B1). So `setColorIndices(...)` plus `setCompositesDirty(true)` on a built cell is not enough to re-compose it, unlike the id-icon swap fixed for Task 12 B3. `icon-colour-status-toggle.jpg` | **fixed, verified on the bench** (B2 re-run with mibsdk 0.1.0+bench.6 at 78225f9: six presses, both directions, the first frame with the new colour at +0.56 to +0.81 s every time; see "B2 re-run (C1 fix)"). Cause (lsd-src): the HMI job only marks the icons dirty (`AbstractWidget.setInvalid` sets a bit and marks the parent, no paint), and the HMI paints only when asked, mostly after a consumed model update (`AbstractScreenWidget.processModelUpdateEvent` → `triggerRepaint`). A colour-only change writes no model, and the press's own repaint runs before the job. Fix (Task 7): after a job that recoloured a cell, `HmiWidgets` calls `SdkStatusPopup.redraw` once, which is the popup's stock `triggerRepaint` (an unbound popup asks the current screen's `doCheckedRepaint`). The spec's fallback (`processStateChange` 0 plus a popup rebuild) is not needed |
| C2 | **B4 is not reachable: the Hello row is never the drawer's "selected" row.** In this HMI the drawer's selected row (`Color` = app colour, the red icon) is the row of the *current screen*; the knob focus frame does not tint (`icon-colour-drawer-current-row.jpg`: Audi drive select red, focused Driver assistance white). SDK pages have no drawer handle: on the Hello page `menu_left` does nothing (`icon-colour-hello-page-no-drawer.jpg`) and `back` returns to Audi drive select, so the drawer always opens with drive select as the selected row. With the knob focus on Hello after visiting its page, the icon and reflection stay white/blue (`icon-colour-drawer-selected.jpg`, `-drawer-selected-zoom.png`). The red-multiply case of spec §2.2 therefore never shows for plugin rows on this unit | **finding (spec §2.2 [spike] row: not observable).** In practice drawer PNGs always keep their own colours here. Spec §2.2 and the `Ui.icon(String,int)` / `Icon.OWN_COLOURS` Javadoc now say so |
| C3 | Until MENU is first opened after a boot, the Car drawer still shows the SDK's Plugins row (black icon square) above the plugin rows (`icon-colour-drawer-plugins-row-before-menu.jpg`). After the first MENU press the ring ends with Plugins (wrench) and the drawer has no Plugins row (`icon-colour-ring-plugins.jpg`, `-drawer-after-menu.jpg`). This is the designed `SdkGlobals.mainMenu` PENDING → ACTIVE behaviour (screen 46 is built lazily), not a regression; Task 12 M3 checked the drawer after MENU | recorded (by design) |
| C4 | The unit reset on `slay -s11 j9` again (as Task 12 B6): bridge false at once, ping lost at 11:47:23, and it came back only after one `keypanel up` DHCP kick (11:51:42 → up 11:52:50) | bench behaviour; one try was enough |
| C5 | The B11 rotation quirk again: `rot 5` from Apple and `rot 4` from Apple both landed on the info row `Selected: Pear`; one step back reached "Status icon colour". Every `knob_select` was sent after a frame check of the focus | recorded (bench) |
| C6 | The Bike rack drawer row (and the pre-MENU Plugins row) shows a black square icon, as in Task 12 (`menu-icons-r3-drawer.jpg`) | unchanged, not in scope |

## Host checks (Phase 1, before the restart)

- **H1 (pass), mibpkg 0.9.1 + new packages** on a byte copy of the unit (fake1): mibsdk 0.1.0+bench.4 → 0.1.0+bench.5
  and hello-menu 1.0.1-bench.1 → 1.2.0. The hello-menu index is `hello=d4b21c0db915e1a4,72,72`,
  `hello-20=721e6875f2d6d947,20,20`, `hello.r=bb52526b1280f087,72,68` (LF, sorted); `bb52526b1280f087.png` is 19720
  bytes and every sha16 matches the package file. `icons-sdk/index.properties` has 24 lines and no `.r`. The bundles
  file is unchanged.
- **H2 (pass), old mibpkg 0.9.0 + new packages** (fake2): both install. 0.9.0 accepts the undeclared
  `icons/hello.r.png`, keeps it only in the package store and writes no `.r` line or file.
- **H3 = spec B5 (pass), old runtime + new index:** with the e3240cb `platform.jar`/`api.jar` in jshell,
  `IconIndex.read("hello-menu", fake1)` gives `hello` as the 72×72 entry and `hello.r` = null. This branch's jars on
  the fake2 index (no `.r`) give `hello` with `reflection=null`; on fake1 they give
  `reflection=hello-menu/bb52526b1280f087` (positive control).

## Builds and deploys

- Host and QNX mibpkg 0.9.1 from the mibpkg `feat/icon-colour` branch, `mibpkg-0.9.1.mpk`; host mibpkg 0.9.0 from main.
- `mibsdk-0.1.0+bench.5.mpk` (api 4, 24 SDK icons, no `.r`) and `hello-menu-1.2.0.mpk` (`hello.png` 1683,
  `hello-20.png` 432, `hello.r.png` 19720 bytes) from the SDK at 4fd678f with `-Pmibsdk.version=0.1.0+bench.5`.
- On the unit: a safety copy of the live bundles file (`/mnt/persist/mibsdk-bench/bundles.before2`), then each
  package uploaded, read back, `cmp`-verified and installed with `mibpkg install-file`: mibpkg 0.9.0 → 0.9.1 (it
  restarted itself), mibsdk bench.4 → bench.5 (`reboot needed: yes`), hello-menu → 1.2.0 (BLOCKED_INVALID on the
  still-running api 1..3 runtime, as expected until R1). Pre-restart: `sdk status` showed `api: 1..4`, the unit's
  hello-menu index equals H1's, the bundles file was unchanged, `/mnt/app` read-only, no safe mode, no forcefail.

## R1: the single restart

| Time (host) | Event |
|---|---|
| 11:45:58 | Pre-flight: `bridge_connected:true`; `sdk status` bench.5, `api: 1..4`, `reboot needed: yes` |
| 11:46:03 | `installer/mibsdk-dev restart` (`slay -s11 j9`), returned 11:46:06 |
| 11:46:13–11:47:10 | ping up, `bridge_connected:false` |
| 11:47:23–11:51:57 | no ping (the unit reset, C4) |
| 11:51:42 | `ssh rpi-bench keypanel up` (try 1): `mmx: no answer - kicking DHCP on mlan0 via RCC`, `mmx: up` |
| 11:52:50 | keypanel ready; `bridge_connected:true`; `sdk status` `running: yes`, `reboot needed: no` |

About 6 min 47 s from the restart to a usable unit. The log (`icon-colour-log-r1.txt`) has `ui system=sdk`,
`ui car=sdk`, `ui status popup 609600 registered`, the five plugins `ENABLED` (hello-menu 1.2.0, api 4, no
BLOCKED_INVALID), `ui status popup 609600 built`, and `boot healthy: 5 plugin(s) enabled for 60000 ms`.

## Bench checks

| Check | Result | Frames |
|---|---|---|
| B1 own | **pass.** After the boot (stored colour: none, so OWN_COLOURS): on Car (Audi drive select) hello-20 is drawn white-left / blue-right; on Media the same. daemon-status's built-in cross is tinted: red on Car, yellow on Media | `-status-own-car.jpg`, `-status-own-media.jpg`, `-status-own-{car,media}-zoom.png` |
| B1 tinted | **pass.** Car → drawer → Hello → "Status icon colour" (`colour=tinted` stored). After a screen change hello-20 is red/black on Car and yellow/black on Media; the cross stays tinted in all four frames. A CAR press while on the Hello page (no visible screen change) also redraws it tinted (`-status-tinted-hello-page.jpg`) | `-status-tinted-car.jpg`, `-status-tinted-media.jpg`, `-status-tinted-{car,media}-zoom.png` |
| B2 [spike] | **fail (C1).** Three presses on the Hello page with the popup up, frames at +1.2 s and later: the cell does not change within ~1 s; it shows the new colours only on a later redraw (about 32 s after press 3) or at once on any screen change | `-hello-page.jpg`, `-status-toggle.jpg` (labelled zooms, top to bottom in press order) |
| B3 | **pass.** Car → drawer, focus on Bike rack: the Hello row's icon is white/blue and under it a faded reflection, no "8", next to stock rows with their own faded reflections. The log has two distinct ids: `416468 getImagePath 609100 -> ../mibsdk/icons/hello-menu/d4b21c0db915e1a4.png` and `416468 getImagePath 609101 -> ../mibsdk/icons/hello-menu/bb52526b1280f087.png`. The reflection reads like the stock ones at this size, so the §3.3 polish is not needed for this art | `-drawer.jpg`, `-drawer-reflection-zoom.png` |
| B4 | **not reachable (C2).** The Hello row can carry the knob focus but never be the drawer's selected (current-screen) row; with focus, its icon and reflection stay white/blue | `-drawer-selected.jpg`, `-drawer-selected-zoom.png`, `-drawer-current-row.jpg`, `-hello-page-no-drawer.jpg` |

## End state

- mibpkg 0.9.1, mibsdk 0.1.0+bench.5 running (`api: 1..4`, `running: yes`, `reboot needed: no`), takeover ACTIVE:
  `HMICar=`/`HMISystem=` SDK activators, `ui system=sdk`, `ui car=sdk`, MENU ring ends with Plugins (wrench), and no
  drawer Plugins row (`icon-colour-ring-plugins.jpg`, `-drawer-after-menu.jpg`).
- All five plugins ENABLED; hello-menu 1.2.0 with grants `ui.slot.carmenu,ui.slot.statusbar`; ledger 0 overrides.
- The unit's hello-menu index has the three lines of H1, with `bb52526b1280f087.png` 19720 bytes.
- hello-menu's stored colour is `own` (three B2 presses from `tinted`), and the home screen draws it white/blue.
- No safe-mode marker, `boot.state` `unhealthyBoots=0`, `/mnt/persist/mibsdk/dev/` empty (no forcefail).
- The live bundles file equals `bundles.before2` (10238 bytes); `/mnt/app` is read-only.
- The unit is on its home screen, Car / Audi drive select (`icon-colour-final-home.jpg`).

## Follow-ups

- C1: done. The Task 7 repaint is verified by the B2 re-run; the fallback (`processStateChange` 0 plus a popup rebuild) is not needed.
- C2: done in Task 7 (spec §2.2, `Ui.icon(String,int)`, `Icon.OWN_COLOURS`).

## B2 re-run (C1 fix)

Run 2026-10-25 on the same unit, SDK at 78225f9 (`fix(hqc): repaint the status popup after a colour change (bench C1)`),
packaged as `mibsdk-0.1.0+bench.6.mpk` (`-Pmibsdk.version=0.1.0+bench.6`, a package-version-only bench tag; 624105
bytes, `api = 4`, `mibsdk-adapter-hqc.jar` 129493 bytes, sha256 `bb9865b9…`, which has `SdkStatusPopup.redraw`). mibpkg
0.9.1 and hello-menu 1.2.0 were already on the unit and were not changed.

**Result: pass. The colour change is drawn live in both directions; C1 is fixed.**

### Install and restart

- Dry run on a fake root (a `pax` byte copy of the unit: mibpkg state/backups/pkgs, `lsd/mibsdk/`, `lsd/jars/`, the
  bundles file, `DSITracer.jar`, `/mnt/persist/mibsdk`, `img_ver.txt`, `/ifs/version_info.txt`) with host mibpkg 0.9.1:
  `updated mibsdk 0.1.0+bench.5 -> 0.1.0+bench.6; reboot the unit (or restart the HMI) to start it`; `sdk status`
  bench.6, `api: 1..4`, `reboot needed: yes`; the bundles file `cmp`-equal; the installed adapter jar has the package's
  sha256; hello-menu index unchanged (3 lines with `hello.r`), `icons-sdk` 24 lines, no `.r`.
- On the unit, `installer/mibsdk-dev install` (upload, read back, `install-file mibsdk`): the same message. Staged
  state before the restart: `sdk: 0.1.0+bench.6`, `api: 1..4`, `running: no`, `reboot needed: yes`, both SDK
  activator lines, 5 plugins with their grants, ledger 0; the live bundles file equals the pre-install copy and
  `bundles.before2` (10238 bytes); the live adapter jar has the package's sha256; `/mnt/app` read-only; `dev/` empty;
  `colour=own`.

| Time (host) | Event |
|---|---|
| 12:23:01 | `installer/mibsdk-dev restart` (`slay -s11 j9`), returned 12:23:04 |
| 12:23:09–12:23:41 | ping up, `bridge_connected:false`, then no ping (the unit reset, as C4) |
| 12:30:32 | `ssh rpi-bench keypanel up` (try 1): `mmx: no answer - kicking DHCP on mlan0 via RCC`, `mmx: up` |
| 12:31:39 | keypanel ready; `bridge_connected:true`; `sdk status` bench.6 `running: yes`, `reboot needed: no` |

About 8 min 38 s from the restart to a usable unit, one restart and one `keypanel up`. The log
(`icon-colour-b2r-log.txt`) has `ui system=sdk`, `ui car=sdk`, `ui status popup 609600 registered`, the five plugins
`ENABLED`, `ui status popup 609600 built` and `boot healthy: 5 plugin(s) enabled for 60000 ms`.

### Procedure

Car → drawer (the pre-MENU Plugins row was there, C3) → Hello → the Hello page with the status popup up
(`icon-colour-b2r-hello-page.jpg`, focus on "Status icon colour"; `rot 4` from Apple landed on `Selected: Pear` and one
step back reached it, the C5 quirk again). For each press, a host loop fetched `/screen.jpg` about every 0.25 s from
1 s before to 6 s after one `knob_select`, with no other input; the times below are from the `POST /inject` to the
start of each frame fetch. The stored value was read from `data/hello-menu.properties` after each press.

| Press | Direction (stored after) | Last old-colour frame | First new-colour frame | Frame at ~+1.2 s, hello-20 seen |
|---|---|---|---|---|
| 1 | own → tinted (`tinted`) | +583 ms | +810 ms | +1126 ms: red/black (`icon-colour-b2r-p1.jpg`) |
| 2 | tinted → own (`own`) | +339 ms | +611 ms | +1254 ms: white/blue (`-p2.jpg`) |
| 3 | own → tinted (`tinted`) | +334 ms | +568 ms | +1288 ms: red/black (`-p3.jpg`) |
| 4 | tinted → own (`own`) | +317 ms | +605 ms | +1266 ms: white/blue (`-p4.jpg`) |
| 5 | own → tinted (`tinted`) | +327 ms | +557 ms | +1193 ms: red/black (`-p5.jpg`) |
| 6 | tinted → own (`own`) | +458 ms | +717 ms | +1211 ms: white/blue (`-p6.jpg`) |

`icon-colour-b2r-toggle-zoom.png` shows the status bar of each press at about −1 s, at the last old frame and at
~+1.2 s. Mean colour of the hello-20 cell: own (40,39,70), tinted (42,15,19), the same on every press.

### Other checks

- **Only the cell changes.** Frame to frame over the whole 512×240 frame (difference thresholded at 15%), every press
  series has exactly one change: 31 pixels, bounding box 5×8 at (325,2), the hello-20 glyph. daemon-status's cross
  stays red (mean (65,14,21) in every frame), its label `TS ? MQ dn` and the `Hello` label do not change, and nothing
  else on the screen flickers or blinks. (The SAFE/PDC indicator was not shown on this screen; no pixel outside the
  cell changed in any frame.)
- **Log:** no new exception or error. The only lines added after the boot are the drawer's two `getImagePath`
  lines (609100 → `d4b21c0db915e1a4.png`, 609101 → `bb52526b1280f087.png`). The one `Exception` text in the log is the
  boot-time `dirSync=unsupported` durability note, unchanged from earlier runs.
- **Job time:** the SDK has no DEBUG timing for the HMI job, so it was not measured. Upper bound from the frames: the
  new colour was on screen 0.56–0.81 s after the press was sent, which includes the plugin's press handling, the job
  and the ~5 fps mirror.

### End state

mibpkg 0.9.1; mibsdk 0.1.0+bench.6 running (`api: 1..4`, `running: yes`, `reboot needed: no`), takeover ACTIVE
(`HMICar=`/`HMISystem=` SDK activators, `ui system=sdk`, `ui car=sdk`, the MENU ring ends with Plugins (wrench) after
Settings, `icon-colour-b2r-ring-plugins.jpg`); all five plugins ENABLED, ledger 0; hello-menu `colour=own` (six presses
from `own`); no safe mode (`unhealthyBoots=0`), `dev/` empty (no forcefail); the bundles file equals `bundles.before2`;
`/mnt/app` read-only; the unit on its home screen, Car / Audi drive select, hello-20 white/blue
(`icon-colour-b2r-final-home.jpg`).
