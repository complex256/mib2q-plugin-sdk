# Main MMI menu entry, row icons and status-line icons

Date: 2026-10-24. Scope: can the SDK put a "Plugins" entry in the top-level MMI menu (the MENU-key screen), can
SDK rows (main menu and Car drawer) carry an icon, ideally a custom one, and can SDK status items show a small icon.
Build `HMI_AU_MIB2_HQC_NAR_G22_C10_180803`. Host-only, read-only survey of the decompiled HMI and the firmware tree;
nothing here has run on a unit. Everything marked **[spike]** is settled by Spike C (§8).

Sources (all outside this repo):
- Decompiled stock HMI, two trees of the same `lsd.jxe`: `patching/lsd-src/lsd/` and `patching/lsd_java/` (CFR).
  `lsd-src` has the real numbers in most classes, but some of its classes (e.g. `CarScreenBag1`, `SystemSMM` model
  ids above 100000) are byte-swapped too. Numbers quoted from such places are given decoded and marked "(decoded)".
- Firmware tree `firmware/mmxfs/app/eso/hmi/`: `lsd/lsd.sh`, `lsd/bundles_dsitracer_enabled.properties`,
  `lsd/images/<factory>/<id>.png`, `lsd/kzbs/HMISystemEvoHigh/*.kzb`, `lsd/jars/lang_data.zip`, `fonts/`.
- This repo: `docs/research/2026-10-20-hmi-ui-injection.md` (the "UI note"), `adapter-hqc/.../ui/*`,
  `spikes/hmi-ui`, `docs/bench-e2e.md`.

**Correction to the UI note §2.** The UI note says "there is no Java app list screen" and that a top-level entry
would mean patching the table-driven SystemSMM. That is wrong. The MENU screen is an ordinary Java screen built by
the System screen factory (§1). Only the SMM *events* its rows fire are table-driven, and an SDK row does not need
one. The UI note is right that the route needs a stock activator to be replaced. Here that activator is `HMISystem=`
instead of `HMICar=`, and that changes the risk (§2.3).

## 1. How the top-level menu is built

- **Bundle and activator:** `HMISystem=de.audi.tghu.system.hmi.Activator` (bundles file line 62). The activator has
  the same shape as Car's: `super(0, "System", skin, new SystemModelBank())`, a cached `SystemScreenFactory`
  (`getScreenFactory()`, protected), and `getConditionBank()` → `new SystemConditionBank((SystemScreenFactory) …)`.
  No class outside `de.audi.tghu.system.hmi` refers to these classes by name (grep of `lsd-src`).
- **Screen:** System screen **46**, `SystemScreenBag2.mAINWIZARD` (`SystemScreenFactory.createScreen` case 46,
  `SystemScreenFactory.java:307`; body `SystemScreenBag2.java:1353-1625`). It is a `ScreenWidgetEVO(46)` holding a
  **`MainWizardController`** (a 3D "wizard ring", `MainWizardRendererHigh`) with **16 `MenuItemController` rows**.
  Each row is type 2 (ACTION) with `setKeyHandler(3)`. Key handler 3 is `StateMachineKeyHandler`: on SELECT (key 17)
  it calls `hmiService.fireSMEvent(terminal, getEvent())`.

  | Add order | Label id → en_US text | SMM event | widgetID (= icon) | Visibility |
  |---|---|---|---|---|
  | 1 | 244 Vehicle | 1528 | 0 | always |
  | 2 | 250 Sound | 1537 | 8 | always |
  | 3 | 245 Radio | 1538 | 1 | always |
  | 4 | 246 Media | 1530 | 2 | always |
  | 5 | 247 Telephone | 1536 | 3 | cond 473 / 522 |
  | 6 | 2272 Info call | 1977 | 13 | cond 363 / 4155 |
  | 7 | 248 Navigation | 1532 | 4 | cond 361 / 459 |
  | 8 | 222 Map | 1625 (model 138) | 5 | cond 361 / 459 |
  | 9 | 249 Audi connect | 1533 | 6 | cond 474 / 523 / 4252 |
  | 10–13 | 3197 Audi smartphone interface, 3198 Android Auto, 3199 Apple CarPlay, 4068 Baidu CarLife | 1959 | 18, 17, 16, 19 | cond 522 / 4044 / 4237–4239 / 5571 |
  | 14 | 2561 TPEG | 1991 | 15 | cond 442 / 523 |
  | 15 | 192 Office | 1666 (model 528) | 7 | cond 359 / 3939 / 4004 |
  | 16 | 251 Settings | 1534 | 9 | always, `setLockable(true)` (cond 5600) |

  The labels were decoded from `lang_data.zip` `resources/HMISystemEvoHigh/strings_en_US.data`: a big-endian count,
  then one start offset per id, then the text. The variant-coded entries are hidden by **condition widgets**:
  `screen.setConditionWidgets(int[] ids, AbstractWidget[][])` lists for each condition the rows it affects, and
  `SystemScreenFactory.executeConditionmAINWIZARDScreen` (`:2033`) calls `setVisible`/`setEnabled` on those rows from
  `ComponentConditionManager.isTrue(…)`. A row that is in no condition list is never touched.
  **[spike]** The on-screen order: the decompile gives the add order above, but the owner remembers the menu as
  "Navigation, Map, Audi connect, Media, Phone, Car …". The ring may be rotated so that it starts at the last app.
- **Model:** the controller and the Map row use **system ChoiceModel 138**, "wizard last application"
  (`AbstractFrameworkActionProxy.getWizardAppChoice`). `MainWizardController.keyTurned` writes the focused row's
  **widgetID** into 138, and SystemSMM sets it through `mainApplicationWizardLastApplication(terminal, n)` (values 0,
  4, 6, … in `SystemSMMActions`). SystemSMM guard 509 switches on 138 == 0..6, 8, 9 and has a default branch
  (`SystemSMM.java:254-285`).
- **Navigation:** SMM state **94** is the wizard state. Its event list is `{1, 1959, 2293, 1977, 1666, 1625, 1530,
  1529, 1535, 1526, 1534, 1527, 1537, 1536, 1538, 1532, 1533, 1528, 1647, 1991}` (`SystemSMMInitStates.java:141`).
  Selecting a stock row fires its event, and the table-driven SystemSMM changes app. MENU (`HK_MENU`) is an SMM
  hard-key event, as the UI note says.
- **Rendering:** `MainWizardRendererHigh` merges the Kanzi project of `kzbs/HMISystemEvoHigh/MAINWIZARD_*.kzb`
  (`mergeProject(9, 170, screenId)`). It draws **9 ring slots** (`mw_item0..8`) and takes text from
  `factory.getText(labelId, viewSize)`, so labels are live (cached per slot, compared every render).
- **Rows are collected from children:** `AbstractPlaceholderMenuController.add(widget)` wraps every added
  `MenuItemController` in an adapter (`AbstractPlaceholderMenuController$1`) and appends it to the item list. SELECT
  goes to the focused item: `AbstractPlaceholderMenuController.keyPressed` (`:490-534`) → `item.keyPressed` →
  `MenuItemController.keyPressed` → its key handler. **`MenuItemController.setKeyHandler(IWidgetKeyHandler)` is
  public**, so an SDK row can carry its own handler and fire no SMM event.

## 2. Adding a Plugins entry to the main menu

### 2.1 Route: `HMISystem=` takeover, the same pattern as `HMICar=`

- The installer points `HMISystem=` at `mibsdk.adapter.hqc.ui.SdkSystemActivator extends AbstractHMIActivator`. It
  copies the stock activator: `super(0, "System", skin, new SystemModelBank())`, the same condition bank, and
  `SdkSystemScreenFactory extends SystemScreenFactory`. On any `Throwable`, or when `SdkGlobals.uiEnabled` is false,
  it falls back to exactly the stock factory, as `SdkCarActivator` does.
- `SdkSystemScreenFactory.createScreen(int id, int t)` (protected, not final): call `super`. For id 46, find the
  `MainWizardController` child and `add()` one `MenuItemController`:
  - type 2;
  - `setLabelId(SDK text id)`, served by an override of `getText(int)`/`getText(int,int)`/`getAltText`, e.g. ids
    90000+ (System has 5384 strings);
  - `setWidgetID(<spare icon index>)` (§3.2);
  - colour indices and glass-plate insets copied from a stock row;
  - `setKeyHandler(new SdkWizardKeyHandler())`: on key 17 it consumes the press and posts a `RunnableEvent` that runs
    the SDK's existing page path (`FwHMI.showScreen` with a prebuilt Screen, as in the UI note §8 S2a).
  - No SMM event, no model, no condition. `add()` appends, so the row lands after Settings. A different position
    needs the children removed and re-added in the new order before the screen connects **[spike]**.
- The screen cache is a per-application LRU (`ScreenCacheImpl`), so screen 46 can be rebuilt at any time. A
  factory override sees every rebuild. Patching the live cached widget tree would not, so do not do that.
- The adapter must allow screen 46 as an origin. Today `PageScreens.openAllowed` accepts only Car screens
  (`isCarScreen`) and SDK pages.

### 2.2 What happens to …

- **BACK from the SDK page:** the page consumes BACK and re-shows the saved `IScreenData`, which is screen 46. The
  SMM never left state 94, so the two agree **[spike]**. The ring has an in/out animation (`mw_RingPos`), and it is
  open whether a re-show animates it **[spike]**.
- **Focus/scroll:** focus on the SDK row writes its widgetID (10, 11, 12 or 14) into model 138. Guard 509 then takes
  its default branch, as it does today for Info call (13) or TPEG (15). SystemSMM writes 138 only with values 0..9,
  so no stock path selects the SDK row by accident **[spike]** (what "default" does when MENU is pressed with focus
  on the SDK row).
- **Variant coding:** unaffected. The conditions change only the stock rows they list. The SDK row hides itself
  (`setVisible(false)` on HMIEvent when no plugin wants it) **[spike]**.
- **HOME/MENU keys** while an SDK page shows: the SMM is still in state 94, so MENU does whatever state 94 does on
  MENU. Probably it closes the wizard to the last app, which replaces the SDK page, and that is acceptable
  **[spike]**.
- **Startup ordering:** HMISystem is constructed before HMICar and draws the first screens (`mAINREFTEMPLATE`, init
  and error screens, standby). The SDK configurator is a `serviceDelegateConfigurator`, so it should already have run
  **[spike]** (log `configuratorSeen` from the System activator's constructor).
- **CarPlay/AA rows:** these are condition-driven rows with event 1959. They are untouched, and so is
  `TerminalModeActivator`.

### 2.3 Why this is riskier than the Car takeover

- The System module draws every screen: startup, errors, popups (volume, status bar, popup 65) and **the SWDL
  (firmware update) UI**. `lsd.sh` uses `bundles_dsitracer_enabled.properties` in `RUN_MODE=swdl` too (lines
  260-268).
- A class-load failure of the substituted activator (missing jar, `NoClassDefFoundError`) leaves no System module.
  The likely result is a blank HMI, while the Car takeover only ever cost the Car app. Recovery is SSH plus restoring
  the line.
- Mitigations:
  - the activator is stock whenever `System.getProperty("RUN_MODE")` is `swdl`;
  - the activator has no static initializer that can throw;
  - every override catches `Throwable` and falls back to `super`;
  - mibpkg's HMI-version guard must cover the second line;
  - mibpkg must manage `HMISystem=` like `HMICar=`: stock-value check, owned class
    `de/audi/tghu/system/hmi/Activator.class`, and byte-identical restore. That is a mibpkg change.

### 2.4 Alternatives considered

- **Repurpose a coded-off stock row** (CarLife 4068, TPEG, Office on NAR): this changes the same factory as adding a
  row, and the conditions may hide it again. No gain.
- **Runtime patch of the cached screen 46** from the Car side: it avoids the substitution, but it is lost on every
  LRU rebuild. Rejected.
- **The Car entry's own screen:** "Vehicle" (event 1528) opens Car, whose drawer already has the Plugins row. Moving
  the SDK rows to the **top** of the drawer (insert before the stock rows instead of appending) is the closest spot
  that needs no new substitution. It is a cheap change in `SdkCarScreenFactory.addRows` **[spike]** (insert order
  vs. `SelectionMenuController` focus restore).
- **Long-press of a hard key:** hard keys arrive as SMM events (`HK_*`). Without a shadowed class the SDK has no
  global key hook, so this is **no-go**.

## 3. Icons

### 3.1 How bitmaps are resolved (all rows except the main menu)

- A bitmap is an **int image id**. `HMIService.getImage(id, …)` → `ResourceManager.getImageImpl` → the `HMIBundle`
  of module `id / 100000` → `AbstractScreenFactory.getImagePath(id, terminal)` = `"<factory.getName()>/<id>.png"`.
  `AbstractImageLoader.loadImage` prefixes `ImageRoot`, which `lsd.sh` sets to `$BASE_DIR/lsd/images`
  (`/ramdisk/images` in `RUN_MODE=swdl`, where only System images are copied).
- So `images/HMICarEvoHigh/600023.png` is the drive-select icon. **The id's module, not the screen's, picks the
  bundle**, so a Car-module id works on a System screen and the reverse.
- `HMIImage` is just a path plus a pixel format, and the native EAL/Kanzi layer decodes the PNG. The format comes
  from `TargetImageInfo.getImageType(id)`. An unknown id returns -1 → RGBA8888 (format 6) and a warning log line.
- **`getImagePath(int,int)` is public and not final.** `SdkCarScreenFactory` can map an SDK id range (e.g.
  609100–609399) to any file: return a relative path such as `"../mibsdk/icons/609100.png"`, because the loader
  concatenates `ImageRoot + "/" + path` **[spike]**. The SDK's own activator serves that module, so no stock class
  changes.
- **File-path images without ids** exist too:
  - `IconController` accepts a `ResourceLocatorModel`/`HMIResourceLocator(String uri)`, a `String`, an `Integer` or a
    ChoiceModel index into `setBitmaps` (`IconController.calculateContent`, `lsd_java …/evo/widgets/IconController.java:180-215`);
  - `AbstractImageLoader.getResourceViaResourceLocatorPath` → `loadViaPath(uri)` → `new HMIImage(path, RGBA)`. Stock
    uses this for album art and the online provider logo (model 3838);
  - `SelectionMenuRendererHigh.getTextureDescription` (drawer rows) accepts icon data as `Integer` (id), `String`
    (absolute path → `HMIImage(path, 6)`) or `HMIResourceLocator` (`lsd_java …/SelectionMenuRendererHigh.java:509-560`).
  - Loading from a byte stream is **not** supported (`ImageLoaderMIB2High.loadViaStream` throws).
- **Caches:** images are cached by id (`guideBitmapCache`, the renderer's `textureMap`, `PlaceholderItemEntry.cachedIcons`;
  the latter is cleared on `setConnected(true)`). So a changed icon needs a **new id**, not a new file under the old
  id.
- Stock formats: drawer row bitmaps are `{icon 72×72, reflection 72×68, small 34×34}` in 8-bit grey+alpha. Car
  row 1 is `{600023, 600060, 600140}` (decoded). `SelectionMenuRendererHigh` uses index 0 as `Texture` and index 1 as
  `ReflexTexture`, and tints both with the row colour (white, or the focus colour). Custom icons should therefore be
  white-on-transparent RGBA PNGs plus a faded reflection.

### 3.2 Main menu icons are a Kanzi atlas, not bitmaps

- For the wizard, `getIconData` returns the row's **widgetID** (adapter `$1`, the `instanceof MainWizardController`
  branch). The renderer looks up `layout.getMainWizardIconDecoratorMapping()[widgetID] = {atlasIndex,
  decoratorIndex}` and sets the Kanzi properties `AtlasIndex` on the slot and on `mw_decorator`. The value is cast
  `(Integer)`, so a String or path would throw.
- The mapping is `LayoutMIB2HighQ7`/`LayoutMIB2HighScale` (identical): `{{0,0},{1,1},…,{11,11},{12,3},{13,3},{14,5},
  {15,5},{17,12},{18,12},{19,12},{20,12}}`, indexed by widgetID 0..19.
- The atlas is `iconAtlas.png`, embedded in every `MAINWIZARD_*.kzb`: 1848×176 RGBA, **21 cells of 88×88**, with the
  normal icon in the top row and the highlighted one in the bottom row. It is byte-identical in all 11 variants
  (md5 `6d7df432…`). Cells:
  - 0 car, 1 radio, 2 media, 3 phone, 4 nav flag, 5 map, 6 connect globe, 7 office, 8 sound, 9 settings;
  - **10 warning triangle**, **11 night vision**, **12 Audi headset**, 13 wrench with rings (Info call),
    **14 Audi "flower"**, 15 TPEG;
  - **16 red wrench (no widgetID maps to it)**;
  - 17 CarPlay, 18 Android Auto, 19 smartphone interface, 20 CarLife.
- **Free widgetIDs with a valid mapping: 10, 11, 12, 14** (no stock row uses them). Options:
  - (a) **reuse a stock icon**: give the SDK row widgetID 14 (Audi flower; decorator 5) or 12 (headset). This is
    cheap and needs no stock data changed.
  - (a') **the unused wrench (cell 16)**: overwrite the unused entry, `mapping[10] = {16, 9}`, in the static array the
    layout hands out. It is in place, and no stock row reads entry 10 **[spike]**. A wrench reads as "plugins/tools".
  - (b) runtime custom bitmap: **not possible** through this renderer (the icon index must be an atlas cell).
  - (c) installer-side custom icon: re-encode `iconAtlas.png` with a custom cell 16 inside each `MAINWIZARD_*.kzb`.
    The PNG length changes inside a Kanzi binary with internal offsets, and a firmware update overwrites the file.
    **Not recommended.**
  - A row with widgetID -1 is no fix either: the renderer logs "no mapping" and leaves the slot's previous
    `AtlasIndex`, so the row shows a stale icon from another row.

### 3.3 Recommended icon approach

| Where | Cheapest | Custom |
|---|---|---|
| Car drawer row | `setBitmaps` with a stock id pair, e.g. System `{422, 423}` or Car `{600023, 600060}` (proven by spike A) | `setBitmaps({sdkId, sdkId+1})`, with `SdkCarScreenFactory.getImagePath` mapping the SDK id range to RGBA PNGs the installer unpacks (one fresh id per distinct icon); fallback: a row that implements `PlaceholderMenuItem` itself and returns an absolute path String **[spike]** |
| Main menu row | widgetID 14 or 12 (stock atlas) | wrench via `mapping[10] = {16, 9}`; a real custom image only by editing the kzb (not recommended) |
| SDK pages (bonus) | `IconController` with stock ids | `IconController` on a `ResourceLocatorModel` holding a file path **[spike]** |

Plugin icons ship in the plugin package. The runtime validates them: PNG, RGBA, ≤ 72×72, ≤ 32 KiB. They go to a
directory under `ImageRoot`'s parent (e.g. `/mnt/app/eso/hmi/lsd/mibsdk/icons/`, written by mibpkg at install), or
to RAM (`/ramdisk/mibsdk/icons/`, which needs a deeper `../` path).

## 4. Status-line icons (question 5)

Today: SDK text, ≤ 24 characters, in the label of stock partial popup **65**. That popup is
`SystemScreenBag3.partialPopupDebugInfo`, `SystemScreenBag3.java:204-245`: bounds (769,0,255,24), `setSurviveScreenChange(true)`, slot 5, zpm slot 12. Its children are:
- an `IconController` with `setBitmaps({251})`, a 1×1 backdrop stretched to 255×24;
- the `LabelController` on model 416, bounds (10,7,235,20).

| Route | Possible? | Icons that fit, size | Custom bitmap? |
|---|---|---|---|
| (a) **Popup 65's tree** | There is no image view except the backdrop. An `IconController` can only be added by changing the popup the System factory builds (`SystemScreenFactory.getPartialPopup(65, t)`, public), so it needs the `HMISystem=` takeover (§2.3). Mutating the live popup instead is fragile (popups are cached and rebuilt). | Height ≤ 20 px (24-px band). About 4 icons of 20 px plus about 14 characters of text in 255 px. | Yes: SDK id → `getImagePath` (any module), or a `ResourceLocatorModel` path |
| (a') **An SDK-owned partial popup, registered by the Car bundle** (recommended) | `AbstractPartialPopupManager` collects `getPartialPopupStubs` from **every** HMIBundle service (`AbstractPartialPopupManager$2.addingService`). `SdkCarScreenFactory` can add one stub, e.g. `new PartialPopupStub(609600, <SDK ChoiceModel>, -1, 250, 5, 12, true, 7, t, 2, null, true, true)` (popup 65's parameters), and build popup 609600 in `getPartialPopup`: backdrop, N `IconController`s and a label. It needs **no new substitution**, and it drops the popup-65 conflicts with the engineering Mem/CPU line and DAB debug. **[spike]**: whether it shows, survives screen changes, and how it shares zpm slot 12 / slot 5 with stock popups (else pick another slot). | The SDK picks the size. Keeping the top-right 255×24 band: 4–5 icons of 20 px. A taller band (30 px, the status-bar icon height) may overlap title bars. | Yes, the same two ways |
| (b) **Stock status bar** (popup 62, `StatusbarControllerHigh`) | Cells are a closed `switch` of 39 types in `AbstractStatusBarController.add(widget, type)`, each an `IconController` on a fixed stock model with fixed bitmaps (e.g. Bluetooth `{127, 220, 221, 222}` on model 3940). Status icons are 30 px tall and 24–100 px wide (`images/HMISystemEvoHigh/204..234.png`). Cell 18, the provider logo, is an `IconController` on **ResourceLocatorModel 3838**, written by the online RemoteHMI (`ContextManagerComponent.showProviderLogo(path)`: status 1 plus a URI). The SDK could write a PNG path there, but that fights the online writer and shows only where the screen's stub allows it. **[spike, optional]** Adding a cell means shadowing the controller (carplay-rgi). **No-go** as a product route. | 1 (the logo cell) | Yes (a path), but borrowed |
| (c) **Inline glyphs** in label 416 | The label font is `AudiTypeDisplayHigh-Normal`. It has arrows `← ↑ → ↓ ↖ ↗ ↘ ↙`, `• ▪ ▯ ► ◀ ◆ ◊ ∞ √ ≈ ≠ ≤ ≥ ⌀ ‰ ™`, and U+F8FF (glyph "apple"). Its private-use glyphs are alternate digits (`zero.lntx`…), not icons. No ✓ ● ★ ▲ ■ ⚠. The CJK fonts (ZYHei GB18030, LTUnivers JP/KR) have `● ○ ★ ☆ ▲ △ ▼ ▽ ■ □ ◆ ◇ ◎ ※ ♪ ☎`. `fonts/variant_*.lur` look like fallback tables; whether a NAR label falls back to them is **[spike]**. | Each glyph costs one of the 24 characters | No |
| (d) **Custom-drawn widget** | A renderer of our own would have to drive EAL/Kanzi nodes (`AbstractRendererHigh`). That is a lot of work for nothing an `IconController` cannot already draw, and the glasshud-style native overlay needs a shadowed `DisplayManager`. **No-go.** | — | — |

## 5. Risks

1. **`HMISystem=` blast radius:** a failure there blanks the whole HMI, including during a firmware update (SWDL
   mode uses the same bundles file). It needs a stock fallback on `Throwable`, stock behaviour in `RUN_MODE=swdl`,
   no throwing static initializers, and SSH recovery in the runbook.
2. **Build coupling and firmware updates:** the route depends on screen id 46, the wizard row recipe, model 138
   semantics, the widgetID→atlas mapping and atlas cells 10–16, all of this build's internals. SWDL rewrites the
   bundles file and the jxe. The SDK jars may survive, but a new jxe with the old line would load a subclass against
   a changed `SystemScreenFactory` (`NoSuchMethodError` at class load means no System module). mibpkg's HMI-version
   guard and its second managed line (`HMISystem=`, owned class, byte-identical restore) are prerequisites.
3. **Native image decoding and caches:** every custom icon is decoded by the native EAL layer, outside Java's
   protection, and cached by id for the session. A malformed or huge PNG could crash or bloat the renderer process,
   and reusing an id shows stale pixels. The runtime must validate PNGs (type, size, dimensions) and allocate fresh
   ids. Smaller: focus on the SDK row writes 10–14 into SMM model 138 (guard 509 default branch); the mapping
   mutation (§3.2 a') changes a static stock array; an SDK popup may contend for zpm slot 12.

## 6. Verdicts

- **Main menu entry: NEEDS-SPIKE.** The menu is a Java screen (System 46). An `HMISystem=` takeover can append a row
  with its own key handler, but this boot path and the SMM/BACK/MENU behaviour are unproven, and its blast radius is
  the whole HMI.
- **Row icons: GO-likely for stock icons, NEEDS-SPIKE for custom ones.** Drawer rows take any image id, and the SDK
  factory can map its own ids to PNG files. The main menu can only use the stock atlas (4 spare cells plus the unused
  wrench).
- **Status-line icons: NEEDS-SPIKE.** The best route is an SDK-owned partial popup registered through the Car
  factory's stubs. Glyphs are a zero-risk stopgap with a small set, and the stock status bar is no-go.

## 7. Open points (Spike C)

| # | Question | Go means |
|---|---|---|
| C0 | Does the `HMISystem=` spike activator boot like stock? | First screen, home, MENU ring, volume popup and status bar look like stock; log line from the System activator, with `configuratorSeen=true` |
| C1 | Does an appended wizard row render? Which rows are visible and in what order? | The SDK rows appear in the ring with their labels and atlas icons (widgetID 10, 11→16, 12, 14); the child dump matches the screen |
| C2 | Does SELECT reach the SDK key handler without an SMM event? | Log `wizard-press` on HMIEvent; the stock state does not change |
| C3 | Does the page open from the menu, and does BACK return to the menu? | The page renders; BACK shows the ring again, focus on the SDK row |
| C4 | What do MENU and the 138 default do? | MENU on the SDK page and MENU on the ring with SDK focus: recorded, no stuck screen |
| C5 | Can the row be hidden/shown and relabelled live? | `setVisible(false)` removes it from the ring within 1 s or at the next open; the label changes |
| I1 | Does an SDK image id served by `getImagePath` show a custom PNG in the drawer? | Custom icon and reflection drawn, tinted, focusable |
| I2 | Does a stock id from another module (System 422/423) show in the Car drawer? | The phone icon is drawn |
| I3 | Does a new id per change refresh the icon? | The new icon is shown at the next drawer open (or live) |
| S1 | Does an SDK partial popup registered by the Car bundle show, with icons? | 609600 visible top right on home, Car and media, icons plus text; hides on value 0 |
| S2 | Does a file-path icon (`ResourceLocatorModel`) render? | The icon is drawn from `/mnt/app/eso/hmi/lsd/mibsdk-spike/icons/p20.png` |
| S3 | Can popup 65 hold extra `IconController`s (System factory)? | Icons drawn left of the label |
| S4 | Which glyphs render in label 416? | Ruler frame read out |
| S5 | (optional) Does provider-logo model 3838 show an SDK PNG in the status bar? | Recorded; reset afterwards |

## 8. Bench spike plan (Spike C)

This plan is for the owner to run. Nothing here has run. One HMI restart to install, one to uninstall. Every check
after the boot is driven by command files, without a restart.

### 8.1 Code (in `spikes/hmi-ui`, same module and jar as spikes A/B, a new README section "Spike C")

- **`SpikeSystemActivator`** (on `HMISystem=`):
  - a copy of `de.audi.tghu.system.hmi.Activator`: `super(0, "System", skin, new SystemModelBank())`;
    `getScreenFactory()` returns a `SpikeSystemFactory`, or on any `Throwable` the stock `SystemScreenFactory`;
  - when `"swdl".equals(System.getProperty("RUN_MODE"))` it uses the stock factory;
  - its constructor only logs `system activator constructed thread=… configuratorSeen=…`.
- **`SpikeSystemFactory extends SystemScreenFactory`**:
  - `createScreen(46, t)`: `super`, log every wizard child (`labelId, widgetID, event, visible, internalID`), then
    append **4 rows**. Their labels are text ids 90000..90003 ("SDK w10", "SDK w11 wrench", "SDK w12", "SDK w14"),
    their widgetIDs 10, 11, 12, 14. Copy `setColorIndices`/insets from the Settings row. Each gets
    `setKeyHandler(new SpikeWizardKeyHandler(n))`. Log `wizard rows appended`. Never throws: catch → stock screen.
  - `getText(int)`, `getText(int,int)`, `getAltText(int)` for 90000..90003 (live strings, `SpikeIds`).
  - `getPartialPopup(65, t)`: `super`, then (only when `/tmp/mibsdk-spike-ui.p65icons` exists at build time) add two
    `IconController`s at (10,2,20,20) and (34,2,20,20), with bitmaps `{609120}` (custom) and `{230}` (stock), and
    move the label to x=58.
- **`SpikeWizardKeyHandler implements IWidgetKeyHandler`**: on key 17, `consume(false)`, log
  `wizard-press n=… thread=…`, then post `RunnableEvent(false, …)` that calls `SpikePage.postShowDirect()` with
  `from` = the current screen data (screen 46). Other keys do nothing.
- **`SpikeFactory`** (existing Car factory) additions:
  - `getImagePath(int id, int t)`: for 609100..609199 return `"../mibsdk-spike/icons/" + id + ".png"`, else `super`.
    Log the first call per id.
  - Drawer slot bitmaps: slot 0 `{609100, 609101}` (custom), slot 1 `{422, 423}` (System stock), slot 2 none (as S1f).
  - `getPartialPopupStubs(t)`: `super` plus `new PartialPopupStub(609600, 602751, -1, 250, 5, 12, true, 7, t, 2,
    null, true, true)`.
  - `getPartialPopup(609600, t)`: a copy of `partialPopupDebugInfo` with id 609600 at (514,0,255,24), three
    `IconController`s:
    - bitmaps `{609110}`;
    - bitmaps `{609111}`;
    - model 602752 (`ResourceLocatorModel`, file path);
    and a label on `LabelModel` 602753.
- **`SpikeBank`**: grow by 3 models. 602751 is a ChoiceModel (popup visible), 602752 a `ResourceLocatorModel`,
  602753 a `LabelModel`.
- **`SpikeProbes`**: new command files (`/tmp/mibsdk-spike-ui.<cmd>`, polled every 100 ms):
  - `pop-on`/`pop-off`: 602751 := 1/0;
  - `pop-file`: 602752 := `HMIResourceLocator("/mnt/app/eso/hmi/lsd/mibsdk-spike/icons/p20.png")`;
  - `wrench`: on HMIEvent, `mapping[10] = {16, 9}` via the connected `MainWizardController`'s
    `getMainWizardIconDecoratorMapping()`. Log the old value; re-open MENU to see it;
  - `wz-hide`/`wz-show`: `setVisible(false/true)` on the "SDK w10" row;
  - `wz-label`: change the 90000 string;
  - `icon-next`: set slot 0's bitmaps to `{609102, 609103}`;
  - `glyphs1`/`glyphs2`: write `"←↑→↓•▪►◀◆◊∞√⌀"` and `"●○★▲■□◎※♪☎✓⚠"` into 416 with choice 1 := 1;
  - `glyph-off`;
  - `logo-on`/`logo-off`: system `ResourceLocatorModel` 3838: status 1 and the p30.png path, or status 0 and `""`.
- **Icons** (`spikes/hmi-ui/icons/`, made by a small pure-Python `make_icons.py`, zlib+struct, 8-bit RGBA):
  - 609100.png 72×72 white "plug" glyph and 609101.png 72×68 faded copy;
  - 609102/609103 a second, different glyph;
  - 609110/609111/609120 20×20;
  - p20.png 20×20 and p30.png 30×30.
- **`run.sh`** (Spike C needs changes, because mibpkg removed `mibsdk/stock/`):
  - expected live lines: `HMICar=mibsdk.adapter.hqc.ui.SdkCarActivator` and
    `HMISystem=de.audi.tghu.system.hmi.Activator`;
  - `install` first saves a byte copy of the live bundles file to `/mnt/persist/mibsdk-spike/bundles.before`
    (persist survives the unit reset);
  - then it pushes the jar and the icons dir to `/mnt/app/eso/hmi/lsd/mibsdk-spike/icons/`, and rewrites both lines
    to `mibsdk.spike.ui.SpikeCarActivator` / `mibsdk.spike.ui.SpikeSystemActivator`, keeping CRLF and mode 777;
  - `uninstall` writes `bundles.before` back (`cmp` check), removes the jar and the icons dir, and leaves `/mnt/app`
    read-only;
  - keep the F8 rule: every remote command re-asserts `mount -uw` inside the write section;
  - option `--car-only` keeps the stock `HMISystem=` line (§8.3).
- Do not run mibpkg actions (install, push, grant) while the spike is installed. The live `HMICar=` is foreign to
  mibpkg then, and its spike-jar guard refuses anyway.

### 8.2 Steps (each one observable check)

Before: `installer/mibsdk-dev status` (the SDK installed, `HMICar=…SdkCarActivator`); `curl …:8099/status` →
`bridge_connected:true`.

0. Baseline frames without the spike: `menu` → frame of the ring (record the order and the visible entries).
   Rotate through the whole ring, one frame per stop (answers the C1 order question for stock).
1. `./gradlew :spikes:hmi-ui:carJar && python3 -I spikes/hmi-ui/icons/make_icons.py && spikes/hmi-ui/run.sh install`.
   Check: `run.sh` prints both line rewrites, and `bundles.before` exists.
2. `installer/mibsdk-dev restart` (unit reset; `ssh rpi-bench keypanel up` if there is no lease). **C0:** the home
   frame looks stock, and `run.sh log | grep spike.ui` shows `system activator constructed … configuratorSeen=true`
   and `factory: SpikeSystemFactory`. If the screen stays black for more than 5 min after the unit answers SSH: go to
   §8.4 emergency revert.
3. **C0 (cont.):** press `vol_up` once → volume popup frame; the status bar shows its clock.
4. `menu` → frame. **C1:** the ring shows the stock rows plus "SDK w10" (warning icon), "SDK w11 wrench" (night
   vision icon until step 9), "SDK w12" (headset), "SDK w14" (flower). The log has the child dump and
   `wizard rows appended`.
5. Rotate to "SDK w10" → frame. **C1/C4:** the label is focused and highlighted (bottom-row atlas cell), and the log
   shows no `no mapping found`.
6. `knob_select` on "SDK w10" → frame after 2 s. **C2/C3:** log `wizard-press n=0 thread=…HMIEvent`; the SDK spike
   page renders.
7. `back` → frame. **C3:** the ring is shown again, focus on "SDK w10" (record the animation).
8. `knob_select` again (page), then `menu` → frame. **C4:** record where MENU lands. Then `menu` again from wherever
   it landed → the ring. Rotate onto an SDK row, `menu` → frame (guard 509 default); record.
9. `touch /tmp/mibsdk-spike-ui.wrench`, then `menu` twice (close/open) → frame. **§3.2 a':** "SDK w11 wrench" shows
   the wrench icon.
10. `wz-label` with the ring open → frame. **C5:** "SDK w10" relabels. Then `wz-hide` → frame, the row is gone;
    `wz-show` → back.
11. Select a stock row (Radio), then MENU → Navigation: frames. Stock rows still work.
12. `car`, `menu_left` (drawer) → frame. **I1/I2:** slot 0 shows the custom plug icon and its reflection, tinted;
    slot 1 the System phone icon (422); slot 2 the empty square (as S1f). The log shows the `getImagePath 609100`
    line.
13. `icon-next`, close and reopen the drawer → frame. **I3:** slot 0 shows the second glyph. Also take one frame with
    the drawer open right after `icon-next` (live or not).
14. `pop-on` → frames on home, Car and media. **S1:** popup 609600 at the top right with two custom icons and the
    label. Then `pop-file` → frame, **S2:** the third icon appears. With popup 65 also on (the SDK's status line or
    `glyphs1`): record whether both show, or which one wins slot 12. `pop-off` → gone.
15. `glyphs1` → frame on home; `glyphs2` → frame. **S4:** read out which glyphs are drawn and which are boxes or
    blank. `glyph-off`.
16. (only if 609600 failed) `touch /tmp/mibsdk-spike-ui.p65icons`, then make popup 65 rebuild. Popups are cached;
    the reliable way is one more restart, so **skip unless S1 failed**. **S3.**
17. (optional) `logo-on` → frames on home, media and an Audi connect screen. **S5.** `logo-off`.
18. `run.sh log > docs/research/ui-spike-c-log.txt`; save the frames as `docs/research/ui-spike-c-*.jpg`.
19. Revert: `spikes/hmi-ui/run.sh uninstall` (it `cmp`s the bundles file against `bundles.before`), then
    `installer/mibsdk-dev restart`. Check: `mibsdk-dev status` shows `HMICar=…SdkCarActivator`, `ui car=sdk`; the
    Car drawer ends with the Plugins row; MENU shows the stock ring.

### 8.3 Splitting it (if the owner prefers less risk per boot)

Boot A with `run.sh install --car-only` (only `HMICar=` changes): steps 12–15 and 17. Boot B adds `HMISystem=`:
steps 3–11. That is one more restart, and the second boot carries all of the risk §2.3 describes.

### 8.4 Emergency revert (a blank HMI after step 2)

SSH still works (QNX; `keypanel up` for the lease). Run
`mount -uw /mnt/app; cp /mnt/persist/mibsdk-spike/bundles.before /mnt/app/eso/hmi/lsd/bundles_dsitracer_enabled.properties; chmod 777 …; sync; mount -ur /mnt/app; slay -s11 j9`.
Then `run.sh uninstall` cleans the jar and the icons.

### 8.5 Expected frames (names)

`ui-spike-c-ring-stock-*.jpg` (step 0), `-ring-sdk-rows.jpg`, `-ring-sdk-focus.jpg`, `-page-from-menu.jpg`,
`-back-to-ring.jpg`, `-menu-from-page.jpg`, `-ring-wrench.jpg`, `-ring-relabel.jpg`, `-ring-hidden.jpg`,
`-drawer-custom-icon.jpg`, `-drawer-icon-next.jpg`, `-popup609600-{home,car,media}.jpg`, `-popup-file-icon.jpg`,
`-glyphs1.jpg`, `-glyphs2.jpg`, `-logo.jpg` (optional), `-after-uninstall-{ring,drawer}.jpg`.
