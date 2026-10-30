# API levels

The `"api"` field in `plugin.json` is the **newest plugin API your code uses**. It is not the SDK version
and not your plugin version.

`mibsdk.api.SdkInfo.API_VERSION` is the newest level the SDK implements; `MIN_API_VERSION` is the oldest
it still loads.

| API | Arrived in | What it added |
|---|---|---|
| 1 | SDK 0.1.0 | `MibPlugin`, `PluginContext`, `Log`, `Storage`, `Scheduler`, `Events`, `Hooks`, `Vehicle`/`Parking`, `Permissions`, `Features` |
| 2 | SDK 0.1.0 | `Ui.page`, `PageSpec` (`item`, `toggle`, `info`, `link`), `Page`, `UiAction`, `Ui.carMenuRow`, `Ui.statusItem`/`StatusItem`, `Net` |
| 3 | SDK 0.2.0 | Manifest `"icons"`, `Ui.icon`, `Ui.stockIcon`, `StockIcons`, icon overloads of `carMenuRow`, `statusItem` and `StatusItem.set` |
| 4 | SDK 0.2.0 | `Ui.icon(name, colour)` with `Icon.TINTED` / `Icon.OWN_COLOURS` |
| 5 | SDK 0.3.0 | `PageSpec.choice`, `dropdown`, `slider`, `progress`, item icons, `enabled`; `UiAction.value()` / `text()`; `Ui.confirm`, `Ui.toast`, `Ui.speller`; `Features.UI_INPUTS` |
| 6 | SDK 0.3.0 | `StatusItem.setColour`, `StatusItem.visibleIn`, `StatusItem.setIcon`; `UiColor`; `HmiContext` |
| 7 | SDK 0.6.2 | `ctx.wifi()` with `Wifi`, `WifiStatus`, `WifiNetwork`, `WifiChanged`; `Ui.textInput`; `Features.WIFI`; `network.wifi` |

The current SDK implements api 1–7.

## Rules

**Levels are additive.** A newer SDK runs older plugins unchanged. SDK 0.6.3 loads api 1–7.

**Declare the lowest level that covers what you use.** The build enforces it: a call to an api 5 member
while the manifest declares api 4 fails with the call site:

```text
permissionScan: ...CoffeeTimerPlugin.spec -> PageSpec.slider needs api 5; plugin.json declares api 4
```

Compile-time constants such as `PageSpec.MAX_OPTIONS` are inlined by the compiler, so they leave no call
site and are not checked.

**mibpkg checks the range.** It installs a plugin only when `sdk.min_api <= plugin.api <= sdk.api` for
the SDK on the unit. An api-7 plugin needs SDK 0.6.2 or later.

**Features are separate.** An api level says what the API *has*. `ctx.has(Features.X)` says what *this
unit* can draw at *this boot*. A missing feature makes the matching handle inert, not failing. Check the
feature when the difference matters.

## Choosing a level

| If you use | Declare |
|---|---|
| Only lifecycle, storage, timers, events, vehicle | 1 |
| Any page, Car row, status item or network query | 2 |
| Your own PNG icons or a built-in icon | 3 |
| `Icon.OWN_COLOURS` | 4 |
| A slider, dropdown, choice, progress, toast, confirm or speller | 5 |
| Status-item colour, per-context visibility or icon-only items | 6 |
| The Wi-Fi uplink or a masked text field | 7 |

## Related

- [plugin.json](plugin-manifest.md)
- [Java compatibility](compatibility.md)
- [Features](../guides/pages-and-inputs.md)
