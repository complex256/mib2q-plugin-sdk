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

(Filled in by Plan 2 Tasks 1–2.)
