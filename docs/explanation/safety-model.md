# Safety model

The SDK is designed so a faulty plugin can break itself but not the head unit's boot, and so a plugin
never blocks a stock HMI thread. It is not a security sandbox.

## The four containment rules

### 1. Plugin code runs only on its own worker

Each plugin gets one daemon worker thread. `onEnable`, `onDisable`, page actions, scheduler tasks, event
listeners and dialog answers all run there, one at a time.

Plugin code never runs on:

- the HMI event dispatcher;
- a stock service thread;
- the runtime's control, timer or watchdog thread.

A slow plugin delays its own callbacks, not the HMI.

### 2. Failures are counted, hangs are cut off

| Condition | Result |
|---|---|
| A callback throws once | Logged; the plugin keeps running |
| 3 callback failures in 60 s | Plugin quarantined |
| A callback runs longer than 30 s | Plugin quarantined as hung |
| A callback runs longer than 5 s | Logged as slow |

Lifecycle hooks are special: a throwing `onEnable` disables the plugin as `FAILED` immediately; a
throwing `onDisable` is only logged. `onDisable` still runs after a failed `onEnable`, so guard your
fields.

Quarantine is a disable: registrations are cancelled, overrides released, UI removed and `onDisable`
runs if possible. The plugin stays quarantined until the user re-enables it or the unit restarts. Its
code is not rerun to test recovery.

### 3. Safe mode

If the HMI fails to come up healthy — 60 s without a crash — three boots in a row with plugins enabled,
the next boot runs in **safe mode**:

- the adapter starts;
- pending vehicle overrides are restored;
- no plugin is discovered or loaded, not even by a reload;
- the Plugins menu reads **Plugins (safe mode)**.

A boot where every plugin `FAILED` is not counted as unhealthy: a failed plugin runs no code, so a later
HMI crash is not its doing.

### 4. Overrides are restored

Vehicle writes go through a ledger. It records the prior value and restores it when the plugin is
disabled, quarantined, uninstalled, or the ignition goes off (for `UNTIL_IGNITION_OFF`). A failed restore
is retried with backoff and on the next parking observation or ignition change. On boot, stale entries
from a run that ended badly are restored before plugins load.

## Permissions

Permissions are a seat belt:

- the user sees what a plugin wants before installing it;
- a plugin missing any declared permission does not run;
- revoking one stops the plugin;
- the build refuses known permissioned calls that are not declared.

Outbound network access is a separate permission (`network.outbound`). It can send data off the unit;
approve it only for a plugin you trust.

They do not stop a plugin that sets out to do harm. `platform.unrestricted` lifts the SDK's build-time
and class-loader limits entirely, and mibpkg shows a danger warning. Install only plugins whose source
you trust.

## The heap is small

The whole HMI runs with a 60 MB heap. When free heap stays low, the SDK quarantines the most recently
enabled plugin. Keep data small; do not cache large strings, images or logs.

## What the SDK does not protect against

- A plugin with `platform.unrestricted` can do anything the JVM can.
- A plugin can start its own thread; the SDK cannot cancel or quarantine it. Do not.
- A plugin can loop inside a callback until the 30 s hang threshold, delaying itself and its own UI.
- A bug in the adapter or the runtime is not contained by the plugin model.
- Firmware updates and other mods can change the assumptions the SDK relies on.

## Recovery

If the HMI misbehaves after installing a plugin:

1. Let it boot into safe mode (three unhealthy boots), or force it:
   `installer/mibsdk-dev safe-mode on <unit-ip>`.
2. Remove the plugin: `installer/mibsdk-dev remove <id> <unit-ip>`.
3. Uninstall the SDK if needed; it restores the stock files.
4. Read the log from the failing boot.

Always keep a way to reach the unit (SSH or SD-card recovery) before installing experimental software.

## Related

- [Architecture](architecture.md)
- [Permissions](../reference/permissions.md)
- [Troubleshooting](../reference/troubleshooting.md)
