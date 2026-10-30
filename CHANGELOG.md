# Changelog

All notable changes to the SDK package and the plugin API. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow semver as mibpkg compares them.
Bench builds carried a `+bench.N` tag and are not releases.

## 0.7.0 — 2026-10-30

### Changed

- **Icon-only status items sit on the right.** The built-in bar now groups icon-only items into shared,
  right-aligned icon rows, highest-priority icons first (left to right). Lower-priority icons spill onto
  further icon rows when a row is full. Text items, with or without an icon, each get their own full-width
  row instead of sharing the strip; all rows paginate with the existing dwell/animation settings. When the
  built-in bar has its own right-side icons, they show first as page 0 and crossfade to the SDK's rows.
- **Native-size status icons.** Status icons may now be up to 30×30 px (`StatusItem.ICON_MAX_PX`), matching
  the stock bar's 30 px glyphs. The built-in bar draws them at 30 px; a legacy 20 px icon is upscaled, and
  the popup fallback and toast scale a 30 px icon down to their 20 px cell.

## 0.6.3 — 2026-10-29

### Added

- **A minimum SDK version on every plugin package.** `mibpkgPackage` now writes `requires.toml` with
  `[mibsdk] min = "<the SDK version it was built against>"`. mibpkg 0.11.1 enforces it on install and
  update, and updates the SDK before plugins. It is a sidecar file, so older mibpkg releases still
  install the package and ignore the constraint.

## 0.6.2 — 2026-10-29

### Fixed

- A failed hotspot join no longer clears the current lease before the new association completes. The
  adapter keeps the old address until `wpa_cli` reports `COMPLETED`, waits for DHCP then, and does not
  re-select a network that is already scanning or associating. A wrong SSID/password therefore cannot
  leave the unit with `0.0.0.0` and no route.

## 0.6.0 — 2026-10-29

Plugin API 1–7.

### Added

- **The phone-hotspot uplink is core.** The SDK's own **Plugins → SDK → Internet** page sets the Wi-Fi
  network and password in the car, turns the uplink on or off, scans, and shows the current state. The same
  settings live in `wifi.toml` under the persist root, so mibpkg's web UI edits the same file. On first run
  the uplink is on when no SIM uplink is present. The same page chooses DHCP or a manual IPv4 address,
  netmask, gateway and optional DNS; with DHCP on it shows the live IP, netmask, gateway and DNS. The car's
  own access point stays up: the stock service runs the access point and the client together. When the stock
  DSI backend acknowledges a connect without moving `mlan0`, the adapter falls back to the same `wpa_cli` the
  bench uses.
- **`network.wifi` (api 7).** `ctx.wifi()` exposes `status()`, `scan(ms)`, `connect(ssid, password)`,
  `disconnect()`, `forget(ssid)` and `onChanged(...)`, with `WifiStatus`, `WifiNetwork` and `WifiChanged`.
  `Features.WIFI` says whether the stock Wi-Fi service is present.
- **Secret text input (api 7).** `ui().textInput(title, initial, maxLen, secret, onDone)` accepts up to 64
  characters and, with `secret`, masks the field through the stock Wi-Fi password input mode.

## 0.5.0 — 2026-10-28

### Added

- **A published documentation site.** The README now routes instead of containing the whole manual. The new site has a tutorial, task guides, reference pages, an architecture explanation, contribution guides and a generated Java API reference (Dokka).
- **An Apache-2.0 license.**
- **A pull-stock-jars task.** `./gradlew pullStockJars -Punit=<host>` copies `lsd.jxe`, `DSITracer.jar` and `version_info.txt` off a unit and can convert the JXE with `jxe2jar`. Plugin builds still need no stock files.
- **A firmware-adapter guide.** The supported-firmware page now explains how to inventory a unit, map its HMI and implement a new adapter, with a definition of done.
- **Outbound TCP.** `ctx.net().query(...)` can reach a host outside the unit with the new
  `network.outbound` permission. The API stays plain TCP; the car has no TLS stack. Loopback hosts still
  need only `network`. DNS resolution shares the call's timeout and runs off the plugin worker.

## 0.4.1 — 2026-10-28

Plugin API 1–6.

### Fixed

- Removing a plugin no longer needs a restart. When a reload marker finds the jar gone, the runtime
  disables the plugin and forgets it, so the Plugins page drops the row immediately. Reinstalling the
  same id discovers it again.

## 0.4.0 — 2026-10-28

Plugin API 1–6.

### Added

- **Icon-only status items.** `StatusItem.setIcon(icon)` shows an icon with no text. Icon-only items share
  a fixed strip at the left of the built-in bar; text items keep paginating to its right.
  `set("", icon)` still hides the item.

### Changed

- `bike-rack`, `hello-menu` and `inputs-demo` moved to the `mib2q-plugins` monorepo. The SDK keeps
  `hello`, `crashy` and `coffee-timer`. `daemon-status` is replaced by `tailscale-status` and
  `carmqtt-status`.

## 0.3.0 — 2026-10-28

Plugin API 1–6. This release includes the native-inputs work and the developer-experience work.

### Added

- **Built-in status bar (api 6).** Plugin status items are drawn in the car's own status bar, one row per
  item, instead of the floating popup (which remains as a fallback). `StatusItem.setColour(UiColor)` sets
  the text colour; `StatusItem.visibleIn(HmiContext...)` limits an item to contexts; `StatusItem.setIcon`
  shows an icon-only item. The SDK page holds the bar's on/off switch, dwell (default 5 s), animation
  (default 300 ms) and item order, stored in `/mnt/persist/mibsdk/statusbar.properties`.
- **Native inputs (api 5).** `PageSpec.choice`, `dropdown`, `slider`, `progress`, item icons,
  `enabled(id, false)`, `UiAction.value()`/`text()`, `Ui.confirm`, `Ui.toast` and the experimental
  `Ui.speller`, plus `Features.UI_INPUTS`.
- **Native checkbox for every toggle**, at any api.
- **Standalone plugin builds.** `mibsdk.plugin` resolves `api`, `compat` and `plugin-test` by coordinate
  from the repository named in `settings.gradle.kts`, so a plugin no longer has to be a subproject of this
  repository.
- **`PluginTestHarness`.** One call installs the built jar and icons into a temporary unit, starts the
  real runtime on the fake car and waits for the plugin to enable. It exposes the fake screen,
  `advance(ms)`, `await`, `storedValues()`, `restart()`, grants and features.
- **`newPlugin` and the standalone template.** `./gradlew newPlugin -Pid=<id> -Pdir=<path>` scaffolds
  `templates/plugin`; `standalonePluginCheck` builds it outside the project graph.
- **`mibpkgPackage`** builds a plugin's `.mpk`. `mibPackage` remains as a deprecated alias.
- **`MIBSDK_HOST`.** `mibsdk-dev` has no default host; it reads the last argument or `MIBSDK_HOST`.
- **Car class-library signature list.** `linkCheck` runs against a committed list of names and
  descriptors, so a plugin build needs no stock `lsd.jar`. `adapter-hqc` and `dist` still do.

### Changed

- **Permissions are checked at build.** `PluginJson` validates names against the catalog with a
  "did you mean" hint; `permissionScan` fails a call whose permission is not declared and names the call
  site.
- **The API level is checked at build** for api 2–5, with the call site and the required level.
- **`Ui.MAX_*`** constants are public and named in the Javadoc. `permissionScan` warns when a status-bar
  call names a declared icon larger than 20×20.
- **An api-5 plugin needs SDK 0.3.0**; mibpkg refuses it on 0.2.0. Api 1–4 plugins run unchanged.

### Fixed

- `permissionScan` catches a permissioned call inside `ctx.granted(...)` that the manifest does not declare.
- `linkCheck` names the referencing method and explains backported class names.

## 0.2.0

Plugin API 1–4. The first release after `0.1.0`; it replaces every `0.1.0+bench.N` build.

### Added

- **Plugins menu and UI.** A Plugins menu listing every plugin and the SDK, plugin pages (lists, toggles,
  info lines) opened from it or from a Car-drawer row (at most 2 per plugin), and a status slot (at most 1
  item, 12 characters).
- **mibpkg distribution.** The SDK and every plugin ship as `.mpk` packages. Install, update, grants and
  removal go through mibpkg, which manages the `HMICar=` and `HMISystem=` lines.
- **Main-menu takeover.** With the takeover active, the MENU ring ends with a Plugins entry after
  Settings, and the Car drawer has no Plugins row.
- **Icons (api 3).** Car rows and status items can use a built-in glyph (`StockIcons`) or a PNG declared
  in the manifest (at most 8, up to 72×72; status icons up to 20×20).
- **Per-icon colour (api 4).** `Ui.icon(name, Icon.OWN_COLOURS)` draws a status icon in its own colours.
- **Faded reflections.** The build adds `<name>.r` for every plugin icon taller than 20 px; the Car drawer
  draws it under the row icon.
- **Fair-share status line.** Items share the line after the SAFE and PDC R OFF indicators.

## 0.1.0

Plugin API 1–2, bench builds only.

### Added

- Plugin lifecycle, context, logging, storage, scheduler, events, hooks and vehicle/parking access.
- Declarative pages, Car rows, status items, `UiAction` and loopback networking (api 2).
