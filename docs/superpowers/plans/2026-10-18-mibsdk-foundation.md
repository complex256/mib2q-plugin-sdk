# MHI2Q Plugin SDK — Plan 1: Foundation (bench week) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A working plugin SDK on the bench MHI2Q. It includes:
- the toolchain;
- the firmware-neutral API;
- the runtime, with containment, permissions, undo ledger and safe mode;
- a fake platform;
- the HQC adapter, which bootstraps through LSD's bundle list and wraps DSI services with `java.lang.reflect.Proxy`;
- hot reload, an installer, and the bike-rack plugin, tested against the fake platform.

**Architecture:**
- **Plugins** are written in modern Java or Kotlin against `mibsdk.api`. The build pipeline lowers them to v48 class files, link-checks them against the car's JCL, and scans them for escape routes.
- **The runtime** (`mibsdk.runtime`) is firmware-neutral. It loads each plugin in a filtered class loader and runs all plugin code on SDK-owned threads.
- **The adapter** reaches the HMI only through the `mibsdk.platform` SPI. The HQC adapter is the only code that touches stock classes.
- **Bootstrap path:**
  1. An on-disk `bundles_dsitracer_enabled.properties` names `mibsdk.adapter.hqc.ChainingConfigurator` as the service-delegate configurator. It wraps allow-listed DSI services in place, then chains to `TraceConfigurator` when DSITracer was active.
  2. A `MibSdk` autostart bundle then starts the runtime.

**Tech Stack:**
- Gradle 8.13 with an included build `build-logic`.
- JDK 17 (javac + Jabel 1.0.0) and Corretto 8 (Retrolambda 2.5.7).
- Retrotranslator 1.2.9.
- Kotlin 2.0.21 with ProGuard 7.6.0 / proguard-core 9.1.6.
- ASM 9.7, JUnit 5.10.2, and Python 3 (installer generator).

**Spec:** `docs/superpowers/specs/2026-10-18-mib2q-plugin-sdk-design.md`. Background: `docs/research/2026-10-18-java-onboard-map.md` and `spikes/modern-java/README.md`.

**Plan series:**
- **This plan:** the bench-provable foundation.
- **Plan 2:** UI — Plugins menu, native templates, Car-menu and status-bar slots, the hello-menu and daemon-status reference plugins. Starts with an RE spike on menu injection.
- **Plan 3:** mibpkg plugin package type and permission prompt.
- **Plan 4:** car session (PDC verification, car `lsd/jars` coexistence, respawn timing) and the carplay-rgi migration.

## Global Constraints

**Bytecode and linking**
- Every class that runs on the unit is **class-file version 48**. v49/v50 are rejected by J9 2.3 even with `-noverify`. The build fails on anything else.
- Every on-unit artifact passes **LinkCheck**: call sites, field refs, type refs, class headers (super/interfaces) and catch types, against the car JCL (`java/**`, `javax/**` extracted from `$mibsdk.stockLsdJar`) plus its declared car classpath.
- The **adapter never compiles against stock compile-time constants**: no `ATTR_*`, `RT_*` or `ATTRVALIDFLAG_*` field references in `adapter-hqc/src/main`. jxe2jar constants are byte-swapped and javac inlines them. Read them at runtime via `DsiConstants`.

**Threads**
- **No plugin code ever runs on a stock thread.**
- The configurator and the DSI proxy run on stock threads. They must never throw (catch `Throwable` and pass through) and never block (no locks, no I/O, no allocation-heavy work beyond the proxy call itself).
- SDK-created threads are daemon threads with priority `Thread.NORM_PRIORITY - 1`.

**Firmware and safety**
- Supported HMI, compared as an exact string: `HMI_AU_MIB2_HQC_NAR_G22_C10_180803`, read from the `HMI = …` line of `/ifs/version_info.txt`. No match means the SDK stays off and no plugin loads.
- `bundles.properties` semantics: **every key other than `de.audi.tghu.serviceDelegateConfigurator` and `Bundle.Startup.Autostart` is treated as a bundle definition**. Never add SDK settings there; they go in `sdk.properties`.
- Never log, print or read the j9 command line (`lsd.sh` embeds the VIN and IMSI). Don't use `pidin … arg`.

**Paths on the unit**
- Base dir: `/mnt/app/eso/hmi/lsd/mibsdk/`, holding:
  - `sdk.properties`, `grants.properties`, `ledger.properties`, `boot.state`, `SAFE_MODE` (marker);
  - `plugins/<id>.jar`, `plugins/<id>.reload`;
  - `cache/`, `data/<id>.properties`, `stock/`.
- SDK jars go in `/mnt/app/eso/hmi/lsd/jars/`, named `mibsdk-runtime.jar`, `mibsdk-adapter-hqc.jar`, `mibsdk-rt-retrotranslator.jar`.
- Log file: `/tmp/mibsdk.log`. `/tmp` is `/dev/shmem`, which holds files but no directories.
- Bench SSH: `ssh -o HostKeyAlgorithms=ssh-rsa -o PubkeyAcceptedKeyTypes=ssh-rsa root@10.20.0.23`. The unit lacks `date`, `tr`, `md5sum`, `cksum`, so generate files on the Mac. To restart the HMI: `slay -s11 j9`.

**Build pipeline values**
- Retrotranslator `-backport` value:
  `java.util.function:mibsdk.compat.java.util.function;java.util:mibsdk.compat.java.util;java.lang:mibsdk.compat.java.lang`
  - On-unit SDK jars use the **non-embedded** Retrotranslator runtime, i.e. references to `net/sf/retrotranslator/runtime/**`, shipped once as `mibsdk-rt-retrotranslator.jar`.
- Kotlin compiler flags: `-Xlambdas=class -Xsam-conversions=class -Xstring-concat=inline -Xno-param-assertions -Xno-call-assertions -Xno-receiver-assertions`, `jvmTarget=1.8`.
- ProGuard for Kotlin plugins:
  ```
  -keepnames class ** { *; }
  -keepattributes !LocalVariableTable,!LocalVariableTypeTable,*
  -dontoptimize
  -dontwarn **
  -ignorewarnings
  ```
  The keepnames pass exists only so `LocalVariableTable` is dropped; Kotlin's `$i$a$-…` names are illegal in v48.
- Plugin class-loader parent allows **only** these prefixes: `java.`, `javax.`, `mibsdk.api.`, `mibsdk.compat.`, `net.sf.retrotranslator.runtime.`.

**Repo and environment**
- Git authorship: `user.name=complex256`, `user.email=me@complex256.dev`. Never put the owner's personal name, username or email in files or commits (rule in `~/complex-projects/CLAUDE.md`); use `~`/`$HOME` for home paths.
- Gradle properties, with defaults in `gradle.properties`:
  - `mibsdk.jdk8Home` (this machine: `~/Library/Java/JavaVirtualMachines/corretto-1.8.0_372/Contents/Home`, expanded to an absolute path) (machine-local: put it in `~/.gradle/gradle.properties` rather than the committed file);
  - `mibsdk.stockLsdJar` = path to jxe2jar's `lsd.jar`;
  - `mibsdk.stockDsiTracerJar` = path to stock `DSITracer.jar`.
  - Stock jars are never committed.

## Review Focus

1. **Wall clock jumps.** The unit boots at 1970 and GPS later steps the clock by decades. Every duration (quarantine window, healthy timer, debounce, slow/hung callback) must use `MonotonicClock`, never `System.currentTimeMillis()` deltas. **Test:** Task 7, `MonotonicClockTest.wallClockJumpDoesNotAffectUptime`.
2. **The configurator wrapped services but the SDK runtime never started** (adapter mismatch, safe mode, crash in `SdkBundleActivator`). Wrapped DSI services must keep forwarding every call and every listener update unchanged. **Test:** Task 14, `DsiServiceProxyTest.forwardsWithNoRuntimeAttached`.
3. **Short ignition cycles.** Three drives under 60 s each must **not** land the next boot in safe mode, because ignition-off is a clean shutdown. **Test:** Task 12, `BootGuardTest.ignitionOffCountsAsCleanShutdown`.
4. **Hot reload while the old jar is open** or replaced in place. The new code must load and the old loader must not serve stale classes. **Test:** Task 6, `PluginJarCacheTest.reloadAfterInPlaceOverwriteLoadsNewBytes`.
5. **Permission scan false positives** from compiler-generated `class$` methods (1.4 class literals) and from the shrunk Kotlin stdlib (`kotlin/**`). An ordinary plugin must pass without `platform.unrestricted`. **Test:** Task 2, `PermissionScanTest.ignoresSyntheticClassDollarAndKotlinStdlib`.

---

## File Structure

```
mib2q-plugin-sdk/
├── settings.gradle.kts            includeBuild("build-logic"); modules below
├── build.gradle.kts               shared repo config
├── gradle.properties              mibsdk.* defaults (non-machine-local)
├── build-logic/                   Gradle plugins: mibsdk.car-library, mibsdk.plugin
│   └── src/main/java/mibsdk/build/
│       ├── Toolchain.java           pinned versions, backport string, Kotlin flags, allowed prefixes
│       ├── CarJclTask.java          extracts java/** javax/** from stockLsdJar → build/carjcl
│       ├── BackportTask.java        Retrolambda (JDK8) → Retrotranslator → v48 dir
│       ├── KotlinShrinkTask.java    ProGuard (plugin + kotlin-stdlib)
│       ├── LinkCheck.java / LinkCheckTask.java
│       ├── PermissionScan.java / PermissionScanTask.java
│       ├── ClassVersionCheckTask.java
│       ├── CarLibraryPlugin.java    convention for SDK modules
│       └── MibPluginPlugin.java     convention for plugin projects (Java/Kotlin)
├── compat/      mibsdk.compat.java.{lang,util,util.function}.* (Java 1.4 source, javac 8)
├── api/         mibsdk.api.*            (what plugin authors see)
├── platform/    mibsdk.platform.*       (adapter SPI + HookTable)
├── runtime/     mibsdk.runtime.*        (loader, lifecycle, containment, permissions, ledger, boot guard, dev channel)
├── adapter-fake/ mibsdk.adapter.fake.*  (host-only FakePlatform for tests)
├── adapter-hqc/ mibsdk.adapter.hqc.*    (configurator, DSI proxy, bridges, bundle activator)
├── dist/        assembles build/dist/*.jar for the unit
├── installer/   gen_bundles.py, test_gen_bundles.py, mibsdk-dev (bash)
├── plugins/hello  (Java)  plugins/crashy (Java)  plugins/bike-rack (Kotlin)
├── docs/ …   spikes/ … (existing)
```

---

### Task 1: Repo, build-logic skeleton, compat module, backport pipeline

**Files:**
- Create: `.gitignore`, `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, Gradle wrapper (8.13)
- Create: `build-logic/settings.gradle.kts`, `build-logic/build.gradle.kts`, `build-logic/src/main/java/mibsdk/build/{Toolchain,CarJclTask,BackportTask,ClassVersionCheckTask,CarLibraryPlugin}.java`
- Create: `compat/build.gradle.kts`, `compat/src/main/java/mibsdk/compat/java/lang/{_Long,_Integer,AutoCloseable}.java`, `compat/src/main/java/mibsdk/compat/java/util/{Objects,_Map,_Locale}.java`, `compat/src/main/java/mibsdk/compat/java/util/function/{Function,Supplier,BiFunction,Consumer,Predicate,Runnable}.java`. Port the spike's shims from `spikes/modern-java/compat/src`; `Consumer`/`Predicate` follow the same raw-`Object` style.
- Create: `compat/src/test/java/mibsdk/compat/CompatShimTest.java`

**Interfaces:**
- Produces Gradle plugin id `mibsdk.car-library` with extension `mibsdk { carClasspath = files(...) }`. It registers:
  - `carJcl`, output `build/carjcl/`, shared from the root project;
  - `backport`, input = compiled classes, output `build/v48/`;
  - `classVersionCheck`;
  - `carJar`, output `build/libs/<project>-car.jar`.
  - `check` depends on `classVersionCheck`.
- Produces `Toolchain.BACKPORT` (the exact `-backport` string), `Toolchain.KOTLIN_FLAGS`, `Toolchain.ALLOWED_PARENT_PREFIXES`.

- [ ] **Step 1: Init the repo.**
  - `git init` in `~/complex-projects/mib2q-plugin-sdk`, then `git config user.name complex256 && git config user.email me@complex256.dev`.
  - `.gitignore`: `build/`, `.gradle/`, `spikes/modern-java/{tools,out,out-k,carjcl}/`, `spikes/modern-java/car/{payload,build,results-*.log}`, `*.class`.
  - Commit the existing `docs/` and `spikes/` as `chore: import design, research and spike`.

- [ ] **Step 2: Write `CompatShimTest`.** It runs on the host JVM:
  - `_Map.merge(m,"a",2L, (a,b)->a+b)` on `{a=5}` returns 7 and `m.get("a")==7L`.
  - `_Map.merge` with a null result removes the key.
  - `_Map.getOrDefault(m,"zz","d")=="d"`.
  - `Objects.equals(null,null)` is true.
  - `_Integer.hashCode(42)==42`.
  - `_Locale.ROOT.getLanguage().equals("")`.

- [ ] **Step 3: Run it and confirm it fails.**
  Run: `./gradlew :compat:test` — expected: FAIL, compilation errors for missing classes.

- [ ] **Step 4: Implement the shims and wire the build.**
  - **compat compilation:** forked javac from `mibsdk.jdk8Home` with `-source 1.4 -target 1.4`; tests compile with JDK 17 against those classes.
  - **`BackportTask`:**
    1. Run Retrolambda as `$jdk8Home/bin/java -Dretrolambda.inputDir=… -Dretrolambda.outputDir=… -Dretrolambda.classpath=<input>:<carClasspath>:$jdk8Home/jre/lib/rt.jar -Dretrolambda.bytecodeVersion=49 -Dretrolambda.defaultMethods=true -jar retrolambda-2.5.7.jar`.
    2. Run Retrotranslator main `net.sf.retrotranslator.transformer.Retrotranslator` on the JDK8 classpath (transformer + runtime + compat classes) with `-srcdir … -destdir … -target 1.4 -stripsign -backport <Toolchain.BACKPORT>`, and **no `-embed`**.
    3. Resolve tool jars as Gradle configurations from Maven Central, using the coordinates in the Tech Stack.
  - **`ClassVersionCheckTask`:** fails listing every class whose major version ≠ 48.

- [ ] **Step 5: Run the tests and confirm they pass.**
  Run: `./gradlew :compat:test :compat:classVersionCheck` — expected: PASS.

- [ ] **Step 6: Commit.**
  `git add -A && git commit -m "build: build-logic backport pipeline and compat shims"`

### Task 2: LinkCheck, permission scan, car JCL

**Files:**
- Create: `build-logic/src/main/java/mibsdk/build/{LinkCheck,LinkCheckTask,PermissionScan,PermissionScanTask}.java`
- Create: `build-logic/src/main/resources/mibsdk/linkcheck-known-unreadable.txt`, containing `java/lang/Double` (jxe2jar emits an unreadable `Double.class`; treat it as present)
- Test: `build-logic/src/test/java/mibsdk/build/{LinkCheckTest,PermissionScanTest}.java`. Fixtures are compiled in-test with `javax.tools.ToolProvider`, then written as v48 by ASM where needed.

**Interfaces:**
- Produces `LinkCheck.Result LinkCheck.run(File checkedDirOrJar, List<File> classpath, List<File> transitiveLibs)`, where:
  - `Result.missing()` is a sorted `List<String>` of lines like `class java/lang/AutoCloseable (interface of a/B)`, `member java/util/Map.merge(...)`, `class java/util/regex/Pattern (via net/sf/retrotranslator/runtime/java/lang/_String.split(...))`;
  - `transitiveLibs` are jars whose method bodies are followed recursively from each referenced member, so a gap inside the Retrotranslator runtime is attributed to the plugin call that reaches it.
- Produces `PermissionScan.Result PermissionScan.run(File dirOrJar)`, where `Result.findings()` is a `List<String>` of `owner.method -> java/lang/reflect/Method.invoke`.
- Produces tasks `linkCheck` and `permissionScan`, both wired into `check` by `mibsdk.car-library`. `permissionScan` fails unless the manifest grants `platform.unrestricted`; that check is wired in Task 13.

- [ ] **Step 1: Write `LinkCheckTest`:**
  - `reportsMissingInterfaceInClassHeader`: a class implementing `java.lang.AutoCloseable`, checked against `build/carjcl`, gives `missing` containing `class java/lang/AutoCloseable (interface of fixture/Res)`.
  - `reportsMissingCatchType`: a `catch (java.util.regex.PatternSyntaxException e)` is reported.
  - `reportsMissingMember`: a call to `java.util.Map.merge` is reported as `member java/util/Map.merge(Ljava/lang/Object;Ljava/lang/Object;Ljava/util/function/BiFunction;)Ljava/lang/Object;`.
  - `followsTransitiveLibraryBodies`: a fixture lib jar method `lib/L.f()` that calls `java/util/regex/Pattern.compile`, with the checked class calling `lib/L.f()`, gives `missing` containing `class java/util/regex/Pattern (via lib/L.f()V)`.
  - `treatsKnownUnreadableAsPresent`: a reference to `java/lang/Double.valueOf` is not reported.
  - `cleanFixturePasses`: a class using only `java/lang/StringBuffer` gives `missing` empty.

- [ ] **Step 2: Write `PermissionScanTest`:**
  - `flagsReflectionAndExec`: `Method.invoke`, `Class.forName` (in a normal method), `Runtime.exec`, `System.exit` and `Thread.stop` each produce one finding.
  - `ignoresSyntheticClassDollarAndKotlinStdlib`: `Class.forName` inside a synthetic static method named `class$`, and any reflection inside classes under `kotlin/`, produce zero findings.

- [ ] **Step 3: Run them and confirm they fail.**
  Run: `./gradlew -p build-logic test` — expected: FAIL (classes missing).

- [ ] **Step 4: Implement.**
  - Use ASM 9.7 (`org.ow2.asm:asm`).
  - `LinkCheck` indexes classes (members + supers) from classpath dirs/jars, checks instructions, LDC types, try-catch types and class headers, and resolves members up the super/interface chain.
  - Transitive libs: BFS over `(owner,name,desc)` method bodies inside those jars, with a visited set.
  - `CarJclTask` (Task 1 stub) extracts `java/**` and `javax/**` from `mibsdk.stockLsdJar`.

- [ ] **Step 5: Run the tests and confirm they pass.**
  Run: `./gradlew -p build-logic test` — expected: PASS.

- [ ] **Step 6: Wire the tasks into compat.**
  Wire `linkCheck` into `mibsdk.car-library`, with classpath = `carjcl` + own v48 output + `carClasspath`.
  Run: `./gradlew :compat:check` — expected: PASS.

- [ ] **Step 7: Commit.**
  `git commit -am "build: LinkCheck (headers, catch types, transitive libs) and permission scan"`

### Task 3: Plugin API (`mibsdk.api`)

**Files:**
- Create in `api/src/main/java/mibsdk/api/`:
  - `MibPlugin`, `PluginContext`, `Log`, `Storage`, `Events`, `Listener`, `Subscription`, `Scheduler`;
  - `IgnitionChanged`, `ParkingSoundChanged`, `TrailerHitchedChanged`, `SoundDirections`;
  - `Vehicle`, `Parking`, `VehicleOverride`, `OverrideScope`, `OverrideConflictException`;
  - `Hooks`, `HookPoint`, `Veto`, `HookObserved`;
  - `Permissions`, `Features`, `PermissionDeniedException`, `SdkInfo`.
- Create: `api/build.gradle.kts` (applies `mibsdk.car-library`, `carClasspath` = compat)
- Test: `api/src/test/java/mibsdk/api/SoundDirectionsTest.java`

**Interfaces (exact; later tasks rely on these):**
```java
public abstract class MibPlugin { protected abstract void onEnable(PluginContext ctx) throws Exception; protected void onDisable() throws Exception {} }
public interface PluginContext { String pluginId(); Log log(); Storage storage(); Events events(); Hooks hooks();
  Vehicle vehicle(); Scheduler scheduler(); boolean has(String feature); boolean granted(String permission); }
public interface Log { void info(String m); void warn(String m); void error(String m, Throwable t); }
public interface Storage { String get(String key, String def); void put(String key, String value); void remove(String key); }
public interface Listener<E> { void on(E event) throws Exception; }
public interface Subscription { void cancel(); }
public interface Events { <E> Subscription on(Class<E> type, Listener<? super E> listener); }
public interface Scheduler { Subscription after(long delayMs, Runnable task); Subscription every(long periodMs, Runnable task); }
public final class SoundDirections { public SoundDirections(boolean front, boolean rear, boolean left, boolean right);
  public boolean front(); rear(); left(); right(); public SoundDirections withRear(boolean rear); equals/hashCode/toString }
public final class IgnitionChanged { public IgnitionChanged(boolean on); public boolean isOn(); }
public final class ParkingSoundChanged { public ParkingSoundChanged(SoundDirections value); public SoundDirections value(); }
public final class TrailerHitchedChanged { public TrailerHitchedChanged(boolean hitched); public boolean isHitched(); }
public interface Vehicle { Parking parking(); }
public interface Parking { SoundDirections soundDirections();
  VehicleOverride overrideSoundDirections(SoundDirections value, OverrideScope scope) throws OverrideConflictException; }
public enum OverrideScope { UNTIL_IGNITION_OFF, PERSISTENT }
public interface VehicleOverride { boolean isActive(); void release(); }
public enum HookPoint { PARKING_SOUND_SET, PARKING_SOUND_UPDATE }
public interface Hooks { Veto veto(HookPoint point); Subscription observe(HookPoint point, Listener<HookObserved> l); }
public interface Veto { void setActive(boolean active); boolean isActive(); void release(); }
public final class HookObserved { public HookObserved(HookPoint p, Object value, boolean vetoed); point(); value(); vetoed(); }
public final class Permissions { VEHICLE_READ="vehicle.read"; VEHICLE_PARKING_WRITE="vehicle.parking.write"; HOOKS_PARKING="hooks.parking"; PLATFORM_UNRESTRICTED="platform.unrestricted"; }
public final class Features { PARKING="vehicle.parking"; IGNITION="vehicle.ignition"; HOOK_PARKING="hook.parking"; }
public class PermissionDeniedException extends RuntimeException { public PermissionDeniedException(String permission); public String permission(); }
public class OverrideConflictException extends Exception { public OverrideConflictException(String key, String holderPluginId); }
public final class SdkInfo { public static final int API_VERSION = 1; }
```
The API has no default methods in interfaces that plugins implement (`Listener`, `Subscription` are single-method). `MibPlugin` is an abstract class (Bukkit style).

- [ ] **Step 1: Write `SoundDirectionsTest`.**
  - `new SoundDirections(true,true,false,false).withRear(false)` equals `new SoundDirections(true,false,false,false)`.
  - Equal values have equal `hashCode`.
  - `toString()` is `"SoundDirections{front=true, rear=false, left=false, right=false}"`.
- [ ] **Step 2: Run it and confirm it fails.**
  Run: `./gradlew :api:test` — expected: FAIL (compile).
- [ ] **Step 3: Implement the API types listed above** (signatures as given). The `HookObserved.value()` type is `SoundDirections` for both parking points.
- [ ] **Step 4: Run the checks and confirm they pass.**
  Run: `./gradlew :api:check` — expected: PASS (tests, classVersionCheck, linkCheck, permissionScan).
- [ ] **Step 5: Commit.**
  `git commit -am "feat(api): v1 plugin API"`

### Task 4: Platform SPI and HookTable

**Files:**
- Create: `platform/src/main/java/mibsdk/platform/{PlatformAdapter,PlatformHost,ParkingIo,HookTable}.java`, `platform/build.gradle.kts`
- Test: `platform/src/test/java/mibsdk/platform/HookTableTest.java`

**Interfaces:**
```java
public interface PlatformAdapter { String id(); boolean supports(String hmiVersion); java.util.Set<String> features();
  void start(PlatformHost host) throws Exception; void stop(); ParkingIo parking(); /* null if no Features.PARKING */ }
public interface PlatformHost { void publish(Object apiEvent); HookTable hooks(); void log(String msg, Throwable t); }
public interface ParkingIo { SoundDirections current(); void apply(SoundDirections value) throws Exception; }
public final class HookTable {
  public HookTable(int observationCapacity);                  // runtime uses 256
  // stock-thread side (lock-free, never blocks, never throws):
  public boolean isVetoed(HookPoint p);
  public void observed(HookPoint p, Object value, boolean vetoed);   // drop-oldest when full
  // runtime side:
  public Object addVeto(HookPoint p);  public void setVetoActive(Object token, boolean active);  public void removeVeto(Object token);
  public HookObserved poll(long timeoutMs) throws InterruptedException;  public long droppedCount();
}
```
`isVetoed` reads a `volatile` per-point array of active tokens, replaced copy-on-write under a runtime-side lock.

- [ ] **Step 1: Write `HookTableTest`:**
  - `noVetoByDefault`.
  - `activeVetoIsSeen`: `addVeto` + `setVetoActive(t,true)` makes `isVetoed(PARKING_SOUND_SET)` true; with `false` it is false again.
  - `removeVetoClears`.
  - `observationQueueDropsOldest`: with capacity 2, observing 3 values leaves `poll` returning values 2 and 3 and `droppedCount()==1`.
  - `observedNeverBlocks`: 10,000 `observed` calls with no consumer finish in under 200 ms.
- [ ] **Step 2: Run them and confirm they fail.** `./gradlew :platform:test` — FAIL.
- [ ] **Step 3: Implement** (bounded ring under a tiny internal monitor for the runtime-side poll; the producer side uses `synchronized` on a private lock held only for an array store; no `wait` on the producer path).
- [ ] **Step 4: Run the checks and confirm they pass.** `./gradlew :platform:check` — PASS.
- [ ] **Step 5: Commit.** `git commit -am "feat(platform): adapter SPI and lock-free HookTable"`

### Task 5: Manifest and MiniJson

**Files:**
- Create: `runtime/src/main/java/mibsdk/runtime/{MiniJson,PluginManifest,ManifestParser,ManifestException}.java`, `runtime/build.gradle.kts` (car-library; `carClasspath` = compat, api, platform)
- Test: `runtime/src/test/java/mibsdk/runtime/ManifestParserTest.java`

**Interfaces:**
```java
public final class MiniJson { public static Object parse(String json) throws ManifestException; } // Map/List/String/Long/Boolean/null
public final class PluginManifest { String id(); String name(); String version(); String author(); int api(); String main();
  java.util.List<String> permissions(); java.util.List<String> dependencies(); }
public final class ManifestParser { public static PluginManifest parse(String json) throws ManifestException; }
```
Manifest file: `plugin.json` at the jar root, e.g.
`{"id":"bike-rack","name":"Bike Rack","version":"1.0.0","author":"complex256","api":1,"main":"…","permissions":["vehicle.read"],"dependencies":[]}`.
Validation:
- `id` matches `^[a-z0-9][a-z0-9-]{1,40}$`;
- `api` == `SdkInfo.API_VERSION`;
- `main` is non-empty;
- `permissions` and `dependencies` are optional (default empty).

- [ ] **Step 1: Write `ManifestParserTest`:**
  - `parsesFullManifest`: all fields round-trip.
  - `rejectsBadId`: `"Bike_Rack"` throws with a message containing `id`.
  - `rejectsWrongApi`: `"api":2` throws with a message containing `api`.
  - `defaultsOptionalLists`.
  - `miniJsonHandlesEscapesAndNesting`: `"a\\\"b"` and nested arrays.
- [ ] **Step 2: Run them and confirm they fail.** `./gradlew :runtime:test --tests '*ManifestParserTest'` — FAIL.
- [ ] **Step 3: Implement** a recursive-descent `MiniJson` (no regex: the car has none).
- [ ] **Step 4: Run the checks and confirm they pass.** Same command, plus `./gradlew :runtime:linkCheck` — PASS.
- [ ] **Step 5: Commit.** `git commit -am "feat(runtime): plugin manifest + MiniJson"`

### Task 6: Plugin loading — filtered parent, jar cache, class loader

**Files:**
- Create: `runtime/src/main/java/mibsdk/runtime/{FilteringParentLoader,PluginJarCache,PluginClassLoaders}.java`
- Test: `runtime/src/test/java/mibsdk/runtime/{FilteringParentLoaderTest,PluginJarCacheTest}.java`. Fixture jars are built in-test from source strings with `javax.tools`.

**Interfaces:**
```java
public final class FilteringParentLoader extends ClassLoader {
  public FilteringParentLoader(ClassLoader delegate, String[] allowedPrefixes); }   // others → ClassNotFoundException
public final class PluginJarCache { public PluginJarCache(java.io.File cacheDir);
  public java.io.File snapshot(java.io.File pluginJar) throws java.io.IOException;   // copies to cache/<name>-<n>.jar, returns copy
  public void purgeExcept(java.util.Set<java.io.File> keep); }
public final class PluginClassLoaders { public static ClassLoader create(java.io.File jarCopy, ClassLoader filteringParent) throws java.io.IOException; }
```

- [ ] **Step 1: Write the tests:**
  - `FilteringParentLoaderTest.blocksStockPackages`: with the delegate = the test classloader, `loadClass("de.audi.Foo")` and `loadClass("mibsdk.runtime.SdkRuntime")` throw `ClassNotFoundException`, while `loadClass("mibsdk.api.MibPlugin")` and `loadClass("java.util.List")` succeed. The allowed prefixes are exactly `Toolchain.ALLOWED_PARENT_PREFIXES` (copied into a runtime constant `RuntimeConstants.PLUGIN_PARENT_PREFIXES`).
  - `PluginJarCacheTest.reloadAfterInPlaceOverwriteLoadsNewBytes` (Review Focus 4): build jar v1 with class `p.V` whose `toString()` returns `"v1"`, snapshot it and load it; overwrite the **same path** with v2 (`"v2"`), snapshot and load with a new loader. The new instance returns `"v2"`, the old one still returns `"v1"`, and the two snapshot files differ.
  - `PluginJarCacheTest.purgeRemovesOldSnapshots`.
- [ ] **Step 2: Run them and confirm they fail.** `./gradlew :runtime:test --tests '*Loader*' --tests '*JarCache*'` — FAIL.
- [ ] **Step 3: Implement.**
  - `create` uses `new URLClassLoader(new URL[]{jarCopy.toURL()}, parent)`. `File.toURL()` exists on Foundation 1.1; `toURI()` does not.
  - Snapshot names use a per-process counter, not time.
- [ ] **Step 4: Run them and confirm they pass.** Same command — PASS.
- [ ] **Step 5: Commit.** `git commit -am "feat(runtime): filtered plugin class loading with jar snapshots"`

### Task 7: Execution and containment primitives

**Files:**
- Create: `runtime/src/main/java/mibsdk/runtime/{Clock,MonotonicClock,SerialExecutor,FailureTracker,RingLog}.java`
- Test: `runtime/src/test/java/mibsdk/runtime/{MonotonicClockTest,SerialExecutorTest,FailureTrackerTest,RingLogTest}.java`, `runtime/src/test/java/mibsdk/runtime/FakeClock.java`

**Interfaces:**
```java
public interface Clock { long uptimeMs(); }
public final class MonotonicClock implements Clock {      // ticker thread adds min(observed wall delta, 2*tickMs) per tick; negative deltas count 0
  public MonotonicClock(WallSource wall, long tickMs); public void start(); public void stop();
  public interface WallSource { long nowMs(); } }
public final class SerialExecutor {                        // one daemon thread per plugin, priority NORM-1
  public SerialExecutor(String name, Clock clock, long slowMs, long hungMs, Listener listener);
  public boolean submit(String label, Runnable task);      // false if shut down
  public void shutdown();                                  // interrupts; does not wait
  public interface Listener { void onFailure(String label, Throwable t); void onSlow(String label, long ms); void onHung(String label); } }
public final class FailureTracker { public FailureTracker(int threshold, long windowMs, Clock clock); public boolean recordFailure(); /* true → quarantine */ }
public final class RingLog { public RingLog(int capacity); public void append(String level, String source, String msg);
  public java.util.List<String> snapshot(); public void writeTo(java.io.File f) throws java.io.IOException; }   // line: "<uptimeMs> <LEVEL> <source> <msg>"
```
Defaults (fields of `RuntimeConfig`, created in Task 9): `tickMs=1000`, `slowMs=5000`, `hungMs=30000`, `quarantineFailures=3`, `quarantineWindowMs=60000`, `RingLog` capacity 2000.

- [ ] **Step 1: Write the tests:**
  - `MonotonicClockTest.wallClockJumpDoesNotAffectUptime` (Review Focus 1): a fake `WallSource` steps +1000 per tick, then jumps +1,000,000,000,000 once, then +1000 again. After 3 manual `tick()` calls (package-private for tests), `uptimeMs()` is 4000 or less; a −5000 jump adds 0.
  - `SerialExecutorTest.runsTasksInOrderOffCallerThread`.
  - `SerialExecutorTest.exceptionReportedNotPropagated`: the listener's `onFailure` is called once, and the next task still runs.
  - `SerialExecutorTest.hungTaskReportedAndExecutorKeepsAccepting`: a task blocks on a latch; with a `FakeClock` advanced past `hungMs`, `onHung` fires exactly once. Uses an injected check hook rather than sleeps.
  - `FailureTrackerTest.quarantinesOnThirdFailureWithinWindow` (two failures, advance 61 s, one more failure → `false`; then two more → `true`).
  - `RingLogTest.keepsNewestCapacityLines`.
- [ ] **Step 2: Run them and confirm they fail.** `./gradlew :runtime:test --tests '*Clock*' --tests '*Executor*' --tests '*Failure*' --tests '*RingLog*'` — FAIL.
- [ ] **Step 3: Implement** (no `java.util.concurrent`: use `Object.wait/notify` and a `LinkedList` queue).
- [ ] **Step 4: Run them and confirm they pass.** Same command — PASS.
- [ ] **Step 5: Commit.** `git commit -am "feat(runtime): monotonic clock, serial executors, failure tracking, ring log"`

### Task 8: FakePlatform, EventBus, Scheduler, Storage

**Files:**
- Create: `adapter-fake/src/main/java/mibsdk/adapter/fake/FakePlatform.java`, `adapter-fake/build.gradle.kts` (plain `java-library`, host-only, depends on api and platform)
- Create: `runtime/src/main/java/mibsdk/runtime/{EventBus,PluginScheduler,PluginStorage,Registrations}.java`
- Test: `runtime/src/test/java/mibsdk/runtime/{EventBusTest,PluginStorageTest}.java`, `adapter-fake/src/test/java/mibsdk/adapter/fake/FakePlatformTest.java`

**Interfaces:**
```java
public final class FakePlatform implements PlatformAdapter {
  public FakePlatform(String supportedVersion, java.util.Set<String> features);
  public void setIgnition(boolean on);                  // publishes IgnitionChanged via host
  public void ecuSetSoundDirections(SoundDirections v); // simulates the car changing it; publishes ParkingSoundChanged
  public SoundDirections ecuSoundDirections();          // persisted ECU value
  public java.util.List<SoundDirections> appliedLog();  // every ParkingIo.apply call
  public boolean simulateHookCall(HookPoint p, SoundDirections v); } // does what the real proxy does (isVetoed + observed); returns true if forwarded
public final class Registrations { public void add(Subscription s); public void cancelAll(); }
public final class EventBus { public Subscription subscribe(String pluginId, Class type, Listener l, SerialExecutor exec); public void publish(Object event); }
public final class PluginScheduler implements Scheduler { public PluginScheduler(SerialExecutor exec, Clock clock); /* single shared timer thread posts into exec */ }
public final class PluginStorage implements Storage { public PluginStorage(java.io.File file, Clock clock, long debounceMs); public void flushNow(); }
```

- [ ] **Step 1: Write the tests:**
  - `EventBusTest.deliversOnPluginExecutorNotPublisherThread`: the delivery thread name is the executor name.
  - `EventBusTest.subtypeAndExactTypeMatch`.
  - `EventBusTest.cancelledSubscriptionStopsDelivery`.
  - `PluginStorageTest.putIsVisibleImmediatelyAndPersistedAfterFlush`.
  - `PluginStorageTest.survivesCorruptFile`: garbage file content gives `get` returning `def` and no exception.
  - `FakePlatformTest.ecuChangePublishesEvent`.
  - `FakePlatformTest.simulatedVetoedCallNotForwarded`.
- [ ] **Step 2: Run them and confirm they fail.** `./gradlew :runtime:test --tests '*EventBus*' --tests '*Storage*' :adapter-fake:test` — FAIL.
- [ ] **Step 3: Implement** (`PluginStorage` writes `data/<id>.properties` via write-to-temp then rename; debounce defaults to 2000 ms).
- [ ] **Step 4: Run them and confirm they pass.** Same command — PASS.
- [ ] **Step 5: Commit.** `git commit -am "feat(runtime): event bus, scheduler, storage; fake platform"`

### Task 9: SdkRuntime lifecycle

**Files:**
- Create: `runtime/src/main/java/mibsdk/runtime/{SdkRuntime,PluginHost,PluginState,PluginStatus,PluginContextImpl,RuntimeConstants,RuntimeConfig}.java`
- Test: `runtime/src/test/java/mibsdk/runtime/{SdkRuntimeLifecycleTest,RuntimeConfigTest}.java`. Fixture plugin jars are built in-test into a temp `plugins/` dir.

**Interfaces:**
```java
public enum PluginState { DISCOVERED, BLOCKED_INVALID, BLOCKED_PERMISSIONS, BLOCKED_DEPENDENCY, ENABLED, FAILED, QUARANTINED, DISABLED }
public final class PluginStatus { String id(); String version(); PluginState state(); String reason(); }
public final class RuntimeConfig { public static RuntimeConfig load(java.io.File baseDir);  // reads baseDir/sdk.properties; missing file/keys → defaults
  java.io.File baseDir(); java.io.File pluginsDir(); java.io.File dataDir(); java.io.File cacheDir(); java.io.File logFile(); /* /tmp/mibsdk.log */
  long pluginStartDelayMs(); /* 30000 */ long tickMs(); /* 1000 */ long slowMs(); /* 5000 */ long hungMs(); /* 30000 */
  int quarantineFailures(); /* 3 */ long quarantineWindowMs(); /* 60000 */ int safeModeAfterUnhealthyBoots(); /* 3 */ long healthyAfterMs(); /* 60000 */
  String chainConfigurator(); /* null if absent */ java.util.List<String> hookServices(); /* default ["org.dsi.ifc.carparkingsystem.DSICarParkingSystem"] */
  long devPollMs(); /* 2000 */ long heapLowBytes(); /* 8388608 */ int heapLowChecks(); /* 3 */ long heapCheckMs(); /* 5000 */ }
public final class SdkRuntime {
  public SdkRuntime(RuntimeConfig cfg, PlatformAdapter platform, Clock clock, HookTable hooks, RingLog log);
  public void start();                  // adapter.start(host); load+enable all plugins (honours safe mode, Task 12)
  public void stop();                   // disable all (reverse order), adapter.stop()
  public void reload(String pluginId);  // disable → re-snapshot → enable
  public java.util.List<PluginStatus> statuses();
  public boolean safeMode();
  public RingLog log(); }
```
Rules:
- Discovery reads `plugins/*.jar` and parses `plugin.json`.
- Dependencies are ordered topologically. A missing, blocked or cyclic dependency gives `BLOCKED_DEPENDENCY`.
- `onEnable` runs on the plugin's `SerialExecutor`; an exception gives `FAILED` and an immediate disable.
- A callback failure counts toward `FailureTracker`; on threshold → `QUARANTINED`: cancel `Registrations`, run `onDisable`, shut down the executor.
- `onHung` → `QUARANTINED` immediately.
- Every plugin-registered `Subscription`, `Veto` and `VehicleOverride` is added to that plugin's `Registrations`.
- Every state change appends `RingLog.append("STATUS", id, STATE + " " + reason)`.
- `ctx.vehicle().parking()` throws `UnsupportedOperationException` until Task 11 supplies `ParkingImpl`.

- [ ] **Step 1: Write `SdkRuntimeLifecycleTest`** (uses `FakePlatform`, `FakeClock`, and a grants file granting all):
  - `enablesValidPluginAndDeliversEvents`: plugin logs on `IgnitionChanged`; `fake.setIgnition(true)` gives a log line `ignition=true` in `runtime.log().snapshot()`.
  - `throwInOnEnableMarksFailedOthersUnaffected`.
  - `repeatedListenerFailuresQuarantine`: 3 throws within 60 s give `QUARANTINED`, and its subscriptions no longer fire.
  - `hungCallbackQuarantines`.
  - `dependencyOrderAndMissingDependencyBlocked`.
  - `reloadSwapsCodeAndCancelsOldRegistrations`: after reload, an event is handled once, by v2 only.
  - `disableRunsOnDisableAndCancelsAll`.
  - `RuntimeConfigTest.defaultsWhenFileMissing`.
  - `RuntimeConfigTest.readsOverridesAndHookServicesList` (`hook.services=a,b` gives `[a, b]`).
- [ ] **Step 2: Run it and confirm it fails.** `./gradlew :runtime:test --tests '*SdkRuntimeLifecycleTest'` — FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run it and confirm it passes.** Same — PASS.
- [ ] **Step 5: Commit.** `git commit -am "feat(runtime): plugin lifecycle with quarantine and reload"`

### Task 10: Permissions and Hooks API

**Files:**
- Create: `runtime/src/main/java/mibsdk/runtime/{Grants,HooksImpl,HookDispatcher}.java`; modify `PluginContextImpl` and `SdkRuntime`
- Test: `runtime/src/test/java/mibsdk/runtime/{GrantsTest,HooksTest}.java`

**Interfaces:**
```java
public final class Grants { public static Grants load(java.io.File grantsProperties);   // "<pluginId>=perm1,perm2"; missing/corrupt → empty
  public java.util.Set<String> granted(String pluginId); }
public final class HookDispatcher { public HookDispatcher(HookTable table, EventBus bus); public void start(); public void stop(); } // polls HookTable, publishes HookObserved
```
Rules:
- A plugin whose manifest `permissions` aren't all granted is `BLOCKED_PERMISSIONS`, with `reason` listing the missing ones.
- `ctx.vehicle()` requires `vehicle.read`.
- `parking().overrideSoundDirections` requires `vehicle.parking.write`.
- `hooks().veto/observe` on a `PARKING_*` point require `hooks.parking`.
- A missing permission throws `PermissionDeniedException(permission)`. `ctx.granted()` reflects grants.
- A point whose feature (`Features.HOOK_PARKING`) is absent from the adapter throws `UnsupportedOperationException`.

- [ ] **Step 1: Write the tests:**
  - `GrantsTest.parsesAndTrims`, `GrantsTest.corruptFileIsEmpty`.
  - `HooksTest.vetoAppliesWithoutPluginCodeOnCallerThread`: `veto.setActive(true)` makes `fake.simulateHookCall(PARKING_SOUND_SET, v)` not forwarded, and the plugin's `observe` listener later receives `HookObserved(vetoed=true)` on its executor thread.
  - `HooksTest.releasedVetoNoLongerApplies`.
  - `HooksTest.quarantineReleasesVetos`.
  - `HooksTest.missingPermissionDenied`.
  - `GrantsTest.ungrantedPluginBlocked` (via `SdkRuntime.statuses()`).
- [ ] **Step 2: Run them and confirm they fail.** `./gradlew :runtime:test --tests '*Grants*' --tests '*HooksTest'` — FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run them and confirm they pass.** Same — PASS.
- [ ] **Step 5: Commit.** `git commit -am "feat(runtime): permissions and declarative hooks"`

### Task 11: Override ledger and Parking API

**Files:**
- Create: `runtime/src/main/java/mibsdk/runtime/{OverrideLedger,ParkingImpl,ActiveOverride}.java`; modify `SdkRuntime` (expose `java.util.List<ActiveOverride> activeOverrides()`)
- Test: `runtime/src/test/java/mibsdk/runtime/OverrideLedgerTest.java`

**Interfaces:**
```java
public final class ActiveOverride { String key(); String pluginId(); OverrideScope scope(); String activeValue(); }
public final class OverrideLedger {
  public OverrideLedger(java.io.File ledgerFile, Clock clock);
  public VehicleOverride acquire(String key, String pluginId, OverrideScope scope, String priorEncoded, String activeEncoded,
                                 Applier applier) throws OverrideConflictException;
  public void releaseAllFor(String pluginId);        // restore prior values
  public void releaseScope(OverrideScope scope);     // ignition off → UNTIL_IGNITION_OFF
  public void restorePendingOnBoot(Applier applier); // safe mode / startup: restore entries left in the file
  public void onObserved(String key, String observedEncoded); // sticky re-apply, debounce 1000 ms, max 5 per 60 s then give up + log
  public java.util.List<ActiveOverride> active();
  public interface Applier { void apply(String key, String encodedValue) throws Exception; } }
```
- Ledger file format: `<key>=<pluginId>|<scope>|<prior>|<active>`. `SoundDirections` encodes as `F1R0L0X0` (front/rear/left/right; `X` = right).
- The key for parking is `parking.soundDirections`.
- A second plugin acquiring the same key gets `OverrideConflictException`.
- The runtime calls `releaseScope(UNTIL_IGNITION_OFF)` on `IgnitionChanged(false)`, and `releaseAllFor` on disable, quarantine or revoke, and for any ledger entry whose plugin jar no longer exists at startup.

- [ ] **Step 1: Write `OverrideLedgerTest`** (with `FakePlatform` as the applier via `ParkingImpl`):
  - `overrideAppliesAndReleaseRestoresPrior`: prior `F1R1L0X0`, override `F1R0L0X0`; `fake.appliedLog()` ends with rear=false; `release()` restores `F1R1L0X0`.
  - `ignitionOffRestoresUntilIgnitionOffScopeOnly`.
  - `stickyReapplyWhenEcuChangesBack`: while active, `fake.ecuSetSoundDirections(F1R1…)` leads, after the debounce, to rear=false being applied again.
  - `stickyGivesUpAfterFiveReappliesPerMinute`.
  - `pendingEntriesRestoredOnBoot`: write a ledger file, construct a new runtime in safe mode, and the prior value is applied with the file left empty.
  - `uninstalledPluginEntryRestored`.
  - `conflictingOverrideRejected`.
- [ ] **Step 2: Run it and confirm it fails.** `./gradlew :runtime:test --tests '*OverrideLedgerTest'` — FAIL.
- [ ] **Step 3: Implement** (writes via temp+rename on every change; no wall-clock values in the file).
- [ ] **Step 4: Run it and confirm it passes.** Same — PASS.
- [ ] **Step 5: Commit.** `git commit -am "feat(runtime): override ledger with restore and sticky re-apply"`

### Task 12: Boot guard, adapter selection, config, dev channel

**Files:**
- Create: `runtime/src/main/java/mibsdk/runtime/{BootGuard,AdapterSelector,DevChannel,HeapWatch}.java`; modify `SdkRuntime.start()`
- Test: `runtime/src/test/java/mibsdk/runtime/{BootGuardTest,AdapterSelectorTest,DevChannelTest,HeapWatchTest}.java`

**Interfaces:**
```java
public final class BootGuard { public BootGuard(java.io.File bootState, java.io.File safeModeMarker, int threshold);
  public boolean beginBoot();          // increments unhealthyBoots unless lastShutdownClean; returns true → SAFE MODE
  public void markHealthy();           // unhealthyBoots=0
  public void markCleanShutdown(); }   // called on IgnitionChanged(false)
public final class AdapterSelector { public static String readHmiVersion(java.io.File versionInfo);   // value of "HMI = …" line, trimmed; null if absent
  public static PlatformAdapter select(String hmiVersion, PlatformAdapter[] candidates); }   // null if none supports it
public final class DevChannel { public DevChannel(SdkRuntime rt, java.io.File pluginsDir, java.io.File logFile, long pollMs, Clock clock);
  public void start(); public void stop(); }   // each poll: for plugins/<id>.reload → rt.reload(id), delete marker; rewrite logFile from RingLog
public final class HeapWatch { public HeapWatch(MemorySource mem, long lowBytes, int lowChecks, Victim victim);
  public void check();   // every heapCheckMs on the runtime timer; lowChecks consecutive readings < lowBytes → victim.quarantineMostRecentlyEnabled("heap low"), then reset
  public interface MemorySource { long freeBytes(); }   // prod: Runtime.getRuntime().freeMemory() + (60 MB - totalMemory())
  public interface Victim { void quarantineMostRecentlyEnabled(String reason); } }
```
`boot.state` format is properties: `unhealthyBoots=<n>`, `lastShutdownClean=<true|false>`.

`SdkRuntime.start()` order:
1. `BootGuard.beginBoot()`. If safe mode, or the `SAFE_MODE` marker exists → restore the ledger, start `DevChannel`, load no plugins, and set `safeMode()=true` with the reason logged.
2. Otherwise, after `pluginStartDelayMs`, load plugins.
3. After `healthyAfterMs` of uptime with plugins enabled → `markHealthy()`.

It always logs `STATUS mibsdk runtime started adapter=<id|none> safeMode=<bool>`. With no adapter, the line is `adapter=none` and the runtime stops after logging.

- [ ] **Step 1: Write the tests:**
  - `BootGuardTest.threeUncleanBootsEnterSafeMode`.
  - `BootGuardTest.healthyResetsCounter`.
  - `BootGuardTest.ignitionOffCountsAsCleanShutdown` (Review Focus 3): repeat boot → `markCleanShutdown()` three times, and the fourth `beginBoot()` is false.
  - `BootGuardTest.safeModeMarkerForces`.
  - `AdapterSelectorTest.readsHmiLine`: a fixture copied from the bench `version_info.txt` lines, including `HMI = HMI_AU_MIB2_HQC_NAR_G22_C10_180803`, returns exactly that string.
  - `AdapterSelectorTest.noMatchReturnsNull`.
  - `DevChannelTest.reloadMarkerTriggersReloadAndIsDeleted`.
  - `DevChannelTest.logFileRewritten`.
  - `HeapWatchTest.quarantinesMostRecentAfterConsecutiveLowReadings`: readings 9, 7, 7, 7 MB with `lowBytes` = 8 MB and `lowChecks` = 3 give exactly one victim call. A reading of 7, 7, 9, 7 gives none.
- [ ] **Step 2: Run them and confirm they fail.** `./gradlew :runtime:test --tests '*BootGuard*' --tests '*AdapterSelector*' --tests '*DevChannel*' --tests '*HeapWatch*'` — FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run them and confirm they pass**, then run the full `./gradlew :runtime:check` — PASS (`linkCheck` with transitive Retrotranslator runtime included).
- [ ] **Step 5: Commit.** `git commit -am "feat(runtime): boot guard, safe mode, adapter selection, dev channel"`

### Task 13: Plugin build convention, hello and crashy plugins

**Files:**
- Create: `build-logic/src/main/java/mibsdk/build/{MibPluginPlugin,KotlinShrinkTask}.java`
- Create: `plugins/hello/{build.gradle.kts,src/main/resources/plugin.json,src/main/java/sample/hello/HelloPlugin.java}`
- Create: `plugins/crashy/{build.gradle.kts,src/main/resources/plugin.json,src/main/java/sample/crashy/CrashyPlugin.java}`
- Test: `build-logic/src/test/java/mibsdk/build/MibPluginPluginFunctionalTest.java` (Gradle TestKit)

**Interfaces:**
- Gradle plugin id `mibsdk.plugin`. For Java projects: compile (JDK 17 `--release 8` + Jabel, `compileOnly` api) → `backport` → `linkCheck` (classpath = carjcl + api/compat/platform car jars; transitive = `mibsdk-rt-retrotranslator.jar`) → `permissionScan` (fails unless `plugin.json` lists `platform.unrestricted`) → `mibPluginJar` = `build/mibsdk/<id>.jar` with `plugin.json` at the root.
- If `org.jetbrains.kotlin.jvm` is applied, it sets `Toolchain.KOTLIN_FLAGS` and `jvmTarget=1.8` and inserts `KotlinShrinkTask` (keep rule `-keep class <main> { *; }` plus the Global Constraints ProGuard lines) before `backport`.
- `HelloPlugin`:
  - `onEnable` logs `hello enabled`;
  - subscribes to `IgnitionChanged` and logs `ignition=<on>`;
  - counts enables in `storage` key `enables`;
  - permissions `["vehicle.read"]`.
- `CrashyPlugin`, with permissions `[]`, chooses its mode from storage key `mode`, written by a test or by hand:
  - `throw-on-enable`;
  - `throw-on-event` (throws on every `IgnitionChanged`);
  - `hang-on-event` (sleeps forever in the listener);
  - `busy-loop` (spins on its own scheduler task).

- [ ] **Step 1: Write the TestKit test:**
  - `javaPluginProducesV48JarWithManifest`: run `:plugins:hello:mibPluginJar`; every class in the jar is major 48, `plugin.json` is present, and the build succeeds.
  - `reflectionWithoutUnrestrictedFailsBuild`: a temp plugin calling `Class.forName` in a normal method fails with output containing `platform.unrestricted`.
  - `kotlinPluginShrinksAndPasses`: a temp Kotlin plugin using `listOf(1,2).map{…}` builds, and the jar has no class with `$i$a$` in a LocalVariableTable.
- [ ] **Step 2: Run it and confirm it fails.** `./gradlew -p build-logic test --tests '*MibPluginPluginFunctionalTest'` — FAIL.
- [ ] **Step 3: Implement the plugin convention and the two plugins.**
- [ ] **Step 4: Run it and confirm it passes**, plus `./gradlew :plugins:hello:mibPluginJar :plugins:crashy:mibPluginJar` — PASS.
- [ ] **Step 5: Add `SdkRuntimeLifecycleTest.crashyModesAreContained`.** It loads the real `crashy` jar from `plugins/crashy/build/mibsdk/crashy.jar` with each mode against `FakePlatform`, and `hello` keeps receiving events in every mode. Run it — PASS.
- [ ] **Step 6: Commit.** `git commit -am "feat(build): mibsdk.plugin convention; hello and crashy plugins"`

### Task 14: HQC adapter — chaining configurator and DSI proxy

**Files:**
- Create: `adapter-hqc/build.gradle.kts`: car-library; compileOnly + carClasspath = `mibsdk.stockLsdJar`, `mibsdk.stockDsiTracerJar`, compat/api/platform/runtime.
- Create: `adapter-hqc/src/main/java/mibsdk/adapter/hqc/{SdkGlobals,ChainingConfigurator,DsiServiceProxy,HookBindings,DsiConstants,SdkFiles}.java`
- Test: `adapter-hqc/src/test/java/mibsdk/adapter/hqc/{DsiServiceProxyTest,ChainingConfiguratorTest,NoStockConstantsTest}.java`. On the host, `org.dsi.ifc.*` interfaces come from the stock jar. Fake service objects implement `DSICarParkingSystem` through `java.lang.reflect.Proxy` in the tests.

**Interfaces:**
```java
public final class SdkGlobals { public static final HookTable HOOKS = new HookTable(256); public static volatile boolean wrappingEnabled;
  public static final RingLog LOG = new RingLog(2000); }   // exists before the runtime; the configurator logs "wrapped <iface> chain=<TraceConfigurator|none>"
public final class SdkFiles { public static final String BASE = "/mnt/app/eso/hmi/lsd/mibsdk"; public static final String VERSION_INFO = "/ifs/version_info.txt"; }
public final class ChainingConfigurator implements de.dreisoft.lsd.IServiceDelegateConfigurator {
  public ChainingConfigurator();                       // used by LSD
  ChainingConfigurator(java.io.File baseDir, java.io.File versionInfo);   // tests
  public void initConfigurator() throws Exception;     // never throws in practice: catch-all
  public org.osgi.framework.ServiceRegistration getServiceRegistration(de.dreisoft.lsd.IServiceDelegator d, String[] names, Object service, java.util.Dictionary props); }
final class DsiServiceProxy implements java.lang.reflect.InvocationHandler {
  static Object wrap(Object service, Class[] interfaces, HookTable hooks, HookBindings bindings);  // returns service itself on any failure
  static Object wrapListener(Object listener, HookTable hooks, HookBindings bindings); }   // used for setNotification/clearNotification args, identity-mapped
final class HookBindings { static HookBindings defaults();   // DSICarParkingSystem.setPDCSoundReproduction → PARKING_SOUND_SET; listener updatePDCSoundReproduction → PARKING_SOUND_UPDATE
  HookPoint pointFor(String iface, String method); Object toApiValue(HookPoint p, Object[] args); }   // PDCSoundReproduction → SoundDirections via its getters, reflectively
final class DsiConstants { static int get(String className, String field); }   // reflective, cached; -1 + log on failure
```
`initConfigurator`:
1. Set `wrappingEnabled = (hmiVersion equals the supported string) && !SAFE_MODE marker && boot.state unhealthyBoots < 3`.
2. If `sdk.properties` has `chain.configurator`, instantiate it reflectively and call its `initConfigurator()`. If that throws, drop the chain and log; TraceConfigurator throws `IllegalStateException` when deactivated.

`getServiceRegistration`:
1. If `wrappingEnabled`, `props` lacks `mibsdk.proxy`, and any name is in `hookServices`, build `wrapped = DsiServiceProxy.wrap(service, <all interfaces of service's class hierarchy>, …)` and add `mibsdk.proxy=true` to a **copy** of the props.
2. Then: if there's a chain, return `chain.getServiceRegistration(d, names, wrapped, props')`, falling back to `d.createServiceRegistration(names, wrapped, props')` when that returns null.
3. Otherwise return null if nothing was wrapped, else `d.createServiceRegistration(names, wrapped, props')`.
4. Any `Throwable` → delegate to the chain with the original object, or return null.

`DsiServiceProxy.invoke`:
- For `setNotification`/`clearNotification`, replace each `DSIListener` arg with its wrapped listener.
- For a bound point, compute `vetoed = hooks.isVetoed(p)` and `hooks.observed(p, toApiValue, vetoed)`.
- If vetoed and the return type is `void`, return without forwarding; otherwise forward via `Method.invoke`, unwrapping `InvocationTargetException`.

- [ ] **Step 1: Write the tests:**
  - `DsiServiceProxyTest.forwardsWithNoRuntimeAttached` (Review Focus 2): a wrapped fake `DSICarParkingSystem` with a fresh `HookTable` and nobody polling; 1,000 `setPDCSoundReproduction` calls all reach the target, and `observed` drops oldest without throwing.
  - `DsiServiceProxyTest.vetoBlocksVoidCall`.
  - `DsiServiceProxyTest.listenerWrappedAndUpdatesObserved`: `setNotification(listener)` passes a proxy; calling the proxy's `updatePDCSoundReproduction(new PDCSoundReproduction(true,false,false,false), 1)` reaches the original listener and produces `HookObserved(PARKING_SOUND_UPDATE, SoundDirections(F1R0…))`.
  - `DsiServiceProxyTest.clearNotificationUsesSameWrapper`.
  - `DsiServiceProxyTest.targetExceptionPropagatesUnwrapped`.
  - `ChainingConfiguratorTest.wrapsAllowListedAndChains`: a fake chain configurator records that it received the **wrapped** object and props containing `mibsdk.proxy`.
  - `ChainingConfiguratorTest.passthroughWhenVersionMismatch` and `…WhenSafeModeMarker`.
  - `ChainingConfiguratorTest.chainThatThrowsOnInitIsDropped`.
  - `ChainingConfiguratorTest.neverThrows`: a `null` service and a `null` names array give no exception.
  - `NoStockConstantsTest.adapterSourceHasNoStockConstantRefs`: scan `adapter-hqc/src/main/java/**/*.java` for the regex `\b[A-Z]\w*\.(ATTR|RT|ATTRVALIDFLAG)_[A-Z0-9_]+` and assert zero matches.
- [ ] **Step 2: Run them and confirm they fail.** `./gradlew :adapter-hqc:test` — FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run them and confirm they pass**, plus `./gradlew :adapter-hqc:check` — PASS (linkCheck against carjcl + stock jars).
- [ ] **Step 5: Commit.** `git commit -am "feat(adapter-hqc): chaining delegate configurator and DSI proxy hooks"`

### Task 15: HQC adapter — platform, bridges, bundle activator

**Files:**
- Create: `adapter-hqc/src/main/java/mibsdk/adapter/hqc/{HqcPlatform,ParkingBridge,IgnitionBridge,ServiceWaiter,SdkBundleActivator}.java`
- Test: `adapter-hqc/src/test/java/mibsdk/adapter/hqc/{ParkingBridgeTest,IgnitionBridgeTest,HqcPlatformTest}.java` (fake `BundleContext` via `java.lang.reflect.Proxy`)

**Interfaces:**
```java
public final class HqcPlatform implements PlatformAdapter {
  public static final String SUPPORTED = "HMI_AU_MIB2_HQC_NAR_G22_C10_180803";
  public HqcPlatform(org.osgi.framework.BundleContext ctx);  // id() = "hqc-nar-g22-c10"
  // features(): Features.PARKING, Features.IGNITION, Features.HOOK_PARKING
}
final class ServiceWaiter { ServiceWaiter(BundleContext ctx, Clock clock);
  Object await(String ifaceName, long retryMs, Callback onAvailable); }   // polls getServiceReferences on an SDK thread: 200 ms ×50, then every 2000 ms; never on caller thread
final class ParkingBridge implements ParkingIo {   // own listener = java.lang.reflect.Proxy implementing DSICarParkingSystemListener; registered with service.setNotification(listener)
  // updatePDCSoundReproduction(v, flag) with flag == DsiConstants.get("org.dsi.ifc.base.DSIListener","ATTRVALIDFLAG_VALID") → cache + host.publish(ParkingSoundChanged)
  // updatePDCTrailerHitched(b, flag) → host.publish(TrailerHitchedChanged); all other methods no-op
  // apply(v) → service.setPDCSoundReproduction(new PDCSoundReproduction(front, rear, right, left))   // NOTE DSI ctor order: front, rear, right, left
}
final class IgnitionBridge { /* DSIPowerManagement listener; updateClampSignal(cs, flag) → IgnitionChanged(cs.isClamp15()) on change only */ }
public final class SdkBundleActivator implements org.osgi.framework.BundleActivator {
  public void start(BundleContext ctx);  // returns in <50 ms: spawns daemon thread "mibsdk-main" that builds RuntimeConfig, selects adapter (AdapterSelector over {new HqcPlatform(ctx)}), builds MonotonicClock + SdkRuntime(cfg, adapter, clock, SdkGlobals.HOOKS, SdkGlobals.LOG) and calls start(); all Throwables logged to RingLog/stderr, never rethrown
  public void stop(BundleContext ctx); }
```
The bridges obtain the real services through `ServiceWaiter` using `BundleContext.getServiceReferences(<iface>, null)`. The reference returned may be the SDK proxy; that's fine.

- [ ] **Step 1: Write the tests:**
  - `ParkingBridgeTest.applyUsesDsiArgumentOrder`: `apply(SoundDirections(front=true, rear=false, left=true, right=false))` makes the fake service receive `PDCSoundReproduction{front=true, rear=false, right=false, left=true}`.
  - `ParkingBridgeTest.invalidFlagIgnored`.
  - `ParkingBridgeTest.validUpdatePublishesEvent`.
  - `IgnitionBridgeTest.publishesOnlyOnChange`.
  - `HqcPlatformTest.supportsOnlyExactVersion`: `"HMI_AU_MIB2_HQC_NAR_G22_C10_180803"` is true; the same string with a trailing space trimmed by the selector is true; `"…_180804"` is false.
  - `HqcPlatformTest.activatorStartReturnsQuickly`: `start()` with a context whose `getServiceReferences` blocks for 5 s returns in under 100 ms.
- [ ] **Step 2: Run them and confirm they fail.** `./gradlew :adapter-hqc:test` — FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run them and confirm they pass**, plus `./gradlew :adapter-hqc:check` — PASS.
- [ ] **Step 5: Commit.** `git commit -am "feat(adapter-hqc): platform, parking/ignition bridges, bundle activator"`

### Task 16: Distribution, installer generator, dev tool

**Files:**
- Create: `dist/build.gradle.kts`. Task `assembleUnit` → `build/dist/{mibsdk-runtime.jar (compat+api+platform+runtime v48), mibsdk-adapter-hqc.jar, mibsdk-rt-retrotranslator.jar}`, then runs LinkCheck over the three jars together against carjcl + stock jars.
- Create: `installer/gen_bundles.py`, `installer/test_gen_bundles.py`, `installer/mibsdk-dev` (bash, executable)

**Interfaces:**
- `gen_bundles.py generate --stock <stock.properties> --tracer-active <true|false> --out <file>` produces the stock file with exactly these changes:
  - the line `de.audi.tghu.serviceDelegateConfigurator=…` (commented or not) becomes `de.audi.tghu.serviceDelegateConfigurator=mibsdk.adapter.hqc.ChainingConfigurator`;
  - `Bundle.Startup.Autostart` becomes `DSITracer,MibSdk,<stock rest without DSITracer>` if tracer-active, else `MibSdk,<stock rest without DSITracer>`;
  - one line `MibSdk=mibsdk.adapter.hqc.SdkBundleActivator` is appended;
  - every other line is byte-identical.
- `gen_bundles.py sdkprops --tracer-active <bool> --out <file>` writes `chain.configurator=de.audi.tghu.dsitrace.TraceConfigurator` (only if tracer-active) and `hook.services=org.dsi.ifc.carparkingsystem.DSICarParkingSystem`.
- `mibsdk-dev <cmd> [host]` (host defaults to `10.20.0.23`; uses the Global Constraints SSH options):
  - `install`:
    1. Fetch `/ifs/version_info.txt`, `lsd/bundles_dsitracer_enabled.properties` and marker state.
    2. **Refuse** if the version ≠ supported, if `lsd/jars/` contains a name matching `*carplay*`, if any `*.carplay-stock` file exists under `/mnt/app/eso/hmi`, or if `lsd/DSITracer.jar` is missing.
    3. Back up the stock bundles file and the marker state into `mibsdk/stock/` (first install only).
    4. Generate the files locally, push the jars, bundles file and `sdk.properties`, and ensure `dsitracer_activated` exists.
    5. Print `installed; restart HMI with: mibsdk-dev restart`.
  - `uninstall`: restore the bundles file from `stock/`, remove the marker if it was absent before, remove the three jars; `--purge` also removes `mibsdk/`.
  - `restart`: `slay -s11 j9`.
  - `push <plugin.jar>`: copy it to `plugins/<id>.jar` (id read from the jar's `plugin.json`) and touch `plugins/<id>.reload`.
  - `grant <id> <perm,…>`: rewrite that line of `grants.properties`.
  - `safe-mode on|off`: touch or remove `mibsdk/SAFE_MODE`.
  - `log`: print `/tmp/mibsdk.log`.
  - `status`: print the log lines containing ` STATUS `.

  Files are pushed with `ssh … "cat > path" < file`. No `scp`.

- [ ] **Step 1: Write `test_gen_bundles.py`** (`python3 -m unittest`), using a fixture copy of the stock `bundles_dsitracer_enabled.properties` from `~/projects/audi-mib2/firmware/mmxfs/app/eso/hmi/lsd/` placed in `installer/testdata/` (commit it; it contains no personal data):
  - `test_tracer_active`: autostart becomes `DSITracer,MibSdk,FwUtilServices,Profiling,Tracing,ATIPImpl` and the configurator line is replaced.
  - `test_tracer_inactive`: autostart is `MibSdk,FwUtilServices,Profiling,Tracing,ATIPImpl`.
  - `test_other_lines_unchanged`: the diff touches only those 2 lines plus 1 appended line.
  - `test_sdkprops_without_chain`.
  - `test_idempotent_on_already_generated`: running it on its own output gives no duplicate `MibSdk=` and no duplicate autostart entry.
- [ ] **Step 2: Run it and confirm it fails.** `python3 -m unittest installer/test_gen_bundles.py` — FAIL.
- [ ] **Step 3: Implement** `gen_bundles.py`, `mibsdk-dev` and `:dist:assembleUnit`.
- [ ] **Step 4: Run the checks and confirm they pass:** the same unittest command, plus `./gradlew :dist:assembleUnit`. Expected: PASS, with the three jars in `dist/build/dist/` and the combined LinkCheck reporting 0 missing.
- [ ] **Step 5: Commit.** `git commit -am "feat(dist): unit assembly, bundles generator, mibsdk-dev tool"`

### Task 17: Bench end-to-end

**Files:**
- Create: `docs/bench-e2e.md`, a checklist with the results recorded, using the paths and commands below.
- Modify: whatever the run exposes (fix-forward with a failing host test first, per the earlier tasks' style).

**Interfaces:** consumes `mibsdk-dev`, the dist jars, and the hello/crashy plugin jars.

- [ ] **Step 1: Baseline.**
  - `curl -s 10.20.0.23:8099/status` gives `{"bridge_connected":true}`.
  - Save a `GET /screen.jpg` (poll twice, about 2 s apart).
- [ ] **Step 2: Install and restart.**
  - `installer/mibsdk-dev install && installer/mibsdk-dev grant hello vehicle.read && installer/mibsdk-dev push plugins/hello/build/mibsdk/hello.jar && installer/mibsdk-dev restart`.
  - Wait for the HMI: poll `pidin -p j9` over SSH, then 60 s.
  - Expected: `mibsdk-dev log` shows `STATUS mibsdk runtime started adapter=hqc-nar-g22-c10 safeMode=false`. After 30 s it also shows `hello enabled` and `STATUS hello ENABLED`.
- [ ] **Step 3: Check the stock HMI and the tracer chain are intact.**
  - The keypanel `/status` is still `bridge_connected:true`.
  - `/inject {"control":"car","action":"tap"}` then `/screen.jpg` shows the Car menu.
  - `back` returns home.
  - `mibsdk-dev log` contains `wrapped org.dsi.ifc.carparkingsystem.DSICarParkingSystem chain=TraceConfigurator`.
- [ ] **Step 4: Hot reload.**
  - Change the hello log text to `hello enabled v2`, rebuild and `mibsdk-dev push`.
  - Within 5 s the log shows `hello enabled v2`; the HMI j9 PID is unchanged.
- [ ] **Step 5: Forced failure.**
  - Push crashy and grant it nothing.
  - For each mode: set it via `ssh … "echo mode=<m> > /mnt/app/eso/hmi/lsd/mibsdk/data/crashy.properties"`, touch the reload marker, wait 40 s.
  - Expected: `STATUS crashy FAILED` or `QUARANTINED` as appropriate; the HMI stays responsive (a keypanel `car` tap changes the screen); hello still logs; the j9 PID is unchanged.
- [ ] **Step 6: Safe mode.**
  - `mibsdk-dev safe-mode on && mibsdk-dev restart`.
  - Log shows `safeMode=true` and no plugin enabled; keypanel works.
  - `safe-mode off && restart` restores normal mode.
- [ ] **Step 7: Uninstall.**
  - `mibsdk-dev uninstall && mibsdk-dev restart`.
  - `lsd/bundles_dsitracer_enabled.properties` is byte-identical to `mibsdk/stock/…`; check with `cat` on both, compared locally via `cmp`.
  - The `dsitracer_activated` marker state matches the backup; the keypanel works.
- [ ] **Step 8: Reinstall** so the bench stays on the SDK for Plan 2. Commit the `docs/bench-e2e.md` results.
  `git commit -am "test(e2e): bench end-to-end results"`

### Task 18: bike-rack plugin (Kotlin)

**Files:**
- Create: `plugins/bike-rack/{build.gradle.kts,src/main/resources/plugin.json,src/main/kotlin/sample/bikerack/BikeRackPlugin.kt}`
- Test: `plugins/bike-rack/src/test/kotlin/sample/bikerack/BikeRackPluginTest.kt` (host, JUnit 5, runtime + FakePlatform as `testImplementation`)

**Interfaces:**
- Manifest: `{"id":"bike-rack","name":"Bike Rack","version":"1.0.0","author":"complex256","api":1,"main":"sample.bikerack.BikeRackPlugin","permissions":["vehicle.read","vehicle.parking.write"]}`.
- Behaviour:
  - The toggle state is storage key `active` (`"true"`/`"false"`). Plan 1 has no UI; the toggle is driven by storage plus reload, and Plan 2 adds the menu row.
  - On enable, if `active` is true → `parking().overrideSoundDirections(current.withRear(false), OverrideScope.UNTIL_IGNITION_OFF)`.
  - On `IgnitionChanged(false)`, set `active=false`. The ledger restores the sounds itself.
  - If `parking().soundDirections()` is null at enable, subscribe to `ParkingSoundChanged` and apply on the first value.

- [ ] **Step 1: Write `BikeRackPluginTest`:**
  - `activeOnEnableMutesRearOnly`: ECU `F1R1L1X1` gives an applied `F1R0L1X1`.
  - `inactiveDoesNothing`.
  - `ignitionOffRestoresAndClearsToggle`: the applied log ends `F1R1L1X1` and storage `active=false`.
  - `waitsForFirstValueWhenUnknown`.
  - `ecuFlipsBackGetsReapplied` (via the ledger's sticky re-apply).
  - `disablingPluginRestores`.
- [ ] **Step 2: Run it and confirm it fails.** `./gradlew :plugins:bike-rack:test` — FAIL.
- [ ] **Step 3: Implement the plugin.**
- [ ] **Step 4: Run it and confirm it passes**, plus `./gradlew :plugins:bike-rack:mibPluginJar`. Expected: PASS; the jar is v48 and `linkCheck` reports 0 missing.
- [ ] **Step 5: Bench smoke**, the call path only, since the bench has no parking ECU.
  - `mibsdk-dev grant bike-rack vehicle.read,vehicle.parking.write`, push the jar, set storage `active=true`, reload.
  - Expected log lines: `STATUS bike-rack ENABLED`, then either `waiting for parking value` (no ECU), or an override applied plus a `HookObserved PARKING_SOUND_SET` line from the proxy.
  - Record which in `docs/bench-e2e.md`. Real PDC behaviour is verified in Plan 4.
- [ ] **Step 6: Commit.** `git commit -am "feat(plugins): bike-rack (Kotlin) on the override ledger"`
