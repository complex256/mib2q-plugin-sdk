# API overview

The generated [Java API reference](https://complex256.github.io/mib2q-plugin-sdk/api/) is the exact
contract. This page is the map: which type to reach for and how the pieces fit together.

## The entry point

```java
public class MyPlugin extends MibPlugin {
    @Override
    protected void onEnable(PluginContext ctx) {
        // everything starts here
    }
}
```

`MibPlugin` has two hooks: `onEnable(PluginContext)` and `onDisable()`. The runtime calls them on the
plugin's worker thread.

`PluginContext` is the only door to the SDK:

| Method | Gives you | Permission |
|---|---|---|
| `log()` | `Log` | — |
| `storage()` | `Storage` | — |
| `scheduler()` | `Scheduler` | — |
| `events()` | `Events` | — |
| `ui()` | `Ui` | — (individual calls need slot permissions) |
| `net()` | `Net` | `network`; `network.outbound` for non-loopback hosts |
| `wifi()` | `Wifi` | `network.wifi` |
| `vehicle()` | `Vehicle` | `vehicle.read` |
| `hooks()` | `Hooks` | `hooks.parking` |
| `has(feature)` | Whether this unit supports a feature | — |
| `granted(permission)` | Whether the user approved a permission | — |
| `pluginId()` | The manifest id | — |

## UI

| Type | Role |
|---|---|
| `Ui` | Creates pages, Car rows, status items, toasts and dialogs |
| `PageSpec` | Immutable page content and builder |
| `Page` | A live page: `update`, `open`, `cancel` |
| `UiAction` | A user action or dialog answer |
| `StatusItem` | One status-line item |
| `Icon`, `StockIcons` | Icons for rows, items and toasts |
| `UiColor`, `HmiContext` | Status text colour and per-screen visibility |

Start with [Pages and inputs](../guides/pages-and-inputs.md) and [Status line](../guides/status-line.md).

## Data and time

| Type | Role |
|---|---|
| `Storage` | Per-plugin persistent string map |
| `Scheduler` | Delayed and periodic tasks on the worker |
| `Subscription` | A cancellable registration |
| `Listener<E>` | A callback that may throw |

Start with [Storage and timers](../guides/storage-and-timers.md).

## Wi-Fi

| Type | Role |
|---|---|
| `Wifi` | The Wi-Fi client uplink: `status`, `scan`, `connect`, `disconnect`, `forget`, `onChanged` |
| `WifiStatus` | State, SSID, BSSID, IPv4, signal and last error |
| `WifiNetwork` | One scan result: SSID, BSSID, signal and security |
| `WifiChanged` | A state change, delivered on the worker |

The car's own access point stays up: the stock service runs the access point and the client together.
Start with [Networking](../guides/networking.md#wi-fi-uplink).

## Vehicle

| Type | Role |
|---|---|
| `Vehicle` | Vehicle services |
| `Parking` | Parking-sound state and overrides |
| `SoundDirections` | Immutable front/rear/left/right |
| `VehicleOverride`, `OverrideScope` | A held override |
| `Hooks`, `HookPoint`, `HookObserved`, `Veto` | Declarative parking hooks |
| `Events` | Ignition, parking, trailer and hook events |
| `IgnitionChanged`, `ParkingSoundChanged`, `TrailerHitchedChanged` | Event types |

Start with [Vehicle and events](../guides/vehicle-and-events.md).

## Errors and permissions

| Type | Role |
|---|---|
| `PermissionDeniedException` | A declared permission was not granted |
| `OverrideConflictException` | Another plugin holds the parking override |
| `Permissions` | Permission id constants |
| `Features` | Feature id constants |
| `SdkInfo` | `API_VERSION` and `MIN_API_VERSION` |

## Tests

| Type | Role |
|---|---|
| `mibsdk.test.PluginTestHarness` | Runs a built jar on the real runtime and a fake car |
| `mibsdk.adapter.fake.FakeUi` | Drives pages, rows, status, dialogs and toasts |
| `mibsdk.adapter.fake.FakePlatform` | The fake car: ignition, parking ECU, hooks |

Start with [Test your plugin](../getting-started/test-your-plugin.md).

## Package notes

- Everything in `mibsdk.api` is compiled against by plugins.
- `mibsdk.runtime`, `mibsdk.platform` and `mibsdk.adapter.hqc` are internal and hidden at runtime.
- The test harness and fake car are host-only and never ship to the unit.
