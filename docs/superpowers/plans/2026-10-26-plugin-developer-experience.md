# Plugin Developer Experience Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Anyone with JDK 8 + JDK 17 (and no Audi firmware) can create, build, host-test and package an mibsdk plugin in its own repository, with build-time errors that name the cause.

**Architecture:** `linkCheck` reads a committed signature list of the car JCL when no `lsd.jar` is configured. `api`, `compat`, `platform`, `runtime`, `adapter-fake`, a new `plugin-test` harness module and the `mibsdk.plugin` Gradle plugin publish to a file repo under `build/repo`. The convention takes them by coordinate when there is no `:api` project. A committed template is both the newcomer's starting point and the fixture of a root `standalonePluginCheck`. The build learns permission names, API-to-permission rules, api levels 2–3 and UI limits, and `linkCheck` names the referencing site.

**Tech Stack:** Gradle 8 (Java build-logic, TestKit), ASM 9.7, JUnit 5, Retrolambda/Retrotranslator, Python 3 unittest (installer), bash (`mibsdk-dev`).

**Spec:** `docs/guide/developer-bumps.md` (the bumps list; "bump N" below means its section N). Platform rules: `docs/superpowers/specs/2026-10-18-mib2q-plugin-sdk-design.md`.

## Global Constraints

- Maven group `dev.complex256.mibsdk`; version = `mibsdk.version` in `gradle.properties` (0.3.0); Gradle plugin ids stay `mibsdk.plugin` and `mibsdk.car-library`.
- Publishing goes only to the file repo `<repo root>/build/repo`. No `publishToMavenLocal`, no remote repo, no push, no tag.
- On-unit classes stay class-file v48; runtime/platform/compat code stays Java 1.4 API (compat stays Java 1.4 *source*: no generics); LinkCheck still runs for every car module.
- API changes are additive only; `SdkInfo.API_VERSION` stays 5, manifests api 1..5 still load. No new api level in this plan.
- No plugin code on stock HMI threads; stock ids only in `adapter-hqc` `HqcUiIds` (nothing here touches them).
- No owner name, personal email or home-directory path in any committed file (this includes generated files and error messages).
- The car JCL signature list carries names, flags, supertypes and member names/descriptors only, never bytecode.
- Every task updates the README / `docs/guide/first-plugin.md` text its change makes wrong, in the same commit, and marks its bump fixed in `docs/guide/developer-bumps.md` (add `**Status:** fixed in <task>` under the bump heading).
- Gates before every commit: `./gradlew check`, `./gradlew :build-logic:test`, `python3 -m unittest discover -s installer -p 'test_*.py'`. Commit with `git -c user.name=complex256 -c user.email=me@complex256.dev commit`; do not add an AI co-author trailer (DeepSeek is not Anthropic, and a wrong trailer is worse than none).
- Host only: no SSH to any unit, no bench, no car.

## Review Focus

1. **The owner's own build after the path defaults go (Task 3):** with `mibsdk.stockLsdJar`, `mibsdk.jdk8Home` and `mibsdk.stockDsiTracerJar` unset, every module except `adapter-hqc` and `dist` must still build. `adapter-hqc`/`dist` must fail with the property-naming message, not a Gradle validation dump. Test: `ToolchainTest.missingPropertyNamesItAndTheReadme` plus the Task 3 step that runs `./gradlew check` with an empty `GRADLE_USER_HOME` properties file.
2. **Signature-list drift:** a regenerated list that differs from the committed one must fail the build when `lsd.jar` is configured, rather than going stale silently. Test: `CarJclSignaturesTest.checkTaskFailsOnDrift` (Task 2).
3. **jxe2jar's unreadable classes** (`java/lang/Double` and the others in LinkCheck's known-unreadable list) must behave the same from the signature list as from the jar. Test: `LinkCheckTest.signatureListMatchesJarForUnreadableClasses` (Task 1).
4. **A plugin that guards a permissioned call with `ctx.granted(...)`** still has to declare the permission. The new scan failing it is intended, and the error must say "declare it in plugin.json". Test: `PermissionScanTest.guardedCallStillNeedsDeclaration` (Task 8).
5. **An existing sample whose `"api"` is lower than what the new api 2/3 table finds** must fail with the per-site message, and the sample is then fixed, not the table. The case: `hello` declares api 1 (Task 9 step 5).

---

## File Structure

- `build-logic/src/main/java/mibsdk/build/CarJclSignatures.java` (new): text format write/read of class signatures.
- `build-logic/src/main/resources/mibsdk/build/carjcl.sigs` (new, generated, committed): the car JCL list.
- `build-logic/src/main/java/mibsdk/build/LinkCheck.java`, `LinkCheckTask.java`: index `.sigs`, report sites (Task 11).
- `build-logic/src/main/java/mibsdk/build/LinkCheckHints.java` (new): backport-name mapping and hints.
- `build-logic/src/main/java/mibsdk/build/CarJclTask.java`: jar mode or bundled-list mode.
- `build-logic/src/main/java/mibsdk/build/Toolchain.java`: no home defaults; JDK 8 by toolchain; `required(...)` messages.
- `build-logic/src/main/java/mibsdk/build/SdkCoordinates.java` (new): group/version/permission catalog read from a generated resource.
- `build-logic/src/main/java/mibsdk/build/MibPluginPlugin.java`, `CarLibraryPlugin.java`: standalone mode, test wiring, `mibpkgPackage`.
- `build-logic/src/main/java/mibsdk/build/ApiPermissions.java` (new): API member → permission table.
- `build-logic/src/main/java/mibsdk/build/ApiLevels.java`, `PermissionScan.java`, `PermissionScanTask.java`, `PluginJson.java`.
- `plugin-test/` (new module): `mibsdk.test.PluginTestHarness`.
- `templates/plugin/` (new): the stand-alone plugin template.
- `build.gradle.kts` (root): `publishLocal`, `standalonePluginCheck`, `newPlugin`.
- `api/src/main/java/mibsdk/api/Ui.java`, `Scheduler.java`, `MibPlugin.java`, `package-info.java` (new).
- `runtime/src/main/java/mibsdk/runtime/UiImpl.java`, `PluginContextImpl.java`, `UndeclaredPermissionException.java` (new).
- `compat/src/main/java/mibsdk/compat/java/util/function/*.java` (new interfaces).
- `adapter-fake/src/main/java/mibsdk/adapter/fake/FakeUi.java` (`choose`).
- `installer/mibsdk-dev`, `installer/test_mibsdk_dev.py`.

---

### Task 1: Car JCL signature format, read by LinkCheck

**Files:**
- Create: `build-logic/src/main/java/mibsdk/build/CarJclSignatures.java`
- Modify: `build-logic/src/main/java/mibsdk/build/LinkCheck.java` (`Checker.index`)
- Test: `build-logic/src/test/java/mibsdk/build/CarJclSignaturesTest.java`, `LinkCheckTest.java`

**Interfaces:**
- Produces: `final class CarJclSignatures` with
  - `static void write(File dirOrJar, String sourceLabel, Writer out) throws IOException`: indexes `java/**` and `javax/**` classes only. It does what `CarJclTask` filters, so it accepts the jar or an extracted dir.
  - `static void readInto(InputStream in, SignatureSink sink) throws IOException`
  - `interface SignatureSink { void klass(String name, String superName, String[] interfaces, boolean isInterface, Set<String> members); void unreadable(String name); }`
  - `static final String SUFFIX = ".sigs"`
- File format (UTF-8, LF, sorted by class name, members sorted, deterministic):
  ```
  # mibsdk car JCL signatures v1
  # source: <sourceLabel>
  C java/lang/Object - - 0
  M <init>()V
  M hashCode()I
  F out:Ljava/io/PrintStream;
  C java/lang/Runnable java/lang/Object - 1
  U java/lang/Double
  ```
  The `C` fields are name, super (`-` for none), interfaces comma-joined (`-` for none), `1` if interface. `M name+desc` is a method and `F name:desc` a field (the same keys `LinkCheck.ClassInfo.members` uses). `U name` is a class ASM could not read.
- `LinkCheck.run` behaviour: a classpath entry that is a `*.sigs` file, or a directory containing `*.sigs` files at its top level, is indexed from the list (never `keepBody`). A `U` entry is added to the unreadable set and treated as present like the known-unreadable list.

- [ ] **Step 1: Write the failing tests**

  - `CarJclSignaturesTest.roundTripsAFixtureDirectory`: write the existing `Fixtures` JCL dir (the one `LinkCheckTest` uses) to a string; read it back into a recording sink; assert it has the same classes, supers, interfaces, interface flags and member sets as indexing the dir with ASM. Assert the output is byte-identical on a second `write` (determinism).
  - `CarJclSignaturesTest.headerNamesTheSource`: the second line equals `# source: TEST`.
  - `LinkCheckTest.signatureListGivesTheSameResultAsTheClasses`: for every existing LinkCheck fixture case, run `LinkCheck.run(checked, [sigsFile], [])` and `run(checked, [classesDir], [])`, then assert equal `missing()`.
  - `LinkCheckTest.signatureListMatchesJarForUnreadableClasses`: a fixture with a corrupt `java/lang/Double.class` gives a `U` line, and a reference to `java/lang/Double.valueOf(D)Ljava/lang/Double;` is not reported missing.

- [ ] **Step 2: Run** `./gradlew :build-logic:test --tests '*CarJclSignatures*' --tests '*LinkCheckTest*'`. Expected: compilation FAIL (`CarJclSignatures` missing).

- [ ] **Step 3: Implement** `CarJclSignatures` (ASM `SKIP_CODE`, like `Checker.index`). Then implement the `.sigs` branch in `Checker.index`, filling `ClassInfo` and `unreadable` through the sink.

- [ ] **Step 4: Run** the same command. Expected: PASS. Then run all three gates.

- [ ] **Step 5: Commit** `feat(build-logic): car JCL signature list format, read by linkCheck`.

### Task 2: Generate, commit and bundle the car JCL list; `carJcl` without `lsd.jar`

**Files:**
- Create: `build-logic/src/main/resources/mibsdk/build/carjcl.sigs` (generated by step 4, committed)
- Modify: `build-logic/src/main/java/mibsdk/build/CarJclTask.java`, `CarLibraryPlugin.java` (`registerCarJcl`)
- Create: `build-logic/src/main/java/mibsdk/build/CarJclSignaturesTask.java`
- Test: `build-logic/src/test/java/mibsdk/build/CarJclSignaturesTest.java`, `MibPluginPluginFunctionalTest.java`

**Interfaces:**
- Consumes: Task 1's `CarJclSignatures`.
- Produces:
  - `CarJclTask.getStockLsdJar()` becomes `@Optional @InputFile`. When it is present, the task extracts classes as today. When it is absent, it writes the bundled resource `/mibsdk/build/carjcl.sigs` to `<outputDir>/carjcl.sigs`. Same output dir, so `linkCheck` is unchanged.
  - Root task `carJclSignatures` (`CarJclSignaturesTask`, registered only when `mibsdk.stockLsdJar` is set): regenerates `build-logic/src/main/resources/mibsdk/build/carjcl.sigs` with `sourceLabel = MpkStage.HMI` (the HMI build string).
  - Root task `carJclSignaturesCheck`: same generation into `build/`, failing when it differs from the bundled resource. It is wired into root `check` only when the property is set.

- [ ] **Step 1: Write the failing tests**
  - `CarJclSignaturesTest.bundledListIsForTheSupportedBuild`: load the resource; assert line 2 is `# source: HMI_AU_MIB2_HQC_NAR_G22_C10_180803`. Assert it has a `C java/lang/Object` line and more than 600 `C` lines (the extracted JCL has 696 class files).
  - `CarJclSignaturesTest.checkTaskFailsOnDrift`: TestKit project with a fake "lsd.jar" (a zip holding one fixture class). Run `carJclSignaturesCheck`. Expected: FAIL with `car JCL signatures differ from the bundled list: run ./gradlew carJclSignatures`.
  - `MibPluginPluginFunctionalTest.pluginBuildsWithoutStockLsdJar`: run the existing `javaPluginProducesV48JarWithManifest` build with `-Pmibsdk.stockLsdJar` not passed. Assert success and `build/carjcl/carjcl.sigs` exists.

- [ ] **Step 2: Run** `./gradlew :build-logic:test --tests '*CarJclSignatures*' --tests '*FunctionalTest.pluginBuildsWithoutStockLsdJar'`. Expected: FAIL (resource missing, task missing).

- [ ] **Step 3: Implement** the optional input, the resource fallback, and the two root tasks.

- [ ] **Step 4: Generate the list:** `./gradlew carJclSignatures -Pmibsdk.stockLsdJar=<owner's lsd.jar>`. Then check it with `grep -c '^C ' build-logic/src/main/resources/mibsdk/build/carjcl.sigs` (expect ~696) and `grep -c '^\(M\|F\) ' …`. Also `grep -v '^[CMFU#]'` (expect no output: no bytecode or other content).

- [ ] **Step 5: Run** the tests above, then `./gradlew clean check` with no `stockLsdJar` property, then with it. Expected: all PASS, and `carJclSignaturesCheck` passes. Then run all three gates.

- [ ] **Step 6: Docs.** README "Toolchain": `lsd.jar` becomes optional (SDK maintainers only; it checks the bundled list). Rewrite developer-bumps bump 1 status. In `first-plugin.md`, drop the "you need lsd.jar" prerequisite.

- [ ] **Step 7: Commit** `feat(build): plugins link-check against a bundled car JCL signature list; lsd.jar optional`.

### Task 3: Toolchain paths: no personal defaults, JDK 8 by toolchain, messages that name the property

**Files:**
- Modify: `build-logic/src/main/java/mibsdk/build/Toolchain.java`, `CarLibraryPlugin.java`, `BackportTask.java` callers, `KotlinShrinkTask` caller in `MibPluginPlugin.java`, `adapter-hqc/build.gradle.kts`, `settings.gradle.kts`, `gradle.properties` comment
- Test: `build-logic/src/test/java/mibsdk/build/ToolchainTest.java` (new)

**Interfaces:**
- Produces:
  - `static Provider<String> jdk8Home(Project p)`: `mibsdk.jdk8Home` if set. Otherwise the installation path of `JavaToolchainService.launcherFor(JavaLanguageVersion.of(8))`. Callers switch from `File` to this provider (`BackportTask.getJdk8Home().set(...)`, the legacy compile's fork executable and bootclasspath, `KotlinShrinkTask`).
  - `static File required(Project p, String property, String what)`: returns the file, or throws `GradleException` with exactly:
    `mibsdk: <property> is not set` (or `mibsdk: <property> points at <path>, which does not exist`), then `\n  <what>\n  Set it with -P<property>=<path> or in ~/.gradle/gradle.properties; see README.md#toolchain`.
  - `stockLsdJar(Project)` returns `null` when unset (Task 2's optional mode). When it is set but the file is missing, it calls `required`. `stockDsiTracerJar(Project)` always calls `required`.
  - `settings.gradle.kts` applies `id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"` so a missing JDK 8 can be provisioned.

- [ ] **Step 1: Write the failing tests** (ProjectBuilder):
  - `ToolchainTest.missingPropertyNamesItAndTheReadme`: `stockDsiTracerJar` with no property throws. The message contains `mibsdk.stockDsiTracerJar is not set` and `README.md#toolchain`.
  - `ToolchainTest.missingFileNamesThePath`: the property set to `/nonexistent/x.jar` gives a message containing `points at /nonexistent/x.jar, which does not exist`.
  - `ToolchainTest.unsetLsdJarIsOptional`: `stockLsdJar(project)` returns null.
  - `ToolchainTest.noHomeRelativeDefaults`: the `Toolchain` source has no `user.home` (read the source file via `mibsdk.repoRoot`).

- [ ] **Step 2: Run** `./gradlew :build-logic:test --tests '*ToolchainTest*'`. Expected: FAIL.

- [ ] **Step 3: Implement.** In `adapter-hqc/build.gradle.kts`, replace its hand-written message with `Toolchain.required(...)`.

- [ ] **Step 4: Run** the tests. Then run `GRADLE_USER_HOME=$(mktemp -d) ./gradlew check -x :adapter-hqc:check -x :dist:check` from the worktree. The first run downloads dependencies. Expected: PASS without any `mibsdk.*` property. Then run `./gradlew :adapter-hqc:compileJava` with no properties. Expected: FAIL with the `mibsdk.stockDsiTracerJar is not set` message.

- [ ] **Step 5: Owner step (before the gates):** ask the owner to add `mibsdk.stockDsiTracerJar=…` (and optionally `mibsdk.stockLsdJar=…`, `mibsdk.jdk8Home=…`) to their `~/.gradle/gradle.properties`. Today they rely on the defaults being removed. Do not edit that file without their OK. Then run the three gates.

- [ ] **Step 6: Docs.** Rewrite the README toolchain table (JDK 8 found by Gradle toolchain or `-Pmibsdk.jdk8Home`; stock jars only for `adapter-hqc`/`dist`). Rewrite the `gradle.properties` comment and the bump 4 status.

- [ ] **Step 7: Commit** `fix(build): no machine-local path defaults; JDK 8 via toolchain; missing paths name their property`.

### Task 4: Publish the SDK artifacts and the build plugin to a local file repo

**Files:**
- Modify: `api/`, `compat/`, `platform/`, `runtime/`, `adapter-fake/` `build.gradle.kts`, root `build.gradle.kts`, `build-logic/build.gradle.kts`
- Create: `build-logic/src/main/java/mibsdk/build/SdkCoordinates.java`; a generated resource `mibsdk/build/sdk.properties` and a bundled `mibsdk/build/permissions.json` (both produced by a build-logic `processResources` step from `../gradle.properties` and `../api/permissions.json`)
- Test: `build-logic/src/test/java/mibsdk/build/SdkCoordinatesTest.java`

**Interfaces:**
- Produces:
  - `SdkCoordinates.GROUP = "dev.complex256.mibsdk"`, `SdkCoordinates.version()` (from the resource), `SdkCoordinates.coordinate(String artifact)` → `"dev.complex256.mibsdk:<artifact>:<version>"`, `SdkCoordinates.permissionCatalog()` → `Map<String,String>` id→text from the bundled json.
  - Artifacts in `build/repo` (group `dev.complex256.mibsdk`, version 0.3.0): `api` (host jar + `car` classifier = `carJar` + sources + javadoc), `compat` (jar + `car` classifier), `platform`, `runtime`, `adapter-fake` (host jars + sources), the plugin marker `mibsdk.plugin.gradle.plugin` and `build-logic` (artifact id `mibsdk-gradle-plugin`).
  - Root task `publishLocal`: depends on every `publishAllPublicationsToLocalRepository` and on `gradle.includedBuild("build-logic").task(":publishAllPublicationsToLocalRepository")`.

- [ ] **Step 1: Write the failing test** `SdkCoordinatesTest.versionMatchesGradleProperties`: `SdkCoordinates.version()` equals `mibsdk.version` read from `<repoRoot>/gradle.properties`. `permissionCatalog()` has the 7 ids of `api/permissions.json`.

- [ ] **Step 2: Run** `./gradlew :build-logic:test --tests '*SdkCoordinatesTest*'`. Expected: FAIL.

- [ ] **Step 3: Implement** the publications (`maven-publish`, repository `local` = `rootProject.layout.buildDirectory.dir("repo")`; build-logic uses `../build/repo`), the resources and `publishLocal`.

- [ ] **Step 4: Verify:** `./gradlew publishLocal && find build/repo -name '*.pom' | sort`. Expected: one POM each for api, compat, platform, runtime, adapter-fake, mibsdk-gradle-plugin and the plugin marker. `unzip -l build/repo/dev/complex256/mibsdk/api/0.3.0/api-0.3.0-car.jar` lists only `mibsdk/api/**` classes. Also run `grep -r "$HOME" build/repo --include='*.pom'`. Expected: no output.

- [ ] **Step 5: Run** the gates and the test.

- [ ] **Step 6: Commit** `build: publish SDK artifacts and the mibsdk.plugin Gradle plugin to build/repo`.

### Task 5: Stand-alone plugin builds (convention without `:api`), template and `standalonePluginCheck`

**Files:**
- Modify: `build-logic/src/main/java/mibsdk/build/MibPluginPlugin.java` (`apply`), `CarLibraryPlugin.java` (`compatClasses`)
- Create: `templates/plugin/{settings.gradle.kts, build.gradle.kts, gradle.properties, src/main/resources/plugin.json, src/main/java/example/myplugin/MyPlugin.java, .gitignore}`
- Modify: root `build.gradle.kts` (`standalonePluginCheck`, added to `check`)
- Test: `build-logic/src/test/java/mibsdk/build/MibPluginPluginFunctionalTest.java`

**Interfaces:**
- Consumes: Task 4's `SdkCoordinates.coordinate(...)`, the `build/repo` layout.
- Produces:
  - Mode rule: `boolean inSdkRepo = project.getRootProject().findProject(":api") != null`. In-repo, the behaviour is unchanged (project deps, `evaluationDependsOn`). Stand-alone, the convention adds `compileOnly coordinate("api")`, a non-transitive resolvable configuration `mibsdkCarLibs` with `coordinate("api") + ":car"@jar` and `coordinate("compat") + ":car"@jar` into `carClasspath`, and `mibsdkCompat` from `coordinate("compat")`. It adds no repositories: the template's `settings.gradle.kts` declares them.
  - Template `settings.gradle.kts`: `pluginManagement { repositories { maven { url = uri(providers.gradleProperty("mibsdk.repo").get()) }; gradlePluginPortal() } }`, `dependencyResolutionManagement { repositories { maven(<same>); mavenCentral() } }`, the foojay plugin, `rootProject.name = "my-plugin"`. The template `gradle.properties` holds `mibsdk.repo=` with a comment.
  - Template `build.gradle.kts`: `plugins { id("mibsdk.plugin") version "0.3.0" }` and nothing else (Task 7 gives tests their wiring).
  - Template plugin: id `my-plugin`, api 1, no permissions, `MyPlugin extends MibPlugin` logging "my-plugin enabled".
  - Root `standalonePluginCheck` (Exec): depends on `publishLocal`. It copies `templates/plugin` to `build/standalone-check/`, then runs `<repo>/gradlew -p build/standalone-check -Pmibsdk.repo=<file uri of build/repo> mibPluginJar test` and asserts `build/standalone-check/build/mibsdk/my-plugin.jar` exists.

- [ ] **Step 1: Write the failing test** `MibPluginPluginFunctionalTest.conventionOutsideTheSdkUsesCoordinates`. It is a TestKit project with no `:api`/`:compat` and a `maven { url = <tmp repo> }` holding hand-made `api`/`compat` jars (built from the test's existing `Fixtures` classes, with POMs). Assert `dependencies --configuration compileClasspath` lists `dev.complex256.mibsdk:api:<version>`, and that `mibPluginJar` succeeds.

- [ ] **Step 2: Run** `./gradlew :build-logic:test --tests '*conventionOutsideTheSdkUsesCoordinates'`. Expected: FAIL (`Project with path ':compat' could not be found`).

- [ ] **Step 3: Implement** the mode rule and the template. Then implement `standalonePluginCheck` and add it to root `check`.

- [ ] **Step 4: Run** `./gradlew standalonePluginCheck`. Expected: PASS and `my-plugin.jar` present. Then run the three gates.

- [ ] **Step 5: Docs.** README "How a plugin reaches a car": build your plugin in its own repo from `templates/plugin`, after `./gradlew publishLocal` in an SDK checkout (until the artifacts are hosted). `first-plugin.md` §1.2–1.4: start from the template instead of `plugins/` + `settings.gradle.kts`. Bump 2 status.

- [ ] **Step 6: Commit** `feat(build): mibsdk.plugin builds outside the SDK repo; plugin template; standalonePluginCheck`.

### Task 6: `PluginTestHarness` (new `plugin-test` module) and `FakeUi.choose`

**Files:**
- Create: `plugin-test/build.gradle.kts`, `plugin-test/src/main/java/mibsdk/test/PluginTestHarness.java`, `plugin-test/src/test/java/mibsdk/test/PluginTestHarnessTest.java`
- Modify: `settings.gradle.kts` (`include("plugin-test")`), `adapter-fake/src/main/java/mibsdk/adapter/fake/FakeUi.java`, Task 4's publish list (add `plugin-test`)
- Modify: `plugins/coffee-timer/src/test/.../CoffeeTimerPluginTest.java`, `plugins/hello-menu/.../HelloMenuPluginTest.java`, `plugins/inputs-demo/.../InputsDemoPluginTest.java` (port to the harness)

**Interfaces:**
- Consumes: `SdkRuntime`, `RuntimeConfig`, `FakePlatform`, `FakeUi`, `UiTable`, `HookTable`, `RingLog`. The harness alone uses these internals; plugin tests no longer import them.
- Produces (package `mibsdk.test`, host Java 8 language level like `adapter-fake`):
  ```java
  public final class PluginTestHarness implements AutoCloseable {
      public static Builder builder(File tmp);               // jar from -Dmibsdk.pluginJar, icons dir from -Dmibsdk.pluginIcons
      public static PluginTestHarness start(File tmp);       // builder(tmp).start()
      public static final class Builder {
          public Builder features(String... features);       // default: every Features constant (UI_PAGES, UI_INPUTS, UI_ICONS, SLOT_CAR_MENU, SLOT_STATUS_BAR, PARKING, IGNITION, HOOK_PARKING)
          public Builder grants(String... permissions);      // default: every permission plugin.json declares
          public Builder jar(File jar); public Builder icons(File dir);
          public PluginTestHarness start();                  // waits until ENABLED; fails with the runtime log otherwise
          public PluginTestHarness startExpectingFailure();  // waits until FAILED/BLOCKED_*, does not throw
      }
      public String pluginId();
      public String pageKey(String pageId);                  // "<id>/<pageId>"
      public String state();                                 // PluginState name, e.g. "ENABLED"
      public FakeUi ui(); public FakePlatform car();
      public void advance(long ms);                          // moves the plugin's Scheduler clock
      public void await(BooleanSupplier condition, String what); // 5 s, fails with the UI render and log
      public Properties storedValues();                       // stops the runtime (flushes) and reads data/<id>.properties
      public void restart();                                  // stop + start on the same tmp (storage kept)
      public String log();
      public void close();
  }
  ```
  - Icons are installed as mibpkg does: `<sha16>.png`, where sha16 is the first 16 lowercase hex characters of SHA-256 of the bytes, plus `index.properties` lines `<name>=<sha16>,<w>,<h>`. The harness reads sizes with `ImageIO` (host only).
  - The `sdk.properties` it writes is `plugin.start.delay.ms=0`, `heap.low.bytes=0`, `ui.publish.min.ms=0`, `log.file=<tmp>/mibsdk.log`. Documented on the class.
  - `FakeUi.choose(String pageKey, String itemId, int index)`: opens the `<pageKey>#<itemId>` sub-page and selects option id `String.valueOf(index)`. The key format is documented on `FakeUi`.

- [ ] **Step 1: Write the failing tests** in `PluginTestHarnessTest`, using the `hello-menu` jar (wired like `runtime/build.gradle.kts` wires `hello`):
  - `startsTheBuiltJarEnabled`: `state()` is `"ENABLED"` and `ui().carRows()` is non-empty.
  - `installsIconsUnderTheirSha16`: the index line for `hello` equals `hello=<sha256 prefix of src/main/icons/hello.png>,<w>,<h>`.
  - `withoutAGrantThePluginIsBlocked`: `builder(tmp).grants().startExpectingFailure().state()` starts with `BLOCKED`.
  - `restartKeepsStorage` and `advanceMovesTheScheduler` (hello-menu's existing timer behaviour).
  - `FakeUiTest.chooseSelectsTheOption` (adapter-fake): the inputs-demo-style spec `choice("lang", …)`, then `choose(page, "lang", 1)`, gives an action with `value() == 1`.

- [ ] **Step 2: Run** `./gradlew :plugin-test:test :adapter-fake:test`. Expected: FAIL (classes missing).

- [ ] **Step 3: Implement** the harness and `choose`. Then port the three sample tests. Every existing assertion stays, and the copied harness code and `mibsdk.runtime`/`mibsdk.platform` imports go away.

- [ ] **Step 4: Run** `./gradlew :plugin-test:test :plugins:coffee-timer:test :plugins:hello-menu:test :plugins:inputs-demo:test`. Expected: PASS with the same test counts as before (coffee-timer 6). Then run the gates.

- [ ] **Step 5: Docs.** `first-plugin.md` host-test section rewritten around `PluginTestHarness`. Bumps 6 and 19 status.

- [ ] **Step 6: Commit** `feat(test): PluginTestHarness for plugin host tests; FakeUi.choose`.

### Task 7: Convention wires host tests; samples' build files shrink to `plugins { }`

**Files:**
- Modify: `build-logic/src/main/java/mibsdk/build/MibPluginPlugin.java`; every `plugins/*/build.gradle.kts`; `templates/plugin/` (add `src/test/java/example/myplugin/MyPluginTest.java`)
- Test: `build-logic/src/test/java/mibsdk/build/MibPluginPluginFunctionalTest.java`

**Interfaces:**
- Consumes: Task 6's `plugin-test` (project `:plugin-test` in-repo, `coordinate("plugin-test")` stand-alone), Task 5's mode rule.
- Produces: the convention adds `testImplementation(plugin-test)` and JUnit (`Toolchain.JUNIT`, `JUNIT_LAUNCHER`). Every `Test` task gets the `mibPluginJar` output as an input, `-Dmibsdk.pluginJar=<jar>` and `-Dmibsdk.pluginIcons=<project>/src/main/icons`.

- [ ] **Step 1: Write the failing test** `MibPluginPluginFunctionalTest.testTaskSeesThePluginJar`. The fixture plugin's test asserts `System.getProperty("mibsdk.pluginJar")` ends with `/<id>.jar` and the file exists. The fixture build file has only `plugins { id("mibsdk.plugin") }`.

- [ ] **Step 2: Run** it. Expected: FAIL (property null).

- [ ] **Step 3: Implement.** Delete the duplicated wiring from the sample build files and add the template's test, which uses `PluginTestHarness.start(tmp)` and asserts `state()` is `ENABLED`. `standalonePluginCheck` already runs `test`.

- [ ] **Step 4: Run** `./gradlew standalonePluginCheck` and the gates. Expected: PASS, and the template's test ran (`build/standalone-check/build/test-results/test/*.xml` exists).

- [ ] **Step 5: Docs.** In `first-plugin.md`, the plugin's `build.gradle.kts` is now one block. Bump 6 status (the build-script half).

- [ ] **Step 6: Commit** `feat(build): mibsdk.plugin wires host tests and the harness`.

### Task 8: Permissions checked at build; runtime says "not declared" vs "not granted"

**Files:**
- Create: `build-logic/src/main/java/mibsdk/build/ApiPermissions.java`, `runtime/src/main/java/mibsdk/runtime/UndeclaredPermissionException.java`
- Modify: `PluginJson.java` (`read`), `PermissionScan.java` (`Result`), `PermissionScanTask.java`, `MibPluginPlugin.java` (pass declared permissions), `runtime/.../UiImpl.java` and `PluginContextImpl.java` (`requirePermission`)
- Test: `PluginJsonTest.java`, `PermissionScanTest.java`, `runtime/src/test/java/mibsdk/runtime/PermissionMessageTest.java` (new)

**Interfaces:**
- Consumes: Task 4's `SdkCoordinates.permissionCatalog()`.
- Produces:
  - `PluginJson.read` fails for an unknown permission: `mibsdk.plugin: <file>: unknown permission "ui.slot.statusbr" (did you mean "ui.slot.statusbar"?); known: <comma list>`. The hint is the catalog id at Levenshtein distance ≤ 3, else omitted.
  - `ApiPermissions.required(String owner, String name, String desc)` → permission id or null. It keys on owner + name (any descriptor):
    `mibsdk/api/Ui.carMenuRow`→`ui.slot.carmenu`; `Ui.statusItem`, `Ui.toast`→`ui.slot.statusbar`; `mibsdk/api/PluginContext.net`→`network`; `PluginContext.vehicle`→`vehicle.read`; `mibsdk/api/Parking.overrideSoundDirections`→`vehicle.parking.write`; `PluginContext.hooks`→`hooks.parking`.
  - `PermissionScan.Result.permissionSites()` → sorted `Map<String,String>` of `owner.method -> Ui.carMenuRow` → permission. `PermissionScanTask.getDeclaredPermissions()` (`ListProperty<String>`). The task fails when a needed permission is undeclared, listing each site: `<site> needs "ui.slot.carmenu": declare it in plugin.json "permissions"`. This applies even with `platform.unrestricted`. The library default is an empty check (report only).
  - `UndeclaredPermissionException extends PermissionDeniedException` (runtime, Java 1.4 API), overriding `getMessage()` with `permission not declared in plugin.json: <p>`. The runtime throws it when `p` is not in the manifest's permissions. A declared-but-ungranted `p` keeps `permission not granted: <p>` (the owner did not approve it).

- [ ] **Step 1: Write the failing tests**
  - `PluginJsonTest.unknownPermissionSuggestsTheNearest` (`ui.slot.statusbr` → message contains `did you mean "ui.slot.statusbar"`); `PluginJsonTest.unknownPermissionWithoutNearMatchListsKnown` (`gps`).
  - `PermissionScanTest.carMenuRowNeedsItsPermission`: a fixture class calling `Ui.carMenuRow`, declared `[]`, gives the `needs "ui.slot.carmenu"` line.
  - `PermissionScanTest.guardedCallStillNeedsDeclaration`: the call is inside `if (ctx.granted("ui.slot.carmenu"))`, so the same finding.
  - `PermissionScanTest.declaredPermissionPasses`.
  - `PermissionMessageTest.undeclaredSaysNotDeclared` / `declaredButNotGrantedSaysNotGranted`: build a `PluginContextImpl` the way the existing runtime tests do. Assert the exception type and message for `ctx.net()`.

- [ ] **Step 2: Run** `./gradlew :build-logic:test --tests '*PluginJsonTest*' --tests '*PermissionScanTest*' :runtime:test --tests '*PermissionMessageTest*'`. Expected: FAIL.

- [ ] **Step 3: Implement.** Then run `./gradlew check`. Every sample must pass. If one fails, its manifest is missing a permission it uses: fix the manifest and bump that sample's patch version.

- [ ] **Step 4: Run** the tests and the gates. Expected: PASS.

- [ ] **Step 5: Docs.** In `first-plugin.md`, the permissions section and the debugging section's "permission not granted" walkthrough. Bump 5 status.

- [ ] **Step 6: Commit** `feat(build): check permission names and API-to-permission use at build; clearer runtime denial`.

### Task 9: Api-level scan knows api 2 and 3

**Files:**
- Modify: `build-logic/src/main/java/mibsdk/build/ApiLevels.java` (static table); `api/src/main/java/mibsdk/api/{PluginContext,Ui,Page,PageSpec,UiAction,StatusItem,Net}.java` (add `@since api 2` Javadoc tags to api 2 members)
- Test: `build-logic/src/test/java/mibsdk/build/ApiLevelsTest.java` (new)

**Interfaces:**
- Produces: table entries (label in parentheses):
  - api 2: `PluginContext.ui`, `PluginContext.net`, `Ui.page`, `Ui.carMenuRow(String,Page)`, `Ui.statusItem(String)`, every `Page` method, every `PageSpec`/`PageSpec$Entry`/`PageSpec$Kind` member not already api 5, every `UiAction` member not already api 5, `StatusItem.set(String)` and the other api 2 `StatusItem` methods, `Net.query`.
  - api 3: `Ui.carMenuRow(String,Page,Icon)`, `Ui.statusItem(String,Icon)`, `Ui.icon(String)`, `Ui.stockIcon`, `StatusItem.set(String,Icon)`, `StockIcons.isKnown`.
  Exact descriptors come from `javap -s` on `api/build/classes/java/main`.

- [ ] **Step 1: Write the failing tests**
  - `ApiLevelsTest.api2And3Members`: `since("mibsdk/api/Ui","statusItem","(Ljava/lang/String;)Lmibsdk/api/StatusItem;") == 2`; `since("mibsdk/api/Ui","stockIcon","(Ljava/lang/String;)Lmibsdk/api/Icon;") == 3`; `since("mibsdk/api/Net","query",<desc>) == 2`.
  - `ApiLevelsTest.everySinceTagHasAnEntry`: scan `<repoRoot>/api/src/main/java/mibsdk/api/*.java` for `@since api N` (N ≥ 2). Take the member name from the first following non-comment line, the identifier before `(` (or the constant name for an enum constant). Assert some table entry with that owner simple name and member name has level N. Skip compile-time constants: `static final` primitives/strings are inlined, as the `ApiLevels` class comment says.
  - `PermissionScanTest.daemonStatusAtApi1IsRefused`: the daemon-status classes (fixture copy) with declared api 1 give `tooNew(1)` entries for `Ui.statusItem` (api 2) and `Ui.stockIcon` (api 3).

- [ ] **Step 2: Run** `./gradlew :build-logic:test --tests '*ApiLevelsTest*' --tests '*daemonStatusAtApi1IsRefused'`. Expected: FAIL.

- [ ] **Step 3: Implement** the entries and the `@since api 2` tags.

- [ ] **Step 4: Run** `./gradlew check`. If a sample now fails with `needs api N` (`hello` declares api 1, `crashy` api 2), the sample's `"api"` is raised, never the table. Sample versions get a patch bump. Then run the gates.

- [ ] **Step 5: Docs.** README api-levels table ("the build enforces every level"). Bump 9 status.

- [ ] **Step 6: Commit** `feat(build): api-level scan covers api 2 and 3`.

### Task 10: Public UI limits and a build-time status-icon warning

**Files:**
- Modify: `api/src/main/java/mibsdk/api/Ui.java` (constants + Javadoc), `runtime/src/main/java/mibsdk/runtime/UiImpl.java:67-69`, `PermissionScan.java`/`PermissionScanTask.java`, `MibPluginPlugin.java` (pass icon sizes)
- Test: `api/src/test/java/mibsdk/api/UiLimitsTest.java` (new), `PermissionScanTest.java`

**Interfaces:**
- Produces:
  - `Ui.MAX_PAGES = 8`, `Ui.MAX_CAR_ROWS = 2`, `Ui.MAX_STATUS_ITEMS = 1` (public interface constants, inlined, so no `ApiLevels` entry). `Ui.page` and `Ui.carMenuRow` Javadoc name them, plus `StatusItem` text's 12-character limit. `UiImpl` uses `Ui.MAX_*` and drops its own three fields.
  - `PermissionScanTask.getIconSizes()` (`MapProperty<String,Integer>` name→max(w,h), from `PngCheck` in `MibPluginPlugin`). When the classes call `Ui.statusItem(String,Icon)`, `StatusItem.set(String,Icon)` or `Ui.toast`, and the manifest declares icons but none ≤ `StatusItem.ICON_MAX_PX` (20), the task logs this warning (not a failure): `mibsdk.plugin: <site> passes an icon to the status bar, but no declared icon is 20x20 or smaller; a larger one throws at runtime`.

- [ ] **Step 1: Write the failing tests:** `UiLimitsTest.constants` (8, 2, 1); `PermissionScanTest.statusIconWarningWhenNoSmallIcon`, which returns the warning line through `Result.warnings(Map<String,Integer> iconSizes)`; `PermissionScanTest.noWarningWithA20pxIcon`.

- [ ] **Step 2: Run** them. Expected: FAIL.

- [ ] **Step 3: Implement.**

- [ ] **Step 4: Run** the tests and the gates (the runtime tests that hit `already has 2 Car rows` must still pass). Expected: PASS.

- [ ] **Step 5: Docs.** In `first-plugin.md`, the limits section uses `Ui.MAX_*`. Bump 10 status.

- [ ] **Step 6: Commit** `feat(api): public UI count limits; build warns on oversized status icons`.

### Task 11: `linkCheck` names the site, the JDK name and a hint

**Files:**
- Create: `build-logic/src/main/java/mibsdk/build/LinkCheckHints.java`
- Modify: `LinkCheck.java` (record sites), `LinkCheckTask.java` (message)
- Test: `LinkCheckTest.java`, `LinkCheckHintsTest.java` (new)

**Interfaces:**
- Produces:
  - `LinkCheck.Result.sites()` → `Map<String, List<String>>`: each `missing()` line → sorted, de-duplicated `owner.method` names of the checked-code methods that reference it directly (empty for `via`/header lines). `missing()` is unchanged.
  - `LinkCheckHints.jdkName(String internalName)`: `edu/emory/mathcs/backport/java/util/concurrent/X` → `java/util/concurrent/X`; `net/sf/retrotranslator/runtime/java/lang/_String` → `java/lang/String` (and the same `_X` → `X` rule under `net/sf/retrotranslator/runtime/`); anything else unchanged.
  - `LinkCheckHints.hint(String missingLine)` → one line or null, for: `java/lang/String.matches`/`replaceAll`/`replaceFirst`/`split` → `no regex on the car: use indexOf/substring or a loop`; `java/util/concurrent/` → `no java.util.concurrent: the plugin runs on one SDK worker, so plain fields need no locks`; `java/util/function/` → `compat has only some java.util.function interfaces; see compat/src/main/java/mibsdk/compat/java/util/function`.
  - The `LinkCheckTask` failure message and report are one block per missing line:
    ```
    LinkCheck: 1 reference(s) the car cannot resolve:
      member java/lang/String.matches(Ljava/lang/String;)Z
        used by tutorial/coffeetimer/CoffeeTimerPlugin.start
        hint: no regex on the car: use indexOf/substring or a loop
    ```
    When `jdkName` differs, the first line gets ` (java/util/concurrent/ConcurrentHashMap in your source)`.

- [ ] **Step 1: Write the failing tests:** `LinkCheckTest.sitesNameTheReferencingMethod` (a fixture class `a/B.run` calling `String.matches` → `sites().get(line) == [a/B.run]`); `LinkCheckHintsTest.backportNamesMapBack`, `.stringFormatMapsBack` and `.regexHint`; `MibPluginPluginFunctionalTest.linkCheckFailureShowsSiteAndHint` (TestKit fixture plugin using `"x".matches("y")`; output contains `used by` and `hint: no regex`).

- [ ] **Step 2: Run** them. Expected: FAIL.

- [ ] **Step 3: Implement.** Sites are recorded in `CheckingClassVisitor` (the current method name is known in `visitMethod`).

- [ ] **Step 4: Run** the tests and the gates. Expected: PASS.

- [ ] **Step 5: Docs.** Update the `first-plugin.md` "what you can't do" / debugging section with the new output. Bump 8 status.

- [ ] **Step 6: Commit** `feat(build): linkCheck reports the referencing method, the source name and a hint`.

### Task 12: Small fixes: compat functions, `mibpkgPackage`, manifest message, `mibsdk-dev`, quiet Retrolambda

**Files:**
- Create: `compat/src/main/java/mibsdk/compat/java/util/function/{BinaryOperator,UnaryOperator,BiConsumer,BiPredicate,IntFunction,IntPredicate,IntUnaryOperator,ToIntFunction,IntSupplier,BooleanSupplier}.java`
- Modify: `MibPluginPlugin.java` (task names), `PluginJson.java:70` (message), `BackportTask.java` (log level), `installer/mibsdk-dev`, `installer/test_mibsdk_dev.py`, `plugins/{hello,crashy}/src/main/resources/plugin.json` (drop `"dependencies": []`)
- Test: `MibPluginPluginFunctionalTest.java`, `installer/test_mibsdk_dev.py`

**Interfaces:**
- Produces:
  - The compat interfaces copy the existing raw Java 1.4 style (`public interface BinaryOperator extends BiFunction {}`, `UnaryOperator extends Function {}`, `IntFunction { Object apply(int v); }`, …), with the single abstract method of the JDK interface, erased.
  - Plugin task `mibpkgPackage` (canonical). `mibPackage` stays as an alias that depends on it and logs `mibPackage is renamed mibpkgPackage; the old name goes in 0.4.0`.
  - Missing-manifest message: `(id, name, version, author, api, main; optional: permissions, icons)`.
  - `mibsdk-dev`: no `DEFAULT_HOST`. The host is the last argument, else `$MIBSDK_HOST`, else `die "no host: pass it last or set MIBSDK_HOST"`. The minimum mibpkg is `0.9.1` (usage text, the check at line ~104, and line 4's comment). `restart` usage reads `restart the HMI (on a bench unit this resets the whole unit, 2-3 min)`.
  - `BackportTask` runs Retrolambda/Retrotranslator with output at `LogLevel.INFO` (shown only with `--info`). Warnings and errors still show.

- [ ] **Step 1: Write the failing tests:** `MibPluginPluginFunctionalTest.binaryOperatorLambdaLinks` (fixture lambda `BinaryOperator<Integer> add = (a, b) -> a + b;` → `mibPluginJar` succeeds); `.mibpkgPackageIsTheTaskName` (`tasks --all` lists both, and running `mibPackage` prints the rename line); `test_mibsdk_dev.TestHost.test_no_host_fails` / `.test_env_host_used` / `.test_mibpkg_0_9_0_refused`; `.quietBackport` (a default build's output has no `Retrolambda` line).

- [ ] **Step 2: Run** `./gradlew :build-logic:test --tests '*binaryOperator*' --tests '*mibpkgPackage*' --tests '*quietBackport*'` and `python3 -m unittest installer.test_mibsdk_dev`. Expected: FAIL.

- [ ] **Step 3: Implement.**

- [ ] **Step 4: Gradle 9 deprecations:** run `./gradlew check --warning-mode all`. Fix each deprecation the build's own scripts and build-logic cause (third-party plugin ones are listed in the commit message, not fixed). Expected: no `Deprecated Gradle features` line caused by repo code.

- [ ] **Step 5: Run** the tests and the gates. Expected: PASS.

- [ ] **Step 6: Docs.** Use `mibpkgPackage` and `MIBSDK_HOST` everywhere in README/`first-plugin.md`. Bumps 16, 21, 22 (message part), 23, 25 and 7 (host part) status.

- [ ] **Step 7: Commit** `fix: compat function interfaces, mibpkgPackage, mibsdk-dev host and mibpkg 0.9.1, quieter build`.

### Task 13: API docs: threading model, lifecycle, limits, UI behaviour

**Files:**
- Create: `api/src/main/java/mibsdk/api/package-info.java`
- Modify: `api/src/main/java/mibsdk/api/{Scheduler,MibPlugin,Features,Permissions}.java`

**Interfaces:**
- Produces Javadoc only, no signature changes:
  - `package-info` covers: one serial worker per plugin (everything a plugin's code runs: `onEnable`, actions, timers, events, answers; no locks needed between them); the lifecycle including "`onDisable` also runs after `onEnable` threw, so guard fields that may still be null"; quarantine (3 failures in 60 s; slow 5 s, hung 30 s, values from `RuntimeConfig`); inert handles; the UI behaviours from bump 18 as a bullet list; records not supported (Jabel's `Must be annotated with @Desugar`).
  - `Scheduler` class comment: "on the plugin's single SDK worker thread". Every `Features` and `Permissions` constant gets a one-line comment, each permission naming the methods that need it (Task 8's table).

- [ ] **Step 1: Run** `./gradlew :api:javadoc 2>&1 | grep -c warning` and record the count (100 today, capped).

- [ ] **Step 2: Write** the docs.

- [ ] **Step 3: Verify** `./gradlew :api:javadoc` produces no warning for `package-info.java`, `Scheduler`, `MibPlugin`, `Features` or `Permissions` (`grep -E 'package-info|Scheduler|MibPlugin|Features|Permissions'` on the output is empty). Check that the threading values match `RuntimeConfig` (`grep -n "5000\|30000\|60000" runtime/src/main/java/mibsdk/runtime/RuntimeConfig.java`).

- [ ] **Step 4: Run** the gates.

- [ ] **Step 5: Docs.** `first-plugin.md` links the package doc for the threading model. Bumps 11 (partial: the class warnings in `Ui`/`PageSpec`/`Net` remain, listed), 14, 15 and 18 (text part) status.

- [ ] **Step 6: Commit** `docs(api): threading model, lifecycle, limits and UI behaviour in the package doc`.

### Task 14: `newPlugin` scaffolding task

**Files:**
- Modify: root `build.gradle.kts`
- Test: root `standalonePluginCheck` (extended)

**Interfaces:**
- Consumes: Task 5/7 template.
- Produces: `./gradlew newPlugin -Pid=<id> -Pdir=<path> [-Ppackage=<java.package>]`. It copies `templates/plugin` to `<path>`, replacing `my-plugin` with `<id>`, `example.myplugin` with the package (default `plugin.<id without dashes>`) and `MyPlugin` with the PascalCase id. It sets `mibsdk.repo` in the new `gradle.properties` to this checkout's `build/repo` URI and writes a `.gitignore`. It fails when `<path>` exists and is non-empty, or when the id breaks `PluginJson`'s id rule, reusing the same message.

- [ ] **Step 1: Write the failing check:** change `standalonePluginCheck` to generate its project with `newPlugin -Pid=check-plugin -Pdir=build/standalone-check` instead of a plain copy, then build and test it. Expected: FAIL (task missing).

- [ ] **Step 2: Implement.**

- [ ] **Step 3: Run** `./gradlew standalonePluginCheck`, then `./gradlew newPlugin -Pid=Bad_Id -Pdir=build/x`. Expected: the first PASSES; the second FAILS with the id rule message. Then run the gates.

- [ ] **Step 4: Docs.** `first-plugin.md` §1 starts with `newPlugin`. Bump 12 status.

- [ ] **Step 5: Commit** `feat(build): newPlugin scaffolds a stand-alone plugin project`.

### Task 15: Acceptance: the tutorial from a fresh clone

**Files:**
- Modify: `docs/guide/first-plugin.md`, `README.md`, `docs/guide/developer-bumps.md` (status summary table column), `CHANGELOG.md` (Unreleased section)

- [ ] **Step 1: Fresh clone:** `git clone <worktree path> $(mktemp -d)/sdk`, check out the branch, and use `GRADLE_USER_HOME=$(mktemp -d)` with no `mibsdk.*` properties. In it, run `./gradlew publishLocal`. Expected: PASS without `lsd.jar` or any stock jar.

- [ ] **Step 2: Follow `first-plugin.md` literally**, outside the clone: `newPlugin`, then each section's code, then `./gradlew test` (6 tests, matching `plugins/coffee-timer`), then `mibPluginJar` and `mibpkgPackage` (needs host `mibpkg` 0.9.1 on `PATH`). Record every place the text and reality differ.

- [ ] **Step 3: Fix** each difference in the docs. If the cause is in code, fix it with a test, under the gates. Repeat step 2 until it runs clean.

- [ ] **Step 4: Bumps summary:** add a `Status` column to the summary table (fixed / partial / deferred, with the task or the reason).

- [ ] **Step 5: CHANGELOG** Unreleased: build without `lsd.jar`, stand-alone plugins, `PluginTestHarness`, permission and api-level checks, `Ui.MAX_*`, `mibpkgPackage`, `MIBSDK_HOST`.

- [ ] **Step 6: Run** the gates. **Commit** `docs: tutorial verified from a fresh clone; bump status`.

---

## Deferred (not in this plan)

| Bump | Why deferred |
|---|---|
| 3 firmware-distance tool | Needs a mibpkg verb (`mibpkg sdk status`) reading `version_info.txt`; separate mibpkg plan |
| 7 web "developer push" | mibpkg web-interface feature; separate mibpkg plan (only the host default is fixed here) |
| 13 optional permissions | Runtime + manifest + mibpkg approval design; needs its own spec |
| 17 `String.format` | Needs a bench run to decide; bench work belongs to the other session |
| 20 events vs `vehicle.read` | Changes what an approval means (gate the events, or reword the text): owner's decision |
| 22 `dependencies` field | Message fixed in Task 12; what the field means is a runtime design question |
| 24 hooks sample, trailer fake | Nice to have; not on the "can anyone build" path |
| 11 remaining Javadoc warnings, fail-on-warnings | After Task 13 the remaining warnings are per-member; a follow-up |
| 18 screenshots | Needs the unit |
| Hosting the artifacts | Publishing anywhere but `build/repo` needs the owner's explicit go |
