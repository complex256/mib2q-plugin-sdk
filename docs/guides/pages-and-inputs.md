# Pages and inputs

A plugin page is a declarative `PageSpec`: a title and a list of rows. The runtime draws it with the
HMI's own cells, checkboxes, sliders, dropdowns and dialogs. You never draw anything yourself.

```java
Page page = ctx.ui().page(
        PageSpec.of("main", "My plugin")
                .item("status", "Status", "Idle")
                .toggle("enabled", "Enabled", true)
                .info("Changes are saved automatically."),
        action -> handle(action));
```

`ui.page` also lists the page under your plugin's entry in the **Plugins** menu. To put it in the Car
drawer:

```java
ctx.ui().carMenuRow("My plugin", page, ctx.ui().stockIcon(StockIcons.STAR));
```

A plugin may have at most 8 pages and 2 Car rows. See [Limits](../reference/limits.md).

## Page and item ids

- Page ids and item ids match `[A-Za-z0-9._-]{1,32}`.
- Item ids are unique within a page.
- A page's key for the test harness and the menu is `<plugin id>/<page id>`.

An invalid id throws `IllegalArgumentException`.

## Rows

| Builder | Draws | Action |
|---|---|---|
| `item(id, label, value)` | A list row with a value column | `select` |
| `item(id, label, value, icon)` | The same with a 24 px icon | `select` |
| `toggle(id, label, on)` | The native checkbox | `requestedOn()` |
| `info(text)` | A text line | none |
| `link(id, label, target)` | A row that opens another page | `select` |
| `choice(id, label, options, selected)` | A list row that opens an option list | `value()` = index |
| `dropdown(id, label, options, selected)` | An inline drop-down | `value()` = index |
| `slider(id, label, min, max, step, value, centred)` | A slider on its own screen | `value()` = level |
| `progress(id, label, percent)` | A progress bar | none |

Text is never rejected for length; the runtime truncates it. Counts are enforced: a 25th entry throws
`IllegalStateException`.

### Toggles

A toggle does not flip by itself. The HMI sends the requested state and keeps showing what your next
update says:

```java
private void onAction(UiAction a) {
    if ("enabled".equals(a.itemId())) {
        enabled = a.requestedOn().booleanValue();
        storage.put("enabled", enabled ? "on" : "off");
        page.update(spec());
    }
}
```

### Sliders

```java
PageSpec.of("main", "Lights")
        .slider("brightness", "Brightness", 0, 100, 10, brightness, false)
```

Rules: `min < max`, `step >= 1`, `(max - min) % step == 0`, at most 24 steps, and the current value on
the grid. A centred slider (drawn from the middle, like a balance) needs an even step count. A page may
have at most 4 dropdown plus slider rows; they are drawn after the list rows.

A slider shows its position, not its number. If the number matters, show it in another row's value
column.

### Choice and dropdown

```java
String[] modes = {"Eco", "Comfort", "Sport"};
PageSpec.of("main", "Drive mode")
        .choice("mode", "Mode", modes, mode)
        .dropdown("fan", "Fan", new String[] {"Low", "Mid", "High"}, fan);
```

The pick arrives as `value()`, the index into your options array. `choice` opens a sub-page; its key is
`<pageKey>#<itemId>` and its option ids are `"0"`, `"1"`, ... The test harness's `choose(...)` opens it
for you.

### Disabled rows

```java
spec = spec.enabled("stop", false);
```

Only `ITEM`, `TOGGLE`, `LINK` and `CHOICE` rows can be disabled. A disabled row is greyed and cannot be
selected. Any other kind throws.

### Progress

```java
spec = spec.progress("copy", "Copying", percent);
```

`percent` is clamped to 0..100. Progress is display-only.

## Handling actions

```java
private void onAction(UiAction a) {
    switch (a.itemId()) {
        case "start" -> start();
        case "stop" -> stop();
        case "brightness" -> {
            brightness = a.value().intValue();
            storage.put("brightness", String.valueOf(brightness));
        }
        default -> log.warn("unknown item " + a.itemId());
    }
    page.update(spec());
}
```

`UiAction` gives you:

| Method | Value |
|---|---|
| `pageId()` | The page |
| `itemId()` | The row |
| `requestedOn()` | The requested checkbox state; null for other rows |
| `value()` | Option index, slider level, or 1/0 for confirm; null otherwise |
| `text()` | Speller text; null otherwise |

Actions arrive on the plugin's worker thread, one at a time. Rebuild the whole spec from your fields and
call `page.update(spec)`; that is the pattern every sample uses.

## Dialogs

Dialogs need api 5 and `Features.UI_INPUTS`. They open only while one of your pages is the open page.

### Confirm

```java
ctx.ui().confirm("Stop the timer?", "The brew will be lost.", "Stop", "Keep brewing", answer -> {
    if (answer.value().intValue() == 1) {
        stop();
    }
});
```

- At most 3 lines and 120 characters.
- `value()` is 1 for Yes and 0 for No, BACK, dismissed, or a dialog that could not be shown.
- The listener is called exactly once unless you cancel the returned `Subscription` first.

### Toast

```java
ctx.ui().toast("Coffee is ready", cupSmall);
```

- 20 characters, optional 20 px icon, 3 seconds.
- Needs `ui.slot.statusbar`; without it, `PermissionDeniedException`.
- Inert without `Features.UI_INPUTS`.

### Speller (experimental)

```java
ctx.ui().speller("Name", initial, 32, done -> {
    String text = done.text(); // null if the speller never showed
});
```

The HMI speller has no OK key: BACK leaves it and counts as done. `text()` is null when the speller never
showed or was dismissed because its page closed.

## Missing features

When the unit cannot draw the UI this boot, `page`, `carMenuRow`, `statusItem`, `toast`, `confirm` and
`speller` still return handles, but they are inert. Argument and count checks still apply. Check
`ctx.has(Features.UI_PAGES)` or `Features.UI_INPUTS` when the difference matters.

## Related

- [Status line and toasts](status-line.md)
- [Icons](icons.md)
- [Limits](../reference/limits.md)
