> **Internal engineering record.** This page is kept for history and may be out of date. See [the internal docs index](README.md).

# Bench end-to-end: release 0.2 (mibsdk 0.2.0, bike-rack 1.1.0)

Run 2026-10-25 on the bench MHI2Q (10.20.0.23, `HMI_AU_MIB2_HQC_NAR_G22_C10_180803`), branch `release/0.2` at
3097b00. The checklist is the release report's "Bench checklist" (icon I1–I4, alignment B9-1..B9-6). Frames are
`docs/bench-e2e/release-0.2-*.jpg` (512×240 keypanel captures, half the 1024×480 screen in each axis) and
`*-zoom.png` host crops of the status band (rows 0–19, x 240–511, upscaled ×4). The R1 log is
`docs/bench-e2e/release-0.2-log-r1.txt`.

**Overall: pass for I1, I2, I4, B9-1, B9-3 and B9-5. I3 and B9-4 cannot be shown on the bench (no PDC ECU, no
safe mode without a second restart). B9-2 is partial: the bench never shows more than two items. B9-6 is
measured: 13 capture px, about 26 screen px.** One restart (R1) was used. The unit ends in the required state.

## Builds and dry run

- Host mibpkg 0.9.1, built fresh from mibpkg main a0a5e49 (the repo's `target/` binary was not used).
- From release/0.2 at 3097b00 with no version override (only `-Pmibsdk.mibpkg=<host mibpkg>`):
  `./gradlew :dist:mibpkgPackage :plugins:<each>:mibPackage`. Outputs:

  | Package | Bytes | Notes |
  |---|---|---|
  | `mibsdk-0.2.0.mpk` | 624387 | api 4, min_api 1, 24 SDK icons |
  | `bike-rack-1.1.0.mpk` | 28019 | api 3, icons `bike`, `bike-20`, plus `icons/bike.r.png` |
  | `daemon-status-1.1.0.mpk` | 6465 | api 3 |
  | `hello-menu-1.2.0.mpk` | 6977 | api 4 |
  | `crashy-1.1.0.mpk` | 6136 | api 2 |
  | `hello-1.0.0.mpk` | 2400 | api 1 |

  The working tree stayed clean.
- **Fake root (pass).** A `pax -w` copy of the unit: the mibpkg tree, `lsd/mibsdk`, `lsd/jars`, the bundles file,
  `DSITracer.jar`, `/mnt/persist/mibsdk`, `/mnt/app/img_ver.txt`, `/ifs/version_info.txt` and `startup.sh`. Each
  package was copied to `<root>/tmp/mibpkg.upload.<id>.mpk` and installed with
  `mibpkg --root <root> install-file <pkg> <id> -`. The results:
  - mibsdk: `updated mibsdk 0.1.0+bench.6 -> 0.2.0; reboot …`
  - bike-rack: 1.0.0 -> 1.1.0
  - daemon-status: 1.0.1-bench.1 -> 1.1.0
  - hello-menu 1.2.0, crashy 1.1.0 and hello 1.0.0: "already installed"

  The bundles file was byte-equal before and after. The bike-rack index is
  `bike=2ccc1952f4d6b173,72,72`, `bike-20=a0dee0389ab98d1c,20,20`, `bike.r=4abcfb05951cb8fa,72,68`. Each sha16 matches
  `sha256(icons/<name>.png)` in the package.
  - Fake-root note: mibpkg's unit detection reads `/mnt/app/img_ver.txt`. Without that file in the copy, every
    install refuses with "not compatible with this unit: unknown (unknown firmware)", and nothing changes.

## Install on the unit (no restart)

- `installer/mibsdk-dev install` gave `updated mibsdk 0.1.0+bench.6 -> 0.2.0; reboot the unit (or restart the HMI)
  to start it`.
- `installer/mibsdk-dev install-plugin bike-rack-1.1.0.mpk` gave `updated bike-rack 1.0.0 -> 1.1.0`, then `loaded:
  ENABLED` on the still-running bench.6 runtime (api 1..4). mibpkg did not ask for approval, because the permissions
  are unchanged.
- `daemon-status` was still the bench build `1.0.1-bench.1`, so it was replaced by its release build:
  `updated daemon-status 1.0.1-bench.1 -> 1.1.0`, `loaded: ENABLED`.
- Before the restart:
  - `sdk status` showed `sdk: 0.2.0`, `api: 1..4`, `running: no`, `reboot needed: yes`, and ledger 0.
  - The live bundles file was byte-equal to the copy taken before the install.
  - The unit's bike-rack index equals the dry run's.
  - `/mnt/app` was read-only. Safe mode was off and `dev/` was empty.
- `mibpkg list` after the install: bike-rack 1.1.0, carmqtt 0.2.2, crashy 1.1.0, daemon-status 1.1.0, hello 1.0.0,
  hello-menu 1.2.0, mibpkg 0.9.1, mibsdk 0.2.0. **No package carries a bench tag.**

## Daemons (staged before R1)

### Tailscale (mib2q-tailscale)

- `/mnt/app/root/tailscale/` already held the five binaries and `tailscale.sh`. All six are byte-equal to the repo's
  `build/` and `deploy/tailscale.sh`, so nothing was redeployed.
- `startup.sh` had no autostart line. The line from `deploy/startup-hook.txt` (the comment plus the detached
  `( sleep 30; [ -x …/tailscale.sh ] && …/tailscale.sh start ) </dev/null >/dev/null 2>&1 &`) went in as the README
  says: in `start_late_drivers()`, right after `inetd > /dev/null 2>&1`, above mibpkg's own hook. The steps:
  1. Edited on the host, uploaded and compared.
  2. `ksh -n` on the unit.
  3. Renamed over the live file.
  4. Kept the backup `startup.sh.pre-tailscale`.
  5. Remounted `/mnt/system` read-only again.

  mibpkg's hook anchor (the `inetd` line) and its removal (its marker line plus the `##` above it) are unaffected.
- `tailscale.sh start` printed a login URL (`tailscale up` without an auth key). The owner approved the device and
  renamed it `mmx-bench` in the admin console. Auth state is in `/mnt/persist/tailscale` (`tailscaled.state`,
  `profile-data`, `derpmap.cached.json`).
- **Hostname:** `tailscale.sh` passes no `--hostname`, and the prefs have `"Hostname": ""`. The node reports the
  OS hostname `mmx`, and the admin-set name holds: `DNSName` is `mmx-bench.<tailnet>.ts.net`. Nothing fights the
  rename, and it survived R1.
- First-start noise: `tailscale.sh start` prints `cannot open /tmp/tailscaled` (and the same for the other four
  files). This is `ts_filesize` on the not-yet-copied RAM copy. The copy then succeeds. It is harmless.

### carmqtt (mib2q-carmqtt, mibpkg package 0.2.2)

- carmqtt is a mibpkg package, so its config is mibpkg's: `state/config/carmqtt/carmqtt.toml`, config number 0. It
  was set the way the web UI sets it. The new file was uploaded to `/tmp/mibpkg.text.<id>` and compared, then
  `mibpkg gem config-set carmqtt 0 <id>` gave `OK: saved carmqtt.toml for carmqtt; restarting its services`. mibpkg
  kept `carmqtt.toml.prev`, and the file is mode 600.
- The `[mqtt]` section is now host `192.168.10.5`, port `1883`, username `mqtt`, and a password (redacted; it exists
  only in the unit's config file). The rest of the bench config is unchanged (UBX off, status port 19851).
- **carmqtt does not connect, by design:** `mqtt=0`, `last_publish_age_s=-1`, `signals=0`, `exlap=1`. The log says
  `waiting for VIN before connecting to MQTT`. The publisher's first connect needs a VIN to build the topics, from
  ExLAP or from `[device] vin` (`publisher.rs` `vin()`), and the bench's ExLAP supplies none, since there is no
  vehicle. No VIN override was set.
- **The broker is reachable from the unit, both ways.** At first the tailnet node `stash` advertised
  `192.168.0.0/16`, `tailscale up --accept-routes` installed it on `tun9`, and `telnet 192.168.10.5 1883` from the
  unit gave `Connected to 192.168.10.5.` over Tailscale. After the T1 fix (routes off) the same telnet connects
  directly, through the bench router (default gateway 10.20.0.1). Home Assistant's broker was up both times.

### Finding T1: the accepted 192.168.0.0/16 route cut the bench LAN (resolved)

The same accepted route carries the unit's replies to every 192.168.x host. Once `tailscaled` is logged in:

- the unit stops answering on 10.20.0.23 from the host and the bench Pi, which are both on 192.168.x;
- `keypanel status` reports `mmx: UNREACHABLE`.

The unit itself is fine and is reachable at its tailnet address, including SSH and the keypanel on `:8099`. Until the fix below, this
run drove everything after the login over the tailnet. After a boot, the LAN path works only until the autostart
brings the tunnel up, about 30 s after the uplink.

**Resolved (owner decision): accept-routes is off on the bench unit only.** mib2q-tailscale's `tailscale.sh` gained a
per-unit opt-out (branch `feat/accept-routes-opt-out`, 902baea, with a host test). `/mnt/persist/tailscale/accept-routes`
containing `0` makes `tailscale up` pass `--accept-routes=false`; with no file the default stays on, as the car needs.
The steps on the unit:
1. The updated script went to `/mnt/app/root/tailscale/` the way it was deployed before: `mount -uw`, upload, read
   back, `ksh -n`, rename, `mount -ur`. The old copy is kept as `/mnt/persist/tailscale/tailscale.sh.pre-accept-routes`.
2. Wrote `echo 0 >/mnt/persist/tailscale/accept-routes`.
3. Applied it live with `tailscale set --accept-routes=false` (prefs `RouteAll: false`). There was no HMI restart
   and no tailscaled restart.

The 192.168.0.0/16 route left `tun9` at once. After that:
- 10.20.0.23 answers SSH from the host;
- `keypanel status` reports `mmx: reachable (10.20.0.23)`, `daemon: up`;
- the node is still `Running` as `mmx-bench` with the same 100.x address;
- tsstatus on 19850 still answers `state=Running`.

The setting persists two ways: in tailscaled's stored prefs, and in the file that the next `tailscale.sh start`
reads (host-tested). It was not re-checked across a boot.

**The broker is reachable directly too:** with routes off, `telnet 192.168.10.5 1883` from the unit connects through
`mlan0` and its default gateway 10.20.0.1. So the bench router reaches 192.168.10.x on its own, and turning routes off
costs the bench nothing.

## R1: the single restart

| Time (host) | Event |
|---|---|
| 14:37:49 | Pre-flight over the tailnet: `sdk status` 0.2.0, `api: 1..4`, `reboot needed: yes`; `colour=own` |
| 14:37:53 | `installer/mibsdk-dev restart <tailnet address>` (`slay -s11 j9`) |
| 14:38:03–14:38:33 | the keypanel on the tailnet address answers `bridge_connected:false` |
| 14:38:45–14:48:00 | no answer on either path (the unit reset, as C4 of the icon-colour run) |
| 14:48:14 | `ssh rpi-bench keypanel up` (try 1): `no answer - kicking DHCP on mlan0 via RCC`, `mmx: up` |
| 14:49:21 | keypanel ready; `sdk status` `running: yes`, `reboot needed: no` |

The unit was usable about 11.5 min after the restart, and one `keypanel up` was enough. Minutes later Tailscale came
up and the LAN path dropped again (T1). The log (`release-0.2-log-r1.txt`) has:
- `ui system=sdk`, `ui car=sdk` and `safeMode=false`;
- `ui status popup 609600 registered` and `built`;
- all five plugins `ENABLED`, with no BLOCKED_INVALID;
- `boot healthy: 5 plugin(s) enabled for 60000 ms`.

At boot a low-fuel "find a gas station?" dialog was up. It was answered "No".

## Checks

Geometry: the popup band is x 257–383 in the capture (screen 514–767). The text extents below are the first and
last bright columns in rows 0–11, from x 257.

| Check | Result | Frames |
|---|---|---|
| I1 | **pass.** Car → drawer, focus on Bike rack: the row shows a white bicycle (two wheels, frame, saddle, bars), not a black square. Under it is a faded reflection like the Hello row's. The log has `getImagePath 609100 -> ../mibsdk/icons/bike-rack/2ccc1952f4d6b173.png` (`bike`) and `609101 -> …/4abcfb05951cb8fa.png` (`bike.r`). The unfocused bicycle also shows next to Hello with both reflections | `release-0.2-drawer-bike.jpg`, `-drawer-bike-zoom.png`, `-drawer-hello.jpg` |
| I2 | **pass.** The unit's `icons/bike-rack/index.properties` is `bike=2ccc1952f4d6b173,72,72`, `bike-20=a0dee0389ab98d1c,20,20`, `bike.r=4abcfb05951cb8fa,72,68` (`bike.r` is 72×68). There are three PNGs of 1649, 396 and 19720 bytes, and each sha16 matches the package | none |
| I3 | **bench-impossible (expected).** RACK never appears, because there is no PDC ECU. The rack page reads "Rear tones: waiting for parking system". The host test covers it | `release-0.2-rack-page.jpg` |
| I4 | **pass.** Selecting the Bike rack row opens the `rack` page ("Bike rack": `[on] Rack fitted (rear PDC tones off)`, the parking-wait line, "Turns off at ignition off") | `release-0.2-rack-page.jpg` |
| B9-1 | **pass.** The group sits at the right of the band, with the free space on the left. On bench.6 the same two items started at the left padding (x 263); now `TS up MQ dn` + Hello spans 276–278 to 370. The same right end (370) shows on every screen: Car "Vehicle: Audi drive select", Hello, Plugins, Bike rack, Daemon Status and the MENU ring. A short line (daemon-status item hidden, Hello alone) is 346–370, with x 257–345 free | `release-0.2-status-car.jpg` (+zoom), `-status-short.jpg` (+zoom) |
| B9-2 | **partial.** The bench shows at most two items: crashy shows none, RACK needs PDC, and SAFE needs safe mode. That two-item line is the fullest available. It starts at x 276 with nothing clipped at the right (it ends at 370, and the band ends at 383) | `release-0.2-status-car.jpg` |
| B9-3 | **pass.** On the Daemon Status page, "Status item" was toggled off and on, with frames every ~0.4 s from −1 s to +7 s and no other input. Off: the old line at +415 ms, the short line at +850 ms. On: the short line at +1989 ms, the full line at +2456 ms. Each change happens in one frame, with no intermediate or stale frame, and Hello stays fixed at its right end. No text is clipped at any point. The ~2 s latency of the on-press shows on the page's own `[off]`/`[on]` text too, so it is the toggle path, not the popup. The log has `Plugins menu: daemon-status status item hidden` / `shown` | `-status-shrink-before.jpg`, `-status-short.jpg`, `-status-grow-before.jpg`, `-status-grow-after.jpg` (+zoom), `-daemon-status-page.jpg` |
| B9-4 | **not run.** SAFE needs safe mode at boot (a second restart), and PDC R OFF needs a PDC ECU. The host layout tests cover the order | none |
| B9-5 | **pass.** On the Hello page, "Status icon colour" was pressed twice. Frames every ~0.4 s, with hello-20's pixels classified: press 1 own → tinted between +522 and +909 ms (white/blue → red/black); press 2 tinted → own between +172 and +565 ms. The stored value went back to `colour=own` | `release-0.2-colour-p1-before.jpg`, `-p1-after.jpg`, `-p2-after.jpg` (+zooms) |
| B9-6 | **measured.** The last glyph (Hello's `o`) ends at capture x 370, and the band's last column is 383, so the gap is **13 capture px, about 26 screen px**. It is the same on every screen and line length. The label box runs to the band edge (2b5b5e3), and the text is left-aligned inside a box sized by `STATUS_CHAR_PX` = 10. The real glyphs are narrower (the 5-character "Hello" is about 36 screen px rather than 50), so the slack sits at the right. If the gap matters, a smaller `STATUS_CHAR_PX` (about 7–8 for this font) or right-aligning the last label would close it | `release-0.2-status-car-zoom.png`, `-status-short-zoom.png` |

Also seen:
- Before the first MENU press, the Car drawer still has the SDK's Plugins row with a black square (C3 of the
  icon-colour run, by design: `release-0.2-drawer-plugins-row-before-menu.jpg`). After MENU the ring ends with
  Plugins (wrench) (`release-0.2-ring-plugins.jpg`, `-plugins.jpg`).
- The B11/C5 rotation quirk recurred: `rot 5` from Apple on the Hello page landed on `Selected: Pear`, and one step
  back reached "Status icon colour". Every `knob_select` followed a frame check.
- daemon-status's item icon is a red cross before the toggle and a red dot after re-showing it (`TS up MQ dn` both
  times). This is plugin behaviour, not layout.

## Post-boot daemons

- **Tailscale autostarted** from the startup.sh line, with no manual start after R1. `tailscaled` and `tsstatus`
  are running, `BackendState` is `Running`, `DNSName` is `mmx-bench.<tailnet>.ts.net`, and the prefs have
  `Hostname: ""` (still logged in as mmx-bench).
- **tsstatus on 127.0.0.1:19850** answers `v=1`, `daemon=tailscale`, `state=Running`, `ip=<100.x>`, `peers=5`,
  `peers_online=3`, `last_handshake_age_s=41`, `age_s=6`.
- **carmqtt on 127.0.0.1:19851** answers `v=1`, `daemon=carmqtt`, `version=0.2.2`, `mqtt=0`,
  `last_publish_age_s=-1`, `signals=0`, `exlap=1`. It started at boot with the new config and is waiting for a
  VIN, as described above.
- The status line shows daemon-status's `TS up MQ dn` on every screen.

## End state (verified)

- mibpkg 0.9.1. mibsdk 0.2.0 running (`api: 1..4`, `running: yes`, `reboot needed: no`), takeover ACTIVE
  (`HMICar=`/`HMISystem=` SDK activators, `ui system=sdk`, `ui car=sdk`, MENU ring ends with Plugins).
- All five plugins ENABLED, ledger 0. hello-menu has `colour=own`.
- Safe mode off (`safeMode=false`, `unhealthyBoots=0`), and `dev/` is empty (no forcefail).
- The bundles file is byte-equal to its copy from before the install.
- `/mnt/app` and `/mnt/system` are read-only.
- Tailscale autostart is configured (`startup.sh` line, backup `startup.sh.pre-tailscale`), and accept-routes is off
  on this unit (`/mnt/persist/tailscale/accept-routes` = `0`). The LAN path 10.20.0.23 and the tailnet path both
  work. carmqtt is configured.
- The unit is on its home screen (Car / Audi drive select) (`release-0.2-home-end.jpg`).
