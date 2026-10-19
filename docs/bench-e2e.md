# Bench end-to-end (Task 17)

Bench MHI2Q at 10.20.0.23, HMI `HMI_AU_MIB2_HQC_NAR_G22_C10_180803`, DSITracer active (`dsitracer_activated`
present, so the installer chained `TraceConfigurator`). Run 2026-10-19 from branch `feat/foundation`
(base 117cb19; fixes a870e1b and b2f6486 deployed during the run). Log lines below are verbatim from
`mibsdk-dev log` (first field: SDK uptime in ms).

**Overall: the SDK's runtime, seam, isolation, hot reload, quarantine, safe mode and uninstall all work on the
real unit, but plugins only load when `/mnt/app` is writable, and on a normal boot it is not (see Blocker B1).
Steps 4, 5 and the bike-rack smoke were run with a bench-only manual `mount -uw /mnt/app` after each
`mibsdk-dev` command. Step 8 (reinstall) was not done: after the post-uninstall restart the unit stayed off
the network (see "Bench state at the end").**

## Blockers and findings

| # | Finding | Status |
|---|---------|--------|
| B1 | **`/mnt/app` is read-only while the HMI runs.** It boots read-only, and mibpkg (and `mibsdk-dev` itself, by design) put it back to read-only after each of their writes; during the run it went back to read-only on its own within minutes of a manual `mount -uw`. The runtime writes `boot.state`, `cache/<id>-<n>.jar`, `data/<id>.properties`, `ledger.properties` and deletes `plugins/<id>.reload` under `/mnt/app/eso/hmi/lsd/mibsdk/`, so on every normal boot: every plugin is `FAILED cannot load jar: java.io.FileNotFoundException: .../cache/<id>-1.jar`, reload markers are never consumed (`cannot delete hello.reload; not reloading`), and `cannot write boot.state` means **the boot guard's crash-loop / wrap-loop counting does not work at all**. Needs a design ruling: where should runtime-mutable state live, or should the runtime remount, and when? | **open, design decision** |
| F1 | `/tmp/mibsdk.log` was never written: `/tmp` is QNX `/dev/shmem`, where J9's `File.renameTo` always returns false (I checked this from a plugin: `probe /tmp write ok exists=true`, `probe /tmp rename=false target=false`, `direct ok`). | fixed b2f6486 (in-place fallback, host test) |
| F2 | `mibsdk-dev install` refused with `could not verify the absence of carplay-rgi (find failed)`: the unit's interactive shell has no `find` (`sh: find: cannot execute`). | fixed a870e1b (`ls -R`, host test) |
| F3 | `mibsdk-dev restart` (`slay -s11 j9`) does not respawn j9 on this bench. j9 stays down for about 2-3 min, then the whole unit resets and comes back without a DHCP lease (`ssh rpi-bench keypanel up` kicks DHCP). Each restart took about 4-5 min in total. This is a bench/HMI behaviour, not an SDK one: it also happened after the uninstall. | recorded |
| F4 | Uninstall restores the bundles file byte-identical but not its mode: `-rwxrwxrwx` before install, `-rw-r--r--` after uninstall (`push_file` writes a new file). `install` also refreshes the mtime of the existing `dsitracer_activated` marker (`touch`). lsd.sh only tests and reads these files, so this is cosmetic. | minor, open |
| F5 | The configurator log prints `hookServices=java.util.Collections$UnmodifiableRandomAccessList@76e49317` (the list object, not its contents) on J9. | cosmetic, open |
| F6 | `boot healthy: plugins enabled for 60000 ms` is logged even when every plugin `FAILED` at load. | cosmetic, open |
| F7 | The bench has no PDC ECU (Parking aid: "The selected system is currently unavailable"), so bike-rack never gets a parking value and the override, ledger and HookObserved paths cannot be exercised here. | bench limitation |

## Step 1: Baseline

- `curl -s 10.20.0.23:8099/status` gave `{"bridge_connected":true,"capture_fps":0.0}`.
- Home screen: `bench-e2e/01-home-baseline.jpg`.
- Before the install, `/mnt/app/eso/hmi/lsd/` held `DSITracer-clientData.bin`, `DSITracer.jar` (1180602),
  `bundles.properties` (10162), `bundles_dsitracer_disabled.properties` (10162),
  `bundles_dsitracer_enabled.properties` (10171, `-rwxrwxrwx`, Feb 26 2018), `bundles_headless.properties`,
  `dsitracer_activated` (0 bytes, `-rw-r--r--`), `hmi_startup.json.example`, `images/`, `jars/`, `jre/`,
  `kzbs/`, `lang_hmi_builtin.config`, `language_lut_hmi.txt`, `libjprof.so`, `lsd.sh`,
  `traceConfig.properties`, `version.txt`, and no `mibsdk/`.
- `jars/` held 17 stock files (AppDevelopment, DevelopmentStubEvo, RemoteHMI-Libs-Ext, diag, fw_util_* x7,
  lang_data.zip, locale.zip, xEventRecorder, xEventRecorderEvo, xTcpKeyPanel, xTcpLog). There was no carplay jar
  and no NavActiveIgnore.
- A local copy of the stock `bundles_dsitracer_enabled.properties` was saved for the uninstall comparison.
- `/tmp` links to `/dev/shmem`. `/mnt/app` is mounted read-only. j9 runs as uid 0 and its stderr goes to
  `/dev/ser1`.

## Step 2: Install and restart

`mibsdk-dev install` (after fix F2):

```
mibsdk-dev: stock files backed up to /mnt/app/eso/hmi/lsd/mibsdk/stock (dsitracer_activated existed: true)
mibsdk-dev: pushed mibsdk-runtime.jar
mibsdk-dev: pushed mibsdk-adapter-hqc.jar
mibsdk-dev: pushed mibsdk-rt-retrotranslator.jar
```

The generated `sdk.properties` contained `chain.configurator=de.audi.tghu.dsitrace.TraceConfigurator` and
`hook.services=org.dsi.ifc.carparkingsystem.DSICarParkingSystem`. After the restart, `pidin -p j9 fds`
showed all three SDK jars open from `lsd/jars/`, and threads `mibsdk-clock`, `-runtime`, `-ledger`,
`-watchdog`, `-hqc-events`, `-hooks`, `-timer` and `-dev` were running.

Log of a normal boot, with F1 fixed and `/mnt/app` read-only as booted:

```
0 ERROR mibsdk cannot write boot.state: java.io.FileNotFoundException: /mnt/app/eso/hmi/lsd/mibsdk/boot.state.tmp at java.io.FileOutputStream.<init>(Unknown Source)
0 INFO mibsdk.hqc configurator: hmi=HMI_AU_MIB2_HQC_NAR_G22_C10_180803 safeMode=false wrapLoop=false wrappingEnabled=true hookServices=java.util.Collections$UnmodifiableRandomAccessList@76e49317
0 INFO mibsdk.hqc configurator: chain=de.audi.tghu.dsitrace.TraceConfigurator
0 INFO mibsdk.hqc activator: hmi=HMI_AU_MIB2_HQC_NAR_G22_C10_180803 adapter=hqc-nar-g22-c10
0 STATUS mibsdk seam configurator=seen wrapping=true raw.parking=false
0 INFO mibsdk.hqc wrapped org.dsi.ifc.carparkingsystem.DSICarParkingSystem chain=TraceConfigurator
1057 INFO mibsdk.hqc org.dsi.ifc.carparkingsystem.DSICarParkingSystem available after 0 ms (1 lookups)
1057 INFO mibsdk.hqc org.dsi.ifc.powermanagement.DSIPowerManagement available after 0 ms (1 lookups)
1057 STATUS mibsdk runtime started adapter=hqc-nar-g22-c10 safeMode=false
1057 STATUS mibsdk seam configurator=seen wrapping=true raw.parking=true
1057 STATUS mibsdk heap free=47725872 total=62914560
1057 INFO mibsdk plugins load in 30000 ms
3077 WARN mibsdk dev channel: cannot delete hello.reload; not reloading
31878 STATUS hello DISCOVERED discovered
31878 STATUS hello FAILED cannot load jar: java.io.FileNotFoundException: /mnt/app/eso/hmi/lsd/mibsdk/cache/hello-1.jar at java.io.FileOutputStream.<init>(Unknown Source)
91887 INFO mibsdk boot healthy: plugins enabled for 60000 ms
```

- **Expected line present:** `STATUS mibsdk runtime started adapter=hqc-nar-g22-c10 safeMode=false`.
- **Seam:** `configurator=seen`, so the configurator and the activator share one class-loader copy of
  `mibsdk.adapter.hqc` (no class-loader split). `raw.parking` turns true once the parking service registers.
- **Wrapping:** `wrapped org.dsi.ifc.carparkingsystem.DSICarParkingSystem chain=TraceConfigurator`.
  `java.lang.reflect.Proxy` with the bootstrap loader works on J9. There was no `not wrapped`, Proxy or
  class-loading WARN, and no ClassCastException.
- **Heap baseline** (for `heap.low.bytes`), measured at runtime start in four boots:

  | Boot | Free | Total |
  |------|------|-------|
  | 1 | 50,763,476 | 62,914,560 |
  | 2 | 47,725,872 | 62,914,560 |
  | 3 (safe mode) | 49,936,332 | 62,914,560 |
  | 4 | 50,826,736 | 62,914,560 |

  So there is about 47.7-50.8 MB free of a 60 MB (`-Xmx60m`) heap.
- **Expected `hello enabled` / `STATUS hello ENABLED` after 30 s: FAILED, blocker B1.** With a manual
  `mount -uw /mnt/app`, the pending marker was consumed on the next dev poll:

```
189912 INFO mibsdk dev channel: reload requested for hello
189912 STATUS hello DISCOVERED discovered
189912 INFO hello hello enabled
189912 STATUS hello ENABLED enabled
```

`data/hello.properties` then held `enables=3`, so storage writes work on a writable mount.

## Step 3: Stock HMI and tracer chain

- Keypanel `/status` was `bridge_connected:true` with the SDK installed. DSITracer (TCP 1796) works through
  the chain.
- `car` tap opened "Vehicle: Audi drive select" (`bench-e2e/02-car-menu-sdk-installed.jpg`). A bench
  "low on fuel" popup sometimes overlays; `back`, or rotate plus select "No", dismisses it.
- `back` returned to the main menu (`bench-e2e/03-back-home-sdk-installed.jpg`).
- The `wrapped … chain=TraceConfigurator` line is present (Step 2).
- The screenshots show no personal data: menu screens only, no VIN.

## Step 4: Hot reload (manual rw mount)

Changed the hello log text to `hello enabled v2` (bench-only edit, not committed), rebuilt, ran
`mibsdk-dev push`, then `mount -uw /mnt/app`:

```
253921 INFO mibsdk dev channel: reload requested for hello
253921 STATUS hello DISABLED reloading
253921 STATUS hello DISCOVERED discovered
253921 INFO hello hello enabled v2
253921 STATUS hello ENABLED enabled
```

The reload landed within the 4 s check (dev poll 2 s). The j9 PID was unchanged (753716 before and after).

## Step 5: Forced failure (crashy, no grants, manual rw mount)

Each mode was set with `echo mode=<m> > data/crashy.properties`, then the marker was touched and I waited 40 s.
After each mode: the j9 PID was still 753716, a keypanel `car` tap changed the screen (different frame), and
`back` returned to the identical home frame. `bridge_connected` stayed true.

- **throw-on-timer: QUARANTINED**
  ```
  299928 STATUS crashy ENABLED enabled
  300928 ERROR crashy callback scheduled failed: java.lang.IllegalStateException: crashy: throw-on-timer at sample.crashy.CrashyPlugin.lambda$onEnable$3(Unknown Source)
  301928 ERROR crashy callback scheduled failed: …
  302928 ERROR crashy callback scheduled failed: …
  302928 STATUS crashy QUARANTINED 3 failures within 60000 ms, last: java.lang.IllegalStateException: crashy: throw-on-timer …
  ```
- **hang-on-timer: QUARANTINED**
  ```
  366940 INFO crashy hanging
  396944 ERROR crashy callback scheduled hung for over 30000 ms
  396944 STATUS crashy QUARANTINED hung in scheduled
  ```
- **busy-loop: QUARANTINED** (this is the NORM-1 starvation check)
  ```
  429949 INFO crashy spinning
  459954 ERROR crashy callback scheduled hung for over 30000 ms
  459954 STATUS crashy QUARANTINED hung in scheduled
  ```
  - At 12 s into the spin, `pidin` showed `mibsdk-plugin-crashy RUNNING 9r`. Every SDK thread runs at QNX
    priority 9 (`NORM_PRIORITY - 1`); j9's main thread is 12 and most HMI threads are 10.
  - A `car` tap during the spin changed the screen (`bench-e2e/04-car-tap-during-busy-loop.jpg`), and `back`
    returned home.
  - The clock and the watchdog were not starved: the quarantine landed at exactly 30 s.
  - After the quarantine the crashy thread was gone (the interrupt ended the spin).
- **throw-on-enable: FAILED**
  ```
  495958 INFO crashy mode=throw-on-enable
  495958 STATUS crashy FAILED java.lang.IllegalStateException: crashy: throw-on-enable at sample.crashy.CrashyPlugin.onEnable(Unknown Source)
  ```
- **hello unaffected:** it stayed `ENABLED` throughout, and logged on real ignition transitions (rpi-bench
  `systemctl stop kl15` / `start kl15`; the unit did not sleep):
  ```
  707989 INFO hello ignition=false
  720991 INFO hello ignition=true
  ```
  This also confirms that the IgnitionBridge delivers real KL15 off/on on the unit.

## Task 18 Step 5: bike-rack smoke (manual rw mount)

Ran `grant bike-rack vehicle.read,vehicle.parking.write`, then the push:

```
581970 STATUS bike-rack ENABLED enabled
```

Then set `active=true` and reloaded:

```
593972 STATUS bike-rack DISABLED reloading
593972 STATUS bike-rack DISCOVERED discovered
593972 INFO bike-rack waiting for parking value
593972 STATUS bike-rack ENABLED enabled
```

No parking value ever arrived, so there was no override, no `HookObserved` and no ledger entry (finding F7).
Opening Vehicle > Driver assistance > Parking aid on the HMI showed "The selected system is currently
unavailable". Then set `active=false` and reloaded (`683986 STATUS bike-rack DISABLED reloading`, then
`683986 STATUS bike-rack ENABLED enabled`).
`ledger.properties` was never created, so the ledger was empty before the uninstall.

## Step 6: Safe mode

Ran `safe-mode on && restart`:

```
0 INFO mibsdk.hqc configurator: hmi=HMI_AU_MIB2_HQC_NAR_G22_C10_180803 safeMode=true wrapLoop=false wrappingEnabled=false …
0 STATUS mibsdk seam configurator=seen wrapping=false raw.parking=false
1125 WARN mibsdk safeMode=true reason=marker (SAFE_MODE present; no plugin loads)
1125 STATUS mibsdk runtime started adapter=hqc-nar-g22-c10 safeMode=true
1125 STATUS mibsdk heap free=49936332 total=62914560
```

- No `wrapped` line and no plugin lines (three plugins were installed).
- Keypanel `bridge_connected:true`; `car` and `back` worked.

Then ran `safe-mode off && restart`:

- `SAFE_MODE` was removed and `boot.state` reset (`unhealthyBoots=0`, `lastShutdownClean=true`,
  `wrapBoots=0`).
- The next boot logged `configurator: … safeMode=false … wrappingEnabled=true`,
  `wrapped org.dsi.ifc.carparkingsystem.DSICarParkingSystem chain=TraceConfigurator` and
  `runtime started adapter=hqc-nar-g22-c10 safeMode=false`. Normal mode was restored.
- The three plugins then `FAILED cannot load jar` (blocker B1 again).

## Step 7: Uninstall

The ledger was empty, so `mibsdk-dev uninstall` ran without `--force`:

```
mibsdk-dev: restored /mnt/app/eso/hmi/lsd/bundles_dsitracer_enabled.properties
uninstalled; restart HMI with: mibsdk-dev restart
```

- `cmp` of the live `bundles_dsitracer_enabled.properties` against `mibsdk/stock/…` was identical.
- `cmp` against the local pre-install copy from Step 1 was also identical.
- `stock/tracer.was` = `true` and `dsitracer_activated` is still present, which matches the backup.
- The SDK jars are gone from `jars/`, and the `jars/` listing matches the baseline.
- The only differences are F4 (the bundles file mode, the marker mtime) and the `mibsdk/` directory, which is
  kept without `--purge`.
- **Restart after uninstall:** the unit did not come back on the network. After the j9 kill and the
  usual reset (F3), `keypanel up` reported `still unreachable - is it powered and booted?` on every attempt
  for over 25 min, including after a kl15 off/on cycle. The CAN bus stayed alive (rpi-bench can0 rx about
  190 frames/s), so the bench is powered. I could not tell whether the MMX was booted but offline or stuck,
  because the RCC serial console was not available to me. The installed state on flash is the stock state
  (byte-identical bundles file, no SDK jars on the boot classpath), so the SDK cannot be what is running.

## Step 8: Reinstall

**Not done.** The unit was unreachable after the Step 7 restart. When it is back:

```
installer/mibsdk-dev install && installer/mibsdk-dev restart
```

The `mibsdk/` directory still holds `plugins/hello.jar` (v2 build), `crashy.jar` and `bike-rack.jar`,
`grants.properties`, and `data/bike-rack.properties` (`active=false`).

- Remove crashy before relying on the bench: `mount -uw /mnt/app; rm plugins/crashy.jar data/crashy.properties`.
- Re-push hello from `main` (`mibsdk-dev push plugins/hello/build/mibsdk/hello.jar`).
- Plugins will only load after blocker B1 is resolved.
