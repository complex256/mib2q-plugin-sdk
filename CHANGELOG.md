# Changelog

The `mibsdk` SDK package (`dist/build/dist/mibsdk-<version>.mpk`) and its sample plugins. Versions are semver as
mibpkg compares them; bench builds carried a `+bench.N` tag on the package version only and are not releases.

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
