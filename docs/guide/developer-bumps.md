# Developer bumps

These are the rough edges a newcomer hits, written down while writing [Writing your first plugin](first-plugin.md)
and building its `coffee-timer` plugin from the docs alone. The plugin was built in a fresh clone with an empty
Gradle user home, then tested and packaged on the host. Nothing here was run on a unit.

The SDK's code mostly behaved well. The plugin compiled, passed `linkCheck` and `permissionScan`, and passed its
six host tests on the first try. Most of the friction is **what you have to know before you start**: the stock jar,
working inside the repo, the copied test harness, and limits that live only in runtime code. The second biggest
source is **failures that pass the build and surface only at runtime**.

Severity:

- **blocker**: an outside developer cannot proceed without help;
- **annoying**: costs real time or causes a wrong turn;
- **nit**: polish.

The list is ordered by impact. The tutorial step a bump affects is given as §N.

## Summary

| # | Severity | Bump |
|---|---|---|
| 1 | blocker | Every plugin build needs Audi's stock `lsd.jar` |
| 2 | blocker | A plugin can only be built as a subproject of the SDK repo |
| 3 | blocker (by design) | One exact firmware build is supported |
| 4 | annoying | Path defaults are the owner's machine layout; a missing path gives a generic Gradle error |
| 5 | annoying | Permissions are not checked at build; the runtime error says "not granted" when the cause is "not declared" |
| 6 | annoying | Every host test copies about 80 lines of harness, plus the build-script wiring |
| 7 | annoying | The dev loop (`mibsdk-dev`) is wired to the owner's bench unit |
| 8 | annoying | A `linkCheck` failure does not say where the reference is, and shows rewritten class names |
| 9 | annoying | The api-level scan only knows api 4 and 5 members |
| 10 | annoying | Count and icon-size limits live in runtime code, are enforced only at runtime, and are not in the API |
| 11 | annoying | Javadoc gaps, the threading guarantee among them |
| 12 | annoying | No scaffolding: five files and a `settings.gradle.kts` edit by hand |
| 13 | annoying | Permissions are all or nothing |
| 14 | nit | `onDisable` runs after a failed `onEnable`, which the Javadoc does not say |
| 15 | nit | Records fail with Jabel's `Must be annotated with @Desugar` |
| 16 | nit | `compat` has 5 of the `java.util.function` interfaces |
| 17 | nit | The spec says there is no `String.format`, and the build accepts it |
| 18 | nit | Surprising UI behaviour you only learn from Javadoc or code |
| 19 | nit | Choice sub-page keys are undocumented, and `FakeUi` has no helper for them |
| 20 | nit | Events need no permission, though `vehicle.read`'s approval text says it covers ignition |
| 21 | nit | Task names are inconsistent: `mibPackage` against `mibpkgPackage` |
| 22 | nit | The build and the runtime disagree on manifest rules |
| 23 | nit | `mibsdk-dev` says mibpkg 0.8.0 is enough |
| 24 | nit | Missing samples and fake-car gaps |
| 25 | nit | Build output noise and a Gradle 9 deprecation warning |

---

## 1. Every plugin build needs Audi's stock `lsd.jar` (blocker)

**Status:** fixed in Task 2. `carJcl` writes the bundled `carjcl.sigs` list when `mibsdk.stockLsdJar` is not set, so a
plugin build needs no stock jar. The description below is the problem as found.

- **Where:** §1.1. `CarLibraryPlugin.registerCarJcl` (`build-logic/src/main/java/mibsdk/build/CarLibraryPlugin.java:181`)
  registers one root `carJcl` task, which extracts the car's class library from `lsd.jar`. Every module's
  `linkCheck`, a plugin's included, depends on it (`CarLibraryPlugin.java:101-110`). The path comes from
  `Toolchain.stockLsdJar` (`build-logic/src/main/java/mibsdk/build/Toolchain.java:86`).
- **What happens:** you cannot build any plugin, not even `hello`, without a 131 MB jar converted from your own
  unit's `lsd.jxe` with jxe2jar. The repo cannot ship it, and the README can only point at the research notes for
  how to make it. In practice, the SDK owner is the only person who can build a plugin today.
- **Suggested fix:** commit what `linkCheck` actually needs instead of the jar: a generated **signature list** of
  the car JCL (class names, supertypes, member names and descriptors; no code). Or publish that list as a small
  artifact. Make `carJcl` read it when `mibsdk.stockLsdJar` is unset. Keep the full jar as an option for SDK
  (`dist`, `adapter-hqc`) builds only.

## 2. A plugin can only be built as a subproject of the SDK repo (blocker)

**Status:** fixed in Task 5. Without an `:api` project in the build, `mibsdk.plugin` takes `api` (`compileOnly`), the `car`
classifier jars of `api` and `compat` (for `linkCheck`) and `compat` (for Retrotranslator) by coordinate from the
repository the build's `settings.gradle.kts` names. `templates/plugin` is a ready stand-alone project, and
`./gradlew standalonePluginCheck` builds it outside the repo. The artifacts are published to `build/repo` by
`./gradlew publishLocal` (Task 4); no hosted repository exists yet. The description below is the problem as found.

- **Where:** §1.2 and §1.4. `MibPluginPlugin.apply` (`build-logic/src/main/java/mibsdk/build/MibPluginPlugin.java:52-61`)
  calls `project.evaluationDependsOn(":compat")`, `project(":api")` and `project.project(":compat")`.
  `build-logic` is an included build, not a published plugin.
- **What happens:** you clone the whole SDK, add your plugin under `plugins/` and edit the SDK's
  `settings.gradle.kts`. Your plugin's history then lives inside a fork of the SDK. Every `./gradlew` run configures
  the SDK, the adapter and all the samples. A stand-alone repository with `plugins { id("mibsdk.plugin") version "..." }`
  is not possible.
- **Suggested fix:**
  - Publish `api-car.jar` and `compat-car.jar` (with sources and Javadoc), the Retrotranslator runtime coordinate
    and the `mibsdk.plugin` Gradle plugin, to a Maven repo or as GitHub release assets.
  - Let the convention take the api jar from a dependency coordinate when there is no `:api` project.
  - Add a template repository.

## 3. One exact firmware build is supported (blocker, by design)

- **Where:** README "Supported units and firmware". `HqcVersion.SUPPORTED`
  (`adapter-hqc/src/main/java/mibsdk/adapter/hqc/HqcVersion.java:7`) and `MpkStage.HMI`
  (`build-logic/src/main/java/mibsdk/build/MpkStage.java:17`).
- **What happens:** the SDK runs only on `HMI_AU_MIB2_HQC_NAR_G22_C10_180803`. Anyone with another MHI2Q build gets
  "the SDK stays off". This is a deliberate v1 decision (spec G3), but it is the first question every newcomer
  asks, and nothing tells them how far their firmware is from supported.
- **Suggested fix:**
  - Keep the strictness.
  - Ship a host tool (or a `mibpkg sdk status` line) that reads a unit's `version_info.txt` and says "supported" or
    "not supported: <build>".
  - Document what a second adapter would take.

## 4. Path defaults are the owner's machine layout, and a missing path gives a generic error (annoying)

**Status:** fixed in Task 3. `Toolchain` has no path defaults. The JDK 8 comes from a Gradle toolchain
(`-Pmibsdk.jdk8Home` overrides it), and a stock jar that is needed but unset or missing fails with `mibsdk:
<property> is not set` (or `points at <path>, which does not exist`), what the file is, and a link to the README
toolchain table. The description below is the problem as found.

- **Where:** §1.1. `Toolchain.jdk8Home` / `stockLsdJar` / `stockDsiTracerJar`
  (`build-logic/src/main/java/mibsdk/build/Toolchain.java:80-95`) default to
  `Library/Java/JavaVirtualMachines/corretto-1.8.0_372/Contents/Home` and `projects/audi-mib2/...` under the home
  directory.
- **What happens:**
  - A wrong `lsd.jar` path fails as Gradle's input-validation problem, `Type 'mibsdk.build.CarJclTask' property
    'stockLsdJar' specifies file '...' which doesn't exist`, followed by Gradle's generic "possible solutions". It
    does not name the `mibsdk.stockLsdJar` property or say what `lsd.jar` is.
  - A wrong JDK 8 path fails in `:compat:compileJava` with `The configured executable does not exist
    (.../bin/javac)`.

  By contrast, the `adapter-hqc` script has a good message (`adapter-hqc/build.gradle.kts:17-18`), and so does the
  missing-mibpkg error.
- **Suggested fix:** drop the personal defaults. When a property is unset or its file is missing, fail early, at
  configuration or in a `doFirst`, with one message that names the property, says what the file is, and links to
  the README toolchain table. Also find the JDK 8 through a Gradle toolchain (`JavaLanguageVersion.of(8)`) rather
  than a home path.

## 5. Permissions are not checked at build, and the runtime error misleads (annoying)

**Status:** fixed in Task 8. `PluginJson.read` rejects an unknown permission with a "did you mean" hint,
`permissionScan` fails the build when the code calls API whose permission `plugin.json` does not declare (also under
`platform.unrestricted`), and the runtime says `permission not declared in plugin.json: <p>` for an undeclared
permission and keeps `permission not granted: <p>` for a declared but ungranted one. The description below is the
problem as found.

- **Where:** §2. `PluginJson.read` copies `"permissions"` without checking them
  (`build-logic/src/main/java/mibsdk/build/PluginJson.java:101-110`). The runtime check is `UiImpl.requirePermission`
  (`runtime/src/main/java/mibsdk/runtime/UiImpl.java:480`) and `PluginContextImpl.requirePermission`
  (`runtime/src/main/java/mibsdk/runtime/PluginContextImpl.java:284`).
- **What happens:**
  - `"permissions": ["ui.slot.statusbr"]` (a typo) builds **and packages** with `mibPackage`.
  - A plugin that calls `carMenuRow` without declaring `ui.slot.carmenu` builds too. At runtime `onEnable` throws,
    and the plugin is `FAILED mibsdk.api.PermissionDeniedException: permission not granted: ui.slot.carmenu`.
    The author's instinct is to go and grant it, but the fix is in `plugin.json`: a granted but undeclared
    permission is never usable (`PluginContextImpl.java:33-37`).
- **Suggested fix:**
  - Validate `"permissions"` against `api/permissions.json` in `PluginJson.read`, with a "did you mean" hint.
  - Teach `permissionScan` the API-to-permission map, the way it already knows api levels:
    - `Ui.carMenuRow` → `ui.slot.carmenu`;
    - `Ui.statusItem` and `Ui.toast` → `ui.slot.statusbar`;
    - `PluginContext.net` → `network`;
    - `PluginContext.vehicle` → `vehicle.read`;
    - `Parking.overrideSoundDirections` → `vehicle.parking.write`;
    - `PluginContext.hooks` → `hooks.parking`.

    Fail when a used permission is undeclared.
  - Make the runtime message say `not declared in plugin.json` or `declared but not granted`, whichever it is.

## 6. Every host test copies about 80 lines of harness (annoying)

**Status:** fixed in Task 6 and Task 7. Task 6: `plugin-test` publishes `mibsdk.test.PluginTestHarness`, and the three
samples' tests use it, so none of them imports `mibsdk.runtime.*` or `mibsdk.platform.*` any more. Task 7: `mibsdk.plugin`
adds `plugin-test` and JUnit to every plugin's tests and sets `-Dmibsdk.pluginJar` and `-Dmibsdk.pluginIcons` on every
`Test` task, in the SDK repository and in a stand-alone build, so a plugin's `build.gradle.kts` is only the `plugins`
block (`standalonePluginCheck` runs the template's own test). The description below is the problem as found.

- **Where:** §6. Compare `plugins/hello-menu/src/test/java/sample/hellomenu/HelloMenuPluginTest.java:50-113`,
  `plugins/inputs-demo/src/test/java/sample/inputsdemo/InputsDemoPluginTest.java:57-120` and
  `plugins/coffee-timer/src/test/java/tutorial/coffeetimer/CoffeeTimerPluginTest.java:62-120`. Each sample's
  `build.gradle.kts` also repeats the same 15 lines of `pluginJar` and `-Dmibsdk.pluginJar` wiring
  (`plugins/hello-menu/build.gradle.kts:5-21`).
- **What happens:** to test a plugin you must know and copy:
  - the `SdkRuntime` constructor and its six collaborators (`RuntimeConfig`, a `Clock`, `HookTable(64)`,
    `UiTable(64)`, `RingLog(500)`);
  - three undocumented `sdk.properties` keys a test needs (`plugin.start.delay.ms=0`, `heap.low.bytes=0`,
    `ui.publish.min.ms=0`; they are only in `RuntimeConfig`'s class comment);
  - the `grants.properties` format;
  - mibpkg's icon index format, with made-up `sha16` names;
  - the plugin-page key format (`<id>/<page>`);
  - a polling `await`.

  None of it is about your plugin. Tests also import `mibsdk.runtime.*` and `mibsdk.platform.*`, which are internal
  packages.
- **Suggested fix:** add a `PluginTestHarness` to `adapter-fake`. Something like
  `PluginTestHarness.start(tmp, features...)` that:
  - reads the jar from the system property;
  - grants what the manifest declares;
  - installs `src/main/icons` with real sha16 names;
  - exposes `ui()`, `advance(ms)`, `await(...)`, `storage()` and `restart()`.

  Move the test-task wiring into `mibsdk.plugin`, so a plugin's build file is just `plugins { id("mibsdk.plugin") }`.

## 7. The dev loop is wired to the owner's bench unit (annoying)

- **Where:** §8.2 and §8.3. `installer/mibsdk-dev:12` sets `DEFAULT_HOST="10.20.0.23"`, and `installer/mibsdk-dev:13`
  expects the unit's mibpkg at `/tmp/mibpkg`. Root SSH needs `ssh-rsa` host-key options.
- **What happens:**
  - Forget the host argument, and every command goes to the owner's bench address.
  - The hot-reload `push` exists only in `mibsdk-dev`. A developer who reaches their car through mibpkg's phone web
    interface has no "push a jar" path: every code change is a full package install with approval.
  - `restart` is documented as "restart the HMI", but on the bench it resets the whole unit for 2 to 3 minutes
    (`docs/car-readiness.md`).
- **Suggested fix:**
  - Require the host, or read `MIBSDK_HOST`, and drop the default.
  - Add a "developer push" to mibpkg's web interface: upload a stamped jar and request a reload.
  - Rename `restart` or say what it really does.

## 8. A `linkCheck` failure does not say where the reference is (annoying)

- **Where:** §10. `LinkCheckTask` (`build-logic/src/main/java/mibsdk/build/LinkCheckTask.java:57`).
- **What happens:**

  ```
  > LinkCheck: 2 reference(s) the car cannot resolve:
      class edu/emory/mathcs/backport/java/util/concurrent/ConcurrentHashMap
      member java/lang/String.matches(Ljava/lang/String;)Z
  ```

  You get no class or method of yours, only JVM descriptors. `java.util.concurrent` appears under the name
  Retrotranslator rewrote it to, which the author never typed. `permissionScan` already prints
  `owner.method -> call` lines, so the information exists in the same build.
- **Suggested fix:**
  - Print `tutorial/coffeetimer/CoffeeTimerPlugin.start -> java/lang/String.matches(String)` for each site.
  - Map the backport prefixes (`edu/emory/mathcs/backport/`, `net/sf/retrotranslator/runtime/`) back to the JDK
    name.
  - Add a hint table for the usual suspects: regex → `indexOf`, `split` → a loop, `concurrent` → the plugin's single
    worker.

## 9. The api-level scan only knows api 4 and 5 members (annoying)

- **Where:** `ApiLevels` (`build-logic/src/main/java/mibsdk/build/ApiLevels.java:36-63`) lists `Ui.icon(String,int)`
  (api 4) and the api 5 members, and nothing from api 2 or 3.
- **What happens:** set `plugins/daemon-status`'s `"api"` from 3 to 1 and it still builds, although it uses
  `Ui.statusItem` and `Net` (api 2) and `ui.stockIcon` and `StatusItem.set(text, icon)` (api 3). The README and the
  tutorial must now say "declare the lowest level you need", but the build only enforces it for 4 and 5. It does
  not matter on SDK 0.3.0, which loads 1 to 5, but it makes `"api"` look checked when it is half checked.
- **Suggested fix:** add the api 2 members (`PluginContext.ui`, `PluginContext.net`, `Ui.page`, `Ui.carMenuRow`,
  `Ui.statusItem`, `PageSpec.*`, `Page.*`, `UiAction.*`, `StatusItem.*`, `Net.query`) and the api 3 members (the
  icon overloads, `Ui.icon(String)`, `Ui.stockIcon`, `StockIcons.isKnown`). A test could also check that every
  `@since api N` tag in `api/` has a table entry.

## 10. Count and icon-size limits live in runtime code (annoying)

- **Where:** §4.3, §4.5 and §10.
  - `UiImpl.MAX_PAGES = 8`, `MAX_CAR_ROWS = 2` and `MAX_STATUS_ITEMS = 1`
    (`runtime/src/main/java/mibsdk/runtime/UiImpl.java:67-69`) are package-private. `Ui.carMenuRow`
    (`api/src/main/java/mibsdk/api/Ui.java:56`) and `Ui.page` (`Ui.java:50`) do not mention them.
  - The status and toast icon limit (20 px), the item icon limit (24 px) and the 12-character status text are in
    the API, but they are checked **only at runtime** (`UiImpl.java:496-500`).
- **What happens:**
  - You find the Car-row and page limits when `IllegalStateException: plugin x already has 2 Car rows` is thrown
    on the unit.
  - A 24×24 icon passed to `statusItem` builds and packages, then throws in `onEnable`. The build has already
    decoded every icon (`PngCheck`), so it knows the sizes.
  - The 12-character status text is cut silently, so "Coffee 10m" fits and "Coffee 10 min" does not.
- **Suggested fix:**
  - Move the three counts to public constants on `Ui` (`MAX_PAGES`, `MAX_CAR_ROWS`, `MAX_STATUS_ITEMS`) and name
    them in the Javadoc.
  - Have `permissionScan` warn when a `statusItem` or `toast` call site uses a constant icon name whose PNG is over
    20 px. Or simply warn at build when a plugin declares no icon of 20 px or smaller but calls `statusItem`.

## 11. Javadoc gaps, the threading guarantee among them (annoying)

- **Where:** `./gradlew :api:javadoc` prints 100 warnings, the limit javadoc stops counting at. They fall in
  `Ui.java` (35), `PageSpec.java` (9), `Net.java` (8), `Features.java` (7), `PluginContext.java` (6), `Hooks.java`
  (6), and then `MibPlugin`, `Log`, `HookObserved`, `OverrideScope`, `IgnitionChanged`, `HookPoint`, `Page`,
  `Events` and `Listener`.
- **What happens:**
  - `Features` constants other than `UI_ICONS` and `UI_INPUTS` have no comment, so you cannot tell what
    `SLOT_CAR_MENU` means on a unit, or when it is absent.
  - `Permissions`, `Log`, `Events`, `Vehicle` and `HookPoint` have no class or member docs.
  - **The most useful guarantee is not in the API at all.** Everything a plugin runs (`onEnable`, actions, timers,
    events, answers) runs serially on one worker per plugin. That is in `SerialExecutor` and `PluginScheduler`
    (`runtime/src/main/java/mibsdk/runtime/PluginScheduler.java:5-7`). `Scheduler`'s Javadoc
    (`api/src/main/java/mibsdk/api/Scheduler.java:3`) says "SDK-owned daemon threads", plural, which suggests you
    need locks.
  - The slow-callback and hung-callback thresholds (5 s and 30 s), and the quarantine rule (3 failures in 60 s),
    are only in `RuntimeConfig`.
- **Suggested fix:**
  - Add a `package-info.java` for `mibsdk.api` with the threading model, the lifecycle, the quarantine rule and
    the inert-handle rule.
  - Document every `Features` and `Permissions` constant, and link each permission to the methods that need it.
  - Fix the `Scheduler` wording.
  - Fail `:api:javadoc` on warnings once they are fixed.

## 12. No scaffolding (annoying)

- **Where:** §1.3 to §1.5.
- **What happens:** a new plugin is five files plus an edit to the SDK's `settings.gradle.kts`, and most of the
  build file and all of the test harness are copied from a sample. `mibpkg new <name>` exists, but it makes a mibpkg
  package, not an SDK plugin.
- **Suggested fix:** add a `./gradlew newPlugin -Pid=my-plugin` task, or a template repository once bump 2 is fixed.
  It would write `plugin.json`, the class, a passing test that uses the harness from bump 6, and the include.

## 13. Permissions are all or nothing (annoying)

- **Where:** §10. Spec §7, and the mibpkg README's "Revoking".
- **What happens:** if a user revokes any one permission, the whole plugin goes to `BLOCKED_PERMISSIONS`. A plugin
  cannot offer "status line optional", so authors will tend to declare fewer features than they could.
- **Suggested fix:** the planned `optional` manifest list. Until then, say plainly in the approval sheet that
  revoking one permission stops the plugin.

## 14. `onDisable` runs after a failed `onEnable` (nit)

- **Where:** §4.1. `MibPlugin.onDisable` (`api/src/main/java/mibsdk/api/MibPlugin.java:14`).
- **What happens:** in the permission experiment of bump 5, the log showed `INFO coffee-timer disabled`, written by
  `onDisable`, right after `onEnable` threw. Fields set after the failure point are still null at that moment, so a
  naive `onDisable` throws a `NullPointerException` on top of the real error. The Javadoc says only "Called when
  the plugin is disabled or unloaded".
- **Suggested fix:** document it, or skip `onDisable` when `onEnable` did not return.

## 15. Records fail with a cryptic error (nit)

- **Where:** §10.
- **What happens:** `record R(int x) {}` fails in `compileJava` with `error: Must be annotated with @Desugar`. That
  is Jabel's message, and it does not say "records are not supported on the car".
- **Suggested fix:** document it (the tutorial now does), and name records in the convention's Javadoc and in the
  spec's G1 list.

## 16. `compat` has 5 of the `java.util.function` interfaces (nit)

- **Where:** `compat/src/main/java/mibsdk/compat/java/util/function/` has `BiFunction`, `Consumer`, `Function`,
  `Predicate` and `Supplier`.
- **What happens:** a lambda typed as `BinaryOperator`, `UnaryOperator`, `BiConsumer`, `IntFunction` and so on
  fails `linkCheck` with `class java/util/function/BinaryOperator (interface of ...$$Lambda$4)`. This message, at
  least, names the class.
- **Suggested fix:** add the rest of the package's common interfaces. They are tiny.

## 17. The spec says there is no `String.format`, and the build accepts it (nit)

- **Where:** spec §4 (`docs/superpowers/specs/2026-10-18-mib2q-plugin-sdk-design.md:90`): "There is no
  `java.util.concurrent` and no `String.format`."
- **What happens:** `String.format("brewing for %d min", minutes)` passes every check. Retrotranslator rewrites it
  to `net/sf/retrotranslator/runtime/java/lang/_String.format`, and `linkCheck` follows that into the
  Retrotranslator runtime. Nothing says whether it works on J9, or how heavy it is on a 60 MB heap. A newcomer who
  read the spec avoids a call the build allows, and one who did not read it uses a call nobody has tested.
- **Suggested fix:** check it on the bench. Then either update the spec or add `_String.format` to a deny list
  with a hint.

## 18. Surprising UI behaviour you only learn from Javadoc or code (nit)

- **Where:** §4.4, §4.6 and §6.
  - A **slider shows its position, not its number**, so the value must be repeated somewhere else (coffee-timer
    puts it in the Start row's value column).
  - **Dropdown and slider rows are always drawn after the list rows**, whatever order you add them in.
  - A **toggle does not flip by itself**: it shows what your next `page.update` says.
  - A **toast needs `ui.slot.statusbar`**, which you would not guess from the name.
  - A Car row's new icon shows only **the next time the Car screen opens**.
- **What happens:** each of these is documented, but in the middle of a long Javadoc paragraph (`PageSpec`'s class
  comment, `Ui.toast`). A newcomer learns them by seeing the wrong thing on the unit.
- **Suggested fix:** a short "UI behaviour" section in `package-info.java` (see bump 11), and screenshots in the
  guide.

## 19. Choice sub-page keys are undocumented (nit)

**Status:** fixed in Task 6. `FakeUi.choose(pageKey, itemId, index)` opens the sub-page and picks the option, and its
Javadoc gives the key format. `inputs-demo`'s test uses it. The description below is the problem as found.

- **Where:** §6, and `plugins/inputs-demo/src/test/java/sample/inputsdemo/InputsDemoPluginTest.java`, where
  `LANG = PAGE + "#lang"` and `ui.select(LANG, "1")` appear.
- **What happens:** to test a `choice` you must know that the SDK derives a sub-page keyed `<page>#<itemId>` whose
  options have the ids `"0"`, `"1"`, and so on. `FakeUi` has `pick` for a dropdown and `turn` for a slider, but no
  `choose` for a choice.
- **Suggested fix:** add `FakeUi.choose(pageKey, itemId, index)`, and document the key format on `FakeUi`.

## 20. Events need no permission, but the approval text says otherwise (nit)

- **Where:** `api/permissions.json:2` (`"Read vehicle state: ignition, gear, parking sensors"`) and
  `PluginContextImpl.events()` (`runtime/src/main/java/mibsdk/runtime/PluginContextImpl.java:241`), which has no
  check.
- **What happens:** any plugin can subscribe to `IgnitionChanged`, `ParkingSoundChanged` and
  `TrailerHitchedChanged` without `vehicle.read`. Only `events().on(HookObserved.class)` is gated. The text the
  user approves says ignition needs the permission. There is also no gear API.
- **Suggested fix:** gate the vehicle events on `vehicle.read`, or change the approval text to match what it
  gates (`vehicle().parking()`), and drop "gear" until a gear API exists.

## 21. Task names are inconsistent (nit)

- **Where:** §5 and §7. The plugin tasks are `mibPluginJar` and `mibPackage`
  (`MibPluginPlugin.java:79` and `:167`). The SDK's are `carJar`, `assembleUnit` and `mibpkgPackage`
  (`dist/build.gradle.kts`).
- **What happens:** you type `mibpkgPackage` for a plugin, or `mibPackage` for the SDK, and get "task not found".
- **Suggested fix:** one name for both (`mibpkgPackage`), with the old one kept as an alias for a release.

## 22. The build and the runtime disagree on manifest rules (nit)

- **Where:**
  - `PluginJson.java:89`: ids are 2 to 40 characters. `ManifestParser.java:31` allows 2 to 41.
  - `PluginJson.java:70-71`: the "missing plugin.json" message lists a `dependencies` field that the build ignores.
  - `ManifestParser.java:59`: the runtime parses `"dependencies"`, and `PluginState.BLOCKED_DEPENDENCY` exists.
    mibpkg's `[plugin]` table has no dependencies, and no doc describes the field.
- **What happens:** the `crashy` and `hello` samples carry `"dependencies": []`, and a newcomer copies it without
  knowing whether it does anything.
- **Suggested fix:** document `dependencies`, or remove it from the samples and the message. Make the two id rules
  share one constant, or explain the difference where the rule is stated (the mibpkg README does).

## 23. `mibsdk-dev` says mibpkg 0.8.0 is enough (nit)

- **Where:** `installer/mibsdk-dev:4`, `:32` and `:104`.
- **What happens:** the tool accepts mibpkg 0.8.0. SDK 0.3.0's package needs 0.9.0 (`hmisystem`, icons), and
  plugin icon reflections need 0.9.1. An install on 0.8 fails inside mibpkg with "unknown field".
- **Suggested fix:** raise the check to 0.9.1, or read the required version from the package.

## 24. Missing samples and fake-car gaps (nit)

- **Where:** `plugins/`, and `FakePlatform` (`adapter-fake/src/main/java/mibsdk/adapter/fake/FakePlatform.java:124-131`).
- **What happens:**
  - Nothing in `plugins/` uses `ctx.hooks()` (veto or observe), so `hooks.parking` has no example.
  - No sample uses `Page.open()` or `link` between two plugin pages.
  - `FakePlatform` can publish ignition and parking-sound changes, but not `TrailerHitchedChanged`, so a plugin
    that reacts to the trailer cannot be tested on the host.
- **Suggested fix:** add a small hooks sample with a test, and `FakePlatform.setTrailerHitched(boolean)`.

## 25. Build output noise and a Gradle 9 deprecation warning (nit)

- **Where:** every plugin build.
- **What happens:** each build prints Retrolambda's INFO block, its classpath (with the full JDK 8 `rt.jar` path)
  and "Transformed N file(s)". `check` also ends with "Deprecated Gradle features were used in this build, making
  it incompatible with Gradle 9.0". A newcomer cannot tell this noise from a real warning.
- **Suggested fix:** run Retrolambda quietly unless `--info`, and fix the deprecations the problems report lists
  (`build/reports/problems/problems-report.html`).
