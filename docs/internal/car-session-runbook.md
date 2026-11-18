> **Internal engineering record.** This page is kept for history and may be out of date. See [the internal docs index](README.md).

# Car session runbook (Plan 4)

This runbook is for the owner's Audi (MHI2Q). It is based on the bench runbooks (`docs/bench-e2e*.md`), the
archived reports (`.superpowers/sdd-archive/`) and the compatibility audit
(`docs/research/2026-10-26-carplay-rgi-compat.md`). Nothing here has run on the car yet. The host preparation
was done on 2026-10-26, and its report is `~/complex-projects/car-session-2026-10-26/PREP-REPORT.md`.

**Path:**
1. Uninstall carplay-rgi and check that the unit is stock.
2. Install SDK 0.3.0 and the plugins, then run the checks.
3. Uninstall the SDK with a byte-identical restore, and check stock again.
4. Install the **latest** carplay-rgi (`a63d5474`), and check that CarPlay works.

mibpkg refuses the SDK while carplay-rgi is on the unit (audit §2.2). The owner has agreed to remove it for the
session.

**Owner decisions (binding):**
- **End state:** CarPlay back, no SDK, carplay-rgi upgraded to the latest build. The session ends with step 4.
- **Fallback uninstaller:** carplay-rgi's own `custom.sh uninstall`, run over SSH, is approved if the M.I.B.
  uninstall card restores nothing (U2).
- **Not in this session:**
  - the Kombi brightness check (see "Not in this session");
  - O1, the old runtime refusing an api-5 plugin. It is proven on the host (H1, `docs/bench-e2e-native-inputs.md`),
    so SDK 0.2.0 is not installed on the car.
- **Captures:** mib-keypanel is not on the car, so every capture is an owner phone photo. The photo list says when
  to take each one and what it must show.

## Owner checklist (read first)

**Bring:**
- The laptop with this repo, the mibpkg repo and `~/complex-projects/car-session-2026-10-26/`, charged or on a 12 V
  charger. That folder holds every package (P1) and `stock-sha256.txt`.
- The M.I.B. SD card, plus the two carplay-rgi card sets from `car-session-2026-10-26/carplay-rgi/`. Both are
  build `a63d5474`, the latest on `origin/main`:
  - the uninstall card, `uninstall_MoreIncredibleBash/mod/`;
  - the install card, `install_MoreIncredibleBash/mod/` with `mod/carplay/`. It is the reinstall at the end, and
    the fallback uninstaller.

  A second SD card (or a USB stick) saves swapping `mod/` folders on the car.
- The iPhone and its USB cable, for the CarPlay check at the end.
- An object for the rear sensors: a box or bin about 50–80 cm tall.
- A phone for photos, with space for about 25 photos and 3 short videos.

**Where and how:**
- Park on level ground with clear space behind (and in front of) the car, away from traffic. Use your own drive
  or an empty car park.
- Keep the engine running or the battery on a maintainer: there are four reboots with the ignition on.
- Put the laptop on the car's Wi-Fi hotspot (`ssh audi` = `root@10.173.189.1`), or on a hotspot both can reach.

**Safety rules for every check:**
- The car is **parked, with the parking brake on**.
- Select reverse only while stationary, **with your foot on the brake**. Never drive during a check.
- Never drive off with the bike rack "armed": the rear tones are off then. The end state must have rear tones
  audible again (E1).

**Time:** about 2 hours on the car (see "Time plan"). The host pre-flight is done.

**Stop rule:** if any **STOP** gate below fails, do not continue. Run its rollback, and leave the car as it was.

## Photo list (owner, phone)

Name each photo with its ID, for example `PH07`. Write the ID and the time in the session notes. The laptop
operator calls out each one.

| ID | When | It must show |
|---|---|---|
| PH01 | S1, before any write | REM → Version information: the `Current version` line (`MHI2Q_…`) |
| PH02 | S5, before any write | Sound menu → Sound effects with the **UpMix** row (carplay-rgi active) |
| PH03 | U2, at the end of the uninstall card | The whole M.I.B. console: the `restored …` and `removed …` lines and `DONE (uninstall). Reboot the HU.` Take two photos if it scrolls |
| PH04 | U4, after R0 | Sound → Sound effects with **no UpMix row** |
| PH05 | U4, after R0 | The TEL key pressed: the stock phone screen, with no CarPlay disclaimer |
| PH06 | R1, the first seconds after the HMI shows (P3) | Foot on the brake, R: the camera image and PDC. Take one every 3–5 s for about 30 s, while the status line passes from popup 65 to 609600 |
| PH07 | I3, after R1 | The Car screen drawer open, showing the **Bike rack** and **Plugins** rows |
| PH08 | I3, after R1 | The MENU ring with the **Plugins** entry, and the status line (top right) |
| PH09 + video V1 | F7.0 | R with the object behind: the PDC display shows it. The video has the rear tones |
| PH10 | F7.1 | The status line with **`PDC R OFF`** (and `RACK`) |
| V2 | F7.2 | R with the object behind: the PDC display still shows it, and **no rear tone** |
| PH11 + video V3 | F7.3 | `PDC R OFF` gone. In R, the rear tones are back |
| PH12 | F7.4 step 5 | After the quick ignition cycle: `PDC R OFF` is shown again |
| PH13 | P1, from home | R: the camera and PDC, with nothing from the status line or a toast over them |
| PH14 | P1, from the Car screen | The same |
| PH15 | P1, from media | The same |
| PH16 | P2 | Rack armed, R: `PDC R OFF` is not over the camera or PDC |
| PH17 | P4 | A toast was triggered, then R within 2 s: the toast is not over the camera or PDC |
| PH18 | Q2 | Plugins → Daemon Status showing `MQ up` |
| PH19 | X3, after R2 | The Car drawer as stock (no Bike rack or Plugins rows) |
| PH20 | X3, after R2 | The MENU ring as stock (no Plugins) |
| PH21 | V2 (end), at the end of the install card | The whole M.I.B. console with `DONE (install). Reboot the HU to load.` and the `dio_manager.json …` line |
| PH22 | V5, after R3 | CarPlay on the main screen (the home screen of CarPlay) |
| PH23 | V5, after R3 | CarPlay maps in the virtual cockpit, if CarPlay navigation is running |
| PH24 | V5, after R3 | Sound → Sound effects with the **UpMix** row back |
| PH25 | End | The unit on its home screen with CarPlay disconnected, and then the phone reconnected |

Never photograph a screen that shows the VIN, such as the REM "Vehicle" page or a Home Assistant device page.

## Rules for the operator (laptop)

- **Never print or log the VIN or IMSI.** Never read `lsd.sh`, the j9 command line (`pidin` with arguments,
  `ps`, `/proc/*`), the full `version_info.txt`, `carmqtt.toml` or the full carmqtt log.
- Use `mosquitto_sub` only without `-v`, so no topic (which holds the VIN) is printed.
- When reading the bundles file, `grep` only the `HMI*=`, `SM*=` and `MibSdk` lines.
- Redact any line that would carry the VIN before it goes into a report.
- Unit shell limits (`mibpkg/docs/unit-facts.md`): the SSH shell has no `cmp`, `od`, `wc`, `tail`, `printf` or
  `find`. Stock firmware does have `find`, `cmp` and `od` in `/mnt/app/armle/usr/bin/`, but that is not on the
  SSH `PATH`.
  - Compare files on the laptop: `ssh audi cat <f> > <local>`, then `shasum -a 256`. The stock values are in
    `car-session-2026-10-26/stock-sha256.txt` (from the owner's MMX dump).
  - Nothing is written to `/mnt/app` by hand except the emergency revert (R-E).
- Throughout, `U=audi` (or the unit's IP), `M=/tmp/mibpkg`, `D=installer/mibsdk-dev` (its host argument goes
  last: `$D status 10.173.189.1`), and `P=~/complex-projects/car-session-2026-10-26`.
- Write every observation, its time and the photo ID into `docs/car-session-<date>.md` as you go.

## P. Pre-flight on the host (done 2026-10-26)

### P1. Builds

All the packages are in `car-session-2026-10-26/`, each listed in its `SHA256SUMS`. They are release versions, with
no bench tags: the car has no SDK and no plugin, and mibpkg goes 0.6/0.7 → 0.9.1.

| Package | Source | Bytes | sha256 (first 16) | Why |
|---|---|---|---|---|
| `mibpkg-0.9.1.mpk` | mibpkg main `a0a5e49` | 1149704 | `a6547e0eed41aa5c` | The car has 0.6.0 or 0.7.0 (audit §4) |
| `fallback/mibpkg-0.8.1.mpk` | mibpkg `a53963e` (the 0.8.1 merge) | 1132021 | `25cc53270602a680` | Only if 0.9.1 refuses to install over 0.6/0.7 |
| `host/mibpkg-0.9.1-host-darwin-arm64` | mibpkg main `a0a5e49` | 2216048 | `efa66c6e78eed5c1` | The host binary for `--root` checks |
| `mibsdk-0.3.0.mpk` | SDK main (`7932f35` code) | 697447 | `48a8606db6e42818` | The SDK under test |
| `bike-rack-1.1.0.mpk` (api 3) | SDK main | 28019 | `602f0f222945eec7` | F7, status line |
| `daemon-status-1.1.0.mpk` (api 3) | SDK main | 6465 | `993fbf40c2532bd8` | Status line, carmqtt and tailscale view (Q) |
| `inputs-demo-1.0.0.mpk` (api 5) | SDK main | 7774 | `1c11e91efd97fa47` | The toast (P4) |
| `hello-menu-1.2.0.mpk` (api 4) | SDK main | 6977 | `f8efc2c64c2edf75` | Spare. It is not in the plan |
| `carplay-rgi/carplay-rgi-a63d5474-install.zip` | carplay-rgi `a63d5474` | 2018170 | `386799d467e4ac54` | The reinstall (V), and the fallback uninstaller |
| `carplay-rgi/carplay-rgi-a63d5474-uninstall.zip` | carplay-rgi `a63d5474` | 2194 | `c5c9094c59f8d7af` | U2 |

- `mibsdk-0.2.0.mpk` is not built, because O1 is not run on the car.
- **Do not build or install carmqtt or tailscale.** The car has carmqtt 0.2.3 (`unit-facts.md`, 2026-10-14) and
  the repo is at 0.2.2. The session only checks the installed carmqtt.
- The new carplay-rgi jar is `carplay_hook.jar`, 311160 bytes, sha256 `6f9b57bfc440764d…`, with `BUILD_ID`
  `MHI2Q-2026-10-21-11-ga63d5474`.

### P1a. Fake-root dry run (pass)

The dry run used a stock root built from the owner's MMX dump (`~/projects/audi-mib2/firmware/mmxfs`, with no
`dsitracer_activated`). It ran from both mibpkg 0.6.0 and mibpkg 0.7.0, and every check passed. The details are in
`PREP-REPORT.md`, and the script is `car-session-2026-10-26/host-checks/dryrun.sh`. The run checked these steps:
- **M2:** `gem install-file mibpkg` gave `updated mibpkg 0.x -> 0.9.1`, and carmqtt was kept.
- **The guard:** a `carplay_hook.jar`, a `lang_data.zip.carplay-stock` and a `*.carplay-stock` outside `jars/` were
  each refused, and nothing changed.
- **SDK 0.3.0, then the three plugins:**
  - `sdk.properties` has no `chain.configurator`;
  - the plugins changed nothing in the bundles file.
- **Removing the plugins, then the SDK:**
  - the bundles file came back byte- and mode-equal;
  - the marker was absent again;
  - `lsd/jars` was equal to stock;
  - carmqtt was still installed.

The carplay-rgi `a63d5474` install card was also run on a fake stock unit, followed by each of its uninstallers.
See `host-checks/cprgi_uninstall_check.out`:
- **The uninstall card and the install card's `custom.sh uninstall`:** every touched stock file comes back
  byte-equal. The modes of the three NCM files are an exception (U4).
- **The uninstall card without `find`:** 0 `restored` lines, yet it still prints `DONE`. This is the case the
  fallback in U2 is for.

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
| `$D restart <ip>` (`slay -s11 j9`) | The tested method. On the bench it resets the whole unit (F3). |
| Owner: hold the head unit's power button about 10 s, until the screen goes black and the Audi logo returns | Whenever SSH is gone. |
| Ignition off, lock the car, wait about 10 min for the unit to sleep | Last resort. |

**Is SSH back after a reboot?** Not yet known on the car.
- **R0 tests it** before anything SDK-related is installed. The laptop must re-join the car's Wi-Fi after each
  reboot.
- The tailnet address is a second route: the tailnet comes up about 2 minutes after boot (`unit-facts.md`, boot
  timeline).
- If SSH does not come back within 10 minutes of R0, **stop the session** and reinstall carplay-rgi from the
  install card (V). It runs from M.I.B. and needs no SSH. Without SSH there is no SDK revert path.

**HMI boot loop** (Audi logo again and again)
- The SDK's boot guard goes to safe mode after 3 unhealthy boots: `SAFE` on the status line, no plugins, stock UI.
- If SSH answers in between, run R-E at once.
- carplay-rgi's recovery notes: `docs/build-and-deploy/ISSUE24_RCC_MMX_RECOVERY.md` in the carplay repo.

## S. Connect and read-only survey

Nothing is written in this section.

- **S0 — connect:** `ssh audi 'echo ok'`. Old OpenSSH needs `-o HostKeyAlgorithms=ssh-rsa -o PubkeyAcceptedKeyTypes=ssh-rsa`,
  which are already in `~/.ssh/config`.
- **S1 — HMI build:** `ssh audi "grep '^HMI' /ifs/version_info.txt"`.
  - It must give `HMI_AU_MIB2_HQC_NAR_G22_C10_180803`.
  - Cross-check with the REM (**PH01**): Version information → Current version starts with `MHI2Q_`, and the
    train should be `MHI2Q_US_AUG22_P5087`.
  - **STOP** if the build differs (mibpkg would refuse anyway).
- **S2 — mibpkg:** `ssh audi '/tmp/mibpkg status; /tmp/mibpkg list'`. Record the version (0.6.0 or 0.7.0
  expected) and carmqtt's version.
- **S3 — bundles lines only:**
  ```
  ssh audi "grep -n '^HMI[A-Za-z]*=\|^SM[A-Za-z]*=\|MibSdk' /mnt/app/eso/hmi/lsd/bundles_dsitracer_enabled.properties"
  ```
  - Expect these stock lines: 61 `SMSystem=de.audi.tghu.system.sm.Activator`, 62
    `HMISystem=…system.hmi.Activator`, 74 `HMITone=…tone.hmi.Activator` and 121 `HMICar=…car.hmi.Activator`.
  - There must be no `MibSdk` line. **STOP** if the lines are not stock.
  - The bundles file's sha256 should equal `stock-sha256.txt`. A difference is recorded; S3's lines decide.
- **S4 — lsd tree:** `ssh audi 'ls -l /mnt/app/eso/hmi/lsd/ /mnt/app/eso/hmi/lsd/jars/'`. Record:
  - whether `dsitracer_activated` exists (expected: no);
  - whether `DSITracer.jar` exists (it is needed).
- **S5 — carplay-rgi state:**
  - `ssh audi 'ls -l /mnt/app/eso/hmi/lsd/jars/ | grep -i carplay; ls -l /mnt/app/root/hooks/'`;
  - `ssh audi 'ls -R /mnt/app/eso/hmi /mnt/system/etc /mnt/app/eso/bin/apps /mnt/app/eso/lib /mnt/app/armle | grep carplay-stock'`;
  - copy `carplay_hook.jar` to the laptop and read its `CarPlayApp` `BUILD_ID` string there (`unzip -p … com/luka/carplay/core/CarPlayApp.class | strings | grep MHI2Q`),
    to identify the release on the car. Any release works with the a63d5474 uninstall card. Record it for the
    change summary;
  - **PH02** (UpMix row present).
- **S6 — free space:** `ssh audi 'df -k /mnt/app /mnt/system /mnt/persist'`.
  - **STOP** if `/mnt/app` has less than 20 MB free or `/mnt/persist` less than 1 MB.
  - mibpkg keeps the previous versions. The SDK is about 0.7 MB, and the new carplay-rgi payload is about 5 MB
    (written after the SDK is gone).
- **S7 — daemons:** run `$D status`-style reads only after mibpkg is updated. For now, check that tailscale and
  carmqtt are running:
  - `ssh audi "/tmp/mibpkg status"`;
  - `ssh audi "grep -c 'mqtt connected' /tmp/carmqtt.log; grep -c 'waiting for VIN' /tmp/carmqtt.log"`.

## B. Backups (before any write)

Make these on the laptop and in `/mnt/persist/carsession/`. That partition is qnx6 and read-write without a
remount, but it is small, so keep only small files there.

- **B1 — bundles file:**
  - `ssh audi 'mkdir -p /mnt/persist/carsession && cp -p /mnt/app/eso/hmi/lsd/bundles_dsitracer_enabled.properties /mnt/persist/carsession/bundles.pre && ls -l /mnt/persist/carsession'`;
  - `ssh audi cat /mnt/app/eso/hmi/lsd/bundles_dsitracer_enabled.properties > car/bundles.pre`;
  - the sha256 of the laptop copy must equal the sha256 of a second `cat` of the persist copy.
- **B2 — marker state:** `ls -l` of `dsitracer_activated` into `car/lsd-ls.pre` (and `/mnt/persist/carsession/lsd-ls.pre`).
- **B3 — laptop only (larger files):**
  - every file in `lsd/jars/`, including `lang_data.zip` and `carplay_hook.jar`;
  - `smartphone_integrator.json`, `dio_manager.json`, `/mnt/system/etc/pf*.conf`, `mcd.conf`, `startncm.sh`,
    `stopncm.sh` and `usblauncher_otg.lua`;
  - each `*.carplay-stock` found in S5.

  Record each file's sha256 and `ls -l` mode in `car/sha256.pre`.
- **B4 — mibpkg state listing:** `ssh audi 'ls -lR /mnt/app/root/mibpkg/state' > car/mibpkg-state.pre`. Do not copy
  `state/config` (it holds secrets).

## M. mibpkg to 0.9.1

- **M1.** Upload `mibpkg-0.9.1.mpk` to `/tmp/mibpkg.upload.<uid>.mpk`
  (`ssh audi "cat > /tmp/mibpkg.upload.<uid>.mpk" < $P/mibpkg-0.9.1.mpk`). Check its size on the unit (1149704).
- **M2.** Run `ssh audi '/tmp/mibpkg gem install-file mibpkg <uid> -'`.
  - 0.6 and 0.7 have no top-level `install-file` (P3-F1).
  - Expect `OK: updated mibpkg 0.x -> 0.9.1; mibpkg restarts itself`. This passed on the host from both 0.6.0
    and 0.7.0.
  - If it is refused, install `fallback/mibpkg-0.8.1.mpk` the same way first. Then install 0.9.1 with
    `/tmp/mibpkg install-file mibpkg <uid> -`.
- **M3.** Within 15 s, `$M status` must show `supervisor 0.9.1 … (running)`, and `$M list` must show carmqtt
  unchanged and running.
  - **STOP** if carmqtt or tailscale did not come back.
  - Rollback: `$M rollback mibpkg`.
- **M4.** `$M sdk status` should say `sdk: not installed`, with the stock `HMICar=` and `HMISystem=` lines.

## U. Uninstall carplay-rgi and check stock

- **U1. Dry check:** `MIBSDK_MPK=$P/mibsdk-0.3.0.mpk $D install 10.173.189.1` must be **refused** with `carplay-rgi is
  installed … nothing changed`. This confirms the guard on the car. Check the bundles file sha against B1.
- **U2. Uninstall with the a63d5474 uninstall card** (audit §5.1 (B)), through M.I.B. as the owner installs it:
  1. Copy `carplay-rgi/uninstall_MoreIncredibleBash/mod/` onto the M.I.B. card as `mod/`.
  2. Disconnect CarPlay.
  3. Run GEM → M.I.B. → Advanced → Run Custom Script (on an older M.I.B.: Run individual script).
  4. Expect `restored …` lines, `removed …` lines, a 20 s wait and `DONE (uninstall). Reboot the HU.` Take
     **PH03**.
     - On a unit with a release up to 10-21, expect 12 `restored` lines: `lang_data.zip`, the SI binary,
       `smartphone_integrator.json`, `dio_manager.json`, the 4 pf profiles, `deva-ctrl-qc.so` and the 3 NCM files.
     - A later release (`a63d5474`) gives 15, which adds `libairplay.so`, `mcd` and `mcd.conf`.
  - **The card uses `find`.** It sets its own `PATH`, which includes `/mnt/app/armle/usr/bin`, and stock firmware
    has `find` there. So it is expected to work, even though the SSH shell has no `find`.
  - **The fallback (approved by the owner): if there is no `restored` line at all, `find` failed.** Before the
    reboot, do this:
    1. Put the a63d5474 install card in (`mod/custom.sh` and `mod/carplay/`).
    2. Run `ssh audi 'sh /fs/sda0/mod/custom.sh uninstall'` (audit §5.1 (A)). If the card is not on `/fs/sda0`,
       check `/fs/sdb0`.
    3. It needs no `find`. It restores each known backup path by name (SI json, dio, SI binary, `libairplay.so`,
       `mcd`, `mcd.conf`, `lang_data.zip`, NCM and pf) and the replaced files in its manifest. It removes the
       files the manifest lists.
    4. Expect `restored /mnt/app/eso/hmi/lsd/jars/lang_data.zip` and `DONE (uninstall). Reboot the HU.` Take a
       photo of the SSH output.
    - Files that only an older release owned, such as `hssr_*.bin` in `/mnt/app/root/hooks`, may stay. They are
      not in `lsd/jars` and not under `/mnt/app/eso/hmi`, so mibpkg's guard ignores them. Record them.
- **U3. R0, the first reboot:** `$D restart` (or the power button).
  - Time how long until SSH answers again over the car Wi-Fi, and over the tailnet.
  - Note whether the laptop had to re-join.
  - **STOP** if there is no SSH after 10 minutes (see R).
- **U4. Stock check** (all must hold):
  - no `*carplay*` in `lsd/jars`, and the S5 `ls -R … | grep carplay-stock` prints nothing;
  - every `lsd/jars` file, including `lang_data.zip` (502034 bytes), is byte-equal to `stock-sha256.txt` (by
    sha256, on the laptop). A difference is recorded, and the go decision is the owner's;
  - the bundles file is byte-equal to B1, and S3's lines are stock;
  - each B3 file that had a `.carplay-stock` backup now equals that backup by sha256, and also equals
    `stock-sha256.txt`;
  - on screen, the Sound menu has no UpMix row (**PH04**), and the TEL key shows the stock path (**PH05**);
  - `$M sdk status` shows `sdk: not installed`, with stock lines.

  `startncm.sh` and `stopncm.sh` may come back with mode 755, and `usblauncher_otg.lua` with mode 644. carplay-rgi
  writes those backups from its card with fixed modes. The bytes are stock, so record the mode and go on.
- **U5. Go/STOP.** Go only if U4 holds.
  - If it fails, skip to V (reinstall carplay-rgi) and end the session.

## I. SDK install

- **I1. Install mibsdk 0.3.0.** `MIBSDK_MPK=$P/mibsdk-0.3.0.mpk $D install <ip>`.
  - Expect `installed mibsdk 0.3.0; reboot …`.
  - The live bundles lines should now be `HMICar=mibsdk.adapter.hqc.ui.SdkCarActivator`,
    `HMISystem=mibsdk.adapter.hqc.ui.SdkSystemActivator` and the `MibSdk` bundle.
  - `dsitracer_activated` now exists. mibpkg records it as "absent" in its backups.
  - `sdk.properties` has no `chain.configurator`.
- **I2. Plugins:**
  - `$D install-plugin $P/bike-rack-1.1.0.mpk --approve=vehicle.read,vehicle.parking.write,ui.slot.carmenu,ui.slot.statusbar <ip>`;
  - `$D install-plugin $P/daemon-status-1.1.0.mpk --approve=network,ui.slot.statusbar <ip>`;
  - `$D install-plugin $P/inputs-demo-1.0.0.mpk --approve=ui.slot.statusbar <ip>`.
  - Each says `installed … ; the SDK is not running; it loads at the next boot`. `$D status` shows sdk 0.3.0,
    api 1..5, 3 plugins, `reboot needed: yes` and ledger 0.
- **I3. R1:** `$D restart` into 0.3.0.
  - Be ready for P3 (**PH06**): the owner sits in the car with a foot on the brake, so that R can be selected as
    soon as the HMI shows.
  - **Go** only if the log has all of these:
    - `runtime started … safeMode=false`;
    - `ui car=sdk` and `ui system=sdk`;
    - `ui status popup 609600 registered` and `ui toast popup 609601 registered`;
    - 3 × ENABLED and `boot healthy: 3 plugin(s)`.
  - On screen: the Car drawer has Bike rack and Plugins rows (**PH07**), and the MENU ring has Plugins (**PH08**).
  - **STOP** and run X (then V) if the HMI does not come up healthy within 10 minutes of SSH returning. Use R-E
    if mibpkg cannot run.

## C. Checks (SDK 0.3.0 running)

There is no keypanel on the car, so every capture is an owner phone photo or video (see the photo list). Copy the
`$D log` lines for each check to the laptop as well.

### F7 — bike-rack override end to end, against the real PDC ECU

- **F7.0 Baseline.** Place the object about 50 cm behind the middle of the rear bumper.
  - Owner: foot on the brake, select R, stationary. The rear tones sound and the PDC shows the obstacle
    (**PH09 + V1**).
  - Select P.
  - `$D status`: `ledger: 0 override(s)`.
- **F7.1 Arm.** Car → drawer → Bike rack → "Rack fitted (rear PDC tones off)" on.
  - The log has `rear parking tones muted` and an `override parking.soundDirections=… by bike-rack
    (UNTIL_IGNITION_OFF), prior …` line.
  - The ledger file has one `parking.soundDirections=bike-rack|UNTIL_IGNITION_OFF|<prior>|<active>|A` line.
  - The status line shows **`PDC R OFF`** (and `RACK`) (**PH10**).
- **F7.2 Real object, muted.** Foot on the brake, R, stationary.
  - The PDC display still shows the rear obstacle, and **the rear tones are silent** (**V2**).
  - Optional: a hand near a front sensor still beeps, so the front is unchanged.
  - Select P. Record whether the ECU sent an `updatePDCSoundReproduction` for the write (the I4 car question, see
    `car-readiness.md`).
- **F7.3 Disarm and restore.** Toggle off.
  - The status line keeps `PDC R OFF` while restoring.
  - Then the log has `restored parking.soundDirections=<prior> (confirmed by the car) (override of bike-rack
    ended)`. This is the restore observed through the parking update hook (HookObserved / `PARKING_SOUND_UPDATE`).
    `(… last report …)` as a WARN is acceptable and must be recorded.
  - `PDC R OFF` disappears. `$D status` shows `ledger: 0 override(s)`, and the ledger file is empty.
  - R with foot on the brake: the rear tones sound again (**PH11 + V3**).
- **F7.4 Ignition-off restore (T18 and M6 on the car).**
  1. Arm again (F7.1).
  2. Ignition off. Wait 10 s, so the 5 s re-arm window is over.
  3. Ignition on.
  4. The log has a restore at ignition off. The rack is disarmed, the ledger is empty, and the rear tones sound
     (R, foot on the brake).
  5. Then arm, ignition off, and ignition on **within 5 s**: the rack re-arms and `PDC R OFF` shows (**PH12**).
     Disarm.
- **F7 pass** = all of F7.1–F7.4 hold, the ledger ends empty and the rear tones are audible.
- **STOP/rollback** if the tones do not return after F7.3:
  1. ignition off and on;
  2. then `$D remove bike-rack`;
  3. then check the stock Car → Parking aid sound settings by hand;
  4. never leave with the tones muted.

### P — the SDK popups vs RVC/PDC (car-only checks in `docs/car-readiness.md`)

- **P1.** On home with the status line (609600, top right x 514..769), foot on the brake and R.
  - Photo the reverse-camera image and the PDC display (**PH13**). Neither 609600 nor the toast (609601, slot 6)
    may cover them.
  - Select P. Repeat from the Car screen (**PH14**) and from media (**PH15**).
- **P2.** Arm the rack, so `PDC R OFF` is on the line, then R (**PH16**): the line is not over the camera or PDC.
  Disarm.
- **P3. Popup 65 in the boot window.** This runs in R1's boot window (I3); use the spare restart only if it was
  missed.
  1. As soon as the HMI shows after the restart: foot on the brake, R. The line is on popup 65 before 609600 takes
     over.
  2. Photo every 3–5 s through the hand-over (**PH06**).
  3. Pass if neither popup ever covers the camera or PDC.
- **P4.** In inputs-demo, trigger a toast (Plugins → Inputs Demo → Toast), then select R within its 2 s (**PH17**).

### Q — carmqtt with the real VIN

1. `ssh audi "grep -c 'waiting for VIN' /tmp/carmqtt.log; grep -c 'mqtt connected' /tmp/carmqtt.log"`. Use counts
   only.
2. Read the status endpoint through daemon-status: Plugins → Daemon Status shows `MQ up` (**PH18**), and the
   status line shows `MQ` up. Or run
   `ssh audi "echo | /mnt/app/armle/usr/bin/nc 127.0.0.1 19851"` if `nc` exists; `telnet` also works. Expect
   `mqtt=1`, a small `last_publish_age_s` and `exlap=1`. The snapshot has no VIN.
3. On the HA host: `mosquitto_sub -h <broker> -u … -t 'mib2q/+/status' -C 1` (**no `-v`**) gives `online`. In
   HA, the device's "Online" entity is on. Do not screenshot the device page, which shows the VIN.

**Pass** = `mqtt=1` after ExLAP gives the VIN, with no VIN printed anywhere.

## X. SDK uninstall and byte-identical restore

- **X1.** Remove the plugins:
  - `$D remove inputs-demo`, `$D remove daemon-status`, `$D remove bike-rack`. Each should say `has no overrides
    in the ledger`;
  - then `$D uninstall`. Expect `removed mibsdk; kept plugin data in /mnt/persist/mibsdk …`.
  - `uninstall` refuses while the ledger has entries. Never use `--force` on the car.
- **X2.** Check before the reboot:
  - the bundles file sha256 equals B1, and its mode is the same;
  - `dsitracer_activated` is absent again, as in B2;
  - no `mibsdk-*.jar` is in `lsd/jars`;
  - `lsd/jars` sha256s equal U4's (and `stock-sha256.txt`);
  - `/mnt/persist/mibsdk` is kept (data only), unless the owner wants `--purge`.
- **X3. R2:** reboot. Then check stock again before carplay-rgi goes back on:
  - `$M sdk status` shows `sdk: not installed` and the stock lines;
  - the Car drawer and the MENU ring are stock (**PH19**, **PH20**);
  - carmqtt and tailscale are running (`$M status`).
  - **STOP** if the HMI is not stock. Run R-E, reboot, and only then go on to V.

## V. Install the latest carplay-rgi (a63d5474) and check CarPlay

- **V1.** Copy `carplay-rgi/install_MoreIncredibleBash/mod/` onto the M.I.B. card as `mod/`. It holds `custom.sh`,
  `command.sh` and `carplay/` with `manifest.txt` and `root/`. CarPlay is disconnected.
- **V2.** Run GEM → M.I.B. → Advanced → Run Custom Script.
  - It reads the train from E2P. P5087 is a verified train, so there is no second-run prompt.
  - If it reports another CarPlay cluster mod, stop and record it.
  - Expect `dio_manager.json: all hook message IDs registered` (or `already registered`), then
    `DONE (install). Reboot the HU to load.` Take **PH21**.
  - A `WARN dio_manager.json …` line means the file had an unexpected layout. Record it: route guidance will not
    reach the cluster until the manual step (carplay-rgi `DEPLOY_AND_RELEASE.md`) is done.
- **V3. Check on disk, before the reboot:**
  - `ssh audi 'ls -l /mnt/app/eso/hmi/lsd/jars/ | grep -i carplay'` shows only `carplay_hook.jar` (the CN jar is
    removed for region US);
  - its sha256 on the laptop is `6f9b57bfc440764d…` (`carplay-rgi/SHA256SUMS`), and its `BUILD_ID` is
    `MHI2Q-2026-10-21-11-ga63d5474`;
  - `ssh audi "grep -c carplay_startup.sh /mnt/system/etc/eso/production/smartphone_integrator.json; grep -c '\"0x5204\"' /mnt/system/etc/eso/production/dio_manager.json; grep -c '\"0x4171\"' /mnt/system/etc/eso/production/dio_manager.json; grep -c '6030:6032' /mnt/system/etc/pf.conf /mnt/system/etc/pf.ecm0.conf"`.
    Each count must be 1 or more;
  - the bundles file is still byte-equal to B1 (carplay-rgi never edits it).
- **V4. R3:** reboot. Wait for SSH, then `$M status`: carmqtt and tailscale are running.
- **V5. CarPlay works:**
  1. Connect the iPhone by USB. CarPlay starts on the main screen (**PH22**).
  2. Play audio, and make sure that it is heard.
  3. Start CarPlay navigation for a moment. If the cockpit shows the maps, take **PH23**.
  4. Sound → Sound effects has the UpMix row again (**PH24**).
  5. `ssh audi "grep -c ERROR /tmp/carplay_hook.log"` gives a count only, which is recorded.
  - If CarPlay does not start, collect logs with carplay-rgi's `logging_MoreIncredibleBash` card (build it from the
    repo; it deletes nothing). Leave the install in place, and record it as an open issue.
- **V6.** Disconnect CarPlay, go to the home screen (**PH25**), and reconnect if the owner wants.

## Restart budget

| # | Why | Expected |
|---|---|---|
| R0 | after uninstalling carplay-rgi; tests SSH after a reboot | required |
| R1 | the SDK 0.3.0 boot, which is also P3's boot window | required |
| R2 | after the SDK uninstall: stock check | required |
| R3 | after the carplay-rgi reinstall | required |
| spare ×1 | P3 if it was missed, or a retry | optional |

Ignition cycles for F7.4 are not counted as restarts. **Stop adding restarts after six**, and finish with X and V,
or with R-E and V.

## Time plan

Each reboot is planned at 5–10 minutes until SSH answers. The bench took 4–5 minutes and more per restart (F3).
Part of that was the bench's DHCP kick (`keypanel up`), which the car does not need.

| Step | Minutes |
|---|---|
| Set-up: park, laptop on the car Wi-Fi, S0 | 5 |
| S1–S7 survey (PH01, PH02) and B1–B4 backups | 12 |
| M: mibpkg to 0.9.1 | 5 |
| U1–U2: guard check and the uninstall card (PH03) | 10 |
| U3: R0 | 7 |
| U4–U5: stock check (PH04, PH05) | 8 |
| I1–I2: SDK and plugins | 5 |
| I3: R1, with P3 in its boot window (PH06–PH08) | 10 |
| F7.0–F7.4 | 18 |
| P1, P2, P4 | 8 |
| Q | 5 |
| X1–X2: SDK uninstall and checks | 5 |
| X3: R2 and the stock check (PH19, PH20) | 10 |
| V1–V3: carplay-rgi install card and checks (PH21) | 8 |
| V4: R3 | 7 |
| V5–V6: CarPlay check (PH22–PH25) | 8 |
| **Total** | **about 2 h 10 min** |

- Each reboot row uses 7 minutes, the middle of 5–10. With every reboot at 10 minutes, the total is about
  2 h 25 min.
- Add 10 minutes for each spare restart or retry.

## End state

This is fixed by the owner:
- mibpkg 0.9.1;
- no SDK: the bundles file is byte-equal to B1, and the marker state is as in B2;
- carplay-rgi `a63d5474` installed, with CarPlay working (V5);
- carmqtt and tailscale running, with carmqtt `mqtt=1`;
- `/mnt/app` and `/mnt/system` read-only;
- `/mnt/persist/carsession/` stays as a record (it is small; delete it later). `/mnt/persist/mibsdk` (plugin data)
  stays unless the owner wants `--purge`;
- the unit on its home screen;
- **(E1) the rear tones audible in R**.

After this, the SDK cannot be installed again until carplay-rgi is repackaged onto it (audit §2.2, §5.3).

## Rollback criteria

Roll back at once, using X and then V (or R-E and then V), if any of these happens:
- no healthy HMI within 10 minutes of SSH returning after an SDK boot;
- `SAFE` on the status line, or safe mode in the log, when it was not expected;
- any WARN/ERROR from mibpkg about the bundles file or backups;
- the rear tones still off after F7.3 plus an ignition cycle;
- carmqtt or tailscale not coming back after M2;
- the owner wants to stop.

The session also ends, with V, when SSH does not come back after R0 or when U4 fails.

## Not in this session

- **K — Kombi brightness (`DSICarKombi.setDCBrightness`)** is skipped. The SDK has no API for it
  (`.superpowers/sdd-archive/can-brightness-research.md`, §2–3), and no probe plugin was written.
  - **Correction:** the bench **does** have a Kombi. The earlier plan assumed it did not, and that a probe could
    only log "no Kombi" there. So a future probe plugin (with `platform.unrestricted`) can be tested on the bench
    first. It would:
    1. read `DCViewOptions.brightness` and `updateDCBrightness`;
    2. make one `setDCBrightness(v)`;
    3. restore the read value.
  - It does not need a car session.
- **O1 — the old runtime refusing an api-5 plugin** is proven on the host (H1, `docs/bench-e2e-native-inputs.md`).
  It was not shown on the bench, because the unit was in safe mode. It is not repeated on the car.
