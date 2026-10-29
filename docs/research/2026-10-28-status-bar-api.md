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

Implemented on the branch: file-backed icons (the shared `IconIds` table, served by the System factory too),
per-item RGBA (`UiColor`, api 6), the animated vertical slide, and the Dwell/Animation rows on the SDK page
(saved to `statusbar.properties`, re-read by the bar each dwell). The global order UI was already in
`PluginsMenu`. `StatusItem.setColour` is api 6; the `hello-menu` sample's "Status text colour" exercises it.

## Next steps

0. **File icons (resolved path).** A grown `SdkSystemModelBank` with `ResourceLocatorModel`s was tried; a plain
   `LayoutContainerController` never registers a model view (only a partial popup's `setViews` does), so the
   models did not paint. The working route is the shared `IconIds` table: `SdkSystemScreenFactory.getImagePath`
   serves the same SDK image ids as the Car factory, and `StatusLayout.Cell` carries the file icon's id. Plugin
   PNG paths are global (Car, Media, System, EarlyApps), unlike the Car-only image ids the spike used.
1. `StatusBarHost` in `adapter-hqc`: attach once via the registry; create the clipped viewport; render rows
   (label/icon) from the ordered items; 5 s/300 ms animation; update on `currentHMIContext`/screen changes.
2. A `StatusLine` sink that uses the bar when available, the popup otherwise; wire the existing
   `statusItem` registrations into it.
3. Plugins -> Status bar settings page (dwell/animate/order) writing the file.
4. Icon provider that works on non-Car factories (the spike borrowed the Car factory); choose an SDK image id
   served everywhere or a file-backed `ResourceLocatorModel`.
5. Left-side scope: the clock is not hideable via the bar API; decide whether to leave it fixed or invest in a
   left-group rebuild/model-level approach later.

## Build and deploy on the bench

1. Uninstall the throwaway spike first (it points HMICar at `SpikeCarActivator`):
   `spikes/hmi-ui/run.sh uninstall 10.20.0.23`, then restart and `ssh rpi-bench keypanel up`.
2. Build the SDK package (bump its bench version, e.g. `0.3.0+bench.2`) with the host mibpkg from
   `~/complex-projects/car-session-2026-10-26/host/mibpkg-0.9.1-host-darwin-arm64`.
3. `MIBSDK_MPK=<new>.mpk installer/mibsdk-dev install 10.20.0.23`, restart, `keypanel up`.
4. Verify on the bench: the bar row on home/Car/Media, a plugin file icon, an RGBA item, one full 5 s/300 ms
   slide, and the two dwell/animation rows on Plugins -> SDK.

## Bench evidence

- `~/Downloads/bench-spike/statusbar-car-label.jpg` — label at bottom-right, clock visible.
- `~/Downloads/bench-spike/statusbar-clock-still-there*.jpg` — clock remains after the hide attempts.
- `~/Downloads/bench-spike/statusbar-media.jpg` — label follows the Media palette.
- `~/Downloads/bench-spike/statusbar-car-icon.jpg` — icon + text.
- `/tmp/carsession/bench-clip-car.jpg`, `bench-clip-media.jpg` — clipped two-row viewport, one row visible.

## Implementation findings (0.3.0+bench.8)

- **The bar could never attach.** `StatusSinks.show` only called `BarStatusLine.show` when `available()`, but only
  that show posted the attach job. It now tries the bar on every show: an unattached bar posts the attach job and
  throws, so the same call falls back to the popup and the next one retries. The bar's attach callback hides the
  popups on HMIEvent as soon as it has drawn the pending line.
- **The slide snapped.** The animation frames shared the applier's `HmiPoster` pending slot and were coalesced. The
  bar has its own poster now: queue one frame, wait for the HMI to apply it, then wait 16 ms.
- **The bar showed the popup's fair-share cut** (`TS up MQ ...`): `UiStatusPart.fullText` carries the item's whole
  text; `StatusLayout.of(..., true)` uses it for the bar, while the popup keeps the 24-character cut.
- **A `ResourceLocatorModel` does not paint in a plain layout container** (see next step 0): file icons use the
  shared image id, and the System factory serves the same SDK ids as the Car factory.
- The row label is left-aligned (`LabelRendererHigh.setAlignment(1, 4)`), like the popup's labels.
- Two items are expected on the bench: `daemon-status` (always) and `hello-menu`; `bike-rack`, `crashy` and
  `inputs-demo` only publish a status item while active/crashing.
