# mib2q plugin SDK

**Write plugins for the MIB2 High head unit in modern Java or Kotlin.**

[![Docs](https://github.com/complex256/mib2q-plugin-sdk/actions/workflows/docs.yml/badge.svg)](https://complex256.github.io/mib2q-plugin-sdk/)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

The MIB2 High runs a 2008-era IBM J9 Java VM inside a QNX head unit. This SDK gives it a small plugin
platform in the spirit of Bukkit or Fabric: you write ordinary Java against a stable API, and the SDK
handles class-file backporting, loading, permissions, isolation, UI and recovery.

A plugin can add a row to the **Car** drawer, draw a page with the HMI's own cells and inputs, show an
item on the **status line**, pop a toast, read vehicle state, and observe or veto parking-sound changes.
It never sees a stock HMI class, never runs on an HMI thread, and is quarantined if it keeps failing.

![The SDK's Plugins page](docs/assets/screenshots/plugins-page.jpg)

## Quick start

You need a JDK 17, a JDK 8 (Gradle can download one) and, for packaging, the host
[`mibpkg`](https://github.com/complex256/mibpkg) binary. No stock HMI files are needed to build a plugin.
Full details are in
[Prerequisites](https://complex256.github.io/mib2q-plugin-sdk/getting-started/prerequisites/).

```sh
git clone https://github.com/complex256/mib2q-plugin-sdk.git
cp -R mib2q-plugin-sdk/templates/plugin my-plugin
cd my-plugin

./gradlew test                 # runs the plugin on the real runtime and a fake car
./gradlew mibPluginJar         # build/mibsdk/my-plugin.jar
./gradlew mibpkgPackage        # build/mibsdk/my-plugin-1.0.0.mpk
```

Then install the SDK once, install your `.mpk` with mibpkg, and approve its permissions. The
[tutorial](https://complex256.github.io/mib2q-plugin-sdk/getting-started/tutorial/) walks through the
whole path with a working coffee-timer plugin.

The smallest useful plugin is one class:

```java
package example.myplugin;

import mibsdk.api.MibPlugin;
import mibsdk.api.PluginContext;

public class MyPlugin extends MibPlugin {
    @Override
    protected void onEnable(PluginContext ctx) {
        ctx.log().info("my-plugin enabled");
    }
}
```

## What you can build

- **Pages** built from native list cells, checkboxes, sliders, dropdowns and progress rows.
- **Car-drawer rows** that open your pages, with your own PNG icons.
- **Status-line items** with text, icons, colours and per-screen visibility.
- **Toasts and dialogs** using the HMI's own widgets.
- **Background work** with a per-plugin scheduler, persistent storage and TCP networking: loopback by
  default, outbound with an extra permission.
- **Vehicle integrations** that read parking state, observe ignition and trailer events, or hold a
  parking-sound override that the SDK restores afterwards.

Each plugin declares the capabilities it needs. The user approves them at install time, and the build
refuses code that uses an undeclared permission.

## Supported hardware

| | |
|---|---|
| Unit | **MHI2Q** (MIB2 High, QNX), the unit mibpkg calls `MHI2Q` |
| HMI build | **`HMI_AU_MIB2_HQC_NAR_G22_C10_180803`** |
| Package manager | mibpkg **0.9.1** or later |
| Plugin API | 1 through 7 (SDK 0.6.2) |

The firmware check is strict: on any other HMI build the SDK stays off and loads no plugins. Other MHI2
variants, regions and other vehicle platforms need a new firmware adapter. See
[Firmware support](https://complex256.github.io/mib2q-plugin-sdk/explanation/firmware-support/).

!!! warning "Experimental software for a car"
    This SDK has been verified on a bench MHI2Q, not yet on a car. It changes how the stock HMI boots,
    and a broken install can leave the HMI misbehaving until you recover it. Read the
    [safety model](https://complex256.github.io/mib2q-plugin-sdk/explanation/safety-model/) before
    installing. Installing this software is likely to void your warranty, and nothing here is affiliated
    with or endorsed by the vehicle or head-unit manufacturers.

## Documentation

| Start here | |
|---|---|
| [Getting started](https://complex256.github.io/mib2q-plugin-sdk/getting-started/) | Install the SDK, create a project, build and test |
| [Tutorial](https://complex256.github.io/mib2q-plugin-sdk/getting-started/tutorial/) | Build the coffee-timer plugin step by step |
| [Project layout](https://complex256.github.io/mib2q-plugin-sdk/getting-started/project-layout/) | What lives in a plugin, and what ships to the unit |

| Reference | |
|---|---|
| [plugin.json](https://complex256.github.io/mib2q-plugin-sdk/reference/plugin-manifest/) | Every manifest field |
| [Limits](https://complex256.github.io/mib2q-plugin-sdk/reference/limits/) | Pages, entries, icons, text and storage |
| [Permissions](https://complex256.github.io/mib2q-plugin-sdk/reference/permissions/) | The catalog and the approval model |
| [Java API](https://complex256.github.io/mib2q-plugin-sdk/api/) | Generated API reference |
| [Troubleshooting](https://complex256.github.io/mib2q-plugin-sdk/reference/troubleshooting/) | States, logs, safe mode, common errors |

## Repository layout

```
api/              the plugin API (mibsdk.api.*) and permissions.json
compat/           Java 1.4 shims for the car's class library
platform/         firmware-adapter SPI and the UI snapshot model
runtime/          loader, permissions, storage, scheduler, UI, safe mode
adapter-hqc/      the adapter for the supported HMI build
adapter-fake/     host-only fake car for tests
plugin-test/      PluginTestHarness for plugin host tests
docs-api/         documentation-only module that aggregates the Dokka reference
build-logic/      the mibsdk.plugin and mibsdk.car-library Gradle plugins
dist/             the three unit jars and the SDK .mpk
templates/plugin/ the standalone plugin template
plugins/          SDK samples: hello, crashy, coffee-timer
installer/        mibsdk-dev, the SSH bench tool
docs/             this documentation
```

The SDK ships as three jars and a package: `mibsdk-runtime.jar`, `mibsdk-adapter-hqc.jar` and
`mibsdk-rt-retrotranslator.jar`, installed as the `mibsdk` mibpkg package. Uninstalling it restores the
stock HMI files.

## Building the SDK

```sh
./gradlew check                  # all modules, checks and the standalone template build
./gradlew publishLocal           # publish artifacts and Gradle plugins to build/repo
./gradlew :dist:mibpkgPackage    # dist/build/dist/mibsdk-<version>.mpk
```

SDK development also needs the stock `lsd.jar` and `DSITracer.jar`; plugin development needs neither.
See [Build and test](https://complex256.github.io/mib2q-plugin-sdk/contributing/build-and-test/).

## Status

- API 7, SDK 0.6.2. See [CHANGELOG.md](CHANGELOG.md).
- Verified on a bench unit. One car-readiness gate (parking override against a real PDC ECU) remains open.
- Not compatible with some third-party phone-projection mods: both replace some of the same stock classes.

## Contributing

Bug reports, plugin ideas and documentation fixes are welcome. Open an issue or a pull request. By
contributing you agree that your changes are licensed under Apache-2.0.

Nothing here is affiliated with or endorsed by the vehicle or head-unit manufacturers.
