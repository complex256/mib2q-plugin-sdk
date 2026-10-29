# Vehicle and events

Vehicle access is deliberately narrow: read the parking-sound state, change it with a permission, and
observe a few vehicle events. Anything deeper belongs in a firmware adapter, not a plugin.

## Reading parking state

```java
if (ctx.has(Features.PARKING)) {
    SoundDirections now = ctx.vehicle().parking().soundDirections();
    boolean rear = now.rear();
}
```

`SoundDirections` is immutable and has `front()`, `rear()`, `left()`, `right()` and `withRear(...)`.

`ctx.vehicle()` needs the `vehicle.read` permission. Without `Features.PARKING` the parking system is
not available and the call throws `UnsupportedOperationException`; check `ctx.has(...)` first.

## Overriding parking sound

```java
VehicleOverride override = null;

try {
    override = ctx.vehicle().parking().overrideSoundDirections(
            new SoundDirections(true, false, true, true),
            OverrideScope.UNTIL_IGNITION_OFF);
} catch (OverrideConflictException e) {
    ctx.log().warn("another plugin holds the parking override: " + e.getMessage());
}
```

| Rule | Value |
|---|---|
| Permission | `vehicle.parking.write` |
| Conflict | Only one plugin may hold the override; the second gets `OverrideConflictException` |
| Scope | `UNTIL_IGNITION_OFF` or `PERSISTENT` |
| Restore | The SDK's ledger restores the previous value when the plugin is disabled, quarantined, uninstalled, or the ignition goes off (for `UNTIL_IGNITION_OFF`) |

The override is restored automatically. You can release it earlier:

```java
override.release();
```

`PERSISTENT` overrides survive a reboot and are re-claimed by the plugin when it starts again. Use it
only for a setting that should outlive the ignition cycle.

The ledger stores pending restores in `/mnt/persist/mibsdk/ledger.properties` and retries a failed
restore with backoff. If the unit shuts down while an override is active, the next boot restores it
before plugins load.

## Events

```java
ctx.events().on(IgnitionChanged.class, e -> {
    ctx.log().info("ignition " + (e.isOn() ? "on" : "off"));
});

ctx.events().on(ParkingSoundChanged.class, e -> {
    SoundDirections value = e.value();
});

ctx.events().on(TrailerHitchedChanged.class, e -> {
    boolean hitched = e.isHitched();
});
```

| Event | When |
|---|---|
| `IgnitionChanged` | Terminal 15 changes |
| `ParkingSoundChanged` | The parking-sound directions change |
| `TrailerHitchedChanged` | A trailer is hitched or unhitched |
| `HookObserved` | A hook point saw a value (needs `hooks.parking`) |

Events are delivered **after the fact**, asynchronously, on the plugin's worker thread. They are not a
way to veto or modify the stock behaviour; use hooks for that.

Listeners are cancelled when the plugin is disabled. `on` returns a `Subscription` if you want to stop
one earlier.

## Hooks

Hooks are declarative: you register a veto or an observation, and the stock side reads it. **Plugin code
never runs on a stock thread.**

```java
Veto veto = ctx.hooks().veto(HookPoint.PARKING_SOUND_SET);
veto.setActive(true);
...
veto.release();
```

```java
ctx.hooks().observe(HookPoint.PARKING_SOUND_UPDATE, observed -> {
    SoundDirections value = (SoundDirections) observed.value();
    boolean wasVetoed = observed.vetoed();
});
```

| Point | Meaning |
|---|---|
| `PARKING_SOUND_SET` | A parking-sound value is about to be applied |
| `PARKING_SOUND_UPDATE` | A parking-sound value was observed |

Hooks need the `hooks.parking` permission and `Features.HOOK_PARKING`. Without the feature, registration
throws `UnsupportedOperationException`.

A veto is a flag, not a callback: it blocks the stock behaviour while active. `release()` clears it; the
SDK also releases it when the plugin is disabled.

## What is not available

- No arbitrary stock method hooks. Only the hook points above.
- No direct DSI or stock HMI classes. The plugin class loader hides them.
- No raw sockets. Use `ctx.net()` for TCP queries (loopback by default, outbound with `network.outbound`).
- No custom drawing or MENU-ring entries.

## Related

- [Safety model](../explanation/safety-model.md)
- [Permissions](../reference/permissions.md)
- [Networking](networking.md)
