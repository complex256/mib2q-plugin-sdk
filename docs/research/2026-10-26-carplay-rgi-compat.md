# carplay-rgi vs mibsdk: compatibility audit for the car session

Date: 2026-10-26. Host-only, read-only. No car or bench unit was contacted. Input for Plan 4 and
`docs/car-session-runbook.md`.

**Verdict: CONFLICT. It is a policy conflict, not a class conflict.** mibpkg refuses to install the SDK while
carplay-rgi is on the unit (§2.2). In the source, the two do not shadow the same class, edit the same bundle line
or depend on one popup slot (§2, §3). **Resolution for the session: uninstall carplay-rgi first with its own
uninstaller, check that the unit is stock, then install the SDK** (§5). The owner has accepted this, because
carplay-rgi is going to be repackaged anyway.

## 0. Sources and checkouts

- **carplay-rgi, live repo:** `~/complex-projects/mib2q-carplay-rgi`. Its `origin` is the stash mirror of the
  upstream GitHub repo, and `upstream-fork` is `complex256/mib2q-carplay-rgi`. Local `main` is at `c475724c`
  (2026-09-24), 55 commits behind `origin/main`.
  - **This audit reads `origin/main` at `a63d5474` as fetched in that clone (tag `MHI2Q-2026-10-21` is 10
    commits below it).** It was not re-fetched, so do not modify that repo. The remote may be newer.
  - `~/projects/mib2q-carplay-rgi` is an older clone of `luka-dev/mib2q-carplay-rgi` at `f36790d` (2026-09-08).
    It has no Tone or System activators and was not used.
- Paths written as `custom.sh:N` are `install_MoreIncredibleBash/mod/custom.sh` at `origin/main`.
  `uninstall custom.sh:N` is `uninstall_MoreIncredibleBash/mod/custom.sh` at `origin/main`. Java paths are under
  `java_patch/` at `origin/main`.
- SDK: main `7932f35`, mibsdk 0.3.0. mibpkg: main `a0a5e49`, 0.9.1.
- Stock reference: `~/projects/audi-mib2/firmware/mmxfs/app/eso/hmi/` (the owner's unit, MU1316
  `MHI2Q_US_AUG22_P5087`; `docs/research/appendix-a-inventory.md:3-13`). The decompiled stock classes are in
  `~/projects/audi-mib2/patching/lsd_java/`.

## 1. What carplay-rgi installs, and how

**Packaging.** carplay-rgi is **not a mibpkg package**.
- `origin/main` has no `manifest.toml` and no mibpkg reference outside its research docs (`git grep -i mibpkg`).
- It ships as an M.I.B. SD-card mod: `install_MoreIncredibleBash/mod/{custom.sh,command.sh}` with the payload
  in `mod/carplay/`. It is run from GEM → M.I.B. → Advanced → Run Custom Script (`custom.sh:2-4`).
- So it expects no mibpkg version at all. The two only meet on the unit's filesystem.

**Install mechanism.**
- The payload is a whole-file tree, `carplay/root` → `/` (`custom.sh:6-8`).
- Each file is written as `<f>.carplay-new.<pid>` and then `mv`'d into place (`custom.sh:665-668`).
- Stock files that it replaces or patches get a `.carplay-stock` backup, made once with `cp -p`
  (`backup_once`, `custom.sh:150`; `is_replaced`, `custom.sh:51`). Its own files have no backup.
- **Firmware gate:** the train is read from E2P (`custom.sh:85-90`). Only `MHI2Q_US_AUG22_P5087`, `ER_P5092`,
  `ER_P5152`, `JP_P1017` and `CN_K1004` are verified (`custom.sh:128-133`). Any other train is refused once, and a
  second run in the same power cycle forces it.
- The jar variant depends on the region: `carplay_hook.jar` for US/ER/JP and `carplay_hook_cn.jar` for CN
  (`custom.sh:116-121`).

**Jar placement and classpath order.**
- One jar, `/mnt/app/eso/hmi/lsd/jars/carplay_hook.jar`. Any other variant is deleted (`custom.sh:654-660`).
- `lsd.sh` puts every `*.jar`/`*.zip` in `lsd/jars/` on `-Xbootclasspath` **ahead of `lsd.jxe`**, in raw
  `find` order (`docs/research/2026-10-18-java-onboard-map.md:47-57`). That is the whole shadowing mechanism.
- Duplicate classes across those jars resolve in an undefined order. That matters only if two jars define the
  same class, and these two do not (§2.1).
- carplay-rgi **does not edit `lsd.sh` or any bundles file.** The word `bundles` appears 0 times in either
  installer, and `lsd.sh` only appears in a comment (`custom.sh:657`).

**Shadowed stock classes** (`origin/main`, 21 `de.*` files; the CN jar patches six of them):

| Stock class (package) | Bundle line it changes, by name | What it changes |
|---|---|---|
| `de.audi.tghu.tone.hmi.Activator` | `HMITone=` (stock list line 74) | The model bank and screen factory come from `com.luka.carplay.tone.UpmixToneUi`, with a stock fallback on any `Throwable`. It adds the Sound effects "UpMix" row (`de/audi/tghu/tone/hmi/Activator.java:16-46`). |
| `de.audi.tghu.system.sm.Activator` | `SMSystem=` (line 61) | The front system SMMs become `com.luka.carplay.telephony.CarPlaySystemSMM` (`de/audi/tghu/system/sm/Activator.java:14-22`). That subclass only adds an action to `checkGuard` for transition 833, branch 0 (the TEL key CarPlay disclaimer) (`com/luka/carplay/telephony/CarPlaySystemSMM.java:25-30`). |
| `de.audi.tghu.phone.sm.Activator` | `SMPhone=` (line 147) | Builds `CarPlayPhoneSMM`. |
| `de.esolutions.hmi.widgets.audi.evo.high.PartialPopupManagerEvoHigh` | none | Hides footer popup 62 (and the entertainment drawer) while CarPlay screen 3200000 shares the stage with pure OPS 2100008 (§3.1). |
| `de.esolutions.hmi.widgets.audi.evo.widgets.StatusBarStubController` | none | Keeps the entertainment drawer hidden under the same guard (§3.2). |
| `de.audi.tghu.fwhmi.DisplayManagerMIB2High` | none | Adds cluster context 80 and gates `switchContext`. |
| `de.audi.app.terminalmode.combi.TerminalModeBapCombi` | none | The bootstrap of `com.luka.carplay.*`. |
| `de.audi.app.earlyfunc.evo.parking.ParkingSystemControllerComponentEvo`, `de.audi.app.earlyfunc.core.parking.ParkingPartialPopupHandler` | none | PDC small-stage guard call-outs (`com.luka.carplay.pdc.PdcSmallStageGuard`). |
| `de.audi.tghu.storage.FlushValuesDict` | none | A shared flush list. |
| The rest: `AppConnectorTerminalMode`(+`$CoverArtProvider`, `CoverArtProviderMux`), `ServiceManagerPhone`, `TerminalModeSDSHandler`, `ExternalEventsListener`, `CarplayDSILifecycleController`, `HighPriorityResourceTracker`, `CarPlaySpeechRequestHandler`, `AudioDrawerContextImpl`, `ClusterService`, `AltScreenCombiBAPListener`, `CombiMapController` | none | Cluster, cover art, Siri, touchpad and audio drawer (table in `docs/research/appendix-b-injection.md:84-106`). |

The "bundle line" column means the stock line is left as it is but now resolves to carplay-rgi's copy of the
class. The line numbers are from the stock `bundles_dsitracer_enabled.properties`
(`mibpkg/tests/fixtures/mibsdk/bundles_dsitracer_enabled.properties:61,62,74,121,147`).

**Other files it changes.** The installer in-place patches these, each with a `.carplay-stock` backup:
- `smartphone_integrator.json` (`custom.sh:236`);
- the pf profiles (`patch_pf`, `custom.sh:342`);
- `dio_manager.json` (`custom.sh:411-436`);
- the NCM scripts and `usblauncher_otg.lua` (`custom.sh:271-335`);
- the `smartphone_integrator` binary, `libairplay.so`, `mcd` and `mcd.conf` (`custom.sh:472-543`);
- **`lsd/jars/lang_data.zip`**, which is on the boot classpath (`custom.sh:448-466`);
- the stock `/mnt/app/armle/lib/dll/deva-ctrl-qc.so`, replaced through the payload tree.

It also **deletes M.I.B.'s `NavActiveIgnore.jar`** if one is present, with no backup (`custom.sh:676-681`).

## 2. The SDK's owned prefixes and mibpkg's install guards

### 2.1 Owned classes: no overlap

- The `mibsdk` manifest owns `de/audi/tghu/car/hmi/`, `de/audi/atip/model/CarModelBank` and
  `de/audi/tghu/system/hmi/` (`build-logic/src/main/java/mibsdk/build/MpkStage.java:65-66`). It manages
  `HMICar=` and `HMISystem=` (`MpkStage.java:61-64`).
- mibpkg's sole-owner guard is `check_owned_classes` (`mibpkg/src/mibsdk/sdk_pkg.rs:235-263`). It refuses when
  a non-`mibsdk-*` jar in `lsd/jars` defines a `.class` under an owned prefix.
- **carplay-rgi touches none of them.**
  - `git ls-tree -r origin/main java_patch` has no path under the three prefixes.
  - The local `build/carplay_hook.jar` (93 classes, older build) has none either.
  - Its System class is `de.audi.tghu.system.**sm**.Activator` (the SMM bundle `SMSystem=`, line 61). That is not
    `de.audi.tghu.system.**hmi**` (`HMISystem=`, line 62).
  - Its Tone class is `de.audi.tghu.tone.hmi`. Nothing is under `de/audi/atip/model/`.
- **This guard would pass.** carplay-rgi also never edits `HMICar=` or `HMISystem=` (§1). The SDK's bundle-line
  check (`sdk_pkg.rs:200-215`) would therefore find both lines stock.
- **The SDK ships no `de.*` class at all.** `grep -rl '^package de\.'` over `adapter-hqc runtime platform api
  compat` finds nothing. So the SDK can never shadow one of carplay-rgi's classes either.

### 2.2 The carplay-rgi guard: mibpkg refuses the install

`sdk_pkg::check` refuses **before** the owned-classes check:

```rust
if jars.iter().any(|n| n.to_ascii_lowercase().contains("carplay")) {
    bail!("carplay-rgi is installed (a *carplay* jar is in {JARS}); the SDK does not support it (spec §13); nothing changed");
}
match find_carplay_stock(&l.p(HMI_ROOT)) { … Ok(Some(_)) => bail!("carplay-rgi is installed (*.carplay-stock files exist); …") … }
```

(`mibpkg/src/mibsdk/sdk_pkg.rs:180-191`; `find_carplay_stock` is `:50-66` and walks all of `/mnt/app/eso/hmi`,
`mod.rs:42`.)

**On the car, both conditions are true:**
- `carplay_hook.jar` is in `lsd/jars`;
- `lsd/jars/lang_data.zip.carplay-stock` is under `HMI_ROOT` (`custom.sh:448-460`).

So `mibpkg install-file mibsdk` **fails with "nothing changed"**. This is deliberate: spec §13 (`docs/superpowers/specs/2026-10-18-mib2q-plugin-sdk-design.md:398-414`)
says the SDK is the sole owner of every shadowed stock class, that carplay-rgi migrates onto it, and that "until
the migration lands, the SDK installer refuses to install while carplay-rgi's jar is present".

## 3. Runtime conflicts (if both ran together)

None was found in the source. All of them are untested, because no unit has ever run both.

### 3.1 PartialPopupManagerEvoHigh vs the SDK popups 609600 (slot 7) and 609601 (slot 6)

- The slot logic is not in this class. `initSlots`/`sortedSlots` live in the stock base
  `de.esolutions.hmi.widgets.audi.base.AbstractPartialPopupManager` (`lsd_java/…/AbstractPartialPopupManager.java:83-180`),
  which carplay-rgi does not shadow.
- Its copy of `PartialPopupManagerEvoHigh` keeps every stock method. It adds
  `keepCarPlayStatusLineHidden()`/`hideFooter()` and overrides `doShowPopup`. Its `showPopup`/`doShowPopup` only
  act on ids **62** and **2100008**, and only when terminal 0's current screen is CarPlay (3200000) and
  `PdcSmallStageGuard.shouldKeepCarPlayScreen` holds. Every other id goes straight to `super`
  (`PartialPopupManagerEvoHigh.java:192-246`).
- The SDK registers 609600/609601 through the Car bundle's partial-popup stubs
  (`adapter-hqc/…/ui/HqcUiIds.java:176-180`), which this class never sees by id. **Slot handling is unchanged.**
- Overlap on screen is the only open point. It would be 609600 (x 514..769, y 0..24) over CarPlay with a side
  OPS. carplay-rgi removes only the footer.

### 3.2 StatusBarStubController vs popup 65

- carplay-rgi's copy differs from stock only in `setEntertainmentDrawerVisible`. It forces the drawer hidden
  while `keepCarPlayStatusLineHidden()` is true (`StatusBarStubController.java:112-121`; the rest is a
  CFR-vs-Vineflower rename).
- Popup 65 is not referenced.
- The SDK builds its own `StatusBarStubController` on SDK pages (`adapter-hqc/…/ui/PageWidgets.java:659-662`,
  renderStyle 2). With carplay-rgi installed, that would be carplay-rgi's class. On an SDK page the guard is
  false (the screen is not 3200000), so it behaves exactly like stock. The constructor and setters are
  unchanged, so the SDK's link check still holds.

### 3.3 carplay-rgi's System SMM vs the SDK's HMISystem takeover and MENU ring row

- These are different bundles. carplay-rgi replaces the **SMM** activator (`SMSystem=`). The SDK replaces the
  **HMI** activator (`HMISystem=` → `SdkSystemActivator`).
- The SDK's Plugins row on ring screen 46 has its own key handler and fires no SMM event
  (`docs/research/2026-10-24-main-menu-and-icons.md`, §1).
- `CarPlaySystemSMM.checkGuard` delegates every guard to `super` and only adds a side action on transition 833.
  So guard 509 (model 138) and state 94 behave as stock.

### 3.4 The Tone row vs the SDK's Car-drawer rows

- These are different bundles too: `HMITone=` (carplay-rgi's activator substitution) and `HMICar=` (the SDK's
  `SdkCarActivator`).
- carplay-rgi's `ToneModelBank` subclass and the SDK's `CarModelBank` ownership do not intersect.

### 3.5 Other contact points

- carplay-rgi's `VehicleDataModule` only *listens* to `DSICarKombi` (`com/luka/carplay/core/VehicleDataModule.java:28-101`).
  The SDK's brightness probe would *write* `setDCBrightness`, so they do not conflict.
- No carplay-rgi code calls `setPDCSoundReproduction` or uses a `serviceDelegateConfigurator`. The SDK hooks
  `DSICarParkingSystem` through its chaining configurator, so F7 does not touch carplay-rgi.
- carplay-rgi's PDC guard does change what reverse looks like while CarPlay is on screen: CarPlay stays with a
  side OPS instead of the full PDC screen. A car run *with* carplay-rgi installed would need the popup checks
  done twice.

## 4. Versions

**HMI build.**
- The SDK accepts only `HMI_AU_MIB2_HQC_NAR_G22_C10_180803` (`MpkStage.java:17`). mibpkg reads it from the first
  `HMI = …` line of `/ifs/version_info.txt` (`mibpkg/docs/unit-facts.md`, "MIB SDK packages").
- carplay-rgi's docs never name an HMI build. They name trains, and the verified US train is
  `MHI2Q_US_AUG22_P5087` / MU1316 (`origin/main README.md:10`, `custom.sh:128-133`).
- The SDK research ties that train to this build through the owner's unit backup:
  `MU1316-MHI2Q_US_AUG22_P5087` → `allversions.txt` `HMI: HMI_AU_MIB2_HQC_NAR_G22_C10_180803`
  (`docs/research/appendix-a-inventory.md:3-13`).
- **That evidence is from a backup, so confirm it on the car** (runbook S1). A different build means stop: mibpkg
  refuses anyway (`sdk_pkg.rs:171-178`).

**mibpkg on the car.**
- carplay-rgi expects none (§1).
- The last version recorded on the car is **0.6.0** (`mibpkg/docs/unit-facts.md`, "Sources and publishing",
  2026-10-14; `mibpkg/docs/ideas.md:115`). 0.7.0 was built (`mibpkg/out/mpk/mibpkg-0.7.0.mpk`), so the car may
  also be on 0.7.0.
- The SDK needs **0.9.x**, because of `hmisystem`/`icons` in the manifest (`mibpkg/README.md:106-108`).

**Upgrade path to 0.9.1.**
- 0.6 and 0.7 have no top-level `install-file`. Both do have `gem install-file <pkg> <id> -`
  (`git show cc0b7f0:src/cli.rs:36,320`; `ee3b998:src/cli.rs:39,381`), and P3-F1 used it on the bench
  (`docs/bench-e2e.md:560,584`).
- The update is `/tmp/mibpkg gem install-file mibpkg <uid> -`, then wait for `supervisor 0.9.1`.
- A direct jump from 0.6/0.7 to 0.9.1 has not been tried. The bench went 0.7 → 0.8.0 → 0.8.1 → 0.9.x. If 0.9.1 is
  refused, install a 0.8.1 build first and then 0.9.1.

## 5. Resolution

The options, from least to most risky:

1. **Uninstall carplay-rgi for the session (chosen; the owner agreed).** Use its own uninstaller, check that the
   unit is stock, install and test the SDK, uninstall the SDK, then reinstall CarPlay or leave it off.
   - The SDK and carplay-rgi never run together, the guard and spec §13 stay as they are, and every check runs on
     the configuration the SDK is designed for.
   - It costs one extra reboot each way, and there is no CarPlay during the session.
2. **A mibpkg override flag to install beside carplay-rgi.**
   - §2 and §3 suggest it would work, but no unit has ever run the pair. It also needs a mibpkg change and a spec
     §13 change.
   - It needs its own bench run with carplay-rgi installed before it goes in a car. Not for this session.
3. **Do only carmqtt, with no SDK.** This keeps carplay-rgi but leaves F7, the UI checks, Kombi and O1 open.

### 5.1 Does carplay-rgi's uninstall leave the unit stock?

There are two uninstallers.

**(B) The self-contained uninstall card** (`uninstall custom.sh:24-63`). This is the documented route: copy
`uninstall_MoreIncredibleBash/` to the card and run it from M.I.B. (`origin/main README.md:320-322`).
- It restores **every** `*.carplay-stock` under `/mnt/app` and `/mnt/system` (`:50`) and deletes a fixed
  `OWNED` list that includes both jars (`:24-42`).
- **Risk:** it finds the backups with `find … || : > "$LIST"` (`:50`). The bench's interactive SSH shell has no
  `find` (`docs/bench-e2e.md:20`, F2). If M.I.B.'s environment has none either, it restores nothing, removes the
  owned files anyway and still prints `DONE`.
  - After that, `lang_data.zip` and the configs would stay patched, and the leftover `.carplay-stock` files would
    keep mibpkg refusing the SDK.
  - The runbook's U4 check catches this.
- Its `OWNED` list is fixed, so a file that a later release added and the list does not name would stay.

**(A) The install card run with `uninstall`** (`custom.sh:698-735`). This is the fallback.
- It reads the payload from `carplay/manifest.txt`, which needs no `find` (`custom.sh:158-175`, `:557`).
- For each replaced file it moves `.carplay-stock` back over the file. It removes each owned file.
- It then moves the backups of `smartphone_integrator.json`, `dio_manager.json`, the SI binary, `libairplay.so`,
  `mcd`, `mcd.conf`, `lang_data.zip`, the NCM files and every pf profile back. Finally it deletes the
  renderers' GL caches under `/mnt/persist/var/app/luka_carplay_*`.
- M.I.B. passes no argument, and `ACTION` defaults to `install` (`custom.sh:34`). So this has to be started over
  SSH from the card: `sh /fs/sda0/mod/custom.sh uninstall`. That runs the release's own script; it is not a hand
  edit. Still, the owner prefers M.I.B. for carplay-rgi, so their OK is needed.
- **The card must be the release that is installed**, because the owned-file list comes from its manifest.

**What both give back.**
- **Bytes and mode as they were before the first install.** Each backup is a `cp -p` copy made once
  (`custom.sh:150`), and the restore is a rename.
- The **bundles file and `lsd.sh` were never touched**, so they stay byte-identical whatever happens.
- With `carplay_hook.jar` gone, **`lsd/jars` is back to its stock set**, and so is the boot classpath.
- **Not restored:**
  - `NavActiveIgnore.jar`, if M.I.B. had installed one. It is not stock, and losing it is fine for the SDK.
  - Any file that an older carplay-rgi release had already changed before its first backup. The 10-14 release
    left a broken NCM backup (`custom.sh:325`).

**How to check "stock" (runbook U4).**
- No `*carplay*` in `lsd/jars`.
- No `*.carplay-stock` anywhere under `/mnt/app/eso/hmi` (this is the same walk mibpkg does).
- `lsd/jars` and `lang_data.zip` byte-equal to the pre-carplay stock. The references are the firmware dump (lang_data.zip is 502034
  bytes) and the session's own pre-uninstall listing.
- The bundles file byte-equal to the session's first copy.
- `HMICar=`, `HMISystem=`, `HMITone=` and `SMSystem=` as stock.
- Reboot and see the stock Sound menu, with no UpMix row.

### 5.2 Getting CarPlay back after the session

- **Reinstall the current carplay-rgi.** Uninstall the SDK first (mibpkg restores the bundles file byte for
  byte). Then run carplay-rgi's install card (the same release, or a newer one for train P5087) and reboot.
  - carplay-rgi's installer does not look for mibsdk, and mibpkg is not affected by it.
  - Afterwards the SDK cannot be installed again until the repackage (§2.2).
- **Or wait for the repackage** and stay on the SDK with no CarPlay.

### 5.3 What repackaging carplay-rgi onto the SDK and mibpkg would need

Mapped against spec §13 (a plugin where the API covers it, otherwise a privileged module in the adapter):

| carplay-rgi seam | Needs on the SDK side |
|---|---|
| `TerminalModeBapCombi` bootstrap + `CarplayDSILifecycleController` | SDK lifecycle events for the CarPlay session (TMDevice active and inactive) and a CarPlay DSI handle (button and touch post). This would be an adapter feature, `carplay.session`. |
| `PartialPopupManagerEvoHigh` + `StatusBarStubController` (popup 62 and drawer under side OPS) | An adapter-owned "keep this screen, hide the footer" policy hook on the popup manager. The SDK would then become the one owner of that class. |
| `ParkingSystemControllerComponentEvo`, `ParkingPartialPopupHandler`, `ExternalEventsListener`, `HighPriorityResourceTracker` | PDC and RVC show/hide events plus a veto or "keep screen" decision: a `hooks.parking.ui` seam. The SDK's `HookPoint` set covers only the parking *sound* today. |
| `system.sm.Activator`, `phone.sm.Activator` (CarPlay SMM subclasses) | An SMM-factory seam that lets a privileged module wrap `checkGuard` for named transitions. Nothing like it exists today. |
| `tone.hmi.Activator` (UpMix row) | A "row in another app's menu" slot (Tone/Sound), like the Car-drawer slot. That means an `HMITone=` takeover managed like `HMICar=`, and a new owned prefix `de/audi/tghu/tone/hmi/`. |
| `DisplayManagerMIB2High`, `CombiMapController`, `ClusterService`, `AltScreenCombiBAPListener`, `AppConnectorTerminalMode` | Cluster display contexts and BAP gating. These stay a privileged module in the adapter (spec §13); they would not become a plugin API. |
| `TerminalModeSDSHandler`, `CarPlaySpeechRequestHandler`, `ServiceManagerPhone`, `AudioDrawerContextImpl` | Key/PTT interception and audio-drawer model hooks (`hooks.hmi.keys`, spec §7). |
| `FlushValuesDict` | One SDK-owned flush registry, instead of every mod shipping its own copy. |
| Native payload: hooks `.so`, renderers, patched `smartphone_integrator` / `libairplay.so` / `mcd` / `deva-ctrl-qc.so`, and the pf, dio and lang edits | A mibpkg package for the files, plus a mibpkg "replace a stock file with a byte backup and restore it on remove" feature for the patched firmware files and configs. mibpkg already manages the pf edits and its own backups. |

## 6. Required steps (summary)

1. Before the session, build mibpkg 0.9.1, mibsdk 0.2.0 (for O1) and 0.3.0, and the plugin packages (runbook P1).
   Write the Kombi probe or drop that check (runbook P2).
2. On the car, check the HMI build, update mibpkg to 0.9.1 through `gem install-file`, and back everything up.
3. Uninstall carplay-rgi with its uninstall card. If U4 shows leftovers, use the install card's `custom.sh uninstall`
   over SSH, with the owner's OK. Reboot, and check stock (§5.1).
4. Install the SDK and run the checks. Then either uninstall the SDK with a byte-identical restore, or keep it.
5. Reinstall carplay-rgi if the owner wants CarPlay back (§5.2).
