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
| 8 | SDK 0.8.0 | `PageSpec.ring` with `PageSpec.ring()`; `PageSpec.link(…, Icon)`; the `StockIcons` ring set (`CAR` … `SMARTPHONE`) |
| 9 | SDK 0.9.0 | No new members. The api classes moved into `mibsdk.api.ui`, `.vehicle`, `.hooks` and `.net`; see [the package layout](#api-9-package-layout) |

The current SDK implements api 9 only.

## Rules

**Levels are additive, except across api 9.** Up to api 8 a newer SDK ran older plugins unchanged. Api 9 moved
classes between packages, so a plugin built for api 8 or lower does not link against it. SDK 0.9.0 loads api 9
only; rebuild older plugins against it and set `"api": 9`. The next level that adds members will make the
"lowest level" rule below meaningful again.

**Declare the lowest level that covers what you use.** Today that is always 9. The build enforces the rule, and
it bites again once a level above 9 adds members: a call to an api 10 member while the manifest declares api 9
fails with the call site, as a call to an api 5 member under a declared api 4 did before:

```text
permissionScan: ...CoffeeTimerPlugin.spec -> PageSpec.slider needs api 5; plugin.json declares api 4
```

Compile-time constants such as `PageSpec.MAX_OPTIONS` are inlined by the compiler, so they leave no call
site and are not checked.

**mibpkg checks the range.** It installs a plugin only when `sdk.min_api <= plugin.api <= sdk.api` for
the SDK on the unit. An api-9 plugin needs SDK 0.9.0 or later.

**Features are separate.** An api level says what the API *has*. `ctx.has(Features.X)` says what *this
unit* can draw at *this boot*. A missing feature makes the matching handle inert, not failing. Check the
feature when the difference matters.

## Choosing a level

Declare 9. It is the only level SDK 0.9.0 loads, and every member of api 1–8 is part of it. The "arrived in"
column above tells you the oldest SDK that has a given member, which matters only if you build against an older
SDK.

## Related

- [plugin.json](plugin-manifest.md)
- [Java compatibility](compatibility.md)
- [Features](../guides/pages-and-inputs.md)

## API 9 package layout

The classes of `mibsdk.api` are grouped by capability. The core types stay where they were.

| Package | Types |
|---|---|
| `mibsdk.api` | `MibPlugin`, `PluginContext`, `SdkInfo`, `Features`, `Log`, `Storage`, `Scheduler`, `Events`, `Listener`, `Subscription`, `Permissions`, `PermissionDeniedException` |
| `mibsdk.api.ui` | `Ui`, `UiAction`, `UiColor`, `Icon`, `StockIcons`, `Page`, `PageSpec`, `StatusItem`, `HmiContext` |
| `mibsdk.api.vehicle` | `Vehicle`, `Parking`, `SoundDirections`, `IgnitionChanged`, `ParkingSoundChanged`, `TrailerHitchedChanged`, `VehicleOverride`, `OverrideScope`, `OverrideConflictException` |
| `mibsdk.api.hooks` | `Hooks`, `HookPoint`, `HookObserved`, `Veto` |
| `mibsdk.api.net` | `Net`, `Wifi`, `WifiNetwork`, `WifiStatus`, `WifiChanged` |

To migrate, change the imports and rebuild. No signature changed.
