# Native input widgets on plugin pages (api 5) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Plugin pages draw the HMI's own input widgets: a real checkbox for `toggle`, multi-choice, a choice row with a radio sub-page, a value column, item icons, a dropdown, a slider (row and edit screen) and a progress bar. Plugins also get `Ui.confirm`, `Ui.toast` and an experimental `Ui.speller`, at api 5, in SDK 0.3.0.

**Architecture:**
- **List cells.** Everything that fits a list row stays in the page's one `ListController`. The kind is picked by `setRecordSetColumn(2)` (the spike D root-cause fix), and `setEnabledColumn(3)` greys a row. The page `ListModel` has 5 columns: label, int value, kind, enabled, value text. The SDK drops presses on disabled rows itself.
- **Typed rows.** `dropdown` (type 64 on a pooled `ChoiceModel`) and `slider` (type 128 on a pooled `RangeModel`) are menu items after the list, in spec order. A press on a slider row opens SDK screen 609501.
- **Sub-screens and dialogs.** `choice` opens a derived SDK page (`<pageKey>#<itemId>`, radio rows) on the existing page path and stack. The confirm (609502) and speller (609503) screens are requests carried in the snapshot (`UiDialog`). They open only over the requesting plugin's open page and always answer exactly once.
- **Toast.** A second Car-factory partial popup, 609601, sits in slot 7 left of the status popup. It is shown for 3 s from a snapshot `UiToast`.
- **Values.** Every value the HMI reports goes HMI → `UiTable.input` → runtime (range check, clamp, coalesce) → plugin → `page.update` → model write on `mibsdk-ui`. The GUI never sets a value itself.
- **Older runtimes.** Without `Features.UI_INPUTS`, new entries render as INFO text and the new `Ui` calls return inert handles. An older runtime refuses an api-5 manifest (`BLOCKED_INVALID`), and mibpkg refuses to install the plugin over SDK 0.2.0. No mibpkg change is needed.

**Tech Stack:** Unchanged: Gradle 8.13 with `build-logic`; JDK 17/Jabel → Retrolambda → Retrotranslator; class-file v48; JUnit 5.10.2; Python 3 `unittest`. mibpkg 0.9.1 (unchanged, already on the unit).

**Spec:** `docs/research/2026-10-25-native-inputs.md`: §4 (api 5), §6, and §7 (spike D results; the §7.2 recipes are authoritative for every widget build). Spike code: `spikes/hmi-ui/src/main/java/mibsdk/spike/ui/` (`SpikePage.Items`, `SpikePage.build`, `SpikeScreens`, `SpikeInputs`, `SpikeIds`, `SpikeBank`, `SpikeProbes` `toast`). Prior design: `docs/superpowers/plans/2026-10-24-menu-and-icons.md`, `docs/superpowers/plans/2026-10-25-icon-colour-and-reflection.md`.

**Repo and order:** SDK only, worktree `mib2q-plugin-sdk.worktrees/feat-native-inputs`, branch `feat/native-inputs`.
- Tasks 1 → 2 → 3 → 4 → 5 → 6 → 7, strictly in sequence: they share `UiEntry`, `UiHub`, `PageScreens`, `HqcUiIds` and the bank.
- Task 8 (inputs-demo, 0.3.0, docs) needs only Tasks 1–3 (api and FakeUi). It may start after Task 3 is committed and run in parallel with 4–7, since it touches only `plugins/inputs-demo`, `settings.gradle.kts`, `tools`, `installer` tests, `gradle.properties` and the docs.
- Task 9 (bench) needs 1–8. Nobody touches the bench before Task 9.

## Global Constraints

Copied verbatim from the 2026-10-24 and 2026-10-25 plans where they still bind:

**Bytecode and linking** (Plan 2, verbatim)
- Every class that runs on the unit is **class-file version 48**. v49/v50 are rejected by J9 2.3 even with `-noverify`. The build fails on anything else.
- Every on-unit artifact passes **LinkCheck** against the car JCL (`java/**`, `javax/**` extracted from `$mibsdk.stockLsdJar`) plus its declared car classpath.
- No stock constant in `adapter-hqc/src/main/java/mibsdk/adapter/hqc/ui/`. Every stock id (model, screen, event, text, popup, widgetID, atlas cell, key code) is a literal in `HqcUiIds`, taken from `lsd-src` (real values) and confirmed by a spike. `NoStockConstantsTest` enforces it.
- Platform and runtime code is Java 1.4 API: `StringBuffer`, no `codePointAt`, no try-with-resources.

**Threads** (Plan 2, verbatim, plus the System takeover)
- **No plugin code ever runs on a stock thread.**
- SDK code on stock threads now also includes:
  - the `SdkSystemActivator` constructor and `getScreenFactory`;
  - `SdkSystemScreenFactory.createScreen`/`getText`/`getAltText`;
  - the main-menu key handler;
  - `SdkCarScreenFactory.getImagePath`/`getPartialPopupStubs`/`getPartialPopup`;
  - the HMI job.

  This code may only read volatile SDK state, build or adjust stock widgets from it, and call `UiTable.input`. It catches `Throwable` and falls back to stock. It logs only through `RingLog.append`, does no I/O, creates no thread and takes at most 50 ms.
- App-side model writes happen only on `mibsdk-ui`. Widget-tree work is posted with `RunnableEvent(false, r)` through `HmiPoster`, with **at most one SDK job pending** (one combined job, Task 4).
- Time comes from the monotonic `Clock` (`uptimeMs`), never from wall time.

**Firmware and safety** (Plan 2, verbatim)
- Supported HMI, compared as an exact string: `HMI_AU_MIB2_HQC_NAR_G22_C10_180803`.
- Never add SDK settings to `bundles.properties` (every unknown key is a bundle). Never log, print or read the j9 command line.
- Plugins can't hide or cover the reverse camera, eCall or cluster warnings. SDK UI lives only in the MENU ring (one row), the Car drawer, SDK pages and the status popup.

**Icons** (verbatim)
- An icon is a PNG file declared by name in a package. Names match `[a-z0-9-]{1,32}`. Plugins declare at most 8 icons, the SDK's built-in set at most 32.
- It is validated **at package build** (build-logic `PngCheck`) and **at install** (mibpkg `png_check`), by the same rules: size ≤ 32768 bytes; PNG signature; IHDR first: 8-bit, colour type 6 (RGBA) or 4 (grey + alpha), compression 0, filter 0, interlace 0; width and height 1..72; every chunk's CRC correct; no `acTL`/`fcTL`/`fdAT`; IEND last; the IDAT data inflates to exactly `height * (1 + width * channels)` bytes.
- Status icons must also be ≤ 20×20. The runtime enforces that from the index's `w,h`.
- Image ids: one per `<owner>/<sha16>` key per HMI session, never reused or re-pointed. The owner is the plugin id, or `mibsdk` (the reserved plugin id) for the built-in set, so identical bytes in two packages never share an id or a file. The range is `609100..609399` (Car module, served by `SdkCarScreenFactory.getImagePath` from a precomputed relative path).

**Status line** (verbatim, the parts that bind the toast)
- `SAFE` and `PDC R OFF` are must-show.
- The line is drawn in popup **609600, slot 7**, at (514, 0, 255, 24), zpm slot 12, priority 250 (Boot B).
- Once 609600 is in use, the SDK never writes popup 65. If 609600 cannot be registered or built, the line goes to popup 65 as text only. That failure is sticky for the boot.

**Bench** (verbatim)
- SSH: `ssh -o HostKeyAlgorithms=ssh-rsa -o PubkeyAcceptedKeyTypes=ssh-rsa root@10.20.0.23`. Keypanel: `http://10.20.0.23:8099` (poll `/screen.jpg` twice).
- **Every HMI restart costs 10–12 min and can hang the unit** (P3-F12, Boot B). After a restart, run `ssh rpi-bench keypanel up`. If the unit is still unreachable after two tries, stop as BLOCKED and ask the owner to power-cycle. Never power the bench yourself. Batch checks so the bench task stays within its restart budget.
- Never select "Start update". Leave the unit on its home screen.

**Repo hygiene** (verbatim)
- Authorship `complex256` / `me@complex256.dev`: commit with `git -c user.name=complex256 -c user.email=me@complex256.dev commit`.
- No personal names or home paths in committed files. Stock jars are never committed.
- Before every commit: SDK: `./gradlew check` and `python3 -m unittest discover -s installer -p 'test_*.py'` pass.

**Amended by this plan**
- API: additive only. `SdkInfo.API_VERSION = 5` and `MIN_API_VERSION = 1`, so manifests with api 1–5 load. Plugin limits are unchanged: ≤ 2 Car rows, ≤ 1 status item of ≤ 12 chars.
- API use: a plugin whose classes reference any api-5 member (the `ApiLevels` table, Task 1) must declare `"api": 5`. The build refuses it otherwise, as for api 4.
- New feature flag `Features.UI_INPUTS = "ui.inputs"`. It is advertised by `HqcPlatform` with the UI (Task 7, once every widget exists), never by an adapter that lacks the widgets.
- Item icons are ≤ 24×24 (`PageSpec.ITEM_ICON_MAX_PX`). The icon cell does not scale (spike D), so a larger installed icon throws `IllegalArgumentException`. Built-in icons on items use their 20-px variant.
- Stock-thread code also includes the new list cells, the typed-row key handlers, the `ChoiceListener`/`RangeListener`/`SpellerListener`/`ListListener` of the input models, the screens 609501–609503 and the toast popup 609601. They follow the stock-thread rule above, and their files join `StockThreadCodeTest.STOCK_THREAD_FILES`.
- SDK bank: 602738..**602779** (it grows by 16, Task 5). SDK screens: 609500 (page), 609501 (slider), 609502 (confirm), 609503 (speller). SDK popups: 609600 (status), 609601 (toast). SDK texts: 609000..609099. Step 0 of Task 5 checks the new ids against `lsd-src`.
- SDK version **0.3.0** (`gradle.properties` `mibsdk.version`).
- Bench restart budget for Task 9: **1**, at most **2** (the 2nd only to recover from a failed boot).

## Review Focus

1. **A row kind change on refresh.** Examples: an item gains a value or an icon, `toggle` becomes `item` under the same key, or a dropdown or slider is added or removed. List rows must take the new kind (the HMI rebuilds a row whose record set changes, D1), and a change in the typed-row set or the icon set must rebuild the screen. **Tests:** Task 4 `UiApplierTest.samePageSeesEveryInputField`, `PageScreensMappingTest.kindFollowsValueAndIcon`, `PageScreensNavigationTest.iconSetChangeRebuildsScreen`; Task 5 `PageScreensNavigationTest.typedRowChangeRebuildsScreen`; bench N11.
2. **Presses on disabled rows.** The HMI still delivers them (D1 partial). Neither the adapter nor the runtime may act on one. **Tests:** Task 4 `PageScreensNavigationTest.disabledRowPressIsDropped`; Task 2 `UiHubTest.inputOnDisabledEntryIsDropped`; Task 1 `PageSpecTest.enabledOnlyOnListRows`; bench N2.
3. **Knob focus and step loss on the slider screen.** Knob steps arrive faster than the plugin's updates. They must accumulate from the last requested value, clamp at the ends and coalesce. **Tests:** Task 5 `SliderSessionTest.*`; Task 2 `UiHubTest.sliderValuesAreClampedSnappedAndCoalesced`; bench N7.
4. **BACK from the sub-screens.** BACK on the slider, confirm, speller and choice screens returns to the page without popping the stack or sending `PAGE_CLOSED`. A stock screen over a sub-screen closes the page and answers any dialog. **Tests:** Task 5 `PageScreensNavigationTest.subScreenKeepsPageOpen`, `backFromSubScreenReshowsPageWithoutPop`; Task 6 `PageScreensNavigationTest.stockScreenOverDialogAnswersThenCloses`; Task 4 `PageScreensNavigationTest.choiceBackReturnsWithoutAction`; bench N3, N7, N8, N10.
5. **Toast against the status popup in slot 7.** The toast must never hide or move 609600 (it carries `SAFE` and `PDC R OFF`), and must degrade without one if 609601 fails. **Tests:** Task 7 `SdkCarScreenFactoryTest.toastStubSitsLeftOfStatusInSlot7`, `toastNeverTouchesStatusVisibility`, `toastFailureIsStickyAndLeavesStatus`; bench N9.
6. **The speller's BACK-as-done losing text.** The typed text must reach the plugin whether the speller is left by BACK, by the band's close, or by a stock screen, and exactly once. **Tests:** Task 6 `SpellerSessionTest.*`; Task 3 `UiHubTest.dialogAnswersExactlyOnce`; bench N10.
7. **Old SDK with a new plugin.** An api-5 plugin must be refused cleanly by SDK 0.2.0: mibpkg refuses the install, and a running 0.2.0 runtime marks a hot-loaded one `BLOCKED_INVALID`, with no crash count and no quarantine. It never fails with a `NoSuchMethodError`. **Tests:** Task 1 `ManifestParserTest.apiAboveFiveIsRefused`, `SdkRuntimeBootTest.newerApiIsBlockedInvalidNotFailed`, `MibPluginPluginFunctionalTest.inputsNeedApi5`; bench H1, O1.

---

### Task 1: API level 5 surface, inert fallbacks, and the build's api scan — SDK repo

**Files:**
- Modify: `api/src/main/java/mibsdk/api/{PageSpec,Ui,UiAction,Features,SdkInfo}.java`, `api/permissions.json` (`"api": 5`); `runtime/src/main/java/mibsdk/runtime/UiImpl.java`; `build-logic/src/main/java/mibsdk/build/{PluginJson,PermissionScan,PermissionScanTask}.java`
- Create: `build-logic/src/main/java/mibsdk/build/ApiLevels.java`
- Test: `api/src/test/java/mibsdk/api/PageSpecTest.java` (create if absent), `PermissionCatalogTest.java`; `runtime/src/test/java/mibsdk/runtime/{UiImplTest,ManifestParserTest,SdkRuntimeBootTest}.java`; `build-logic/src/test/java/mibsdk/build/{PluginJsonTest,PermissionScanTest,MpkStageTest,MibPluginPluginFunctionalTest}.java`

**Interfaces:**
- Produces (all `@since api 5`, Javadoc "Needs api 5."):
  - `PageSpec.Kind` gains `CHOICE, DROPDOWN, SLIDER, PROGRESS` (appended).
  - Constants: `PageSpec.MAX_OPTIONS = 8`, `MAX_INPUT_ROWS = 4` (DROPDOWN + SLIDER per page), `MAX_SLIDER_STEPS = 24`, `ITEM_ICON_MAX_PX = 24`.
  - `PageSpec.choice(String itemId, String label, String[] options, int selected)`, `dropdown(...)` (same parameters), `slider(String itemId, String label, int min, int max, int step, int value, boolean centred)`, `progress(String itemId, String label, int percent)`, `item(String itemId, String label, String value, Icon icon)` (a null icon = `item(itemId, label, value)`), `enabled(String itemId, boolean enabled)`.
  - Argument rules: all throw `IllegalArgumentException`.
    - choice/dropdown: options non-null, with 1..8 non-null entries; `0 ≤ selected < n`.
    - slider: `min < max`, `step ≥ 1`, `(max - min) % step == 0`, `(max - min) / step ≤ 24`, and `value = min + k·step` within the range. `centred` needs an even step count.
    - enabled: only on an existing ITEM, TOGGLE, LINK or CHOICE id ("only list rows can be disabled").

    `progress` clamps `percent` to 0..100 (not rejected). A 5th DROPDOWN/SLIDER throws `IllegalStateException`.
  - `PageSpec.Entry` accessors: `options()` (a copy, or null), `number()` (selected index / slider value / percent), `min()`, `max()`, `step()`, `centred()`, `icon()`, `enabled()` (default true).
  - Javadoc of the class, verbatim intent: dropdown and slider rows are drawn after the list rows, in spec order; a slider shows its position, not its number.
  - `UiAction(String pageId, String itemId, Boolean requestedOn, Integer value, String text)`. The 3-arg constructor stays, with null value and text. `Integer value()`: the index (CHOICE, DROPDOWN), the level (SLIDER), or 1 yes / 0 no (confirm). `String text()`: the speller text. Both are in `toString`.
  - `Ui.confirm(String title, String text, String yes, String no, Listener<UiAction> onAnswer) -> Subscription`; `Ui.toast(String text, Icon icon)` (void); `Ui.speller(String title, String initial, int maxLen, Listener<UiAction> onDone) -> Subscription`.
    - Constants: `Ui.MAX_CONFIRM_TEXT = 120`, `MAX_TOAST = 20`, `TOAST_MS = 3000`, `MAX_SPELLER = 32` (`maxLen` 1..32, else IAE). Null yes/no mean "Yes"/"No".
    - Confirm Javadoc: it opens only while one of this plugin's pages is the open page; otherwise it answers 0 at once. It answers exactly once: 1 Yes, 0 No/BACK/dismissed. `cancel()` withdraws it with no answer. Text lines break at `'\n'` (at most 3 lines).
    - `toast` needs `ui.slot.statusbar` (else `PermissionDeniedException`, as `statusItem`), and the icon follows the status-icon rule (≤ 20 px).
    - `speller` Javadoc starts with **"EXPERIMENTAL."** It says: BACK leaves the speller and is "done", so `onDone` gets `text()` = the typed text; the speller has no OK key; a page may offer its own Save row; `text()` is null when it never showed.
  - `Features.UI_INPUTS = "ui.inputs"`, with Javadoc: "without it new entries show as text lines and confirm/toast/speller are inert".
  - `SdkInfo.API_VERSION = 5`; `PluginJson.MAX_API = 5` (`MpkStage` follows it).
  - `UiImpl` (this task only adds the inert paths; Tasks 2–3 make them live):
    - `static PageSpec forUnit(PageSpec spec, boolean inputs, boolean icons)`. Without `inputs`, each new-kind entry becomes an `info` line in the `UiPageText.line` style ("Level: Mid", "Bass: 3", "Progress: 60%", "Language: English"), and disabled entries keep their kind. Without `icons`, item icons are dropped. ITEM, TOGGLE, INFO and LINK pass through unchanged.
    - `page`/`update` check every item icon: it is this plugin's handle, and its installed size is ≤ 24 (else `IllegalArgumentException("item icon '<name>' is <w>x<h>; page items take at most 24x24")`). Then they pass `forUnit(spec, has(UI_INPUTS), has(UI_ICONS))` to the hub.
    - `confirm`/`speller` check their arguments, then return an inert `Subscription` (no answer ever), until Task 3. `toast` checks its permission and arguments, then does nothing.
  - `ApiLevels.since(String owner, String name, String desc) -> int` (1 if unlisted). The table has `Ui.icon(String,int)` → 4 (moved from `PermissionScan`), and → 5: every new `PageSpec` builder and `Entry` accessor, `Ui.confirm/toast/speller`, `UiAction.value/text` and the 5-arg `<init>`, and the field refs `PageSpec$Kind.CHOICE/DROPDOWN/SLIDER/PROGRESS`.
  - `PermissionScan` checks method **and field** insns against `ApiLevels`. `Result.needsApi()` = the max. `apiFindings()` keeps its line format (`owner.method -> Ui.icon(String,int)`; api-5 lines read `-> PageSpec.choice`, etc.). The new `Result.tooNew(int declared) -> List<String>` gives `<site> needs api <its level>`, and `PermissionScanTask` reports those lines.

- [ ] **Step 1: Write the failing tests:**
  - `PageSpecTest`:
    - `choiceAndDropdownRejectBadOptions`: null options, 0 options, 9 options, a null option, and selected −1 or n all throw IAE;
    - `sliderRules`: (0, 10, 3) throws (10 % 3 ≠ 0); (0, 30, 1) throws (30 > 24 steps); value 5 with (0, 10, 2) throws; centred (0, 9, 1) throws; (−6, 6, 1, 0, true) is accepted;
    - `progressClamps`: −5 → 0, 150 → 100;
    - `fifthTypedRowThrows`: 4 dropdown/slider rows are accepted, the 5th throws `IllegalStateException`;
    - `enabledOnlyOnListRows`: `enabled("lvl", false)` on a dropdown throws, an unknown id throws, and on a toggle `entry.enabled()` is false;
    - `entriesExposeTheirFields`: `options()` is a copy (mutating it leaves the spec unchanged).
  - `UiImplTest`:
    - `withoutInputsNewKindsAreInfoText`: a FakePlatform without `UI_INPUTS` publishes INFO "Level: Mid", "Bass: 3", "Progress: 60%", "Language: English", and toggles stay TOGGLE;
    - `itemIconLargerThan24Throws` (a 72-px icon);
    - `itemIconOfAnotherPluginThrows`;
    - `confirmAndSpellerAreInertWithoutInputs`: the listener is never called, and `cancel()` is accepted;
    - `toastNeedsStatusbarPermission`;
    - `spellerMaxLenOutOfRangeThrows` (0, 33).
  - `ManifestParserTest.acceptsApi1To5` (renamed) and `apiAboveFiveIsRefused` ("1..5").
  - `SdkRuntimeBootTest.newerApiIsBlockedInvalidNotFailed`: a jar declaring api 6 ends `BLOCKED_INVALID`, the failure count stays 0, and the other plugins are ENABLED. It is a regression guard: the same path on 0.2.0 handles api 5.
  - `PluginJsonTest`: `MAX_API` is 5, and the probe `fail5` becomes `fail6`. `MpkStageTest`: it stages `api = 5`. `PermissionCatalogTest`: it reads 5.
  - `PermissionScanTest`:
    - `findsApi5MembersAndKindFields`: a class calling `PageSpec.choice` gives 5, one reading `PageSpec.Kind.SLIDER` gives 5, and one calling only `toggle` gives 1;
    - `tooNewReportsEachSitesOwnLevel`: `Ui.icon(String,int)` plus `Ui.toast` with declared 3 give two lines, "needs api 4" and "needs api 5".
  - `MibPluginPluginFunctionalTest`:
    - the out-of-range fixture `api5` becomes `api6` (it must still fail);
    - `inputsNeedApi5`: a fixture plugin with `"api": 4` calling `PageSpec.slider` fails `mibPackage` with "needs api 5", and the same plugin with api 5 builds.
- [ ] **Step 2: Run them and verify they fail:** `./gradlew :api:test :runtime:test :build-logic:test`. Expected: FAIL (no `choice`, `MAX_API` 4).
- [ ] **Step 3: Implement** as specified. `PageSpec` stays Java 1.4 API (no regex, no varargs).
- [ ] **Step 4: Run and verify:** `./gradlew check && ./gradlew :build-logic:test && python3 -m unittest discover -s installer -p 'test_*.py'`. Expected: PASS.
- [ ] **Step 5: Commit:** `git add api runtime build-logic && git commit -m "feat(api): api 5 input entries, confirm/toast/speller (inert until wired), build-time api-5 check"`

---

### Task 2: Page input entries in the snapshot, the hub and FakeUi — SDK repo

**Files:**
- Modify: `platform/src/main/java/mibsdk/platform/{UiEntry,UiInput,UiPageText}.java`; `runtime/src/main/java/mibsdk/runtime/{UiHub,UiImpl}.java`; `adapter-fake/src/main/java/mibsdk/adapter/fake/FakeUi.java`
- Test: `runtime/src/test/java/mibsdk/runtime/{UiHubTest,SdkRuntimeUiTest}.java`, `adapter-fake/src/test/java/mibsdk/adapter/fake/FakeUiTest.java`, `platform/src/test/java/mibsdk/platform/UiPageTextTest.java` (create if absent)

**Interfaces:**
- Consumes: Task 1's `PageSpec` kinds, accessors and `UiAction` 5-arg constructor.
- Produces:
  - `UiEntry`:
    - kinds `CHOICE = 4, DROPDOWN = 5, SLIDER = 6, PROGRESS = 7, RADIO = 8` (RADIO only on derived choice pages);
    - immutable copies `withNumber(int)`, `withOptions(String[])`, `withRange(int min, int max, int step, boolean centred)`, `withEnabled(boolean)`, `withIcon(UiIcon)`;
    - getters `number()`, `options()`, `min()`, `max()`, `step()`, `centred()`, `enabled()` (default true), `icon()`.

    The 6-arg constructor is unchanged.
  - `UiInput`: `VALUE_REQUESTED = 5` (dropdown index or slider value), `DIALOG_DONE = 6` (Task 3). The new constructor is `UiInput(int kind, String pageKey, String entryKey, boolean on, int value, String text)`, with `value()` and `text()`.
  - `UiPageText`:
    - `line` (the FakeUi format) is unchanged for the old kinds (TOGGLE keeps `[on] `/`[off] ` in the fake). New kinds:
      - CHOICE `label: <option> >`;
      - DROPDOWN `label: <option> v`;
      - SLIDER `label: <value> [<min>..<max>]`;
      - PROGRESS `label: <n>%`;
      - RADIO `(*) <option>` / `( ) <option>`.

      A disabled entry gets the suffix ` [disabled]`, and an item icon its `[png:<key>] ` / `[stock:<name>] ` tag first, as FakeUi does for rows.
    - `label(UiEntry)` and `valueText(UiEntry)` are the HMI cells: the label alone (no `[on]/[off]`, LINK keeps ` >`), and the value column: ITEM value, CHOICE option, LINK value + ` >`, else null.
  - `UiHub`:
    - `static String choiceKey(String pageKey, String itemId)` = `pageKey + "#" + itemId`.
    - `toUiPage` maps the new kinds, cleaning options to `MAX_VALUE`. An item icon comes from `((UiImpl.IconHandle) e.icon()).icon` (null for inert), as LINK targets do.
    - Snapshot assembly adds one derived page per CHOICE entry: key `choiceKey`, title = the entry's label, and RADIO entries with keys `"0".."n-1"`, the option as label and `on = (i == selected)`. The CHOICE entry's `targetPageKey` is that key.
    - Input mapping (on `mibsdk-ui`):
      - ENTRY_SELECTED on `<pageKey>#<itemId>` with entry key `i` → `UiAction(pageId, itemId, null, i, null)` if `0 ≤ i < n`, else dropped;
      - VALUE_REQUESTED on DROPDOWN → `value = index` if in range, else dropped;
      - VALUE_REQUESTED on SLIDER → the value clamped to `[min, max]` and snapped to `min + k·step` (nearest; ties toward the current value);
      - **coalescing:** at most one SLIDER action per (pageKey, itemId) waits on the plugin's executor. A newer value replaces the waiting one's value;
      - any input on an entry with `enabled() == false` → dropped, logging `ui input for <key> dropped: disabled`;
      - PROGRESS, RADIO on a normal page, and INFO → nothing.
    - `PAGE_SHOWN`/`PAGE_CLOSED` of a `#` key count as its parent page's.
  - `UiImpl`: `forUnit` now passes new kinds through when `UI_INPUTS` is present.
  - `FakeUi` (mirrors the adapter):
    - `select` on CHOICE navigates locally to the derived page (as LINK does); `select` on RADIO sends ENTRY_SELECTED and goes back;
    - `pick(String pageKey, String entryKey, int index)` (DROPDOWN) and `turn(String pageKey, String entryKey, int value)` (SLIDER, raw: the runtime clamps) send VALUE_REQUESTED;
    - on a disabled entry, `select`/`toggle`/`pick`/`turn` send nothing.

- [ ] **Step 1: Write the failing tests:**
  - `UiHubTest`:
    - `choicePublishesItsSubPage`: page `p/main` with `choice("lang","Language",{"English","Deutsch"},0)` gives `p/main#lang`, titled "Language", with RADIO "0" on and "1" off, and the CHOICE target is that key;
    - `choicePickIsAnIndexAction`: ENTRY_SELECTED(`p/main#lang`, "1") → `UiAction{page=main, item=lang, value=1}`; "2" and "x" are dropped;
    - `dropdownIndexOutOfRangeIsDropped`;
    - `sliderValuesAreClampedSnappedAndCoalesced`: on (−6, 6, 1) with a paused executor, inputs 3, 9, 4 give one action with value 4; on (0, 10, 2), input 9 with current 4 gives 8, and 12 gives 10;
    - `inputOnDisabledEntryIsDropped`: TOGGLE_REQUESTED on a disabled toggle gives no action and one log line;
    - `progressAndInfoInputsDoNothing`.
  - `FakeUiTest`:
    - `rendersEveryInputKind`: lines exactly as specified above, including `[disabled]` and the icon tag;
    - `choiceRoundTrip`: select → `openPageKey()` is `p/main#lang`, `render` shows `(*) English`/`( ) Deutsch`; select "1" → the action arrives and `openPageKey()` is `p/main` again;
    - `disabledEntriesSendNothing`.
  - `SdkRuntimeUiTest.pluginSeesEveryInputAction`: a test plugin with each new kind gets the matching `UiAction` values through FakeUi.
  - `UiPageTextTest.hmiCellsDropTheTogglePrefix`: `label(toggle)` = "Wi-Fi", `valueText(choice)` = "English", `valueText(link with value)` = "on >".
- [ ] **Step 2: Run and verify they fail:** `./gradlew :platform:test :runtime:test :adapter-fake:test`. Expected: FAIL.
- [ ] **Step 3: Implement** as specified (Java 1.4 API in platform and runtime).
- [ ] **Step 4: Run and verify:** `./gradlew check`. Expected: PASS.
- [ ] **Step 5: Commit:** `git add platform runtime adapter-fake && git commit -m "feat(ui): input entries, choice sub-pages, clamped and coalesced values in the runtime and FakeUi"`

---

### Task 3: Dialogs (confirm, speller) and toasts in the snapshot and the hub — SDK repo

**Files:**
- Create: `platform/src/main/java/mibsdk/platform/{UiDialog,UiToast}.java`
- Modify: `platform/src/main/java/mibsdk/platform/UiSnapshot.java`; `runtime/src/main/java/mibsdk/runtime/{UiHub,UiImpl}.java`; `adapter-fake/src/main/java/mibsdk/adapter/fake/FakeUi.java`
- Test: `UiHubTest`, `UiImplTest`, `FakeUiTest`

**Interfaces:**
- Consumes: `UiInput.DIALOG_DONE` and its `value`/`text` (Task 2); the `Ui` signatures (Task 1).
- Produces:
  - `UiDialog`: `CONFIRM = 0, SPELLER = 1`, with `long seq()`, `int kind()`, `String ownerPageKey()`, `String title()`, `String text()` (the confirm text, already split to ≤ 3 lines on `'\n'`, each line cleaned; or the speller's initial text), `String yes()`, `String no()`, `int maxLen()`.
  - `UiToast`: `long seq()`, `String text()`, `UiIcon icon()`.
  - `UiSnapshot.dialog()` and `toast()`, both null for none, through a new constructor overload. The old constructors pass null.
  - Hub:
    - `long requestDialog(String owner, int kind, String title, String text, String yes, String no, int maxLen, Listener<UiAction> l, SerialExecutor exec)`:
      - it opens (seq++, published) only when the owner's page is the shown page (PAGE_SHOWN tracking, `#` keys included) and no dialog is up;
      - otherwise it answers at once: confirm `value 0`, speller `text null`.
    - `void cancelDialog(String owner, long seq)`: it clears without an answer.
    - `void showToast(String owner, String text, UiIcon icon)`: the text is cleaned to `MAX_TOAST`, and an empty text is ignored. seq++, and the newest replaces the last.
    - DIALOG_DONE with `entryKey = String.valueOf(seq)`:
      - confirm: `value = 1` only for `value == 1`, else 0;
      - speller: `text` cleaned to `maxLen`;
      - a stale or unknown seq is dropped.
    - PAGE_CLOSED of the owner's page while a dialog is up answers it as dismissed (0 / null) and clears it. Owner removal clears it with no answer.
    - Every request is answered **exactly once** on the plugin's executor, unless cancelled. The `UiAction` has `pageId` = the owner page's id and `itemId` = null.
  - `UiImpl.confirm/speller/toast` go live when `UI_PAGES` and `UI_INPUTS` are present (toast: `SLOT_STATUS_BAR` and `UI_INPUTS`). The toast icon goes through `ownIcon(icon, true)`, the status rule.
  - FakeUi:
    - `String dialog()`: `confirm:<title>|<text>|<yes>|<no>` or `speller:<title>|<text>`, null for none;
    - `answer(int value)`; `type(String text)` (sets the speller text); `leave()` (BACK = done with the typed text);
    - `String toast()`: `[tag] text` of the latest toast, or null.

- [ ] **Step 1: Write the failing tests:**
  - `UiHubTest`:
    - `confirmOpensOnlyOverOwnersPage`: with another page shown it answers `value 0` at once, and no dialog is published;
    - `dialogAnswersExactlyOnce`: DIALOG_DONE twice, or DIALOG_DONE then PAGE_CLOSED, gives one answer;
    - `cancelledDialogIsNeverAnswered`;
    - `secondDialogWhileOneIsUpIsAnsweredAtOnce`;
    - `spellerTextIsCleanedToMaxLen`;
    - `pageClosedAnswersDismissed`;
    - `toastNewestReplaces`;
    - `confirmTextKeepsThreeLines`: "a\nb\nc\nd" gives "a\nb\nc d".
  - `UiImplTest.confirmRoundTripThroughFakeUi`, `spellerLeaveDeliversTypedText`, `toastShowsWithOwnIconTag`.
  - `FakeUiTest.dialogAndToastRendering`.
- [ ] **Step 2: Run and verify they fail:** `./gradlew :platform:test :runtime:test :adapter-fake:test`. Expected: FAIL.
- [ ] **Step 3: Implement** as specified.
- [ ] **Step 4: Run and verify:** `./gradlew check`. Expected: PASS.
- [ ] **Step 5: Commit:** `git add platform runtime adapter-fake && git commit -m "feat(ui): confirm, speller and toast requests in the snapshot; one answer per dialog"`

---

### Task 4: List cells on the page: checkbox, radio, progress, value column, icons, disabled rows — SDK repo

**Files:**
- Create: `adapter-hqc/src/main/java/mibsdk/adapter/hqc/ui/{PageRow,PageLayout}.java`
- Modify: `adapter-hqc/src/main/java/mibsdk/adapter/hqc/ui/{PageScreens,PageWidgets,UiApplier,HqcUiIds}.java`
- Test: `adapter-hqc/src/test/java/mibsdk/adapter/hqc/ui/{PageScreensMappingTest,PageScreensNavigationTest,PageWidgetsTest,UiApplierTest,StockThreadCodeTest}.java`, new `PageLayoutTest.java`

**Interfaces:**
- Consumes: `UiEntry` kinds and fields, `UiPageText.label/valueText`, `UiHub.choiceKey` targets (Task 2); `IconIds`, `BuiltinIcons` (existing).
- Produces:
  - `PageScreens` row kinds (the list's record set, column 2): `ROW_ACTION 0, ROW_TOGGLE 1, ROW_LABEL 2, ROW_RADIO 3, ROW_PROGRESS 4, ROW_VALUE 5, ROW_ICON 6, ROW_ICON_VALUE 7`. The mapping, `static int rowKind(UiEntry e, int iconIndex)`:
    - INFO → LABEL; TOGGLE → TOGGLE; RADIO → RADIO; PROGRESS → PROGRESS;
    - ITEM, LINK, CHOICE → VALUE when `valueText` is non-null, else ACTION;
    - ITEM with `iconIndex ≥ 0` → ICON / ICON_VALUE.
  - `PageRow(String label, int value, int kind, boolean enabled, String valueText)`, final fields, `equals`. `static PageRow of(UiEntry e, int iconIndex)`: `value` is 1/0 for toggle and radio, the percent for progress, the icon index for icon kinds, else 0.
  - `PageLayout`: `int[] iconIds()` (the distinct ids of the page's item icons in order, ≤ 24), `int iconIndex(UiIcon)` (−1 if absent), `equals`/`hashCode`. `static PageLayout of(UiPage p, IconIds ids, BuiltinIcons builtins)`: a built-in uses its 20-px id, a file icon `IconIds.idFor`. Task 5 adds the typed rows.
  - `Screens.build(String pageKey, String title, PageScreens owner, PageLayout layout)`; `Screens.writeRows(PageRow[] rows)`.
  - `PageScreens`:
    - a refresh whose `PageLayout` differs from the shown one re-shows (rebuilds) the page screen; otherwise it only writes rows;
    - `selected(row)` drops a row whose entry is disabled;
    - CHOICE → `requestNav(new Nav(e.targetPageKey()))`;
    - RADIO → `table.input(ENTRY_SELECTED, shownKey, e.key())` then `requestNav(new Nav(null))` (back).
  - `PageWidgets` (recipes copied from spec §7.2 and `SpikePage.Items`):
    - `build`: `setRecordSetColumn(2)`, `setEnabledColumn(3)`, `setMaxColumns(5)`;
    - `Rows.createListItem(kind, cells)` builds the §7.2 layout for each kind: checkbox `{{0},{1}}` mapping on column 1; radio on column 1 with `max {-1, 33}`; progress `setBitmaps {40..45}`, `setColorIndices {1,2,4,0}`, 0..100, height 15, `min {-1, 300}`; value label on column 4 `setAlignment(3, 5)`; icon `IconController` on column 1 with `setBitmaps(layout.iconIds())`, `gaps {0,15,0}`, `max {30,-1}`;
    - `writeRows` writes `TextListCell` label (0), `IntegerListCell` value (1), kind (2), enabled 1/0 (3) and `TextListCell` value text (4, "" for none).

    Every literal goes into `HqcUiIds` (`PAGE_COL_*`, `CHECKBOX_MAPPING`, `PROGRESS_BITMAPS`, `PROGRESS_COLOURS`, …).
  - The wrong comments at `PageScreens.java:46` and `HqcUiIds.PAGE_LIST_MODEL` (the id column picks the row) are fixed: the record-set column picks it.
  - `UiApplier.samePage` compares every new `UiEntry` field (kind, number, options, range, centred, enabled, icon).

- [ ] **Step 1: Write the failing tests:**
  - `PageScreensMappingTest`:
    - `kindFollowsValueAndIcon`: an item with no value is ACTION; with a value, VALUE; with an icon, ICON; with both, ICON_VALUE; LINK "on" is VALUE with valueText "on >"; CHOICE is VALUE with valueText "English";
    - `toggleRowIsLabelOnlyWithValue01`: label "Wi-Fi", value 1, kind 1;
    - `disabledRowWritesEnabled0`.
  - `PageScreensNavigationTest`:
    - `disabledRowPressIsDropped`: no input on the table;
    - `choiceOpensSubPageAndPickReturns`: select the CHOICE row → nav to `p/main#lang`; select row 1 → ENTRY_SELECTED(`p/main#lang`, "1"), then back to `p/main` with no `PAGE_CLOSED`;
    - `choiceBackReturnsWithoutAction`;
    - `iconSetChangeRebuildsScreen`: a refresh adding a new icon id builds a new screen, and a refresh with only a new index does not.
  - `PageLayoutTest.distinctIdsInOrderAndIndex`.
  - `PageWidgetsTest` (with `HostWidgets.init()`):
    - `listUsesRecordSetAndEnabledColumns`;
    - `toggleRowHasCheckboxCellOnColumn1`;
    - `eachKindBuildsItsCells` (radio, progress, value, icon, icon_value: the controller classes and model columns).
  - `UiApplierTest.samePageSeesEveryInputField`: a change in only `enabled`, `number`, an option, the range or the icon is not `samePage`.
  - `StockThreadCodeTest`: `PageRow` and `PageLayout` join `STOCK_THREAD_FILES`.
- [ ] **Step 2: Run and verify they fail:** `./gradlew :adapter-hqc:test`. Expected: FAIL.
- [ ] **Step 3: Implement** as specified.
- [ ] **Step 4: Run and verify:** `./gradlew check` (NoStockConstantsTest, LinkCheck). Expected: PASS.
- [ ] **Step 5: Commit:** `git add adapter-hqc && git commit -m "feat(hqc): native list cells (checkbox, radio, progress, value, icon), disabled rows, choice sub-page"`

---

### Task 5: Typed rows (dropdown, slider row) and the slider edit screen 609501 — SDK repo

**Files:**
- Create: `adapter-hqc/src/main/java/mibsdk/adapter/hqc/ui/{SliderSession,SliderScreen,InputTextIds}.java`
- Modify: `HqcUiIds.java`, `SdkCarModelBank.java`, `SdkCarScreenFactory.java` (`getText`), `PageLayout.java`, `PageScreens.java`, `PageWidgets.java`, `UiApplier.java`
- Test: new `SliderSessionTest.java`, `InputTextIdsTest.java`; `PageScreensNavigationTest`, `PageWidgetsTest`, `SdkCarScreenFactoryTest`, `StockThreadCodeTest`

**Interfaces:**
- Consumes: DROPDOWN/SLIDER entries and VALUE_REQUESTED (Task 2); `PageLayout`, `Screens` (Task 4).
- Produces:
  - **Step 0 (host check, recorded in the `HqcUiIds` Javadoc as the bank note is):** `lsd-src` has no model id 602764..602779 and no Car text id 609040..609079, decoded or byte-swapped, in the namespaces that matter, and CarModelBank stays at 2738 models.
  - `HqcUiIds`:
    - `TYPED_CHOICE_MODEL_0 = MODEL_BASE + 26` (4 ChoiceModels, 602764..602767);
    - `TYPED_RANGE_MODEL_0 = MODEL_BASE + 30` (4 RangeModels, 602768..602771);
    - Task 6's `CONFIRM_TEXT_MODEL = +34`, `CONFIRM_LIST_MODEL = +35`, `SPELLER_MODEL = +36`;
    - Task 7's `TOAST_VISIBLE_MODEL = +37`, `TOAST_LABEL_MODEL = +38`, `TOAST_FILE_ICON_MODEL = +39`;
    - `MODEL_COUNT = 42` (602738..602779). Tasks 6 and 7 fill in their models; the bank creates all 42 now.
    - `SLIDER_SCREEN_ID = 609501`;
    - text ids `TEXT_INPUT_BASE = TEXT_BASE + 40`: typed slot `s` gets label `609040 + 9s` and options `+1..+8`; `TEXT_SLIDER_MIN = 609076`, `TEXT_SLIDER_MAX = 609077`, `TEXT_CONFIRM_TITLE = 609078`, `TEXT_SPELLER_TITLE = 609079`;
    - every §7.2 combo/rotary/slider-screen literal: combo bitmaps `{127 ×42, 129, 332}`, fonts 5, insets 6/7, type 64, widgetID 1, type 128, glassplate `(59,77,946,324)` with its coordinate set, rotary `(397,122,100,100)`, min/max labels `(226,323)`/`(621,323)` 202×30, mirror container `(0,0,1024,480)`, key 17.
  - `PageLayout` gains `TypedRow[] typed()` (`kind`, `entryKey`, `label`, `options`, `steps = (max-min)/step`, `centred`), in spec order, slot = position. A change in it rebuilds the screen (Task 4's rule).
  - `InputTextIds.textFor(int id, PageLayout shown, SliderSession slider, UiDialog dialog) -> String` (null if not ours). `SdkCarScreenFactory.getText` asks it before stock.
  - `SliderSession` (stock-free, HMIEvent only):
    - `SliderSession(int min, int step, int steps, int value)`;
    - `int index()`;
    - `int onTurn(int n)`: accumulates from the last *requested* index, clamped to `0..steps`, and returns the requested value `min + index·step`;
    - `void onModel(int value)`: a refresh with a new plugin value resets the index to it.
  - The RangeModel works in index space: `setLimits(0, steps, 1)`, `setMedialPosition(centred ? steps/2 : 0)`, `setValue((value-min)/step)`. It is written on `mibsdk-ui` by the applier from the snapshot.
  - Dropdown:
    - the pooled `ChoiceModel` value = the selected index (applier);
    - the bank's `ChoiceListener.itemSelected(id, v)` → `UiTable.input(VALUE_REQUESTED, shownKey, entryKey, v)`, and `itemFocused` is ignored;
    - a pick on a disabled dropdown cannot happen: `enabled` is list-only (Task 1).
  - Slider:
    - the row's own key handler (set before connect, §7.2) on key 17 → `PageScreens.sliderPressed(slot)`, which opens `SliderScreen` (609501) if screen changes are allowed;
    - the screen's RangeListener `increment/decrement(n)` → `SliderSession.onTurn(±n)` → VALUE_REQUESTED;
    - BACK (key 15, consumed) → `PageScreens.subBack()`.
  - `PageScreens` sub-screen state:
    - `showSub(int screenId, Object screen)`: while a sub-screen (609501..609503) is connected, the page stays open (no `PAGE_CLOSED`) and `shownKey` keeps routing input;
    - `subBack()` re-shows the page screen with the same rows and no stack pop;
    - a stock screen connecting over a sub-screen is treated as leaving the page (as today).

- [ ] **Step 1: Write the failing tests:**
  - `SliderSessionTest`:
    - `accumulatesFromLastRequest`: steps 12, min −6, value 0 (index 6): `onTurn(2)` → 2, then `onTurn(3)` → 5 without an `onModel`;
    - `clampsAtBothEnds`: `onTurn(20)` → 6, `onTurn(-30)` → −6;
    - `modelResetsIndex`;
    - `stepUnitsMapToValues`: min 0, step 2, steps 5, `onTurn(1)` from 4 → 6.
  - `PageScreensNavigationTest`:
    - `subScreenKeepsPageOpen`: `showSub(609501)`, then connect, then nothing is sent; `PAGE_CLOSED` is never sent;
    - `backFromSubScreenReshowsPageWithoutPop`: stack depth unchanged, the page screen shown, rows written;
    - `typedRowChangeRebuildsScreen`: adding a slider rebuilds; a value change only writes the model;
    - `sliderPressOnStaleSlotDoesNothing`: `sliderPressed` on a slot with no live SLIDER entry opens nothing (sliders cannot be disabled, Task 1);
    - `sliderPressWhileBlockedDoesNothing` (`currentBlocked` true).
  - `InputTextIdsTest`: the labels, options and min/max texts ("-6", "+6" for a centred slider, "0", "10" otherwise), and null for 609039 and 609080.
  - `PageWidgetsTest.typedRowsFollowTheList`: the menu holds the list, then the combo row with `setTextIds` = its option ids and the rotary row with its own key handler; the screen's `setViews` ids are sorted.
  - `SdkCarScreenFactoryTest.bankHas42SdkModels`.
- [ ] **Step 2: Run and verify they fail:** `./gradlew :adapter-hqc:test`. Expected: FAIL.
- [ ] **Step 3: Implement** as specified. `SliderScreen` follows `SpikeScreens.SliderRotary`/`OwnScreen`; it is built on the HMI job like the page screen.
- [ ] **Step 4: Run and verify:** `./gradlew check`. Expected: PASS.
- [ ] **Step 5: Commit:** `git add adapter-hqc && git commit -m "feat(hqc): dropdown and slider rows, slider edit screen 609501 with knob accumulation"`

---

### Task 6: Confirm screen 609502 and speller screen 609503 — SDK repo

**Files:**
- Create: `adapter-hqc/src/main/java/mibsdk/adapter/hqc/ui/{ConfirmScreen,SpellerScreen,SpellerSession}.java`
- Modify: `HqcUiIds.java`, `SdkCarModelBank.java`, `PageScreens.java`, `UiApplier.java`
- Test: new `SpellerSessionTest.java`; `PageScreensNavigationTest`, `PageWidgetsTest`, `StockThreadCodeTest`

**Interfaces:**
- Consumes: `UiSnapshot.dialog()`, `UiInput.DIALOG_DONE` (Tasks 2–3); `PageScreens.showSub/subBack` (Task 5); bank models `CONFIRM_*`, `SPELLER_MODEL` (Task 5's ids).
- Produces:
  - `HqcUiIds`: `CONFIRM_SCREEN_ID = 609502`, `SPELLER_SCREEN_ID = 609503`, `CONFIRM_AUTOWRAP = 0`, `SPELLER_OPENED = 4711`, `SPELLER_CLOSED = 4712`. Plus the §7.2 confirm and speller literals: `setSmallStageType(1)`; colours `{1,0,4,0}`; `setTextOrientationHints {6}`; touch bitmaps `{483,484,857}`; the 49 speller bitmaps of `cMOPTBLUETOOTHNAMEEDIT`; colours `{2,5,4,3}`/`{2,0,4,0}`; stubs 5602 (16) and 5606 (8); insets 17/5; height 60; level 2.
  - `PageScreens.dialogChanged(UiDialog d)`, called on the applier's job:
    - it shows a new seq's screen only when `shownKey.equals(d.ownerPageKey())` (or its `#` child) and `changeAllowed()`;
    - otherwise it sends `DIALOG_DONE(seq, 0, null)` ("not shown");
    - a dialog withdrawn (null, or a new seq) while its screen is up → `subBack()` with no answer.
  - `ConfirmScreen`:
    - built from the §7.2 recipe, shown as a non-popup `ScreenData`;
    - the hint `LabelController` + `MultiLineLabelRendererHigh` with `setAutoWrap(HqcUiIds.CONFIRM_AUTOWRAP)`, the text from `CONFIRM_TEXT_MODEL` (lines joined by `'\n'`);
    - a `ListController` on `CONFIRM_LIST_MODEL` with rows yes, no;
    - row 0 → DIALOG_DONE value 1, row 1 → 0, BACK → 0; each then `subBack()`.
  - `SpellerSession` (stock-free, HMIEvent only):
    - `SpellerSession(long seq, String initial)`;
    - `void onTextChanged(String t)`;
    - `String doneText(String modelText)`: `modelText` if non-null, else the last `textChanged`, else `initial`;
    - `boolean takeDone()`: true only the first time.
  - `SpellerScreen`: the §7.2 recipe with `setOpenSpellerOnOtherScreen(false)`, `SpellerModel.setMaxLength(maxLen)` and `setText(initial)`.
    - BACK (key 15, consumed) → DIALOG_DONE(seq, 0, `doneText(model.getText())`) once, then `subBack()`.
    - 4712 (band closed) does not finish.
    - A disconnect without BACK (a stock screen) sends the same DIALOG_DONE once, before the page-closed path.
  - A stock screen over a dialog: DIALOG_DONE (dismissed: confirm 0; speller with its text), then the existing leave-page handling.

- [ ] **Step 1: Write the failing tests:**
  - `SpellerSessionTest`:
    - `backUsesModelText`;
    - `backWithoutModelTextUsesLastChanged`;
    - `noTypingGivesInitial`;
    - `doneOnlyOnce`: BACK then disconnect gives one done.
  - `PageScreensNavigationTest`:
    - `dialogShownOnlyOverOwnersPage`: another page shown → DIALOG_DONE value 0 right away;
    - `confirmRowsAndBackAnswer` (row 0 → 1, row 1 → 0, BACK → 0, each followed by the page re-shown, no pop);
    - `withdrawnDialogReturnsWithoutAnswer`;
    - `stockScreenOverDialogAnswersThenCloses`: DIALOG_DONE precedes `PAGE_CLOSED`;
    - `spellerDisconnectDeliversText`.
  - `PageWidgetsTest.confirmLabelAutoWrapIsZero`: the hint renderer's autowrap reads 0 after build (`HostWidgets`).
- [ ] **Step 2: Run and verify they fail:** `./gradlew :adapter-hqc:test`. Expected: FAIL.
- [ ] **Step 3: Implement** as specified, from `SpikeScreens`/`SpikeInputs`.
- [ ] **Step 4: Run and verify:** `./gradlew check`. Expected: PASS.
- [ ] **Step 5: Commit:** `git add adapter-hqc && git commit -m "feat(hqc): confirm (609502) and experimental speller (609503) screens; BACK is done"`

---

### Task 7: Toast popup 609601 in slot 7, and advertise `UI_INPUTS` — SDK repo

**Files:**
- Create: `adapter-hqc/src/main/java/mibsdk/adapter/hqc/ui/SdkToastPopup.java`
- Modify: `HqcUiIds.java`, `SdkCarScreenFactory.java` (stubs, `getPartialPopup`), `SdkCarModelBank.java`, `UiApplier.java`, `adapter-hqc/src/main/java/mibsdk/adapter/hqc/HqcPlatform.java` (adds `Features.UI_INPUTS` to `FEATURES_UI` and `FEATURES_HOOKED_UI`), `adapter-hqc/src/main/java/mibsdk/adapter/hqc/SdkGlobals.java` (`toastPopup` state)
- Test: `SdkCarScreenFactoryTest`, `UiApplierTest`, new `SdkToastPopupTest.java`, `adapter-hqc/src/test/java/mibsdk/adapter/hqc/HqcPlatformTest.java`, `StockThreadCodeTest`

**Interfaces:**
- Consumes: `UiSnapshot.toast()` (Task 3); `StatusLayout` char and icon constants; the `TOAST_*` model ids (Task 5).
- Produces:
  - **Slot rule (the definition the bench checks):** slot 7 holds several partial popups at once when their rects are disjoint. Spike D D8 showed 609600 beside 65, and menu-icons S2 showed 609600 with 65. So the toast is its own popup:
    - id `POPUP_SDK_TOAST = 609601`, in `POPUP_SDK_SLOT` (7), zpm 12, priority 250, type 7;
    - rect `POPUP_TOAST_X = POPUP_SDK_X - POPUP_SDK_W` (259), y 0, w 255, h 24, ending where the status popup starts;
    - its text is right-aligned, with the icon (≤ 20 px) left of the text;
    - the toast **never writes** `STATUS_POPUP_VISIBLE_MODEL` or any status model.
  - `getPartialPopupStubs` adds the toast stub after the status stub when `SdkGlobals.toastPopup != -1`. `getPartialPopup(609601)` builds `SdkToastPopup`. Any Throwable → `toastPopup := -1` (sticky; status untouched). Toasts are then dropped with one log line.
  - `UiApplier`:
    - on a new toast seq, it writes the label and the file icon or id icon (as a status cell), then visibility 1, and sets `toastHideAt = clock.uptimeMs() + Ui.TOAST_MS`;
    - its loop wait is bounded by `toastHideAt`, and at the deadline it writes visibility 0;
    - a newer toast restarts the timer;
    - UI down → hidden.
  - `HqcPlatform` advertises `UI_INPUTS` with the UI features.

- [ ] **Step 1: Write the failing tests:**
  - `SdkCarScreenFactoryTest`:
    - `toastStubSitsLeftOfStatusInSlot7`: stub id 609601, slot 7, rect (259, 0, 255, 24), placed after the status stub;
    - `toastFailureIsStickyAndLeavesStatus`: a forced build failure gives −1, no stub on the next call, and the status popup still registered;
    - `toastNeverTouchesStatusVisibility`: show and hide leave `STATUS_POPUP_VISIBLE_MODEL` unchanged.
  - `UiApplierTest`:
    - `toastShowsThenHidesAfter3000ms` (FakeClock: visible at 2999, hidden at 3000);
    - `newerToastRestartsTimer`;
    - `toastWhilePopupFailedIsDroppedOnce`.
  - `SdkToastPopupTest.rightAlignedTextWithIcon`.
  - `HqcPlatformTest.uiFeaturesIncludeInputs`.
- [ ] **Step 2: Run and verify they fail:** `./gradlew :adapter-hqc:test`. Expected: FAIL.
- [ ] **Step 3: Implement** as specified, from `SpikeProbes` `toast` and `SpikePopup`.
- [ ] **Step 4: Run and verify:** `./gradlew check`. Expected: PASS.
- [ ] **Step 5: Commit:** `git add adapter-hqc && git commit -m "feat(hqc): toast popup 609601 beside the status popup in slot 7; advertise ui.inputs"`

---

### Task 8: inputs-demo sample, SDK 0.3.0, docs — SDK repo (after Task 3; parallel with 4–7)

**Files:**
- Create: `plugins/inputs-demo/build.gradle.kts` (copy of hello-menu's), `plugins/inputs-demo/src/main/resources/plugin.json`, `src/main/java/sample/inputsdemo/InputsDemoPlugin.java`, `src/main/icons/plug-24.png`, `src/test/java/sample/inputsdemo/InputsDemoPluginTest.java`
- Modify: `settings.gradle.kts` (include `plugins:inputs-demo`), `tools/make_builtin_icons.py` (`plugin_inputs_demo()`, `--inputs-demo` output), `installer/test_make_builtin_icons.py`, `gradle.properties` (`mibsdk.version=0.3.0`), `CHANGELOG.md` (0.3.0 entry)

**Interfaces:**
- Consumes: the api 5 surface (Task 1) and FakeUi drivers (Tasks 2–3).
- Produces:
  - `plugin.json`: `{"id":"inputs-demo","name":"Inputs Demo","version":"1.0.0","author":"complex256","api":5,"main":"sample.inputsdemo.InputsDemoPlugin","permissions":["ui.slot.statusbar"],"icons":["plug-24"]}`. It has no Car row: it is reached from MENU → Plugins.
  - One page `inputs`, "Inputs", in this order (item ids in brackets):
    - toggle "Wi-Fi" [wifi]; toggles "Red" [red], "Green" [green], "Blue" [blue] (multi-choice); toggle "Locked" [locked], disabled;
    - choice "Language" [lang] {English, Deutsch, Français};
    - item "Version" [ver] value "1.0.0";
    - item "Plug" [plug] with icon `plug-24`; item "Plug value" [plugv] with icon and value "on";
    - progress "Progress" [prog] 60; item "Progress +20" [prog-next] (mod 120, clamped by the SDK);
    - item "Swap kind" [swap]: it flips [swapme] between `toggle("swapme","Swap me",false)` and `item("swapme","Swap me","item")`; plus [swapme] itself;
    - item "Confirm" [confirm]: a 110-char text with no `'\n'`; item "Confirm 2 lines" [confirm2]: "First line\nSecond line";
    - item "Toast" [toast]: `toast("Saved", plug-24)`; item "Toast in 5 s" [toast5]: the plugin's `Scheduler` fires it 5000 ms later;
    - item "Name" [name] value = the stored name: it opens `speller("Name", name, 16, …)`; after a done with changed text, item "Save name" [save] appears; Save stores it, removes the row and toasts "Name saved";
    - dropdown "Level" [level] {Off, Low, Mid, High}; slider "Bass" [bass] −6..6 step 1 centred;
    - info "Last: <action>" (the last `UiAction.toString()`, cut by the SDK).

    Every action is also logged at INFO. State lives in storage keys `name`, `level`, `bass`.
  - `plug-24.png`: a 24×24 white plug glyph from `plugin_inputs_demo()`. The build also stages its `.r` (h > 20), which is unused off the drawer.
  - The CHANGELOG 0.3.0 entry covers: native checkbox for every toggle, the api-5 list, `Ui.speller` experimental, inputs-demo 1.0.0, and "an api-5 plugin needs SDK 0.3.0; mibpkg refuses it over 0.2.0".

- [ ] **Step 1: Write the failing tests:**
  - `InputsDemoPluginTest` (FakeUi):
    - `pageRendersEveryInput`: the full `render` list, exactly;
    - `toggleAndMultiChoice`;
    - `lockedSendsNothing`;
    - `choicePicksDeutsch`;
    - `dropdownAndSliderPersist`: `pick` 2 → `level` = 2 in storage; `turn` 9 → Bass 6;
    - `progressNext`;
    - `swapKindAlternates`;
    - `confirmYesNo`;
    - `toastAndDelayedToast` (FakeClock 5000);
    - `spellerSaveFlow`: leave with "BC" → "Name: BC" plus a "Save name" row; select Save → stored, row gone, toast "Name saved";
    - `manifestIsApi5Version100`.
  - `test_make_builtin_icons.InputsDemoTest.test_committed_icon_is_tool_output`.
- [ ] **Step 2: Run and verify they fail:** `./gradlew :plugins:inputs-demo:test && python3 -m unittest installer.test_make_builtin_icons`. Expected: FAIL.
- [ ] **Step 3: Implement**; generate the icon with `python3 tools/make_builtin_icons.py --inputs-demo plugins/inputs-demo/src/main/icons`.
- [ ] **Step 4: Run and verify:** `./gradlew check && python3 -m unittest discover -s installer -p 'test_*.py' && ./gradlew :dist:mibpkgPackage :plugins:inputs-demo:mibPackage -Pmibsdk.mibpkg=<mibpkg 0.9.1>`. Expected: PASS, `mibsdk-0.3.0.mpk` (api 5, min_api 1) and `inputs-demo-1.0.0.mpk`.
- [ ] **Step 5: Commit:** `git add plugins/inputs-demo settings.gradle.kts tools installer gradle.properties CHANGELOG.md && git commit -m "feat(samples): inputs-demo 1.0.0 exercises every api-5 input; mibsdk 0.3.0"`

---

### Task 9: Bench check — BENCH (restart budget: 1 planned, 2 at most; the 2nd only to recover from a failed boot)

**Files:**
- Create: `docs/bench-e2e-native-inputs.md`, `docs/bench-e2e/inputs-*.jpg`, `docs/bench-e2e/inputs-log-r1.txt`

**Host first (no unit):** build host mibpkg 0.9.1 fresh from mibpkg main `a0a5e49`. From this branch, run `./gradlew :dist:mibpkgPackage :plugins:<each>:mibPackage -Pmibsdk.mibpkg=<host mibpkg>`, with no version override, and record the package table as release 0.2 did. Make a fake root with a `pax -w` copy of the unit (the same set as `docs/bench-e2e-release-0.2.md` "Fake root", including `/mnt/app/img_ver.txt`).
- [ ] H1 (old SDK + new plugin): `mibpkg --root <root> install-file` of `inputs-demo-1.0.0.mpk` while the root has SDK 0.2.0 → refused with "needs plugin api 5; the installed SDK supports 1..4". Nothing changes (tree `cmp`).
- [ ] H2: install `mibsdk-0.3.0`, then every plugin package → `updated mibsdk 0.2.0 -> 0.3.0`; inputs-demo is installed and its index has `plug-24` and `plug-24.r`. The bundles file is byte-equal before and after.

**On the unit, before the restart:**
- [ ] `curl -s 10.20.0.23:8099/status` → `bridge_connected:true`. Save a host copy of the bundles file.
- [ ] `installer/mibsdk-dev install` (0.3.0), then `installer/mibsdk-dev install-plugin inputs-demo-1.0.0.mpk --approve=ui.slot.statusbar`.
- [ ] O1 (old runtime + new plugin, live): the still-running 0.2.0 runtime logs inputs-demo `BLOCKED_INVALID` "unsupported api 5", and the 5 others stay ENABLED. `sdk status`: sdk 0.3.0, api 1..5, reboot needed yes, ledger 0. The bundles file is byte-equal to the copy.

**R1 (restart 1).** Run `installer/mibsdk-dev restart`, then `ssh rpi-bench keypanel up`. Two failed tries → BLOCKED, and the owner power-cycles. If the HMI stays black more than 5 min after SSH answers, run the emergency revert below and stop. Each frame is polled twice, 2 s after the input. Re-send a select dropped while "Loading…".
- [ ] N0: the log has `ui system=sdk`, `ui car=sdk`, `ui status popup 609600 registered/built`, the toast stub 609601 registered, 6 × ENABLED including inputs-demo, and `boot healthy: 6 plugin(s) enabled`.
- [ ] N1 checkbox: MENU → Plugins. The SDK's own plugin page shows its "Enabled" toggles as boxes (an old-api path, no `[on]`). Then open Inputs Demo: Wi-Fi off → on → off; Red and Blue checked together (`-checkbox.jpg`, `-multi.jpg`).
- [ ] N2: "Locked" is grey. Select it → no change; "Last:" is unchanged and the log has `dropped: disabled` (`-disabled.jpg`).
- [ ] N3 choice (assembled here for the first time): the "Language … English" row → the sub-page with radio marks, English marked (`-choice-sub.jpg`). Pick Deutsch → back on the page, the row reads Deutsch (`-choice-picked.jpg`). Open again and BACK → the page, no action (`-choice-back.jpg`).
- [ ] N4: "Version … 1.0.0" right-aligned. The plug icon draws inside its column (24 px, no spill), and "Plug value" has icon and value (`-value-icon.jpg`, zoom crop).
- [ ] N5: the progress bar at 60 → "Progress +20" → 80 (`-progress.jpg`).
- [ ] N6 dropdown: open, knob +2, select → "Mid" (`-dropdown-open.jpg`, `-dropdown-mid.jpg`).
- [ ] N7 slider: the Bass row's knob preview. Select → 609501 with the knob focused; rotate +3 → the pointer +3, and "Last" shows `value=3` (`-slider-plus3.jpg`). Rotate +10 → it stops at +6. BACK → the page, with the row at +6 (`-slider-back.jpg`).
- [ ] N8 confirm: "Confirm" → the long text. **Record whether `setAutoWrap(0)` wraps it** (`-confirm-wrap.jpg`). No → `value=0`; "Confirm 2 lines" shows two lines (`-confirm-2lines.jpg`); Yes → `value=1`; then BACK on a third one → `value=0`.
- [ ] N9 toast: "Toast" → "Saved" with the plug icon left of the status line, and the status line still fully visible (`-toast-page.jpg`); gone after ~3 s (`-toast-gone.jpg`). "Toast in 5 s", then `back` to the home screen → the toast shows over home beside the status line, which is unchanged (`-toast-home.jpg`).
- [ ] N10 speller: "Name" → the speller. Select opens the band; type 2 characters with a frame check before each select (the band re-lays out); BACK → the page shows "Name … <text>" and "Save name" (`-speller-typed.jpg`, `-speller-back.jpg`). Save → toast "Name saved".
- [ ] N11: "Swap kind" twice: the row becomes a checkbox, then a value row, in place (`-swap.jpg`).
- [ ] N12: BACK out of the page and the Plugins menu to home. Save `/tmp/mibsdk.log` as `inputs-log-r1.txt`.

Findings that need a code change (for example, N8's wrap) are recorded with a follow-up and are **not** fixed and re-booted in this task. The confirm `'\n'` path (N8, second confirm) is the documented fallback for plugins.

**Emergency revert** (blank HMI): SSH still works. Run `mount -uw /mnt/app`, then copy mibpkg's stock backup over `/mnt/app/eso/hmi/lsd/bundles_dsitracer_enabled.properties` and `chmod 777` it. Then `sync; mount -ur /mnt/app; slay -s11 j9`, run `mibpkg sdk status`, and report BLOCKED with the log.

- [ ] End state, checked and written down:
  - `mibpkg sdk status` shows `HMICar=…SdkCarActivator`, `HMISystem=…SdkSystemActivator` (the takeover ACTIVE), sdk 0.3.0, api 1..5, running yes, reboot needed no, ledger 0, and all 6 plugins ENABLED;
  - `/mnt/app` read-only, `dev/` empty;
  - the unit on its home screen.
- [ ] Write `docs/bench-e2e-native-inputs.md`: H1–H2, O1, N0–N12, the findings table, frames, restart times and the end state.
- [ ] Commit: `git add docs/bench-e2e-native-inputs.md docs/bench-e2e && git commit -m "docs: bench check for native inputs (api 5, mibsdk 0.3.0)"`

---

## Decisions in this plan

[decided] comes from the owner or the spec; [my call] is a planning choice open to review.

- [decided, owner] Api 5 covers: the native checkbox (`toggle` unchanged, drawn as a box), multi-choice, choice, the value column, item icons ≤ 24 px, dropdown, slider (row + edit screen), progress, `Ui.confirm`, `Ui.toast`, and `Ui.speller` (EXPERIMENTAL, BACK = done).
- [decided, owner] Additive: `API_VERSION = 5`, MIN 1, `Features.UI_INPUTS`, inert or text fallbacks, and a build-time api-5 check like api 4's.
- [decided, owner] One bench task, a budget of 1 (2 at most), the host `--root` dry run first, the hang rule, the stated end state, and SDK 0.3.0.
- [decided, spec §7] Every widget uses the §7.2 recipe. The SDK filters disabled presses. Icons are not scaled. The toast gets its own popup id. The confirm uses `setAutoWrap(0)`, verified at N8.
- [my call] A new **inputs-demo** sample, not hello-menu: 20+ rows would crowd hello-menu's icon demo past `MAX_ENTRIES`, and hello-menu stays the api-4 sample. inputs-demo has no Car row (fewer permissions), and is opened from MENU → Plugins.
- [my call] **`PageSpec.enabled(itemId, boolean)`** is added (list rows only), so the disabled-row filter has a caller and the bench can test it. It is not in the owner's list: see the open point.
- [my call] The choice sub-page is a runtime-derived page (`<pageKey>#<itemId>`, `#` cannot occur in ids) on the existing page screen and stack, not a new screen id. Navigation, BACK and FakeUi reuse the LINK path. A pick sends the index and returns, and the plugin's update redraws the row.
- [my call] Dropdown and slider rows are drawn after the list rows (they are menu items, not list cells). At most 4 per page come from a fixed pool, and any change in that set rebuilds the screen. A slider shows its position, not its number (the stock knob draws no number); a plugin shows the number in another row if it wants one.
- [my call] The slider RangeModel works in step-index space (`0..steps`), so step > 1 needs no knowledge of the firmware's step semantics. Knob turns accumulate from the last requested value (`SliderSession`), and the runtime clamps, snaps and coalesces.
- [my call] Confirm and speller open only over the requesting plugin's own open page; otherwise they answer at once (0 / null). They are never shown over stock screens, and every request is answered exactly once unless cancelled.
- [my call] The toast needs `ui.slot.statusbar` (no new permission, no catalog change). It is 20 characters, with an icon ≤ 20 px, for 3 s, and the newest replaces the last. It lives in slot 7 at (259, 0, 255, 24), left of and disjoint from the status popup, and never touches it. So `SAFE`/`PDC R OFF` are never hidden.
- [my call] FakeUi keeps the `[on] `/`[off] ` text format (existing plugin tests read it). Only the HMI cells drop it (`UiPageText.label`).
- [my call] No mibpkg change: 0.9.1 already refuses a plugin api outside the installed SDK's range, and an old runtime marks an api-5 manifest `BLOCKED_INVALID` without a failure count (H1, O1).
- [my call] `UI_INPUTS` is advertised only in Task 7, so no intermediate build claims widgets it lacks.
- [my call] Bench findings are follow-ups. There is no second restart for a fix.

**Open points for the owner:**
1. Keep `PageSpec.enabled(itemId, boolean)` in api 5 (recommended: it makes the disabled-row filter reachable and testable), or drop it and leave the enabled column unused.
