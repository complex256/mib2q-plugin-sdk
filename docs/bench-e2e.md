# Bench end-to-end (Task 17)

Bench MHI2Q at 10.20.0.23, HMI `HMI_AU_MIB2_HQC_NAR_G22_C10_180803`, DSITracer active (`dsitracer_activated`
present, so the installer chained `TraceConfigurator`). Run 2026-10-19 from branch `feat/foundation`
(base 117cb19; fixes a870e1b and b2f6486 deployed during the run). Log lines below are verbatim from
`mibsdk-dev log` (first field: SDK uptime in ms).

**Overall: the SDK's runtime, seam, isolation, hot reload, quarantine, safe mode and uninstall all work on the
real unit. In the Task 17 run, plugins only loaded when `/mnt/app` was writable (Blocker B1), so steps 4, 5 and
the bike-rack smoke used a bench-only manual `mount -uw /mnt/app`. B1 is fixed, and the "B1 verification" run
(2026-10-19) confirmed on the unit that plugins load, reload and keep their data with `/mnt/app` read-only as
booted. That run also did the reinstall (Step 8).**

## Blockers and findings

| # | Finding | Status |
|---|---------|--------|
| B1 | **`/mnt/app` is read-only while the HMI runs.** It boots read-only, and mibpkg (and `mibsdk-dev` itself, by design) put it back to read-only after each of their writes; during the run it went back to read-only on its own within minutes of a manual `mount -uw`. The runtime writes `boot.state`, `cache/<id>-<n>.jar`, `data/<id>.properties`, `ledger.properties` and deletes `plugins/<id>.reload` under `/mnt/app/eso/hmi/lsd/mibsdk/`, so on every normal boot: every plugin is `FAILED cannot load jar: java.io.FileNotFoundException: .../cache/<id>-1.jar`, reload markers are never consumed (`cannot delete hello.reload; not reloading`), and `cannot write boot.state` means **the boot guard's crash-loop / wrap-loop counting does not work at all**. **Ruling (2026-10-19): move runtime-mutable state off `/mnt/app`.** `boot.state`, `ledger.properties` and `data/` now live in `/mnt/persist/mibsdk/` (qnx6, read-write from boot; `data/` capped at 256 KiB per plugin and 1 MiB in total, a `put` past the cap fails); `cache/` lives in RAM under `/ramdisk/mibsdk/`; reload markers stay in `plugins/` and are never deleted (the runtime tracks consumed markers in memory, `mibsdk-dev push` writes a fresh token each time). The roots come from adapter-hqc (`SdkFiles`). An unwritable root is reported as `STATUS mibsdk state persist=… UNWRITABLE … bootGuard=inert`. | **fixed; verified on the bench 2026-10-19** (see "B1 verification") |
| F1 | `/tmp/mibsdk.log` was never written: `/tmp` is QNX `/dev/shmem`, where J9's `File.renameTo` always returns false (I checked this from a plugin: `probe /tmp write ok exists=true`, `probe /tmp rename=false target=false`, `direct ok`). | fixed b2f6486 (in-place fallback, host test) |
| F2 | `mibsdk-dev install` refused with `could not verify the absence of carplay-rgi (find failed)`: the unit's interactive shell has no `find` (`sh: find: cannot execute`). | fixed a870e1b (`ls -R`, host test) |
| F3 | `mibsdk-dev restart` (`slay -s11 j9`) does not respawn j9 on this bench. j9 stays down for about 2-3 min, then the whole unit resets and comes back without a DHCP lease (`ssh rpi-bench keypanel up` kicks DHCP). Each restart took about 4-5 min in total. This is a bench/HMI behaviour, not an SDK one: it also happened after the uninstall. | recorded |
| F4 | Uninstall restores the bundles file byte-identical but not its mode: `-rwxrwxrwx` before install, `-rw-r--r--` after uninstall (`push_file` writes a new file). `install` also refreshes the mtime of the existing `dsitracer_activated` marker (`touch`). lsd.sh only tests and reads these files, so this is cosmetic. | fixed: install records the mode in `stock/bundles.mode`, install and uninstall `chmod` to it; an existing marker is not touched (host test) |
| F5 | The configurator log prints `hookServices=java.util.Collections$UnmodifiableRandomAccessList@76e49317` (the list object, not its contents) on J9. | fixed: the names are joined explicitly (host test) |
| F6 | `boot healthy: plugins enabled for 60000 ms` is logged even when every plugin `FAILED` at load. | fixed: `boot healthy: <n> plugin(s) enabled …` only with at least one ENABLED and a writable boot guard; otherwise `no plugin is enabled (FAILED=<n>)` or `boot guard inert` (host tests) |
| F8 | `mibsdk-dev push` replaced `plugins/hello.jar` but not `hello.reload` and still printed `pushed hello; reload requested`. mibpkg's supervisor (`/tmp/mibpkg`) put `/mnt/app` back to read-only between the two files, and QNX `mv` exits 0 when it cannot replace its target (`mv: Can't unlink file …: Read-only file system`, status 0). This also explains why `/mnt/app` "went back to read-only on its own" during Task 17. | fixed 3b28e03: inside a write section every remote command re-asserts `mount -uw`; `push_file` judges the move by its result (temp file gone, target read back) and retries once; the `data/` migration checks each moved file (host tests) |
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

**Done in the B1 verification run below** (the unit came back; the SDK is installed again). The rest of this
section is the plan as written before that run. The unit was unreachable after the Step 7 restart. When it is back:

```
installer/mibsdk-dev install && installer/mibsdk-dev restart
```

The `mibsdk/` directory still holds `plugins/hello.jar` (v2 build), `crashy.jar` and `bike-rack.jar`,
`grants.properties`, and `data/bike-rack.properties` (`active=false`). The reinstall moves `data/` to
`/mnt/persist/mibsdk/data/` and drops `cache/` and `boot.state` from `/mnt/app`.

- Remove crashy before relying on the bench: `mount -uw /mnt/app; rm .../mibsdk/plugins/crashy.jar;
  mount -ur /mnt/app; rm /mnt/persist/mibsdk/data/crashy.properties`.
- Re-push hello from `main` (`mibsdk-dev push plugins/hello/build/mibsdk/hello.jar`).

## B1 verification

Run 2026-10-19 on `feat/foundation` at a2f4f83 (installer fix 3b28e03 deployed during the run), dist jars from
`./gradlew check :dist:assembleUnit`. **Every step passed with `/mnt/app` left read-only as booted.** The only
manual `mount -uw /mnt/app` was one setup command for the migration check (below), followed at once by
`mount -ur`. Two HMI restarts were needed in total. The first came back on the network by itself in about
150 s; the second needed one `keypanel up` (F3).

**Old-layout migration (installer on pdksh).** The unit still had the Task 17 state under
`/mnt/app/eso/hmi/lsd/mibsdk/`: `data/{bike-rack,crashy,hello}.properties`, `cache/`, `boot.state`, and a stock
backup with no `bundles.mode`. Setup: `mount -uw /mnt/app && : > …/mibsdk/ledger.properties; sync; mount -ur
/mnt/app`. That is an empty ledger, the way `OverrideLedger` writes one with no overrides. Then
`mibsdk-dev install`:

```
mibsdk-dev: stock backup already present; keeping it
mibsdk-dev: pushed mibsdk-runtime.jar
mibsdk-dev: pushed mibsdk-adapter-hqc.jar
mibsdk-dev: pushed mibsdk-rt-retrotranslator.jar
mibsdk-dev: no recorded mode for /mnt/app/eso/hmi/lsd/bundles_dsitracer_enabled.properties (backup from an older install); using the stock mode 777
installed; restart HMI with: mibsdk-dev restart
```

- `/mnt/persist/mibsdk/` held `data/` with the three files (content and modes kept) and the 0-byte
  `ledger.properties`.
- `/mnt/app/…/mibsdk/` was left with only `grants.properties`, `plugins/`, `sdk.properties` and `stock/`.
  `data/`, `cache/`, `boot.state` and the old ledger were gone.
- So the `${f##*/}`, `case` and `for` constructs work on the unit's shell.
- `/mnt/app` was read-only again afterwards (`touch` gave `Read-only file system`).

1. **Install and restart: pass.** After the boot, `/mnt/app` was still read-only (`touch /mnt/app/x`:
   `Read-only file system`), and j9 was running.
2. **State roots: pass.** No `cannot write boot.state` and no ERROR line at all. The log:
   ```
   0 INFO mibsdk.hqc configurator: hmi=HMI_AU_MIB2_HQC_NAR_G22_C10_180803 safeMode=false wrapLoop=false wrappingEnabled=true hookServices=[org.dsi.ifc.carparkingsystem.DSICarParkingSystem]
   0 INFO mibsdk.hqc configurator: chain=de.audi.tghu.dsitrace.TraceConfigurator
   0 INFO mibsdk.hqc activator: hmi=HMI_AU_MIB2_HQC_NAR_G22_C10_180803 adapter=hqc-nar-g22-c10
   0 STATUS mibsdk seam configurator=seen wrapping=true raw.parking=false
   1050 INFO mibsdk.hqc wrapped org.dsi.ifc.carparkingsystem.DSICarParkingSystem chain=TraceConfigurator
   1050 STATUS mibsdk state persist=/mnt/persist/mibsdk ok ram=/ramdisk/mibsdk ok bootGuard=armed
   1050 STATUS mibsdk runtime started adapter=hqc-nar-g22-c10 safeMode=false
   1050 STATUS mibsdk heap free=47712704 total=62914560
   1050 STATUS mibsdk seam configurator=seen wrapping=true raw.parking=true
   ```
   - `hookServices` lists the names (F5 fixed).
   - The seam line says `wrapping=true` with no `reason=`.
   - **`/mnt/persist` is writable when the configurator runs at boot.** Since f0f0917 the configurator
     turns wrapping off (`reason=boot-state-unwritable`, plus `WARN … wrap skipped`) whenever it cannot write
     the wrap-boot count to `/mnt/persist/mibsdk/boot.state` on the LSD thread. Both boots logged
     `wrapping=true`, no `reason=`, no `wrap skipped` and no `cannot write`, so that write succeeded.
   - The `wrapBoots=1` value itself was not seen. The unit is off the network for its first 2-3 min, and the
     runtime resets the count to 0 at 60 s.
3. **Plugins load: pass.**
   - Log: `31722 STATUS hello ENABLED enabled` (also bike-rack ENABLED, and crashy
     `FAILED … throw-on-enable`, its stored mode). There was no `FAILED cannot load jar`.
   - `/ramdisk/mibsdk/cache` held `bike-rack-1.jar` and `hello-3.jar`. The counter is shared by all
     plugins; crashy's snapshot is removed after its failure.
4. **Boot guard: pass.**
   - `91734 INFO mibsdk boot healthy: 2 plugin(s) enabled for 60000 ms`, and on the second boot
     `90426 INFO mibsdk boot healthy: 3 plugin(s) enabled for 60000 ms`.
   - `/mnt/persist/mibsdk/boot.state` = `unhealthyBoots=0 lastShutdownClean=false wrapBoots=0`, rewritten
     during the boot.
5. **Reload marker: pass, after fix F8.**
   - The first push only half-landed (F8). After 3b28e03:
     ```
     487789 INFO mibsdk dev channel: reload requested for hello
     487789 STATUS hello DISABLED reloading
     487789 STATUS hello DISCOVERED discovered
     487789 INFO hello hello enabled
     487789 STATUS hello ENABLED enabled
     ```
   - `plugins/hello.reload` was still present (fresh token), and 62 s later the log still had exactly one
     `reload requested for hello`.
   - A second push gave exactly one more (`581803 … reload requested for hello`), so the count was 2 and
     stayed at 2.
6. **Plugin data: pass.**
   - `/mnt/persist/mibsdk/data/hello.properties` went from `enables=5` to `enables=7` over the two reloads.
   - Nothing appeared under `/mnt/app/…/mibsdk/`.
7. **data-clear: pass.**
   - `mibsdk-dev data-clear crashy` printed `cleared the data of crashy (/mnt/persist/mibsdk/data/crashy.properties)`.
   - Only that file went: `ledger.properties`, `boot.state`, and bike-rack's and hello's data stayed.
   - After `mibsdk-dev restart`: `crashy mode=`, `crashy no crash mode set; idle`, `STATUS crashy ENABLED`,
     `boot healthy: 3 plugin(s) enabled`, and no `data/crashy.properties` was written back.

**Stock HMI (pass).** Keypanel `bridge_connected:true` with the SDK installed: DSITracer's injector works
through the chained `TraceConfigurator`.
- Home: `bench-e2e/05-home-b1-installed.jpg`.
- `media`: `bench-e2e/06-media-b1-installed.jpg` ("no playable files", since the bench has no media).
- `car`: `bench-e2e/07-car-menu-b1-installed.jpg`.
- `back` returned home. One `back` was dropped and the retry worked.
- The bench's "low on fuel" popup appeared after each boot; rotate plus select "No" dismissed it.

**F4 (pass, no uninstall cycle needed).**
- Install set the bundles file to `-rwxrwxrwx`. It was `-rw-r--r--` before, left by the Task 17 uninstall.
- This backup predates `stock/bundles.mode`, so the installer used the stock mode 777 and said so. Uninstall
  uses the same `restore_bundles_mode` (host-tested).
- `dsitracer_activated` kept its mtime: `Jan 01 00:07` before and after the install.

**Bench state at the end.**
- The SDK is installed: committed `hello`, `bike-rack` (`active=false`) and `crashy` (no mode: idle) are
  ENABLED.
- `/mnt/app` is read-only, and the HMI is on its home screen.
- Removing crashy needs a `/mnt/app` write. `mibsdk-dev` has no remove command, so that is left for later.

### `mibsdk-dev` commands (current)

`mibsdk-dev` is a thin SSH transport over the unit's mibpkg (0.8.0 or later; every command but `log` and `restart`
first checks `/tmp/mibpkg sdk status` and refuses an older or missing mibpkg). It uploads to `/tmp` only and never
remounts anything: mibpkg owns every `/mnt/app` write. mibpkg's exit code passes through (3: permissions to
approve, with an `approve with:` line; 2: bad usage; 1: failed or busy).

| Command | What it does on the unit |
|---------|--------------------------|
| `install` | upload the single `dist/build/dist/mibsdk-*.mpk` (or `$MIBSDK_MPK`; build it with `./gradlew :dist:mibpkgPackage`) to `/tmp/mibpkg.upload.<uid>.mpk`, then `/tmp/mibpkg install-file mibsdk <uid> -` |
| `uninstall [--purge] [--force]` | `/tmp/mibpkg remove mibsdk [--purge] [--force]`; keeps `/mnt/persist/mibsdk` unless `--purge` |
| `install-plugin <file.mpk> [--approve=<perm,...>]` | upload, then `/tmp/mibpkg install-file <id> <uid> - [--approve=…]` (the id is the `.mpk`'s `[package].name`) |
| `remove <id> [--purge\|--purge-data]` | `/tmp/mibpkg sdk remove <id> [--purge]` (plugin packages and pushed jars); keeps the plugin's data unless `--purge` |
| `push <plugin.jar>` | upload a snapshot of the jar and its `<jar>.stamp` (refused without the stamp) to `/tmp/mibpkg.push.<id>.jar{,.stamp}`, then `/tmp/mibpkg sdk push <id>` |
| `grant <id> <perm,...>` | `/tmp/mibpkg sdk grant <id> <perm,...>` (an empty list revokes all; a plugin missing a grant does not run) |
| `safe-mode on\|off` | `/tmp/mibpkg sdk safe-mode on\|off`; `off` also resets `boot.state` |
| `boot-state set-wrap <n>` | `/tmp/mibpkg sdk boot-state set-wrap <n>` |
| `data-clear <id>` | `/tmp/mibpkg sdk data-clear <id>`; restart afterwards, since the storage cap counts files on disk from the runtime's start and a running plugin may write its data back |
| `status` | `/tmp/mibpkg sdk status`: the `HMICar=` line, the SDK, its plugins, the ledger, then the runtime's STATUS lines |
| `log` | print `/tmp/mibsdk.log` (no mibpkg needed) |
| `restart` | `slay -s11 j9` (no mibpkg needed) |

## Plan 3: mibpkg distribution

Run 2026-10-24 on the bench (10.20.0.23, same HMI). mibpkg from `feat/mibsdk-packages` (206f6fc, then the fix
649ed8d deployed during the run); `mibsdk-0.1.0.mpk` and the plugin `.mpk` files built from a clean detached
checkout of 13694a1 (`./gradlew :dist:mibpkgPackage :plugins:{hello,bike-rack,crashy}:mibPackage
-Pmibsdk.mibpkg=<host mibpkg>`); `installer/mibsdk-dev` from the same checkout.

**Overall: pass. mibpkg 0.8.0 adopted the old `mibsdk-dev` install as the `mibsdk` package: the stock backup
moved into mibpkg byte-identical, `mibsdk/stock/` is gone, and after a restart the runtime came up with
`boot healthy: 3 plugin(s) enabled` and the SDK's Car-drawer row. One mibpkg bug (P3-F2) blocked the adoption
until it was fixed on the unit.**

| # | Finding | Status |
|---|---------|--------|
| P3-F1 | mibpkg 0.7.0 has no top-level `install-file` (`unknown command "install-file"`); the 0.7 → 0.8 update needs `/tmp/mibpkg gem install-file mibpkg <uid> -`. Every later step uses the 0.8 verb. | recorded |
| P3-F2 | **`mibsdk-dev install` refused with `cannot list /mnt/app/eso/hmi/lsd/jars/AppDevelopment.jar (not a zip?); nothing changed`.** The jar is a valid zip. On the unit `lseek` from the QNX 6.5 std port fails with `EINVAL` (an on-unit probe: `seek 185611: Err(Os { code: 22 … "Invalid argument" })`, even for small jars), so the owned-classes guard's seek-and-read zip reader could not list any stock jar. | fixed 649ed8d (reads the whole jar, no seek; the refusal now carries the reader's error; 674 host tests) |
| P3-F3 | A fixed mibpkg with the same version cannot replace the running one: `install-file` says `mibpkg 0.8.0 is already installed`, and a self-update keeps no previous version (`no earlier version of mibpkg is kept`), so a rollback round-trip is impossible. The fixed build went on as `0.8.0+bench.1` (package version only; the binary is 0.8.0 at 649ed8d and reports `supervisor 0.8.0`). | bench-only version tag |
| P3-F4 | mibpkg's web log tail (`GET /api/logs/<id>`) also seeks (`src/web/api.rs`), so it returns nothing on the unit for the same reason as P3-F2. Not exercised here. | open |
| P3-F5 | The old install predates the UI: its `sdk.properties` had no `ui.enabled=true` and the live list had the stock `HMICar=` line. The `mibsdk` 0.1.0 package is UI-enabled, so after the adoption the live list differs from the Step 1 copy in exactly the `HMICar=` line (`de.audi.tghu.car.hmi.Activator` → `mibsdk.adapter.hqc.ui.SdkCarActivator`). Expected for this package. | recorded |
| P3-F6 | Between the adoption and the restart, `mibpkg sdk status` said `running: no` while the old runtime was still running (known deferred minor: `running:` uses the package state, not the log). | recorded |
| P3-F7 | `mibsdk-dev restart` reset the whole unit again (F3); one `keypanel up` (DHCP kick) brought it back after ~10 min. | recorded |

### Step 1: Baseline (pass)

- `/tmp/mibpkg status`: `supervisor 0.7.0 pid 1654910 (running)`, `mibpkg.web: running`.
- `lsd/jars/` held the three SDK jars (`mibsdk-adapter-hqc.jar` 45105, `mibsdk-rt-retrotranslator.jar` 308523,
  `mibsdk-runtime.jar` 165104) and no spike jar. `lsd/mibsdk/` held `grants.properties`
  (`hello=vehicle.read`, `bike-rack=vehicle.read,vehicle.parking.write`), `sdk.properties`
  (`chain.configurator=de.audi.tghu.dsitrace.TraceConfigurator`,
  `hook.services=org.dsi.ifc.carparkingsystem.DSICarParkingSystem`), `plugins/{hello,bike-rack,crashy}.{jar,reload}`
  and `stock/` (`bundles_dsitracer_enabled.properties` 10171, `tracer.was` = `true`).
- `/mnt/persist/mibsdk/ledger.properties` empty; no `plugins.properties`.
- Live bundle list (10224) vs the stock copy: the configurator line, `MibSdk` in `Autostart`, the `MibSdk=` bundle
  line; `HMICar=de.audi.tghu.car.hmi.Activator` in both (P3-F5). Local copies of both were kept for Step 3.

### Step 2: mibpkg 0.7.0 → 0.8.0 (pass)

- Uploaded `mibpkg-0.8.0.mpk` (1131575 bytes, read back by size) to `/tmp/mibpkg.upload.<uid>.mpk`;
  `/tmp/mibpkg gem install-file mibpkg <uid> -` (P3-F1): `OK: updated mibpkg 0.7.0 -> 0.8.0; mibpkg restarts itself`.
- Within 10 s: `supervisor 0.8.0 pid 20553766 (running)`; the 0.7 pid 1654910 was gone (`pidin -p` no such process).
- `/tmp/mibpkg sdk status`: exit 0; `HMICar=de.audi.tghu.car.hmi.Activator` (P3-F5); `sdk: not installed`;
  `running: yes`; `plugin bike-rack pushed grants=vehicle.parking.write,vehicle.read`, `plugin crashy pushed grants=`,
  `plugin hello pushed grants=vehicle.read`; `ledger: 0 override(s)`; the runtime's STATUS lines.

### Step 3: adopt the SDK (pass after P3-F2)

- First `mibsdk-dev install`: refused, nothing changed (P3-F2). After the fix, `0.8.0+bench.1` (P3-F3) was installed
  the same way: `updated mibpkg 0.8.0 -> 0.8.0+bench.1; mibpkg restarts itself`; the new supervisor was up within
  15 s and the previous one gone.
- `mibsdk-dev install`, verbatim:
  ```
  checking mibsdk 0.1.0 (uploaded file)
  unpacking
  switching mibsdk to 0.1.0
  starting services
  installed mibsdk 0.1.0; reboot the unit (or restart the HMI) to start it
  no recorded mode for /mnt/app/eso/hmi/lsd/bundles_dsitracer_enabled.properties (backup from an older install); using the stock mode 777
  adopted the mibsdk-dev stock backup from /mnt/app/eso/hmi/lsd/mibsdk/stock
  ```
- `mibsdk/stock/` is gone; `mibsdk/` now holds `grants.properties` (unchanged), `permissions.json`, `plugins/`
  (unchanged) and `sdk.properties` (`ui.enabled=true` added). The three jars were replaced (adapter 93064, runtime
  227193).
- The mibpkg backup `state/backups/mnt%app%eso%hmi%lsd%bundles_dsitracer_enabled.properties` is byte-identical to
  the Step 1 stock copy. The live list (10231, `-rwxrwxrwx`) equals the Step 1 live copy except the `HMICar=` line
  (P3-F5).
- `mibsdk-dev restart` → unit reset, one `keypanel up` (P3-F7). Then `mibsdk-dev status`: `sdk: 0.1.0`,
  `api: 1..2`, `hmi: … (supported)`, `running: yes`, `reboot needed: no`, the three plugins pushed with the same
  grants, `ledger: 0 override(s)`, `STATUS mibsdk runtime started adapter=hqc-nar-g22-c10 safeMode=false`,
  `STATUS mibsdk ui car=sdk`, and `STATUS bike-rack|crashy|hello ENABLED enabled`. The log has
  `91461 INFO mibsdk boot healthy: 3 plugin(s) enabled for 60000 ms` (`mibsdk-dev status` does not print that
  line). `/mnt/persist/mibsdk/boot.state`: `unhealthyBoots=0`.
- Keypanel `/status`: `{"bridge_connected":true,…}`. Home: `bench-e2e/08-home-mibpkg-sdk-adopted.jpg` (after `back`
  dismissed the fuel popup). Car drawer (`car`, `menu_left`): `bench-e2e/09-car-drawer-mibpkg-sdk-adopted.jpg`;
  scrolled to the end it shows the SDK's `Plugins` row: `bench-e2e/10-car-drawer-plugins-row-mibpkg-sdk-adopted.jpg`.

**Bench state at the end.** mibpkg 0.8.0 (package `0.8.0+bench.1`) supervises; `mibsdk` 0.1.0 is an installed
package; `hello`, `bike-rack` and `crashy` are pushed plugins (not packages) and ENABLED; kl15 active; home screen.

## Plan 3, Task 16: approval, F8, web, remove, uninstall

Run 2026-10-24 on the bench (10.20.0.23, same HMI). mibpkg 0.8.1 from `feat/mibsdk-packages` 729bd83
(`scripts/build.sh`, `scripts/bundle.sh`); `mibsdk-0.1.0.mpk` and the plugin `.mpk` files built from `feat/ui`
(eca752e, so the adapter carries 760d92f/f3ee91c) with `./gradlew :dist:mibpkgPackage
:plugins:{hello,bike-rack,crashy,hello-menu,daemon-status}:mibPackage -Pmibsdk.mibpkg=<host mibpkg from 729bd83>`;
`installer/mibsdk-dev` from the same tree. The web UI was driven over an SSH forward to the unit's loopback
(`ssh -L 18080:127.0.0.1:80`): the web server answers only on the interfaces chosen in its settings, and the bench's
`mlan0` is not one of them (it closes the connection without a reply, by design).

**Overall: Steps 0–5 pass; Step 6 was cut short. The second HMI restart (after the reinstall) reset the unit,
and it did not come back after two `keypanel up` tries and a further ~10 min wait (P3-F12).**

| # | Finding | Status |
|---|---------|--------|
| P3-F4 | The web log tail is empty on the unit (seek). | **fixed 729bd83, verified**: the Logs › mibpkg view shows the log (`bench-e2e/11-web-log-view-mibpkg-0.8.1.png`) |
| P3-F8 | The brief's network label "Open network connections …" is stale: the catalog (`api/permissions.json`) says "Query local services on this unit (loopback only)", and the sheet shows that. | brief wording only |
| P3-F9 | `sdk remove` does not print a `loaded: …` line. The brief expected "loaded: DISABLED plugin jar not found" before the purge. The runtime did log `STATUS hello DISABLED plugin jar not found` before mibpkg printed `cleared the data of hello`, so the order is right; only the CLI echo is missing. | recorded (cosmetic) |
| P3-F10 | F7 still holds: there is no PDC ECU, so bike-rack's "Rack fitted" stays at `Rear tones: waiting for parking system` and never takes an override. The Step 4 override was a hand-written ledger line (see Step 4), so "overrides of bike-rack restored" could not be exercised. | bench limitation |
| P3-F11 | After `sdk remove`, the Plugins menu still listed the removed plugin (Crashy) until the menu was rebuilt. `plugins/<id>.reload` markers also stay after a remove and after the uninstall (by design since B1). | recorded |
| P3-F12 | **The HMI restart after the reinstall did not come back.** `slay -s11 j9` at 12:44 reset the unit as usual (F3). `keypanel up` at 12:56 and 12:59 both said `still unreachable - is it powered and booted?`. Passive pings until 13:10 got no answer, and `keypanel status` showed `mmx: UNREACHABLE`, `daemon: down`, kl15 active. Task 15's restart after the same kind of install came back after one `keypanel up`. At 13:12 the RCC console gave 0 bytes in 15 s even after a CR (it answered earlier the same day) while CAN rx ran at ~190 frames/s, so the unit was powered but neither the RCC nor the MMX answered. The SDK installed is code-identical to the one that booted in Task 15 (only docs changed since), and the RCC is a separate processor no plugin code runs on, so this points at the bench, not the adapter. Needs a power cycle (the HA PDU entity is unavailable). | **open: bench unreachable, BLOCKED** |

### Step 0: mibpkg 0.8.1 (pass)

- `mibpkg-0.8.1.mpk` (1132021 bytes, read back and compared) → `/tmp/mibpkg install-file mibpkg <uid> -`:
  `updated mibpkg 0.8.0+bench.1 -> 0.8.1; mibpkg restarts itself`. Within 15 s: `supervisor 0.8.1 pid 15622180
  (running)`, `installed mibpkg 0.8.1 [complex256-mibpkg] (previous 0.8.0+bench.1)`.
- `GET /api/logs` → `[{"id":"mibpkg",…},{"id":"mibpkg.web",…}]`; `GET /api/logs/mibpkg?bytes=600` returned the
  log's last lines (P3-F4 fixed). The web UI's Logs view shows them too.

### Step 1: install with approval, taking over a push (pass)

- `mibsdk-dev install-plugin plugins/hello/build/mibsdk/hello-1.0.0.mpk` → exit 3:
  `Read vehicle state: ignition, gear, parking sensors (vehicle.read)`, `approve with: --approve=vehicle.read`.
- With `--approve=vehicle.read` → exit 0: `installed hello 1.0.0`, `replaced the pushed hello.jar`,
  `loaded: ENABLED enabled`. `grants.properties` has `hello=vehicle.read`.

### Step 2: F8 regression (pass)

- `install-plugin bike-rack-1.0.0.mpk --approve=vehicle.read,vehicle.parking.write,ui.slot.carmenu,ui.slot.statusbar`
  while a second shell ran `/tmp/mibpkg gem-screens` five times. The five runs exited 0, 0, 0, 1, 0, where 1 means
  busy because the install held the action lock. The install printed `installed bike-rack 1.0.0`,
  `replaced the pushed bike-rack.jar`, `loaded: ENABLED enabled`.
- Afterwards `ls /tmp | grep mibpkg.rw` printed nothing, and `/mnt/app` is read-only (`touch /mnt/app/.rwprobe` →
  `Read-only file system`; QNX `mount` prints no flags). `bike-rack=ui.slot.carmenu,ui.slot.statusbar,vehicle.parking.write,vehicle.read`.

### Step 3: web approval and revoke (pass)

- Uploaded `crashy-1.0.0.mpk` on Install from file: no permissions section and no sheet; Install →
  `OK: installed crashy 1.0.0`, `replaced the pushed crashy.jar`, `loaded: ENABLED enabled`.
- Uploaded `daemon-status-1.0.0.mpk`. Install opened the sheet "This plugin asks to:" with "Query local services
  on this unit (loopback only) `network`" and "Show an item in the status bar `ui.slot.statusbar`" (P3-F8):
  `bench-e2e/12-web-approval-sheet-daemon-status.png`. Allow and install → `loaded: ENABLED enabled`
  (`13-web-daemon-status-installed.png`).
- On daemon-status's package page, unchecking `network` showed "This plugin stops running until every permission it
  asks for is granted." (`14-web-revoke-network-warning.png`). Save → `granted daemon-status: ui.slot.statusbar`,
  `loaded: BLOCKED_PERMISSIONS missing permissions: network` (`16-web-daemon-status-blocked.png`). `mibpkg sdk status`
  printed `plugin daemon-status 1.0.0 grants=ui.slot.statusbar blocked: network`. The HMI's Plugins › Daemon Status page
  printed `Blocked: missing permissions: network` (`15-plugins-menu-daemon-status-blocked.jpg`).
- Re-checked and saved → `granted daemon-status: network,ui.slot.statusbar`, `loaded: ENABLED enabled`
  (`17-web-daemon-status-regranted.png`).
- A test-only `bike-rack` 1.0.1 that adds `network` was built from a temporary `plugin.json` edit, which was
  reverted and not committed, and uploaded. The page said "upgrade from 1.0.0", and the sheet listed only `network`
  (`18-web-update-sheet-only-new-permission.png`). I pressed Cancel, so 1.0.0 stayed installed and the grants were
  unchanged.

### Step 4: removal (pass, with P3-F9/F10)

- `mibsdk-dev remove crashy`: `removed crashy: plugins/crashy.jar and its grants line; reload requested`,
  `crashy has no overrides in the ledger`. The data dir kept `bike-rack.properties` and `hello.properties`. The
  ledger is empty, and the crashy package is gone from `mibpkg list`.
- `hello-menu` was pushed (`mibsdk-dev push …/hello-menu.jar`, then `grant hello-menu ui.slot.carmenu` →
  ENABLED). Plugins › Hello Menu › Enabled switched it to `[off]` (`19-plugins-menu-hello-menu-off.jpg`;
  `plugins.properties`: `hello-menu.off=true`; STATUS `DISABLED off in Plugins menu`). Then
  `mibsdk-dev remove hello-menu`: `removed hello-menu: plugins/hello-menu.jar and its grants line and its
  hello-menu.off switch; reload requested`. `plugins.properties` is empty afterwards.
- `mibsdk-dev remove hello --purge-data` (data had `enables=25`) printed `removed hello: …`, `hello has no overrides
  in the ledger`, `cleared the data of hello (/mnt/persist/mibsdk/data/hello.properties)` and the restart hint. The
  runtime logged `STATUS hello DISABLED plugin jar not found` first (P3-F9). `data/` then held only
  `bike-rack.properties`.
- Override in force: "Rack fitted" only reached `waiting for parking system` (P3-F10), so I wrote one held entry by
  hand: `vehicle.parking.rearTones=bike-rack|UNTIL_IGNITION_OFF|on|off|A` in `/mnt/persist/mibsdk/ledger.properties`.
  `sdk status` then showed `ledger: 1 override(s)`. `mibsdk-dev uninstall` → exit 1,
  `mibpkg: remove these plugins first: bike-rack, daemon-status`, and mibsdk stayed installed. I emptied the ledger
  by hand because the runtime never held that entry. Then `mibsdk-dev remove bike-rack` → `bike-rack has no overrides
  in the ledger`, and `mibsdk-dev remove daemon-status` → the same. `grants.properties` was then empty.

### Step 5: uninstall and restore (pass)

- `mibsdk-dev uninstall`: `removed mibsdk; kept plugin data in /mnt/persist/mibsdk (remove it with: mibpkg remove
  mibsdk --purge); reboot the unit (or restart the HMI) to finish removing it`.
- `bundles_dsitracer_enabled.properties` is byte-equal (`cmp`) to the Task 15 Step 1 stock copy: 10171 bytes,
  `-rwxrwxrwx`, `HMICar=de.audi.tghu.car.hmi.Activator`. `dsitracer_activated` is still present. No `mibsdk-*`
  jar is in `lsd/jars`. `/mnt/persist/mibsdk` (`boot.state`, `data/`, `ledger.properties`, `plugins.properties`) and
  `lsd/mibsdk/grants.properties` are kept. No `mibpkg.rw` holder is left.
- `mibsdk-dev restart` (12:32) → unit reset; one `keypanel up` at 12:43 brought it back. `sdk status`:
  `HMICar=de.audi.tghu.car.hmi.Activator`, `sdk: not installed`, `running: no`. Keypanel `bridge_connected:true`.
  After `back` dismissed the fuel popup, the stock home screen (`20-home-sdk-uninstalled.jpg`) and the stock Car
  drawer with no Plugins row (`21-car-drawer-sdk-uninstalled.jpg`) were shown.

### Step 6: reinstall (cut short by P3-F12)

- `mibsdk-dev install` (feat/ui build; adapter 95805 bytes on the unit = `dist/build/dist`): `installed mibsdk
  0.1.0; reboot the unit (or restart the HMI) to start it`; live `HMICar=mibsdk.adapter.hqc.ui.SdkCarActivator`.
- `mibsdk-dev restart` at 12:44 → the unit did not come back (P3-F12). The plugin installs were not run.

**Bench state at the end of this run:** unreachable. Last known state: mibpkg 0.8.1, `mibsdk` 0.1.0 installed
from `feat/ui` with a restart pending, no plugin packages, `/mnt/persist/mibsdk/data/bike-rack.properties` kept,
kl15 active.
