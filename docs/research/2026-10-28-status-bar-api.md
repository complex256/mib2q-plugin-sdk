# SDK status bar: spike results and API skeleton

Date: 2026-10-28. Bench MHI2Q (`10.20.0.23`, HMI `HMI_AU_MIB2_HQC_NAR_G22_C10_180803`), SDK
0.3.0+bench.1. Spike code is throwaway (`spikes/hmi-ui`, installed with `run.sh install --car-only`).

## Goal

Replace the floating popup status line (609600, top right) with items in the **built-in car status bar**:
right side first, per-context/screen rules, multiple plugins, SDK-owned rendering and settings. The popup line
stays as the fallback where the bar is unavailable.

## Spike findings

- **The bar is global, not a Java child of the screen.** `StatusbarControllerHigh` is created by the Kanzi
  template and registers itself in the terminal's `WidgetRegistry`:
  `screen.getTerminal() -> getWidgetRegistry() -> getStatusbarGapEventHandler()`.
  The same bar object persists across Car -> Media.
- **The right group** (`rightGroupContainer`, a `LayoutContainerController`) is 262x36 in the tested context.
  A `LabelController` added to it renders at the bottom-right; stock clock/left group untouched.
- **Text + icon** work. `LabelController` + `LabelRendererHigh` + `FontFactory.getFonts(0, 0, fw)`;
  `IconController` + `IconRendererHigh` with an image id. The image id must exist in the screen factory's
  image set (`609105` was missing; `609110` rendered).
- **Persistence:** the item survives a Car -> Media screen change. The palette follows the app context
  (red in Car, amber in Media) through the colour indices `{1,2,4,0}`.
- **Clipping:** a `ContainerController` + `ContainerRendererHigh` viewport (200x24) with rows at y=0 and
  y=24 shows only the first row on both screens. The container clips as needed for vertical pagination.
- **Context mapping:** each screen's `StatusBarStubController` reads ChoiceModel **138** and calls
  `setCurrentHMIContext(n)` on the bar. Measured: Car screen `600044` -> model 138 = 0; Media `200061` ->
  model 138 = 2; the bar's `currentHMIContext` was 2 on Media (`-1` on Car at the probe instant, i.e. the
  stub had not pushed it yet). Screen IDs and contexts are different layers: several screens share a context.
- **Clock hide: not solved.** `setVisible(false)`, `setChildOnScreen(clock,false)` and
  `setChildVisible(clock,false)`, re-applied every second, do **not** hide the clock; the label renderer
  keeps drawing it. Right-side stock icons are model/visibility driven (`setIconVisibility`), which is the
  hide hook for that side.
- **Status items are already registered by plugins** through `ctx.ui().statusItem(...)`; the runtime composes
  them into the popup line today (`StatusLine`/`SdkPopupStatusLine`).

## API shape (right side first, `ui.statusbar`)

The status bar is **SDK-owned**. Plugins only register items:

```
ctx.ui().statusItem("rack")
    .text("RACK")
    .icon("bike-20")
    .priority(10)                  // hint for this plugin's items only
    .visibleIn(HmiContext.CAR)     // optional, default everywhere
```

No `paginate`/`dwell`/`animate`/ordering calls for plugins. The SDK:

- merges all registered items (`pluginId/itemId` stable keys);
- sorts by the **global user order**, then the priority hint, then registration order;
- applies per-context/screen visibility;
- renders a clipped vertical viewport in the bar's right group;
- paginates with the user's dwell/animation settings;
- falls back to popup 609600 where the bar is unavailable.

User settings live in **Plugins -> Status bar**:

- enabled, dwell (default 5000 ms), animation (default 300 ms);
- global item order across plugins (reorder, enable/disable);
- later: per-item configuration.

Settings file: `/mnt/persist/mibsdk/statusbar.properties` (a few hundred bytes, written only on user action).
An SD card is optional for export/import because it can be absent; the persist partition is the reliable
place (the SDK already writes `boot.state` and `ledger.properties` there).

### Context vs screen

- `HmiContext` (from model 138) is the primary key and matches what the stock bar uses.
- A screen ID is a finer optional override for exceptions.
- The user's order is global across contexts (owner decision).

## Pagination

- Rows are stacked in a clipped viewport inside the right group; one row visible at a time.
- Default dwell 5 s, vertical slide ~300 ms, wrap at the end.
- Rows are built from the ordered item set for the current context; the viewport/container clips (spike
  confirmed).
- Pause while the user interacts; resume after the idle timeout (design).

## Skeleton in this branch

- `runtime/.../StatusBarConfig.java` — settings model; defaults, clamping, properties load/save (temp + rename
  + fsync), global order and `rank(key)`.
- `runtime/.../StatusBarItems.java` — merges registered items: user order first, then priority/sequence.
- `StatusBarConfigTest` — defaults, round-trip, clamping, order dedupe, merge order.
- `adapter-hqc/.../BarStatusLine.java` — attaches lazily through the terminal widget registry, creates the
  clipped `CompositeRendererHigh` viewport (200x24) in the right group, builds one row per status part
  (indicators + items) from `StatusLayout` and swaps the visible row every `StatusBarConfig.dwellMs()`.
  Built-in icons keep their own colours. It reports `available()` only after attachment; before that it
  throws so `StatusSinks` falls back to the popups.
- `StatusSinks`/`UiApplier.create` — bar first, then SDK popup 609600, then popup 65.

Still to come in the host: custom file-backed icons, per-item RGBA colour and the animated slide (the
viewport is clipped and the rows share bounds, so the swap is ready to become a vertical translation).
The global order UI already exists in `PluginsMenu` (per-plugin status toggle + move earlier/later); the
remaining settings page only needs dwell/animation rows on the SDK page and the config save.

## Next steps

1. `StatusBarHost` in `adapter-hqc`: attach once via the registry; create the clipped viewport; render rows
   (label/icon) from the ordered items; 5 s/300 ms animation; update on `currentHMIContext`/screen changes.
2. A `StatusLine` sink that uses the bar when available, the popup otherwise; wire the existing
   `statusItem` registrations into it.
3. Plugins -> Status bar settings page (dwell/animate/order) writing the file.
4. Icon provider that works on non-Car factories (the spike borrowed the Car factory); choose an SDK image id
   served everywhere or a file-backed `ResourceLocatorModel`.
5. Left-side scope: the clock is not hideable via the bar API; decide whether to leave it fixed or invest in a
   left-group rebuild/model-level approach later.

## Bench evidence

- `~/Downloads/bench-spike/statusbar-car-label.jpg` — label at bottom-right, clock visible.
- `~/Downloads/bench-spike/statusbar-clock-still-there*.jpg` — clock remains after the hide attempts.
- `~/Downloads/bench-spike/statusbar-media.jpg` — label follows the Media palette.
- `~/Downloads/bench-spike/statusbar-car-icon.jpg` — icon + text.
- `/tmp/carsession/bench-clip-car.jpg`, `bench-clip-media.jpg` — clipped two-row viewport, one row visible.
