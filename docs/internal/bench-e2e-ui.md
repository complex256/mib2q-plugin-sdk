> **Internal engineering record.** This page is kept for history and may be out of date. See [the internal docs index](README.md).

# Bench end-to-end: SDK UI (Plan 2, Task 19)

Run 2026-10-24 on the bench MHI2Q (10.20.0.23, `HMI_AU_MIB2_HQC_NAR_G22_C10_180803`). The unit is owned by
mibpkg 0.8.1 (`/tmp/mibpkg`). At the start, `mibsdk` 0.1.0 (built from `feat/ui` eca752e, code-identical to
96f4990) and the plugin packages `hello`, `bike-rack`, `crashy`, `hello-menu` and `daemon-status` were installed,
ENABLED and granted (Plan 3 Task 16, `docs/bench-e2e.md`). The plan's `mibsdk-dev install` / `grant` / `push` steps
predate mibpkg owning the SDK. This run replaced them with checks of the existing install and installs through
mibpkg. Screens were driven and captured with mib-keypanel (`docs/bench-e2e/ui-*.jpg`).

**Overall: pass, with three fixes made during the run (deployed to the unit and re-checked) and one open UX
difference.** The fixes are U1 (the Plugins menu showed no state words), U2 (the boot's feature set was never logged)
and U3 (a racy host test). The open difference is U4: BACK from the top SDK page lands on the Car screen under the
drawer, but the stock HMI reopens the drawer. Four HMI restarts were needed. Three came back on their own after
about 2.5 minutes. The fourth needed the `keypanel up` DHCP kick after about 8 minutes (as in P3-F7).

## Findings

| # | Finding | Status |
|---|---------|--------|
| U1 | **The Plugins menu showed no state words.** `PluginsMenu` puts `On`/`Off`/`Quarantined`/… in each plugin LINK's value, but `UiPageText.line` drew a LINK as `label >` and dropped the value (`ui-04-plugins-menu-no-state-words.jpg`). The host tests read the value from the snapshot, so they passed. | **fixed d48130e** (`UiPageTextTest.linkShowsItsValue` first). A LINK with a value now reads `label: value >`. Verified on the unit: `Bike Rack: On >` … (`ui-25`), and in safe mode `Hello: Off >` (`ui-23`) |
| U2 | **The boot's feature set was never logged.** `STATUS mibsdk features=[…]` was logged only when the set changed after start, so the Step 2 log line `features=[…slot.carmenu, slot.statusbar, ui.pages…]` could not appear. | **fixed 98badab** (`SdkRuntimeBootTest.initialFeaturesAreLoggedOnceAtStart` first). Verified on the unit: `STATUS mibsdk features=[hook.parking, slot.carmenu, slot.statusbar, ui.pages, vehicle.ignition, vehicle.parking]`; in safe mode `[slot.carmenu, slot.statusbar, ui.pages, vehicle.ignition, vehicle.parking]`; under the latch `[vehicle.ignition, vehicle.parking]` |
| U3 | `SdkRuntimeBootTest.unhealthyBootsEnterSafeModeAndStayUnhealthy` asserted `statuses().isEmpty()` straight after start. That races the safe-mode listing, which adds a DISABLED entry per plugin for the Plugins menu, and it failed 1 run in 5. | **fixed d48130e** (it now asserts that no plugin was loaded; 6/6 runs passed) |
| U4 | **BACK from the top SDK page does not reopen the Car drawer.** It shows the stock screen recorded under the drawer, with the drawer closed: the Car main screen (drive select, `ui-11`) or Vehicle settings when that was underneath. The stock HMI's BACK from a drawer entry reopens the drawer with that row focused (`ui-12`, three frames: drawer → Vehicle settings → BACK). This is the behaviour S2a accepted ("BACK lands on the screen it came from"); the brief's "BACK to the drawer" expected the stock behaviour. A related stock difference: after BACK from a child page, the Plugins list puts the focus back on its first row. | open (UX; Plan 4 or a follow-up) |
| U5 | The SDK page's marker line reads `SAFE_MODE marker set: clear it with mibsdk-dev safe-mode o…` (truncated on screen). On a mibpkg-owned unit the command is `mibpkg sdk safe-mode off`, and `mibsdk-dev` delegates to it. | recorded (wording) |
| U6 | `tailscale.sh start` on a first deploy prints five `cannot open /tmp/<binary>` lines from `ts_install` (no copy in `/tmp` yet). It then starts normally. | recorded (mib2q-tailscale, cosmetic) |
| U7 | `slay -s11 j9` reset the unit each time, as before (F3/P3-F7). The unit was down for about 1.5 min, and three of the four restarts came back on their own. The fourth (14:06) was unreachable for 8 min until `keypanel up` kicked DHCP over the RCC. The j9 PID did not change during Step 8, so no restart was missed. | recorded (bench) |

## Builds and deploys

- **Host:** `feat/ui` b68b8c0, then 98badab and d48130e. `./gradlew check` passed before each commit (663 JVM test
  cases, 0 failures at d48130e).
- **crashy 1.1.0:** api 2, `permissions: ["ui.slot.statusbar"]`, new mode `ui-throw`; built with
  `./gradlew :plugins:crashy:mibPackage -Pmibsdk.mibpkg=<mibpkg 0.8.1 from feat/mibsdk-packages 729bd83>`. Uploaded
  to `/tmp/mibpkg.upload.<id>.mpk`, then `/tmp/mibpkg install-file crashy <id> -`. Without approval: exit 3,
  `Show an item in the status bar (ui.slot.statusbar)`, `approve with: --approve=ui.slot.statusbar`. With
  `--approve=ui.slot.statusbar`: `updated crashy 1.0.0 -> 1.1.0`, `loaded: ENABLED enabled`, grants
  `crashy=ui.slot.statusbar`.
- **mibsdk 0.1.0+bench.1:** U1/U2 needed a new SDK on the unit. It was built from d48130e with
  `./gradlew :dist:mibpkgPackage -Pmibsdk.version=0.1.0+bench.1` (package version only, as in P3-F3; the repo stays
  at 0.1.0) and installed with `install-file mibsdk`: `updated mibsdk 0.1.0 -> 0.1.0+bench.1; reboot the unit (or
  restart the HMI)`. The runtime jar on the unit is 227215 bytes (`dist/build/dist`). The adapter jar did not change.
  The restart for Step 9.1 started it.
- **carmqtt (Task 14):** `mib2q-carmqtt` `feat/status-endpoint` f113e79, `scripts/build.sh` (fresh cross-build; the
  old binary in `target/` was from 2026-10-07, before the endpoint), then `scripts/package.sh` with the same host
  mibpkg → `carmqtt-0.2.2.mpk` (468719 bytes). `install-file carmqtt` → `installed carmqtt 0.2.2; needs config:
  carmqtt.toml`. A bench config went on with `gem config-set carmqtt 0 <id>`: broker `127.0.0.1:1883` (no broker
  on the bench), `[sources.ubx] enabled = false` (keeps the RCC console free), ExLAP on, `[status] port = 19851`.
  The service waited the full 5 min for the tailnet (`started without tailnet after 5m`), then logged
  `status endpoint on 127.0.0.1:19851` and `exlap: authenticated`. The unit has no `nc`, so the endpoint was read
  over an SSH forward: `v=1 daemon=carmqtt version=0.2.2 mqtt=0 last_publish_age_s=-1 signals=0 exlap=1 exlap_err=
  uptime_s=15`.
- **tailscale + tsstatus (Task 15):** `mib2q-tailscale` `feat/tsstatus` 4db4a74. A rebuild of `tsstatus` in the
  project's Docker image gave the same sha256 as `build/tsstatus` (d5c68e21…). `tailscaled`/`tailscale`/`sntp`/
  `libtsclock.so` were already built after the last change to their sources. They were deployed per the README:
  `mount -uw /mnt/app`, copy to `/mnt/app/root/tailscale/`, then `mount -ur` (`/mnt/app` read-only again, checked).
  The `startup.sh` autostart line was **not** added, and no auth key was written. `tailscale.sh start` therefore
  runs it by hand after each reset, and the node stays `NeedsLogin`. Endpoint: `v=1 daemon=tailscale
  state=NeedsLogin ip= tailnet= peers=0 peers_online=0 last_handshake_age_s=-1 age_s=3`.

## Step 1: host prep (pass)

`CrashyUiModeTest.uiThrowQuarantinesAndLeavesStoppedNotice` was written first and failed. `ui-throw` was then
implemented: a page `boom` (Plugins › Crashy › `Crashy >`) whose action listener throws, and a status item rewritten
200 times every 100 ms. `SdkRuntimeLifecycleTest.crashyModesAreContained` now grants crashy `ui.slot.statusbar`,
because its manifest declares it. `./gradlew check` passed. Commit b68b8c0.

## Step 2: install (pass, verified instead of installed)

- `/tmp/mibpkg list`: `bike-rack 1.0.0`, `crashy 1.0.0` (then 1.1.0), `daemon-status 1.0.0`, `hello 1.0.0`,
  `hello-menu 1.0.0`, `mibpkg 0.8.1`, `mibsdk 0.1.0` (then 0.1.0+bench.1), and `carmqtt 0.2.2` after its deploy.
- `/tmp/mibpkg sdk status`: `HMICar=mibsdk.adapter.hqc.ui.SdkCarActivator`, `api: 1..2`, `hmi: … (supported)`. The
  grants match the brief: `hello-menu=ui.slot.carmenu`, `daemon-status=network,ui.slot.statusbar`,
  `bike-rack=ui.slot.carmenu,ui.slot.statusbar,vehicle.parking.write,vehicle.read`, and after the update
  `crashy=ui.slot.statusbar`. Also `ledger: 0 override(s)`.
- Log (first boot): `STATUS mibsdk ui car=sdk`, `runtime started adapter=hqc-nar-g22-c10 safeMode=false`, each
  plugin `ENABLED enabled`. There was no `features=` line (U2). After the U2 fix, the restart for Step 9.2 logged
  `STATUS mibsdk features=[hook.parking, slot.carmenu, slot.statusbar, ui.pages, vehicle.ignition, vehicle.parking]`,
  `ui car=sdk`, all 5 `ENABLED`, and `boot healthy: 5 plugin(s) enabled for 60000 ms`.

## Step 3: Car drawer (pass)

`car` → `menu_left`: the top of the drawer matches `docs/research/ui-spike-a-drawer-top.jpg`
(`ui-02-car-drawer-top.jpg`). All five stock rows are there (Audi drive select, Vehicle settings, Driver
assistance, Air conditioning, Service & checks), and below them come `Plugins`, `Bike rack` and `Hello`
(`ui-03-car-drawer-sdk-rows.jpg`). The SDK rows' icon is the blank square for `ROW_BITMAP_SOURCE = -1` (no bitmap
source).

## Step 4: Plugins menu (pass after U1; U4 open)

1. `Plugins` lists Hello, Bike Rack, Crashy, Hello Menu, Daemon Status and SDK: five plugins (the brief's four plus
   `hello`) and the SDK page. **No state words** were shown (U1, `ui-04`). After the fix: `Bike Rack: On >`,
   `Crashy: On >`, … (`ui-25-plugins-menu-state-words.jpg`).
2. `Daemon Status` → `v1.0.0 by complex256`, `[on] Enabled`, `Permissions: network, ui.slot.statusbar`,
   `Daemons >` (`ui-06`).
3. Hello Menu › `Enabled` → `[off] Enabled`, and the `Hello >` link went away (`ui-09`). Log `Plugins menu:
   hello-menu off`, `STATUS hello-menu DISABLED off in Plugins menu`, `plugins.properties` `hello-menu.off=true`. The
   next view of the drawer had no `Hello` row (`ui-10`). The 2 s bound could not be timed on screen, because the
   drawer is not visible from the Plugins page; the row was gone at the first drawer view, about 5 s later.
4. Toggled back on: `[on] Enabled`, `STATUS hello-menu ENABLED enabled`, and the `Hello` drawer row was back.
5. BACK: Hello Menu → Plugins → the stock Car screen with the drawer closed (`ui-11`), not the drawer (U4).

The SDK page shows `API 2 · adapter hqc-nar-g22-c10`, `Safe mode: off`, `Boot guard: ok`, and no reset item
(`ui-05`).

## Step 5: hello-menu (pass; BACK per U4)

`Hello` drawer row → `Apple`, `Pear`, `Plum`, `Selected: <none>` (`ui-13`). Selecting `Pear` → `Selected: Pear`
(`ui-14`). BACK returned to the stock screen under the drawer (Vehicle settings in this run, U4). The
selection is kept in storage (`data/hello-menu.properties` `last=pear` at the end of the run), and the page still
showed `Selected: Pear` after crashy's quarantine.

## Step 6: daemon-status (pass)

- Status line (popup 65, top right): `TS dn MQ dn`. tailscale is `NeedsLogin` (no auth key) and carmqtt has
  `mqtt=0` (no broker on the bench). Both daemons ran and answered; neither showed `?`.
- `Daemons` page: `Tailscale: NeedsLogin`, `Tailnet:`, `Peers: 0/0`, `carmqtt: MQTT off, ExLAP on`, `Last publish:
  never`, `Refresh now` (`ui-07`).
- I stopped tailscaled (`tailscale.sh stop`, which also stops tsstatus) and pressed `Refresh now` at 13:34:38. At
  13:34:41 the page showed `Tailscale: ?` and the status line `TS ? MQ dn` (`ui-08`), so it updated within 3 s.
  Restarted afterwards.

## Step 7: bike-rack (pass)

`[off] Rack fitted (rear PDC tones off)`, `Rear tones: normal`. Toggling it on gave `Rear tones: waiting for parking
system`, unchanged 5 s later (`ui-15`; log `toggle requested: on`, `waiting for parking value`; F7, no PDC ECU). The
status line stayed `TS dn MQ dn` (no `RACK`, no `PDC R OFF`) and the ledger stayed empty. Toggled off → `Rear tones:
normal` (`ui-16`). The real override path remains a car check (`docs/car-readiness.md`, F7).

## Step 8: forced failure with UI (pass)

- j9 PID before: 712752.
- Plugins › Crashy › `Enabled` off, `data/crashy.properties` = `mode=ui-throw`, `Enabled` on. Log `INFO crashy
  mode=ui-throw`, `STATUS crashy ENABLED enabled`. The status line gained the rewritten item (`TS dn MQ dn C801`,
  then `C400`, …; `ui-17`), and the entry gained the `Crashy >` link.
- `Crashy >` → `Throw`, `Each press throws; the third quarantines Crashy` (`ui-18`). Three presses →
  `STATUS crashy QUARANTINED 3 failures within 60000 ms, last: java.lang.IllegalStateException: crashy: ui-throw boom
  …`. The open page became `Crashy stopped: 3 failures within 60000 ms, last: java.lan…` (`ui-19`), and the `C…` item
  left the status line.
- BACK → Crashy entry with `Stopped: 3 failures …` (`ui-20`). With tailscaled stopped again, the status line moved to
  `TS ? MQ dn` within 17 s (15 s poll), so it still updates. Keypanel taps changed screens within 1.5 s. The Car
  drawer and the Hello page (`Selected: Pear`) still worked.
- j9 PID after: 712752 (unchanged).
- Afterwards crashy's mode was set back to empty (idle) so later boots are clean.

## Step 9: indicators (pass)

1. `mibpkg sdk safe-mode on` (`safe mode marker set`) + restart at 13:51 (the unit was back at 13:54 on its own;
   `keypanel up` after). Log `WARN mibsdk safeMode=true reason=marker`, `runtime started … safeMode=true`, each
   plugin `DISABLED safe mode: not loaded`, `ui car=sdk`. The status line reads `SAFE` (`ui-21`), and the drawer's
   only SDK row reads `Plugins (safe mode)` (`ui-22`). The Plugins page header reads `SDK safe mode: plugins are off
   (marker)`, then each plugin is listed as `Off` (`ui-23`). The SDK page shows `Safe mode: on (marker)` and
   `SAFE_MODE marker set: clear it with mibsdk-dev safe-mo…` (U5), and has no reset item (`ui-24`).
2. `mibpkg sdk safe-mode off` (marker removed, boot.state reset) + restart at 13:57 (the unit was back at 14:00;
   `keypanel up`). Normal boot, all 5 ENABLED, `boot healthy`. The Plugins menu has state words (`ui-25`).
3. Latch: `mibpkg sdk boot-state set-wrap 3` (`wrapBoots=3`) + restart at 14:01 (the unit was back at 14:04;
   `keypanel up`). Log `WARN … wrapping=off reason=wrap-boots`, `uiEnabled=false`,
   `STATUS mibsdk ui car=stock reason=wrap-boots`, `features=[vehicle.ignition, vehicle.parking]`. The plugins still
   ran, with UI handles inert. On screen the Car drawer is stock with no Plugins row (`ui-27`), and popup 65 shows no
   SDK text (`ui-26`). Then `mibpkg sdk safe-mode off` (boot.state `unhealthyBoots=0`, `wrapBoots=0`) + restart at
   14:06. The unit stayed off the network until the first `keypanel up` at 14:14 kicked DHCP (U7); it answered at
   14:15. The UI came back: `ui car=sdk`, the full features line, 5 ENABLED, `boot healthy`, and the drawer with
   Plugins, Bike rack and Hello (`ui-28`).

The safe-mode and boot-state commands were `mibpkg sdk …` on the unit, which is what `mibsdk-dev safe-mode` and
`boot-state` call (`installer/mibsdk-dev`). The restart was `slay -s11 j9`, which is what `mibsdk-dev restart` runs.

## Step 10: uninstall/reinstall (not re-run)

This step was covered by mibpkg on code-identical SDK: Plan 3 Task 16, Steps 5–6 in `docs/bench-e2e.md`. After
`mibsdk-dev uninstall` the live bundles file was `cmp`-equal to the stock copy (10171 bytes, `-rwxrwxrwx`,
`HMICar=de.audi.tghu.car.hmi.Activator`). The stock home screen and the stock Car drawer with no Plugins row
followed, and then the reinstall. Not observed separately: popup 65 hidden after the uninstall.

## Task 11 deferred bench checks

| Check | Result |
|-------|--------|
| A block that outlasts `platform.stop()` leaves an inert page until a press or hardkey | **bench-impossible**: a block means RVC/PDC is active, and the bench has no reverse camera or PDC ECU |
| An orphan page is re-adopted by its old owner on reconnect | **bench-impossible**: needs the runtime to restart inside one HMI life with an SDK page shown, and the unit has no verb for that (every restart here reset the unit) |
| The new renderer's orphan check never runs if it publishes no pages | **bench-impossible**: same precondition as above |
| The Car 600044 fallback bypasses CarSMM | **not reached**: the fallback runs only when no stock screen was saved. Every BACK in this run had a saved screen, and the log never shows `ui page: no saved stock screen`. Related: BACK always went to the saved stock screen (U4) |
| Status line and pages under RVC/PDC (popup 65 never covers the camera or PDC; `PDC R OFF`) | **bench-impossible**; added to `docs/car-readiness.md` as car-only checks |

## Bench state at the end

- mibpkg 0.8.1 supervises. `mibsdk` is `0.1.0+bench.1`, built from `feat/ui` d48130e.
- Plugin packages: `hello`, `bike-rack`, `crashy` 1.1.0 (mode empty, idle), `hello-menu` and `daemon-status`, all
  ENABLED with the brief's grants. Safe mode is off, `boot.state` has `unhealthyBoots=0` and `wrapBoots=0`, and the
  ledger is empty.
- `carmqtt` 0.2.2 is installed as a mibpkg package with the bench config (no broker) and is running.
- tailscale and tsstatus are in `/mnt/app/root/tailscale/`, with no autostart line and no auth key. They run until
  the next reset (`tailscale.sh start` brings them back). The status line reads `TS dn MQ dn`.
- `/mnt/app` is mounted read-only. kl15 is active. The unit is on its home screen with drive select on Dynamic
  (`ui-29`).

## Status line on the bench

Run 2026-10-24 on the bench (10.20.0.23, same HMI), for `feat/status-line` (447ce0d) and mibpkg
`feat/statusline-cleanup` (620b00f). Frames: `docs/bench-e2e/ui-status-*.jpg` (popup 65 is the text at the top
right of each frame).

**Overall: pass. No defects found; two cosmetic notes (S1, S2). Three HMI restarts. The first came back on its
own after about 1.5 minutes. The second and third each needed one `keypanel up` (about 10 and 6 minutes).**

| # | Finding | Status |
|---|---------|--------|
| S1 | "Move status item earlier" on the item that is already first logs `status item moved earlier, order now [...]` with an unchanged order. `StatusPrefs.move` returns false and does nothing, but `SdkRuntime` logs without checking the result. | recorded (log wording only) |
| S2 | After `mibsdk-dev remove statustest`, the Plugins list still showed `Status Test: Off >` until the menu was rebuilt (P3-F11, unchanged by this branch). | recorded (known) |

### Builds and deploys

- **mibpkg `0.8.1+bench.1`** (package version only; the binary is 0.8.1 at 620b00f): `scripts/build.sh` (QNX
  cross-build in `qnx65-armv7-toolchain:8.5-rust`, Docker Desktop context), `scripts/bundle.sh`, then
  `out/stage/mibpkg` was rebuilt as a package with the version `0.8.1+bench.1` in the staged manifest (the repo
  version stays 0.8.1). `install-file mibpkg` → `updated mibpkg 0.8.1 -> 0.8.1+bench.1; mibpkg restarts itself`.
  The new supervisor was up within 15 s.
- **mibsdk `0.1.0+bench.2`** from 447ce0d: `./gradlew :dist:mibpkgPackage -Pmibsdk.version=0.1.0+bench.2
  -Pmibsdk.mibpkg=<host mibpkg from 620b00f>`. Then `mibsdk-dev install`: `updated mibsdk 0.1.0+bench.1 ->
  0.1.0+bench.2`. On the unit the runtime jar is 236457 bytes (`dist/build/dist`).
- **Throwaway plugin `statustest` 0.0.1** (never committed, removed at the end): `ui.slot.statusbar`, a single
  fixed item `LONGITEM-ABCDEFGHIJKLMN` (23 chars). Installed with `mibsdk-dev install-plugin
  --approve=ui.slot.statusbar`.
- `crashy` mode `ui-throw` (`data/crashy.properties`) gave a third item, `C<n>` (rewritten in bursts, 2–4 chars).
  `daemon-status` showed `TS ? MQ ?`, later `TS ? MQ dn`. tailscale was not started after the resets, and carmqtt's
  endpoint came up after its 5-minute tailnet wait.

Each expected line below was computed on the host with `StatusLine.compose` (the branch's runtime classes,
`MAX_LINE_CHARS` 24) for the texts seen in the frame. Each one matched the frame character for character.

### Fair share with three items (pass)

After restart 1, the log showed `runtime started … safeMode=false` and all six plugins `ENABLED`. The default order is
by plugin id: crashy, daemon-status, statustest.

| Frame | Texts | Expected = on screen |
|-------|-------|----------------------|
| `ui-status-01` | `C281`, `TS ? MQ ?`, `LONGITEM-ABCDEFGHIJKLMN` | `C281 TS ? MQ ? LONGIT...` |

The short items keep their full text, and the long one gets the rest of the line, cut with `...`. None is pushed
off. Before this branch, the long item placed first would have taken the whole line.

### Plugins menu rows: toggle and move (pass)

Plugins › Status Test shows `[on] Status item (4 of 4)`, `Move status item earlier` and `Move status item later`
(`ui-status-02`). All four eligible plugins count: bike-rack, crashy, daemon-status and statustest (bike-rack has
no item now). The rows are reachable with the knob. Each frame below was captured 1.8 s after the press.

| Step | Row | Status line | `statusline.properties` | Frame |
|------|-----|-------------|-------------------------|-------|
| hide | `[off] Status item (4 of 4)` | `C212 TS ? MQ ?` (item gone) | `statustest.hidden=true` | `ui-status-03` |
| show | `[on] … (4 of 4)` | `C462 TS ? MQ ? LONGIT...` | empty | `ui-status-04` |
| earlier | `(3 of 4)` | `C237 LONGIT... TS ? MQ ?` | `order=bike-rack,crashy,statustest,daemon-status` | `ui-status-05` |
| earlier | `(2 of 4)` | `LONGIT... C608 TS ? MQ ?` | `order=bike-rack,statustest,crashy,daemon-status` | `ui-status-06` |
| later | `(3 of 4)` | `C399 LONGIT... TS ? MQ ?` | `order=bike-rack,crashy,statustest,daemon-status` | `ui-status-07` |
| earlier ×2 | `(1 of 4)` | `LONGIT... C800 TS ? MQ ?` | `order=statustest,bike-rack,crashy,daemon-status` | `ui-status-08` |
| earlier (already first) | `(1 of 4)` | unchanged | unchanged (S1) | — |
| hide | `[off] Status item (1 of 4)` | `C1 TS ? MQ ?` | `order=statustest,…` + `statustest.hidden=true` | `ui-status-09` |

Each press logged `INFO mibsdk Plugins menu: statustest status item hidden|shown|moved …`. statustest stayed
`ENABLED` throughout.

### Persistence and safe mode (pass)

- `mibsdk-dev safe-mode on` + restart 2. The log had `WARN mibsdk safeMode=true reason=marker` and every plugin
  `DISABLED safe mode: not loaded`. Popup 65 reads `SAFE` alone (`ui-status-10`). No plugin runs in safe mode, so
  no item can show next to `SAFE`. SAFE with items is covered by the host composition
  (`SAFE C462 TS ? MQ ?`). The file kept both lines. Plugins › Status Test shows `Safe mode: changes apply after it
  ends` and `[off] Status item (1 of 4)` (`ui-status-11`), so the hidden flag and the order are both read back
  after the restart.
- `mibsdk-dev safe-mode off` + restart 3: normal boot, all six plugins `ENABLED`. Popup 65 shows `C837 TS ? MQ dn`
  with statustest still hidden (`ui-status-12`). After `[on]`, it reads `LONGIT... C411 TS ? M...`, with statustest
  first as saved (`ui-status-13`; expected `LONGIT... C411 TS ? M...`).

### mibpkg remove and reinstall in one boot (pass)

With statustest hidden and first in `order=` again, `mibsdk-dev remove statustest` printed:

```
stopping statustest and removing its files
removed statustest: plugins/statustest.jar and its grants line and its status-line settings; reload requested
statustest has no overrides in the ledger
```

`sed -n 1,20p /mnt/persist/mibsdk/statusline.properties` then printed `order=bike-rack,crashy,daemon-status` only
(no `statustest.hidden`, and statustest is gone from `order=`). Popup 65 showed `C3 TS ? MQ dn` (`ui-status-14`).
`install-plugin` of the same package (same boot) gave `loaded: ENABLED enabled`, and the file did not change. The
item came back visible in the default place, last by id: `C42 TS ? MQ dn LONGIT...` (`ui-status-15`; expected
the same). A second `remove` at the end printed no "status-line settings" clause, because there was nothing to
drop.

### Bench state at the end

- mibpkg `0.8.1+bench.1` (620b00f) supervises. `mibsdk` is `0.1.0+bench.2`, built from `feat/status-line` 447ce0d.
- `hello`, `bike-rack`, `crashy` 1.1.0 (mode empty, idle; reloaded through Plugins › Crashy › Enabled off/on),
  `hello-menu` and `daemon-status` are installed and ENABLED with the brief's grants. `statustest` is removed (not
  in `mibpkg list` or `grants.properties`). `carmqtt` 0.2.2 is unchanged.
- Safe mode is off (no `SAFE_MODE`). `boot.state` has `unhealthyBoots=0` and `wrapBoots=0`.
  `statusline.properties` holds `order=bike-rack,crashy,daemon-status`, which is the same as the default id order.
- The unit is on its home screen (drive select). Popup 65 reads `TS ? MQ dn` (`ui-status-16`). tailscale was not
  restarted after the resets.
