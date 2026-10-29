# Storage and timers

Plugins get a small persistent key/value store and a scheduler that runs work on the plugin's own
worker thread. Both are cancelled or flushed automatically when the plugin is disabled.

## Storage

```java
Storage storage = ctx.storage();

String minutes = storage.get("minutes", "4");
storage.put("minutes", "7");
storage.remove("minutes");
```

| Rule | Value |
|---|---|
| Type | String keys and string values, per plugin |
| Persistence | Survives reboots |
| Per-plugin cap | 256 KiB by default |
| All-plugins cap | 1 MiB by default |
| Location | `/mnt/persist/mibsdk/data/<id>.properties` |

A `put` that would exceed a cap throws `IllegalStateException` and stores nothing. Removing keys or
storing shorter values always works. The caps are set in `sdk.properties` on the unit.

Storage is written shortly after a `put` and on shutdown. It is shared with the stock persistence
partition, so keep data small.

### Parse defensively

Stored values can come from an older version of your plugin, or be missing:

```java
private int storedMinutes() {
    try {
        int m = Integer.parseInt(storage.get("minutes", "4"));
        return m >= 1 && m <= 10 ? m : 4;
    } catch (NumberFormatException e) {
        return 4;
    }
}
```

Do not assume a key exists, and do not assume its value is valid.

## Scheduler

```java
Scheduler scheduler = ctx.scheduler();

Subscription once = scheduler.after(5_000, () -> ctx.log().info("five seconds later"));
Subscription tick = scheduler.every(60_000, this::onMinute);
tick.cancel();
```

| Method | Behaviour |
|---|---|
| `after(delayMs, task)` | Runs once, no sooner than `delayMs` from now. A negative delay counts as 0 |
| `every(periodMs, task)` | Runs every `periodMs`, starting one period from now |

- `periodMs` must be at least 1.
- A run that comes due while the previous one is still queued is skipped, not piled up, so a slow task
  does not produce a burst of catch-up runs.
- Cancelling twice is harmless.
- Everything scheduled is cancelled when the plugin is disabled, quarantined or reloaded.

### One worker, one task at a time

`after` and `every` tasks run on the plugin's single worker thread, like every other callback. They never
overlap `onEnable`, page actions, event listeners or another task, and they never run on an HMI thread.

That means a slow task delays the rest of your plugin. The SDK logs a callback over 5 s as slow and
quarantines a callback that runs for 30 s. Keep tasks short; use `ctx.net()` for bounded I/O (3 s
timeout).

Do not start your own `Thread` for scheduled work. The SDK cannot cancel or quarantine it, and it will
still be running after the plugin is disabled.

## Testing storage and timers

```java
Properties before = new Properties();
before.setProperty("minutes", "2");
PluginTestHarness h = PluginTestHarness.builder(tmp).storedValues(before).start();

h.ui().select(h.pageKey("timer"), "start");
h.advance(60_000);                    // move the scheduler forward
h.await(() -> "Coffee 1m".equals(h.ui().statusLine()), "one minute");

Properties after = h.storedValues(); // stops the runtime and flushes storage
assertEquals("2", after.getProperty("minutes"));
```

`h.restart()` stops and starts on the same directory, keeping storage, so you can test that values come
back. Call `h.ui()` again after a restart.

## Related

- [Test your plugin](../getting-started/test-your-plugin.md)
- [Pages and inputs](pages-and-inputs.md)
- [Limits](../reference/limits.md)
