# Car session runbook (Plan 4)

This runbook is for the owner's Audi (MHI2Q). It is based on the bench runbooks (`docs/bench-e2e*.md`), the
archived reports (`.superpowers/sdd-archive/`) and the compatibility audit
(`docs/research/2026-10-26-carplay-rgi-compat.md`). Nothing here has run on the car yet.

**Path:** uninstall carplay-rgi, check the unit is stock, then install the SDK. mibpkg refuses the SDK while
carplay-rgi is on the unit (audit §2.2), and the owner has agreed to remove it for the session.

## Owner checklist (read first)

**Bring:**
- The laptop with this repo, the mibpkg repo and the built packages (P1), charged or on a 12 V charger.
- The M.I.B. SD card, plus two card sets for **the carplay-rgi release that is in the car**:
  - its uninstall card (`uninstall_MoreIncredibleBash/mod/`);
  - its install card (`install_MoreIncredibleBash/mod/` with `mod/carplay/`).

  The install card is the fallback uninstaller, and it reinstalls CarPlay afterwards if you want it back.
- An object for the rear sensors: a box or bin about 50–80 cm tall.
- A phone for photos.

**Where and how:**
- Park on level ground with clear space behind (and in front of) the car, away from traffic. Use your own drive
  or an empty car park.
- Keep the engine running or the battery on a maintainer: there are several reboots with the ignition on.
- Put the laptop on the car's Wi-Fi hotspot (`ssh audi` = `root@10.173.189.1`), or on a hotspot both can reach.

**Safety rules for every check:**
- The car is **parked, with the parking brake on**.
- Select reverse only while stationary, **with your foot on the brake**. Never drive during a check.
- Never drive off with the bike rack "armed": the rear tones are off then. The end state must have rear tones
  audible again (E1).

**Time:** about 3½–4½ hours on the car. That is 5 planned reboots of about 5–10 minutes each, plus the checks.
Pre-flight on the host takes about 1 hour and is done before you go out.

**Stop rule:** if any **STOP** gate below fails, do not continue. Run its rollback, and leave the car as it was.

## Rules for the operator (laptop)

- **Never print or log the VIN or IMSI.** Never read `lsd.sh`, the j9 command line (`pidin` with arguments,
  `ps`, `/proc/*`), the full `version_info.txt`, `carmqtt.toml` or the full carmqtt log.
- Use `mosquitto_sub` only without `-v`, so no topic (which holds the VIN) is printed.
- When reading the bundles file, `grep` only the `HMI*=`, `SM*=` and `MibSdk` lines.
- Redact any line that would carry the VIN before it goes into a report.
- Unit shell limits (`mibpkg/docs/unit-facts.md`): there is no `cmp`, `od`, `wc`, `tail`, `printf` or `find`.
  - Compare files on the laptop: `ssh audi cat <f> > <local>`, then `shasum -a 256`.
  - Nothing is written to `/mnt/app` by hand except the emergency revert (R-E).
- Throughout, `U=audi` (or the unit's IP), `M=/tmp/mibpkg`, `D=installer/mibsdk-dev` (its host argument goes
  last: `$D status 10.173.189.1`).
- Write every observation, its time and the photo name into `docs/car-session-<date>.md` as you go.

## P. Pre-flight on the host (before the session)

### P1. Builds

Build from clean trees and record bytes and the first 16 hex characters of each sha256.

| Package | Source | Command | Why |
|---|---|---|---|
| `mibpkg-0.9.1.mpk` | mibpkg main `a0a5e49` | `scripts/build.sh`, then the package step used for 0.8.1 (`docs/bench-e2e.md`, Task 16 Step 0) | The car has 0.6.0 or 0.7.0 (audit §4) |
| `mibpkg-0.8.1.mpk` (fallback) | the 0.8.1 commit | the same | Only if 0.9.1 refuses to install over 0.6/0.7 |
| `mibsdk-0.2.0.mpk` | SDK `3097b00` (release 0.2) | `./gradlew :dist:mibpkgPackage -Pmibsdk.mibpkg=<host mibpkg 0.9.1>` | O1: the old runtime |
| `mibsdk-0.3.0.mpk` | SDK main `7932f35` | the same | The SDK under test |
| `bike-rack-1.1.0.mpk` (api 3), `daemon-status-1.1.0.mpk` (api 3) | main | `./gradlew :plugins:<id>:mibPackage -Pmibsdk.mibpkg=…` | F7, status line, carmqtt and tailscale view |
| `inputs-demo-1.0.0.mpk` (api 5) | main | the same | O1 and the toast |
| `kombi-probe` (P2) | not written yet | — | K |

- Every version is above anything on the car: the car has no SDK and no plugin, and mibpkg goes 0.6/0.7 → 0.9.1.
- **Do not build or install carmqtt or tailscale.** The car has carmqtt 0.2.3 (`unit-facts.md`, 2026-10-14) and
  the repo is at 0.2.2. The session only checks the installed carmqtt.

### P1a. Fake-root dry run

Do this as on the bench (`docs/bench-e2e-native-inputs.md`, "Builds and dry run") using a **stock-firmware
root**. Build that root from `~/projects/audi-mib2/firmware/mmxfs/app` (`eso/hmi/lsd`, `lsd/jars`, the bundles
file, `DSITracer.jar`, `img_ver.txt`, `/ifs/version_info.txt`) with **no `dsitracer_activated`** marker.

Install `mibsdk-0.2.0`, then `0.3.0`, then the plugins, then remove all of them and the SDK.

**Go only if:**
- each step succeeds;
- `sdk.properties` has **no** `chain.configurator` (the tracer is not active on the car, a path the bench never
  ran);
- after the removal, the bundles file is byte-equal to the stock one and the marker is absent again.

Also check the guard: with a dummy `lsd/jars/carplay_hook.jar` in the root, the install is refused with
`carplay-rgi is installed`.

### P2. Kombi probe (car-only, needs code first)

- The SDK has no API for `DSICarKombi.setDCBrightness`
  (`.superpowers/sdd-archive/can-brightness-research.md`, §2–3).
- Check K needs a small probe plugin with `platform.unrestricted`. It should:
  1. read `DCViewOptions.brightness` (availability) and `updateDCBrightness`, and log both;
  2. on a page action, make one `setDCBrightness(v)`;
  3. on a second action, restore the read value;
  4. log every call and update.
- Build it, run it once on the bench (where it can only log "no Kombi"), and add it to P1.
- **If it is not ready, skip K and record it as open.**

### P3. Prepared text

- Have the emergency revert (R-E) ready to paste.
- Have `<uid>` values ready: 32 hex characters each, `od -An -tx1 -N16 /dev/urandom | tr -d ' \n'` on the laptop.

## R. Recovery plan (no RCC console, no PDU)

**Normal revert, over SSH**

| Step | What to run |
|---|---|
| Revert the SDK | `$M remove mibsdk` (plugins first: `$M sdk remove <id>`), then reboot. mibpkg restores the bundles file and the marker byte for byte. |
| Revert a held vehicle override | Ignition off and on (`UNTIL_IGNITION_OFF` restores at ignition off), then `$M sdk remove bike-rack`. |

**R-E: emergency revert**

Use this if the HMI will not come up healthy, or mibpkg is unusable. It does not need mibpkg. It turns the SDK
off at the next boot, because `lsd.sh` uses the on-disk bundle list only while the marker exists
(`docs/superpowers/specs/2026-10-18-mib2q-plugin-sdk-design.md` §5.1).

- If **S4 found no marker** (expected on the car):
  ```
  mount -uw /mnt/app && cp /mnt/persist/carsession/bundles.pre /mnt/app/eso/hmi/lsd/bundles_dsitracer_enabled.properties && rm -f /mnt/app/eso/hmi/lsd/dsitracer_activated && sync && mount -ur /mnt/app
  ```
- If S4 found a marker, leave out the `rm -f …dsitracer_activated` part.
- Then reboot. When the unit is back, run `$M sdk status`, and `$M remove mibsdk` to clean up the package
  record. If mibpkg refuses, stop and take the unit state to the host: the stock list is already in effect.

**Reboot**

| Method | When |
|---|---|
| `$D restart <ip>` (`slay -s11 j9`) | The tested method. On the bench this resets the whole unit (F3, about 4–5 min there, partly DHCP). |
| Owner: hold the head unit's power button about 10 s, until the screen goes black and the Audi logo returns | Whenever SSH is gone. |
| Ignition off, lock the car, wait about 10 min for the unit to sleep | Last resort. |

**Is SSH back after a reboot?** Not yet known on the car.
- **R0 tests it** before anything SDK-related is installed. The laptop must re-join the car's Wi-Fi after each
  reboot.
- The tailnet address is a second route: the tailnet comes up about 2 minutes after boot (`unit-facts.md`, boot
  timeline).
- If SSH does not come back within 10 minutes of R0, **stop the session** and reinstall carplay-rgi from the card
  (U6). Without SSH there is no revert path.

**HMI boot loop** (Audi logo again and again)
- The SDK's boot guard goes to safe mode after 3 unhealthy boots: `SAFE` on the status line, no plugins, stock UI.
- If SSH answers in between, run R-E at once.
- carplay-rgi's recovery notes: `origin/main docs/build-and-deploy/ISSUE24_RCC_MMX_RECOVERY.md` in the carplay
  repo.

## S. Connect and read-only survey

Nothing is written in this section.

- **S0 — connect:** `ssh audi 'echo ok'`. Old OpenSSH needs `-o HostKeyAlgorithms=ssh-rsa -o PubkeyAcceptedKeyTypes=ssh-rsa`,
  which are already in `~/.ssh/config`.
- **S1 — HMI build:** `ssh audi "grep '^HMI' /ifs/version_info.txt"`.
  - It must give `HMI_AU_MIB2_HQC_NAR_G22_C10_180803`.
  - Cross-check with the REM: Version information → Current version starts with `MHI2Q_`, and the train should be
    `MHI2Q_US_AUG22_P5087`.
  - **STOP** if the build differs (mibpkg would refuse anyway).
- **S2 — mibpkg:** `ssh audi '/tmp/mibpkg status; /tmp/mibpkg list'`. Record the version (0.6.0 or 0.7.0
  expected) and carmqtt's version.
- **S3 — bundles lines only:**
  ```
  ssh audi "grep -n '^HMI[A-Za-z]*=\|^SM[A-Za-z]*=\|MibSdk' /mnt/app/eso/hmi/lsd/bundles_dsitracer_enabled.properties"
  ```
  Expect stock lines: line 61 `SMSystem=de.audi.tghu.system.sm.Activator`, 62 `HMISystem=…system.hmi.Activator`,
  74 `HMITone=…tone.hmi.Activator`, 121 `HMICar=…car.hmi.Activator`, and no `MibSdk`. **STOP** if not stock.
- **S4 — lsd tree:**
  - `ssh audi 'ls -l /mnt/app/eso/hmi/lsd/ /mnt/app/eso/hmi/lsd/jars/'`;
  - record whether `dsitracer_activated` exists (expected: no) and whether `DSITracer.jar` exists (needed);
  - look for `xTcpKeyPanel.jar` or a keypanel install: is mib-keypanel on the car? Also try
    `curl -s 10.173.189.1:8099/status`.
- **S5 — carplay-rgi state:**
  - `ssh audi 'ls -l /mnt/app/eso/hmi/lsd/jars/ | grep -i carplay; ls -l /mnt/app/root/hooks/'`;
  - `ssh audi 'ls -R /mnt/app/eso/hmi /mnt/system/etc /mnt/app/eso/bin/apps /mnt/app/armle | grep carplay-stock'`;
  - copy `carplay_hook.jar` to the laptop and read its `CarPlayApp` `BUILD_ID` string there, to identify the
    release. It must match the card you brought.
- **S6 — free space:** `ssh audi 'df -k /mnt/app /mnt/system /mnt/persist'`.
  - **STOP** if `/mnt/app` has less than 20 MB free or `/mnt/persist` less than 1 MB.
  - mibpkg keeps the previous versions; the SDK is about 0.7 MB.
- **S7 — daemons:** run `$D status`-style reads only after mibpkg is updated. For now, check that tailscale and
  carmqtt are running:
  - `ssh audi "/tmp/mibpkg status"`;
  - `ssh audi "grep -c 'mqtt connected' /tmp/carmqtt.log; grep -c 'waiting for VIN' /tmp/carmqtt.log"`.

## B. Backups (before any write)

Make these on the laptop and in `/mnt/persist/carsession/` (qnx6, read-write without a remount; the partition is
small, so keep only small files there).

- **B1 — bundles file:**
  - `ssh audi 'mkdir -p /mnt/persist/carsession && cp -p /mnt/app/eso/hmi/lsd/bundles_dsitracer_enabled.properties /mnt/persist/carsession/bundles.pre && ls -l /mnt/persist/carsession'`;
  - `ssh audi cat /mnt/app/eso/hmi/lsd/bundles_dsitracer_enabled.properties > car/bundles.pre`;
  - the sha256 of the laptop copy must equal the sha256 of a second `cat` of the persist copy.
- **B2 — marker state:** `ls -l` of `dsitracer_activated` into `car/lsd-ls.pre` (and `/mnt/persist/carsession/lsd-ls.pre`).
- **B3 — laptop only (larger files):**
  - every file in `lsd/jars/`, including `lang_data.zip` and `carplay_hook.jar`;
  - `smartphone_integrator.json`, `dio_manager.json`, `/mnt/system/etc/pf*.conf` and `mcd.conf`;
  - each `*.carplay-stock` found in S5.

  Record each file's sha256 in `car/sha256.pre`.
- **B4 — mibpkg state listing:** `ssh audi 'ls -lR /mnt/app/root/mibpkg/state' > car/mibpkg-state.pre`. Do not copy
  `state/config` (it holds secrets).

## M. mibpkg to 0.9.1

- **M1.** Upload `mibpkg-0.9.1.mpk` to `/tmp/mibpkg.upload.<uid>.mpk`
  (`ssh audi "cat > /tmp/mibpkg.upload.<uid>.mpk" < mibpkg-0.9.1.mpk`). Check its size on the unit.
- **M2.** Run `ssh audi '/tmp/mibpkg gem install-file mibpkg <uid> -'`.
  - 0.6 and 0.7 have no top-level `install-file` (P3-F1).
  - Expect `updated mibpkg 0.x -> 0.9.1; mibpkg restarts itself`.
  - If it is refused, install 0.8.1 the same way first, then 0.9.1 with `/tmp/mibpkg install-file mibpkg <uid> -`.
- **M3.** Within 15 s, `$M status` must show `supervisor 0.9.1 … (running)`, and `$M list` must show carmqtt
  unchanged and running.
  - **STOP** if carmqtt or tailscale did not come back.
  - Rollback: `$M rollback mibpkg`.
- **M4.** `$M sdk status` should say `sdk: not installed`, with the stock `HMICar=` and `HMISystem=` lines.

## U. Uninstall carplay-rgi and check stock

- **U1. Dry check:** `$D install 10.173.189.1` with `mibsdk-0.3.0.mpk` must be **refused** with `carplay-rgi is
  installed … nothing changed`. This confirms the guard on the car. Check the bundles file sha against B1.
- **U2. Uninstall with carplay-rgi's documented uninstall card** (audit §5.1 (B)), through M.I.B. as the owner
  installs it:
  1. Copy `uninstall_MoreIncredibleBash/mod/` onto the M.I.B. card as `mod/`.
  2. Disconnect CarPlay.
  3. Run GEM → M.I.B. → Advanced → Run Custom Script.
  4. Expect `restored …` lines, `removed …` lines and `DONE (uninstall). Reboot the HU.` Photograph the console.
  - **No `restored` line at all** means this card's `find` failed. Before the reboot, run the release's install
    card in uninstall mode over SSH, with the owner's OK: put the install card (`mod/custom.sh` + `mod/carplay/`)
    in, then `ssh audi 'sh /fs/sda0/mod/custom.sh uninstall'` (audit §5.1 (A)).
- **U3. R0, the first reboot:** `$D restart` (or the power button).
  - Time how long until SSH answers again over the car Wi-Fi, and over the tailnet.
  - Note whether the laptop had to re-join.
  - **STOP** if there is no SSH after 10 minutes (see R).
- **U4. Stock check** (all must hold):
  - no `*carplay*` in `lsd/jars`, and `ls -R /mnt/app/eso/hmi | grep carplay-stock` prints nothing;
  - `lang_data.zip` and every other `lsd/jars` file is byte-equal to `~/projects/audi-mib2/firmware/mmxfs/app/eso/hmi/lsd/jars/`
    (by sha256, on the laptop). A difference is recorded, and the go decision is the owner's;
  - the bundles file is byte-equal to B1, and S3's lines are stock;
  - each other file B3 listed with a backup now equals its `.carplay-stock` from B3;
  - on screen, the Sound menu has no UpMix row and the TEL key shows the stock path. Take photos;
  - `$M sdk status` shows `sdk: not installed`, with stock lines.
- **U5. Go/STOP.** Go only if U4 holds.
  - If it fails: reinstall carplay-rgi (U6) and end the session.
- **U6. (rollback, or the end of the session) Reinstall carplay-rgi:**
  1. Uninstall the SDK first (X).
  2. Run the release's install card (`DONE (install). Reboot the HU to load.`) and reboot.
  3. Check that `carplay_hook.jar` is back and the jar sha equals B3, and that CarPlay connects.

## I. SDK install, with O1 built in

- **I1. Install mibsdk 0.2.0.** `MIBSDK_MPK=…/mibsdk-0.2.0.mpk $D install <ip>`.
  - Expect `installed mibsdk 0.2.0; reboot …`.
  - The live bundles lines should now be `HMICar=mibsdk.adapter.hqc.ui.SdkCarActivator`,
    `HMISystem=mibsdk.adapter.hqc.ui.SdkSystemActivator` and the `MibSdk` bundle.
  - `dsitracer_activated` now exists. mibpkg records it as "absent" in its backups.
  - `sdk.properties` has no `chain.configurator`.
- **I2. Plugins:**
  - `$D install-plugin bike-rack-1.1.0.mpk --approve=vehicle.read,vehicle.parking.write,ui.slot.carmenu,ui.slot.statusbar <ip>`;
  - `$D install-plugin daemon-status-1.1.0.mpk --approve=network,ui.slot.statusbar <ip>`.
- **I3. R1:** `$D restart`.
  - **Go** only if the log has `runtime started … safeMode=false`, `ui car=sdk`, `ui system=sdk`, `ui status popup
    609600 registered` and `boot healthy: 2 plugin(s) enabled`.
  - On screen: the Car drawer has Bike rack and Plugins rows, and the MENU ring has Plugins. Take photos.
  - **STOP** and run X (then U6) if the HMI does not come up healthy within 10 minutes of SSH returning. Use R-E
    if mibpkg cannot run.
- **I4. O1, the old runtime refusing an api-5 plugin:**
  1. `$D install <ip>` with `mibsdk-0.3.0.mpk`. It is staged: `updated mibsdk 0.2.0 -> 0.3.0; reboot …`, and the
     running runtime is still 0.2.0.
  2. `$D install-plugin inputs-demo-1.0.0.mpk --approve=ui.slot.statusbar <ip>`.

  **Pass if:**
  - the still-running 0.2.0 runtime logs `inputs-demo BLOCKED_INVALID … unsupported api 5`, with no failure count
    and no quarantine;
  - bike-rack and daemon-status stay ENABLED;
  - `sdk status` shows sdk 0.3.0, api 1..5, `reboot needed: yes` and ledger 0;
  - the bundles lines are as in I1.

  On the bench this could not be shown, because the unit was in safe mode. If the runtime is in safe mode here
  too, record O1 as not shown and do not spend a spare restart on it.
- **I5. R2:** `$D restart` into 0.3.0.
  - **Go** if the log has `ui toast popup 609601 registered`, 3 × ENABLED and `boot healthy: 3 plugin(s)`.

## C. Checks (SDK 0.3.0 running)

Captures: use keypanel `screen.jpg` only if S4 found keypanel on the car. Even then it may not show the camera
layer, so **every RVC/PDC check uses owner phone photos**, plus the `$D log` lines (copy them to the laptop).

### F7 — bike-rack override end to end, against the real PDC ECU

- **F7.0 Baseline.** Place the object about 50 cm behind the middle of the rear bumper.
  - Owner: foot on the brake, select R, stationary. The rear tones sound and the PDC shows the obstacle.
  - Select P. Photo plus a short video for the sound.
  - `$D status`: `ledger: 0 override(s)`.
- **F7.1 Arm.** Car → drawer → Bike rack → "Rack fitted (rear PDC tones off)" on.
  - The log has `rear parking tones muted` and an `override parking.soundDirections=… by bike-rack
    (UNTIL_IGNITION_OFF), prior …` line.
  - The ledger file has one `parking.soundDirections=bike-rack|UNTIL_IGNITION_OFF|<prior>|<active>|A` line.
  - The status line shows **`PDC R OFF`** (and `RACK`). Photo.
- **F7.2 Real object, muted.** Foot on the brake, R, stationary.
  - The PDC display still shows the rear obstacle, and **the rear tones are silent**. Video.
  - Optional: a hand near a front sensor still beeps, so the front is unchanged.
  - Select P. Record whether the ECU sent an `updatePDCSoundReproduction` for the write (the I4 car question, see
    `car-readiness.md`).
- **F7.3 Disarm and restore.** Toggle off.
  - The status line keeps `PDC R OFF` while restoring.
  - Then the log has `restored parking.soundDirections=<prior> (confirmed by the car) (override of bike-rack
    ended)`. This is the restore observed through the parking update hook (HookObserved / `PARKING_SOUND_UPDATE`).
    `(… last report …)` as a WARN is acceptable and must be recorded.
  - `PDC R OFF` disappears. `$D status` shows `ledger: 0 override(s)`, and the ledger file is empty.
  - R with foot on the brake: the rear tones sound again. Video.
- **F7.4 Ignition-off restore (T18 and M6 on the car).**
  1. Arm again (F7.1).
  2. Ignition off. Wait 10 s, so the 5 s re-arm window is over.
  3. Ignition on.
  4. The log has a restore at ignition off. The rack is disarmed, the ledger is empty, and the rear tones sound
     (R, foot on the brake).
  5. Then arm, ignition off, and ignition on **within 5 s**: the rack re-arms and `PDC R OFF` shows. Disarm.
- **F7 pass** = all of F7.1–F7.4 hold, the ledger ends empty and the rear tones are audible.
- **STOP/rollback** if the tones do not return after F7.3:
  1. ignition off and on;
  2. then `$D remove bike-rack`;
  3. then check the stock Car → Parking aid sound settings by hand;
  4. never leave with the tones muted.

### P — the SDK popups vs RVC/PDC (car-only checks in `docs/car-readiness.md`)

- **P1.** On home with the status line (609600, top right x 514..769), foot on the brake and R.
  - Photo the reverse-camera image and the PDC display. Neither 609600 nor the toast (609601, slot 6) may cover
    them.
  - Select P. Repeat from the Car screen and from media.
- **P2.** Arm the rack, so `PDC R OFF` is on the line, then R. Photo: the line is not over the camera or PDC.
  Disarm.
- **P3. Popup 65 in the boot window.**
  1. Right after a restart (use R3, or the spare), as soon as the HMI shows: foot on the brake, R. The line is on
     popup 65 before 609600 takes over.
  2. Photo every few seconds through the hand-over.
  3. Pass if neither popup ever covers the camera or PDC.
- **P4.** In inputs-demo, trigger a toast (Plugins → Inputs Demo → Toast), then select R within its 2 s. Photo.

### K — Kombi brightness (only if P2 of the pre-flight produced the probe)

1. Install the probe: `$D install-plugin kombi-probe-*.mpk --approve=platform.unrestricted` (it shows the loud
   warning).
2. On its page, choose "Read". Log `DCViewOptions.brightness` and the current value.
3. If brightness is not available, record **not supported** and stop K.
4. Otherwise choose "Set" to a clearly different value (for example 20 if the current value is above 50). The
   owner watches the virtual cockpit. Photo before and after, from the same angle.
5. Choose "Restore": the original value is logged, and the cockpit looks as it did before.
6. Remove the probe.

### Q — carmqtt with the real VIN

1. `ssh audi "grep -c 'waiting for VIN' /tmp/carmqtt.log; grep -c 'mqtt connected' /tmp/carmqtt.log"`. Use counts
   only.
2. Read the status endpoint through daemon-status: Plugins → Daemon Status shows `MQ up`, and the status line
   shows `MQ` up. Or run
   `ssh audi "echo | /mnt/app/armle/usr/bin/nc 127.0.0.1 19851"` if `nc` exists; `telnet` also works. Expect
   `mqtt=1`, a small `last_publish_age_s` and `exlap=1`. The snapshot has no VIN.
3. On the HA host: `mosquitto_sub -h <broker> -u … -t 'mib2q/+/status' -C 1` (**no `-v`**) gives `online`. In
   HA, the device's "Online" entity is on. Do not screenshot the device page, which shows the VIN.

**Pass** = `mqtt=1` after ExLAP gives the VIN, with no VIN printed anywhere.

### O1

See I4. Record pass or not shown.

## X. SDK uninstall and byte-identical restore

- **X1.** Remove the plugins:
  - `$D remove inputs-demo`, `$D remove daemon-status`, `$D remove bike-rack`. Each should say `has no overrides
    in the ledger`;
  - remove `kombi-probe` if it was installed;
  - then `$D uninstall`.
  - `uninstall` refuses while the ledger has entries. Never use `--force` on the car.
- **X2.** Check before the reboot:
  - the bundles file sha256 equals B1, and its mode is the same;
  - `dsitracer_activated` is absent again, as in B2;
  - no `mibsdk-*.jar` is in `lsd/jars`;
  - `lsd/jars` sha256s equal U4's;
  - `/mnt/persist/mibsdk` is kept (data only), unless the owner wants `--purge`.
- **X3. R3:** reboot. `$M sdk status` shows `sdk: not installed` and the stock lines. The Car drawer and MENU ring
  are stock. Photos.
- **X4 (if the owner keeps the SDK instead):** skip X1–X3, leave mibsdk 0.3.0, bike-rack (disarmed) and
  daemon-status installed, and remove inputs-demo and the probe.

## Restart budget

| # | Why | Expected |
|---|---|---|
| R0 | after uninstalling carplay-rgi; tests SSH after a reboot | required |
| R1 | SDK 0.2.0 boot (O1 setup) | required |
| R2 | SDK 0.3.0 boot | required |
| R3 | after the SDK uninstall: stock check (also P3's boot window if not done earlier) | required unless X4 |
| R4 | after the carplay-rgi reinstall (U6) | if the owner wants CarPlay back |
| spare ×1 | P3 or a retry | optional |

Ignition cycles for F7.4 are not counted as restarts. **Stop adding restarts after six**, and finish with X or R-E.

## End state

Choose one with the owner before the session.

- **(a) Default — CarPlay back.**
  - mibpkg 0.9.1;
  - no SDK (the bundles file is byte-equal to B1, and the marker state is as in B2);
  - carplay-rgi reinstalled from the same release;
  - carmqtt and tailscale running, with carmqtt `mqtt=1`.
- **(b) SDK kept, no CarPlay until the repackage** (X4). bike-rack stays disarmed, and the rear tones are audible.

In both:
- `/mnt/app` and `/mnt/system` are read-only;
- `/mnt/persist/carsession/` stays as a record (it is small; delete it later);
- the unit is on its home screen;
- **(E1) the rear tones are audible in R**.

## Rollback criteria

Roll back at once, using X, then U6 (or R-E, then U6), if any of these happens:
- no healthy HMI within 10 minutes of SSH returning after an SDK boot;
- `SAFE` on the status line, or safe mode in the log, when it was not expected;
- any WARN/ERROR from mibpkg about the bundles file or backups;
- the rear tones still off after F7.3 plus an ignition cycle;
- carmqtt or tailscale not coming back after M2;
- the owner wants to stop.

The session also ends when SSH does not come back after R0, or when U4 fails.
