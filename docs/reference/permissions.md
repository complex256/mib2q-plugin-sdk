# Permissions

Permissions make a plugin's intent visible and stop accidents. They are a **seat belt, not a sandbox**:
they do not stop a plugin that sets out to do harm.

## The catalog

| Permission | Grants | Approval text |
|---|---|---|
| `vehicle.read` | `ctx.vehicle()` and parking state | Read vehicle state: ignition, gear, parking sensors |
| `vehicle.parking.write` | `Parking.overrideSoundDirections` | Change parking-sensor settings, for example mute the rear tones |
| `hooks.parking` | `ctx.hooks()` and `HookObserved` events | Watch and override parking-system messages |
| `ui.slot.carmenu` | `Ui.carMenuRow` | Add a row to the Car menu |
| `ui.slot.statusbar` | `Ui.statusItem`, `Ui.toast` | Show an item in the status bar |
| `network` | `ctx.net()` for loopback | Query local services on this unit (loopback only) |
| `network.outbound` | `ctx.net()` for non-loopback hosts | Connect to hosts outside this unit |
| `network.wifi` | `ctx.wifi()` | Use the Wi-Fi client uplink to connect to a phone hotspot or another network |
| `platform.unrestricted` | Reflection, raw sockets, stock classes | Unrestricted: may use reflection and stock HMI classes; the SDK cannot contain it |

The source of truth is [`api/permissions.json`](https://github.com/complex256/mib2q-plugin-sdk/blob/main/api/permissions.json).

## Declared and granted

A permission must be **both declared and granted**:

1. The plugin declares it in `plugin.json` `"permissions"`.
2. The user approves it at install time.
3. The runtime exposes the matching API only when both are true.

If any declared permission is not granted, the plugin is `BLOCKED_PERMISSIONS` and does not start. There
are no optional permissions yet.

A granted but undeclared permission is unusable: the build refuses the call, and the runtime reports
`permission not declared in plugin.json` if one slips through.

## Build-time enforcement

`permissionScan` knows which API members need which permission and fails the build when you call one
without declaring it:

```text
tutorial/coffeetimer/CoffeeTimerPlugin.onEnable -> Ui.carMenuRow needs "ui.slot.carmenu":
declare it in plugin.json "permissions"
```

A call inside `if (ctx.granted(...))` still needs the declaration. Declaring `platform.unrestricted`
does not excuse it.

## Runtime enforcement

The API checks the grant when you call it. A missing grant throws `PermissionDeniedException` (a
`RuntimeException`) with `permission()` naming the permission. Normally a plugin with a missing grant
never reaches this point because it is blocked at load.

Revoking a permission stops the plugin: it becomes `BLOCKED_PERMISSIONS` until the permission is granted
again.

## `platform.unrestricted`

This is the loud escape hatch. It lifts the build's ban on:

- `Class.forName`, `Method.invoke`, `Constructor.newInstance`;
- `Runtime.exec`, `System.exit`, native loading, `Thread.stop`;
- raw `Socket`, `ServerSocket`, `URL.openConnection`, `InetAddress.getByName`.

mibpkg shows a danger warning at install, and the SDK cannot contain the plugin. Use it only for a plugin
you control and do not intend to share.

## Checking grants from code

```java
if (ctx.granted(Permissions.NETWORK)) {
    // ...
}
```

`ctx.has(Features.X)` is different: it says what this unit can do at this boot, not what the user
approved.

## Related

- [plugin.json](plugin-manifest.md)
- [Safety model](../explanation/safety-model.md)
- [Java compatibility](compatibility.md)
