# mibsdk: a plugin SDK for the Audi MHI2Q head unit

mibsdk lets you write **plugins** for the Audi MIB2 High (MHI2Q) infotainment unit, in the way Bukkit and Fabric
let you write plugins for Minecraft. You write a small plugin in modern Java (or Kotlin) against a stable API
(`mibsdk.api`). The build turns it into class files the unit's 2008-era Java VM can load. mibpkg, the unit's package
manager, installs it, and the plugin then shows up in the car:

- a row in the **Car** drawer;
- a page drawn with the HMI's own list cells, checkboxes, sliders and drop-downs;
- an item on the **status line**;
- a **toast** at the top of the screen.

A plugin never sees an Audi class. If it keeps failing, it is switched off (quarantined). It cannot block the
HMI's threads, and if the HMI crashes several boots in a row, the next boot comes up in safe mode with no plugins.

> **New here?** Read this page, then follow [Writing your first plugin](docs/guide/first-plugin.md). It builds a
> small "coffee timer" plugin, which is in [`plugins/coffee-timer`](plugins/coffee-timer). Things that are still
> awkward are listed in [Developer bumps](docs/guide/developer-bumps.md).

## Contents

- [Supported units and firmware](#supported-units-and-firmware)
- [Safety and warranty](#safety-and-warranty)
- [What a plugin can do (api 5)](#what-a-plugin-can-do-api-5)
- [How a plugin gets onto a car](#how-a-plugin-gets-onto-a-car)
- [Repository layout](#repository-layout)
- [Toolchain](#toolchain)
- [Building and testing](#building-and-testing)
- [Versioning and api levels](#versioning-and-api-levels)
- [Documentation](#documentation)

## Supported units and firmware

The SDK supports exactly one firmware build:

| | |
|---|---|
| Unit | Audi **MHI2Q** (MIB2 High, QNX 6.5), the unit mibpkg calls `MHI2Q` |
| HMI build | **`HMI_AU_MIB2_HQC_NAR_G22_C10_180803`** (variant `FMQ-P-TNSL-US-AU-MLE`) |
| How it is matched | the `HMI = ...` line of `/ifs/version_info.txt`, compared exactly (`adapter-hqc/src/main/java/mibsdk/adapter/hqc/HqcVersion.java`) |
| Package manager | mibpkg **0.9.1** or later on the unit (0.9.0 for the `HMISystem=` line and icons, 0.9.1 for icon reflections) |

The firmware check is strict. On any other HMI build, the SDK runtime finds no matching adapter. It then stays off
and loads no plugin. The SDK package also refuses to install there: its manifest names that one `hmi` build and the
`MHI2Q` unit (`build-logic/src/main/java/mibsdk/build/MpkStage.java`). The plain MHI2 (non-Q), other regions and
other VW Group units are not supported. Each of them would need a new firmware adapter (see `platform/`).

## Safety and warranty

- **This is experimental software for a car.** The SDK has been verified on a bench MHI2Q only (`docs/bench-e2e*.md`).
  As of this writing it has **not** run in a car. One car-readiness gate is still open (F7: the parking override
  against a real PDC ECU; see [`docs/car-readiness.md`](docs/car-readiness.md)).
- The SDK changes how the stock HMI boots: it adds a bundle, the DSITracer configurator, and the `HMICar=` and
  `HMISystem=` lines. All of this is on the writable app partition and needs no IFS reflash, and mibpkg's
  uninstall restores the stock files. Even so, a broken install can leave the HMI misbehaving until you recover it.
  Read the safe-mode and recovery notes in the [tutorial](docs/guide/first-plugin.md#9-debugging) before you
  install.
- mibpkg will not install the SDK while **carplay-rgi** is on the unit. Both replace some of the same stock
  classes (see `docs/research/2026-10-26-carplay-rgi-compat.md`).
- Changes like these are very likely to **void your warranty**, and a dealer software update can overwrite them.
  You use this software at your own risk. Nothing here is affiliated with or endorsed by Audi, VW or e.solutions.
- Permissions are a **seat belt, not a sandbox**. They stop accidents and make a plugin's intent visible. They do
  not stop a plugin that sets out to do harm. Install only plugins whose source you trust.
- Plugins must never distract the driver. The SDK itself adds no lockout while the car is moving.

## What a plugin can do (api 5)

A plugin is one class that extends `mibsdk.api.MibPlugin`. In `onEnable(PluginContext ctx)` it gets everything it
may touch. Each sample in `plugins/` uses some of the API:

| Area | API | Notes and limits | Sample |
|---|---|---|---|
| Lifecycle | `MibPlugin.onEnable(ctx)`, `onDisable()` | When a plugin is disabled, the SDK cancels everything it registered | all |
| Pages | `ctx.ui().page(PageSpec, Listener<UiAction>)`, `Page.update/open/cancel` | At most 8 pages per plugin and 24 entries per page. Every page is reachable from the SDK's **Plugins** menu: MENU → Plugins, or the Car drawer's Plugins row when the main-menu takeover is off | hello-menu |
| Page entries | `PageSpec.item`, `toggle` (a native checkbox), `info`, `link` | ids `[A-Za-z0-9._-]{1,32}`. Text is cut to `MAX_TITLE` 32, `MAX_LABEL` 40, `MAX_VALUE` 20 and `MAX_INFO` 80 characters | hello-menu |
| Inputs (api 5) | `PageSpec.choice`, `dropdown`, `slider`, `progress`, `item(..., Icon)`, `enabled(id, false)` | At most 8 options. At most 4 dropdown plus slider rows per page. A slider has at most 24 steps. Item icons are at most 24 px | inputs-demo |
| Car drawer | `ui.carMenuRow(label, page[, icon])` | At most **2** rows per plugin. Needs `ui.slot.carmenu` | hello-menu, bike-rack |
| Status line | `ui.statusItem(text[, icon])`, `StatusItem.set` | At most **1** per plugin and **12** characters, cut to a fair share of the line. Icon at most **20 px**. Needs `ui.slot.statusbar` | hello-menu, daemon-status |
| Icons (api 3/4) | manifest `"icons"`, `ui.icon(name[, Icon.OWN_COLOURS])`, `ui.stockIcon(StockIcons.STAR)` | At most 8 PNGs per plugin, 8-bit RGBA or grey + alpha, ≤ 72 px and ≤ 32 KiB. The build adds a drawer reflection for each | hello-menu, bike-rack |
| Dialogs (api 5) | `ui.confirm(...)`, `ui.toast(text, icon)`, `ui.speller(...)` (**experimental**) | A confirm has up to 3 lines and 120 characters. A toast has 20 characters, shows for 3 s and needs `ui.slot.statusbar`. A speller holds up to 32 characters | inputs-demo |
| Storage | `ctx.storage().get/put/remove` | String key/value pairs, kept across reboots. 256 KiB per plugin, 1 MiB for all plugins | hello-menu, inputs-demo |
| Timers | `ctx.scheduler().after/every` | Runs on the plugin's own worker thread | inputs-demo, daemon-status |
| Logging | `ctx.log().info/warn/error` | Goes to a ring buffer, mirrored to `/tmp/mibsdk.log` on the unit | all |
| Network | `ctx.net().query(host, port, request, timeoutMs)` | Loopback only, 8 KiB reply, 3 s timeout. Needs `network` | daemon-status |
| Vehicle | `ctx.vehicle().parking().soundDirections()` and `overrideSoundDirections(value, scope)` | Reading needs `vehicle.read`. An override needs `vehicle.parking.write`; the runtime's ledger restores the prior value | bike-rack |
| Events | `ctx.events().on(IgnitionChanged.class, ...)`, `ParkingSoundChanged`, `TrailerHitchedChanged`, `HookObserved` | Delivered asynchronously, after the fact | bike-rack |
| Hooks | `ctx.hooks().veto(HookPoint.PARKING_SOUND_SET)`, `observe(...)` | Declarative: plugin code never runs on a stock thread. Needs `hooks.parking` | (none) |
| Features | `ctx.has(Features.UI_INPUTS)` and others | A missing feature is not an error: handles are inert | all |
| Permissions | the manifest `"permissions"`, `ctx.granted(...)` | All or nothing: a plugin missing one grant does not run | all |

The permission catalog is [`api/permissions.json`](api/permissions.json): `vehicle.read`, `vehicle.parking.write`,
`hooks.parking`, `ui.slot.carmenu`, `ui.slot.statusbar`, `network` and `platform.unrestricted`. The last one is the
"loud" escape hatch that allows reflection and stock HMI classes.

What a plugin **cannot** do: run code on an HMI thread, see or call stock Audi classes (the plugin class loader
hides them), use reflection, `Runtime.exec` or raw sockets without `platform.unrestricted`, draw custom graphics,
add MENU-ring entries, or ship class files newer than Java 1.4 (the build converts them for you). The tutorial's
section on [what you can't do](docs/guide/first-plugin.md#10-what-you-cant-do) has the full list.

## How a plugin gets onto a car

```
 your plugin source              ./gradlew :plugins:<id>:mibPluginJar           ./gradlew :plugins:<id>:mibPackage
 (Java 17 syntax / Kotlin)  ──►  compile → backport to v48 → classVersionCheck ──► build/mibsdk/<id>-<version>.mpk
 plugin.json, icons/*.png        → linkCheck → permissionScan → build/mibsdk/      (host mibpkg build: jar + stamp +
                                 <id>.jar + <id>.jar.stamp                         icons + manifest.toml)
                                                                                         │
             ┌───────────────────────────────────────────────────────────────────────────┘
             ▼
 on the unit:  mibpkg install-file <id> ...   (web UI upload, GEM, or `installer/mibsdk-dev install-plugin`)
               → checks the stamp and the icons → asks you to approve the permissions (exit 3 / web sheet)
               → writes plugins/<id>.jar, grants.properties and icons/<id>/
               → the SDK runtime (itself the `mibsdk` mibpkg package) loads the plugin on the next reload or boot
```

1. **Build.** The `mibsdk.plugin` Gradle convention compiles your source with JDK 17 (`--release 8`, with Jabel for
   newer syntax). It then lowers the classes to class-file version 48 (Retrolambda, then Retrotranslator) and runs
   three checks:
   - `classVersionCheck`: every class is v48.
   - `linkCheck`: every reference resolves against the car's own class library.
   - `permissionScan`: no reflection or exec, and no API newer than the `"api"` you declare.

   The result is `build/mibsdk/<id>.jar` plus `<id>.jar.stamp`, which lists the checks that ran and the jar's
   SHA-256.
2. **Package.** `mibPackage` stages the jar, its stamp, your icons (with a generated `<name>.r.png` reflection
   for each icon taller than 20 px) and a `manifest.toml`. The host `mibpkg build` then packs them into
   `build/mibsdk/<id>-<version>.mpk`.
3. **Install the SDK once.** The SDK is itself a mibpkg package: `dist/build/dist/mibsdk-<version>.mpk`, from
   `./gradlew :dist:mibpkgPackage`. mibpkg installs its three jars and the permission catalog, and edits the
   bundle and configurator lines. Its uninstall restores the stock files byte for byte.
4. **Install the plugin.** Upload the `.mpk` in mibpkg's web interface, or install it from the SD card or over SSH.
   mibpkg checks the stamp and the icons. It lists the plugin's permissions in plain words and installs only once
   you approve them: the web interface shows a sheet, and the CLI exits with status 3 until you re-run it with
   `--approve=<perm,...>`.
5. **Develop faster on a bench unit.** [`installer/mibsdk-dev`](installer/mibsdk-dev) is a thin SSH wrapper around
   the unit's mibpkg. With `mibsdk-dev push build/mibsdk/<id>.jar`, the runtime hot-reloads the new jar without an
   HMI restart. Run `mibsdk-dev log` to read the log and `mibsdk-dev grant` to change grants. It defaults to the
   project's bench unit (`10.20.0.23`); give your unit's address as the last argument.

## Repository layout

```
                        ┌──────────── on the unit (dist: three v48 jars in the mibsdk .mpk) ────────────┐
 plugins/*  ──compileOnly──►  api  ◄──  platform  ◄──  runtime  ◄──  adapter-hqc ──► stock lsd.jar,
   (your code)               (mibsdk.api)  (adapter SPI,  (loader, perms,   (the MHI2Q          DSITracer.jar
                               ▲            UI table)      storage, UI)      firmware adapter)   (never committed)
                               │              ▲               ▲
                             compat           └── adapter-fake ┘  (host-only fake car for tests)
                    (Java 5+ shims for the car)
 build-logic: the Gradle plugins (mibsdk.car-library, mibsdk.plugin) that every module above is built with
```

| Path | What it is |
|---|---|
| `api/` | **The plugin API** (`mibsdk.api.*`) and `permissions.json`. This is the only module a plugin compiles against. |
| `compat/` | Java 1.4 source shims (`java.util.function.*`, `Objects`, `AutoCloseable`, ...) that Retrotranslator maps missing JDK classes onto. It ships inside the runtime jar. |
| `platform/` | The firmware adapter SPI (`PlatformAdapter`) and the UI snapshot model (`UiTable`, `UiPage`, `UiPageText`) shared by the runtime and the adapters. |
| `runtime/` | The SDK runtime (`SdkRuntime`). It loads plugins in filtered class loaders and enforces permissions. It also runs each plugin's serial executor and the scheduler, the storage and quotas, the boot guard and safe mode, quarantine, the override ledger, the Plugins menu and the dev channel (hot reload). |
| `adapter-hqc/` | The adapter for `HMI_AU_MIB2_HQC_NAR_G22_C10_180803`: the bundle activator, the configurator chain and the DSI bridges. It also draws the HMI UI (Car drawer rows, pages, popups, the status line, toasts and dialogs). It compiles against the stock jars. |
| `adapter-fake/` | A host-only fake car (`FakePlatform`) and UI driver (`FakeUi`) for unit tests. It never ships. |
| `build-logic/` | An included Gradle build with the `mibsdk.car-library` and `mibsdk.plugin` conventions, plus the backport, `LinkCheck`, `PermissionScan`, `PngCheck`, check-stamp and mibpkg packaging tasks. |
| `dist/` | Assembles the three unit jars (`mibsdk-runtime.jar`, `mibsdk-adapter-hqc.jar`, `mibsdk-rt-retrotranslator.jar`), link-checks them together against the stock jars, stamps them and packs `mibsdk-<version>.mpk`. `dist/icons/` holds the built-in icon set. |
| `installer/` | `mibsdk-dev`, the bench developer tool (bash, over SSH), and its Python tests (`test_mibsdk_dev.py`, `test_make_builtin_icons.py`). |
| `plugins/` | Sample plugins, each a Gradle subproject: `hello` (api 1, minimal), `crashy` (fails on purpose to show quarantine), `hello-menu` (a page, a Car row, a status item and icons), `daemon-status` (`net`), `bike-rack` (Kotlin; vehicle override and events), `inputs-demo` (every api-5 input) and `coffee-timer` (the tutorial). |
| `spikes/` | Throwaway bench experiments: `modern-java` (proves Java 17 and Kotlin run on J9 after backporting) and `hmi-ui` (the UI injection spikes). They are not part of the distribution. |
| `tools/` | `make_builtin_icons.py`, which regenerates `dist/icons/`, the PNG test vectors and some samples' icons, all deterministically. |
| `docs/` | The design spec, plans, research notes, bench end-to-end records, car readiness and this guide (see [Documentation](#documentation)). |

## Toolchain

| Need | Why | How it is found |
|---|---|---|
| **JDK 17** | Gradle runs on it and compiles with it; tests run on it | Gradle toolchain (`JavaLanguageVersion.of(17)`). Install any JDK 17; Gradle's toolchain detection finds it |
| **JDK 8** (Corretto 8 on the owner's machine) | Retrolambda, Retrotranslator, ProGuard (Kotlin) and the Java 1.4 compile of `compat` | `-Pmibsdk.jdk8Home=<home>`, else `Library/Java/JavaVirtualMachines/corretto-1.8.0_372/Contents/Home` under your home directory |
| **Stock `lsd.jar`** (the HMI's `lsd.jxe`, converted with jxe2jar). **Optional**: SDK maintainers only | Plugin builds do not need it: every `linkCheck` uses the car Java class library signature list bundled in the build logic (`carjcl.sigs`, names and member descriptors only, no code). With the property set, `carJcl` extracts the class library from the jar instead, and `./gradlew carJclSignatures` regenerates the bundled list. `./gradlew check` then also runs `carJclSignaturesCheck`, which fails when the bundled list is out of date. `adapter-hqc` and `dist` still compile against the jar | `-Pmibsdk.stockLsdJar=<path>`; `adapter-hqc` and `dist` fall back to `projects/audi-mib2/patching/lsd.jar` under your home directory. **Never committed** |
| **Stock `DSITracer.jar`** | Compiling `adapter-hqc` and the `dist` link check (SDK builds only) | `-Pmibsdk.stockDsiTracerJar=<path>`, else `projects/audi-mib2/firmware/mmxfs/app/eso/hmi/lsd/DSITracer.jar` under your home directory. **Never committed** |
| **Host `mibpkg`** | `mibPackage` and `:dist:mibpkgPackage` run `mibpkg build` | `-Pmibsdk.mibpkg=<path>`, else `mibpkg` on `PATH`. Build it with `cargo build --release` in the mibpkg repo (Rust toolchain) |
| Gradle 8.13 | the build | the wrapper (`./gradlew`) downloads it |
| Internet (Maven Central) | Jabel, Retrolambda, Retrotranslator, ProGuard, ASM, JUnit and the Kotlin plugin | the first build downloads them |
| Python 3 | the `installer/` tests, `tools/make_builtin_icons.py` and the tutorial's icon script | `python3` on `PATH` |
| bash, ssh | `installer/mibsdk-dev` (the unit needs `-o HostKeyAlgorithms=ssh-rsa`; the tool sets it) | on `PATH` |

Put the machine-local paths in the `gradle.properties` of your Gradle user home (`$GRADLE_USER_HOME`, by default
the `.gradle` directory in your home directory), so you do not have to pass `-P` every time:

```properties
mibsdk.jdk8Home=/path/to/jdk8/home
mibsdk.stockLsdJar=/path/to/lsd.jar   # optional; SDK maintainers only
mibsdk.stockDsiTracerJar=/path/to/DSITracer.jar
mibsdk.mibpkg=/path/to/mibpkg/target/release/mibpkg
```

The stock jars are Audi's code, so this repository cannot ship them. To get them, you dump them from your own unit
and convert `lsd.jxe` with jxe2jar. The research notes describe how (`docs/research/2026-10-18-java-onboard-map.md`).
A **plugin** build needs neither stock jar: its `linkCheck` runs against the bundled car class library list.

## Building and testing

All commands run from the repository root.

```sh
./gradlew check                                    # every module's tests and checks, plus build-logic's tests
./gradlew :plugins:hello-menu:test                 # one plugin's host tests (against adapter-fake)
./gradlew :plugins:hello-menu:mibPluginJar         # build/mibsdk/hello-menu.jar + .jar.stamp
./gradlew :plugins:hello-menu:mibPackage -Pmibsdk.mibpkg=<path-to-mibpkg>
                                                   # build/mibsdk/hello-menu-1.2.0.mpk
./gradlew :dist:assembleUnit                       # dist/build/dist: the three unit jars + mibsdk-unit.stamp
./gradlew :dist:mibpkgPackage -Pmibsdk.mibpkg=<path-to-mibpkg>
                                                   # dist/build/dist/mibsdk-0.3.0.mpk (the SDK package)
python3 -m unittest discover -s installer -p 'test_*.py'
                                                   # mibsdk-dev and icon-tool tests (no device needed)
installer/mibsdk-dev                               # usage; every command but log/restart talks to the unit's mibpkg
```

`check` runs every module's `classVersionCheck`, `linkCheck` and `permissionScan`, and their reports are under
`<module>/build/reports/mibsdk/`. It needs both stock jars.

## Versioning and api levels

There are three version numbers:

- **The SDK version** (`mibsdk.version` in `gradle.properties`, now **0.3.0**) is the version of the `mibsdk` mibpkg
  package. It is semver as mibpkg compares it. Bench builds carried a `+bench.N` tag and were not releases. See
  [CHANGELOG.md](CHANGELOG.md).
- **A plugin's version** (`"version"` in `plugin.json`, semver `1.2.3` or `1.2.3-tag`) becomes its package version.
  mibpkg uses it to order updates.
- **The api level** (`"api"` in `plugin.json`, an integer) is the newest plugin API the plugin uses.
  `mibsdk.api.SdkInfo.API_VERSION` is the newest level this SDK implements and `MIN_API_VERSION` is the oldest it
  still loads.

| api | Arrived in | What it added |
|---|---|---|
| 1 | SDK 0.1.0 (bench builds only) | `MibPlugin` lifecycle, `PluginContext`, `Log`, `Storage`, `Scheduler`, `Events` (ignition, parking sound, trailer, hook observations), `Hooks` (parking veto/observe), `Vehicle`/`Parking` with the override ledger, `Permissions`, `Features` |
| 2 | SDK 0.1.0 | Declarative UI: `Ui.page`, `PageSpec` (`item`, `toggle`, `info`, `link`), `Page`, `UiAction`, `Ui.carMenuRow`, `Ui.statusItem`/`StatusItem`, and `Net` (loopback queries) |
| 3 | SDK 0.2.0 | Icons: the manifest `"icons"` (at most 8 PNGs), `Ui.icon(name)`, `Ui.stockIcon` and `StockIcons`, and the icon overloads of `carMenuRow`, `statusItem` and `StatusItem.set` |
| 4 | SDK 0.2.0 | `Ui.icon(name, colour)` with `Icon.TINTED` / `Icon.OWN_COLOURS` (status icons in their own colours) |
| 5 | SDK 0.3.0 | Native inputs: `PageSpec.choice`, `dropdown`, `slider`, `progress`, item icons, `enabled`; `UiAction.value()`/`text()`; `Ui.confirm`, `Ui.toast`, `Ui.speller` (experimental); `Features.UI_INPUTS` |

Compatibility rules:

- **Levels are additive.** A newer SDK runs plugins of every older level unchanged. SDK 0.3.0 loads api 1 to 5,
  and 0.2.0 loads api 1 to 4.
- **Declare the lowest level that covers what you use.** The build refuses a plugin that uses an api-4 or api-5
  member while it declares a lower level. It names each call site
  (`... -> PageSpec.slider needs api 5; plugin.json declares api 4`). Members from api 2 and 3 are **not** in the
  scan's table (`build-logic/src/main/java/mibsdk/build/ApiLevels.java`): the scan does not catch them, and the
  only api-3 check is on `"icons"`.
- **mibpkg installs a plugin only when** `sdk.min_api <= plugin.api <= sdk.api` for the SDK on the unit. An api-5
  plugin needs SDK 0.3.0, and mibpkg refuses it on 0.2.0.
- **Features are separate from api levels.** An api level says what the API has, and `ctx.has(Features.X)` says
  what this unit can draw at this boot. When a feature is missing, the handles are inert: pages fall back to text
  lines, and confirm answers No.
- A plugin's `"api"` is not raised for you. The build tells you when you need a higher level.

## Documentation

| Document | What is in it |
|---|---|
| [docs/guide/first-plugin.md](docs/guide/first-plugin.md) | **Start here**: a hands-on tutorial from nothing to an installed plugin |
| [docs/guide/developer-bumps.md](docs/guide/developer-bumps.md) | The rough edges a newcomer hits, by severity, with suggested fixes |
| [docs/superpowers/specs/2026-10-18-mib2q-plugin-sdk-design.md](docs/superpowers/specs/2026-10-18-mib2q-plugin-sdk-design.md) | The design spec: goals, constraints of the J9 VM, containment, permissions, API surface |
| [docs/superpowers/plans/](docs/superpowers/plans/) | The implementation plans (foundation, UI, mibpkg distribution, car readiness, menu and icons, icon colour, native inputs) |
| [docs/research/](docs/research/) | Research notes: the onboard Java map (how the HMI runs and how to inject into it), HMI UI injection, the main menu and icons, icon colour and reflections, native inputs, and carplay-rgi compatibility |
| [docs/bench-e2e.md](docs/bench-e2e.md) and `docs/bench-e2e-*.md` | Bench end-to-end records, with screenshots in `docs/bench-e2e/`: foundation, UI, release 0.2, menu icons, icon colour, native inputs |
| [docs/car-readiness.md](docs/car-readiness.md) | The gates that must close before a car install, and known follow-ups |
| [docs/car-session-runbook.md](docs/car-session-runbook.md) | The runbook for the first car session |
| [CHANGELOG.md](CHANGELOG.md) | SDK and sample-plugin releases |
| The mibpkg README (in the mibpkg repo) | Packages, `[plugin]` and `[sdk]` manifests, icons, approval, the `mibpkg sdk` verbs |
