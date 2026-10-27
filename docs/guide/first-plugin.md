# Writing your first plugin

This tutorial takes you from nothing to a working mibsdk plugin: **coffee-timer**. It adds a "Coffee" row with a
cup icon to the Car drawer. The row opens a page where you set a brew time with a slider and switch a "pop up when
ready" toggle. Both settings survive a reboot. While a timer runs, the status line counts down ("Coffee 3m"). When
it ends, the status line says "Coffee ready" and a toast pops up.

Along the way you will:

- set up the project;
- write `plugin.json`;
- draw two icons;
- write the plugin class;
- test it on your computer against the SDK's fake car;
- build the stamped jar and the `.mpk` package;
- install it and approve its permissions.

The last sections cover debugging and the rules the SDK enforces.

The finished plugin is in [`plugins/coffee-timer`](../../plugins/coffee-timer). Every file in this tutorial is
the same as the one there. If you get stuck, compare the two.

Read the [README](../../README.md) first, at least the supported firmware, safety and toolchain sections.

## Contents

1. [Set up the project](#1-set-up-the-project)
2. [The manifest: plugin.json](#2-the-manifest-pluginjson)
3. [Icons](#3-icons)
4. [The plugin class](#4-the-plugin-class)
5. [Build the jar](#5-build-the-jar)
6. [Test it on your computer](#6-test-it-on-your-computer)
7. [Package it](#7-package-it)
8. [Install it](#8-install-it)
9. [Debugging](#9-debugging)
10. [What you can't do](#10-what-you-cant-do)

## 1. Set up the project

### 1.1 Create the project

A plugin is a Gradle project in **its own repository**. The `mibsdk.plugin` plugin takes the SDK (the `api` jar, the
`compat` shims and the car class files it links against) from a Maven repository, so you do not clone the SDK into
your project — and you do not copy files by hand either: the checkout's `newPlugin` task scaffolds the project.

Until the artifacts are hosted, you publish them from an SDK checkout to a local file repository. Do this once, and
again whenever you update the checkout:

```sh
git clone <this repository> mibsdk
cd mibsdk
./gradlew publishLocal
```

That writes `build/repo` inside the checkout. Then scaffold your plugin from the SDK's template, with the SDK's
`newPlugin` task:

```sh
./gradlew newPlugin -Pid=coffee-timer -Pdir=../coffee-timer -Ppackage=tutorial.coffeetimer
```

- `-Pid` is the id `plugin.json` will carry: 2 to 40 characters of `a-z`, `0-9` and `-`. The task refuses an id the
  build refuses, with the same sentence.
- `-Pdir` is where the project goes. It must not exist yet, or be empty: the task does not write into a directory that
  already has files in it, and it never leaves half a project behind.
- `-Ppackage` is the Java package. Without it you get `plugin.<id without dashes>` (`plugin.coffeetimer` here); this
  tutorial uses `tutorial.coffeetimer`, so its files read the same as the sample's.

The task copies `templates/plugin` and rewrites the template's names to yours: `my-plugin` becomes the id,
`example.myplugin` the package, and `MyPlugin` the class named after the id — `CoffeeTimer` for this id. It also
writes the two things the template cannot know:

- `gradle.properties` gets `mibsdk.repo` set to this checkout's `build/repo` as an absolute `file:` URL, taken from
  the build's own layout when the task runs. It is the only machine-specific line: the path is written from where
  the checkout sits at that moment, so it differs per machine and is under your home directory if the checkout is;
- `.gitignore`, covering `build/`, `.gradle/` and `.kotlin/`.

Everything else — the wrapper, the build file, the manifest, the class and its test — is the template's.

Build the new project once, before you change anything:

```sh
cd ../coffee-timer
./gradlew mibPluginJar
```

You should see `BUILD SUCCESSFUL`, and `build/mibsdk/coffee-timer.jar` should exist.

- `mibsdk.repo is not set` means `mibsdk.repo` is still empty: a project copied by hand from `templates/plugin` does
  not get it, only a scaffolded one does.
- `Plugin [id: 'mibsdk.plugin', version: '...'] was not found` means the URL does not point at the `build/repo` of a
  checkout where `./gradlew publishLocal` has run, or the checkout's `mibsdk.version` differs from the version in your
  `build.gradle.kts`.
- `The configured executable does not exist (.../bin/javac)` means the JDK 8 path is wrong.

### 1.2 Toolchain

You need everything in the README's [Toolchain](../../README.md#toolchain) table **except** the stock
`DSITracer.jar` and the stock `lsd.jar`. A plugin build uses neither: `linkCheck` runs against a car class library
list that ships with the build logic. In short, you need:

- a JDK 17 (Gradle finds it);
- a JDK 8 (Gradle's toolchain detection finds an installed one, and downloads one if there is none; set
  `mibsdk.jdk8Home` only to pick a specific JDK 8);
- a host `mibpkg` binary, for packaging only;
- Python 3, for the icon script below.

Put the paths in the `gradle.properties` of your Gradle user home (`$GRADLE_USER_HOME`, by default the `.gradle`
directory in your home directory). Then every command below works without `-P` flags:

```properties
mibsdk.mibpkg=/path/to/mibpkg/target/release/mibpkg
```

> The build has no default under your home directory. If `mibpkg` is not on `PATH`, set `mibsdk.mibpkg`. If a
> property you set points at a missing file, the build fails with a message that names the property.

### 1.3 What the project contains

```
build.gradle.kts                          plugins { id("mibsdk.plugin") version "..." }
settings.gradle.kts                       the repositories (mibsdk.repo, Gradle plugin portal, Maven Central)
gradle.properties                         mibsdk.repo, written by newPlugin
gradlew, gradlew.bat                      the wrapper, so ./gradlew works in the new project
gradle/wrapper/                           the wrapper jar and its properties
src/main/resources/plugin.json            the manifest
src/main/java/tutorial/coffeetimer/CoffeeTimer.java
src/test/java/tutorial/coffeetimer/CoffeeTimerTest.java
.gitignore
```

The layout is fixed by the build:

- `src/main/resources/plugin.json` is the manifest.
- `src/main/icons/<name>.png` holds one PNG per icon the manifest declares. Create the directory when you need it.
- The Java sources go under `src/main/java`. Host tests go under `src/test/java`.

Create the icons directory this tutorial adds:

```sh
mkdir -p src/main/icons
```

The rest of this tutorial writes paths and tasks as they are in the SDK repository's finished sample,
`plugins/coffee-timer`. In your own project, drop the `plugins/coffee-timer/` prefix from paths and the
`:plugins:coffee-timer:` prefix from task names: `./gradlew :plugins:coffee-timer:mibPluginJar` is
`./gradlew mibPluginJar` there.

### 1.4 The manifest and the class replace the scaffold's

Sections [2](#2-the-manifest-pluginjson) and [4](#4-the-plugin-class) write the `plugin.json` and the plugin class
that replace the scaffolded ones. The generated class is named after the id (`CoffeeTimer`); the tutorial's is
`CoffeeTimerPlugin`, the name the sample uses, so write that name in the manifest's `main`, in the class and in its
test. **Delete the scaffolded `src/main/java/tutorial/coffeetimer/CoffeeTimer.java` and its
`src/test/java/tutorial/coffeetimer/CoffeeTimerTest.java`** as well: they test the scaffold, not your plugin, and
leaving them in makes the `test` run count seven tests, because the scaffold's own test still runs. There is nothing
to register in any other build: the project builds on its own.

### 1.5 The build file

The whole build file is one `plugins` block. In your own project it is the template's `build.gradle.kts`, which has the
same block with the version (`id("mibsdk.plugin") version "0.3.0"`); keep the version, because without it Gradle
cannot find the plugin in your repository. The finished sample, a subproject of the SDK repository, has no version,
because the SDK's own build provides the plugin:

<!-- file: plugins/coffee-timer/build.gradle.kts -->
```kotlin
plugins {
    id("mibsdk.plugin")
}
```

`id("mibsdk.plugin")` does all the real work:

- It compiles your code against the SDK's `api` jar only, as `compileOnly`.
- It lowers the classes to Java 1.4, then checks and packs them.
- It sets up the host test of section 6, in the SDK repository and in your own project alike: it puts JUnit and the
  SDK's test harness (`PluginTestHarness`, with the real runtime and the fake car behind it) on the test classpath,
  and passes the path of the built jar and of your icons to the test. A `test` run builds the jar first, and runs again
  when the jar changes. None of this goes into the plugin jar.

> Kotlin works as well: add `id("org.jetbrains.kotlin.jvm")` before `id("mibsdk.plugin")`. See
> `plugins/bike-rack/build.gradle.kts`, which also sets the test compile's `jvmTarget` to 17.

## 2. The manifest: plugin.json

Create `plugins/coffee-timer/src/main/resources/plugin.json`:

<!-- file: plugins/coffee-timer/src/main/resources/plugin.json -->
```json
{
  "id": "coffee-timer",
  "name": "Coffee Timer",
  "version": "1.0.0",
  "author": "your-name",
  "api": 5,
  "main": "tutorial.coffeetimer.CoffeeTimerPlugin",
  "permissions": ["ui.slot.carmenu", "ui.slot.statusbar"],
  "icons": ["cup", "cup-20"]
}
```

| Field | Rules |
|---|---|
| `id` | `[a-z0-9][a-z0-9-]{1,39}`, so 2 to 40 characters. It names the jar (`coffee-timer.jar`), the mibpkg package, your storage file and your icon directory. `mibsdk` is reserved |
| `name` | Shown in the Plugins menu and in mibpkg |
| `version` | semver: `1.0.0` or `1.0.0-beta.1`. mibpkg uses it to order updates, so raise it for every release |
| `author` | free text. The runtime reads it and the build ignores it |
| `api` | the plugin API level you use: an integer from 1 to 5. This plugin uses a slider and a toast, so it needs **5**. The build refuses a lower number and names each call that needs more (see [section 10](#10-what-you-cant-do)) |
| `main` | the fully qualified class that extends `MibPlugin` |
| `permissions` | what the plugin may do. It needs `ui.slot.carmenu` for the Car-drawer row and `ui.slot.statusbar` for the status item **and** the toast. The catalog is `api/permissions.json` |
| `icons` | (api 3+) up to 8 names matching `[a-z0-9-]{1,32}`. Each needs `src/main/icons/<name>.png` |

The build checks your manifest three ways:

- **Names are validated.** A typo such as `ui.slot.statusbr` fails the build and suggests the nearest name:
  `unknown permission "ui.slot.statusbr" (did you mean "ui.slot.statusbar"?); known: ...`.
- **Use is checked against what you declare.** `permissionScan` knows which API calls need which permission
  (`Ui.carMenuRow` needs `ui.slot.carmenu`, `Ui.statusItem` and `Ui.toast` need `ui.slot.statusbar`,
  `PluginContext.net` needs `network`, `PluginContext.vehicle` needs `vehicle.read`,
  `Parking.overrideSoundDirections` needs `vehicle.parking.write`, and `PluginContext.hooks` and
  `ctx.events().on(HookObserved.class, ...)` need `hooks.parking`).
  If you call one without declaring its permission, the build fails and names each call site:
  `tutorial/coffeetimer/CoffeeTimerPlugin.onEnable -> Ui.carMenuRow needs "ui.slot.carmenu": declare it in
  plugin.json "permissions"`. A call inside `if (ctx.granted(...))` still needs the declaration, and declaring
  `platform.unrestricted` does not excuse it.
- **Status-bar icons are size-checked, as a warning.** A `statusItem` or `toast` call that passes `ui.icon("name")`
  when your manifest declares that icon larger than 20×20 builds, but `permissionScan` warns: that icon throws at
  runtime (section 10). A `ui.stockIcon(...)` is always small enough and never warns.

Copy the names from `mibsdk.api.Permissions` or `api/permissions.json`.

## 3. Icons

The Car-drawer row shows a 72×72 icon and the status item a 20×20 one. Every icon is a PNG with these properties:

- 8-bit RGBA (or grey + alpha), not palette, not 16-bit;
- no interlacing and no animation;
- at most 72 px either way and 32 KiB.

The HMI draws a status icon tinted in the app colour by default. Car rows draw icons in their own colours, so a
**white glyph on transparent** looks like the stock icons. Keep the 2 right-most columns of the 20 px icon clear:
the status text starts right after it.

Any image editor works if it can export 8-bit RGBA. This tutorial uses a small Python script instead, so the result
is exact. Create `plugins/coffee-timer/make_icons.py`:

<!-- file: plugins/coffee-timer/make_icons.py -->
```python
#!/usr/bin/env python3
"""Writes coffee-timer's icons: cup.png (72x72, Car-drawer row) and cup-20.png (20x20, status item).

White glyph on a transparent background, 8-bit RGBA, as the SDK's icon rules want. Standard library only.
Usage: python3 make_icons.py <dir>
"""
import os
import struct
import sys
import zlib


def png(w, h, inside):
    rows = b""
    for y in range(h):
        row = b"\x00"  # filter type 0 for every row
        for x in range(w):
            row += b"\xff\xff\xff\xff" if inside(x / w, y / h) else b"\x00\x00\x00\x00"
        rows += row

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xffffffff)

    ihdr = struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)  # 8-bit, RGBA, no interlace
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", zlib.compress(rows, 9))
            + chunk(b"IEND", b""))


def cup(u, v):
    """A cup with a handle, in unit coordinates (0..1, y down)."""
    body = 0.15 <= u <= 0.65 and 0.30 <= v <= 0.80
    handle = 0.65 <= u <= 0.85 and 0.40 <= v <= 0.65 and not (0.65 <= u <= 0.75 and 0.47 <= v <= 0.58)
    steam = v <= 0.22 and v >= 0.08 and any(abs(u - c) <= 0.03 for c in (0.28, 0.40, 0.52))
    return body or handle or steam


def cup_small(u, v):
    # The status icon keeps its 2 right-most columns clear: the status text follows the 20-px icon cell directly.
    return u < 0.9 and cup(u / 0.9, v)


out = sys.argv[1] if len(sys.argv) > 1 else "."
os.makedirs(out, exist_ok=True)
for name, w, glyph in (("cup", 72, cup), ("cup-20", 20, cup_small)):
    with open(os.path.join(out, name + ".png"), "wb") as f:
        f.write(png(w, w, glyph))
    print("wrote", os.path.join(out, name + ".png"))
```

Run it:

```sh
python3 -I plugins/coffee-timer/make_icons.py plugins/coffee-timer/src/main/icons
```

It prints `wrote .../cup.png` and `wrote .../cup-20.png`. The build checks both files with the same rules mibpkg
applies at install (`PngCheck`). For the 72 px icon, which is taller than 20 px, it also writes a faded reflection
(`cup.r.png`), which the drawer draws under the row icon.

| Icon use | Largest size | Rule |
|---|---|---|
| Car-drawer row (`carMenuRow`) | 72×72 (`Ui.ROW_ICON_MAX_PX`) | drawn in its own colours |
| Status item, toast (`statusItem`, `toast`) | 20×20 (`StatusItem.ICON_MAX_PX`) | tinted, unless `ui.icon(name, Icon.OWN_COLOURS)` (api 4) |
| Page item (`PageSpec.item(..., icon)`) | 24×24 (`PageSpec.ITEM_ICON_MAX_PX`) | the cell does not scale |

These limits are enforced **at runtime, not at build**. A 24 px icon passed to `statusItem` builds fine, then
throws `IllegalArgumentException` when your code runs. The built-in glyphs (`StockIcons.STAR`, `CHECK`, `PLUG`
and others, via `ui.stockIcon`) need no file at all.

## 4. The plugin class

Create `plugins/coffee-timer/src/main/java/tutorial/coffeetimer/CoffeeTimerPlugin.java`:

<!-- file: plugins/coffee-timer/src/main/java/tutorial/coffeetimer/CoffeeTimerPlugin.java -->
```java
package tutorial.coffeetimer;

import mibsdk.api.Icon;
import mibsdk.api.Log;
import mibsdk.api.MibPlugin;
import mibsdk.api.Page;
import mibsdk.api.PageSpec;
import mibsdk.api.PluginContext;
import mibsdk.api.Scheduler;
import mibsdk.api.StatusItem;
import mibsdk.api.Storage;
import mibsdk.api.Subscription;
import mibsdk.api.Ui;
import mibsdk.api.UiAction;

/**
 * A brew timer: a Car-drawer row opens the "Coffee timer" page, where you set the brew time (a slider) and whether a
 * toast pops up when it is done (a toggle). While a timer runs, the status line counts down; when it ends it says
 * "Coffee ready" until you dismiss it. The brew time and the toggle are kept in storage.
 */
public class CoffeeTimerPlugin extends MibPlugin {

    static final String PAGE_ID = "timer";
    static final int MIN_MINUTES = 1;
    static final int MAX_MINUTES = 10;
    static final int DEFAULT_MINUTES = 4;
    static final long MINUTE_MS = 60_000L;

    private Log log;
    private Storage storage;
    private Scheduler scheduler;
    private Ui ui;
    private Page page;
    private StatusItem status;
    private Icon cupSmall;

    private int minutes;           // the brew time, stored as "minutes"
    private boolean toastWhenReady; // stored as "toast" = on|off
    private int left;              // minutes left; 0 when no timer runs
    private boolean ready;         // the timer ended and nobody dismissed it yet
    private Subscription tick;     // the once-a-minute task while a timer runs

    @Override
    protected void onEnable(PluginContext ctx) {
        log = ctx.log();
        storage = ctx.storage();
        scheduler = ctx.scheduler();
        ui = ctx.ui();

        minutes = storedMinutes();
        toastWhenReady = !"off".equals(storage.get("toast", "on"));

        cupSmall = ui.icon("cup-20");
        page = ui.page(spec(), this::onAction);
        ui.carMenuRow("Coffee", page, ui.icon("cup"));
        status = ui.statusItem(null, cupSmall); // hidden until a timer runs
        log.info("enabled, brew time " + minutes + " min");
    }

    @Override
    protected void onDisable() {
        // The runtime has already cancelled the timer, the page, the row and the status item.
        log.info("disabled");
    }

    private void onAction(UiAction a) {
        switch (a.itemId()) {
            case "start" -> start();
            case "stop" -> stop();
            case "dismiss" -> {
                ready = false;
                status.set(null);
            }
            case "toast" -> {
                toastWhenReady = a.requestedOn().booleanValue();
                storage.put("toast", toastWhenReady ? "on" : "off");
            }
            case "minutes" -> {
                minutes = a.value().intValue();
                storage.put("minutes", String.valueOf(minutes));
            }
            default -> log.warn("unknown item " + a.itemId());
        }
        page.update(spec());
    }

    private void start() {
        cancelTick();
        ready = false;
        left = minutes;
        tick = scheduler.every(MINUTE_MS, this::onMinute);
        status.set("Coffee " + left + "m", cupSmall);
        log.info("brewing for " + minutes + " min");
    }

    private void stop() {
        cancelTick();
        left = 0;
        status.set(null);
        log.info("stopped");
    }

    private void onMinute() {
        left--;
        if (left > 0) {
            status.set("Coffee " + left + "m", cupSmall);
        } else {
            cancelTick();
            ready = true;
            status.set("Coffee ready", cupSmall);
            if (toastWhenReady) {
                ui.toast("Coffee is ready", cupSmall);
            }
            log.info("ready");
        }
        page.update(spec());
    }

    private void cancelTick() {
        if (tick != null) {
            tick.cancel();
            tick = null;
        }
    }

    private PageSpec spec() {
        PageSpec s = PageSpec.of(PAGE_ID, "Coffee timer");
        s = left > 0 ? s.item("stop", "Stop timer", left + " min left") : s.item("start", "Start timer", minutes + " min");
        if (ready) {
            s = s.item("dismiss", "Dismiss", null);
        }
        return s.toggle("toast", "Pop up when ready", toastWhenReady)
                .slider("minutes", "Brew time", MIN_MINUTES, MAX_MINUTES, 1, minutes, false)
                .info(ready ? "Your coffee is ready." : left > 0 ? "Brewing..." : "Set a brew time, then Start.");
    }

    /** The stored brew time, or the default when none is stored or it is out of range. */
    private int storedMinutes() {
        try {
            int m = Integer.parseInt(storage.get("minutes", String.valueOf(DEFAULT_MINUTES)));
            return m >= MIN_MINUTES && m <= MAX_MINUTES ? m : DEFAULT_MINUTES;
        } catch (NumberFormatException e) {
            return DEFAULT_MINUTES;
        }
    }
}
```

Here is what each part does.

### 4.1 The lifecycle

- The SDK creates your `main` class with its no-argument constructor and calls **`onEnable(ctx)`** once. Do your
  setup there: read storage, register the page, the row, the status item, timers and event listeners.
  `PluginContext` is your only door to the SDK.
- **`onDisable()`** runs in these cases: the plugin is disabled (from the Plugins menu, by a reload or because it
  was quarantined), its permissions are revoked, it is removed, or the HMI shuts down. By the time it runs, the SDK
  has already cancelled everything you registered: pages, rows, status items, timers, listeners and overrides. You
  do not have to clean up. Values you `put` into storage here are still saved.
- `onDisable()` also runs after an `onEnable` that **threw**. Any field you set after the failure point is still
  null then, so keep `onDisable` defensive.
- On the unit, plugins start `plugin.start.delay.ms` after the SDK, which is **30 s** after boot by default. A
  jar that is installed or pushed later loads within a few seconds.

### 4.2 Threads: one worker per plugin

All of your code runs on **one SDK-owned worker thread per plugin**, one task at a time:

- `onEnable`;
- page actions;
- `Scheduler` tasks;
- event listeners;
- confirm and speller answers.

That is why `CoffeeTimerPlugin` has no `synchronized` and no `volatile`: `onMinute` and `onAction` can never run at
the same time. It also means **a slow callback blocks the rest of your plugin**. The SDK logs a callback that
takes longer than 5 s (`callback.slow.ms`) as slow. One that runs longer than 30 s (`callback.hung.ms`) gets the
plugin quarantined. Your code never runs on an HMI thread.

The `mibsdk.api` package doc has the full model: the lifecycle, when `onDisable` runs, the quarantine rule (three
failures in 60 s), inert handles, and the UI behaviours that surprise people. It is in the
[`mibsdk.api` package summary](../../api/build/docs/javadoc/mibsdk/api/package-summary.html) after
`./gradlew :api:javadoc`.

### 4.3 A Car-drawer row that opens a page

```java
page = ui.page(spec(), this::onAction);
ui.carMenuRow("Coffee", page, ui.icon("cup"));
```

- `ui.page` registers the page, and the SDK lists it under your plugin's entry in the **Plugins** menu.
- `carMenuRow` adds a row to the Car drawer that opens it.
- Lambdas and method references work. The build turns them into plain classes.
- A plugin has at most `Ui.MAX_CAR_ROWS` (2) Car rows and `Ui.MAX_PAGES` (8) pages. The third row throws
  `IllegalStateException`.

The Car screen reads its rows when it opens. So if you later replace a row, for example to change its icon, the
change shows the next time the Car screen opens.

`PageSpec` is an **immutable builder**: each call returns a new spec. To change what is on screen, build a new spec
and call `page.update(spec)`. `spec()` rebuilds the whole page from the plugin's fields, which is the simplest
pattern and the one the samples use. A plugin page's key is `<plugin id>/<page id>`, here `coffee-timer/timer`;
the test uses it.

### 4.4 A toggle and a slider with stored values

```java
return s.toggle("toast", "Pop up when ready", toastWhenReady)
        .slider("minutes", "Brew time", MIN_MINUTES, MAX_MINUTES, 1, minutes, false)
```

- A **toggle** is drawn as the HMI's native checkbox. When the user selects it, you get a `UiAction` with
  `requestedOn()` set to the state they asked for. The checkbox does **not** flip by itself: it shows whatever your
  next `page.update` says. Store the value, then update.
- A **slider** (api 5) is edited on its own screen with the knob. Each knob step arrives as `UiAction.value()`. A
  slider shows its position, not its number, so the "Start timer" row shows the minutes in its value column.

  Rules (else `IllegalArgumentException`):
  - `min < max`;
  - `(max - min) % step == 0`;
  - at most 24 steps;
  - the value is on the grid.

  A page holds at most 4 slider plus dropdown rows, and they are drawn **after** the list rows, whatever order you
  add them in.
- **Storage** is a string map per plugin: `storage.get(key, default)`, `put`, `remove`. The SDK writes it to the
  unit's persistent partition shortly after a `put`, and on shutdown. It is capped at 256 KiB per plugin (a `put`
  past the cap throws `IllegalStateException`). Parse what you read defensively, as `storedMinutes()` does: the
  file can be from an older version of your plugin.

`onAction` uses a Java 14 `switch` with arrows. Modern syntax is fine; modern **library** calls may not be (see
section 10).

### 4.5 A status item

```java
status = ui.statusItem(null, cupSmall); // hidden until a timer runs
...
status.set("Coffee " + left + "m", cupSmall);
```

- A plugin has **one** status item (`Ui.MAX_STATUS_ITEMS`).
- `set(null)` or `set("")` hides it.
- Text over **12 characters** (`StatusItem.MAX_TEXT`) is cut. The line is shared with the SDK's own indicators and
  other plugins, and each item gets a fair share of the width, so put the important part first. "Coffee ready" is
  exactly 12.
- The user can hide your item or move it from your plugin's page in the Plugins menu, and you cannot tell. `set`
  keeps working and never fails because the item is hidden.

### 4.6 A toast

```java
ui.toast("Coffee is ready", cupSmall);
```

- A toast (api 5) shows up to 20 characters, with an optional 20 px icon, for 3 s at the top of the screen.
- It needs `ui.slot.statusbar`. Without that permission it throws `PermissionDeniedException`.
- Without the `ui.inputs` feature it is silently inert.

### 4.7 Missing features are not errors

When the unit cannot show something, for example because the SDK UI is off this boot, `ui.page`, `carMenuRow`,
`statusItem` and `toast` still return handles. These handles are **inert**: calls on them are accepted and show
nothing. Count limits and argument checks still apply, so a bug shows up either way. Use
`ctx.has(Features.SLOT_CAR_MENU)` and the other features when you need to know.

## 5. Build the jar

```sh
./gradlew :plugins:coffee-timer:mibPluginJar
```

This compiles the code, runs `backport` (Retrolambda and Retrotranslator, down to class-file v48), then
`classVersionCheck`, `linkCheck` and `permissionScan`, and writes:

```
plugins/coffee-timer/build/mibsdk/coffee-timer.jar         the plugin: v48 classes + plugin.json at the root
plugins/coffee-timer/build/mibsdk/coffee-timer.jar.stamp   which checks ran, and the jar's SHA-256
```

The stamp is what lets mibpkg accept the jar: it refuses a jar without a valid stamp. Do not edit the jar after the
build. The Retrolambda and Retrotranslator output in the log (`Saving lambda class ...`, `Transformed 3 file(s).`)
is normal; it appears when you build with `--info`.

Reports land in `plugins/coffee-timer/build/reports/mibsdk/`: `linkcheck.txt` (empty when clean) and
`permissions.txt`.

## 6. Test it on your computer

There is no emulator for the head unit. Instead, `plugin-test` gives you `PluginTestHarness`. It installs the **built
jar** into a temporary directory laid out like the unit, starts the real SDK runtime on a fake car (`adapter-fake`'s
`FakePlatform`) and gives you a fake screen (`FakeUi`) to drive the UI as a user would.

Create `plugins/coffee-timer/src/test/java/tutorial/coffeetimer/CoffeeTimerPluginTest.java`:

<!-- file: plugins/coffee-timer/src/test/java/tutorial/coffeetimer/CoffeeTimerPluginTest.java -->
```java
package tutorial.coffeetimer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import mibsdk.adapter.fake.FakeUi;
import mibsdk.test.PluginTestHarness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CoffeeTimerPluginTest {

    @TempDir
    File tmp;

    private PluginTestHarness h;
    /** What the plugin stored on an earlier run: set before start. */
    private final Properties stored = new Properties();
    private FakeUi ui;
    /** A plugin page's key is "<plugin id>/<page id>". */
    private String page;
    private String row;
    private String cup20Sha;

    @AfterEach
    void tearDown() {
        if (h != null) {
            h.close();
        }
    }

    /** Installs the built jar into a temp "unit", starts the real runtime on the fake car and waits for the page. */
    private void start() {
        h = PluginTestHarness.builder(tmp).storedValues(stored).start();
        ui = h.ui();
        page = h.pageKey("timer");
        row = "[png:" + h.iconSha("cup") + "] Coffee";
        cup20Sha = h.iconSha("cup-20");
        h.await(() -> !ui.render(page).isEmpty(), "the page");
    }

    private void minutePasses() {
        h.advance(CoffeeTimerPlugin.MINUTE_MS);
    }

    // ---- the tests ----

    @Test
    void carRowOpensTheTimerPage() {
        start();
        h.await(() -> ui.carRows().contains(row), "the Coffee row: " + ui.carRows());
        ui.selectRow(ui.carRows().indexOf(row));
        h.await(() -> page.equals(ui.openPageKey()), "the page open");
        assertEquals(Arrays.asList(
                "Start timer: 4 min",
                "[on] Pop up when ready",
                "Brew time: 4 [1..10]",
                "Set a brew time, then Start."), ui.render(page));
    }

    @Test
    void sliderAndToggleAreStored() {
        start();
        ui.turn(page, "minutes", 7);
        h.await(() -> ui.render(page).contains("Start timer: 7 min"), "7 min");
        ui.toggle(page, "toast");
        h.await(() -> ui.render(page).contains("[off] Pop up when ready"), "toggle off");
        Properties stored = h.storedValues();
        assertEquals("7", stored.getProperty("minutes"));
        assertEquals("off", stored.getProperty("toast"));
    }

    @Test
    void storedValuesAreRestored() {
        stored.setProperty("minutes", "2");
        stored.setProperty("toast", "off");
        start();
        List<String> lines = ui.render(page);
        assertEquals("Start timer: 2 min", lines.get(0));
        assertEquals("[off] Pop up when ready", lines.get(1));
    }

    @Test
    void timerCountsDownOnTheStatusLineAndToasts() {
        start();
        ui.turn(page, "minutes", 2);
        h.await(() -> ui.render(page).contains("Start timer: 2 min"), "2 min");
        assertEquals("", ui.statusLine());
        ui.select(page, "start");
        h.await(() -> "Coffee 2m".equals(ui.statusLine()), "Coffee 2m on the status line");
        minutePasses();
        h.await(() -> "Coffee 1m".equals(ui.statusLine()), "Coffee 1m");
        assertNull(ui.toast());
        minutePasses();
        h.await(() -> "Coffee ready".equals(ui.statusLine()), "Coffee ready");
        h.await(() -> ("[png:" + cup20Sha + "] Coffee is ready").equals(ui.toast()), "the toast: " + ui.toast());
        h.await(() -> ui.render(page).contains("Dismiss"), "the Dismiss row");
        ui.select(page, "dismiss");
        h.await(() -> "".equals(ui.statusLine()), "the status item hidden");
    }

    @Test
    void noToastWhenTurnedOff() {
        start();
        ui.toggle(page, "toast");
        ui.turn(page, "minutes", 1);
        h.await(() -> ui.render(page).contains("Start timer: 1 min"), "1 min");
        ui.select(page, "start");
        h.await(() -> "Coffee 1m".equals(ui.statusLine()), "Coffee 1m");
        minutePasses();
        h.await(() -> "Coffee ready".equals(ui.statusLine()), "Coffee ready");
        assertNull(ui.toast());
    }

    @Test
    void stopCancelsTheTimer() {
        start();
        ui.select(page, "start");
        h.await(() -> "Coffee 4m".equals(ui.statusLine()), "Coffee 4m");
        h.await(() -> ui.render(page).contains("Stop timer: 4 min left"), "the Stop row");
        ui.select(page, "stop");
        h.await(() -> ui.render(page).contains("Start timer: 4 min"), "back to Start");
        assertEquals("", ui.statusLine());
    }
}
```

Run it:

```sh
./gradlew :plugins:coffee-timer:test
```

You should see `BUILD SUCCESSFUL`, and the report lists six tests in `CoffeeTimerPluginTest`. A failing `h.await`
prints the runtime's status, the car rows, the status line,
your plugin's pages and the runtime's whole log, and the log usually tells you why. Open
`plugins/coffee-timer/build/reports/tests/test/index.html` for the details.

What the harness does, and why:

- **`PluginTestHarness.start(tmp)`** does the setup every host test needs. It reads the jar from
  `-Dmibsdk.pluginJar` (or `Builder.jar(File)`), and the icons from `-Dmibsdk.pluginIcons` (or `Builder.icons(File)`,
  by default `src/main/icons`). Then it:
  1. copies the jar into the temporary unit;
  2. writes an `sdk.properties` with the 30 s start delay, the heap guard and the UI throttle off
     (`plugin.start.delay.ms=0`, `heap.low.bytes=0`, `ui.publish.min.ms=0`);
  3. writes `grants.properties`, standing in for what the user approved at install: by default every permission your
     `plugin.json` declares;
  4. installs the icons as mibpkg does: each PNG under its `<sha16>.png` name, with an `index.properties` line
     `<name>=<sha16>,<w>,<h>`. `h.iconSha("cup")` gives you the `sha16` for an assertion;
  5. starts the runtime and waits until the plugin is `ENABLED`. If it never is, the test fails with the runtime log.
- **Grants and failures.** `builder(tmp).grants().startExpectingFailure()` grants nothing and waits for the plugin to
  be `FAILED` or `BLOCKED_*` without throwing: `h.state()` is then `"BLOCKED_PERMISSIONS"`.
- **Features.** `builder(tmp).features(...)` decides what the fake unit can show; by default it has all of them.
  Leave out `Features.UI_INPUTS` and the slider becomes a text line and the toast is inert, as on a unit with the
  native inputs off.
- **Time.** `h.advance(ms)` moves the plugin's `Scheduler` forward, so a 2-minute timer runs in milliseconds.
- **Waiting.** The runtime works on its own threads, so every check is an `h.await(condition, what)` loop (5 s), not
  an assertion right after the action.
- **Storage.** `builder(tmp).storedValues(properties)` puts values in the plugin's storage before it starts, as if an
  earlier run had stored them. `h.storedValues()` stops the runtime, which flushes the plugin's storage, and returns
  what the plugin stored. `h.restart()` stops and starts again on the same directory, so you can test that values come
  back. After a restart, call `h.ui()` again.
- **Pages.** `h.pageKey("timer")` is the page's key for `FakeUi` (`<plugin id>/<page id>`). `h.openPage("timer")`
  opens it from the Plugins menu, which the confirm and speller dialogs need.
- **FakeUi drivers:**
  - `carRows()`, `selectRow(i)`, `render(pageKey)`, `select(pageKey, itemId)`, `toggle(...)`, `turn(...)` for a
    slider and `pick(...)` for a dropdown;
  - `choose(pageKey, itemId, index)` for a choice. The SDK derives a sub-page keyed `<pageKey>#<itemId>` whose
    options have the ids `"0"`, `"1"` and so on; `choose` opens it and picks the option for you;
  - `statusLine()`, `statusParts()` and `toast()`;
  - `dialog()`, `answer(...)`, `type(...)` and `leave()` for confirm and speller.

  Every driver (`select`, `toggle`, `turn`, `pick`, `choose`) does nothing when the entry is not on the page yet or
  is disabled. After an action that changes the page, await the change before the next one: the plugin's `start()`
  sets the status line, and then `onAction`, which called it, rebuilds the page with `page.update(spec())`, so the
  test above waits for the "Stop timer" row before selecting it.
- **The car.** `h.car()` is the `FakePlatform`: change the unit's ignition or sound state there.
- **Rendered text.** `render` gives one line per entry, in a fixed text format (`UiPageText.line`): `[on] label` for
  a toggle, `label: value` for an item, `Brew time: 4 [1..10]` for a slider. Icons show as tags: `[png:<sha16>]`
  for your PNG, `[star]` for a built-in.

## 7. Package it

```sh
./gradlew :plugins:coffee-timer:mibpkgPackage
```

This needs the host `mibpkg` (`mibsdk.mibpkg` in `gradle.properties`, or `-Pmibsdk.mibpkg=<path>`). Without it the
task fails with `host mibpkg not found: build it (cargo build --release in the mibpkg repo) and pass
-Pmibsdk.mibpkg=<path>`. The task writes:

```
plugins/coffee-timer/build/mibsdk/coffee-timer-1.0.0.mpk
```

The package holds the stamped jar, `icons/cup.png`, `icons/cup-20.png`, `icons/cup.r.png` and a generated
`manifest.toml`:

```toml
[package]
name = "coffee-timer"
version = "1.0.0"
description = "Coffee Timer (mibsdk plugin)"

[compat]
units = ["MHI2Q"]

[plugin]
jar = "coffee-timer.jar"
api = 5
permissions = ["ui.slot.carmenu", "ui.slot.statusbar"]
icons = ["cup", "cup-20"]
```

You can look at the staged files in `plugins/coffee-timer/build/mibpkg-stage/`. The task is `mibpkgPackage`, the
same name as the SDK's own `:dist:mibpkgPackage` (`mibPackage` still runs and says it is renamed).

## 8. Install it

The unit needs mibpkg 0.9.1 or later and the **SDK package**, installed once:

- build it with `./gradlew :dist:mibpkgPackage` (this needs both stock jars);
- install `dist/build/dist/mibsdk-0.3.0.mpk` with mibpkg.

A plugin with `"api": 5` needs SDK 0.3.0. mibpkg refuses it on an older SDK.

### 8.1 With mibpkg (any unit)

Open mibpkg's web interface from your phone (the address is in GEM → mibpkg), upload
`coffee-timer-1.0.0.mpk`, and install it. mibpkg lists the two permissions in plain words ("Add a row to the Car
menu", "Show an item in the status bar") and installs only when you approve them. On the unit's command line, the
equivalent is `mibpkg install-file`. Without approval it exits with status **3** and lists the permissions. Re-run
it with `--approve=ui.slot.carmenu,ui.slot.statusbar`.

mibpkg then does three things:

1. It writes the jar.
2. It writes your grants line and your icons, the icons under their content hashes.
3. It asks the running SDK to load the plugin, which takes a few seconds and needs no HMI restart.

Open the Car drawer: the "Coffee" row with the cup is there.

### 8.2 With mibsdk-dev (a unit you can reach over SSH)

`installer/mibsdk-dev` runs on your computer and drives the unit's mibpkg over SSH as root. It has no default
host: pass your unit's address as the last argument, or set `MIBSDK_HOST` once in your shell:

```sh
installer/mibsdk-dev install-plugin plugins/coffee-timer/build/mibsdk/coffee-timer-1.0.0.mpk <unit-ip>
# exit 3: lists the permissions to approve, ending in "approve with: ..."
installer/mibsdk-dev install-plugin plugins/coffee-timer/build/mibsdk/coffee-timer-1.0.0.mpk \
    --approve=ui.slot.carmenu,ui.slot.statusbar <unit-ip>
```

### 8.3 The fast loop: push a jar

Once the package is installed (so the grants and icons are on the unit), you can send code changes as a bare jar:

```sh
./gradlew :plugins:coffee-timer:mibPluginJar
installer/mibsdk-dev push plugins/coffee-timer/build/mibsdk/coffee-timer.jar <unit-ip>
```

The SDK hot-reloads it within a couple of seconds, with no HMI restart. A push carries **only the jar**:

- New or changed icons need a new package. Until you install one, a pushed plugin shows its rows and items without
  the icons and logs one WARN.
- A new permission needs `installer/mibsdk-dev grant coffee-timer <perm,...> <unit-ip>`.

Other `mibsdk-dev` commands:

| Command | What it does |
|---|---|
| `status` | the SDK, the plugins with their states, grants, safe mode |
| `log` | print `/tmp/mibsdk.log` |
| `remove <id> [--purge]` | remove the plugin; `--purge` also deletes its storage |
| `data-clear <id>` | delete its storage |
| `safe-mode on\|off` | force safe mode on or off |
| `restart` | restart the HMI. On the bench unit this resets the whole unit, which takes 2 to 3 minutes |

## 9. Debugging

**Logs.** `ctx.log().info/warn/error` writes to the SDK's in-memory ring buffer. The SDK mirrors the buffer to
`/tmp/mibsdk.log` on the unit (`mibsdk-dev log`). The log also has the SDK's own `STATUS` lines, for example
`STATUS coffee-timer FAILED mibsdk.runtime.UndeclaredPermissionException: permission not declared in plugin.json: ui.slot.carmenu at ...`
(the reason is the exception, then the first stack frame). In a host test, `h.log()` gives you the same lines. `h.await` prints them when a test fails.

**Plugin states.** The Plugins menu shows each plugin's state, and `mibsdk-dev status` lists them too:

| State | Meaning | What to do |
|---|---|---|
| `ENABLED` | running | |
| `BLOCKED_INVALID` | unreadable jar, invalid `plugin.json`, or the jar not named `<id>.jar` | rebuild with `mibPluginJar` |
| `BLOCKED_PERMISSIONS` | a declared permission is not granted. It is all or nothing | approve or grant the full list |
| `FAILED` | loading the class or `onEnable` threw. The reason is in the log | fix and push |
| `QUARANTINED` | 3 callback failures within 60 s, or a callback hung for 30 s | fix and push. A push or reload starts it again |
| `DISABLED` | switched off in the Plugins menu, or by a reload | |

**Crash quarantine.** Every callback runs inside the runtime's catch-all. One exception is logged and the plugin
keeps running. The third within a minute quarantines it: the plugin is disabled, its registrations are cancelled
and the Plugins menu says why. `plugins/crashy` does this on purpose, if you want to see it.

**Safe mode.** If the HMI fails to come up healthy (60 s without a crash) 3 boots in a row with plugins enabled, the
next boot is in **safe mode**: the SDK runs with no plugins, restores any pending vehicle overrides, and the
Plugins menu reads "Plugins (safe mode)". `mibsdk-dev safe-mode on` forces it, which is useful when you suspect a
plugin. `mibsdk-dev safe-mode off` leaves it and resets the boot counter.

**Build failures.** `linkCheck` and `permissionScan` name the method that made the call (`used by
tutorial/coffeetimer/CoffeeTimerPlugin.start`, or `owner.method -> call`), and `linkCheck` adds a hint for the usual
Java 5/6 gaps. Start at the method it names.

**Common failures:**

- `UndeclaredPermissionException: permission not declared in plugin.json: X` (class `mibsdk.runtime.UndeclaredPermissionException`
  in the log). Your code still catches it as `mibsdk.api.PermissionDeniedException`, and `permission()` returns `X`.
  You used something that needs `X` and the manifest does not list it. Add `X` to `"permissions"` and rebuild. The build catches this for the calls it knows
  (section 2), so on the unit it is rare. Granting it on the unit does not help: a permission that is not declared
  can never be used.
- `PermissionDeniedException: permission not granted: X`. `plugin.json` declares `X`, but the owner has not approved
  it. Approve or grant it (section 8). A plugin with a declared permission that is not granted does not normally
  get far enough to throw this: it is `BLOCKED_PERMISSIONS`.
- `IllegalArgumentException: a status icon is at most 20x20 px, this one is 72x72` on a status item or toast. You
  passed the row icon; see the table in section 3.
- `IllegalArgumentException: icon 'cup' is not declared in plugin coffee-timer's plugin.json "icons"`. Add the name
  to `"icons"`.
- A blank row icon after `push`. Icons only arrive with a package (section 8.3).
- A toggle that "doesn't stick". You did not `page.update(spec())` with the new state.

**There is no debugger** on the unit (JDWP is off) and no emulator. Test on the host first. On the unit, use the
log.

## 10. What you can't do

These are the rules. The build enforces most of them, and the runtime enforces the rest.

**Java 1.4 underneath (class-file v48).** The unit runs IBM J9 2.3 with a Java 1.4 class library (CDC Foundation
1.1) and loads only v48 class files. You may write modern **syntax**: lambdas, method references, `var`, switch
expressions, text blocks, enhanced `for`, generics, autoboxing and try-with-resources. The build lowers it. Modern
**library** calls are another matter:

- Retrotranslator backports some Java 5 API (`StringBuilder`, `Integer.valueOf`, `String.format` and others).
- The shims in `compat/` cover `Objects`, `AutoCloseable` and fifteen interfaces from `java.util.function`:
  `Function`, `Consumer`, `Supplier`, `Predicate`, `BiFunction`, `BinaryOperator`, `UnaryOperator`, `BiConsumer`,
  `BiPredicate`, `IntFunction`, `IntPredicate`, `IntUnaryOperator`, `ToIntFunction`, `IntSupplier` and
  `BooleanSupplier`. The numeric and `Long`/`Double` variants of the rest are missing.
- What neither covers, `linkCheck` refuses. Each entry names a direct reference from your code, and
  usually the source name behind a rewritten one and a hint:

  ```
  > LinkCheck: 1 reference(s) the car cannot resolve:
      member java/lang/String.matches(Ljava/lang/String;)Z
        used by tutorial/coffeetimer/CoffeeTimerPlugin.start
        hint: no regex on the car: use indexOf/substring or a loop
  ```

  A backported class line also carries the name you wrote, in parentheses, so you can find the import to remove —
  for example `(java/util/concurrent/ConcurrentHashMap in your source)`.
- Gaps that came up while this tutorial was written:
  - `java.util.regex`, so `String.matches`, `split` and `replaceAll`;
  - `String.join`;
  - `java.util.concurrent`;
  - `java.time`;
  - streams (`List.stream()`).

  Use `indexOf` and `substring` instead, loops, and the SDK's single worker thread (or `synchronized`).
- **Records** do not compile. Jabel stops with the cryptic `error: Must be annotated with @Desugar`.

**The heap is tiny.** The whole HMI runs in 60 MB (`-Xmx60m`). When free heap stays low, the SDK quarantines the
most recently enabled plugin. Keep data small.

**No reflection or escape routes (`permissionScan`).** These calls fail the build:

- `Class.forName`, `Method.invoke`, `Constructor.newInstance`;
- `Runtime.exec`, `System.exit`, native loading, `Thread.stop`;
- raw `Socket`, `ServerSocket`, `URL.openConnection` and `InetAddress.getByName`.

The failure names the call site:

```
> permissionScan: 1 call(s) need platform.unrestricted:
    tutorial/coffeetimer/CoffeeTimerPlugin.start -> java/lang/Class.forName
```

For loopback network queries, use `ctx.net()` with the `network` permission. Declaring `platform.unrestricted`
lifts the ban, but mibpkg then shows a loud "the SDK cannot contain it" warning at install. Do not use it for a
plugin you mean to share.

**No stock classes.** Your class loader sees only `java.*`, `javax.*`, `mibsdk.api.*`, `mibsdk.compat.*` and the
Retrotranslator runtime. `de.audi.*`, `de.esolutions.*`, `org.dsi.*` and the SDK's own `mibsdk.runtime.*` are
hidden. You cannot patch or call the stock HMI. Changes to stock behaviour go through the SDK's declarative hooks
(`ctx.hooks()`, parking only for now).

**The api level is checked (`permissionScan`, too).** Use a member from a newer level than the one you declare
(api 2 to 5), and the build fails:

```
> permissionScan: tutorial/coffeetimer/CoffeeTimerPlugin.spec -> PageSpec.slider needs api 5; plugin.json declares api 4
```

The scan knows every member added in api 2 to 5. If you declare api 1 and call `ctx.ui()` (api 2) or
`ui.stockIcon` (api 3), the build names the call site and the level it needs. Compile-time constants such as
`PageSpec.MAX_OPTIONS` are inlined by the compiler and are not checked.

**Threads.** Your code runs on your plugin's one worker thread (section 4.2):

- Do not block it: no `Thread.sleep` loops and no waiting on I/O for long. `net().query` is capped at 3 s.
- Use `ctx.scheduler()` for anything periodic or delayed.
- You *can* start your own `Thread`: the build does not stop you, and the spikes show it works. The SDK cannot
  cancel or quarantine work you do on it, though, so don't.

**UI limits.** The counts are public, so you can check them in your own code; they are enforced at runtime, not at
build (the build warns where it can):

| What | Limit | When you pass it |
|---|---|---|
| Car-drawer rows | 2 per plugin (`Ui.MAX_CAR_ROWS`) | the third throws `IllegalStateException` |
| Pages | 8 per plugin (`Ui.MAX_PAGES`) | `IllegalStateException` |
| Entries per page | 24 (`PageSpec.MAX_ENTRIES`) | `IllegalStateException` |
| Status items | 1 per plugin (`Ui.MAX_STATUS_ITEMS`), 12 characters (`StatusItem.MAX_TEXT`) | a second throws. Longer text is cut |
| Dropdown + slider rows | 4 per page (`MAX_INPUT_ROWS`) | `IllegalStateException` |
| Options (choice, dropdown) | 8 (`MAX_OPTIONS`) | `IllegalArgumentException` |
| Slider steps | 24 (`MAX_SLIDER_STEPS`) | `IllegalArgumentException` |
| Icons | 8 per plugin. Row ≤ 72 px, status and toast ≤ 20 px, item ≤ 24 px | build (count, PNG rules); runtime (size per use) |
| Text | title 32, label 40, value 20, info 80, toast 20, confirm 120 characters | cut, never rejected |

The icon sizes are checked at build only as far as the PNG rules go: a 72 px icon passed to `statusItem` or `toast`
compiles and packages. `permissionScan` warns about it when the call names the icon literally, `ui.icon("cup")`, and
the manifest declares `cup` larger than 20×20, naming the call site and the icon:

```
> mibsdk.plugin: tutorial/coffeetimer/CoffeeTimerPlugin.start passes icon "cup" to the status bar, but it is larger
  than 20x20; that throws at runtime
```

That is a warning, not a failure: the call only throws if that icon is the one it uses. The check reads an icon named
at the call site only: `ui.stockIcon(...)` is always small enough, and an icon that arrives through a field, a
parameter or a local built elsewhere is not judged. Ship the small icon next to the row one, as the tutorial does
with `cup.png` and `cup-20.png`. An up-to-date build does not re-run the scan, so Gradle prints nothing then; the
warning stays in `build/reports/mibsdk/permissions.txt`.

You cannot add MENU-ring entries or draw your own graphics. A confirm or speller opens only while one of your
pages is on screen; otherwise it answers at once (No, or null text).

**Permissions are all or nothing.** If the user revokes one permission, the plugin stops (`BLOCKED_PERMISSIONS`)
until it is granted again. There are no optional permissions yet.

## Where next

- `plugins/inputs-demo`: every api-5 input, including choice, dropdown, progress, confirm and the (experimental)
  speller.
- `plugins/bike-rack`: Kotlin, ignition events and a vehicle override that the SDK's ledger undoes.
- `plugins/daemon-status`: `ctx.net()` against a local daemon, with a periodic refresh.
- The `mibsdk.api` Javadoc (`./gradlew :api:javadoc`, then open `api/build/docs/javadoc/index.html`) and the
  [design spec](../superpowers/specs/2026-10-18-mib2q-plugin-sdk-design.md).
- [Developer bumps](developer-bumps.md): the rough edges you are likely to hit, collected while this tutorial was
  written.
