# Changelog

The `mibsdk` SDK package (`dist/build/dist/mibsdk-<version>.mpk`) and its sample plugins. Versions are semver as
mibpkg compares them; bench builds carried a `+bench.N` tag on the package version only and are not releases.

## 0.4.1 — 2026-10-28 (SDK; plugin api 1..6)

- **Removing a plugin no longer needs a restart.** When a reload marker finds the plugin's jar gone, the runtime
  disables it and forgets its host, so the Plugins page drops the row at once; reinstalling the same id discovers it
  again. (Previously the row stayed as `Off` — `plugin jar not found` — until the next HMI start.)

## 0.4.0 — 2026-10-28 (SDK; plugin api 1..6)

- **Icon-only status items.** `StatusItem.setIcon(icon)` shows an icon with no text; the built-in bar lays icon-only
  items out side by side in a fixed strip at its left, while text items keep paginating to its right. `set("", icon)`
  still hides the item (existing plugins rely on that). `setColour`/`visibleIn` apply as for text items.
- **Samples moved.** `bike-rack`, `hello-menu` and `inputs-demo` moved to the new `mib2q-plugins` monorepo; the
  `daemon-status` sample is removed. The SDK keeps `hello`/`crashy` (test fixtures) and the `coffee-timer` tutorial.
  The monorepo's `tailscale-status` and `carmqtt-status` plugins replace the daemon-status line with icons.

## 0.3.0 — 2026-10-28 (SDK; plugin api 1..6)

Developer experience, aimed at plugin authors outside this repository, plus the built-in status bar. The plugin api
grows to 6 for a status item's text colour and per-context visibility; every api addition is additive, and a plugin
built against 0.2.0 (api 5 or older) still builds and behaves as it did.

- **The built-in status bar (api 6).** `ctx.ui().statusItem(...)` items are drawn in the car's own status bar, one
  row per item, instead of the floating popup (the popup stays as the fallback). A plugin's PNG icon is drawn through
  the shared image ids, served by the System factory too, so it resolves on every screen, not only Car;
  `StatusItem.setColour(UiColor)` sets the text colour in every context (null keeps the bar's own), and
  `StatusItem.visibleIn(HmiContext...)` limits an item to contexts (the built-in bar applies it; the popup fallback
  shows every item). The SDK page holds the bar's on/off switch, dwell (default 5 s), animation (default 300 ms) and
  the global item order; all of it lives in `/mnt/persist/mibsdk/statusbar.properties`.

- **A plugin build needs no stock jar.** Every `linkCheck` resolves against a car class-library signature list
  bundled in the build logic (`carjcl.sigs`: names, supertypes and member descriptors, no code). A clone builds,
  tests and packages a plugin with no `lsd.jar` and no `DSITracer.jar`; `adapter-hqc`, `dist` and the `hmi-ui` spike
  still need both, and with `mibsdk.stockLsdJar` set `./gradlew carJclSignatures` regenerates the bundled list.
- **A plugin is its own repository.** `mibsdk.plugin` takes `api`, `compat` and the car classifier jars by
  coordinate from the repository `settings.gradle.kts` names, so a plugin no longer has to be a subproject of this
  build. `./gradlew newPlugin -Pid=<id> -Pdir=<path> [-Ppackage=<pkg>]` scaffolds a stand-alone project from
  `templates/plugin` (id, package and class renamed, `mibsdk.repo` written, `.gitignore` and wrapper included), and
  `standalonePluginCheck` builds that project outside the project graph as part of `check`.
- **`PluginTestHarness`.** `plugin-test` publishes `mibsdk.test.PluginTestHarness`: one call installs the built jar
  and its icons into a temporary unit, starts the real runtime on the fake car and waits for the plugin to enable.
  It exposes the fake screen (`ui()`), `advance(ms)`, `await`, `storedValues()`, `restart()`, grants and features,
  and the convention puts it and JUnit on every plugin's test classpath with `-Dmibsdk.pluginJar` and
  `-Dmibsdk.pluginIcons` already set, so a plugin's build file stays one `plugins` block.
- **Permissions are checked at build.** `PluginJson.read` validates the manifest's `"permissions"` against the
  catalog with a "did you mean" hint, and `permissionScan` fails the build when the code calls API whose permission
  the manifest does not declare (also under `platform.unrestricted`), naming the call site. At runtime an undeclared
  permission is reported as `permission not declared in plugin.json`, distinct from `permission not granted`.
- **The api level is checked at build, for api 2 to 5.** `permissionScan` knows every member added after api 1 and
  names the call site and the level it needs; a `@since` tag without a table entry fails `ApiLevelsTest`.
- **`Ui.MAX_*`.** `Ui.MAX_PAGES`, `Ui.MAX_CAR_ROWS` and `Ui.MAX_STATUS_ITEMS` are public constants named in the
  Javadoc. `permissionScan` also warns when a status-bar call site passes a declared icon larger than 20x20 as a
  literal `ui.icon("name")`; that is a warning, not a failure.
- **`mibpkgPackage` builds a plugin's package.** `./gradlew mibpkgPackage` (the plugin's own name, not
  `:dist:mibpkgPackage`) stages the stamped jar, the icons with their reflections and a generated `manifest.toml`,
  then has the host `mibpkg` pack `build/mibsdk/<id>-<version>.mpk`. `mibPackage` still runs as an alias and says
  it is renamed.
- **`MIBSDK_HOST`.** `installer/mibsdk-dev` has no default host: it takes the unit's address as the last argument,
  or reads `MIBSDK_HOST`, and refuses to run without one. Its minimum mibpkg is 0.9.1.

## 0.3.0 (SDK; plugin api 1..5)

Native inputs: plugin pages are drawn with the HMI's own list cells and input screens.

- **Native checkbox for every toggle.** Page toggles, at any api, are the stock checkbox cell instead of an
  `[on]`/`[off]` text row.
- **Api 5** (a plugin using any of it declares `"api": 5`; the build refuses it otherwise):
  - `PageSpec.choice` (a row that opens a sub-page of options to pick one from), `dropdown`, `slider` (edited on its
    own screen with the knob; optionally centred) and `progress`; at most 4 dropdown and slider rows per page and 8
    options per row;
  - a value column (the value of `item(id, label, value)`), item icons (`item(id, label, value, icon)`, at most
    24x24) and greyed, unselectable rows (`PageSpec.enabled`);
  - `UiAction.value()` and `text()`: the picked index, the slider level, the confirm answer and the speller text;
  - `Ui.confirm`, a yes/no screen of up to 3 lines and 120 characters, answered exactly once;
  - `Ui.toast`, a short message with an optional icon shown for 3 s at the top of the screen beside the status line
    (needs `ui.slot.statusbar`);
  - `Ui.speller`, **experimental**: the HMI's on-screen keyboard. It has no OK key, so BACK is "done";
  - the `ui.inputs` feature (`Features.UI_INPUTS`). Without it, choices, dropdowns, sliders and progress bars show
    as text lines ("Level: Mid"), and confirm, speller and toast are inert (a confirm answers No, a speller null).
- **An api-5 plugin needs SDK 0.3.0; mibpkg refuses it over 0.2.0.** Api 1..4 plugins run unchanged.

Sample plugins: inputs-demo 1.0.0 (new, api 5), one page with every native input, opened from MENU → Plugins.

## 0.2.0 (SDK; plugin api 1..4)

The first release after 0.1.0. It replaces every `0.1.0+bench.N` build: mibpkg orders build metadata, so 0.1.0
itself would compare older than a bench build, and 0.2.0 compares newer than all of them.

- **UI.** A Plugins menu that lists every plugin and the SDK, plugin pages (lists, toggles, info lines) opened from it
  or from a Car-drawer row (at most 2 per plugin), and a status slot: a plugin status item (at most 1, of at most 12
  characters) in the SDK's own status popup beside the stock one.
- **mibpkg distribution.** The SDK and every plugin ship as mibpkg packages (`.mpk`): install, update, grants and
  removal go through mibpkg, which also manages the HMI bundle lines (`HMICar=`, `HMISystem=`).
- **Main-menu takeover.** With the takeover active, the MENU ring ends with a Plugins entry (a wrench icon) after
  Settings, and the Car drawer has no Plugins row.
- **Icons.** Car-drawer rows and status items can carry an icon: one of the SDK's built-in glyphs (`StockIcons`) or a
  PNG the plugin declares in its manifest (api 3; at most 8, up to 72x72, status icons up to 20x20), checked at build
  and at install.
- **Per-icon colour (api 4).** `Ui.icon(name, Icon.OWN_COLOURS)` draws a status icon in its own colours instead of
  tinted; a change shows at once.
- **Faded reflections.** The build adds a faded reflection (`<name>.r`) for every plugin icon taller than 20 px, drawn
  under the row icon in the Car drawer like the stock ones (mibpkg 0.9.1 installs them).
- **Fair-share status line.** Items share the line's width fairly, after the SAFE and PDC R OFF indicators, and the
  composed line is packed against the right edge of the popup, so short lines stay clear of long screen titles.

Sample plugins: bike-rack 1.1.0 (a bicycle icon on its Car row and RACK status item, api 3), crashy 1.1.0,
daemon-status 1.1.0, hello 1.0.0, hello-menu 1.2.0.
