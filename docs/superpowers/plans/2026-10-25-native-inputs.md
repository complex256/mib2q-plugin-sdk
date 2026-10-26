# Native input widgets on plugin pages (api 5) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Plugin pages draw the HMI's own input widgets: a real checkbox for `toggle`, multi-choice, a choice row with a radio sub-page, a value column, item icons, a dropdown, a slider (row and edit screen) and a progress bar. Plugins also get `Ui.confirm`, `Ui.toast` and an experimental `Ui.speller`, at api 5, in SDK 0.3.0.

**Architecture:**
- **List cells.** Everything that fits a list row stays in the page's one `ListController`. The kind is picked by `setRecordSetColumn(2)` (the spike D root-cause fix), and `setEnabledColumn(3)` greys a row. The page `ListModel` has 5 columns: label, int value, kind, enabled, value text. The SDK drops presses on disabled rows itself.
- **Typed rows.** `dropdown` (type 64 on a pooled `ChoiceModel`) and `slider` (type 128 on a pooled `RangeModel`) are menu items after the list, in spec order. A press on a slider row opens SDK screen 609501.
- **Sub-screens and dialogs.** `choice` opens a derived SDK page (`<pageKey>#<itemId>`, radio rows) on the existing page path and stack. The confirm (609502) and speller (609503) screens are requests carried in the snapshot (`UiDialog`). They open only over the requesting plugin's open page and always answer exactly once.
- **Toast.** A second Car-factory partial popup, 609601, sits in **its own, otherwise unused popup slot 6**, left of the status popup. It is shown for 3 s from a snapshot `UiToast`.
- **Values.** Every value the HMI reports goes HMI → `UiTable.input` → runtime (range check, clamp, coalesce) → plugin → `page.update` → model write. The GUI never sets a value itself.
- **Older runtimes.** Without `Features.UI_INPUTS`, new entries render as INFO text and the new `Ui` calls are inert (a dialog answers "no" at once). An older runtime refuses an api-5 manifest (`BLOCKED_INVALID`), and mibpkg refuses to install the plugin over SDK 0.2.0. No mibpkg change is needed.

**Tech Stack:** Unchanged: Gradle 8.13 with `build-logic`; JDK 17/Jabel → Retrolambda → Retrotranslator; class-file v48; JUnit 5.10.2; Python 3 `unittest`. mibpkg 0.9.1 (unchanged, already on the unit).

**Spec:** `docs/research/2026-10-25-native-inputs.md`: §4 (api 5), §6, and §7 (spike D results; the §7.2 recipes are authoritative for every widget build). Spike code: `spikes/hmi-ui/src/main/java/mibsdk/spike/ui/` (`SpikePage.Items`, `SpikePage.build`, `SpikeScreens`, `SpikeInputs`, `SpikeIds`, `SpikeBank`, `SpikeProbes` `toast`). Prior design: `docs/superpowers/plans/2026-10-24-menu-and-icons.md`, `docs/superpowers/plans/2026-10-25-icon-colour-and-reflection.md`. Pre-flight: `.superpowers/sdd/2026-10-25-native-inputs/preflight.md` (findings F1–F28, all applied; tags like "(F4)" point there).

**Repo and order:** SDK only, worktree `mib2q-plugin-sdk.worktrees/feat-native-inputs`, branch `feat/native-inputs`.
- Tasks 1 → 2 → 3 → 4 → 5 → 6 → 7, strictly in sequence: they share `UiEntry`, `UiHub`, `PageScreens`, `UiApplier`, `HqcUiIds` and the bank.
- Task 8 (inputs-demo, 0.3.0, docs) needs only Tasks 1–3 (api and FakeUi). It may start after Task 3 is committed and run in parallel with 4–7, since it touches only `plugins/inputs-demo`, `settings.gradle.kts`, `tools`, `installer` tests, `gradle.properties` and `CHANGELOG.md`, none of which Tasks 4–7 touch.
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
- **Model writes (amends the Threads rule, F15).** SDK-owned screen models may be written inside the one combined HMI job: the page rows (`PAGE_LIST_MODEL`), the confirm Yes/No rows (`CONFIRM_LIST_MODEL`), and the speller's `setMaxLength`/`setText` before its screen connects. Plugin-facing app models stay on `mibsdk-ui` only: the pooled typed-row `ChoiceModel`/`RangeModel`s, the toast models and the status models. The one-pending-job rule is unchanged.
- The pre-commit gate also runs `./gradlew :build-logic:test` (an included build that the root `check` does not run).
- API: additive only. `SdkInfo.API_VERSION = 5` and `MIN_API_VERSION = 1`, so manifests with api 1–5 load. Plugin limits are unchanged: ≤ 2 Car rows, ≤ 1 status item of ≤ 12 chars.
- API use: a plugin whose classes reference any api-5 member (the `ApiLevels` table, Task 1) must declare `"api": 5`. The build refuses it otherwise, as for api 4.
- New feature flag `Features.UI_INPUTS = "ui.inputs"`. It is advertised by `HqcPlatform` with the UI (Task 7, once every widget exists), never by an adapter that lacks the widgets. Like the other UI flags, it is fixed for a boot (F23).
- Item icons are ≤ 24×24 (`PageSpec.ITEM_ICON_MAX_PX`). The icon cell does not scale (spike D), so a larger installed icon throws `IllegalArgumentException`. Built-in icons on items use their 20-px variant.
- **Toast slot (F1).** The toast popup 609601 uses partial-popup **slot 6**, never slot 7. Evidence from `lsd-src/lsd`:
  - every stock `new PartialPopupStub(...)` (19 factories) uses slots 0, 1, 2, 3, 4, 5, 9, 10, 11, 12 and 13, and none uses 6 or 8;
  - EarlyApps (parking/RVC, `EarlyAppsScreenFactory.java:3118`) uses 1, 2, 4 and 9;
  - System (volume, status bar, popup 65 and the rest, `SystemScreenFactory.java:5662`) uses 0, 3, 5, 10, 12 and 13;
  - Phone/eCall-side stubs (`PhoneScreenFactory.java:9102`) use 0;
  - `IPartialPopupManagerEvo.PARTIAL_POPUP_SLOT_DEPTHS` gives slot 6 depth 0, the same as the status popup's slot 7 (slot 8 has 20), so the toast stacks exactly like the proven status popup, under every EarlyApps slot (depths 10, 11, 19, 21);
  - `AbstractPartialPopupManager.SLOTS` lists 6 beside 7 (both count as "a partial popup is visible").

  One popup per slot is shown at a time (spike C S1), so the toast and the status popup never displace each other.
- Stock-thread code also includes the new list cells, the typed-row key handlers, the input models' `ChoiceListener`/`RangeListener`/`SpellerListener`/`ListListener`, the screens 609501–609503 and the toast popup 609601. They follow the stock-thread rule above, and their files join `StockThreadCodeTest.STOCK_THREAD_FILES`. Those files read `HqcUiIds` constants, never `mibsdk.api.*` (for example, not `Ui.TOAST_MS`).
- SDK bank: 602738..**602779** (Task 5).
  - +26..+29: 4 ChoiceModels; +30..+33: 4 RangeModels; +35: the confirm `ListModel`; +36: the SpellerModel; +37/+38/+39: toast visibility, label and file icon.
  - +34 (confirm text) and +40/+41 are **reserved with no model**, like +10.

  SDK screens: 609500 (page), 609501 (slider), 609502 (confirm), 609503 (speller). SDK popups: 609600 (status), 609601 (toast). SDK texts: 609000..609099; this plan uses 609040..609082. SDK menu-item internal ids: 609900..609908 (rows) and 609960..609963 (typed rows). Step 0 of Task 5 checks the new ids against `lsd-src`.
- SDK version **0.3.0** (`gradle.properties` `mibsdk.version`).
- Bench restart budget for Task 9: **1**, at most **2** (the 2nd only to recover from a failed boot).

## Review Focus

1. **A row kind change on refresh.** Examples: an item gains a value or an icon, `toggle` becomes `item` under the same key, or a dropdown or slider is added or removed. List rows must take the new kind (the HMI rebuilds a row whose record set changes, D1), and a change in the typed-row set or the icon set must rebuild the screen. **Tests:** Task 4 `UiApplierTest.samePageSeesEveryInputField`, `PageScreensMappingTest.kindFollowsValueAndIcon`, `PageScreensNavigationTest.iconSetChangeRebuildsScreen`; Task 5 `PageScreensNavigationTest.typedRowChangeRebuildsScreen`, `typedEntryBetweenListRowsKeepsRowIndexes`; bench N11.
2. **Presses on disabled rows.** The HMI still delivers them (D1 partial). Neither the adapter nor the runtime may act on one. **Tests:** Task 4 `PageScreensNavigationTest.disabledRowPressIsDropped`; Task 2 `UiHubTest.inputOnDisabledEntryIsDropped`; Task 1 `PageSpecTest.enabledOnlyOnListRows`; bench N2.
3. **Knob focus and step loss on the slider screen.** Knob steps arrive faster than the plugin's updates. They must accumulate from the last requested value, clamp at the ends and coalesce. **Tests:** Task 5 `SliderSessionTest.*`; Task 2 `UiHubTest.sliderValuesAreClampedSnappedAndCoalesced`; bench N7 (both sliders, reversing at the ends).
4. **BACK from the sub-screens.** BACK on the slider, confirm, speller and choice screens returns to the page without popping the stack or sending `PAGE_CLOSED`. A stock screen over a sub-screen closes the page and answers any dialog. **Tests:** Task 5 `PageScreensNavigationTest.subScreenKeepsPageOpen`, `backFromSubScreenReshowsPageWithoutPop`; Task 4 `UiApplierTest.pendingNavRunsWithoutPageChange`; Task 6 `PageScreensNavigationTest.stockScreenOverDialogAnswersThenCloses`; Task 4 `PageScreensNavigationTest.choiceBackReturnsWithoutAction`; bench N3, N7, N8, N10.
5. **The toast against the status popup.** The toast must never hide or move 609600 (it carries `SAFE` and `PDC R OFF`), and must degrade with no toast if 609601 fails or if the status popup is ever hidden while a toast shows. **Tests:** Task 7 `SdkCarScreenFactoryTest.toastStubUsesFreeSlot6LeftOfStatus`, `toastNeverTouchesStatusVisibility`, `toastFailureIsStickyAndLeavesStatus`, `statusHiddenDuringToastDisablesToasts`; bench N9 (toast, status popup and `PDC R OFF` in one frame).
6. **The speller's BACK-as-done losing text.** The typed text must reach the plugin whether the speller is left by BACK, by the band's close, or by a stock screen, and exactly once. **Tests:** Task 6 `SpellerSessionTest.*`; Task 3 `UiHubTest.dialogAnswersExactlyOnce`; bench N10 (BACK with the band open and with it closed).
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
  - `PageSpec.choice(String itemId, String label, String[] options, int selected)`, `dropdown(...)` (same parameters), `slider(String itemId, String label, int min, int max, int step, int value, boolean centred)`, `progress(String itemId, String label, int percent)`, `item(String itemId, String label, String value, Icon icon)` (a null icon = `item(itemId, label, value)`), `enabled(String itemId, boolean enabled)` (kept by controller ruling).
  - Argument rules: all throw `IllegalArgumentException`.
    - choice/dropdown: options non-null, with 1..8 non-null entries; `0 ≤ selected < n`.
    - slider: `min < max`, `step ≥ 1`, `(max - min) % step == 0`, `(max - min) / step ≤ 24`, and `value = min + k·step` within the range. `centred` needs an even step count.
    - enabled: only on an existing ITEM, TOGGLE, LINK or CHOICE id ("only list rows can be disabled").

    `progress` clamps `percent` to 0..100 (not rejected). A 5th DROPDOWN/SLIDER throws `IllegalStateException`.
  - `PageSpec.Entry` accessors: `options()` (a copy, or null), `number()` (selected index / slider value / percent), `min()`, `max()`, `step()`, `centred()`, `icon()`, `enabled()` (default true).
  - The class Javadoc says: dropdown and slider rows are drawn after the list rows, in spec order; a slider shows its position, not its number; a slider's knob may lag a fast turn near its ends until the plugin's update arrives (F18).
  - `UiAction(String pageId, String itemId, Boolean requestedOn, Integer value, String text)`. The 3-arg constructor stays, with null value and text. `Integer value()`: the index (CHOICE, DROPDOWN), the level (SLIDER), or 1 yes / 0 no (confirm). `String text()`: the speller text. Both are in `toString`.
  - `Ui.confirm(String title, String text, String yes, String no, Listener<UiAction> onAnswer) -> Subscription`; `Ui.toast(String text, Icon icon)` (void); `Ui.speller(String title, String initial, int maxLen, Listener<UiAction> onDone) -> Subscription`.
    - Constants: `Ui.MAX_CONFIRM_TEXT = 120`, `MAX_TOAST = 20`, `TOAST_MS = 3000`, `MAX_SPELLER = 32` (`maxLen` 1..32, else IAE). Null yes/no mean "Yes"/"No".
    - **Exactly once, always (F7).** Every confirm and speller call is answered exactly once on the plugin's executor, unless `cancel()` withdraws it first, and that includes the inert case. Confirm: 1 = Yes; 0 = No, BACK, dismissed, not over this plugin's open page, or inert. Speller: `text()` = the typed text, or null when it never showed (inert, or not over this plugin's page).
    - Confirm Javadoc: it opens only while one of this plugin's pages is the open page. Text lines break at `'\n'` (at most 3 lines).
    - `toast` needs `ui.slot.statusbar` (else `PermissionDeniedException`, as `statusItem`), and the icon follows the status-icon rule (≤ 20 px).
    - `speller` Javadoc starts with **"EXPERIMENTAL."** It says: BACK leaves the speller and is "done", so `onDone` gets `text()` = the typed text; the speller has no OK key; a page may offer its own Save row.
  - `Features.UI_INPUTS = "ui.inputs"`, with Javadoc: "without it new entries show as text lines and confirm/toast/speller are inert; fixed for a boot".
  - `SdkInfo.API_VERSION = 5`; `PluginJson.MAX_API = 5` (`MpkStage` follows it).
  - `UiImpl` (this task adds the inert paths; Tasks 2–3 make them live):
    - `static PageSpec forUnit(PageSpec spec, boolean inputs, boolean icons)`. It rebuilds the spec **through the public builders only** (`Entry`'s constructor is package-private).
      - Without `inputs`, each new-kind entry becomes an `info` line in the `UiPageText.line` style ("Level: Mid", "Bass: 3", "Progress: 60%", "Language: English"). `enabled(...)` is re-applied only to entries that stay list rows, never to one turned into INFO (A1).
      - Without `icons`, item icons are dropped.
      - ITEM, TOGGLE, INFO and LINK pass through unchanged.

      It runs at `page()`/`update()` time, which is fine because features are fixed per boot (F23).
    - `page`/`update` check every item icon: it is this plugin's handle, and its installed size is ≤ 24 (else `IllegalArgumentException("item icon '<name>' is <w>x<h>; page items take at most 24x24")`). Then they pass `forUnit(spec, has(UI_INPUTS), has(UI_ICONS))` to the hub.
    - `confirm`/`speller` check their arguments. Until Task 3, and without the features afterwards, they return an inert `Subscription` and post the inert answer (confirm `value 0`, speller `text null`) once on the plugin's executor. `toast` checks its permission and arguments, then does nothing.
  - `ApiLevels.since(String owner, String name, String desc) -> int` (1 if unlisted). The table has `Ui.icon(String,int)` → 4 (moved from `PermissionScan`), and → 5: every new `PageSpec` builder and `Entry` accessor, `Ui.confirm/toast/speller`, `UiAction.value/text` and the 5-arg `<init>`, and the field refs `PageSpec$Kind.CHOICE/DROPDOWN/SLIDER/PROGRESS`.
  - `PermissionScan` checks method **and field** insns against `ApiLevels`. `Result.needsApi()` = the max. `apiFindings()` keeps its line format (`owner.method -> Ui.icon(String,int)`; api-5 lines read `-> PageSpec.choice`, etc.). The new `Result.tooNew(int declared) -> List<String>` gives `<site> needs api <its level>`, and `PermissionScanTask` reports those lines.

- [ ] **Step 1: Write the failing tests:**
  - `PageSpecTest`:
    - `choiceAndDropdownRejectBadOptions`: null options, 0 options, 9 options, a null option, and selected −1 or n all throw IAE;
    - `sliderRules`: (0, 10, 3) throws (10 % 3 ≠ 0); (0, 30, 1) throws (30 > 24 steps); value 5 with (0, 10, 2) throws; centred (0, 9, 1) throws; (−6, 6, 1, 0, true) and (0, 10, 2, 4, false) are accepted;
    - `progressClamps`: −5 → 0, 150 → 100;
    - `fifthTypedRowThrows`: 4 dropdown/slider rows are accepted, the 5th throws `IllegalStateException`;
    - `enabledOnlyOnListRows`: `enabled("lvl", false)` on a dropdown throws, an unknown id throws, and on a toggle `entry.enabled()` is false;
    - `entriesExposeTheirFields`: `options()` is a copy (mutating it leaves the spec unchanged).
  - `UiImplTest`:
    - `withoutInputsNewKindsAreInfoText`: a FakePlatform without `UI_INPUTS` publishes INFO "Level: Mid", "Bass: 3", "Progress: 60%", "Language: English"; toggles stay TOGGLE; a disabled CHOICE becomes INFO with no exception (A1);
    - `itemIconLargerThan24Throws` (a 72-px icon);
    - `itemIconOfAnotherPluginThrows`;
    - `inertConfirmAnswersNoOnce` and `inertSpellerAnswersNullOnce`: without `UI_INPUTS`, the listener is called once (value 0 / text null), and `cancel()` before the executor runs gives no call;
    - `toastNeedsStatusbarPermission`;
    - `spellerMaxLenOutOfRangeThrows` (0, 33).
  - `ManifestParserTest.acceptsApi1To5AndIconsOnlyWithApi3` (renamed from `acceptsApi1To4AndIconsOnlyWithApi3`, keeping the icons half) and `apiAboveFiveIsRefused` ("1..5").
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
- Modify: `platform/src/main/java/mibsdk/platform/{UiEntry,UiInput,UiPageText}.java`; `runtime/src/main/java/mibsdk/runtime/{UiHub,UiImpl,PluginsMenu}.java` (`PluginsMenu`: Javadoc only); `adapter-fake/src/main/java/mibsdk/adapter/fake/FakeUi.java`
- Test: `runtime/src/test/java/mibsdk/runtime/{UiHubTest,SdkRuntimeUiTest}.java`, `adapter-fake/src/test/java/mibsdk/adapter/fake/FakeUiTest.java`, `platform/src/test/java/mibsdk/platform/UiPageTextTest.java` (exists)

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
    - `label(UiEntry)` and `valueText(UiEntry)` are the HMI cells.
      - `valueText`: ITEM value, CHOICE option, LINK value + ` >`, else null.
      - `label`: the label alone (no `[on]/[off]`). A LINK keeps ` >` on its label **only when `valueText` is null**, so there is never a double chevron (F17).
  - `PluginsMenu` Javadoc: toggles render as native checkboxes on the HMI (`[on]/[off]` only in FakeUi).
  - `UiHub`:
    - `static String choiceKey(String pageKey, String itemId)` = `pageKey + "#" + itemId`.
    - `toUiPage` maps the new kinds, cleaning options to `MAX_VALUE`. An item icon comes from `((UiImpl.IconHandle) e.icon()).icon` (null for inert), as LINK targets do.
    - Snapshot assembly adds one derived page per CHOICE entry: key `choiceKey`, title = the entry's label, and RADIO entries with keys `"0".."n-1"`, the option as label and `on = (i == selected)`. The CHOICE entry's `targetPageKey` is that key.
    - `dispatchUnguarded` admits `VALUE_REQUESTED` (and, in Task 3, `DIALOG_DONE`) **before** its ENTRY_SELECTED/TOGGLE_REQUESTED-only kind filter (`UiHub.java:502`) (F8).
    - Input mapping (on `mibsdk-ui`):
      - ENTRY_SELECTED on `<pageKey>#<itemId>` with entry key `i` → `UiAction(pageId, itemId, null, i, null)` if `0 ≤ i < n`, else dropped;
      - VALUE_REQUESTED on DROPDOWN → `value = index` if in range, else dropped;
      - VALUE_REQUESTED on SLIDER → the value clamped to `[min, max]` and snapped to `min + k·step` (nearest; ties toward the current value);
      - **coalescing:** at most one SLIDER action per (pageKey, itemId) waits on the plugin's executor. A newer value replaces the waiting one's value;
      - any input on an entry with `enabled() == false` → dropped, logging `ui input for <key> dropped: disabled`;
      - PROGRESS, RADIO on a normal page, and INFO → nothing.
    - `PAGE_SHOWN`/`PAGE_CLOSED` of a `#` key count as its parent page's. The hub also records `shownPageKey`, the parent key of the **last** `PAGE_SHOWN` (cleared by its `PAGE_CLOSED`), for Task 3 (B3).
  - `UiImpl`: `forUnit` now passes new kinds through when `UI_INPUTS` is present.
  - `FakeUi` (mirrors the adapter):
    - `select` on CHOICE navigates locally to the derived page (as LINK does); `select` on RADIO sends ENTRY_SELECTED and goes back;
    - `pick(String pageKey, String entryKey, int index)` (DROPDOWN) and `turn(String pageKey, String entryKey, int value)` (SLIDER, raw: the runtime clamps) send VALUE_REQUESTED;
    - on a disabled entry, `select`/`toggle`/`pick`/`turn` send nothing.

- [ ] **Step 1: Write the failing tests:**
  - `UiHubTest`:
    - `choicePublishesItsSubPage`: page `p/main` with `choice("lang","Language",{"English","Deutsch"},0)` gives `p/main#lang`, titled "Language", with RADIO "0" on and "1" off, and the CHOICE target is that key;
    - `choicePickIsAnIndexAction`: ENTRY_SELECTED(`p/main#lang`, "1") → `UiAction{page=main, item=lang, value=1}`; "2" and "x" are dropped;
    - `valueRequestedAsTheAdapterSendsIt`: `new UiInput(VALUE_REQUESTED, "p/main", "lvl", false, 2, null)` reaches the plugin as value 2 (F8);
    - `dropdownIndexOutOfRangeIsDropped`;
    - `sliderValuesAreClampedSnappedAndCoalesced`: on (−6, 6, 1) with a paused executor, inputs 3, 9, 4 give one action with value 4; on (0, 10, 2), input 9 with current 4 gives 8, and 12 gives 10;
    - `inputOnDisabledEntryIsDropped`: TOGGLE_REQUESTED on a disabled toggle gives no action and one log line;
    - `progressAndInfoInputsDoNothing`;
    - `shownPageKeyIsTheLastShownParent`: SHOWN a, SHOWN b#x → `b`; CLOSED b#x → null.
  - `FakeUiTest`:
    - `rendersEveryInputKind`: lines exactly as specified above, including `[disabled]` and the icon tag;
    - `choiceRoundTrip`: select → `openPageKey()` is `p/main#lang`, `render` shows `(*) English`/`( ) Deutsch`; select "1" → the action arrives and `openPageKey()` is `p/main` again;
    - `disabledEntriesSendNothing`.
  - `SdkRuntimeUiTest.pluginSeesEveryInputAction`: a test plugin with each new kind gets the matching `UiAction` values through FakeUi.
  - `UiPageTextTest.hmiCellsDropTheTogglePrefix`: `label(toggle)` = "Wi-Fi", `valueText(choice)` = "English", `valueText(link with value)` = "on >" with `label` "Status" (no " >"), and `label(link without value)` = "More >".
- [ ] **Step 2: Run and verify they fail:** `./gradlew :platform:test :runtime:test :adapter-fake:test`. Expected: FAIL.
- [ ] **Step 3: Implement** as specified (Java 1.4 API in platform and runtime).
- [ ] **Step 4: Run and verify:** `./gradlew check && ./gradlew :build-logic:test`. Expected: PASS.
- [ ] **Step 5: Commit:** `git add platform runtime adapter-fake && git commit -m "feat(ui): input entries, choice sub-pages, clamped and coalesced values in the runtime and FakeUi"`

---

### Task 3: Dialogs (confirm, speller) and toasts in the snapshot and the hub — SDK repo

**Files:**
- Create: `platform/src/main/java/mibsdk/platform/{UiDialog,UiToast}.java`
- Modify: `platform/src/main/java/mibsdk/platform/UiSnapshot.java`; `runtime/src/main/java/mibsdk/runtime/{UiHub,UiImpl}.java`; `adapter-fake/src/main/java/mibsdk/adapter/fake/FakeUi.java`
- Test: `UiHubTest`, `UiImplTest`, `FakeUiTest`

**Interfaces:**
- Consumes: `UiInput.DIALOG_DONE` and its `value`/`text`, `UiHub.shownPageKey` (Task 2); the `Ui` signatures (Task 1).
- Produces:
  - `UiDialog`: `CONFIRM = 0, SPELLER = 1`, with `long seq()`, `int kind()`, `String ownerPageKey()` (always the **parent** page key, never a `#` key, so `UiAction.pageId` is the parent page id), `String title()`, `String text()` (the confirm text, already split to ≤ 3 lines on `'\n'`, each line cleaned; or the speller's initial text), `String yes()`, `String no()`, `int maxLen()`.
  - `UiToast`: `long seq()`, `String text()`, `UiIcon icon()`.
  - `UiSnapshot.dialog()` and `toast()`, both null for none. They are added to the private all-fields constructor through a new public overload; the old constructors and `down()` pass null (B3).
  - Hub:
    - `long requestDialog(String owner, int kind, String title, String text, String yes, String no, int maxLen, Listener<UiAction> l, SerialExecutor exec)`:
      - it opens (seq++, published) only when `shownPageKey` belongs to the owner and no dialog is up;
      - otherwise it answers at once: confirm `value 0`, speller `text null`.
    - `void cancelDialog(String owner, long seq)`: it clears without an answer.
    - `void showToast(String owner, String text, UiIcon icon)`: the text is cleaned to `MAX_TOAST`, and an empty text is ignored. seq++, and the newest replaces the last.
    - DIALOG_DONE carries `pageKey = dialog.ownerPageKey()` and `entryKey = String.valueOf(seq)` (F8). It is admitted before the kind filter, like VALUE_REQUESTED.
      - confirm: `value = 1` only for `value == 1`, else 0;
      - speller: `text` cleaned to `maxLen`;
      - a stale or unknown seq is dropped.
    - PAGE_CLOSED of the owner's page while a dialog is up answers it as dismissed (0 / null) and clears it. Owner removal clears it with no answer.
    - Every request is answered **exactly once** on the plugin's executor, unless cancelled. The `UiAction` has `pageId` = the owner page's id and `itemId` = null.
  - `UiImpl.confirm/speller/toast` go live when `UI_PAGES` and `UI_INPUTS` are present (toast: `SLOT_STATUS_BAR` and `UI_INPUTS`). The toast icon goes through `ownIcon(icon, true)`, the status rule.
  - FakeUi:
    - `String dialog()`: `confirm:<title>|<text>|<yes>|<no>` or `speller:<title>|<text>`, null for none;
    - `answer(int value)`; `type(String text)` (sets the speller text); `leave()` (BACK = done with the typed text). Each sends the adapter-shaped DIALOG_DONE;
    - `String toast()`: `[tag] text` of the latest toast, or null.

- [ ] **Step 1: Write the failing tests:**
  - `UiHubTest`:
    - `confirmOpensOnlyOverOwnersPage`: with another plugin's page shown it answers `value 0` at once, and no dialog is published;
    - `dialogDoneAsTheAdapterSendsIt`: `new UiInput(DIALOG_DONE, "p/main", "<seq>", false, 1, null)` answers 1 (F8);
    - `dialogAnswersExactlyOnce`: DIALOG_DONE twice, or DIALOG_DONE then PAGE_CLOSED, gives one answer;
    - `cancelledDialogIsNeverAnswered`;
    - `secondDialogWhileOneIsUpIsAnsweredAtOnce`;
    - `dialogFromChoiceSubPageUsesParentKey`: with `p/main#lang` shown, `ownerPageKey()` is `p/main`;
    - `spellerTextIsCleanedToMaxLen`;
    - `pageClosedAnswersDismissed`;
    - `toastNewestReplaces`;
    - `confirmTextKeepsThreeLines`: "a\nb\nc\nd" gives "a\nb\nc d".
  - `UiImplTest.confirmRoundTripThroughFakeUi`, `spellerLeaveDeliversTypedText`, `toastShowsWithOwnIconTag`.
  - `FakeUiTest.dialogAndToastRendering`.
- [ ] **Step 2: Run and verify they fail:** `./gradlew :platform:test :runtime:test :adapter-fake:test`. Expected: FAIL.
- [ ] **Step 3: Implement** as specified.
- [ ] **Step 4: Run and verify:** `./gradlew check && ./gradlew :build-logic:test`. Expected: PASS.
- [ ] **Step 5: Commit:** `git add platform runtime adapter-fake && git commit -m "feat(ui): confirm, speller and toast requests in the snapshot; one answer per dialog"`

---

### Task 4: List cells on the page: checkbox, radio, progress, value column, icons, disabled rows — SDK repo

**Files:**
- Create: `adapter-hqc/src/main/java/mibsdk/adapter/hqc/ui/{PageRow,PageLayout,ItemIcons}.java`
- Modify: `adapter-hqc/src/main/java/mibsdk/adapter/hqc/ui/{PageScreens,PageWidgets,UiApplier,HqcUiIds,SdkCarModelBank}.java`
- Test: `adapter-hqc/src/test/java/mibsdk/adapter/hqc/ui/{PageScreensMappingTest,PageScreensNavigationTest,PageWidgetsTest,UiApplierTest,StockThreadCodeTest}.java`, new `PageLayoutTest.java`

**Interfaces:**
- Consumes: `UiEntry` kinds and fields, `UiPageText.label/valueText`, `UiHub.choiceKey` targets (Task 2); `DrawerSlots.id(UiIcon, IconIds)` and `BuiltinIcons.get(name, size, reflex)` (existing).
- Produces:
  - `PageScreens` row kinds (the list's record set, column 2): `ROW_ACTION 0, ROW_TOGGLE 1, ROW_LABEL 2, ROW_RADIO 3, ROW_PROGRESS 4, ROW_VALUE 5, ROW_ICON 6, ROW_ICON_VALUE 7`. The mapping, `static int rowKind(UiEntry e, int iconIndex, boolean iconValue)`:
    - INFO → LABEL; TOGGLE → TOGGLE; RADIO → RADIO; PROGRESS → PROGRESS;
    - ITEM, LINK, CHOICE → VALUE when `valueText` is non-null, else ACTION;
    - ITEM with `iconIndex ≥ 0` → ICON, or, when it also has a value, ICON_VALUE if `iconValue` is true and VALUE (the icon dropped) if it is false.

    The callers pass `HqcUiIds.PAGE_ICON_VALUE_ROWS` (`true`). That constant is the F12 fallback switch: if bench N4 fails, the follow-up sets it to `false`.
  - `PageRow(String label, int value, int kind, boolean enabled, String valueText)`, final fields, `equals`. `static PageRow of(UiEntry e, int iconIndex)`: `value` is 1/0 for toggle and radio, the percent for progress, the icon index for icon kinds, else 0.
  - `ItemIcons` (immutable, F28): a map `UiIcon → image id`, built **on `mibsdk-ui`** by `UiApplier.assignItemIcons(UiSnapshot)` (next to `assignSlots`) through `DrawerSlots.id`; a built-in uses its 20-px id. Published through a volatile field read by `PageScreens`. Stock-thread code never assigns ids.
  - `PageLayout`: `int[] iconIds()` (the distinct ids of the page's item icons in order, ≤ 24), `int iconIndex(UiIcon)` (−1 if absent), `equals`/`hashCode`. `static PageLayout of(UiPage p, ItemIcons ids)` only reads. Task 5 adds the typed rows.
  - `Screens.build(String pageKey, String title, PageScreens owner, PageLayout layout)`; `Screens.writeRows(PageRow[] rows)`.
  - `PageScreens`:
    - `rowEntries` holds the **list entries only**: DROPDOWN/SLIDER are excluded, so list row `i` is `rowEntries[i]` (F26);
    - a refresh whose `PageLayout` differs from the shown one re-shows (rebuilds) the page screen when `isCurrent && changeAllowed()`. Otherwise the rebuild stays pending until the next allowed refresh, and meanwhile a row whose icon is not in the shown layout is written as its no-icon kind (B4);
    - `selected(row)` drops a row whose entry is disabled;
    - CHOICE → `requestNav(new Nav(e.targetPageKey()))`;
    - RADIO → `table.input(ENTRY_SELECTED, shownKey, e.key())` then `requestNav(new Nav(null))` (back);
    - `boolean navPending()`.
  - `UiApplier` (F2): `postJobIfNeeded` also posts when `pages.navPending()`, and `runHmiJob` always runs pending navigation (`pages.runPendingNav()`), even when no page changed. `samePage` compares every new `UiEntry` field (kind, number, options, range, centred, enabled, icon).
  - `SdkCarModelBank.createPageList`: `setMaxColumns(5)` on the page `ListModel` (F14).
  - `PageWidgets` (recipes copied from spec §7.2 and `SpikePage.Items`; every literal goes into `HqcUiIds`, as `PAGE_COL_*`, `CHECKBOX_MAPPING`, `PROGRESS_BITMAPS`, `PROGRESS_COLOURS`, …):
    - `build`: `setRecordSetColumn(2)`, `setEnabledColumn(3)` (beside the existing `setIdColumn(2)`).
    - Row shell for every kind (C2): `MenuItemController` type 16, `MenuItemRendererHigh`, one `GridLayout` via `setLayoutChoices`, row `AxisConstraints(1)` with `alignment {5}`, `GridLayoutHints` `hidemode 0` and `alignmentVert 5`. The label is a `LabelController` + `LabelRendererHigh`, `setModelColumn(0)`, `setAlignment(1, 5)`.
    - Checkbox (ROW_TOGGLE): `CheckboxController` + `CheckboxRendererHigh`, `setBounds(0,0,100,100)`, mapping `{{0},{1}}`, `setModelColumn(1)`; columns `gaps {0,22,0}`, `grow/shrink {1,0}`.
    - Radio: `RadioButtonController` + `RadioButtonRendererHigh`, `setBounds(0,0,100,100)`, `setModelColumn(1)`; checkbox columns plus `max {-1, 33}`.
    - Progress: `ProgressBarController` + `ProgressBarRendererHigh`, `setBitmaps {40..45}`, `setColorIndices {1,2,4,0}`, min 0, max 100, `setPreferredHeight(15)`, `setModelColumn(1)`; column `min {-1, 300}`.
    - Value: a second `LabelController` on column 4, `setAlignment(3, 5)`; `grow {1,0}`.
    - Icon (C7): `IconController` + `IconRendererHigh`, `setBitmaps(layout.iconIds())` indexed by column 1, `setPreferredHeight(1)`, `setAlignment(1, 5)`; the icon column first, `gaps {0,15,0}`, `grow {0,1}`, `max {30,-1}`.
    - **Icon + value (ROW_ICON_VALUE), [bench N4] (F12):** `MenuItemColumnsConstraints(3)`: icon, label, value; `gaps {0,15,22,0}`, `grow {0,1,0}`, `max {30,-1,-1}` (the Language list's 3-column precedent, `SettingsScreenBag1$1.java:18-90`). The cells are the icon cell (column 1), the label (column 0) and the value label (column 4, `setAlignment(3, 5)`).
    - `writeRows` writes `TextListCell` label (0), `IntegerListCell` value (1), kind (2), enabled 1/0 (3) and `TextListCell` value text (4, "" for none).
  - The wrong comments at `PageScreens.java:46` and `HqcUiIds.PAGE_LIST_MODEL` (the id column picks the row) are fixed: the record-set column picks it.

- [ ] **Step 1: Write the failing tests:**
  - `PageScreensMappingTest`:
    - `kindFollowsValueAndIcon`: an item with no value is ACTION; with a value, VALUE; with an icon, ICON; with both, ICON_VALUE; LINK "on" is VALUE with valueText "on >"; CHOICE is VALUE with valueText "English";
    - `iconValueFallsBackToValueWhenDisabled`: `rowKind(item with icon and value, 0, false)` is VALUE;
    - `toggleRowIsLabelOnlyWithValue01`: label "Wi-Fi", value 1, kind 1;
    - `disabledRowWritesEnabled0`.
  - `PageScreensNavigationTest`:
    - `disabledRowPressIsDropped`: no input on the table;
    - `choiceOpensSubPageAndPickReturns` (F16): select the CHOICE row → nav to `p/main#lang`, and the page events are CLOSED `p/main` + SHOWN `p/main#lang`; select row 1 → ENTRY_SELECTED(`p/main#lang`, "1"), then CLOSED `p/main#lang` + SHOWN `p/main`. The hub's `#` mapping (Task 2) keeps `p/main` open, so no PAGE_CLOSED reaches the plugin;
    - `choiceBackReturnsWithoutAction`;
    - `iconSetChangeRebuildsScreen`: a refresh adding a new icon id builds a new screen, and a refresh with only a new index does not;
    - `iconChangeWhileBlockedWritesNoIconKind`: the new-icon row is written as VALUE/ACTION until the deferred rebuild.
  - `UiApplierTest`:
    - `samePageSeesEveryInputField`: a change in only `enabled`, `number`, an option, the range or the icon is not `samePage`;
    - `pendingNavRunsWithoutPageChange` (F2): with the combined job pending and no page change, a nav requested by a choice pick runs on the next job;
    - `itemIconIdsAssignedOnUiThread`: `assignItemIcons` gives the same id for the same icon across snapshots.
  - `PageLayoutTest.distinctIdsInOrderAndIndex`.
  - `PageWidgetsTest` (with `HostWidgets.init()`):
    - `listUsesRecordSetAndEnabledColumns`;
    - `pageListModelHas5Columns` (the bank's model);
    - `toggleRowHasCheckboxCellOnColumn1`;
    - `eachKindBuildsItsCells` (radio, progress, value, icon, icon_value: the controller classes, model columns and the ICON_VALUE gaps/grow/max).
  - `StockThreadCodeTest`: `PageRow`, `PageLayout` and `ItemIcons` join `STOCK_THREAD_FILES`.
- [ ] **Step 2: Run and verify they fail:** `./gradlew :adapter-hqc:test`. Expected: FAIL.
- [ ] **Step 3: Implement** as specified.
- [ ] **Step 4: Run and verify:** `./gradlew check && ./gradlew :build-logic:test` (NoStockConstantsTest, LinkCheck). Expected: PASS.
- [ ] **Step 5: Commit:** `git add adapter-hqc && git commit -m "feat(hqc): native list cells (checkbox, radio, progress, value, icon), disabled rows, choice sub-page"`

---

### Task 5: Typed rows (dropdown, slider row) and the slider edit screen 609501 — SDK repo

**Files:**
- Create: `adapter-hqc/src/main/java/mibsdk/adapter/hqc/ui/{SliderSession,SliderScreen,InputTextIds}.java`
- Modify: `HqcUiIds.java`, `SdkCarModelBank.java`, `SdkCarScreenFactory.java` (`getText`, `getAltText`), `PageLayout.java`, `PageScreens.java`, `PageWidgets.java`, `UiApplier.java`
- Test: new `SliderSessionTest.java`, `InputTextIdsTest.java`; `PageScreensNavigationTest`, `PageWidgetsTest`, `SdkCarScreenFactoryTest`, `UiApplierTest`, `StockThreadCodeTest`

**Interfaces:**
- Consumes: DROPDOWN/SLIDER entries and VALUE_REQUESTED (Task 2); `UiDialog` (Task 3); `PageLayout`, `Screens`, `navPending` (Task 4).
- Produces:
  - **Step 0 (host check, recorded in the `HqcUiIds` Javadoc as the bank note is):** `lsd-src` has no model id 602764..602779, no Car text id 609040..609082 and no menu-item internal id 609960..609963, decoded or byte-swapped, in the namespaces that matter. CarModelBank stays at 2738 models (`CarModelBank.java:4734`).
  - `HqcUiIds`:
    - `TYPED_CHOICE_MODEL_0 = MODEL_BASE + 26` (4 ChoiceModels, 602764..602767);
    - `TYPED_RANGE_MODEL_0 = MODEL_BASE + 30` (4 RangeModels, 602768..602771);
    - `CONFIRM_TEXT_RESERVED = +34` (no model, F9); Task 6's `CONFIRM_LIST_MODEL = +35` and `SPELLER_MODEL = +36`; Task 7's `TOAST_VISIBLE_MODEL = +37`, `TOAST_LABEL_MODEL = +38`, `TOAST_FILE_ICON_MODEL = +39`; +40/+41 reserved with no model (F22);
    - `MODEL_COUNT = 42` (602738..602779);
    - `SLIDER_SCREEN_ID = 609501`;
    - `TYPED_INTERNAL_ID_0 = 609960` (slots 0..3; clear of the rows' 609900..609908) (F20);
    - text ids, `TEXT_INPUT_BASE = TEXT_BASE + 40`:
      - typed slot `s`: label `609040 + 9s` (it doubles as the slider screen's title, F10) and options `+1..+8`;
      - `TEXT_SLIDER_MIN = 609076`, `TEXT_SLIDER_MAX = 609077`;
      - `TEXT_CONFIRM_TITLE = 609078`, `TEXT_SPELLER_TITLE = 609079`, `TEXT_CONFIRM_TEXT = 609080`, `TEXT_SPELLER_INFO = 609081`, `TEXT_SPELLER_HINT = 609082`;
    - every §7.2 combo/rotary/slider-screen literal:
      - combo: bitmaps `{127 ×42, 129, 332}`, fonts 5, insets 6/7, type 64, widgetID 1;
      - rotary row: type 128;
      - slider screen: glassplate `(59,77,946,324)` with its coordinate set `{1,59,77,946,324,59,77,946,324}`, rotary `(397,122,100,100)`, min/max labels `(226,323)`/`(621,323)` 202×30 with fonts 2, colours `{1,0,4,0}` and the min label's `setAlignment(3,4)` (the max label's as in `SpikeScreens`), mirror container `(0,0,1024,480)`, `setSmallStageType(3)`;
      - key 17 (`KEY_ENTER`, existing).
  - **Bank routing (F4).** `SdkCarModelBank.createModel` sends only +12..+25 (`STATUS_POPUP_VISIBLE_MODEL .. STATUS_FILE_ICON_MODEL_1 + STATUS_CELLS - 1`) to `createPopupModel`, and `POPUP_MODELS` is that span, not `MODEL_COUNT - …`. It has explicit branches: +26..+29 `ChoiceModel` (the typed `ChoiceListener`), +30..+33 `RangeModel` (the slider `RangeListener`), +35 `ListModel` (5 columns), +36 `SpellerModel`, +37 `ChoiceModel`, +38 `LabelModel`, +39 `ResourceLocatorModel`. +10, +34, +40 and +41 get no model.
  - `PageLayout` gains `TypedRow[] typed()` (`kind`, `entryKey`, `label`, `options`, `min`, `step`, `steps = (max-min)/step`, `centred`), in spec order, slot = position. A change in it rebuilds the screen (Task 4's rule).
  - **Shown layout (F6).** `PageScreens` publishes a volatile `Shown { String pageKey; PageLayout layout; int generation }`, new at each build. `UiApplier` writes the pooled ChoiceModel/RangeModels on `mibsdk-ui` only for the entries of that layout's typed rows, and writes all of them again when `generation` changes.
  - `InputTextIds.textFor(int id, Shown shown, SliderSession slider, UiDialog dialog) -> String` (null if not ours). `SdkCarScreenFactory.getText` **and** `getAltText` both ask it first through one helper `sdkText(int id)` (F19).
  - `SliderSession` (stock-free, HMIEvent only):
    - `SliderSession(int min, int step, int steps, int value)`;
    - `int index()`;
    - `int onTurn(int n)`: accumulates from the last *requested* index, clamped to `0..steps`, and returns the requested value `min + index·step`;
    - `void onModel(int value)`: a refresh with a new plugin value resets the index to it.

    Its Javadoc records F18: the HMI limits `n` against the RangeModel's current (plugin-confirmed) value, so a fast reversal at an end can lose a step until the update lands.
  - The RangeModel works in index space: `setLimits(0, steps, 1)`, `setMedialPosition(centred ? steps/2 : 0)`, `setValue((value-min)/step)`. This deviates from the spike's value space and is checked at N7 with a non-centred step-2 slider (F13).
  - Dropdown: §7.2 combo row with `setInternalID(TYPED_INTERNAL_ID_0 + slot)`. The pooled `ChoiceModel` value = the selected index. Its `ChoiceListener.itemSelected(id, v)` → `UiTable.input(VALUE_REQUESTED, shown.pageKey, entryKey, v)`, and `itemFocused` is ignored.
  - Slider row: §7.2 rotary row with `setInternalID(TYPED_INTERNAL_ID_0 + slot)` and its own key handler, set before connect: on key 17 it calls `consume(false)` and `PageScreens.sliderPressed(slot)`, which opens the slider screen if screen changes are allowed. The screen's RangeListener `increment/decrement(n)` → `SliderSession.onTurn(±n)` → VALUE_REQUESTED. BACK (key 15, consumed) → `PageScreens.subBack()`.
  - `Screens` (F25): `Object buildSlider(int slot, PageLayout layout, PageScreens owner)`; Task 6 adds `buildConfirm(UiDialog d, PageScreens owner)` and `buildSpeller(UiDialog d, PageScreens owner)`. All are shown with the existing `show(Object)`.
  - `PageScreens` sub-screen state:
    - `openSub(int screenId, Object screen)`: while a sub-screen (609501..609503) is connected, the page stays open (no `PAGE_CLOSED`) and `shownKey` keeps routing input;
    - `subBack()` re-shows the page screen with the same rows and no stack pop; it runs through `requestNav`, so F2's always-run nav covers it;
    - a stock screen connecting over a sub-screen is treated as leaving the page (as today).

- [ ] **Step 1: Write the failing tests:**
  - `SliderSessionTest`:
    - `accumulatesFromLastRequest`: steps 12, min −6, value 0 (index 6): `onTurn(2)` → 2, then `onTurn(3)` → 5 without an `onModel`;
    - `clampsAtBothEnds`: `onTurn(20)` → 6, `onTurn(-30)` → −6;
    - `modelResetsIndex`;
    - `stepUnitsMapToValues`: min 0, step 2, steps 5, `onTurn(1)` from 4 → 6.
  - `PageScreensNavigationTest`:
    - `subScreenKeepsPageOpen`: `openSub(609501)`, then connect, then nothing is sent; `PAGE_CLOSED` is never sent;
    - `backFromSubScreenReshowsPageWithoutPop`: stack depth unchanged, the page screen shown, rows written;
    - `typedRowChangeRebuildsScreen`: adding a slider rebuilds; a value change only writes the model;
    - `typedEntryBetweenListRowsKeepsRowIndexes` (F26): entries [toggle, dropdown, item]: list row 1 selects the item;
    - `sliderPressOnStaleSlotDoesNothing`: `sliderPressed` on a slot with no live SLIDER entry opens nothing (sliders cannot be disabled, Task 1);
    - `sliderPressWhileBlockedDoesNothing` (`currentBlocked` true).
  - `UiApplierTest`:
    - `typedModelsFollowShownLayout`: values are written only for the shown layout's slots, and all are rewritten after a rebuild (new generation).
  - `InputTextIdsTest`: the labels, options and min/max texts ("-6", "+6" for a centred slider, "0", "10" otherwise), the confirm and speller ids, and null for 609039 and 609083.
  - `PageWidgetsTest.typedRowsFollowTheList`: the menu holds the list, then the combo row with `setTextIds` = its option ids and internal id 609960, then the rotary row with its own key handler and internal id 609961; the screen's `setViews` ids are sorted.
  - `SdkCarScreenFactoryTest`:
    - `bankModelKindsByIndex`: +12..+25 are popup models (14), +26 is a ChoiceModel, +30 a RangeModel, +35 a ListModel with 5 columns, +36 a SpellerModel, +37 ChoiceModel, +38 LabelModel, +39 ResourceLocatorModel, and +10/+34/+40/+41 have none;
    - `altTextServesInputTexts`.
  - `StockThreadCodeTest`: `SliderSession`, `SliderScreen` and `InputTextIds` join the files; `getAltText` joins the override list.
- [ ] **Step 2: Run and verify they fail:** `./gradlew :adapter-hqc:test`. Expected: FAIL.
- [ ] **Step 3: Implement** as specified. `SliderScreen` follows `SpikeScreens.SliderRotary`/`OwnScreen`, and is built on the HMI job like the page screen.
- [ ] **Step 4: Run and verify:** `./gradlew check && ./gradlew :build-logic:test`. Expected: PASS.
- [ ] **Step 5: Commit:** `git add adapter-hqc && git commit -m "feat(hqc): dropdown and slider rows, slider edit screen 609501 with knob accumulation"`

---

### Task 6: Confirm screen 609502 and speller screen 609503 — SDK repo

**Files:**
- Create: `adapter-hqc/src/main/java/mibsdk/adapter/hqc/ui/{ConfirmScreen,SpellerScreen,SpellerSession}.java`
- Modify: `HqcUiIds.java`, `PageScreens.java`, `UiApplier.java`, `adapter-hqc/build.gradle.kts` (`hostStockJar` packages gain `de/audi/tghu/connectivity/`, F11)
- Test: new `SpellerSessionTest.java`; `PageScreensNavigationTest`, `PageWidgetsTest`, `UiApplierTest`, `StockThreadCodeTest`

**Interfaces:**
- Consumes: `UiSnapshot.dialog()`, `UiInput.DIALOG_DONE` (Tasks 2–3); `openSub/subBack`, `Screens`, `InputTextIds`, the bank's +35/+36 (Task 5).
- Produces:
  - `HqcUiIds`: `CONFIRM_SCREEN_ID = 609502`, `SPELLER_SCREEN_ID = 609503`, `CONFIRM_AUTOWRAP = 0`, `SPELLER_OPENED = 4711`, `SPELLER_CLOSED = 4712`. Plus the §7.2 literals:
    - confirm: `setSmallStageType(1)`; colours `{1,0,4,0}`; `setTextOrientationHints {6}`;
    - speller (C15): touch bitmaps `{483,484,857}`; the 49 speller bitmaps of `cMOPTBLUETOOTHNAMEEDIT`; Connectivity `FontFactory.getFonts(4, …)` for the field and `(5, …)` for the speller; colours `{2,5,4,3}`/`{2,0,4,0}`; cursor offsets 1/−1; insets 17/5; height 60; `setPrefilledInputField(true)`; `setTouchControllerScreenLevel(2)`; `setWidgetID(1)`; stubs 5602 (16) and 5606 (8); views `{138, 5602, 5606, SPELLER_MODEL}`.
  - **Dialog trigger (F3).** `UiApplier.needsRender` also compares `renderedDialogSeq` with `snapshot.dialog()` (a seq or a null), so a new or withdrawn dialog alone posts the job. `renderPages` calls `pages.dialogChanged(snapshot.dialog())`.
  - `PageScreens.dialogChanged(UiDialog d)` (on the job):
    - it shows a new seq's screen only when `shownKey` is `d.ownerPageKey()` or its `#` child, and `changeAllowed()`;
    - otherwise it sends `DIALOG_DONE` (pageKey = `d.ownerPageKey()`, entryKey = seq, value 0, text null): "not shown";
    - a dialog withdrawn (null, or a new seq) while its screen is up → `subBack()` with no answer.
  - `ConfirmScreen`, the §7.2 recipe (F9 follows the proven text-id path), shown as a non-popup `ScreenData`:
    - an `InstructionTextContoller` (0,0,100,100, colours `{1,0,4,0}`) with `setOptions(menu)` and `addHintText(label)`;
    - the hint is a `LabelController` + `MultiLineLabelRendererHigh` with `setTextIds(TEXT_CONFIRM_TEXT)` (served by `InputTextIds` from `UiDialog.text()`, lines joined by `'\n'`), `setTextOrientationHints {6}` and `setAutoWrap(HqcUiIds.CONFIRM_AUTOWRAP)`;
    - the title is `TEXT_CONFIRM_TITLE`;
    - a `ListController` on `CONFIRM_LIST_MODEL` (5 columns, written in the job as the Global Constraint allows) with the page item factory: rows yes and no, ACTION kind, enabled 1;
    - row 0 → DIALOG_DONE value 1, row 1 → 0, BACK → 0; each then `subBack()`.
  - `SpellerSession` (stock-free, HMIEvent only):
    - `SpellerSession(long seq, String initial)`;
    - `void onTextChanged(String t)`; `void onCommand(int c)` (4711 band open, 4712 band closed; neither finishes);
    - `String doneText(String modelText)`: `modelText` if non-null, else the last `textChanged`, else `initial`;
    - `boolean takeDone()`: true only the first time.
  - `SpellerScreen`: the §7.2 recipe with `setOpenSpellerOnOtherScreen(false)`, `setInfoTextIDs(TEXT_SPELLER_INFO)`, a hint label on `TEXT_SPELLER_HINT`, the title `TEXT_SPELLER_TITLE`, and `SpellerModel.setMaxLength(maxLen)`/`setText(initial)` written in the job before connect.
    - BACK (key 15) that reaches the screen → DIALOG_DONE(pageKey = owner, seq, 0, `doneText(model.getText())`) once, then `subBack()`.
    - If the stock speller consumes a BACK while its band is open (it only closes the band), the screen sees no key and the band's 4712 does not finish; the next BACK is done (F21). No extra code: the bench records which happens.
    - A disconnect without BACK (a stock screen) sends the same DIALOG_DONE once, before the page-closed path.
  - A stock screen over a dialog: DIALOG_DONE (dismissed: confirm 0; speller with its text), then the existing leave-page handling.

- [ ] **Step 1: Write the failing tests:**
  - `SpellerSessionTest`:
    - `backUsesModelText`;
    - `backWithoutModelTextUsesLastChanged`;
    - `noTypingGivesInitial`;
    - `bandCommandsNeverFinish`;
    - `doneOnlyOnce`: BACK then disconnect gives one done.
  - `UiApplierTest.newDialogAlonePostsJob` and `withdrawnDialogAlonePostsJob` (F3).
  - `PageScreensNavigationTest`:
    - `dialogShownOnlyOverOwnersPage`: another page shown → DIALOG_DONE value 0 right away, with pageKey = the owner key;
    - `dialogShownOverChoiceSubPage`: `shownKey` `p/main#lang`, owner `p/main` → shown;
    - `confirmRowsAndBackAnswer` (row 0 → 1, row 1 → 0, BACK → 0, each followed by the page re-shown, no pop);
    - `withdrawnDialogReturnsWithoutAnswer`;
    - `stockScreenOverDialogAnswersThenCloses`: DIALOG_DONE precedes `PAGE_CLOSED`;
    - `spellerDisconnectDeliversText`.
  - `PageWidgetsTest` (with `HostWidgets.init()`):
    - `confirmLabelAutoWrapIsZero`: reads the renderer's protected `autoWrap` field by reflection after the build (it has no getter, `MultiLineLabelRendererHigh.java:26,419`), and checks the hint's text id is 609080;
    - `spellerScreenBuildsOnHost` (it needs the Connectivity classes in the host jar).
  - `StockThreadCodeTest`: the three new files join.
- [ ] **Step 2: Run and verify they fail:** `./gradlew :adapter-hqc:test`. Expected: FAIL.
- [ ] **Step 3: Implement** as specified, from `SpikeScreens`/`SpikeInputs`.
- [ ] **Step 4: Run and verify:** `./gradlew check && ./gradlew :build-logic:test`. Expected: PASS.
- [ ] **Step 5: Commit:** `git add adapter-hqc && git commit -m "feat(hqc): confirm (609502) and experimental speller (609503) screens; BACK is done"`

---

### Task 7: Toast popup 609601 in its own slot 6, and advertise `UI_INPUTS` — SDK repo

**Files:**
- Create: `adapter-hqc/src/main/java/mibsdk/adapter/hqc/ui/{SdkToastPopup,StatusPopupController}.java`
- Modify: `HqcUiIds.java`, `SdkCarScreenFactory.java` (stubs, `getPartialPopup`), `SdkStatusPopup.java` (builds `StatusPopupController`), `HmiWidgetState.java`, `HmiWidgets.java` (the toast part, F5), `UiApplier.java`, `adapter-hqc/src/main/java/mibsdk/adapter/hqc/HqcPlatform.java` (adds `Features.UI_INPUTS` to `FEATURES_UI` and `FEATURES_HOOKED_UI`), `adapter-hqc/src/main/java/mibsdk/adapter/hqc/SdkGlobals.java` (`toastPopup` state)
- Test: `SdkCarScreenFactoryTest`, `UiApplierTest`, `HmiWidgetsTest`, new `SdkToastPopupTest.java`, `adapter-hqc/src/test/java/mibsdk/adapter/hqc/HqcPlatformTest.java`, `StockThreadCodeTest`

**Interfaces:**
- Consumes: `UiSnapshot.toast()` (Task 3); `StatusLayout` char and icon constants; the toast models +37..+39 (Task 5).
- Produces:
  - `HqcUiIds`: `POPUP_SDK_TOAST = 609601`, `POPUP_TOAST_SLOT = 6` (the Global Constraint's evidence goes in its Javadoc), zpm 12, priority 250, type 7; the rect `POPUP_TOAST_X = POPUP_SDK_X - POPUP_SDK_W` (259), y 0, w 255, h 24, ending where the status popup starts; `TOAST_MS = 3000` (stock-thread files may not read `Ui`).
  - `SdkToastPopup`: text right-aligned, with the icon (≤ 20 px) left of the text (a file icon on `TOAST_FILE_ICON_MODEL`, or an id icon as a status cell has). The toast **never writes** `STATUS_POPUP_VISIBLE_MODEL` or any status model.
  - `getPartialPopupStubs` (F24): the toast stub depends only on `SdkGlobals.toastPopup != -1`, independent of `statusPopup`. A toast may show while the status line has fallen back to popup 65, because it has its own slot. The early return for `statusPopup == -1` skips only the status stub.
  - `getPartialPopup(609601)` builds `SdkToastPopup`. Any Throwable → `toastPopup := -1` (sticky; status untouched). Toasts are then dropped with one log line.
  - **Sticky safety fallback (F1).** `StatusPopupController extends PartialPopupController` (609600) overrides `hide(int)` (`AbstractPartialPopupController.java:412`): if the manager hides 609600 while its visibility model is 1 and a toast is up, it sets `SdkGlobals.toastPopup := -1` ("toast disabled: status popup hidden"), hides the toast, then calls `super`.
  - **Widgets (F5).** `HmiWidgetState` gains a toast part (text, id icon or file path, visible). `HmiWidgets.apply` sets the id icon's `setBitmaps` and repaints in the one combined job. The models (+37..+39) are written on `mibsdk-ui`.
  - `UiApplier`:
    - on a new toast seq, it writes the label and the icon, then visibility 1, and sets `toastHideAt = clock.uptimeMs() + Ui.TOAST_MS`;
    - its loop wait is bounded by `toastHideAt`, and at the deadline it writes visibility 0;
    - a newer toast restarts the timer;
    - UI down → hidden.
  - `HqcPlatform` advertises `UI_INPUTS` with the UI features.

- [ ] **Step 1: Write the failing tests:**
  - `SdkCarScreenFactoryTest`:
    - `toastStubUsesFreeSlot6LeftOfStatus`: stub id 609601, slot 6, zpm 12, rect (259, 0, 255, 24), placed after the status stub, and the status stub still in slot 7;
    - `toastStubWithoutStatusPopup`: with `statusPopup == -1`, the toast stub is still registered;
    - `toastFailureIsStickyAndLeavesStatus`: a forced build failure gives −1, no stub on the next call, and the status popup still registered;
    - `toastNeverTouchesStatusVisibility`: show and hide leave `STATUS_POPUP_VISIBLE_MODEL` unchanged;
    - `statusHiddenDuringToastDisablesToasts`: `hide` on the status controller with visibility 1 during a toast → `toastPopup == -1` and the toast hidden.
  - `UiApplierTest`:
    - `toastShowsThenHidesAfter3000ms` (FakeClock: visible at 2999, hidden at 3000);
    - `newerToastRestartsTimer`;
    - `toastWhilePopupFailedIsDroppedOnce`.
  - `HmiWidgetsTest.toastIdIconAppliedInJob`.
  - `SdkToastPopupTest.rightAlignedTextWithIcon`.
  - `HqcPlatformTest.uiFeaturesIncludeInputs`.
  - `StockThreadCodeTest`: `SdkToastPopup` and `StatusPopupController` join.
- [ ] **Step 2: Run and verify they fail:** `./gradlew :adapter-hqc:test`. Expected: FAIL.
- [ ] **Step 3: Implement** as specified, from `SpikeProbes` `toast` and `SpikePopup`.
- [ ] **Step 4: Run and verify:** `./gradlew check && ./gradlew :build-logic:test`. Expected: PASS.
- [ ] **Step 5: Commit:** `git add adapter-hqc && git commit -m "feat(hqc): toast popup 609601 in its own slot 6 beside the status popup; advertise ui.inputs"`

---

### Task 8: inputs-demo sample, SDK 0.3.0, docs — SDK repo (after Task 3; parallel with 4–7)

**Files:**
- Create: `plugins/inputs-demo/build.gradle.kts` (copy of hello-menu's), `plugins/inputs-demo/src/main/resources/plugin.json`, `src/main/java/sample/inputsdemo/InputsDemoPlugin.java`, `src/main/icons/plug-24.png`, `src/test/java/sample/inputsdemo/InputsDemoPluginTest.java`
- Modify: `settings.gradle.kts` (include `plugins:inputs-demo`), `tools/make_builtin_icons.py` (`plugin_inputs_demo()` and an `--inputs-demo` output, like `--plugin-bike-rack`), `installer/test_make_builtin_icons.py`, `gradle.properties` (`mibsdk.version=0.3.0`), `CHANGELOG.md` (0.3.0 entry)

**Interfaces:**
- Consumes: the api 5 surface (Task 1) and FakeUi drivers (Tasks 2–3).
- Produces:
  - `plugin.json`: `{"id":"inputs-demo","name":"Inputs Demo","version":"1.0.0","author":"complex256","api":5,"main":"sample.inputsdemo.InputsDemoPlugin","permissions":["ui.slot.statusbar"],"icons":["plug-24"]}`. It has no Car row: it is reached from MENU → Plugins.
  - One page `inputs`, "Inputs", in this order (item ids in brackets; 22 entries plus the conditional Save row, ≤ 24; 3 typed rows, ≤ 4):
    - toggle "Wi-Fi" [wifi]; toggles "Red" [red], "Green" [green], "Blue" [blue] (multi-choice); toggle "Locked" [locked], disabled;
    - choice "Language" [lang] {English, Deutsch, Français};
    - item "Version" [ver] value "1.0.0";
    - item "Plug" [plug] with icon `plug-24`; item "Plug value" [plugv] with icon and value "on";
    - progress "Progress" [prog] 60; item "Progress +20" [prog-next] (mod 120, clamped by the SDK);
    - item "Swap kind" [swap]: it flips [swapme] between `toggle("swapme","Swap me",false)` and `item("swapme","Swap me","item")`; plus [swapme] itself;
    - item "Confirm" [confirm]: a 110-char text with no `'\n'`; item "Confirm 2 lines" [confirm2]: "First line\nSecond line";
    - item "Toast" [toast]: `toast("Saved", plug-24)`; item "Toast in 5 s" [toast5]: the plugin's `Scheduler` fires it 5000 ms later;
    - item "Name" [name] value = the stored name: it opens `speller("Name", name, 16, …)`; after a done with changed text, item "Save name" [save] appears; Save stores it, removes the row and toasts "Name saved";
    - dropdown "Level" [level] {Off, Low, Mid, High}; slider "Bass" [bass] −6..6 step 1 centred; slider "Volume" [vol] 0..10 step 2, not centred (F13);
    - info "Last: <action>" (the last `UiAction.toString()`, cut by the SDK).

    Every action is also logged at INFO. State lives in storage keys `name`, `level`, `bass` and `vol`.
  - `plug-24.png`: a 24×24 white plug glyph from `plugin_inputs_demo()`. The build also stages its `.r` (h > 20), which is unused off the drawer.
  - The CHANGELOG 0.3.0 entry covers: native checkbox for every toggle, the api-5 list, `Ui.speller` experimental, inputs-demo 1.0.0, and "an api-5 plugin needs SDK 0.3.0; mibpkg refuses it over 0.2.0".

- [ ] **Step 1: Write the failing tests:**
  - `InputsDemoPluginTest` (FakeUi on a FakePlatform whose features include `UI_PAGES`, `UI_INPUTS`, `UI_ICONS` and `SLOT_STATUS_BAR`, F27):
    - `pageRendersEveryInput`: the full `render` list, exactly;
    - `withoutInputsRendersTextLines`: the same page on a FakePlatform without `UI_INPUTS` shows the INFO fallbacks ("Level: Off", "Bass: 0", "Volume: 0", "Progress: 60%", "Language: English");
    - `toggleAndMultiChoice`;
    - `lockedSendsNothing`;
    - `choicePicksDeutsch`;
    - `dropdownAndSlidersPersist`: `pick` 2 → `level` = 2 in storage; `turn` Bass 9 → 6; `turn` Volume 5 → 4 (snapped);
    - `progressNext`;
    - `swapKindAlternates`;
    - `confirmYesNo`;
    - `toastAndDelayedToast` (a test-local Clock at 5000, as hello-menu's tests do);
    - `spellerSaveFlow`: leave with "BC" → "Name: BC" plus a "Save name" row; select Save → stored, row gone, toast "Name saved";
    - `manifestIsApi5Version100`.
  - `test_make_builtin_icons.InputsDemoTest.test_committed_icon_is_tool_output`.
- [ ] **Step 2: Run and verify they fail:** `./gradlew :plugins:inputs-demo:test && python3 -m unittest installer.test_make_builtin_icons`. Expected: FAIL.
- [ ] **Step 3: Implement**; generate the icon with `python3 tools/make_builtin_icons.py --inputs-demo plugins/inputs-demo/src/main/icons`.
- [ ] **Step 4: Run and verify:** `./gradlew check && ./gradlew :build-logic:test && python3 -m unittest discover -s installer -p 'test_*.py' && ./gradlew :dist:mibpkgPackage :plugins:inputs-demo:mibPackage -Pmibsdk.mibpkg=<mibpkg 0.9.1>`. Expected: PASS, `mibsdk-0.3.0.mpk` (api 5, min_api 1) and `inputs-demo-1.0.0.mpk`.
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
- [ ] N0: the log has `ui system=sdk`, `ui car=sdk`, `ui status popup 609600 registered/built`, the toast stub 609601 registered in slot 6, 6 × ENABLED including inputs-demo, and `boot healthy: 6 plugin(s) enabled`.
- [ ] N1 checkbox: MENU → Plugins. The SDK's own plugin page shows its "Enabled" toggles as boxes (an old-api path, no `[on]`). Then open Inputs Demo: Wi-Fi off → on → off; Red and Blue checked together (`-checkbox.jpg`, `-multi.jpg`).
- [ ] N2: "Locked" is grey. Select it → no change; "Last:" is unchanged and the log has `dropped: disabled` (`-disabled.jpg`).
- [ ] N3 choice (assembled here for the first time): the "Language … English" row → the sub-page with radio marks, English marked (`-choice-sub.jpg`). Pick Deutsch → back on the page, the row reads Deutsch (`-choice-picked.jpg`). Open again and BACK → the page, no action (`-choice-back.jpg`).
- [ ] N4 [F12]: "Version … 1.0.0" right-aligned. The plug icon draws inside its column (24 px, no spill). **"Plug value" draws icon, label and value with no overlap** (`-value-icon.jpg`, zoom crop). If it does not, record the finding: the follow-up sets `PAGE_ICON_VALUE_ROWS = false` (value-only).
- [ ] N5: the progress bar at 60 → "Progress +20" → 80 (`-progress.jpg`).
- [ ] N6 dropdown: open, knob +2, select → "Mid" (`-dropdown-open.jpg`, `-dropdown-mid.jpg`).
- [ ] N7 sliders:
  - The Bass row shows its knob preview. Select → 609501 with the knob focused and the title "Bass"; rotate +3 → the pointer +3, and "Last" shows `value=3` (`-slider-plus3.jpg`).
  - Rotate +10 → it stops at +6; rotate −1 at once → +5 (F18: record any lost step) (`-slider-end.jpg`). BACK → the page, with the row at +5 (`-slider-back.jpg`).
  - Volume (0..10 step 2, F13): its row preview at 0. Open it: "0"/"10" labels and 6 ticks; rotate +2 → `value=4`, with the pointer 2 ticks on (`-slider-vol.jpg`). BACK.
- [ ] N8 confirm: "Confirm" → the long text. **Record whether `setAutoWrap(0)` wraps it** (`-confirm-wrap.jpg`). No → `value=0`; "Confirm 2 lines" shows two lines (`-confirm-2lines.jpg`); Yes → `value=1`; then BACK on a third one → `value=0`.
- [ ] N9 toast with the status line (F1):
  - first arm the bike rack as in `docs/bench-e2e-ui.md` Step 7, so the must-show `PDC R OFF` is on the status line;
  - "Toast" → **one frame shows the toast "Saved" with the plug icon, the status popup and `PDC R OFF` together** (`-toast-with-status.jpg`); gone after ~3 s while the status line stays (`-toast-gone.jpg`);
  - "Toast in 5 s", then `back` to the home screen → the toast shows over home beside the unchanged status line (`-toast-home.jpg`);
  - the log has no "toast disabled" line. Disarm the bike rack.
  - `SAFE` needs safe mode, which needs a restart, so it is not shown in this budget. It sits in the same popup as `PDC R OFF`, so the frame covers it.
- [ ] N10 speller (F21):
  - "Name" → the speller. Select opens the band; type 2 characters, with a frame check before each select (the band re-lays out);
  - **BACK with the band open:** record whether it leaves (done) or only closes the band (`-speller-back-open.jpg`). If it only closes the band, BACK again;
  - the page shows "Name … <text>" and "Save name" (`-speller-back.jpg`). Save → toast "Name saved";
  - open the speller again, close the band with ▼, then BACK → done at once, with the text kept (`-speller-back-closed.jpg`).
- [ ] N11: "Swap kind" twice: the row becomes a checkbox, then a value row, in place (`-swap.jpg`).
- [ ] N12: BACK out of the page and the Plugins menu to home. Save `/tmp/mibsdk.log` as `inputs-log-r1.txt`.

Findings that need a code change (for example, N4 or N8) are recorded with a follow-up and are **not** fixed and re-booted in this task. The confirm `'\n'` path (N8, second confirm) is the documented fallback for plugins.

**Emergency revert** (blank HMI): SSH still works. Run `mount -uw /mnt/app`, then copy mibpkg's stock backup over `/mnt/app/eso/hmi/lsd/bundles_dsitracer_enabled.properties` and `chmod 777` it. Then `sync; mount -ur /mnt/app; slay -s11 j9`, run `mibpkg sdk status`, and report BLOCKED with the log.

- [ ] End state, checked and written down:
  - `mibpkg sdk status` shows `HMICar=…SdkCarActivator`, `HMISystem=…SdkSystemActivator` (the takeover ACTIVE), sdk 0.3.0, api 1..5, running yes, reboot needed no, ledger 0, and all 6 plugins ENABLED;
  - `/mnt/app` read-only, `dev/` empty;
  - the unit on its home screen.
- [ ] Write `docs/bench-e2e-native-inputs.md`: H1–H2, O1, N0–N12, the findings table, frames, restart times and the end state.
- [ ] Commit: `git add docs/bench-e2e-native-inputs.md docs/bench-e2e && git commit -m "docs: bench check for native inputs (api 5, mibsdk 0.3.0)"`

---

## Decisions in this plan

[decided] comes from the owner, the controller or the spec; [my call] is a planning choice open to review.

- [decided, owner] Api 5 covers: the native checkbox (`toggle` unchanged, drawn as a box), multi-choice, choice, the value column, item icons ≤ 24 px, dropdown, slider (row + edit screen), progress, `Ui.confirm`, `Ui.toast`, and `Ui.speller` (EXPERIMENTAL, BACK = done).
- [decided, owner] Additive: `API_VERSION = 5`, MIN 1, `Features.UI_INPUTS`, inert or text fallbacks, and a build-time api-5 check like api 4's.
- [decided, owner] One bench task, a budget of 1 (2 at most), the host `--root` dry run first, the hang rule, the stated end state, and SDK 0.3.0.
- [decided, spec §7] Every widget uses the §7.2 recipe. The SDK filters disabled presses. Icons are not scaled. The toast gets its own popup id. The confirm uses `setAutoWrap(0)`, verified at N8.
- [decided, controller] `PageSpec.enabled(itemId, boolean)` is in api 5 (list rows only): without it the disabled-row filter is unreachable.
- [decided, controller] Every pre-flight resolution F1–F28 is applied.
- [decided, controller, F1] The toast uses slot 6, which no stock stub uses and which has the same depth as the status popup's slot 7. It never shares slot 7. N9 shows the toast, the status popup and `PDC R OFF` together. A hide of the status popup during a toast disables toasts for the boot.
- [decided, controller, F12] The icon+value row stays, with explicit 3-column constraints, [bench N4]. If N4 fails, it falls back to value-only through `PAGE_ICON_VALUE_ROWS`.
- [decided, controller, F15] SDK-owned screen models may be written in the combined HMI job. Plugin-facing app models stay on `mibsdk-ui` only.
- [decided, controller, F7] Inert confirm/speller calls still answer exactly once (0 / null), so a plugin awaiting the answer never hangs.
- [decided, controller] Task 8 runs beside Tasks 4–7, because their files do not overlap.
- [my call] A new **inputs-demo** sample, not hello-menu: 22+ rows would crowd hello-menu's icon demo past `MAX_ENTRIES`, and hello-menu stays the api-4 sample. inputs-demo has no Car row (fewer permissions), and is opened from MENU → Plugins.
- [my call] The choice sub-page is a runtime-derived page (`<pageKey>#<itemId>`, `#` cannot occur in ids) on the existing page screen and stack, not a new screen id. Navigation, BACK and FakeUi reuse the LINK path. A pick sends the index and returns, and the plugin's update redraws the row.
- [my call] Dropdown and slider rows are drawn after the list rows (they are menu items, not list cells). At most 4 per page come from a fixed pool, and any change in that set rebuilds the screen. A slider shows its position, not its number (the stock knob draws no number).
- [my call] The slider RangeModel works in step-index space (`0..steps`), checked at N7 with a step-2 slider (F13). Knob turns accumulate from the last requested value (`SliderSession`), and the runtime clamps, snaps and coalesces. A lag at the ends is accepted and documented (F18).
- [my call] Confirm and speller open only over the requesting plugin's own open page (or its choice sub-page); otherwise they answer at once (0 / null). They are never shown over stock screens.
- [my call] The confirm body is a factory text id (`TEXT_CONFIRM_TEXT`, the proven spike path), not a LabelModel (F9). Model +34 stays reserved.
- [my call] The toast needs `ui.slot.statusbar` (no new permission, no catalog change). It is 20 characters, with an icon ≤ 20 px, for 3 s, and the newest replaces the last. It may show while the status line is on its popup-65 fallback (F24).
- [my call] FakeUi keeps the `[on] `/`[off] ` text format (existing plugin tests read it). Only the HMI cells drop it (`UiPageText.label`).
- [my call] No mibpkg change: 0.9.1 already refuses a plugin api outside the installed SDK's range, and an old runtime marks an api-5 manifest `BLOCKED_INVALID` without a failure count (H1, O1).
- [my call] `UI_INPUTS` is advertised only in Task 7, so no intermediate build claims widgets it lacks.
- [my call] Bench findings are follow-ups. There is no second restart for a fix.

**Open points for the owner:** none.
