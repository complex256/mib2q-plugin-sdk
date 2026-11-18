> **Internal engineering record.** This page is kept for history and may be out of date. See [the internal docs index](../README.md).

# HMI UI injection: Car menu, pages, status line, threading

Date: 2026-10-20. Scope: what Plan 2 (UI) needs from the stock HMI of build `HMI_AU_MIB2_HQC_NAR_G22_C10_180803`.
Read-only survey; nothing here has run on a unit yet. Everything marked **[spike]** is settled by Plan 2's
Tasks 1–2 on the bench, and their results are appended to this note.

Sources (all outside this repo):
- Decompiled stock HMI, two trees of the same `lsd.jxe`: `lsd-src/lsd/` (real numeric constants) and `lsd_java/`
  (CFR; **numeric constants byte-swapped**). Paths below are package paths; cite `lsd-src` for any number.
- carplay-rgi `origin/main`: `java_patch/com/luka/carplay/tone/UpmixToneUi.java`,
  `java_patch/de/audi/tghu/tone/hmi/Activator.java`, the shadowed `StatusBarStubController` and
  `PartialPopupManagerEvoHigh`, `docs/LANDMINES.md`.
- The stock `bundles_dsitracer_enabled.properties` (`HMICar=de.audi.tghu.car.hmi.Activator`, line 121).
- `mib2q-glasshud` docs (native overlay), `mib2q-tailscale`, `mib2q-carmqtt`.

## 1. Prior art: carplay-rgi's Tone row

carplay-rgi adds one combo row to the Tone menu by **shadowing** `de.audi.tghu.tone.hmi.Activator`:
- The copy calls `super(10, "Tone", System.getProperty("variant.skin","EvoHigh"), newBank())` and overrides
  `getScreenFactory()` / `getConditionBank()`. On any `Throwable` it falls back to the stock bank and factory.
- The bank subclass grows `models[]` / `modelIDs[]` and adds a `ChoiceModel` (model id = module × 100000 + index;
  `AbstractModelBank.getModelIndex`). The bank must arrive through the constructor: `AbstractHMIActivator.start()`
  registers it as the `HMIModelBank` OSGi service. The factory is cached once (`cashedScreenFactory`).
- The factory subclass overrides `createScreen(int id, int terminal)`, calls super, clones a reference row into
  a new `MenuItemController`, and rebuilds `screen.setViews(ids, views)` with the ids **sorted** (`findViews`
  binary-searches).
- Labels are text ids written into `lang_data.zip` by its installer. Selection goes through the ChoiceModel's
  listener; no state-machine (SMM) event is involved.

## 2. Car menu: zero shadowed classes

`de.audi.tghu.car.hmi.Activator` is the same shape as Tone's (`super(6, "Car", skin, new CarModelBank())`;
creates `CarScreenFactory` and `CarConditionBank`). No stock class references it by name; LSD instantiates it
from the `HMICar=` line of the bundle list.

**The SDK already owns that bundle list** (its installer writes `bundles_dsitracer_enabled.properties`). So
instead of shadowing the activator, the installer points `HMICar=` at a **new SDK class**
(`mibsdk.adapter.hqc.ui.SdkCarActivator extends AbstractHMIActivator`) that mirrors the stock activator and
substitutes subclasses of `CarModelBank` and `CarScreenFactory`. This is the Tone pattern with **no shadowed
stock class**: a missing or failing SDK class can only affect the Car app, and the uninstall restores the
original line byte for byte.

The two Car lists (`lsd-src`):
- **Selection drawer `cARSEL`** — the left-hand "Vehicle" list opened by the left menu key in Car.
  `CarScreenFactory.getSelectionDrawers(n)`, body `CarScreenBag1.java:1047-1330`: `DrawerController(600043)`,
  `setHKReturnEvent(600173)`, a `SelectionMenuController` with 13 `MenuItemController` rows, type ACTION (2),
  sharing ChoiceModel `601227`, each `setEvent(6000xx)`. Visibility per row: a child
  `VisibilityProxyWidget(modelId)` plus condition widgets. Drawers are cached
  (`DrawerManagerEvoHigh.selectionDrawers`). **[spike]** that this is the list the owner calls the Car menu.
- **Car settings main**, screen `600002`, SMM state `carCarsettingsMain`: static rows (submenu 4, checkbox 8,
  rotary 128), events 600007..600014 etc.

Row mechanics (`MenuItemBuilder.configureKeyHandler`): no model → `StateMachineKeyHandler` fires the row's
SMM event; `ChoiceModel` + widget id → `ChoiceModelKeyHandler`; `OptionModel` → `OptionModelKeyHandler`; any
other model → `ButtonModelKeyHandler`. An SDK row therefore carries an SDK-owned model whose listener only
enqueues the press (no plugin code, no SMM event). **[spike]** that the listener fires.

`VisibilityProxyWidget.refreshVisibilityStatus` reads a ChoiceModel: 0 visible, 1 hidden, other disabled.
Because the drawer is built once (before plugins load), the SDK **pre-allocates N hidden rows** and shows them
by model value. **[spike]** that a cached drawer reacts to the value change.

Dynamic text: `LabelController.getText(int)` resolves through the screen factory's public, non-final
`AbstractScreenFactory.getText(int)` / `getText(int,int)` / `getAltText(int)`; `CarScreenFactory` does not
override them. The SDK factory serves a reserved text-id range from the UI snapshot. **[spike]** whether a row
re-reads its text after build (live relabel), or only a model-bound label (`LabelModel.setText`,
`setReplacementModelIds`) updates live.

Main MMI menu (MENU key): `KEY_MENU` is an app-change hard key routed to `SystemSMM`; there is no Java
"app list" screen, and a new top-level entry would mean patching the table-driven SystemSMM. **Not feasible
without shadowing; the Plugins entry is a row in the Car drawer** (the spec's named fallback).

## 3. Pages (templates)

- **Showing an SDK screen without an SMM state:** `FwHMI.showScreen(terminal, n2, IScreenData)`; `ScreenManager`
  uses `ScreenData.getScreen()` when non-null, so a prebuilt `Screen` can be shown directly. The SMM does not
  know the screen, so the SDK must handle BACK (`getPreviousConnectedScreenData()`). **[spike]**
- **Alternative (hijack):** an SDK row fires the event of an existing Car state and the SDK factory returns its
  own screen for that state's screen id; the SMM handles BACK, but each concurrent page needs a spare state.
- **List widget exemplar:** `EngineeringScreenBag2.engModeApplConfigBundles` (screen 1300014): `MenuController`
  holding `ListController.setModelID(..)`, an item factory that builds `MenuItemController(type 16)` +
  `LabelController.setModelColumn(0)`, data in `ListModel.addRow(new ListCell[]{TextListCell.create(s),
  IntegerListCell.create(0)})`, selection via `ListListener.itemSelected(modelId,row,…)`.
- **Toggle rows:** `MenuItemController` type CHECKBOX (8); `CheckboxController` accepts a `ChoiceModelGUI` or an
  `IntegerListCell` (example `CarScreenBag5.java:1146-1156`). Info text: a LABEL row bound to a `LabelModel`.
- `CarScreenFactory.createDefaultScreen` already builds a screen at runtime with `new LabelModel(0).setText(..)`.

## 4. Status bar and on-screen indicator

- `StatusBarStubController` (per screen, draws nothing) only tells the real bar which icons this screen allows.
- The real bar, `StatusbarControllerHigh` in partial popup **62** (`SystemScreenBag2.partialPopupStatusbar`), is
  the bottom band (0,415,1024,65). Its cells are a **closed switch of types, each bound to a fixed stock model**
  (clock 55, Bluetooth 3940, provider name 3889/506, …). **There is no spare cell**; adding one means shadowing
  `StatusbarControllerHigh`/`SystemScreenBag2` (high risk), and repurposing a stock cell fights its stock writer.
- **Partial popup 65 "DebugInfos"** (`SystemScreenBag3.java:204`): a 255×24 label at the top right (769,0),
  `LabelController` on **LabelModel 416**, shown while **system ChoiceModel 1** is non-zero. Stock use: the
  engineering Mem/CPU line (`EngineeringApp.setRunMemPollTimer`), and `DABTuner$DsiUpListener` writes 416 from a
  DSI thread. This is the SDK's **status line**: one string that merges SDK indicators and plugin status items.
  Conflicts: the engineering Mem/CPU flag and DAB debug mode also write 416 (rare; accepted). **[spike]**
- Custom drawing (mib2q-glasshud: GLES2 window adopted by the DisplayManager) needs a shadowed
  `DisplayManagerMIB2High.defineContexts` and AF_UNIX; out of scope for Plan 2.

## 5. Threading

- The HMI thread is the e.solutions dispatcher **"HMIEvent"** (`HMIEventDispatcher`, `HMIEventQueue`;
  `fwservices.json`: HMIThreadPool prio 10, job time limit 200 ms; a blocked dispatcher is reported to the
  ErrorManager as "event dispatch thread hanging").
- **Model writes are thread-safe and marshalled:** `LabelModel.setText` / `ChoiceModel.setValue` update under a
  mutex and post a coalesced `ModelUpdateEvent` to HMIEvent (`AbstractModel.fireModelUpdateEvent`,
  `HMIEventQueue$ModelUpdateFilter`). Stock DSI threads do this routinely. So SDK threads write App-side models
  directly, and never touch widgets or `*ModelGUI` off-thread.
- **Widget-tree work** (building screens, `showScreen`) runs on HMIEvent: post with
  `HMIService.getEventDispatcher().postEvent(new RunnableEvent(false, r))` (`false`: not before the first
  screen). `FwHMI.showPartialPopup` already posts itself when called off-thread.
- `createScreen` / `getSelectionDrawers` / `getText` are called on HMIEvent by the stock framework.

Consequence for the SDK (spec §6: no plugin code on stock threads): plugins describe UI declaratively; the
runtime folds every plugin's contribution into one immutable **UI snapshot**, published through a volatile
reference (the `HookTable` pattern). Stock-thread code (factory overrides, model listeners, the SDK's posted
`RunnableEvent`) only reads that snapshot and drops user input into a bounded ring; the runtime routes input to
the owning plugin's executor.

## 6. Daemon status endpoints

Neither daemon has a TCP status endpoint; J9's Foundation profile has no AF_UNIX.
- **mib2q-tailscale** (Go; upstream tailscaled, started by its own `tailscale.sh` from a startup hook): state is
  in the LocalAPI on `/tmp/tailscaled.sock` (`GET /localapi/v0/status`: `BackendState`, `TailscaleIPs`,
  `CurrentTailnet`, per-peer `Online`/`LastHandshake`). The script's watchdog already probes with timeouts
  because tailscaled can wedge. Smallest change: a sidecar `cmd/tsstatus` (no tailscale import) that polls the
  LocalAPI every ~10 s with a 5 s timeout and serves the reduced result on **127.0.0.1:19850**.
- **mib2q-carmqtt** (Rust, std threads; mibpkg service `carmqtt`): broker state is
  `Publisher::is_connected()`; last publish and ExLAP connection are not tracked yet. Smallest change: a shared
  `Status` of atomics set at the existing connect/drop/publish/authenticate points, served by one thread on
  **127.0.0.1:19851**.
- Format for both: connect, server writes `key=value` lines and closes. Ages in seconds, never wall-clock times
  (the unit boots at 1970). Ports in use to avoid: 1796, 8099, 19800, 19810, 19820, 19832, 25010, 80/8080.

## 7. Open points, settled by the Plan 2 spikes

| # | Question | Go means |
|---|----------|----------|
| S1a | Is `cARSEL` the Car menu (the left drawer in Car)? | The rows logged from `getSelectionDrawers` match the drawer on screen |
| S1b | Does an `HMICar=` substitution boot and behave like stock? | Car menu, drive select and settings work; keypanel `/status` stays connected |
| S1c | Do pre-allocated hidden drawer rows appear when their visibility model flips after boot? | Row appears within 1 s, without reopening Car |
| S1d | Does an SDK label update live? | A factory `getText` change or a model-bound label shows the new text the next time the drawer opens |
| S1e | Does a row press reach an SDK model listener (on HMIEvent) without an SMM event? | Listener logs the press; stock Car state unchanged |
| S3a | Does popup 65 show an SDK string set from an SDK thread? | Top-right label visible within 1 s on home, Car and media screens; value 0 hides it |
| S3b | How many characters fit popup 65? | Measured; becomes the status-line limit |
| S3c | Does a `RunnableEvent` that throws leave HMIEvent dispatching? | Screens still change; at most a log line |
| S2a | Does `FwHMI.showScreen` with a prebuilt `Screen` render, take knob focus and return on BACK? | All three, BACK lands on the screen it came from |
| S2b | If S2a fails: does the hijack path (SDK screen for a spare Car state) work? | Same three checks |
| S2c | Do list rows (`ListController` + `ListModel`) and checkbox rows render and report selection? | Rows render; selection and toggle reach the SDK listener |

## 8. Spike results

### Spike A (Plan 2 Task 1): Car drawer, labels, presses, status line

Date: 2026-10-19. Bench MHI2Q, `HMI_AU_MIB2_HQC_NAR_G22_C10_180803`, SDK installed (hello, bike-rack, crashy).

Setup:
- `spikes/hmi-ui` (`SpikeCarActivator` on the `HMICar=` line; see its README). One boot, three probe rounds.
- Round 0 ran unseen while the unit had no lease (`ssh rpi-bench keypanel up`). Rounds 1 and 2 were
  re-triggered with `/tmp/mibsdk-spike-ui.rerun` and watched on screen.
- Log lines are `/tmp/mibsdk.log`, tag `spike.ui`; the first number is ms since the runtime started.
- Frames are `docs/research/ui-spike-a-*.jpg`; the full `spike.ui` log is `docs/research/ui-spike-a-log.txt`.

| # | Result | Evidence |
|---|--------|----------|
| S1a | **go**: `cARSEL` (drawer 600043, a `SelectionMenuController`) is the left drawer in Car. Its 13 rows match the screen. | `13389 row 1 label=601352 text='Audi drive select' event=600050 widgetID=0 model=601227 type=2`, then rows 2..13 ('Charge', 'Range & statistics', 'Statistics', 'Vehicle settings', 'Driver assistance', 'Air conditioning', 'Auxiliary heating', 'Auxiliary air conditioning', 'Auxiliary heating / AC', 'Service & checks', "Owner's manual", 'Sportanzeige' visible=false). Child 0 is a `SelectionMenuIdleTimerController`, not a row. Frame `ui-spike-a-drawer-top.jpg` |
| S1b | **go**: the `HMICar=` substitution boots like stock. The drawer, drive select (`Vehicle: Audi drive select`) and Vehicle settings all open, and keypanel `/status` stayed `bridge_connected:true`. | `8075 activator constructed on fw[HMIThreadPool:2]Dispatcher-Startup`, `factory: SpikeFactory`. Frames `ui-spike-a-vehicle-settings.jpg`, `ui-spike-a-after-s3c-car.jpg` |
| S1c | **go**: a pre-allocated hidden row appears when its visibility model flips, while the drawer stays open (no reopen). Timing ≤ 1 s is not proven: the frames are ≥ 2 s apart. | `835604 probe T+0 S1c row1 visibility model 602739 := 0` with the drawer open (`ui-spike-a-live-before.jpg`); the row is present and focusable without reopening (`ui-spike-a-row1-shown.jpg`). Round 1 (`:= 1`) hid it again. |
| S1d | **go (factory `getText`)**: row 0's text changed **live, while the drawer was open**, without any input. **The model-bound label is no-go as wired**: `setReplacementModelIds({602748})` on the row plus an empty drawer replacement entry rendered `%1` literally. | `845606 probe T+10 S1d text[0]='SDK row 0 B2 [%1]'`. Frames `ui-spike-a-live-before.jpg` (B1) → `ui-spike-a-live-after.jpg` (B2), 12 s apart, no key pressed |
| S1e | **go**: an SDK row press reaches the SDK ChoiceModel listener on HMIEvent, with no SMM event. The drawer stays open and the Car state is unchanged. | `639562 press slot=1 thread=fw[HMIThreadPool:5]Dispatcher-HMIEvent model=602747`, `874609 press slot=0 ...`. Frames `ui-spike-a-press-row1.jpg`, `ui-spike-a-press-row0.jpg` |
| S3a | **go**: popup 65 shows SDK text set from an SDK thread (`mibsdk-spike-ui`) via `getLabelModel(416).setText` + `getChoiceModel(1).setValue(1)`. It is visible top right on Car, media and home. `setValue(0)` hides it. | `S3 status len=15 ... choice1=1`, then `len=0 ... choice1=0`. Frames `ui-spike-a-status15-car.jpg`, `ui-spike-a-status20-media.jpg`, `ui-spike-a-status25-home.jpg`, `ui-spike-a-status-hidden-home.jpg` |
| S3b | **measured: 25 characters fit** (uppercase ruler `ABC…Y`). With 30 characters, the stock label truncates to about 25 + `…`. Proposed `STATUS_MAX_CHARS = 24`, one character of margin for the proportional font. | Frames `ui-spike-a-status25-home.jpg` (all 25 shown), `ui-spike-a-status30-home.jpg` (`…UVWXY...`) |
| S3c | **go**: a `RunnableEvent(false, r)` whose `r` throws runs on HMIEvent, and dispatching continues. The screens kept changing (home → Car → home), and the log has only our own line. | `781586 S3c runnable on fw[HMIThreadPool:5]Dispatcher-HMIEvent, throwing`. Frames `ui-spike-a-after-s3c-car.jpg`, `ui-spike-a-status-hidden-home.jpg` |

Confirmed values for `HqcUiIds`:
- `CAR_MODULE=6`.
- `MODEL_BASE=602738`: `CarModelBank` has `models.length=2738` and `modelIDs.length=1147`. 11 grown models
  (602738..602748) were all created on HMIEvent when the drawer was first built.
- `TEXT_BASE=609000`.
- `DRAWER_ROW_TYPE_ACTION=2`, `DRAWER_SELECTION_MODEL=601227`. Drawer id 600043. Stock view ids:
  600815 601005..601010 601172 601227 601234 601672 1700231.
- `POPUP_STATUS=65`, `STATUS_LABEL_MODEL=416`, `STATUS_VISIBLE_CHOICE=1`, `STATUS_MAX_CHARS=24` (25 measured).
- System-model accessor: `IFrameworkAccess.getHmiServiceApp()` (the activator's `getFramework()`; class
  `de.audi.tghu.fwhmi.evo.FwHMIEvo`), then `.getLabelModel(416)` / `.getChoiceModel(1)`. For `postEvent`,
  `AbstractModelBank.getHMIservice().getEventDispatcher()` works.

Notes for Tasks 9–10:
- The drawer is built once, on HMIEvent, about 13 s after the runtime starts. That is **before plugins load**
  (30 s), so pre-allocating hidden rows is required, and it works.
- New rows need:
  - sorted `setViews` entries for each visibility model, plus the shared selection model mapped to all SDK rows;
  - no event and no key handler: `MenuItemBuilder` picks `ChoiceModelKeyHandler` and wraps it in a
    `CloseDrawerKeyHandler`.

  The stock rows' bitmaps were copied from row 1, so every SDK row shows the drive-select icon. The real rows
  need their own icon (or none).
- The text updates live through the factory `getText`, so model-bound labels are not needed. No S1d fallback
  is required.
- The selection drawer closes itself after a few seconds idle (the `SelectionMenuIdleTimerController`). Any
  keypanel `rotate` sent after that moves the focus on the underlying Car screen instead. Send
  rotate+select within ~1 s.
- None of the gates is no-go, so no fallback branch applies. Tasks 9–10 follow the main path.

### Spike A follow-up checks (run during spike B, 2026-10-19)

Pre-spike state: the unit's `bundles_dsitracer_enabled.properties` differed from the SDK's stock backup only by the
SDK's own three lines (configurator, `MibSdk` autostart, `MibSdk=` bundle); `HMICar=de.audi.tghu.car.hmi.Activator`,
no spike jar in `lsd/jars`, no `spike.ui` lines in the log. Spike A left the unit clean. A byte copy of that file was
taken before spike B and the file was `cmp`-identical to it after the uninstall.

| Check | Result | Evidence |
|-------|--------|----------|
| S1c timing | **go, ≤ 1 s.** With the drawer open and focus on SDK row 2, the row-1 visibility model was flipped to 0 by a command file (polled every 100 ms). The row appeared between `SDK page (showScreen)` and `SDK re-press test` without any input. The first frame showing it is 0.73 s after the command file existed (frames are a ~5 fps mirror, so ±0.2 s). | `470627 cmd S1c row1 visibility := 0`. Frames `ui-spike-b-s1c-absent.jpg` → `ui-spike-b-s1c-present.jpg` (0.26 s apart) |
| Drawer idle behaviour | **The drawer does not auto-close when idle.** Opened with no further input it stayed open ≥ 17 s; after one rotate it stayed open ≥ 87 s. It closes **~8.5–9 s after a row press** (open in the frame at +8.4 s, closed at +9.0 s); after that, input reaches the screen underneath (drive select). Spike A's "falls through after a few idle seconds" was this post-press close. | Frames `ui-spike-b-drawer-idle-14s.jpg`, `ui-spike-b-drawer-idle-87s.jpg`, `ui-spike-b-press-drawer-open-8s.jpg`, `ui-spike-b-press-drawer-closed-9s.jpg` |
| Re-press | **go, no reset needed.** Pressing the same SDK row again while the drawer is still open fires `itemSelected` again: 2 s apart (`603665`, `605665`) and 5 s apart (`647671`, `652671`), with no selection-model reset. The listener sees `modelValue=-1` every time (`ChoiceModelKeyHandler` calls `itemSelected(widgetID)` without setting the value). The reset-to--1 variant also fires twice (`566641`, `568641`) but changes nothing. One earlier pair "missed" only because the second tap came after the post-press close and went to drive select (left on Dynamic). | `ui-spike-b-log.txt` |

Note for Tasks 9–10 and bench driving: after an SDK row press, a second action in the drawer must come within ~8 s;
otherwise reopen the drawer first.

### Spike B (Plan 2 Task 2): SDK pages and row icon

Run 1 (2026-10-19) was stopped by the owner after one inconclusive S2a attempt: `showScreen` switched to the page, but
only the background, status bar and drawer handle rendered (`682681 page-show S2a … previous=id=10`, frame
`ui-spike-b-s2a-attempt1-blank.jpg`). Cause: a prebuilt `Screen` bypasses `AbstractTerminalContext.getScreenFromCache`,
which is where `Screen.setTerminal` is called. The fix sets `page.setTerminal(fw.getHMITerminal(0))` and copies the
current screen's colour scheme and screen mode into the `ScreenData`. Run 1 left the unit clean (`cmp`-identical
bundles file).

Run 2 (2026-10-24) booted that fixed jar. S1f was added: drawer row 2 has no bitmaps. Every `spike.ui` line of the boot
is in `ui-spike-b-log.txt` (section "run 2"); the first number on each line is ms since the runtime started.

| # | Result | Evidence |
|---|--------|----------|
| S2a | **go.** On the first press (row 0, from Car screen 600044), the page connects and renders: title, the LABEL row and 5 list rows. The knob moves the focus frame (LABEL row → list row 0 → row 3 …). An ACTION row press reaches the listener. BACK (key 15, re-show mode) returns to the screen the page came from (Car 600044, drive select still on Dynamic). A second show on the same boot works the same way (build+show 2 ms). The `nolabel-on` variant was not needed. | `1040610 page built id=609500 ownBack=true labelRow=true`, `page-show S2a screenData popups=0 colorScheme=1 screenMode=1`, `showScreen done build+show=8ms`, `page connected id=609500`; `1053620 page-focus row=0`, `1056620 page-focus row=3`; `1100626 page-select row=1`; `1102626 page-key press code=15 ownBack=true`, `page-back mode=reshow current=id=609500 … target=id=600044`. Frames `ui-spike-b-s2a-rendered.jpg`, `-focus-row0.jpg`, `-focus-checkbox1.jpg`, `-checkbox1-toggled-on.jpg`, `-action-selected.jpg`, `-back-to-car.jpg`, `-second-show.jpg` |
| S2b | **go.** Row 1 fires Car event 600023. The next Car `createScreen` (id 600033) returns the page, and it renders with the Car drawer handle. Knob focus, select and toggle all work. With BACK left to the SMM (`ownBack=false`), it returns to Car 600044 (drive select on Dynamic). Not the chosen path: S2a works, and a hijacked id stays cached as the SDK page until the HMI restarts. That was not checked on screen; see the concerns in the Task 2 report. | `1130630 page-hijack S2b firing event 600023`, `createScreen id=600033 …`, `page-hijack S2b createScreen(600033) -> SDK page`, `1131630 page connected id=600033`; `1146632 page-toggle row=4 value=1`, `1147632 … value=0`, `1149633 page-select row=1`, `1150633 page-key press code=15 ownBack=false`. Frames `ui-spike-b-s2b-drawer-hijack-row.jpg`, `-rendered.jpg`, `-focus-checkbox2.jpg`, `-back-to-car.jpg` |
| S2c | **go (gate criterion), with one gap.** The list rows render from the `ListModel`. Selection and toggle reach the SDK `ListListener` on HMIEvent, and the toggle writes the model: both checkboxes went 1, then 0, on both pages. **Gap:** the `CheckboxController` cell (column 1) draws nothing. A checked row looks exactly like an unchecked one (`-checkbox1-toggled-on.jpg` vs `-focus-checkbox1.jpg`), so Task 11 must find the stock checkbox cell recipe (bitmaps/renderer setup) or show the state as text in the value column. | `1073622 page-select row=3 …`, `page-toggle row=3 value=1`; `1076623 … value=0`; `1079623 page-toggle row=4 value=1`; `1082624 … value=0` |
| S1f | **go.** Drawer row 2, which has no bitmaps, renders its text legibly and takes focus (red focus frame). The icon slot draws as an empty dark square, and no other row is affected. `ROW_BITMAP_SOURCE = -1` (none) is acceptable; `1` (copy row 1's) is not needed. | Frame `ui-spike-b-s1f-noicon-focused.jpg` (focus on 'SDK re-press test') |

Values for Tasks 9–12:
- `HqcUiIds.PAGE_PATH = SHOW_SCREEN`: build the `Screen` on HMIEvent inside a `RunnableEvent`, `setTerminal(fw.getHMITerminal(0))`, then `FwHMI.showScreen(0, 0, new ScreenData(id, false, false, null, false, screen, cur.getPartialPopups(), null, -1L, -1L, 0, cur.getColorScheme(), cur.getScreenMode(), null))`. The page id used is 609500 (Car module, unused by the Car factory). If HIJACK is ever needed: event 600023 → screen 600033.
- BACK: the page's `MenuController.keyPressed` consumes key 15 (and its release) and posts a `RunnableEvent` that re-shows the saved `IScreenData` (`showScreen(0, 0, new ScreenData((ScreenData) from))`). Event 600173 was not tried.
- Widget recipe (as in `SpikePage.build`, modelled on `EngineeringScreenBag2.engModeApplConfigBundles`):
  - `PageScreen extends ScreenWidgetEVO` with `ScreenRendererHigh`, the stock palettes, `setSmallStageType(3)`, and the factory's ref widgets 0 and 1;
  - a body `ContainerController` with a `MenuController` (`MenuRendererHigh`, info line, glassplate, focus cursor, scrollbar);
  - an optional LABEL row (`MenuItemController` type 1, `CompositeRendererHigh`, on a `LabelModel`);
  - a `ListController` on a 3-column `ListModel` (text, value, kind; `setIdColumn(2)`), rows from a `ListItemFactory`: `MenuItemController` type 16 with a `GridLayout` of `LabelController` (column 0) and, for checkboxes, `CheckboxController` (column 1);
  - a head `ContainerController` with a `TitleBarWidget` (`LabelController` title from a factory text id), and a `StatusBarStubController` on model 138.
- Models used: `MODEL_BASE + 11` = 602749 (page `ListModel`), `MODEL_BASE + 12` = 602750 (LABEL row `LabelModel`).
- Gate: S2a go and S2c go → **Task 11 (native pages) runs; Task 12 (panel fallback) is skipped.** S1f go → `ROW_BITMAP_SOURCE = -1`.
