# Test your plugin

There is no emulator for the head unit. Host tests use the **real SDK runtime** on a **fake car**, so
the lifecycle, permissions, UI table, storage and scheduler behave as they do on the unit.

## The one-line test

```java
@Test
void startsEnabled(@TempDir File tmp) {
    try (PluginTestHarness h = PluginTestHarness.start(tmp)) {
        assertEquals("ENABLED", h.state());
    }
}
```

`PluginTestHarness.start(tmp)`:

1. copies the built plugin jar into a temporary "unit" under `tmp`;
2. writes `sdk.properties` with the start delay, heap guard and UI throttle off;
3. writes `grants.properties` granting every permission the manifest declares;
4. installs the icons as mibpkg does, under their content hashes;
5. starts the runtime and waits until the plugin is `ENABLED`.

The `mibsdk.plugin` convention passes `-Dmibsdk.pluginJar` and `-Dmibsdk.pluginIcons` to every test, so
the harness needs no paths in your code. `./gradlew test` builds the jar first and re-runs the tests when
the jar or the icons change.

## Driving the fake unit

| Call | What it does |
|---|---|
| `h.ui()` | The fake screen (`FakeUi`) |
| `h.car()` | The fake car (`FakePlatform`) |
| `h.pageKey("timer")` | A page key: `<plugin id>/<page id>` |
| `h.openPage("timer")` | Opens a page through the plugin's Plugins-menu link |
| `h.advance(ms)` | Moves the scheduler forward |
| `h.await(condition, what)` | Waits up to 5 s; on failure prints the pages and the runtime log |
| `h.state()` | `ENABLED`, `FAILED`, `BLOCKED_PERMISSIONS`, ... |
| `h.log()` | The runtime log |
| `h.storedValues()` | Stops the runtime, flushes storage and returns it |
| `h.restart()` | Stops and starts on the same directory, keeping storage |

`FakeUi` drivers:

| Call | Use |
|---|---|
| `carRows()` | The Car drawer, Plugins row first |
| `selectRow(i)` | Selects a Car row |
| `render(pageKey)` | One text line per page entry |
| `select(pageKey, itemId)` | Presses an item or link |
| `toggle(pageKey, itemId)` | Presses a checkbox |
| `turn(pageKey, itemId, value)` | Moves a slider |
| `pick(pageKey, itemId, index)` | Picks a dropdown option |
| `choose(pageKey, itemId, index)` | Opens a choice sub-page and picks an option |
| `statusLine()`, `statusParts()`, `statusColours()`, `statusContexts()` | The status line |
| `toast()` | The current toast |
| `dialog()`, `answer(i)`, `type(text)`, `leave()` | Confirm and speller |
| `openPageKey()` | The page currently on screen |

Every driver does nothing when the entry is not on the page yet or is disabled. After an action that
changes the page, await the change before the next one. The plugin's worker updates the UI
asynchronously, so an assertion immediately after `select` can race it.

## Rendered text

`render` uses a fixed text format (`UiPageText.line`):

| Entry | Rendered as |
|---|---|
| Item | `Label: value` |
| Toggle | `[on] Label` / `[off] Label` |
| Slider | `Label: 4 [1..10]` |
| Choice / dropdown | `Label: option` |
| Progress | `Label: 60%` |
| Info | the text |
| Icon | a tag: `[png:<sha16>]` or `[star]` |

`h.iconSha("cup")` returns the 16-character hash used in the tag.

## Grants, features and failures

```java
// No permissions granted: start fails cleanly instead of throwing.
PluginTestHarness h = PluginTestHarness.builder(tmp).grants().startExpectingFailure();
assertEquals("BLOCKED_PERMISSIONS", h.state());

// A unit without native inputs: sliders render as text lines and toasts are inert.
PluginTestHarness h2 = PluginTestHarness.builder(tmp)
        .features(Features.UI_PAGES, Features.SLOT_CAR_MENU)
        .start();
```

By default the fake unit has every feature and every declared permission.

## Storage across restarts

```java
Properties before = new Properties();
before.setProperty("minutes", "2");
PluginTestHarness h = PluginTestHarness.builder(tmp).storedValues(before).start();

h.ui().select(h.pageKey("timer"), "start");
h.advance(60_000);
assertEquals("2", h.storedValues().getProperty("minutes"));
```

`storedValues()` stops the runtime, so call `h.ui()` again after `h.restart()`.

## The fake car

`FakePlatform` lets you change the unit's state:

```java
h.car().setIgnition(false);                       // publishes IgnitionChanged
h.car().ecuSetSoundDirections(new SoundDirections(true, false, true, true));
h.car().ecuFailWrites(1);                          // next parking write throws
h.car().ecuDropWrites(1);                          // next write is silently lost
h.car().simulateHookCall(HookPoint.PARKING_SOUND_SET, value);
```

See the [Java API reference](https://complex256.github.io/mib2q-plugin-sdk/api/) for the full
`PluginTestHarness`, `FakeUi` and `FakePlatform` contracts.

## What host tests do not cover

- Real HMI rendering, focus and timing.
- The stock services and DSI behaviour.
- Native daemons and the real parking ECU.
- Memory pressure on the 60 MB heap.

Test those on a bench unit, then a car, using [Install and debug](install-and-debug.md).
