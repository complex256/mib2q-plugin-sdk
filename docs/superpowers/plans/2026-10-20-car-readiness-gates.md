# MHI2Q Plugin SDK — Car-Readiness Gates and Host Backlog Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close every car-readiness gate in `docs/car-readiness.md` that can be closed without a car (I4, T18, M6, M7, T11, fsync), and clear the host-side follow-ups (`mibsdk-dev remove`, I2 wording, the start-delay counter gap, M1 late features, the all-FAILED boot count).

**Architecture:**
- **Ledger (I4, T11, T18).** A ledger entry has two persisted states, held (`A`) and restoring (`R`). An `R` entry leaves the file only when the **car reports** the prior value: a `ParkingSoundChanged` from the adapter's DSI listener, fed to `OverrideLedger.onObserved`. A write that merely returns does not count. Every A→R transition is written to disk on the ledger thread, and `shutdown()` writes a final time, so a restore that is pending at stop or at power loss is finished by the next boot. The ledger also tracks the ignition: an UNTIL_IGNITION_OFF override taken while the ignition is off ends at the next ignition on.
- **Durability (fsync).** One class, `DurableFiles`, owns the write–fsync–rename–commit recipe for `boot.state`, `ledger.properties` and `data/<id>.properties`. After the rename it tries a directory fsync, then always fsyncs the renamed file again; on qnx6 a file fsync commits a whole-filesystem snapshot, which includes the rename. The runtime logs what the unit supports as a STATUS line, and the bench records it.
- **Installer (M7, remove, I2).** The build writes a hashed **check stamp** next to every jar it has checked: `build/dist/mibsdk-unit.stamp` from `:dist:assembleUnit`, and `build/mibsdk/<id>.jar.stamp` from `mibPluginJar`. `mibsdk-dev install`/`push` refuse a jar whose SHA-256 is not in a matching stamp.

**Tech Stack:** unchanged from Plan 1: Gradle 8.13 with the `build-logic` included build, JDK 17 + Jabel, Corretto 8 (Retrolambda), Retrotranslator 1.2.9, Kotlin 2.0.21 + ProGuard, JUnit 5.10.2, Python 3 `unittest` for the installer, and bash for `mibsdk-dev`.

**Spec:** `docs/superpowers/specs/2026-10-18-mib2q-plugin-sdk-design.md`: §5.3 (on-unit paths, amended 2026-10-19), §6 and §6.1 (reversible vehicle writes). Gate definitions: `docs/car-readiness.md`. Bench facts: `docs/bench-e2e.md`. Prior plan: `docs/superpowers/plans/2026-10-18-mibsdk-foundation.md`.

## Global Constraints

Carried over verbatim from Plan 1 where still valid. "Paths on the unit" is replaced by the spec §5.3 table.

**Bytecode and linking**
- Every class that runs on the unit is **class-file version 48**. v49/v50 are rejected by J9 2.3 even with `-noverify`. The build fails on anything else.
- Every on-unit artifact passes **LinkCheck**: call sites, field refs, type refs, class headers (super/interfaces) and catch types, against the car JCL (`java/**`, `javax/**` extracted from `$mibsdk.stockLsdJar`) plus its declared car classpath.
- The **adapter never compiles against stock compile-time constants**: no `ATTR_*`, `RT_*` or `ATTRVALIDFLAG_*` field references in `adapter-hqc/src/main`. jxe2jar constants are byte-swapped and javac inlines them. Read them at runtime via `DsiConstants`.
- Runtime code is written to the Java 1.4 API (CDC Foundation 1.1). The JCL has no `StringBuilder`, `java.util.regex`, `java.util.concurrent` or `String.format`. `java.nio` is not used on the unit.

**Threads**
- **No plugin code ever runs on a stock thread.**
- The configurator and the DSI proxy run on stock threads. They must never throw (catch `Throwable` and pass through) and never block (no locks, no I/O, no allocation-heavy work beyond the proxy call itself).
- SDK-created threads are daemon threads with priority `Thread.NORM_PRIORITY - 1`.
- `OverrideLedger`'s public methods called while publishing (`onObserved`, `ignitionChanged`, `releaseScope`, `retryPendingRestores`) never block and never do file I/O. File writes happen on the `mibsdk-ledger` thread, in `acquire` (caller's thread), and in `shutdown()`.
- Every delay (debounce, retry, confirmation timeout, re-arm window) is measured on the injected `Clock`. Never use `System.currentTimeMillis()` deltas.

**Firmware and safety**
- Supported HMI, compared as an exact string: `HMI_AU_MIB2_HQC_NAR_G22_C10_180803`, read from the `HMI = …` line of `/ifs/version_info.txt`. No match means the SDK stays off and no plugin loads.
- `bundles.properties` semantics: **every key other than `de.audi.tghu.serviceDelegateConfigurator` and `Bundle.Startup.Autostart` is treated as a bundle definition**. Never add SDK settings there; they go in `sdk.properties`.
- Never log, print or read the j9 command line (`lsd.sh` embeds the VIN and IMSI). Don't use `pidin … arg`.

**Paths on the unit** (spec §5.3; the runtime never names them, they come from `SdkRoots`)

| Root | Path | Writable at runtime | Holds |
|------|------|---------------------|-------|
| install | `/mnt/app/eso/hmi/lsd/mibsdk/` | no (installer only, after `mount -uw`) | `sdk.properties`, `grants.properties`, `SAFE_MODE`, `plugins/<id>.jar`, `plugins/<id>.reload`, `stock/` |
| persist | `/mnt/persist/mibsdk/` | yes (qnx6, from boot) | `boot.state`, `ledger.properties`, `data/<id>.properties` |
| ram | `/ramdisk/mibsdk/` | yes (lost at power-off) | `cache/<id>-<n>.jar` |
| SDK jars | `/mnt/app/eso/hmi/lsd/jars/` | no | `mibsdk-runtime.jar`, `mibsdk-adapter-hqc.jar`, `mibsdk-rt-retrotranslator.jar` |
| log | `/tmp/mibsdk.log` | yes (`/dev/shmem`: no rename, no directories) | the ring log, rewritten in place |

- Bench SSH: `ssh -o HostKeyAlgorithms=ssh-rsa -o PubkeyAcceptedKeyTypes=ssh-rsa root@10.20.0.23`. The unit lacks `date`, `tr`, `md5sum`, `cksum` and `find`, so generate files on the Mac. To restart the HMI: `slay -s11 j9`. On this bench that resets the whole unit within 2–3 min (F3). If the unit is still unreachable after 5 min, run `ssh rpi-bench keypanel up`.
- Ignition on the bench: `ssh rpi-bench systemctl stop kl15` / `start kl15` sends KL15 off/on over CAN. **This is not a power cut**: the unit stays powered.

**Repo and environment**
- Git authorship: `user.name=complex256`, `user.email=me@complex256.dev`. Never put the owner's personal name, username or email in files or commits (rule in `~/complex-projects/CLAUDE.md`); use `~`/`$HOME` for home paths.
- Gradle properties, with defaults in `gradle.properties`: `mibsdk.jdk8Home` (machine-local, in `~/.gradle/gradle.properties`), `mibsdk.stockLsdJar`, `mibsdk.stockDsiTracerJar`. Stock jars are never committed.
- Test commands: `./gradlew :<module>:test --tests '<Class>'`, `./gradlew -p build-logic test`, `python3 -m unittest installer/test_mibsdk_dev.py`. Before every commit: `./gradlew check` and the installer unittest both pass.
- Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

**Ledger file format (this plan, v2)**
- `<persist>/ledger.properties`: one line per key, `<key>=<pluginId>|<scope>|<prior>|<active>|<state>`, where `<state>` is `A` (held) or `R` (restoring). Lines are sorted by key, with no comments and no wall-clock values.
- A 4-field line (written before this plan) loads as `A`. Writing always produces 5 fields.

## Review Focus

1. **The ECU does not report a write that leaves its value unchanged** (a restore to the value the car already has, e.g. the override write was dropped). The entry must not stay "restoring" forever. Expected: after `CONFIRM_TIMEOUT_MS` with no new report, if the car's last report equals the prior, the restore completes (WARN logged). **Test:** Task 4, `OverrideLedgerTest.lastReportEqualToPriorConfirmsAfterTimeout`.
2. **The car's report arrives before `apply()` returns.** FakePlatform reports synchronously; HQC reports on the stock callback thread through the event pump, which can overtake the ledger thread. The confirmation must not be lost. **Test:** Task 4, `OverrideLedgerTest.reportArrivingBeforeApplyReturnsConfirms`.
3. **A ledger file from the previous version** (4-field lines) on a unit upgraded in place. Every entry must load as held and be restored or re-claimed as before; none may be dropped as malformed. **Test:** Task 3, `OverrideLedgerTest.fourFieldLinesFromOlderRunsLoadAsHeld`.
4. **Plugin ids that are prefixes of each other** (`bike` and `bike-rack`) in `mibsdk-dev remove`. Only the exact id's jar, grants line, data and ledger entries are touched. **Test:** Task 11, `RemoveTest.test_leaves_a_plugin_with_a_longer_id_alone`.
5. **A stale stamp next to a rebuilt jar** (the jar was rebuilt by `carJar` or by hand after the checked build). Expected: refused on the hash, with a message naming the build command. **Test:** Task 10, `PushTest.test_refuses_a_stale_stamp` and `InstallTest.test_refuses_a_jar_that_does_not_match_its_stamp`.

---

## File Structure

```
runtime/src/main/java/mibsdk/runtime/
  DurableFiles.java        NEW  write → fsync → rename → commit recipe + STATUS probe (fsync gate)
  LiveFeatures.java        NEW  the adapter's feature set, swapped atomically, read live by plugin contexts (M1)
  OverrideLedger.java      MOD  A/R state on disk, persist on transitions, observed-restore confirmation, ignition tracking
  BootGuard.java           MOD  DurableFiles; markPluginsLoading(boolean hmiHealthy)
  PluginStorage.java       MOD  DurableFiles
  SdkRuntime.java          MOD  STATUS durability, awaitSettled, ignitionChanged, haltForTest, LiveFeatures, health handoff
  PluginContextImpl.java   MOD  LiveFeatures + ParkingSource (lazy Parking)
  HooksImpl.java           MOD  LiveFeatures
platform/src/main/java/mibsdk/platform/PlatformHost.java   MOD  featuresChanged()
adapter-fake/.../FakePlatform.java                         MOD  ECU model (drop/fail/report writes), setFeatures
adapter-hqc/.../HqcPlatform.java                           MOD  featuresChanged() when the parking service arrives
plugins/bike-rack/.../BikeRackPlugin.kt                    MOD  in-memory arm, remove() to disarm, 5 s re-arm window (M6)
build-logic/src/main/java/mibsdk/build/CheckStamp.java     NEW  stamp format + SHA-256 (M7)
build-logic/src/main/java/mibsdk/build/CheckStampTask.java NEW  stamp task for dist
build-logic/.../MibPluginPlugin.java                       MOD  mibPluginJar writes <id>.jar.stamp
dist/build.gradle.kts                                      MOD  unitStamp → build/dist/mibsdk-unit.stamp
installer/mibsdk-dev                                       MOD  stamp checks, I2 wording, ledger v2 text, remove <id>
installer/test_mibsdk_dev.py                               MOD  stamps in fixtures, new tests
docs/car-readiness.md, docs/bench-e2e.md, spec §6.1        MOD  status + bench record
```

---

### Task 1: DurableFiles: one fsync recipe for every persist-root file

**Files:**
- Create: `runtime/src/main/java/mibsdk/runtime/DurableFiles.java`
- Modify: `runtime/src/main/java/mibsdk/runtime/OverrideLedger.java` (`writeLocked`, `tmpFile`), `BootGuard.java` (static `write`, `tmpFile`), `PluginStorage.java` (`write`), `SdkRuntime.java` (`logRoots`)
- Test: `runtime/src/test/java/mibsdk/runtime/DurableFilesTest.java`, `SdkRuntimeBootTest.java`

**Interfaces:**
- Produces:
```java
final class DurableFiles {
    /** Test seam over the platform calls, in the order replace() makes them. */
    interface Ops {
        void syncFile(java.io.FileDescriptor fd) throws java.io.IOException;            // fd.sync()
        void syncPath(java.io.File f, boolean directory) throws java.io.IOException;     // RandomAccessFile(f, directory ? "r" : "rw").getFD().sync()
        boolean rename(java.io.File from, java.io.File to);                              // File.renameTo
        boolean delete(java.io.File f);
    }
    static final Ops SYSTEM;
    static java.io.File tmpFile(java.io.File target);                                   // target + ".tmp"
    /** Write tmp, syncFile, close, rename over target (delete + rename where rename cannot replace), then commit:
     *  syncPath(parent dir, true) (failure ignored), then syncPath(target, false). Throws if the content is not in place.
     *  Returns true iff the final syncPath(target) succeeded. */
    static boolean replace(java.io.File target, byte[] content) throws java.io.IOException;
    static boolean replace(java.io.File target, byte[] content, Ops ops) throws java.io.IOException;
    /** "fileSync=ok dirSync=ok fsyncMs=<n>" | "... dirSync=unsupported (<SdkRuntime.describe(t)>)" | "fileSync=FAILED (<…>)",
     *  using a scratch file "<dir>/.durable-probe" (deleted afterwards). Never throws. */
    static String probe(java.io.File dir, Clock clock);
}
```
- Every existing load path keeps recovering from `tmpFile(target)` when the target is missing. Use `DurableFiles.tmpFile` there.

- [ ] **Step 1: Write the failing tests** in `DurableFilesTest`, using a recording `Ops` that delegates to `SYSTEM` for the file work and logs each call:
  - `replaceSyncsTheTempFileRenamesThenCommitsDirectoryAndTarget`: the log is exactly `[syncFile, rename x.tmp->x, syncPath <dir> dir, syncPath x file]`. The target holds the content, the `.tmp` is gone, and the result is `true`.
  - `directorySyncFailureDoesNotFailTheWrite`: `syncPath(dir, true)` throws, `replace` returns `true`, and the content is in place.
  - `renameThatCannotReplaceDeletesThenRenames`: `rename` returns false on the first call → the log shows `delete x` and then a second `rename`.
  - `failedFileSyncLeavesTheOldTargetUntouched`: `syncFile` throws → `IOException`, the target keeps its old bytes, and no rename was attempted.
  - `probeReportsFileAndDirectorySync`: `probe(tmpDir, clock)` starts with `fileSync=ok dirSync=` and leaves no `.durable-probe`.
  - `SdkRuntimeBootTest.durabilityIsReportedAsAStatusLine`: the log has exactly one `STATUS mibsdk durability persist=<persist> fileSync=ok` line.
- [ ] **Step 2: Run them and confirm they fail.** `./gradlew :runtime:test --tests '*DurableFiles*' --tests '*SdkRuntimeBootTest.durability*'`. Expected: FAIL (compile).
- [ ] **Step 3: Implement `DurableFiles`** in the 1.4 style the runtime uses (no try-with-resources; close in `finally`). Then switch the three writers to it:
  - `OverrideLedger.writeLocked` builds the bytes as today, then calls `replace`; it logs and returns false on `IOException`.
  - `BootGuard.write(File, State, RingLog)` does the same.
  - `PluginStorage.write` stores the snapshot into a `ByteArrayOutputStream` first.
  - `SdkRuntime.logRoots` appends `STATUS mibsdk durability persist=<dir> <probe>` after the `state` line, only when the persist root is writable.
- [ ] **Step 4: Run the whole runtime suite.** `./gradlew :runtime:test :runtime:linkCheck`. Expected: PASS. LinkCheck proves `RandomAccessFile.getFD()` and `FileDescriptor.sync()` exist in the car JCL. If it reports either missing, stop and report: the fsync gate then needs a different plan.
- [ ] **Step 5: Commit.** `git commit -m "feat(runtime): one durable write recipe (fsync, rename, commit) for persist-root files"`

---

### Task 2: FakePlatform ECU model that can drop, fail and report writes

**Files:**
- Modify: `adapter-fake/src/main/java/mibsdk/adapter/fake/FakePlatform.java`
- Test: `adapter-fake/src/test/java/mibsdk/adapter/fake/FakePlatformTest.java`

**Interfaces:**
- Produces (all `public`, thread-safe):
```java
void ecuDropWrites(int n);       // the next n apply() calls return normally but the ECU keeps its value and reports nothing; Integer.MAX_VALUE = until reset with 0
void ecuFailWrites(int n);       // the next n apply() calls throw IllegalStateException("ECU rejected the write (fake)"); checked before drop
void ecuReportWrites(boolean on);// default true: an accepted write publishes ParkingSoundChanged(value) through the host before apply() returns
int droppedWrites();             // how many writes were dropped so far
```
- `appliedLog()` keeps its meaning: every `apply` call (accepted, dropped or failed), in order. `ecuSoundDirections()` is the ECU truth. `parking().current()` returns the ECU truth.

- [ ] **Step 1: Write the failing tests** in `FakePlatformTest`:
  - `acceptedWriteIsReportedBeforeApplyReturns`: after `apply(F1R0L1X1)` returns, `host.events` already holds `ParkingSoundChanged(F1R0L1X1)`.
  - `droppedWriteLeavesTheEcuAndReportsNothing`: with `ecuDropWrites(1)`, the first apply leaves the ECU at its old value and publishes nothing; `droppedWrites()==1`; the second apply is accepted and reported.
  - `failedWriteThrowsAndChangesNothing`: with `ecuFailWrites(1)`, apply throws `IllegalStateException`, and the ECU and the events are unchanged.
  - `silentEcuAcceptsWithoutReporting`: with `ecuReportWrites(false)`, the ECU changes and no event is published.
- [ ] **Step 2: Run them and confirm they fail.** `./gradlew :adapter-fake:test`. Expected: FAIL (compile).
- [ ] **Step 3: Implement.** Use counters guarded by one private lock. Publish outside the lock.
- [ ] **Step 4: Run every suite that uses the fake.** `./gradlew :adapter-fake:test :runtime:test :plugins:bike-rack:test`. Expected: PASS. If a test breaks because it counted `ParkingSoundChanged` events and now sees write reports, add `fake.ecuReportWrites(false)` to that test's setup with a one-line comment. Do not change its assertions.
- [ ] **Step 5: Commit.** `git commit -m "test(adapter-fake): ECU model that can drop, reject and report writes"`

---

### Task 3: Ledger restore state on disk (T11)

**Files:**
- Modify: `runtime/src/main/java/mibsdk/runtime/OverrideLedger.java`
- Test: `runtime/src/test/java/mibsdk/runtime/OverrideLedgerTest.java`

**Interfaces:**
- Consumes: `DurableFiles.replace` (Task 1); `FakePlatform.ecuFailWrites` (Task 2).
- Produces (format in Global Constraints):
  - `static final String STATE_HELD = "A"`, `STATE_RESTORING = "R"`.
  - `parse` accepts 4 or 5 fields (4 → `A`); `writeLocked` always writes 5.
  - A loaded `R` line has `restoring=true`, `holder=null`.
  - Every path that calls `markRestoring` (`releaseAllFor`, `releaseScope`, `restorePendingOnBoot`, `releaseHandle`) queues one `ledger:persist` task (a `write()`) on the ledger thread **before** its sync tasks.
  - `void shutdown()` stops the worker, then calls `write()` once on the caller's thread. On failure it logs, like every write.
  - The class comment's file paragraph is updated to describe the state field.

- [ ] **Step 1: Write the failing tests:**
  - `fileLinesCarryTheRestoreState`: after `acquire("k","p",PERSISTENT,"OLD","NEW",a)` the file is `k=p|PERSISTENT|OLD|NEW|A\n`. With an applier that always throws, `ov.release()` → the file becomes `k=p|PERSISTENT|OLD|NEW|R\n` (await, no Clock advance).
  - `fourFieldLinesFromOlderRunsLoadAsHeld` (Review Focus 3): the file `k=p|PERSISTENT|OLD|NEW\n` → `active()` has one entry, `restorePending()==false`. After `attach(a)` and no release, nothing is applied. The first rewrite (a `releaseAllFor("p")`) writes `…|R` and then restores.
  - `restorePendingAtShutdownIsFinishedByTheNextLedger`: a throwing applier, `release()`, `awaitSettled`/`awaitIdle`, `shutdown()` → the file ends in `|R`. A new `OverrideLedger(file, clock)` reports `restorePending()==true`. `attach(okApplier)` → applies `OLD` and the entry is gone (until Task 4, a successful apply completes it).
  - `pendingRestoreOfAnEnabledPluginIsFinishedAtTheNextStart` (runtime harness in this test class): a plugin with `vehicle.parking.write` holds a PERSISTENT override. `fake.ecuFailWrites(Integer.MAX_VALUE)`, `rt.disable(id, "test")`, `rt.stop()`. The ledger file ends in `|R`. Then `fake.ecuFailWrites(0)` and a new runtime on the same roots whose plugin is still installed and enabled but does not re-acquire → the ECU is back at the prior and `activeOverrides()` is empty. This is the T11 bug: today the entry loads as an unowned `A` entry of an enabled plugin and is never restored.
- [ ] **Step 2: Run them and confirm they fail.** `./gradlew :runtime:test --tests '*OverrideLedgerTest*'`. Expected: the four new tests FAIL.
- [ ] **Step 3: Implement** as in Interfaces. `restoreStaleOverrides` and `restoreOrphanedOverrides` in `SdkRuntime` already skip `restorePending()` entries. The loaded `R` entries are restored by `attach()`, which already queues every `restoring` entry.
- [ ] **Step 4: Run the tests.** `./gradlew :runtime:test`. Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "fix(ledger): a pending restore is written to the ledger file and finished by the next boot (T11)"`

---

### Task 4: A restore completes only when the car reports the prior (I4)

**Files:**
- Modify: `runtime/src/main/java/mibsdk/runtime/OverrideLedger.java`, `SdkRuntime.java` (`stop()`), `docs/superpowers/specs/2026-10-18-mib2q-plugin-sdk-design.md` §6.1
- Test: `runtime/src/test/java/mibsdk/runtime/OverrideLedgerTest.java`

**Design (states per entry, in memory unless noted):**

| State | On disk | Leaves it when |
|-------|---------|----------------|
| held | `A` | released, scope ended, plugin gone → restoring |
| restoring, waiting to write | `R` | the ledger thread runs `sync` → writes prior → confirming (or retry on throw) |
| restoring, confirming | `R` | (a) an observation **equal to the prior** that arrived after the entry became restoring → **done** (entry removed, file rewritten); (b) an observation of another value → failed attempt; (c) `CONFIRM_TIMEOUT_MS` with no observation since the write: if the car's last report (any age) equals the prior → done, WARN; else failed attempt |
| restoring, backing off | `R` | the retry timer fires (`retryDelay(attempts)`: 5 s doubling to 60 s), `retryPendingRestores()` (ignition on), or a changed observation → writes again |

- **Observation counter.** Each entry counts its observations (`observations`). `markRestoring` records `restoringSince = observations`. "Arrived after the entry became restoring" means `observations > restoringSince` at the time of the report. The counter is incremented under `lock` in `onObserved` before any decision, so a report that overtakes `apply()` (Review Focus 2) is counted.
- **What confirms.** Confirmation reads only what the car reported. `ParkingIo.current()` is **not** read: on HQC, `ParkingBridge.apply` sets `current` to the written value, so reading it back would prove nothing. [decided in this plan]
- **When the write returns.** A restore `apply` that returns normally sets `confirming=true`, bumps `confirmSeq` and schedules `confirmCheck(key, generation, confirmSeq)` after `CONFIRM_TIMEOUT_MS = 5000`. If (a) already happened, `sync` sees the entry gone and stops.
- **Failed attempts.** (b) and (c)-failure go through the existing `onSyncFailed` path (restores are never given up while running) with the message `restore of <key> to <prior> for <owner> not confirmed by the car (<reason>) (attempt n); retrying in d ms`. Here `<reason>` is `reported <value>` or `no report within 5000 ms`.
- **Logs.** Done-(a) logs `restored <key>=<prior> (confirmed by the car)`. Done-(c) logs WARN `restored <key>=<prior> (confirmed by the car's last report; none after the write)`. An observation of the prior while restoring, before any write, completes without a write, as today's "reported by the car" path does. Today's `alreadyBack` becomes `observations > restoringSince && prior.equals(lastObserved)`.
- **Applying an override does not change:** an `apply` of the active value still counts as done when it returns. Sticky re-apply stays as it is.
- **Stop.** `awaitIdle` becomes `boolean awaitSettled(long maxMs)`: it returns once the worker queue is idle **and** no entry is `confirming`, polled in 50 ms real-time slices. `SdkRuntime.LEDGER_DRAIN_MS = 7000` (`CONFIRM_TIMEOUT_MS` + 2 s). Entries still unconfirmed (or backing off) at the deadline stay `R` on disk through `shutdown()`'s final write (Task 3), and the next boot writes the prior again.

**Interfaces:**
- Consumes: Task 2 (`ecuDropWrites`, `ecuReportWrites`), Task 3 (`R` persistence, final write).
- Produces: `static final long CONFIRM_TIMEOUT_MS = 5000`; `boolean awaitSettled(long maxMs)` (replaces `awaitIdle`; update every caller and test).

- [ ] **Step 1: Make the existing raw-ledger tests model an ECU that reports.** `RecordingApplier` gets a field `OverrideLedger reportTo`. When it is set, a successful `apply(key, v)` calls `reportTo.onObserved(key, v)` before returning. Set it in every existing test that builds a raw ledger. Leave alone the tests whose subject is rejected writes (`identicalObservations…`, `distinctObservations…`): their applier throws, so nothing is reported.
- [ ] **Step 2: Write the failing tests:**
  - `restoreStaysPendingUntilTheCarReportsThePrior`: a silent applier (no `reportTo`). Release → one restore write; `restorePending()` stays true. `clock.advance(5000)` → the log has `not confirmed by the car (no report within 5000 ms)`, and a second write follows after `clock.advance(5000)`. `l.onObserved("k", "OLD")` → the ledger is empty and the file is empty.
  - `reportArrivingBeforeApplyReturnsConfirms` (Review Focus 2): the applier calls `l.onObserved(key, v)` inside `apply` and then sleeps 50 ms before returning → after release the ledger empties with exactly one write and logs `confirmed by the car`.
  - `lastReportEqualToPriorConfirmsAfterTimeout` (Review Focus 1): `l.onObserved("k","OLD")` before the acquire's apply lands, then a silent applier. The active apply returns; the car never reported `NEW`. Release → one write, no report; `clock.advance(5000)` → the ledger is empty, with the WARN `confirmed by the car's last report`.
  - `reportOfAnotherValueAfterRestoreWriteRetries`: a silent applier, release, `l.onObserved("k","NEW")` → a retry write without a Clock advance (the existing changed-value rule); `restorePending()` stays true.
  - `droppedRestoreWriteIsRetriedUntilObserved` (runtime + FakePlatform): a plugin holds UNTIL_IGNITION_OFF rear=false; `fake.ecuDropWrites(2)`; `fake.setIgnition(false)` → after the first write `restorePending()` is true and the ledger file line ends in `|R`; advance the clock 5000 + 5000 + 5000 + 10000 (two timeouts, two backoffs) → the third write is accepted, `fake.ecuSoundDirections()` equals the prior, and `activeOverrides()` is empty.
  - `stopKeepsAnUnconfirmedRestorePendingForTheNextBoot` (runtime): a PERSISTENT override, `fake.ecuDropWrites(Integer.MAX_VALUE)`, the plugin releases it in `onDisable` → `rt.stop()` returns within about 8 s of real time and the file line ends in `|R`.
- [ ] **Step 3: Run them and confirm they fail.** `./gradlew :runtime:test --tests '*OverrideLedgerTest*'`. Expected: the six new tests FAIL; the Step 1 tests still PASS.
- [ ] **Step 4: Implement** the table above in `sync`, `onObserved`, a new `confirmCheck`, `markRestoring` and `awaitSettled`. Update the class comment's "Failures" paragraph to match.
- [ ] **Step 5: Amend spec §6.1.** After "The ledger itself is persisted.", add: "A restore is complete only when the car reports the prior value (an observation after the restore began), never because the write returned. Until then the entry stays in the ledger file marked restoring, and the next boot finishes it."
- [ ] **Step 6: Run the tests.** `./gradlew :runtime:test :plugins:bike-rack:test`. Expected: PASS.
- [ ] **Step 7: Commit.** `git commit -m "feat(ledger): a restore completes only when the car reports the prior value (I4)"`

---

### Task 5: Ignition-aware ledger and power loss around KL15 (T18)

**Files:**
- Modify: `runtime/src/main/java/mibsdk/runtime/OverrideLedger.java`, `SdkRuntime.java` (`feedLedger`, new `haltForTest`)
- Test: `runtime/src/test/java/mibsdk/runtime/OverrideLedgerTest.java`

**Interfaces:**
- Consumes: Task 3 (persist on transition), Task 4 (confirmation), Task 2 (`ecuDropWrites`).
- Produces:
```java
public void ignitionChanged(boolean on);  // never blocks. off: ignitionOff=true, then releaseScope(UNTIL_IGNITION_OFF).
                                          // on: ignitionOff=false; every held UNTIL_IGNITION_OFF entry with acquiredWhileOff → restoring
                                          //     (persist + sync, reason "acquired while the ignition was off; ignition on"); then retryPendingRestores().
void halt();                              // package-private: stops the worker and timer with NO final write (simulated power loss)
void haltForTest();                       // SdkRuntime, package-private: marks stopped, halts the ledger, shuts down control/dev/dispatcher;
                                          // no plugin disable, no drain, no adapter stop, no file writes
```
- `Entry.acquiredWhileOff` (memory only) is set in `acquire` from `ignitionOff`. [decided in this plan] An UNTIL_IGNITION_OFF override taken while the ignition is off belongs to the drive that just ended, so the next ignition on restores it before plugins see the event; the plugin may re-acquire on `IgnitionChanged(true)`. A PERSISTENT override taken while off is unaffected. The ledger starts with `ignitionOff=false`: the state is unknown until the first event.
- `SdkRuntime.feedLedger`: `IgnitionChanged` → `ledger.ignitionChanged(on)`. It still runs before `bus.publish`, after `noteIgnition`.

- [ ] **Step 1: Write the failing tests:**
  - `untilIgnitionOffOverrideAcquiredWhileOffEndsAtIgnitionOn` (raw ledger, reporting applier): `ignitionChanged(false)`; acquire UNTIL_IGNITION_OFF → applied; `ignitionChanged(true)` → restore written and confirmed, ledger empty.
  - `persistentOverrideAcquiredWhileOffIsKept`: same with PERSISTENT → still held after `ignitionChanged(true)`.
  - `overrideAppliedAsIgnitionDropsIsRestoredAfterPowerLoss` (runtime): the TOGGLE plugin acquires rear=false (UNTIL_IGNITION_OFF) on ignition on.
    - `fake.ecuDropWrites(Integer.MAX_VALUE)` (the PDC ECU sleeps after KL15 off), then `fake.setIgnition(false)`.
    - Await the ledger file line ending in `|R` and the first dropped restore write.
    - `rt.haltForTest()`.
    - `fake.ecuDropWrites(0)`; start a new `SdkRuntime` on the same roots with the same `fake`.
    - Expected: the ECU equals the prior and `activeOverrides()` is empty. Neither `stop()` nor `awaitSettled` ran.
  - `acquireIsOnDiskBeforeTheEcuSeesIt` (runtime): the fake's `apply` hook (wrap `fake.parking()` in the test's `PlatformAdapter` delegate, as `BikeRackPluginTest` does) reads the ledger file at the moment of the active write and finds the `|A` line.
- [ ] **Step 2: Run them and confirm they fail.** `./gradlew :runtime:test --tests '*OverrideLedgerTest*'`. Expected: FAIL (compile).
- [ ] **Step 3: Implement** as in Interfaces.
- [ ] **Step 4: Run the tests.** `./gradlew :runtime:test`. Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "feat(ledger): track the ignition; overrides around KL15 off survive power loss (T18)"`

---

### Task 6: Boot counter: no gap when the start delay exceeds healthyAfterMs; the all-FAILED call

**Files:**
- Modify: `runtime/src/main/java/mibsdk/runtime/BootGuard.java`, `SdkRuntime.java` (`start`, `loadPlugins`, `logHealth`)
- Test: `runtime/src/test/java/mibsdk/runtime/BootGuardTest.java`, `SdkRuntimeBootTest.java`

**Interfaces:**
- Produces: `synchronized void markPluginsLoading(boolean hmiHealthy)`. This replaces the no-arg form. It sets `lastShutdownClean=false` (as today) and, when `hmiHealthy`, clears `wrapBoots` (unless latched), **in one write**.
- `SdkRuntime.start()`: `boolean healthAtLoad = !safeMode && adapterStarted && cfg.pluginStartDelayMs() >= cfg.healthyAfterMs()`. If true, do **not** call `scheduleHmiHealthy`, and `loadPlugins` passes `true`. Otherwise the timer stays as today and `loadPlugins` passes `false`.
  - Why: today `markHmiHealthy` clears `wrapBoots` at `healthyAfterMs` while `lastShutdownClean` stays true until the load. A crash between the two counts nowhere, and the services are wrapped during it.
- All-FAILED boots [decided in this plan]: **not counted as unhealthy**. The count measures HMI restarts that arrive before the HMI ran healthy. A FAILED plugin runs no code: its registrations are released, so a later HMI crash is not attributable to it. Counting such boots would put a working unit into safe mode for crashes the SDK did not cause. `logHealth`'s no-plugin line becomes: `boot ran <ms> ms without a restart, but no plugin is enabled (<states>); unhealthy-boot count reset: only HMI restarts count, plugin failures are contained`.

- [ ] **Step 1: Write the failing tests:**
  - `BootGuardTest.pluginsLoadingClearsTheWrapCountInTheSameWriteWhenAsked`: state `wrapBoots=1`; `markPluginsLoading(true)` → the file reads `unhealthyBoots=0\nlastShutdownClean=false\nwrapBoots=0\n`. With `false`, `wrapBoots=1` is kept.
  - `SdkRuntimeBootTest.wrapCountIsKeptUntilPluginsStartLoadingWhenTheDelayExceedsHealthyAfter`: `BootGuard.countWrapBoot(bootState, null)` first. Start with `plugin.start.delay.ms=120000\nhealthy.after.ms=60000`. `clock.advance(90000)` → `wrapBoots=1` and `lastShutdownClean=true`. `clock.advance(30000)` → `wrapBoots=0` and `lastShutdownClean=false`.
  - In the existing `unwritableRamRootIsReportedAndAllFailedIsNeverHealthy`, add the assertion `awaitLog("unhealthy-boot count reset: only HMI restarts count")`.
- [ ] **Step 2: Run them and confirm they fail.** `./gradlew :runtime:test --tests '*BootGuardTest*' --tests '*SdkRuntimeBootTest*'`. Expected: FAIL.
- [ ] **Step 3: Implement.** Update the class comments of `BootGuard` (the wrapBoots paragraph) and `SdkRuntime` (the Boot paragraph) to describe the hand-off.
- [ ] **Step 4: Run the tests.** `./gradlew :runtime:test`. Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "fix(boot-guard): hand the wrap count to the plugin count in one write; keep all-FAILED boots uncounted"`

---

### Task 7: Features learned after the load reach running plugins (M1)

**Files:**
- Create: `runtime/src/main/java/mibsdk/runtime/LiveFeatures.java`
- Modify: `platform/src/main/java/mibsdk/platform/PlatformHost.java`, `runtime/.../SdkRuntime.java`, `PluginContextImpl.java`, `HooksImpl.java`, `adapter-hqc/.../HqcPlatform.java`, `adapter-fake/.../FakePlatform.java`, the two test `RecordingHost`s (`adapter-hqc/src/test/.../Fakes.java`, `adapter-fake/src/test/.../FakePlatformTest.java`)
- Test: `runtime/src/test/java/mibsdk/runtime/SdkRuntimeBootTest.java`, `adapter-hqc/src/test/java/mibsdk/adapter/hqc/HqcPlatformTest.java`, `FakePlatformTest.java`

**Interfaces:**
```java
// platform
public interface PlatformHost { /* existing */ void featuresChanged(); }   // never blocks; any adapter thread
// runtime
final class LiveFeatures {
    LiveFeatures(java.util.Set<String> initial);
    boolean has(String feature);
    java.util.Set<String> snapshot();                 // unmodifiable
    boolean set(java.util.Set<String> next);          // true if it changed
}
// PluginContextImpl constructor: Set<String> features → LiveFeatures features; ParkingIo parkingIo → ParkingSource parking
interface ParkingSource { mibsdk.platform.ParkingIo parkingIo(); }   // nested in PluginContextImpl; SdkRuntime::parkingIo
// HooksImpl constructor: Set<String> features → LiveFeatures features
// FakePlatform
public void setFeatures(java.util.Set<String> features);              // then host.featuresChanged() if started
```
- `ctx.has(f)` and hook registration read `LiveFeatures` at call time. `vehicle().parking()` builds its `ParkingImpl` on the first call that finds `has(PARKING)` and a non-null `parkingIo()`, caches it, and throws `UnsupportedOperationException` (same message) otherwise.
- `SdkRuntime.platformHost.featuresChanged()` submits a `features` task to the control thread: `refreshFeatures()`. If the set changed, it logs `STATUS mibsdk features=<sorted list>`.
- `HqcPlatform`: the parking `onAvailable` callback calls `host.featuresChanged()` after `pb.attach(service)` (the configurator has recorded the raw service by then, so `features()` already includes `HOOK_PARKING` when wrapping is on).
- No API event is added. A plugin that checked `has()` once in `onEnable` still sees the old answer, which is a false negative only; an API event is Plan 2's call. [decided in this plan]

- [ ] **Step 1: Write the failing tests:**
  - `SdkRuntimeBootTest.featuresLearnedAfterTheLoadReachEnabledPlugins`: a probe plugin logs `hook=<ctx.has(HOOK_PARKING)>` on every `IgnitionChanged`. Start with the fake's features lacking `HOOK_PARKING`; `fake.setIgnition(true)` → `hook=false`. `fake.setFeatures(+HOOK_PARKING)` → await `STATUS mibsdk features=[hook.parking, vehicle.ignition, vehicle.parking]`. `fake.setIgnition(true)` → `hook=true`.
  - `HqcPlatformTest.lateParkingServiceAnnouncesFeatureChange`: with the `Fakes` service registry, the parking service registers after `start()` → `RecordingHost.featuresChanged` count is 1.
  - `FakePlatformTest.setFeaturesNotifiesTheHost`.
- [ ] **Step 2: Run them and confirm they fail.** `./gradlew :runtime:test --tests '*SdkRuntimeBootTest*' :adapter-hqc:test :adapter-fake:test`. Expected: FAIL (compile).
- [ ] **Step 3: Implement** as in Interfaces.
- [ ] **Step 4: Run everything.** `./gradlew check`. Expected: PASS, including the existing `featuresAreReadAgainBeforePluginsLoad` and `missingParkingFeatureIsUnsupported`.
- [ ] **Step 5: Commit.** `git commit -m "fix(runtime): features the adapter learns after the load reach running plugins (M1)"`

---

### Task 8: bike-rack: in-memory arm, safe disarm, 5 s re-arm window (M6)

**Files:**
- Modify: `plugins/bike-rack/src/main/kotlin/sample/bikerack/BikeRackPlugin.kt`
- Test: `plugins/bike-rack/src/test/kotlin/sample/bikerack/BikeRackPluginTest.kt`

**Behaviour** [decided in this plan]:
- **The bug today.** On ignition off the handler calls `storage.put("active","false")` first. `"false"` is one byte longer than `"true"`, so at the storage cap the put throws. The rest of the handler then never runs: `waiting` stays subscribed, and the first parking value after ignition off mutes the rear sensors.
- **Arm state.** `armed` is an in-memory `@Volatile` flag, read from storage (`active == "true"`) once in `onEnable`. Every mute decision reads the flag, never storage.
- **`IgnitionChanged(false)` while armed:**
  - in this order: `armed=false`, cancel `waiting`, release the held `VehicleOverride` (if any);
  - then `storage.remove("active")` in `try`/`catch (e: Exception)`. A failure logs `could not save the disarm: <msg>; the rack stays disarmed for this run`;
  - start the re-arm window: a `ctx.scheduler().after(REARM_WINDOW_MS)` task ends it.
- **`IgnitionChanged(false)` while not armed:** does nothing and writes nothing. KL15 bounces cause no storage churn.
- **`IgnitionChanged(true)` inside the window:**
  - cancel the window task;
  - `storage.put("active","true")` in `try`/`catch`. On failure, log `could not re-arm: <msg>; staying disarmed` and stay disarmed;
  - on success, `armed=true` and mute as in `onEnable` (current value, or wait for the first one).
- **Constants:** `REARM_WINDOW_MS = 5000L`, on the runtime Clock through `ctx.scheduler()`.
- **Residual, documented in the class KDoc:** the disarm reaches disk within the storage debounce (2 s). A power cut inside that window re-mutes on the next boot, where the runtime's override indicator (Plan 2) shows it.

- [ ] **Step 1: Write the failing tests:**
  - Update `ignitionOffRestoresAndClearsToggle`: `storedActive()` is `null` (removed), not `"false"`.
  - `ignitionOffWhileWaitingAtTheStorageCapNeverMutes`:
    - start with `active=true` and `ecuKnown=false`, and add `storage.plugin.max.bytes=12` to `sdk.properties` (`PluginStorage.entryBytes("active","true")` is 12; `"false"` would be 13);
    - `fake.setIgnition(false)`, then `ecuKnown=true` and `fake.ecuSetSoundDirections(f1r1l1x1)`;
    - expected: `assertNoMoreThan(0)`.
  - `ignitionBlipWithinFiveSecondsReArmsAndMutesAgain`: `active=true`, muted (1 apply); `setIgnition(false)` → the restore apply; `now.ms += 3000`; `setIgnition(true)` → `awaitApplied(3, f1r0l1x1)` and `storedActive()=="true"`.
  - `ignitionOnAfterTheWindowStaysDisarmed`: as above, but `now.ms += 5000` (and let the scheduler tick) before `setIgnition(true)` → no third apply; `storedActive()==null`.
  - `bouncingIgnitionOffEndsDisarmedAndUnmuted`: off, on (re-armed at +1 s), off, off at +1 s each, then `now.ms += 5000` and on → the last apply is the prior; `storedActive()==null`.
- [ ] **Step 2: Run them and confirm they fail.** `./gradlew :plugins:bike-rack:test`. Expected: FAIL.
- [ ] **Step 3: Implement** the behaviour above.
- [ ] **Step 4: Run the tests and the plugin checks.** `./gradlew :plugins:bike-rack:check :plugins:bike-rack:mibPluginJar`. Expected: PASS (v48, LinkCheck, permission scan without `platform.unrestricted`).
- [ ] **Step 5: Commit.** `git commit -m "fix(bike-rack): disarm in memory first, never mute after ignition off, re-arm on a KL15 blip (M6)"`

---

### Task 9: Check stamps from the build (M7, build side)

**Files:**
- Create: `build-logic/src/main/java/mibsdk/build/CheckStamp.java`, `build-logic/src/main/java/mibsdk/build/CheckStampTask.java`
- Modify: `build-logic/src/main/java/mibsdk/build/MibPluginPlugin.java`, `dist/build.gradle.kts`
- Test: `build-logic/src/test/java/mibsdk/build/CheckStampTest.java`, `build-logic/src/test/java/mibsdk/build/MibPluginPluginFunctionalTest.java`

**Stamp format** (ISO-8859-1, `\n` line ends, no timestamp: reproducible). The lines appear in this order: `format`, `kind`, `checks`, then the attributes sorted by key, then the `sha256.` lines sorted by file name.
```
format=1
kind=plugin                                    (or: unit)
checks=classVersion48,linkCheck,permissionScan (unit: classVersion48,distLinkCheck)
plugin.id=bike-rack                            (plugin only)
plugin.unrestricted=false                      (plugin only)
sha256.bike-rack.jar=<64 lowercase hex>        (unit: one line per jar, by file name)
```
[decided in this plan] The stamp is **hashed, not signed**. The threat is accident: a jar from `build/libs`, a hand-edited jar, or a jar rebuilt by a task that skips the checks. It is not an adversary with access to the repo. A signature would need key management and buy nothing here.

**Interfaces:**
```java
public final class CheckStamp {
    public static String sha256(java.io.File f) throws java.io.IOException;            // lowercase hex
    public static String format(String kind, java.util.List<String> checks,
            java.util.Map<String, String> attributes, java.util.List<java.io.File> jars) throws java.io.IOException;
    public static void write(java.io.File stamp, String kind, java.util.List<String> checks,
            java.util.Map<String, String> attributes, java.util.List<java.io.File> jars) throws java.io.IOException;
}
public abstract class CheckStampTask extends org.gradle.api.DefaultTask {
    @InputFiles @PathSensitive(PathSensitivity.NAME_ONLY) public abstract ConfigurableFileCollection getJars();
    @Input public abstract Property<String> getKind();
    @Input public abstract ListProperty<String> getChecks();
    @OutputFile public abstract RegularFileProperty getStamp();
    @TaskAction public void writeStamp() throws java.io.IOException;   // CheckStamp.write(..., attributes = empty)
}
```
- **Plugins.** `mibPluginJar` gets a `doLast` that calls `CheckStamp.write(<jar>.stamp, "plugin", [classVersion48, linkCheck, permissionScan], {plugin.id, plugin.unrestricted}, [jar])`, plus `outputs.file(<archive>.stamp)`. The jar task already depends on the three checks, so a stamp exists only for a jar whose checks ran.
- **Dist.** `dist/build.gradle.kts` registers `unitStamp` (`CheckStampTask`: kind `unit`, checks `classVersion48`, `distLinkCheck`; jars = the three `build/dist/*.jar`; stamp `build/dist/mibsdk-unit.stamp`). It `dependsOn(distLinkCheck)`, and `assembleUnit` depends on it.

- [ ] **Step 1: Write the failing tests:**
  - `CheckStampTest.formatIsOrderedAndReproducible`: the exact expected text for two fixture jars and one attribute; calling it twice gives the same text.
  - `CheckStampTest.sha256MatchesKnownVector`: a file holding `abc` hashes to `ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad`.
  - `MibPluginPluginFunctionalTest.pluginJarComesWithAStampMatchingItsHash`: after the existing plugin build, `build/mibsdk/<id>.jar.stamp` exists, its `sha256.<id>.jar` equals `CheckStamp.sha256(jar)`, and `plugin.unrestricted=false`.
- [ ] **Step 2: Run them and confirm they fail.** `./gradlew -p build-logic test`. Expected: FAIL (compile).
- [ ] **Step 3: Implement** as in Interfaces.
- [ ] **Step 4: Run the build-logic tests and the dist build.** `./gradlew -p build-logic test && ./gradlew :dist:assembleUnit :plugins:bike-rack:mibPluginJar`. Expected: PASS; `dist/build/dist/mibsdk-unit.stamp` lists the three jars; `plugins/bike-rack/build/mibsdk/bike-rack.jar.stamp` exists.
- [ ] **Step 5: Commit.** `git commit -m "feat(build): hashed check stamps for unit and plugin jars (M7)"`

---

### Task 10: mibsdk-dev: refuse unstamped jars (M7), I2 wording, ledger v2 text

**Files:**
- Modify: `installer/mibsdk-dev`, `installer/test_mibsdk_dev.py`

**Interfaces:**
- Consumes: the stamp format (Task 9); the ledger v2 line (Global Constraints).
- Produces: an embedded verifier `STAMP_PY` (run like `LEDGER_PY`, `python3 -I -`), with argv `verify <stamp> <kind> <expected-id-or-"-"> <jar>...`. It exits 0 and prints `unrestricted=<true|false>` (plugin) or nothing (unit), or exits 1 with one line on stderr naming the reason: `missing`, `format`, `kind`, `id <x> != <y>`, `no hash for <name>`, `hash mismatch for <name>`.
- `install`: before `connect`, `STAMP_PY verify "$DIST/mibsdk-unit.stamp" unit - <three jars>`. Failure → `die "$DIST is not a checked build (<reason>); build it with: ./gradlew :dist:assembleUnit; nothing changed"`.
- `push`: after reading the id, `STAMP_PY verify "$jar.stamp" plugin "$id" "$jar"`. Failure → `die "$jar has no valid check stamp (<reason>); only jars built by the mibsdk.plugin convention can be pushed: ./gradlew :<project>:mibPluginJar"`. If `unrestricted=true`: `info "WARNING: $id declares platform.unrestricted: it may use reflection, exec or exit; the SDK cannot contain it"`.
- No bypass flag. [decided in this plan]
- I2 refusal in `check_ledger` (exact lines, `info`/`die` as today):
  ```
  refusing to uninstall: these overrides of vehicle state are still in force (key=plugin|scope|prior|active|A or R):
    <lines>
  let the SDK put the prior values back first: mibsdk-dev safe-mode on && mibsdk-dev restart
  (safe mode loads no plugin and restores every override in the ledger; check with: mibsdk-dev log), then run uninstall again.
  after a later reinstall, leave safe mode with: mibsdk-dev safe-mode off
  to uninstall anyway and leave those settings overridden, add --force
  ```
  The `--force` warning's format hint becomes `key=plugin|scope|prior|active|A or R`, and the `LEDGER_PY` comment names both line forms.

- [ ] **Step 1: Update the fixtures, then write the failing tests:**
  - Add a test helper `stamp(path, kind, jars, attrs)` that writes the Task 9 format with `hashlib.sha256`. `FakeUnit.__init__` stamps the three dist jars. `PushTest.plugin_jar` writes `<jar>.stamp` (kind plugin, `plugin.id`, `plugin.unrestricted=false`).
  - `InstallTest.test_refuses_a_dist_without_a_stamp` (nothing on the fake unit changed).
  - `InstallTest.test_refuses_a_jar_that_does_not_match_its_stamp` (Review Focus 5): rewrite one dist jar after stamping → refused, with `hash mismatch for mibsdk-runtime.jar` and `:dist:assembleUnit` in stderr.
  - `PushTest.test_refuses_a_jar_without_a_stamp`, `PushTest.test_refuses_a_stamp_for_another_id`, `PushTest.test_refuses_a_stale_stamp` (Review Focus 5): the jar is rebuilt with other content after stamping. Each one: exit ≠ 0, no `plugins/<id>.jar` on the unit.
  - `PushTest.test_warns_loudly_for_an_unrestricted_plugin`: exit 0 and `WARNING: hello declares platform.unrestricted` in stderr.
  - In `UninstallTest.test_refuses_while_an_override_is_in_force`, replace the `"disable the plugin"` assertion with `"mibsdk-dev safe-mode on && mibsdk-dev restart"` and `"safe-mode off"`. Add `test_refusal_shows_a_pending_restore_line`: the ledger line `parking.sound.rear=bike-rack|PERSISTENT|on|off|R` is printed verbatim.
- [ ] **Step 2: Run them and confirm they fail.** `python3 -m unittest installer/test_mibsdk_dev.py`. Expected: the new and changed tests FAIL.
- [ ] **Step 3: Implement** as in Interfaces, and update `usage()` for `install` and `push`.
- [ ] **Step 4: Run the tests.** `python3 -m unittest installer/test_mibsdk_dev.py installer/test_gen_bundles.py`. Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "feat(installer): refuse jars without a matching check stamp (M7); safe-mode advice on refusal (I2)"`

---

### Task 11: `mibsdk-dev remove <id>`

**Files:**
- Modify: `installer/mibsdk-dev`, `installer/test_mibsdk_dev.py`

**Interfaces:**
- Usage line: `remove <id> [--purge-data]`. It removes `plugins/<id>.jar` and the plugin's `grants.properties` line, then writes a fresh reload token so a running SDK disables the plugin and restores its overrides. It then waits up to `$MIBSDK_REMOVE_WAIT_S` seconds (default 30) for the ledger to drop the plugin's entries. `--purge-data` then also removes `$PERSIST/data/<id>.properties`.
- In `main`'s arity parsing, `remove` is `want=1` after stripping `--purge-data` (handled like uninstall's flags).
- Steps, in order:
  1. `check_id`; `connect`; `need_sdk`.
  2. If none of `plugins/<id>.jar`, the grants line or `data/<id>.properties` exists → `die "<id> is not installed on $HOST"`.
  3. `rw_begin`; `sweep_push_tmp`; `rsh "rm -f '$SDK/plugins/<id>.jar'"`, then `is_present` must say absent, else die.
  4. Rewrite grants without the line whose key is exactly `<id>`. Reuse the grant command's Python with a "drop" mode.
  5. `push_file` a fresh token to `plugins/<id>.reload` (same token recipe as `push`); `rw_end`. Print `removed <id>: plugins/<id>.jar and its grants line; reload requested`.
  6. Ledger wait: poll `$LEDGER` every 2 s (`SECONDS` budget) for lines containing `=<id>|` (fixed-string match; ids cannot contain `|`).
     - No entries at first look: `<id> has no overrides in the ledger`.
     - Entries that are gone in time: `overrides of <id> restored`.
     - Otherwise: `WARNING: these overrides of <id> are not restored yet:` plus the lines, then `they are restored when the SDK next starts (the plugin is no longer installed); check with: mibsdk-dev log`. Exit 0.
  7. With `--purge-data`: `rm -f` the data file and its `.tmp` (no remount; `$PERSIST` is writable). Print `cleared the data of <id>` and the `data-clear` restart advice.

- [ ] **Step 1: Write the failing tests** in a new `RemoveTest` (install first; `MIBSDK_REMOVE_WAIT_S=0` in `env` unless noted):
  - `test_removes_the_jar_and_grant_and_requests_a_reload`: after `push hello` and `grant hello vehicle.read` → `plugins/hello.jar` is gone, `hello` has no grants line, `plugins/hello.reload` holds a new non-empty token, and `/mnt/app` ends read-only (`app.ro` exists).
  - `test_leaves_a_plugin_with_a_longer_id_alone` (Review Focus 4): with plugins `bike` and `bike-rack` both pushed and granted, and ledger entries for both, `remove bike` → `bike-rack.jar`, its grants line, its data and its ledger line are untouched. The warning lists only the `=bike|` line.
  - `test_keeps_data_unless_purge_data`: `data/hello.properties` survives `remove hello`. A second plugin's data survives `remove other --purge-data`, while `other`'s data is gone.
  - `test_reports_overrides_still_in_the_ledger`: the ledger holds `parking.soundDirections=hello|PERSISTENT|F1R1L1X1|F1R0L1X1|R` → exit 0, and stderr has `not restored yet` and that line.
  - `test_refuses_an_unknown_plugin`: exit ≠ 0 with `is not installed`.
- [ ] **Step 2: Run them and confirm they fail.** `python3 -m unittest installer/test_mibsdk_dev.py -k Remove`. Expected: FAIL.
- [ ] **Step 3: Implement** as in Interfaces; add the command to `usage()`.
- [ ] **Step 4: Run all installer tests.** `python3 -m unittest installer/test_mibsdk_dev.py installer/test_gen_bundles.py`. Expected: PASS.
- [ ] **Step 5: Commit.** `git commit -m "feat(installer): mibsdk-dev remove <id> (jar, grant, reload, ledger wait, optional data)"`

---

### Task 12: Bench verification and the readiness record

All steps marked **[BENCH]** run against the bench unit (10.20.0.23) and need it reachable. Steps without the mark are host-only. Never run `pidin … arg`. Never select "Start update" in any menu.

**Files:**
- Modify: `docs/car-readiness.md`, `docs/bench-e2e.md`

- [ ] **Step 1: Host gate.** `./gradlew check :dist:assembleUnit :plugins:hello:mibPluginJar :plugins:bike-rack:mibPluginJar :plugins:crashy:mibPluginJar && python3 -m unittest installer/test_mibsdk_dev.py installer/test_gen_bundles.py`. Expected: PASS; each jar has its stamp.
- [ ] **Step 2: Refusal without a unit.** `installer/mibsdk-dev push plugins/hello/build/libs/hello-car.jar` (if that jar exists; otherwise copy `hello.jar` without its stamp to a temp path and push that). Expected: refused before any SSH (`has no valid check stamp`).
- [ ] **Step 3 [BENCH]: Install and restart.** `installer/mibsdk-dev install && installer/mibsdk-dev restart`. Wait for the reset (F3). If the unit is unreachable after 5 min: `ssh rpi-bench keypanel up`. Expected: `mibsdk-dev status` shows `runtime started adapter=hqc-nar-g22-c10 safeMode=false`.
- [ ] **Step 4 [BENCH]: Durability facts (fsync gate).**
  - Record the `STATUS mibsdk durability persist=/mnt/persist/mibsdk …` line verbatim.
  - Run `ssh … root@10.20.0.23 mount` and record the `/mnt/persist` line (file system type and options).
  - Run `ssh … root@10.20.0.23 'ls /mnt/persist/mibsdk'`. Expected: no `.tmp` and no `.durable-probe` left.
- [ ] **Step 5 [BENCH]: Push with stamps; remove crashy.**
  - `installer/mibsdk-dev push plugins/hello/build/mibsdk/hello.jar` and the same for bike-rack. Expected: `ENABLED` lines.
  - `installer/mibsdk-dev remove crashy --purge-data`. Expected:
    - the log shows `crashy DISABLED plugin jar not found`;
    - `crashy has no overrides in the ledger`;
    - `grants.properties` has no `crashy` line.
  - This closes the bench-state note in `docs/bench-e2e.md` ("Removing crashy needs a `/mnt/app` write").
- [ ] **Step 6 [BENCH]: KL15 off/on (T18 plumbing on the unit).**
  - `ssh rpi-bench systemctl stop kl15`, wait 10 s, then `cat /mnt/persist/mibsdk/boot.state` on the unit. Expected: `lastShutdownClean=true`.
  - `ssh rpi-bench systemctl start kl15`. Expected: `hello ignition=false` / `ignition=true` in the log, and `ledger.properties` empty or absent. The bench has no PDC ECU (F7), so no override can be exercised here.
- [ ] **Step 7 [BENCH, OPTIONAL, needs the owner's explicit OK]: Power cut.** Do this only if the owner confirms a switchable supply for the MMX that is safe to cut. KL15 stop is not a power cut. Procedure:
  1. `systemctl stop kl15` on rpi-bench.
  2. Within 1 s of the `boot.state` write (watch `mibsdk-dev log` for the ignition line), cut power.
  3. Restore power and run `keypanel up`.
  4. Check `boot.state` (three lines, `lastShutdownClean=true`, no `.tmp`).
  5. Repeat three times.
  If not done, the record says so (Step 8).
- [ ] **Step 8: Record.**
  - **`docs/bench-e2e.md`:** add a section "Car-readiness gates (Plan 4 prerequisites)" with Steps 3–7: commands and verbatim output lines, plus the `mount` line and the durability STATUS line.
  - **`docs/car-readiness.md`:** add a Status column to the gate table:
    - I4, T11, T18, M6, M7: `closed (host tests; <commit>)`;
    - fsync: `closed: file fsync + post-rename fsync of the target; directory fsync <ok|unsupported on J9/QNX: …>; power-cut <verified n/3 | not verified: relies on qnx6 committing a snapshot on fsync>`;
    - F7: `open (car)`.
  - **Known follow-ups:**
    - remove the entries for `remove <id>`, I2, the counter gap, M1 and all-FAILED;
    - add "M1: plugins that read `has()` only in `onEnable` still miss a late feature (no API event; Plan 2)";
    - add "bike-rack: a power cut within 2 s of ignition off can keep the rack armed for the next boot (storage debounce)";
    - add "I4 on the car: whether the PDC ECU reports every write, including unchanged values, decides how often the `last report` rule is used (F7)".
- [ ] **Step 9: Commit.** `git add docs/car-readiness.md docs/bench-e2e.md && git commit -m "docs: car-readiness gate status and bench record"`

---

## Self-Review

1. **Spec and gate coverage.**
   - I4 → Task 4 (plus the Task 2 ECU model).
   - T11 → Task 3.
   - T18 → Task 5 (power-loss test) and Task 12 Step 6 (bench plumbing).
   - M6 → Task 8.
   - M7 → Tasks 9 and 10.
   - fsync → Task 1 and Task 12 Steps 4 and 7.
   - `remove <id>` → Task 11. I2 → Task 10.
   - Counter gap and all-FAILED → Task 6. M1 → Task 7.
   - F7 is car-only, out of scope, and recorded as open.
   - The mibpkg read-only remount, the indirect wrap-count proof, F3 restart and the wrap-latch indicator stay follow-ups: not in scope.
   - Spec §6.1 is amended in Task 4.
2. **Step scan.** Every code step names its file, its signatures and the values the gates pin: 5000 ms confirm, 7000 ms drain, 5000 ms re-arm, 12-byte cap, the stamp lines, the refusal text. No step says "handle edge cases".
3. **Type consistency.**
   - `awaitSettled(long)` replaces `awaitIdle` everywhere (Task 4).
   - `markPluginsLoading(boolean)` (Task 6) is the only form.
   - `LiveFeatures` and `ParkingSource` (Task 7) are used by `PluginContextImpl` and `HooksImpl` only.
   - `ecuDropWrites`/`ecuFailWrites`/`ecuReportWrites` (Task 2) match their uses in Tasks 3–5.
   - `STATE_HELD`/`STATE_RESTORING` (Task 3) match the `|A`/`|R` assertions.
   - `CheckStamp.write` (Task 9) matches the format `STAMP_PY` reads (Task 10).
4. **Review Focus.** Each of the five lines has a named test in its owning task (Tasks 3, 4, 10, 11).
5. **Proportion.** The plan carries signatures, state tables and test assertions. The only code-shaped blocks are interfaces and exact copy (stamp format, refusal text).
