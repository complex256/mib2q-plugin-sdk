# Native inputs: the checkbox fix and the HMI's input widgets

Date: 2026-10-25. Scope: why spike B's checkbox cell drew nothing (S2c), the stock recipe for a working checkbox row,
and a survey of every native input/list widget of this HMI that SDK pages could offer plugins (api 5). Build
`HMI_AU_MIB2_HQC_NAR_G22_C10_180803`. Host-only, read-only survey of the decompiled HMI, this repo and carplay-rgi.
Nothing new has run on a unit. Everything marked **[spike]** is settled by the bench check in §6.

Sources (all outside this repo unless a repo path is given):
- Decompiled stock HMI, `patching/lsd-src/lsd/` (cited for every number) and `patching/lsd_java/` (CFR). Paths below
  are package paths under `lsd-src/lsd/`. **The screen bags are byte-swapped in `lsd-src` too** (`CarScreenBag5`,
  `SettingsScreenBag1`, `ToneScreenBag1`, …); every large id below is given decoded, with the raw literal where it
  helps a grep. Small ids (`138`, `427`, `4018`, …) and widget code (`ListController`, `MenuItemBuilder`, …) are real.
- This repo: `docs/research/2026-10-20-hmi-ui-injection.md` (the "UI note"; §8 spike B, S2c),
  `docs/research/2026-10-24-main-menu-and-icons.md` (popup 609600), `spikes/hmi-ui` (`SpikePage`),
  `adapter-hqc/.../ui/{PageWidgets,PageScreens,HqcUiIds,SdkCarModelBank}`, `api/.../{PageSpec,Ui,UiAction}`.
- carplay-rgi `origin/main` `java_patch/com/luka/carplay/tone/UpmixToneUi.java` (a type-64 combo row added to Tone
  screen 1000006 on a bank-grown ChoiceModel).

## 1. Root cause: the checkbox cell was never built

**The list picks a row's item-factory kind from its record-set column, not its id column.** Spike B and the SDK set
only `setIdColumn(2)` (`SpikePage.java:270`, `PageWidgets.java:280`), so every row reached `createListItem` with kind 0
(ACTION) and got the label-only layout. No `CheckboxController` ever existed; the listener toggled column 1 of a model
nobody drew. The stock checkbox cell recipe is byte for byte what the spike wrote.

### 1.1 `ListController`: kind = record set

`…/evo/widgets/menu/list/ListController.java`:
- `:337-342` `getRecordSetForRow(row)`: `recordSetColumn == -1 ? 0 : getIntegerListCellValue(row, recordSetColumn, …)`.
  `recordSetColumn` defaults to `-1` (`:59`).
- `:435-441` `createListItemInternal` stores that value as `recordSet`; `:504-526` `createListItemWidget` calls
  `itemFactory.createListItem(recordSet, cells)` (and caches widgets per record set, `itemsCache.get(recordSet)`).
- `idColumn` (`:58`, `:690-692`) only reaches `OldListModelAccess.setIdColumn` (`:1004`), the row identity.
- Spike: `ROW_KIND = {0,0,0,1,1}` in column 2, `Items.createListItem(kind)` builds the checkbox only for kind 1
  (`SpikePage.java:389`). With `recordSetColumn = -1` kind is always 0. The frames agree: no box at all, checked or
  not (`ui-spike-b-s2a-focus-checkbox1.jpg` vs `-checkbox1-toggled-on.jpg`). An unchecked box would still draw.
- Stock lists that mix row kinds set it: `TunerScreenBag3.java:1468,1479` `setRecordSetColumn(0)` (120 calls in
  `de/audi/tghu`).

The comments that say the id column picks the row (`PageScreens.java:46`, `HqcUiIds.PAGE_LIST_MODEL` javadoc) are
wrong and should be fixed with the change.

### 1.2 The cell once built: nothing else is missing

`CheckboxController` has no bitmaps, colours or init calls to forget:
- Renderer `CheckboxRendererHigh` (`…/evo/high/widgets/CheckboxRendererHigh.java:17-49`) is an
  `AbstractKanziTemplateRenderer`: Kanzi prefab `"Prefabs/checkbox"` in kzb constant `6`, which
  `KzbMappingHelper.get(6)` maps to `glassplateReflex = KzbFileNameMapping.GLASSPLATE` (`KzbMappingHelper.java:40,288`):
  the same kzb as the page's glassplate and scrollbar, which render. It writes three Kanzi properties per frame:
  `cb_state` (`1.0` checked, `2.0` unchecked; animated, anim id 75, 31300 steps), `cb_partialSelection` (0 or 1;
  1 when the value has bit `0x80`) and `itemDisabled` (0/1). Size: layout integer constants 52 × 53.
  **No checked/unchecked bitmap ids exist**; the prefab draws both states.
- Colour indices: none on the checkbox. The row's colours come from the menu (`{1,0,4,0,2,3}`) and the focus cursor.
- Model (`CheckboxController.java:146-199`): `ChoiceModelGUI`, `IntegerListCell` or `Integer`. Checked unless the
  value is in `mapping[0]` (default: value `!= 0`). No model → drawn unchecked and disabled. `HMIModelGUI` status 3 →
  unchecked, disabled.
- Live update (`:89-108`): `processModelUpdateEvent` types 1/2/14 → `updateState` (animated, or immediate while the
  menu scrolls). `ListController.setModelToItemWidget` (`ListController.java:602-613`) sets the new cell as the
  model and sends update type 1 whenever the cell changes, so a `ListModel.setRow`/`setCell` animates the box.
- Key handling (`:263-276`): a `CheckboxChoiceModelKeyHandler` that only acts on a `ChoiceModelGUI`; on an
  `IntegerListCell` it logs "model is not a choice model" and does nothing. The press reaches the list's
  `ListListener.itemSelected` as today, so there is no double toggle.

Stock list recipe (identical to `SpikePage.Items`): `SettingsScreenBag1$2.java:18-74`, the "Factory settings"
checklist, screen **1100007** `sETTINGSFACTORYMAIN` (`SettingsScreenBag1.java:967`), list model 1100091
(`:984`, raw `1003032576`), `setEnabledColumn(3)` (`:986`). Row: `MenuItemController` type 16,
`MenuItemRendererHigh`, `GridLayout` with `MenuItemColumnsConstraints(2)` `gaps {0,22,0}`, `grow {1,0}`,
`shrink {1,0}`; cell 0 `LabelController.setModelColumn(0)`; cell 1 `CheckboxController` + `CheckboxRendererHigh`,
`setBounds(0,0,100,100)`, `setMapping({{0},{1}})`, `setModelColumn(1)`. Also `TunerScreenBag3$1` (with a record-set
column) and `NaviScreenBag8$2..$11`.

### 1.3 The stock type-8 row (Car settings), for comparison

Car settings main, screen **600002** `cARCARSETTINGSMAIN` (`CarScreenBag5.java:4202`), SMM state
`carCarsettingsMain`. Its one checkbox row (`:4419-4434`): `MenuItemController` + `CompositeRendererHigh`,
`setModelID(600758)` (raw `-1238759168`), `setEvent(600014)`, `setLabelId(600915)`, `setType(8)`, `setWidgetID(30)`,
child `VisibilityProxyWidget(601047)`. The screen binds it: `setViews` includes `600758 → {row}` (`:4656`), and
`setModelIDs {600758, 600963, 601202}` / `setEventIDs {600014, 600041, 600073}` (`:4657-4658`) map a model change
to an SMM event.

What the type does at connect (`MenuItemController.autoSetup`, `:318-320`, only when `type != 16`):
`MenuItemBuilder.configureMenuItem` (`MenuItemBuilder.java:57-120`) → `setupCheckboxMenuItem` (`:155-168`):
left `LabelController` (label id), optional `IconController` (bitmap index 0), then
`CheckboxController` with `rendererFactory.createCheckboxRenderer` (= `CheckboxRendererHigh`), the row's model id and
model, `setChainedAction(3, 17)`, `setupScreenViews` (adds the checkbox to the screen's views for that model and
calls `modifiyModelReference`), `setMapping(row.getMapping())`, and the 3-column `createMenuItemLayout`
(`:547-590`). Then `setRealType(8)`, `setType(16)`.

Press: the checkbox's `CheckboxChoiceModelKeyHandler` computes the other mapping value and calls
`ChoiceModelGUI.itemSelected(value, terminal)` → the app's `ChoiceListener.itemSelected(modelId, value, 0, terminal)`
(`ChoiceModel.java:82-89`). The GUI never sets the value itself; the app does, and the box follows the model. The row
itself also gets `ChoiceModelKeyHandler` (ChoiceModel and `widgetID != -1`, `MenuItemBuilder.configureKeyHandler`
`:408-424`), which would send `itemSelected(widgetID)`; which of the two reaches the listener first on a type-8 row
is **[spike]**, so an SDK listener should toggle rather than trust the value.

### 1.4 What our build is missing, and the minimal change

Missing: one call (`setRecordSetColumn`) and the checkbox cell in the SDK's item factory. The SDK's `Rows` builds one
label cell for every kind (`PageWidgets.java:388-412`) and writes toggles as `[on] `/`[off] ` text.

**Fix the cell; do not build type-8 rows.** A type-8 row needs one bank `ChoiceModel` per row, a screen view entry
per row and a row outside the list. The cell needs none of that, keeps the page one `ListController`, and is the
stock Factory-settings recipe.

`adapter-hqc/.../ui/PageWidgets.java`:
1. `build`: after `list.setIdColumn(2)` add `list.setRecordSetColumn(2)` (column 2 already holds the kind).
2. `Rows.createListItem(kind, cells)`: for `kind == PageScreens.ROW_TOGGLE` use the 2-column grid above and add
   `createCell(1)`; other kinds unchanged.
3. `Rows.createCell(1)`: `CheckboxController` + `CheckboxRendererHigh`, `setBounds(0,0,100,100)`,
   `setMapping(new int[][]{{0},{1}})`, `setModelColumn(1)`.
4. `writeRows`: column 1 = `IntegerListCell.create(on ? 1 : 0)` for toggles (it is `0` for every row today), and
   `UiPageText.line` drops the `[on] `/`[off] ` prefix for TOGGLE.

Input is unchanged: `ListListener` → `TOGGLE_REQUESTED` → the plugin decides → `refresh` writes the new column-1 value
→ the box animates. The box therefore shows the state the plugin accepted, as the stock app-sets-value pattern does.
Optional: a 4th column with `setEnabledColumn(3)` greys a row (`itemDisabled`). **[spike]**: the box draws and
animates (D1), a row whose kind changes on refresh is rebuilt with the new layout (`isModelRowValidForItem`).

## 2. Catalogue of native input and list widgets

Two ways a widget can sit on a page:
- **List cell**: a widget that `implements ListItemWidget` inside a type-16 row, fed from a `ListModel` column
  (`ListController.collectListItemModelColumns`, `:619-633`). Cells: `LabelController`, `IconController`,
  `CheckboxController`, `RadioButtonController`, `ProgressBarController`. One `ListModel`, any number of rows.
- **Typed row**: `MenuItemController.setType(n)` in the page's `MenuController` (beside the `ListController`, as
  in `sETTINGSFACTORYMAIN`, which has a list plus an ACTION row), expanded by `MenuItemBuilder` at connect and bound to
  its **own bank model** through `screen.setViews` (ids kept sorted: `findViews` binary-searches, carplay-rgi).
  Needed for `RotaryController`, `ComboBoxController`, `FormfieldInputController`, which are not list cells.

Row types (`MenuItemBuilder.configureMenuItem`, `:57-120`): LABEL 1/32, ACTION 2/3, SUBMENU 4/5, CHECKBOX 8/9,
LIST_ITEM 16, COMBO_BOX 64, ROTARY 128/129, RADIO_BUTTON 256/257, SUBMENU_PREVIEW 512/513, FORMFIELD_INPUT 768,
TIME 1024, DATE 1025 (and 16777476/34619392), AUX_HEATER 1026, SDS 1027/1029, ROUTE_BRIEFING 1028/1030, GEO
19939328/36716544.

| Widget | Stock build | Model | Input to listener | Stock screen | SDK |
|--------|-------------|-------|-------------------|--------------|-----|
| **Checkbox / toggle** | list cell §1.2; or type-8 row §1.3 | `IntegerListCell` 0/1 (cell); `ChoiceModel` 0/1 (row) | cell: `ListListener.itemSelected(model,row,…)`; row: `ChoiceListener.itemSelected(id,value,0,t)` | 1100007 Factory settings (cell); 600002 Car settings (row) | **likely** (fix §1.4, recipe stock; draw is [spike]) |
| **Multi-choice** | the same checkbox cells, one per option | `ListModel` col 1 per row | `ListListener` per row | 1100007 (tick what to reset) | **likely** (= toggles) |
| **Radio / single choice** | list cell `RadioButtonController` + `RadioButtonRendererHigh` (prefab `Prefabs/radioButton`, kzb 6), `setModelColumn(1)`, `setBounds(0,0,100,100)`; 3-column grid `{icon 30, label grow, radio 33}`, `gaps {0,15,22,0}` (`SettingsScreenBag1$1.java:18-90`). Selected when the cell value == `widgetID` (default 1 for a list cell, `RadioButtonController.java:195-207`). Row form: type 256 (`setupRadioButtonMenuItem`, `:170-188`) on a ChoiceModel, selected when value == row `widgetID` | `IntegerListCell` (1 = the selected row) or `ChoiceModel` | `ListListener`; the SDK writes 1 to the picked row, 0 to the rest | 1100004 Language (`sETTINGSLANGUAGEMAIN`, list 1100222, flag icons in col 2); Car joker key 600021 (`cARCARSETTINGSJOKERSELECT`, type-256 rows, `CarScreenBag5.java:3471-3560`) | **likely** (same cell mechanism as the checkbox) |
| **Combo box** | type-64 row (`setupComboBoxMenuItem`, `:483-545`): label + `ComboBoxController` (`createComboRenderer`, prefab `Prefabs/dropdownBox`, kzb 7), options = the row's `setTextIds`, `setMapping` | `ChoiceModel` (value = option index) | press opens the dropdown, knob picks, `ChoiceListener.itemSelected(id, value, 0, t)`; the app sets the value | Tone 1000006 sound effects (stock 1000014, 1000152); Settings units 4014-4020 | **proven** by carplay-rgi on Tone 1000006 (bank-grown ChoiceModel 1000162, no SMM event; in the car 2026-10-13/14); on an SDK page **[spike]** |
| **Slider / level (in row)** | type-128 row (`setupRotaryMenuItem`, `:359-375`): label + `RotaryController` (`createRotaryRenderer`, preview look, kzb 14 `ROTARY`), `setActive(false)` | `RangeModel` (`setLimits(min,max,step)`, `setMedialPosition`; centre-zero bars like bass/treble) | the row is a display; press fires the row's event (Tone: event 1000009) | Tone 1000005 `tONESOUNDMAIN` (1000015, 1000018, 1000038, 1000039); Settings 1100212; Car 600963 | **likely** for display (static row + bank RangeModel) |
| **Slider screen (edit)** | own screen: `RotaryController` + `RotaryBigRendererHigh` on the same RangeModel, `FocusListenerController`, title, glassplate; `setViews {138, model, focus}` | `RangeModel` | knob → `RangeModelKeyHandler.keyTurned` (needs widget state 36) → `RangeModel.increment/decrement(n, t)` → `RangeListener.increment/decrement(id, n, t)`; the app clamps and sets | Tone 1000019 `tONETASLIDER` (model 1000024), 1000015 `tONETOUCHSLIDER`, 1000016 | **likely** as a second SDK prebuilt screen (showScreen path); focus/state 36 **[spike]** |
| **Stepper (+/−)** | no native widget on this rotary HMI; values are stepped by the knob on a RangeModel (slider screen) or picked from a combo | — | — | — | map to slider (step) or choice; no new widget |
| **Submenu row with a value** | type 512/513 (`setupSubmenuPreviewMenuItem`, `:270-357`): label + value `LabelController` on the row model + preview; or a list row with two label cells | `LabelModel` (row form) / `TextListCell` (cell form) | row: its event / model keyPressed; cell: `ListListener` | Phone setup 300382 (`tELOPTSETUPMAIN`), Navi 400739, TV 2600013/2600100 | cell form **likely** (two `LabelController` cells, second right-aligned); row form **[spike]** |
| **Progress / level bar** | list cell `ProgressBarController` + `ProgressBarRendererHigh` (prefab `Prefabs/progressBar`, kzb 6), `setBitmaps {40..45}`, `setColorIndices {1,2,4,0}`, `setMinimumModelValue(0)`, `setMaximumModelValue(100)`, `setPreferredHeight(15)`, `setModelColumn(n)` (`MediaScreenBag1$3.java:965-973`); in a popup: on `RangeModel` | `IntegerListCell` 0..100, `RangeModel`, `ChoiceModel` | none (display) | Media lists; volume popup 52 (`pOPUPVOLUME`, RangeModel 427, visibility 428, label 429) | **likely** |
| **Text input: speller** | `TouchController` (input field, `TouchRendererHigh`) + `SpellerController` (`SpellerControllerFactory.create`, `SpellerRendererEuropeHigh`, 49 bitmaps `{421,84,…,962}`, fonts 3) + `ModelStubController` 5602/5606; `setSpeller`, `setTouchpadMode(33/48)`, `setOpenSpellerOnOtherScreen(true)` | `SpellerModel` (`setMaxLength`, `setText`; `SpellerListener.textChanged/commandPressed/keyTyped`) | the listener gets each character and the OK command | Bluetooth name 2500003 (`cMOPTBLUETOOTHNAMEEDIT`, model 2500126); Wi-Fi password `cMOPTWLANSETUPEDITPASSWORD`; Car joker key search (model 601202); Navi address (match speller) | **hard** (two screens and the SMM in stock; inline mode and an SDK SpellerModel **[spike]**) |
| **Form field (text value)** | type-768 row (`setupFormfieldInputWidget`, `:806-849`), `FormfieldInputController` (prefab `Prefabs/generic_inputField`, kzb 7) | `TextfieldModel` | opens a speller (as above) | Connectivity / Navi edit rows | **hard** (needs the speller) |
| **Confirm popup (full)** | a screen like a page: `InstructionTextContoller` (`setOptions(menu)`, `addHintText(MultiLineLabel)`) + `MenuController` with ACTION rows (event 1741), title bar, `getRefWidget(t,1)`, `setSmallStageType(1)` | `LabelModel` text; rows | rows → their events (stock) / `ListListener` (SDK list rows) | 600138 `cARPOPUPDRIVESELECTLIFTNA` (one OK row); 600047 `mAINPOPUPSHOWROOM` (`setClearMethod(1)`, refWidget 8 on layer 7) | **likely** (another SDK prebuilt screen; Yes/No as list rows) |
| **Partial popup / toast** | a `PartialPopupStub` from the factory's `getPartialPopupStubs` + `getPartialPopup(id)`: backdrop, label, icons; shown by its visibility ChoiceModel | `ChoiceModel` (0 hide, 1 show), `LabelModel` | none (not focusable) | volume 52, status bar 62, DebugInfos 65 (`SystemScreenFactory.java:5661-5662`) | **proven** (SDK popup 609600, Spike C S1); a toast is a timer on top; layout/slot **[spike]** |
| **List with icons** | list cell `IconController` + `IconRendererHigh`, `setBitmaps({…})` indexed by the cell value, `setModelColumn(n)`, `setPreferredHeight(1)` (`SettingsScreenBag1$1.java:65-72`) | `IntegerListCell` (bitmap index) or a resource path | none (display) | 1100004 Language (flags) | **likely** (SDK bitmap ids already served by `getImagePath`, Spike C I1) |
| **Info / details page** | LABEL rows (type 1, `LabelModel`), multi-line labels (`MultiLineLabelRendererHigh`), `InstructionTextContoller` | `LabelModel` / `TextListCell` | none | every popup above; Car info screens | **proven** (S2a LABEL row on LabelModel 602750; list label rows) |
| **Time / date picker** | type 1024 / 1025 (`setupTimeSettingsWidget`, `:602-657`; `setupDateSettingsWidget`, `:659-694`), `TimeSettingsWidgetController` / `DateSettingsWidgetController` | Settings models (1100134 time, 1100125 date) | own key handling | Settings time 1100009 (`sETTINGSTIMEMAIN`); EarlyApps charge timer 2100371 | **hard** (model contract unknown) |
| **Colour picker** | type-128 row whose `RotaryController` model is a `BaseListModel` of colours (`BaseListModelKeyHandler`, `RotaryController.java:32-34,73-92`) | `BaseListModel` (`EvoListRow` per colour) | `BaseListModel` selection | not traced to a stock screen (the controller supports it) | **hard** |
| Balance/fader, seat, car viewer, OPS, map | dedicated controllers (`BalanceFaderController`, `SeatVisualizationController`, …) | special models | — | Tone 1000012, Car | out of scope |

## 3. What SDK pages can reach today

The page path is `FwHMI.showScreen(0, 0, ScreenData(609500, …, prebuiltScreen, …))` from the Car bundle
(`PageWidgets.show`); BACK is the SDK's (UI note §8). No SMM state is involved, so nothing may depend on an SMM event
or on screen `setModelIDs/setEventIDs` (those feed the SMM; a prebuilt SDK screen has none).

| Reach | Widgets |
|-------|---------|
| **Same page, list cells** (no new model, no new screen) | checkbox, multi-choice, radio, progress bar, value column, icon cell, label/info rows |
| **Same page, typed rows** (one bank model per row from an SDK pool, a sorted `setViews` entry, a row in `PageMenu` after the list) | combo (64), in-row slider display (128), type-8 checkbox (not needed), submenu-with-value (512) |
| **Another SDK prebuilt screen** (same showScreen path, its own id, e.g. 609501..609503, BACK re-shows the page) | slider edit screen, confirm popup, speller screen |
| **Partial popup from the Car factory** (proven, 609600) | toast, level toast (progress bar in the popup) |
| **Needs an SMM state or a stock-screen hijack** | none of the recommended set. Stock speller screens rely on `setOpenSpellerOnOtherScreen(true)` and SMM transitions (`TouchController.setEvent`), so an SDK speller must work inline on one screen **[spike]**; time/date pickers depend on Settings models. A hijack (event 600023 → screen 600033, UI note §8) stays the fallback if a prebuilt screen cannot take knob focus. |

Notes:
- `PageWidgets.currentBlocked` treats `IScreenData.isPopup()` as blocked. The SDK confirm popup should be shown as a
  normal (non-popup) `ScreenData`, so the page's own safety checks keep working; it only looks like a popup.
- The SDK bank reserves 602738..602763. Typed rows need a pool past it (e.g. 602764.., checked against the
  Car bank as in `HqcUiIds.MODEL_COUNT`'s note): N `ChoiceModel`s and N `RangeModel`s, one per typed row on the
  shown page, rebound at each build (pages are built per show).
- Combo options and labels are text ids; the SDK serves them from its factory text range (`TEXT_BASE` 609000).

## 4. Recommended api 5 (additive)

All additions are `@since api 5`, inert without a new `Features.UI_INPUTS` (old runtimes: the method still returns,
the entry renders as text). `UiAction` gains `Integer value()` (index or level) and `String text()`, null otherwise;
the existing constructor stays.

| API | Renders as | Input | Feasibility |
|-----|-----------|-------|-------------|
| `PageSpec.toggle(id, label, on)` (unchanged) | native checkbox cell (§1.4); text label without `[on]/[off]` | `requestedOn` as today | likely; box draw **[spike]** |
| `PageSpec.choice(id, label, String[] options, int selected)` | a row "label … option" (value column) that opens an SDK sub-page: one radio cell per option, BACK or a pick returns | `value()` = index, after the pick | likely; radio draw **[spike]** |
| `PageSpec.dropdown(id, label, String[] options, int selected)` (optional) | type-64 combo row on a pooled ChoiceModel, options ≤ 8 | `value()` = index | proven in carplay-rgi; on an SDK page **[spike]** |
| `PageSpec.slider(id, label, int min, int max, int step, int value, boolean centred)` | type-128 row (bar preview); press opens the SDK slider screen (`RotaryBigRendererHigh`), knob edits, BACK returns | `value()` on each knob step (coalesced), the runtime clamps | display likely; edit screen focus **[spike]** |
| `PageSpec.progress(id, label, int percent)` | list row with a `ProgressBarController` cell, 0..100 | none | likely **[spike]** |
| `PageSpec.item(id, label, value)` (unchanged) | value in a right-aligned second label cell instead of one merged string | as today | likely **[spike]** |
| `PageSpec.item(id, label, value, Icon)` | icon cell in column 0 | as today | likely **[spike]** |
| `Ui.confirm(String title, String text, String yes, String no, Listener<UiAction>)` | SDK popup screen: multi-line text + two rows; BACK = no | `value()` 1 yes / 0 no | likely **[spike]** |
| `Ui.toast(String text, Icon icon)` | SDK partial popup (609600 mechanism) for ~3 s | none | likely (popup proven; slot/timer **[spike]**) |
| `Ui.speller(String title, String initial, int maxLen, Listener<UiAction>)` | SDK speller screen with an SDK `SpellerModel` | `text()` on OK | **hard**; defer to api 6 unless D9 passes |

Not recommended: a stepper (no native widget; `slider` with a step covers it), time/date and colour pickers (stock
model contracts), type-8 rows (the cell does the same with less).

Safety stays as for pages: no input widget opens or edits anything while the vehicle blocks screen changes
(`PageScreens.screenChangeAllowed`), and every value from the HMI goes through the runtime (clamp, then the plugin
decides) before the model shows it.

## 5. Proven and [spike]

Proven (bench or shipped):
- Prebuilt SDK screens via showScreen, list rows, `ListListener` on HMIEvent, LABEL rows on a LabelModel (spike B).
- SDK partial popup 609600 with icons and a label (Spike C S1, S2).
- A type-64 combo row on a bank-grown ChoiceModel with a listener-only flow (carplay-rgi, Tone 1000006; row, texts
  and live switching confirmed in the car 2026-10-13/14, `docs/audio/STEREO_TO_5_1_UPMIX.md` "MMI level").
- From the decompile (not run): the checkbox cell recipe and the record-set root cause (§1).

**[spike]**:
1. With `setRecordSetColumn(2)`, the checkbox cell draws, animates on a model write, and starts checked when the cell is 1.
2. A row whose kind changes on refresh is rebuilt with the right layout.
3. `setEnabledColumn` greys a checkbox row.
4. The radio cell draws and follows the cell values.
5. A progress-bar cell, a value label cell and an icon cell (SDK bitmap id) draw in the list.
6. Typed rows in `PageMenu` after the list: a combo row opens its dropdown and reports the index; a type-128 row draws
   its bar from a pooled RangeModel.
7. The type-8 row's key order (`ChoiceModelKeyHandler` vs `CheckboxChoiceModelKeyHandler`), only if type-8 rows are
   ever used.
8. A second prebuilt SDK screen (slider) takes knob focus: `RotaryController` reaches state 36, and
   `RangeListener.increment/decrement` fire.
9. The SDK confirm screen draws its multi-line text and two rows; BACK returns to the page.
10. Toast: popup 609600 with text shows and hides by timer (slot 5 is shared with popup 65, newest wins).
11. Stretch: an inline speller on an SDK screen with an SDK `SpellerModel` reports `textChanged`/`commandPressed`.

## 6. Bench check (one restart): spike D

Prerequisite: `spikes/hmi-ui/run.sh install` refuses unless `HMISystem=` is stock (`run.sh:115`). With SDK 0.9
installed it is not. Relax `install --car-only` to keep a non-stock `HMISystem=` byte for byte (the backup and the
`cmp` restore already cover it), and do not use the MENU ring's Plugins row during the spike. Boot A path only.

Code (all in `spikes/hmi-ui`, throwaway):
- **SpikeIds/SpikeBank**: `MODEL_COUNT` grows by 6: `602754` ChoiceModel (type-8 row), `602755` ChoiceModel (combo,
  value 0), `602756` RangeModel (`setLimits(-6, 6, 1)`, `setMedialPosition(0)`, value 0), `602757` LabelModel
  (confirm text), `602758` ListModel (confirm Yes/No rows), `602759` SpellerModel (stretch, `setMaxLength(20)`).
  Every listener logs `spike.ui` and sets the value itself (Choice: `setValue(value)` for the combo, toggle for 602754;
  Range: `setValue(clamp(v ± n))`). Texts 609022..609031: combo options "Off/Low/Mid/High", "Yes", "No", "Bass",
  confirm text.
- **SpikePage.createListModel**: 5 columns (text, int value, kind, enabled, value text); `setRecordSetColumn(2)` and
  `setEnabledColumn(3)` on the list. Rows: Action A, Action B, Checkbox 1 (0), Checkbox 2 (0), Checkbox 3 (1),
  Checkbox off (0, enabled 0), Radio A (1), Radio B (0), Radio C (0), Progress (60), Value row ("Language" / "English").
  Kinds: 0 action, 1 checkbox, 2 radio, 3 progress, 4 value.
- **SpikePage.Items**: kind 1 = §1.2 recipe; kind 2 = label + `RadioButtonController` (col 1, `setBounds(0,0,100,100)`),
  `gaps {0,22,0}`; kind 3 = label + `ProgressBarController` (Media recipe, col 1); kind 4 = two `LabelController`s
  (cols 0 and 4, second `grow 0`).
  `itemSelected`: kind 1 flips col 1; kind 2 writes 1 to the row and 0 to the other radio rows; kind 3 adds 20 (mod
  120); action rows log.
- **SpikePage.build**: after the list, three typed rows in the menu: type 8 on 602754 (label 609026, `widgetID -1`),
  type 64 on 602755 (`setTextIds(609022..609025)`, `setLabelId(609031)`, `widgetID 1`, bitmaps and fonts copied
  from a stock `CompositeRendererHigh` row as carplay-rgi does), type 128 on 602756 (label 609028). Add their ids to
  `setViews`, **sorted**.
- **SpikeSlider** (new, screen 609501): the `tONETASLIDER` recipe (`ToneScreenBag1`, screen 1000019) with
  `RotaryController` + `RotaryBigRendererHigh` on 602756, a title, `PageMenu`-style BACK (re-show the page). Opened
  by the type-128 row's key (wrap its key handler: on key 17 post the show) and by command `slider`.
- **SpikeConfirm** (new, screen 609502): the `cARPOPUPDRIVESELECTLIFTNA` recipe (`CarScreenBag1`, screen 600138):
  `InstructionTextContoller` with a multi-line label on 602757, `MenuController` with a `ListController` on 602758
  (rows "Yes", "No"); select logs and re-shows the page. Command `confirm`. Shown as a non-popup `ScreenData`.
- **Commands** (`SpikeProbes`): `cb-set` (Checkbox 1 := 1 from the SDK thread via `ListModel.setCell`), `prog-next`,
  `slider`, `confirm`, `toast` (popup 609600 label "Saved", hide after 3 s), `speller` (stretch: screen 609503 from
  the `cMOPTBLUETOOTHNAMEEDIT` recipe, screen 2500003, on 602759 with `setOpenSpellerOnOtherScreen(false)`).

Run (one boot): `./gradlew :spikes:hmi-ui:carJar`, `run.sh install --car-only`, `installer/mibsdk-dev restart`,
open Car → drawer row 0 (the spike page). Capture each frame twice-polled from `/screen.jpg`, 2 s after the input:

| # | Action | Frame | Pass |
|---|--------|-------|------|
| D1 | page shown | `ui-spike-d-page.jpg` | boxes drawn on all 4 checkbox rows; Checkbox 3 checked; "Checkbox off" greyed |
| D1 | rotate to Checkbox 1 | `ui-spike-d-cb1-focus.jpg` | empty box, focus frame |
| D1 | `knob_select` | `ui-spike-d-cb1-on.jpg` | box checked; log `page-toggle row=2 value=1` |
| D1 | `knob_select` | `ui-spike-d-cb1-off.jpg` | box empty again |
| D1 | command `cb-set` | `ui-spike-d-cb1-sdk-set.jpg` | box checked without input (model write from another thread) |
| D2 | select Radio C | `ui-spike-d-radio-c.jpg` | the mark moves from A to C |
| D3 | page / `prog-next` | `ui-spike-d-progress-60.jpg`, `-progress-80.jpg` | bar at 60 %, then 80 % |
| D4 | page | `ui-spike-d-value-col.jpg` | "English" right-aligned on the Language row |
| D5 | select the type-8 row | `ui-spike-d-row8-on.jpg` | box checked; log shows which `itemSelected` value(s) came |
| D5 | select the combo row | `ui-spike-d-combo-open.jpg` | dropdown with Off/Low/Mid/High |
| D5 | rotate +2, select | `ui-spike-d-combo-picked.jpg` | row shows "Mid"; log `choice 602755 value=2` |
| D5 | page | `ui-spike-d-row128.jpg` | Bass bar at the centre |
| D6 | select the Bass row (or `slider`) | `ui-spike-d-slider.jpg` | big rotary screen |
| D6 | rotate +3 | `ui-spike-d-slider-plus3.jpg` | value +3; log `range 602756 increment n=…` |
| D6 | `back` | `ui-spike-d-slider-back.jpg` | page, Bass row bar at +3 |
| D7 | `confirm` | `ui-spike-d-confirm.jpg` | text and Yes/No rows |
| D7 | rotate to No, select | `ui-spike-d-confirm-no.jpg` | page again; log `confirm row=1` |
| D8 | `toast` | `ui-spike-d-toast.jpg`, then +4 s `ui-spike-d-toast-gone.jpg` | "Saved" top right, then gone |
| D9 | (stretch) `speller`, type 2 characters, OK | `ui-spike-d-speller.jpg`, `-speller-typed.jpg` | speller draws; log `textChanged`, `commandPressed` |
| end | `back` to Car, `run.sh uninstall`, restart | `ui-spike-d-back-to-car.jpg` | Car screen; bundles file `cmp`-identical |

Gate: D1 go → land §1.4 in the SDK and remove the text toggles. D2-D4 go → `choice`, `progress`, value column and
icon cells in api 5. D5 combo go → `dropdown`. D6 go → `slider`; D6 no-go (no knob focus) → slider is display-only,
or a hijack fallback. D7 go → `Ui.confirm`. D8 go → `Ui.toast`. D9 decides whether `Ui.speller` is api 5 or later.
Leave the unit on its home screen.
