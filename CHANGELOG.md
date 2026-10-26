# Changelog

The `mibsdk` SDK package (`dist/build/dist/mibsdk-<version>.mpk`) and its sample plugins. Versions are semver as
mibpkg compares them; bench builds carried a `+bench.N` tag on the package version only and are not releases.

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
