> **Internal engineering record.** This page is kept for history and may be out of date. See [the internal docs index](README.md).

# Bench end-to-end: native inputs (api 5, mibsdk 0.3.0)

Run 2026-10-26 on the bench MHI2Q (10.20.0.23, `HMI_AU_MIB2_HQC_NAR_G22_C10_180803`), branch `feat/native-inputs`
at 6428db8.

- **Checklist:** Task 9 of the native-inputs plan: host checks H1–H2, old-runtime check O1, and N0–N12 after one
  restart (R1).
- **Frames:** `docs/bench-e2e/native-inputs-*.jpg`, 512×240 keypanel captures, half the 1024×480 screen in each
  axis. `*-zoom.png` files are host crops, upscaled.
- **R1 log:** `docs/bench-e2e/native-inputs-log-r1.txt`.

**Overall: pass for H1, H2 and N0–N12.** There are a few minor findings, listed below. Two of
them, B and C (page focus), did need a code change: b9cf629 and 7e6be90 fix them. The fix is verified on the bench
in "Focus fix (b9cf629, 7e6be90)" below.

- N9 checks the toast with the status popup and its plugin items. The bench has no PDC ECU, so `PDC R OFF` and `SAFE`
  together with a toast are a car-only check.
- **O1 could not be shown live.** The unit was in safe mode when the run started (see O1).

One restart (R1) was used. The unit ends in the required state.

## Builds and dry run

- Host mibpkg 0.9.1, built fresh from mibpkg main a0a5e49. The repo's `target/` binary was not used.
- From 6428db8 with no version override (only `-Pmibsdk.mibpkg=<host mibpkg>`):
  `./gradlew :dist:mibpkgPackage :plugins:<each>:mibPackage`. The working tree stayed clean.

  | Package | Bytes | sha256 (first 16) | Notes |
  |---|---|---|---|
  | `mibsdk-0.3.0.mpk` | 693679 | 1e326329075724a7 | api 5, min_api 1, 24 SDK icons |
  | `inputs-demo-1.0.0.mpk` | 7774 | 1c11e91efd97fa47 | api 5, `ui.slot.statusbar`, icon `plug-24` plus `icons/plug-24.r.png` |
  | `bike-rack-1.1.0.mpk` | 28019 | 602f0f222945eec7 | api 3 |
  | `hello-menu-1.2.0.mpk` | 6977 | f8efc2c64c2edf75 | api 4 |
  | `daemon-status-1.1.0.mpk` | 6465 | 993fbf40c2532bd8 | api 3 |
  | `crashy-1.1.0.mpk` | 6136 | da9e09eef4f8aa1f | api 2 |
  | `hello-1.0.0.mpk` | 2400 | 95a92cf347751117 | api 1 |

  The jars are `mibsdk-runtime.jar` 266691 bytes (it contains 6428db8's `UiText.cut`), `mibsdk-adapter-hqc.jar`
  183889 bytes and `mibsdk-rt-retrotranslator.jar` 308523 bytes.
- **Fake root.** A `pax -w` copy of the unit, the same set as release 0.2:
  - the mibpkg tree, `lsd/mibsdk`, `lsd/jars`, the bundles file and `DSITracer.jar`;
  - `/mnt/persist/mibsdk`, `/mnt/app/img_ver.txt`, `/ifs/version_info.txt` and `startup.sh`.

  The bundles file in the copy is byte-equal to the live one. Each package was copied to
  `<root>/tmp/mibpkg.upload.<32-hex id>.mpk` and installed with
  `mibpkg --root <root> --no-supervisor install-file <pkg> <id> -`.
  - Without `--no-supervisor`, a `--root` run starts a host supervisor for that root, which is a fake-root gap and
    not a defect.
  - Every run also creates `mnt/app/eso/hmi/engdefs/` in the copy, because the snapshot leaves that directory out.
- **H1 (pass): old SDK + new plugin.** The fake root has SDK 0.2.0. `install-file inputs-demo …` →
  `inputs-demo needs plugin api 5; the installed SDK supports 1..4`, exit 1.
  - The tree is unchanged apart from the consumed upload and the `engdefs/` noted above.
  - The bundles file is byte-equal.
- **H2 (pass).**
  - `install-file mibsdk` → `updated mibsdk 0.2.0 -> 0.3.0; reboot the unit (or restart the HMI) to start it`.
  - The five existing plugins → "already installed".
  - inputs-demo → `installed inputs-demo 1.0.0`, `the SDK is not running; it loads at the next boot`.
  - The inputs-demo index is `plug-24=e8995a53ea8ea9f6,24,24` and `plug-24.r=caaf2c12cd95279e,24,23`. Both sha16s
    match the package's PNGs.
  - The bundles file is byte-equal before and after.
  - `sdk status` gives sdk 0.3.0, api 1..5, running no, reboot needed yes, and 6 plugins.

## Install on the unit (no restart)

- The unit was unreachable at first: KL15 kept dropping and `keypanel up` could not raise it. The owner reseated the
  Pi's FTDI serial adapter, and `keypanel up` then brought the unit up. Bridge `true`.
- **State found:** mibsdk 0.2.0 running in **safe mode**, from earlier boots that died before the HMI ran healthy
  (most likely the bench power trouble):
  - `boot.state` had `unhealthyBoots=3` and `wrapBoots=3`;
  - the log had `safeMode=true reason=unhealthy-boots n=3`, `wrapping=off reason=wrap-boots`, and every plugin
    `DISABLED safe mode: not loaded`;
  - `ui system=stock`, `ui car=stock`.

  The live bundles file was byte-equal to `/mnt/persist/mibsdk-bench/bundles.before2`. A host copy was saved.
- `installer/mibsdk-dev install` → `updated mibsdk 0.2.0 -> 0.3.0; reboot the unit (or restart the HMI) to start it`.
- `installer/mibsdk-dev install-plugin inputs-demo-1.0.0.mpk --approve=ui.slot.statusbar` → `installed inputs-demo
  1.0.0`, `reload requested; the SDK did not confirm within 6 s (it loads at the next HMI start)`.
- **O1 (not shown).** The running 0.2.0 runtime was in safe mode, so it loads no plugin at all. The log has
  `dev channel: reload requested for inputs-demo` and nothing after it: no `BLOCKED_INVALID … unsupported api 5`
  line, and the five others stay DISABLED, not ENABLED.
  - The safe-mode path did hold: the new manifest was not loaded and counted no failure.
  - The live `BLOCKED_INVALID` path on a non-safe old runtime would need an extra restart into 0.2.0, which is
    outside the budget. It is not shown in this run.
- **Staged state:** `sdk status` gave sdk 0.3.0, api 1..5, running no, reboot needed yes, ledger 0, and
  `plugin inputs-demo 1.0.0 grants=ui.slot.statusbar`.
  - The unit's inputs-demo index equals H2's.
  - The live bundles file is byte-equal to the copy.
  - `/mnt/app` is read-only and `dev/` is empty.
- `installer/mibsdk-dev safe-mode off` → SAFE_MODE removed, `boot.state` reset to `unhealthyBoots=0`, `wrapBoots=0`.
  This was needed for the required end state. It takes effect at R1.

## R1

- 09:27:04 `installer/mibsdk-dev restart`. The bridge went false at once.
- About 09:28:17: ping lost, because the unit reset (as at earlier runs).
- 09:33:14 `ssh rpi-bench keypanel up`, try 1: `kl15: active`, DHCP kick, `mmx: up (10.20.0.23)`.
- 09:34:21: bridge true. Restarts used: 1.
- A low-fuel dialog was up at boot (`native-inputs-n0-boot-fuel-dialog.jpg`) and was answered "No".

## Checks

- **N0 (pass).** The log has:
  - `safeMode=false … uiEnabled=true` and `ui system=sdk`;
  - `features=[…, ui.icons, ui.inputs, ui.pages, …]`;
  - `ui car=sdk`, `ui status popup 609600 registered`, `ui toast popup 609601 registered`;
  - 6 × `ENABLED`, inputs-demo included;
  - `ui status popup 609600 built` and `boot healthy: 6 plugin(s) enabled for 60000 ms`.

  The log line does not name a slot. Slot 6 is `HqcUiIds.POPUP_TOAST_SLOT = 6` (ZPM slot 12), and N9's frames show the
  toast beside the slot-7 status popup.
- **N1 (pass).** MENU → Plugins (the ring ends with the Plugins wrench, `-n1-ring-plugins.jpg`). The SDK's own plugin
  page (Bike Rack) shows "Enabled" and "Status item" as native check boxes, with no `[on]` text
  (`-n1-sdk-page-checkbox.jpg`). In Inputs Demo:
  - Wi-Fi went off → on → off (`-checkbox.jpg`, `-checkbox-off.jpg`), logged as `item=wifi, requestedOn=true` and
    then `false`;
  - Red and Blue are checked together (`-multi.jpg`).
- **N2 (pass, finding A).** "Locked" is grey. Selecting it changes nothing and no action is logged
  (`-disabled.jpg`).
  - The log has **no** `dropped: disabled` line. The HQC adapter drops a greyed row's press itself:
    `PageScreens.selected` returns at `!e.enabled()`, so the press never reaches `UiHub`, whose drop path logs the
    line.
  - The behaviour is correct. Only the brief's log expectation is wrong for HQC.
- **N3 (pass, finding C).** The Language row opens a sub-page titled "Language" with radio marks, English marked
  (`-choice-sub.jpg`). Deutsch → back on the page, and the row reads Deutsch (`-choice-picked.jpg`), logged as
  `item=lang, value=1`. Opened again, Deutsch is marked (`-choice-sub-again.jpg`). BACK → the page, with no action
  logged (`-choice-back.jpg`).
- **N4 (pass).**
  - "Version … 1.0.0" is right-aligned.
  - The plug icon draws inside its column with no spill.
  - **"Plug value" draws icon, label and value ("on") with no overlap** (`-value-icon.jpg`, `-value-icon-zoom.png`).
  - The 3-column icon+value row stays, and the `PAGE_ICON_VALUE_ROWS` fallback is not needed.
  - The log has `getImagePath 609104 -> ../mibsdk/icons/inputs-demo/e8995a53ea8ea9f6.png`.
- **N5 (pass).** The progress bar is at about 60 % (`-progress-60.jpg`). "Progress +20" moves it to about 80 %
  (`-progress.jpg`). The page updated in place and the focus stayed on the row.
- **N6 (pass).** The Level dropdown is after the "Last:" row (menu items follow the list rows):
  - open: a ✓ on Off (`-dropdown-open.jpg`);
  - knob +2 and select → "Mid" (`-dropdown-mid.jpg`), logged as `item=level, value=2`.
- **N7 (pass).**
  - The Bass row shows its knob preview (`-slider-row.jpg`, `-slider-row-zoom.png`). Select → the knob screen titled
    "Bass", with "-6"/"+6" labels and the pointer at the top (`-slider-open.jpg`).
  - Rotate +3 → the pointer 3 ticks on, logged as `value=3` (`-slider-plus3.jpg`).
  - Rotate +10 and at once −1 → logged as `value=6`, then `value=5` (same ms), with the pointer one tick before the +6
    end (`-slider-end.jpg`). **F18: no step lost.**
  - BACK → the page. The Bass preview points at +5 and the Volume preview at 0 (`-slider-back.jpg`,
    `-slider-back-zoom.png`).
  - Volume: "0"/"10" labels and 6 ticks (0, 2, 4, 6, 8, 10), with the pointer at 0 (`-slider-vol-open.jpg`). Rotate +2
    → logged as `value=4`, with the pointer 2 ticks on (`-slider-vol.jpg`). Then BACK.
- **N8 (pass).**
  - **`setAutoWrap(0)` wraps:** the long text shows on two centred lines (`-confirm-wrap.jpg`).
  - No → `value=0`.
  - "Confirm 2 lines" shows "First line" and "Second line" (`-confirm-2lines.jpg`); Yes → `value=1`.
  - A third confirm, left with BACK → `value=0`.

  Each answer is logged once.
- **N9 (pass, per ruling B).**
  - "Toast" → **one frame shows the toast "Saved" with the plug icon beside the status popup and its plugin items**:
    daemon-status `✕ TS up MQ dn` and hello-menu's icon with "Hello" (`-toast-with-status.jpg`,
    `-toast-with-status-zoom.png`).
  - The toast is on from the first capture (+0.5 s) to +2.7 s, and gone at +3.2 s, while the status line stays
    (`-toast-gone.jpg`). The log has `ui toast popup 609601 built`.
  - "Toast in 5 s", then BACK ×3: this lands on the MENU ring, and the toast shows over it (`-toast-ring.jpg`). A 4th
    BACK does not leave the ring (stock behaviour; finding D).
  - Again with the CAR key: the toast shows over the Vehicle home screen (Audi drive select) at +5.2 to +6.3 s,
    beside the unchanged status line (`-toast-home.jpg`, `-toast-home-zoom.png`).
  - The log has no toast-disabled line and no status-hide hint WARN (ruling 81).
  - The toast icon x (Task 7, 10 px/char) is fine: the icon and "Saved" do not overlap.
  - Not shown: `PDC R OFF`, because the bench has no PDC ECU and the bike rack never arms. `SAFE` needs safe mode.
    Both together with a toast are a car-only check.
- **N10 (pass).**
  - "Name" → the speller titled "Name" (`-speller.jpg`). Select opens the band, with ▼ focused (`-speller-band.jpg`).
  - Two characters typed, with a frame check before each select (`-speller-typed1.jpg`, `-speller-typed2.jpg`).
    - With empty text, ⌫ is disabled, so the first +2 landed on B.
    - After B the band re-lays out (⌫ enabled). The focus stayed on B, and the next +1 landed on C as expected.
  - **BACK with the band open leaves at once (done)**, and does not just close the band. The log has `text=BC`
    (`-speller-back-open.jpg`).
  - The page shows "Name … BC" and "Save name" (`-speller-back.jpg`). Save → toast "Name saved", with no icon
    (`-speller-saved-toast.jpg`, `-zoom.png`).
  - Opened again (text "BC"), the band closed with ▼ (`-speller-band-closed.jpg`), then BACK → done at once with
    `text=BC` kept. No Save row, because the text equals the saved name (`-speller-back-closed.jpg`).
- **N11 (pass).** "Swap kind" twice: "Swap me" goes from a check box to a value row ("item"), then back to a check
  box, in place (`-swap.jpg`, three frames stacked).
- **N12 (pass).** Left via BACK to the MENU ring and the CAR key to home (`-end-home.jpg`). The log was saved as
  `native-inputs-log-r1.txt` (71 lines). Apart from the usual `dirSync=unsupported` line, it has no WARN, ERROR or
  exception.

## Findings

| # | Check | Finding | Follow-up |
|---|---|---|---|
| A | N2 | The HQC adapter filters a greyed row's press before the hub, so no `dropped: disabled` line is logged. The behaviour is correct. | Doc only: the hub's log line covers other adapters and the FakeUi. Optionally log once in `PageScreens.selected`. |
| B | N3, N7, N8, N10 | Back on the page after a sub-screen (choice pick, slider BACK, confirm answer, speller done), the page draws focus on row 0 (Wi-Fi). After the choice pick, the first +5 landed on row 15, so the HMI's knob position (row 10) and the drawn focus disagreed (like the known rotation quirk C5). After "Save name" removed a row, the focus went to row 10. In-place updates (Progress +20, dropdown pick, Swap kind) keep the focus. | **Fixed** in b9cf629 (focus restored by entry key; row ids keep it across row changes) and 7e6be90; verified, see "Focus fix". |
| C | N3 | The choice sub-page opens with focus on row 0, not on the marked option. | **Fixed** in b9cf629; verified, see "Focus fix". |
| D | N9 | BACK on the MENU ring does not go home (stock). | None. To reach home from the ring, use the CAR key. |
| E | O1 | O1 was not shown: the unit started in safe mode (unhealthy/wrap boots = 3), so the old runtime loaded no plugin. | Optional: rerun O1 on a later bench session that starts on a healthy old runtime. H1 covers the mibpkg side. |
| F | N10 | "Name saved" reads as "Namesaved" at capture scale (tight space glyph). | None, unless it shows on the real screen. |

## End state (verified)

- `mibpkg sdk status`:
  - `HMICar=mibsdk.adapter.hqc.ui.SdkCarActivator` and `HMISystem=mibsdk.adapter.hqc.ui.SdkSystemActivator`, so the
    takeover is ACTIVE;
  - sdk 0.3.0, api 1..5, running yes, reboot needed no, ledger 0;
  - all 6 plugins ENABLED: bike-rack, crashy, daemon-status, hello, hello-menu and inputs-demo.
- Safe mode is off (no SAFE_MODE, `unhealthyBoots=0`, `wrapBoots=0`). `dev/` is empty (no forcefail).
- The bundles file is byte-equal to the pre-install copy. `/mnt/app` and `/mnt/system` are read-only.
- Tailscale is running as mmx-bench (`RouteAll: false`, `accept-routes` = 0, `Hostname` empty).
- The carmqtt config is unchanged (sha256 of `state/config/carmqtt/carmqtt.toml` and of the `boot.d` copy equal the
  pre-run snapshot).
- The unit is on its home screen (Vehicle / Audi drive select).

## Focus fix (b9cf629, 7e6be90)

Second session, 2026-10-26, at branch HEAD 7e6be90.

- **b9cf629:** keeps page focus across sub-screens and row changes. It fixes findings B and C.
- **7e6be90:** null-safe entry keys for INFO rows. It also gives keyless rows their own row ids.

Frames are `docs/bench-e2e/native-inputs-focus-*`. Most are strips of 256×120 half-size frames, top to bottom in
time. The log is `native-inputs-focus-log.txt`.

**Overall: pass, checks 1–9 of the focus-fix report, and the N1/N3/N7/N8/N10 smoke.** One restart was used.

- Every focus check was made with single `rot ±1` steps, with a frame after each one.
- **No step landed off by one in this session.** That covers the 20 single steps up the whole page
  (`-9-single-steps-up.jpg`), including the step onto the greyed Locked row.

### Build, dry run, install

- **Build:** `./gradlew :dist:mibpkgPackage -Pmibsdk.version=0.3.0+bench.1 -Pmibsdk.mibpkg=<host mibpkg 0.9.1>`. The
  version is set only by `-P`, the tree stayed clean, and nothing was committed for it.
  - `mibsdk-0.3.0+bench.1.mpk` is 697513 bytes, api 5, min_api 1.
  - `mibsdk-adapter-hqc.jar` is 187932 bytes (it has `PageRow.rowId`/`keylessId`). The runtime and retrotranslator
    jars are unchanged.
- **Fresh fake root** (a `pax -w` copy, same set as above):
  - `updated mibsdk 0.3.0 -> 0.3.0+bench.1; reboot …`;
  - bundles byte-equal before and after;
  - the installed adapter jar equals the package's;
  - the inputs-demo index is unchanged.
- **Unit:** `installer/mibsdk-dev install` gave the same message, with sdk 0.3.0+bench.1, running no, reboot needed
  yes and ledger 0.
  - The bundles file is equal to the pre-run copy, and the live adapter jar equals the package's.
  - `/mnt/app` is read-only and `dev/` is empty.
  - inputs-demo 1.0.0 was left as installed.
- **R1:** 10:45:34 `mibsdk-dev restart`; the unit reset (ping lost at about 10:46:50). 10:52:22 `keypanel up`, try 1:
  DHCP kick via RCC, so the serial path worked, then `mmx: up`; bridge true at 10:53:29. Restarts used: 1.
- **Log:** `ui system=sdk`, `ui car=sdk`, 609600 and 609601 registered, 609600 built, 6 × ENABLED, `boot healthy: 6
  plugin(s)`.

The plugin restarted, so its in-memory state (check boxes, language) was reset. Stored values (name `BC`, level Mid,
Bass +5, Volume 4) came back from storage.

### Checks

1. **Fresh open (pass).** MENU → Plugins → Inputs Demo → "Inputs >" opens on row 0 (Wi-Fi), and `rot +1` goes to Red
   (`-1-fresh-open.jpg`). inputs-demo has no Car-drawer row, so it was opened from MENU.
2. **Choice (pass).**
   - Single steps to Language (`-2-to-language.jpg`).
   - Select: the sub-page opens **on the marked English**. `rot +1` goes to Deutsch.
   - The pick puts the page back with **the cursor on Language** (Deutsch). `rot +1` goes to **Version**, the next row,
     so the knob and the drawing agree (`-2-choice-pick.jpg`).
   - `rot −1` goes to Language. Opened again, it opens **on Deutsch**. BACK puts the cursor on Language, and `rot +1`
     goes to Version (`-2-choice-reopen-back.jpg`).
3. **Slider (pass).**
   - Last → Level → Bass by single steps. Open (pointer at +5), BACK: **the cursor is on Bass**. `rot +1` goes to
     Volume (`-3-bass-back.jpg`).
   - Volume: open, `rot +1` logs `value=6`. BACK puts the cursor on Volume, and `rot −1` goes to Bass
     (`-3-volume-back.jpg`).
   - Both slider BACKs return to the page, past its keyless "Last:" INFO row (the 7e6be90 case).
4. **Confirm (pass).**
   - Confirm → Yes (`value=1`) puts the cursor back on Confirm, and `rot +1` goes to Confirm 2 lines
     (`-4-confirm-yes.jpg`).
   - Confirm 2 lines → No (`value=0`) puts the cursor back on Confirm 2 lines, and `rot +1` goes to Toast
     (`-4-confirm-no.jpg`).
5. **Speller (pass).**
   - Name ("BC") → band → A → BACK. The page adds "Save name" and **the cursor is on Name (BCA)**. `rot +1` goes to
     Save name (`-5-speller-back.jpg`).
   - Select Save name: the toast "Name saved" shows, and the Save row is removed in place.
   - The cursor goes to the row that takes its place, the keyless "Last:" INFO row (stock's nearest). `rot −1` goes to
     Name, then `rot +1` goes back to Last (`-5-save-row-removed.jpg`).
6. **Regressions (pass).**
   - These keep the cursor on their row, and `rot +1` then goes to the next row:
     - Progress +20, from 60 to 80 (`-6-progress.jpg`);
     - Swap kind twice, Swap me back to a check box (`-6-swap.jpg`);
     - the Level dropdown, which opens with ✓ on Mid; High picked (`-6-dropdown.jpg`).
   - BACK from the Inputs page (a LINK child) returns to the SDK plugin page with the cursor on "Inputs >", and
     `rot −1` goes one row up (`-6-link-back.jpg`).
   - The row kinds draw as in N1–N4.
7. **Reconnect (pass).** With the cursor on Toast, a toast came and went. The cursor stayed on Toast, and `rot +1`
   went to Toast in 5 s (`-7-toast.jpg`). The stock partial-popup reinit to row 0 was not seen.
8. **Plugins menu (pass).**
   - MENU → Plugins, then single steps to Hello Menu (`-8-plugins-steps.jpg`). Open its page, then BACK: on
     **Plugins with the cursor on Hello Menu** (`-8-plugin-page-back.jpg`).
   - `rot −1` goes to Daemon Status, `+1` to Hello Menu, `+1` to Hello (`-8-plugins-rot.jpg`).
   - Selecting Hello opens its page, and BACK puts the cursor on Hello (`-8-other-plugin-back.jpg`).
   - From the Inputs page, BACK twice lands on Plugins with the cursor on Inputs Demo (`-6-link-back.jpg`, last
     frame).
   - The slider BACK past "Last:" is covered in check 3.
9. **Log (pass).** The log has 58 lines. Apart from the usual `dirSync=unsupported` STATUS line, there is no
   `ui page focus failed`, `ui page rows failed`, WARN, ERROR or exception line. The unit is left on its home screen
   (`-end-home.jpg`).

**Smoke (pass):**
- N1: Wi-Fi on and off as native boxes (`-9-n1-checkbox.jpg`).
- N3: check 2.
- N7: check 3. Volume +1 step logs `value=6`.
- N8: check 4. Answers 1 and 0, each logged once.
- N10: check 5. BACK with the band open = done, `text=BCA`. Saving it kept the name BCA.

### End state (verified)

- `mibpkg sdk status`:
  - `HMICar=…SdkCarActivator` and `HMISystem=…SdkSystemActivator`, so the takeover is ACTIVE;
  - sdk 0.3.0+bench.1, api 1..5, running yes, reboot needed no, ledger 0;
  - 6 plugins ENABLED.
- Safe mode is off (`unhealthyBoots=0`, `wrapBoots=0`, no SAFE_MODE) and `dev/` is empty.
- The bundles file is unchanged. `/mnt/app` and `/mnt/system` are read-only.
- Tailscale is running as mmx-bench (`RouteAll: false`).
- The carmqtt config sha is unchanged.
- The unit is on its home screen.
- The stored inputs-demo values are now name `BCA` and level High.
