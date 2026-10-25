# Main-menu Plugins entry, row icons and status-line icons Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The SDK's Plugins entry moves to the top-level MENU ring (wrench icon, via an `HMISystem=` takeover that mibpkg manages like `HMICar=`); Car-drawer plugin rows and status items can carry icons; the status line moves to an SDK-owned popup 609600 in slot 7.

**Architecture:**
- **Main menu.** `SdkSystemActivator` (on `HMISystem=`) is a copy of the stock System activator whose factory, `SdkSystemScreenFactory`, appends one row (widgetID 11, atlas mapping `[11] := {16, 9}`, the wrench) to screen 46 after Settings. SELECT drops the existing Plugins-row input into `UiTable`; the page opens through the existing `PageScreens` path. Any Throwable, `RUN_MODE=swdl` or the UI being off means exactly the stock factory. A shared state `SdkGlobals.mainMenu` tells the Car side whether the Car-drawer Plugins row is still needed.
- **Icons.** Plugins get an opaque `Icon` (a named SDK icon, or a PNG from their jar, validated by the runtime and written to RAM under a content key). The snapshot carries icons on rows and status parts. The adapter maps each content key to a fresh Car-module image id (session lifetime) served by `SdkCarScreenFactory.getImagePath`, and drawer rows get `setBitmaps({id, id})`.
- **Status popup.** Popup 609600, registered through the Car factory's stubs (spike S1), built with a backdrop, one indicator label and 6 item cells (id icon, file icon, label). Text and file icons are model writes from `mibsdk-ui`; cell bounds and id bitmaps are applied by the applier's one coalesced HMIEvent job. If the popup cannot be registered or built, the text goes to popup 65 as today.
- **Stock-thread code stays minimal.** It reads volatile immutable state, builds or adjusts stock widgets, and drops input into the ring. It never calls plugin code.

**Tech Stack:** Unchanged from Plan 2 (Gradle 8.13 + `build-logic`, JDK 17/Jabel → Retrolambda → Retrotranslator, class-file v48, JUnit 5.10.2, Python 3 `unittest`). mibpkg: Rust (stable), `cargo test`.

**Spec:** `docs/research/2026-10-24-main-menu-and-icons.md` (§1–§4, §9 Boot A, §10 Boot B are the authority), plus the owner decisions recorded in "Decisions in this plan" below.
- Spike code to copy from: `spikes/hmi-ui/src/main/java/mibsdk/spike/ui/` (`SpikeSystemActivator`, `SpikeSystemFactory`, `SpikeWizardKeyHandler`, `SpikeFactory.getImagePath`/`getPartialPopupStubs`/`getPartialPopup`, `SpikePopup`, `SpikeProbes` `wrench`/`pop-slot`).
- Existing design: `docs/superpowers/specs/2026-10-18-mib2q-plugin-sdk-design.md`, `docs/superpowers/plans/2026-10-20-mibsdk-ui.md` (Global Constraints, "Status line: fair share and user controls", Decisions), `docs/superpowers/plans/2026-10-20-mibpkg-distribution.md`.
- Status-line handoff: `.superpowers/sdd-archive/status-line-report.md` in the main checkout ("Icon readiness": the icon plugs into `StatusLine.Item`).

**Repos and worktrees:**
- SDK tasks (1–6, 8–10): `mib2q-plugin-sdk.worktrees/menu-icons`, branch `feat/menu-icons` (= `feat/status-line` + `spike/menu-icons`).
- mibpkg task (7): a new worktree `mibpkg.worktrees/hmisystem`, branch `feat/hmisystem`, created from `feat/statusline-cleanup` (HEAD `620b00f`), not from `main`.

**Execution order:** 1 → 2 → 3 → 4; 5 → 6 (6 needs 3's HMI job); 7 any time before 8; 9 after 2; 10 last. Everything except Task 10 is host-only. Nobody touches the bench before Task 10.

## Global Constraints

**Bytecode and linking** (Plan 2, verbatim)
- Every class that runs on the unit is **class-file version 48**. v49/v50 are rejected by J9 2.3 even with `-noverify`. The build fails on anything else.
- Every on-unit artifact passes **LinkCheck** against the car JCL (`java/**`, `javax/**` extracted from `$mibsdk.stockLsdJar`) plus its declared car classpath.
- No stock constant in `adapter-hqc/src/main/java/mibsdk/adapter/hqc/ui/`: every stock id (model, screen, event, text, popup, widgetID, atlas cell, key code) is a literal in `HqcUiIds`, taken from `lsd-src` (real values) and confirmed by a spike. `NoStockConstantsTest` enforces it.
- Platform and runtime code is Java 1.4 API: `StringBuffer`, no `codePointAt`, no try-with-resources.

**Threads** (Plan 2, verbatim, plus the System takeover)
- **No plugin code ever runs on a stock thread.**
- SDK code on stock threads (now also: the `SdkSystemActivator` constructor and `getScreenFactory`; `SdkSystemScreenFactory.createScreen`/`getText`/`getAltText`; the main-menu key handler; `SdkCarScreenFactory.getImagePath`/`getPartialPopupStubs`/`getPartialPopup`; the HMI job) may only read volatile SDK state, build or adjust stock widgets from it, and call `UiTable.input`. It catches `Throwable` and falls back to stock, logs only through `RingLog.append`, does no I/O, creates no thread and takes at most 50 ms.
- App-side model writes happen only on `mibsdk-ui`. Widget-tree work is posted with `RunnableEvent(false, r)` through `HmiPoster`, **at most one SDK job pending** (one combined job, Task 3).
- Time is the monotonic `Clock` (`uptimeMs`), never wall time.

**System takeover (new; owner decision 5)**
- `SdkSystemActivator` and `SdkSystemScreenFactory` have **no static initializer and no static field with an initializer** other than compile-time constants. Every reference to an SDK class other than `HqcUiIds` constants sits inside `try { … } catch (Throwable t)`.
- Stock factory, exactly: when `"swdl".equals(System.getProperty("RUN_MODE"))` (or reading it throws), when `SdkGlobals.uiEnabled` is false, when the forced-failure switch says `factory`, and on any Throwable while building the SDK factory.
- **Zero shadowed classes.** mibpkg writes `HMISystem=mibsdk.adapter.hqc.ui.SdkSystemActivator` only if the stock line reads exactly `HMISystem=de.audi.tghu.system.hmi.Activator` (CRLF kept), restores the file byte for byte on uninstall, refuses unknown HMI builds, and refuses a firmware-changed uninstall without `--force`. Owned prefix `de/audi/tghu/system/hmi/` joins the existing two.

**Firmware and safety** (Plan 2, verbatim)
- Supported HMI, compared as an exact string: `HMI_AU_MIB2_HQC_NAR_G22_C10_180803`.
- Never add SDK settings to `bundles.properties` (every unknown key is a bundle). Never log, print or read the j9 command line.
- Plugins can't hide or cover the reverse camera, eCall or cluster warnings: SDK UI lives only in the MENU ring (one row), the Car drawer, SDK pages and the status popup.

**Icons (new; owner decisions 3 and 4)**
- A plugin PNG reaches the native renderer only after `PngCheck` passed: ≤ 32768 bytes; PNG signature; IHDR first, 8-bit, colour type 6 (RGBA) or 4 (grey + alpha), compression 0, filter 0, interlace 0; width and height 1..72 for a row icon, 1..20 for a status icon; every chunk's CRC correct; no `acTL`/`fcTL`/`fdAT`; IEND last; IDAT inflates to exactly `height * (1 + width * channels)` bytes.
- One image id per distinct icon content, never reused in an HMI session (the stock caches key on the id). Ids `609100..609399` (Car module, served by `SdkCarScreenFactory.getImagePath`).
- Icon changes on Car-drawer rows show at the next Car screen connect, not live (Boot A I3). The API says so.

**Status line** (Plan 2 rules unchanged, plus icons)
- `SAFE` and `PDC R OFF` are must-show. Fair share, order and the user controls are unchanged. An icon counts `StatusLine.ICON_CHARS = 2` characters of an item's width.
- The line is drawn in popup **609600, slot 7**, at (514, 0, 255, 24), zpm slot 12, priority 250 (Boot B). Once 609600 is in use the SDK never writes popup 65. If 609600 cannot be registered or built, the line (text only) goes to popup 65.

**API** (owner constraint)
- Additive only. `SdkInfo.API_VERSION = 3`, `MIN_API_VERSION = 1`; manifests with api 1–3 load. Plugin limits unchanged (≤ 2 Car rows, ≤ 1 status item of ≤ 12 chars).

**Bench** (Plan 2, plus the owner's numbers)
- SSH `ssh -o HostKeyAlgorithms=ssh-rsa -o PubkeyAcceptedKeyTypes=ssh-rsa root@10.20.0.23`; keypanel `http://10.20.0.23:8099` (poll `/screen.jpg` twice).
- **Every HMI restart costs 10–12 min and can hang the unit** (P3-F12, Boot B). After a restart, `ssh rpi-bench keypanel up`; if the unit stays unreachable after two tries, stop as BLOCKED and ask the owner to power-cycle. Never power the bench yourself. Batch checks so Task 10 stays within its restart budget.
- Never select "Start update"; leave the unit on its home screen.

**Repo hygiene:** authorship `complex256` / `me@complex256.dev` (`git -c user.name=complex256 -c user.email=me@complex256.dev commit`), no personal names or home paths in committed files, stock jars never committed. SDK: `./gradlew check` and `python3 -m unittest discover -s installer -p 'test_*.py'` pass before every commit. mibpkg: `cargo test` passes before every commit.

## Review Focus

1. **A hostile or broken PNG**: truncated, wrong CRC, palette or 16-bit, interlaced, APNG, 72×72 header with a 40 MB IDAT stream, 1×100000. It must be refused before any byte is written or handed to the renderer, with `IllegalArgumentException` to the plugin and nothing else affected.
   - **Test:** Task 1 `PngCheckTest.refusesEveryMalformedOrOversizedFile`.
2. **A plugin changes its icon content in a loop** (a new PNG every second). Ids must never be reused or wrap; once the 300 ids are spent, new icons are dropped (row or item shows without an icon) with one WARN, and the HMI is never asked for an unknown id.
   - **Test:** Task 3 `IconIdsTest.exhaustedRangeDropsNewIconsAndNeverReusesAnId`.
3. **The System takeover fails after the Car drawer was built** (screen 46 throws on its first MENU press, the drawer already hid its Plugins row). The drawer row must come back within one applier pass, and the ring must be stock.
   - **Test:** Task 6 `UiApplierTest.drawerPluginsRowReturnsWhenTheMainMenuFails`.
4. **The status popup cannot be built** (stub registered, `getPartialPopup(609600)` throws on the first show). The line must reach popup 65 within one pass, and popup 65 must never be written while 609600 works.
   - **Test:** Task 4 `StatusSinksTest.fallsBackToPopup65WhenThePopupFailsAndNeverWritesItOtherwise`.
5. **The runtime stops or reloads mid-session.** The main-menu row must hide (at the next ring re-layout), status cells clear, icon ids already handed out keep their content, and a restarted runtime must not get a stale id for new content.
   - **Test:** Task 6 `UiApplierTest.downHidesTheMainMenuRowAndClearsStatusCells`; Task 3 `IconIdsTest.idsSurviveARuntimeRestartInTheSameSession`.

---

## File Structure

```
mib2q-plugin-sdk/
├── api/src/main/java/mibsdk/api/
│   ├── Icon, StockIcons                                   (new)
│   └── Ui, StatusItem, Features, SdkInfo                  (modified: additive)
├── platform/src/main/java/mibsdk/platform/
│   ├── UiIcon, UiStatusPart                               (new)
│   └── UiRow, UiSnapshot                                  (modified: icon, statusParts)
├── runtime/src/main/java/mibsdk/runtime/
│   ├── PngCheck, IconStore, IconSource                    (new: validate, write to <ram>/icons)
│   └── UiImpl, UiHub, StatusLine, PluginContextImpl       (modified)
├── adapter-fake/.../FakeUi.java                           (icon tags in its text rendering)
├── adapter-hqc/src/main/java/mibsdk/adapter/hqc/
│   ├── SdkGlobals, ConfiguratorCore, HqcPlatform          (mainMenu state, forced failure, built-in icons)
│   └── ui/
│       ├── IconIds, ImagePaths, BuiltinIcons              (new, stock-free)
│       ├── HmiWidgets, HmiWidgetState                     (new: the combined HMI job's widget part)
│       ├── StatusLayout, SdkStatusPopup, SdkPopupStatusLine, StatusSinks   (new)
│       ├── SdkSystemActivator, SdkSystemScreenFactory, MainMenuKeyHandler  (new)
│       └── HqcUiIds, SdkCarScreenFactory, SdkCarModelBank, DrawerAssignment, DrawerSlots,
│           UiApplier, PageScreens, Popup65StatusLine      (modified)
├── adapter-hqc/src/main/resources/mibsdk/adapter/hqc/icons/<name>-72.png, <name>-72r.png, <name>-20.png
├── tools/make_builtin_icons.py                            (new; deterministic, pure Python)
├── build-logic/.../MpkStage.java, PluginJson.java         (hmisystem fields, owned prefix, api 3)
├── installer/mibsdk-dev, installer/test_mibsdk_dev.py     (ui-forcefail; status shows HMISystem=)
├── plugins/hello-menu, plugins/daemon-status              (use icons; api 3)
└── docs/bench-e2e-menu-icons.md (+ docs/bench-e2e/menu-icons-*.jpg)
mibpkg/
├── src/manifest.rs                                        (SdkSpec.hmisystem_stock / hmisystem, optional)
├── src/mibsdk/bundles.rs, src/mibsdk/sdk_pkg.rs, src/cli.rs   (generate, check, references, status)
└── tests/mibsdk_bundles.rs, tests/sdk_pkg.rs, tests/manifest_kinds.rs, tests/fixtures/manifests/sdk-mibsdk-system.toml
```

---

### Task 1: Icon API (api 3) and PNG validation — SDK repo, host-only

**Files:**
- Create: `api/src/main/java/mibsdk/api/Icon.java`, `api/src/main/java/mibsdk/api/StockIcons.java`, `platform/src/main/java/mibsdk/platform/UiIcon.java`, `runtime/src/main/java/mibsdk/runtime/PngCheck.java`, `runtime/src/main/java/mibsdk/runtime/IconStore.java`, `runtime/src/main/java/mibsdk/runtime/IconSource.java`
- Modify: `api/.../Ui.java`, `api/.../StatusItem.java`, `api/.../Features.java`, `api/.../SdkInfo.java` (`API_VERSION = 3`), `build-logic/src/main/java/mibsdk/build/PluginJson.java` (`MAX_API = 3`), `runtime/.../UiImpl.java`, `runtime/.../PluginContextImpl.java`, `runtime/.../RuntimeConfig.java` (`iconDir()` = `<ram>/icons`)
- Test: `runtime/src/test/java/mibsdk/runtime/PngCheckTest.java`, `IconStoreTest.java`, `UiImplTest.java`, `ManifestParserTest.java`; `build-logic/src/test/java/mibsdk/build/PluginJsonTest.java`

**Interfaces:**
- Produces (api, all additive):
  - `public interface Icon {}`: an opaque handle; only handles made by the runtime are accepted.
  - `public final class StockIcons { DOT="dot", CIRCLE="circle", STAR="star", TRIANGLE="triangle", SQUARE="square", CHECK="check", CROSS="cross", PLUG="plug"; static boolean isKnown(String) }`.
  - `Ui`: `int ROW_ICON_MAX_PX = 72`, `int ICON_MAX_BYTES = 32768`; `Icon stockIcon(String name)` (unknown name → `IllegalArgumentException`); `Icon icon(String jarPath)` (a PNG in the plugin's own jar, validated now; missing or invalid → `IllegalArgumentException`); `Subscription carMenuRow(String label, Page opens, Icon icon)` (null icon = none); `StatusItem statusItem(String text, Icon icon)`.
  - `StatusItem`: `int ICON_MAX_PX = 20`; `void set(String text, Icon icon)` (an icon larger than 20 px → `IllegalArgumentException`).
  - `Features.UI_ICONS = "ui.icons"`. Without it every `Icon` is accepted and drawn nowhere.
- Produces (platform): `public final class UiIcon { static final int STOCK = 0, FILE = 1; static UiIcon stock(String name); static UiIcon file(String path, String key, int width, int height); int kind(); String name(); String path(); String key(); int width(); int height(); equals/hashCode on (kind, name or key) }`. `key()` is `"stock:" + name` for STOCK and the 16-hex content key for FILE.
- Produces (runtime): `final class PngCheck { static final class Info { final int width, height, colorType; } static Info check(byte[] png, int maxPx) throws IllegalArgumentException }`; `final class IconStore { IconStore(File dir); UiIcon put(byte[] validPng, PngCheck.Info info) throws IOException }`, which writes `<dir>/<key>.png` through `.tmp` + rename, with key = the first 16 hex chars of SHA-1 (`java.security.MessageDigest`) and an existing file of the same length reused; `interface IconSource { byte[] read(String jarPath, int maxBytes) throws IOException }`, implemented in `PluginContextImpl` with `java.util.zip.ZipFile` on the plugin's jar copy.
- Consumes: `UiImpl`'s `PageHandle`/`RowHandle`/`StatusHandle` (existing). `UiImpl` accepts an `Icon` only if it is its own `IconHandle` (holding a `UiIcon`), as it does for `PageHandle`.

- [ ] **Step 1: Write the failing tests**

```java
@Test void refusesEveryMalformedOrOversizedFile() {
    byte[] ok = Pngs.rgba(20, 20);                       // test helper: a valid RGBA PNG
    assertEquals(20, PngCheck.check(ok, 20).width);
    for (byte[] bad : new byte[][] {
            Pngs.truncate(ok, 40), Pngs.flipCrc(ok, "IDAT"), Pngs.palette(8, 8), Pngs.depth16(8, 8),
            Pngs.interlaced(8, 8), Pngs.withChunk(ok, "acTL"), Pngs.rgba(21, 20), Pngs.rgba(1, 100000),
            Pngs.idatBomb(72, 72, 40_000_000), new byte[32769], new byte[0]}) {
        assertThrows(IllegalArgumentException.class, () -> PngCheck.check(bad, 72));
    }
}
```
`IconStoreTest.sameContentSameKeyAndFileDifferentContentNewKey`; `UiImplTest.iconFromJarIsValidatedAndBigStatusIconIsRefused` (a 72-px icon on `statusItem(text, icon)` throws, on `carMenuRow` it is fine; `stockIcon("nope")` throws; a foreign `Icon` implementation throws); `ManifestParserTest.acceptsApi1To3`; `PluginJsonTest.maxApiIs3`.

- [ ] **Step 2: Run the tests and verify they fail**

Run: `./gradlew :runtime:test --tests '*PngCheckTest' --tests '*IconStoreTest' --tests '*UiImplTest' --tests '*ManifestParserTest' :build-logic:test`
Expected: FAIL (compilation: `PngCheck`, `Icon`, `Ui.icon` undefined)

- [ ] **Step 3: Implement**

The api additions, `UiIcon`, `PngCheck` (rules exactly as in Global Constraints "Icons"; inflate with `java.util.zip.Inflater`, stopping at expected+1 bytes), `IconStore`, `IconSource`, and the `UiImpl` methods. `UiImpl.icon` reads at most `ICON_MAX_BYTES + 1` bytes on the caller's thread (the plugin's), validates with `maxPx = 72`, then stores. The width and height check against 20 happens where the icon is used for a status item. The existing `carMenuRow(label, opens)` and `statusItem(text)` delegate with a null icon. Javadoc on `carMenuRow(…, Icon)`: "an icon change shows the next time the Car screen opens". The icon field of rows and items reaches `UiHub` in Task 2; until then `UiImpl` stores it on the handle.

- [ ] **Step 4: Run the tests and verify they pass**

Run: the Step 2 command, then `./gradlew check`.
Expected: PASS. LinkCheck passes (`Inflater`, `MessageDigest`, `ZipFile` are in the car JCL. If LinkCheck reports one missing, stop and report; do not work around it).

- [ ] **Step 5: Commit**

```bash
git add api platform runtime build-logic
git commit -m "feat(api): icons for Car rows and status items (api 3), PNG validation"
```

---

### Task 2: Icons in the snapshot and the status line — SDK repo, host-only

**Files:**
- Create: `platform/src/main/java/mibsdk/platform/UiStatusPart.java`
- Modify: `platform/.../UiRow.java`, `platform/.../UiSnapshot.java`, `runtime/.../StatusLine.java`, `runtime/.../UiHub.java`, `runtime/.../UiImpl.java`, `adapter-fake/.../FakeUi.java`
- Test: `runtime/src/test/java/mibsdk/runtime/StatusLineTest.java`, `UiHubTest.java`; `platform/src/test/java/mibsdk/platform/UiSnapshotTest.java`; `adapter-fake/src/test/.../FakeUiTest.java`

**Interfaces:**
- Consumes: `UiIcon` (Task 1).
- Produces:
  - `UiRow(String key, String label, String pageKey, UiIcon icon)` + `UiIcon icon()` (null = none). The 3-arg constructor stays.
  - `public final class UiStatusPart { UiStatusPart(String text, UiIcon icon, boolean indicator); String text(); UiIcon icon(); boolean indicator(); }`. Text is already cut to its share.
  - `UiSnapshot(…existing…, List<UiStatusPart> statusParts)` + `List<UiStatusPart> statusParts()`. The old constructor makes one indicator-less text part from a non-empty `statusLine`. `statusLine()` stays: the text-only line, composed **without** icons (the popup-65 fallback).
  - `StatusLine.ICON_CHARS = 2`; `StatusLine.Item(String owner, String text, UiIcon icon)`, where `width()` = `text.length() + (icon != null ? ICON_CHARS : 0)` and `UiIcon iconAt(int width)` returns the icon only when `width >= ICON_CHARS + MIN_ITEM_CHARS`, else null; `render(width)` cuts the text to `width - ICON_CHARS` when the icon is kept, else to `width`.
  - `static List<UiStatusPart> StatusLine.parts(List<String> indicators, List<Item> items, int maxChars)`: the indicators that `line` keeps, joined into one part with `indicator = true`, then one part per shown item. `line(…)` keeps its current signature and output for icon-less items.
  - `UiHub.setStatusItem(String owner, String text, UiIcon icon)`, `UiHub.addCarRow(String owner, String rowKey, String label, String pageKey, UiIcon icon)`; the old signatures delegate with null.

- [ ] **Step 1: Write the failing tests**

```java
@Test void iconCountsTowardTheFairShareAndIsDroppedBeforeTheTextGetsTooShort() {
    UiIcon i = UiIcon.stock("star");
    List<StatusLine.Item> items = Arrays.asList(new StatusLine.Item("a", "ABCDEFGHIJ", i),
            new StatusLine.Item("b", "XYZ", null));
    List<UiStatusPart> p = StatusLine.parts(Collections.<String>emptyList(), items, 24);
    assertEquals("ABCDEFGHIJ", p.get(0).text()); assertSame(i, p.get(0).icon());   // 12 + 1 + 3 <= 24
    List<UiStatusPart> narrow = StatusLine.parts(Arrays.asList("SAFE", "PDC R OFF"), items, 24);
    assertEquals("SAFE PDC R OFF", narrow.get(0).text());                           // must-show kept whole
    assertNull(narrow.get(1).icon());                                               // 5-char share < 2 + 3
}
```
Plus `StatusLineTest.lineWithoutIconsIsUnchanged` (the existing `line` cases with icon-less items still pass unchanged), `UiHubTest.rowsAndStatusPartsCarryIcons`, `UiHubTest.textOnlyLineIgnoresIcons` (`statusLine()` equals the icon-free composition), `UiSnapshotTest.oldConstructorMakesOneTextPart`, `FakeUiTest.rendersIconTags` (FakeUi shows a row as `[star] Label` and a FILE icon as `[png:<key>]`).

- [ ] **Step 2: Run the tests and verify they fail**

Run: `./gradlew :runtime:test :platform:test :adapter-fake:test`
Expected: FAIL (compilation: `UiStatusPart`, `StatusLine.parts` undefined)

- [ ] **Step 3: Implement** the constructors and accessors above. `UiHub.build` builds both outputs from one ordered item list: `parts` with icons, and `line` from the same items with `icon = null`. `UiImpl`'s row and status handles pass their `UiIcon` (STOCK icons always; FILE icons only if within the size limit of their use).

- [ ] **Step 4: Run the tests and verify they pass**

Run: `./gradlew check && python3 -m unittest discover -s installer -p 'test_*.py'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add platform runtime adapter-fake
git commit -m "feat(runtime): icons on Car rows and status items in the UI snapshot"
```

---

### Task 3: Adapter icon ids, built-in icons and drawer row icons — SDK repo, host-only

**Files:**
- Create: `adapter-hqc/src/main/java/mibsdk/adapter/hqc/ui/IconIds.java`, `ImagePaths.java`, `BuiltinIcons.java`, `HmiWidgets.java`, `HmiWidgetState.java`; `tools/make_builtin_icons.py`; `adapter-hqc/src/main/resources/mibsdk/adapter/hqc/icons/` (24 PNGs: `<name>-72.png`, `<name>-72r.png`, `<name>-20.png` for the 8 `StockIcons` names)
- Modify: `HqcUiIds.java`, `SdkCarScreenFactory.java` (`getImagePath`; bitmaps in `addRows`; static refs to the SDK rows), `DrawerAssignment.java` (`iconAt`), `DrawerSlots.java`, `UiApplier.java` (combined job), `SdkGlobals.java` (`ICON_IDS`), `HqcPlatform.java` (unpack the built-ins at start)
- Test: `adapter-hqc/src/test/java/mibsdk/adapter/hqc/ui/IconIdsTest.java`, `ImagePathsTest.java`, `BuiltinIconsTest.java`, `DrawerSlotsTest.java`, `UiApplierTest.java`, `SdkCarScreenFactoryTest.java`, `StockThreadCodeTest.java`; `installer/test_make_builtin_icons.py`

**Interfaces:**
- Consumes: `UiRow.icon()`, `UiIcon` (Tasks 1–2).
- Produces:
  - `HqcUiIds`: `ICON_ID_FIRST = 609100`, `ICON_ID_LAST = 609399`, `IMAGE_ROOT_FROM_INSTALL = "../images"` (relative to the install root `/mnt/app/eso/hmi/lsd/mibsdk`).
  - `public final class IconIds { IconIds(int first, int last); int idFor(String key, String absFile); String fileFor(int id); boolean exhausted(); }`. `idFor` returns the id already given to `key`, else the next unused id, or -1 when the range is spent (the first -1 logs one WARN through the applier). A STOCK icon takes two ids, keys `stock:<name>-72` and `stock:<name>-72r` (status: `stock:<name>-20`); a FILE icon takes one id, key = its content key. It holds one session-lifetime instance, `SdkGlobals.ICON_IDS`, with synchronized writes and reads from a volatile copy-on-write array, so stock threads read without a lock.
  - `final class ImagePaths { static String relative(File imageRoot, File target) }`: the `../`-relative path from `imageRoot` to `target` (both absolute, normalised).
  - `final class BuiltinIcons { static void unpack(File iconDir, RingLog log); static File file(File iconDir, String name, int size, boolean reflex) }`, which copies the jar resources to `<ram>/icons/builtin/` on `HqcPlatform.start()` (the SDK thread, not a stock thread).
  - `DrawerAssignment.iconAt(int slot)` → `int[]` bitmaps or null. `DrawerSlots.assign` resolves each row's `UiIcon` through `IconIds`: STOCK → `{id(name-72), id(name-72r)}`, FILE → `{id, id}`, unknown or spent → null.
  - `HmiWidgetState` (immutable, built on `mibsdk-ui`): `int[][] slotBitmaps` here (Task 4 adds the status layout, Task 6 the main-menu row). `HmiWidgets.apply(HmiWidgetState want)` (HMIEvent) applies only what differs from the last applied state.
  - `UiApplier`: one combined HMI job replaces `pageJob`. It is posted when pages need rendering **or** the published `HmiWidgetState` differs from the applied one, and only once the first screen is up (`SdkGlobals.uiRowModels != null`, the existing guard). The job re-evaluates `needsRender(table.snapshot(), false)` and renders pages only if that is true, then calls `HmiWidgets.apply`, each in its own try/catch. The after-hook (`wake`) logic is unchanged.
- `SdkCarScreenFactory.getImagePath(int id, int terminal)`: in `ICON_ID_FIRST..ICON_ID_LAST` with a known file → `ImagePaths.relative(imageRoot, file)`; else `super`. It catches Throwable.

- [ ] **Step 1: Write the failing tests**

```java
@Test void exhaustedRangeDropsNewIconsAndNeverReusesAnId() {
    IconIds ids = new IconIds(609100, 609102);
    assertEquals(609100, ids.idFor("k1", "/r/icons/k1.png"));
    assertEquals(609100, ids.idFor("k1", "/r/icons/k1.png"));       // same content, same id
    assertEquals(609101, ids.idFor("k2", "/r/icons/k2.png"));
    assertEquals(609102, ids.idFor("k3", "/r/icons/k3.png"));
    assertEquals(-1, ids.idFor("k4", "/r/icons/k4.png"));
    assertEquals("/r/icons/k2.png", ids.fileFor(609101));
}
@Test void relativePathFromTheImageRootReachesRam() {
    assertEquals("../../../../../../ramdisk/mibsdk/icons/ab.png",
        ImagePaths.relative(new File("/mnt/app/eso/hmi/lsd/images"), new File("/ramdisk/mibsdk/icons/ab.png")));
    assertEquals("../mibsdk/icons/ab.png",
        ImagePaths.relative(new File("/mnt/app/eso/hmi/lsd/images"), new File("/mnt/app/eso/hmi/lsd/mibsdk/icons/ab.png")));
}
```
Plus `IconIdsTest.idsSurviveARuntimeRestartInTheSameSession` (two `UiApplier`s over `SdkGlobals.ICON_IDS` give the same id for the same key and a new one for new content), `DrawerSlotsTest.iconsResolveToBitmapPairs`, `SdkCarScreenFactoryTest.getImagePathServesSdkIdsAndDefersTheRest`, `UiApplierTest.slotIconChangePostsOneJobAndAppliesBitmapsOnce`, `BuiltinIconsTest.everyStockNameHasThreeValidPngs` (each passes `PngCheck` at 72 / 72×68 / 20), `test_make_builtin_icons.py` (the script's output is byte-identical to the committed resources). `StockThreadCodeTest`: add `IconIds`, `ImagePaths`, `HmiWidgets`, `HmiWidgetState` to `STOCK_THREAD_FILES` and `getImagePath` to the catch-Throwable list.

- [ ] **Step 2: Run the tests and verify they fail**

Run: `./gradlew :adapter-hqc:test && python3 -m unittest discover -s installer -p 'test_*.py'`
Expected: FAIL (compilation: `IconIds`, `ImagePaths` undefined)

- [ ] **Step 3: Implement**

- `tools/make_builtin_icons.py`: zlib + struct only, deterministic output. White glyphs on transparent RGBA, as the spike icons were. The reflection is the 72×72 glyph flipped and cut to 72×68, with alpha fading from 40 % to 0.
- Paths: the file layout is `<ram>/icons/<key>.png` (plugin icons, Task 1) and `<ram>/icons/builtin/<name>-<size>[r].png`.
- `SdkCarScreenFactory.addRows` sets each slot's bitmaps from `SdkGlobals.uiSlots.iconAt(slot)` at drawer build, and keeps the 9 `MenuItemController` refs in a static volatile array for `HmiWidgets`. `HmiWidgets.apply` calls `setBitmaps` on rows whose bitmaps changed. Per Boot A I3, the change shows at the next Car screen connect; nothing tries to force it.

- [ ] **Step 4: Run the tests and verify they pass**

Run: `./gradlew check && python3 -m unittest discover -s installer -p 'test_*.py'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add adapter-hqc tools installer/test_make_builtin_icons.py
git commit -m "feat(adapter-hqc): icon ids served by getImagePath; icons on Car drawer rows"
```

---

### Task 4: SDK status popup 609600 with icons, popup-65 fallback — SDK repo, host-only

**Files:**
- Create: `adapter-hqc/src/main/java/mibsdk/adapter/hqc/ui/StatusLayout.java`, `SdkStatusPopup.java`, `SdkPopupStatusLine.java`, `StatusSinks.java`
- Modify: `HqcUiIds.java`, `SdkCarModelBank.java` (popup models), `SdkCarScreenFactory.java` (`getPartialPopupStubs`, `getPartialPopup`), `StatusLineSink.java`, `Popup65StatusLine.java`, `UiApplier.java` (status phase on parts), `HmiWidgetState.java`/`HmiWidgets.java` (layout), `SdkGlobals.java` (`statusPopup` state)
- Test: `adapter-hqc/src/test/java/mibsdk/adapter/hqc/ui/StatusLayoutTest.java`, `StatusSinksTest.java`, `SdkCarActivatorTest.java` (models), `SdkCarScreenFactoryTest.java` (stub + build), `UiApplierTest.java`, `StockThreadCodeTest.java`

**Interfaces:**
- Consumes: `UiSnapshot.statusParts()` (Task 2); `IconIds`, `BuiltinIcons.file` (Task 3); the combined HMI job (Task 3).
- Produces:
  - `HqcUiIds` (from `SpikeFactory`/`SpikePopup`, Boot A/B): `POPUP_SDK_STATUS = 609600`, `POPUP_SDK_X = 514`, `POPUP_SDK_W = 255`, `POPUP_SDK_H = 24`, `POPUP_SDK_SLOT = 7`, `POPUP_SDK_ZPM_SLOT = 12`, `POPUP_SDK_PRIORITY = 250`, `POPUP_BACKDROP_BITMAP = 251`, `STATUS_CELLS = 6`, `STATUS_PAD_PX = 6`, `STATUS_CHAR_PX = 10`, `STATUS_ICON_PX = 20`, models `STATUS_POPUP_VISIBLE_MODEL = MODEL_BASE + 12`, `STATUS_LABEL_MODEL_0 = MODEL_BASE + 13` (+0 indicators, +1..+6 cells), `STATUS_FILE_ICON_MODEL_1 = MODEL_BASE + 20` (+0..+5), so `MODEL_COUNT = 26`.
  - `final class StatusLayout { static final class Cell { final int iconX; final int labelX; final int labelW; final int stockIconId; final String filePath; final String text; } static StatusLayout of(List<UiStatusPart> parts, IconIds ids, File builtinDir); Cell indicators(); Cell cell(int i); boolean sameGeometry(StatusLayout o); }`. Placement: `x = STATUS_PAD_PX`. Before each part after the first, `x += STATUS_CHAR_PX`. For a part with an icon: `iconX = x`, then `x += STATUS_ICON_PX + 2`. Then `labelX = x`, `labelW = POPUP_SDK_W - STATUS_PAD_PX - x`, and `x += text.length() * STATUS_CHAR_PX`. A STOCK icon becomes `stockIconId` (built-in 20 px, tinted); a FILE icon becomes `filePath` (absolute, own colours); a part beyond `STATUS_CELLS` is dropped.
  - `SdkStatusPopup.build(SdkCarScreenFactory f, int terminal)` builds `SpikePopup`'s recipe at (514, 0, 255, 24), slot 7: backdrop, indicator label, and 6 × (id `IconController`, file `IconController` on its ResourceLocatorModel, `LabelController` on its LabelModel). Bounds come from the latest `HmiWidgetState.status`. It is kept in a static volatile for `HmiWidgets`.
  - `SdkGlobals.statusPopup`: `0` unknown, `1` registered (the stub was added), `2` built, `-1` failed (the stub or the build threw).
  - `StatusLineSink.show(String)` becomes `void show(List<UiStatusPart> parts, String textLine)` (`hide()` unchanged). `SdkPopupStatusLine` writes the 7 label models and 6 locator models (`setResourceLocator(-1, path, 1)`, cleared with `(-1, "", 0)`) the way `RowModels` writes the bank's models, and sets the visible model. `Popup65StatusLine.show(parts, textLine)` shows `textLine`.
  - `final class StatusSinks implements StatusLineSink { interface State { int get(); } StatusSinks(StatusLineSink sdkPopup, StatusLineSink popup65, State state) }` (production state reads `SdkGlobals.statusPopup`): delegates to the SDK popup while state is `1` or `2`, else to popup 65. On a switch it hides the old sink only if the SDK showed text on it.
  - `UiApplier` status phase: `status.show(current.statusParts(), current.statusLine())` with the existing re-assert rule. The layout (`StatusLayout.of`) goes into `HmiWidgetState`, and `HmiWidgets.apply` sets bounds and id bitmaps (empty `int[0]` hides) only when `sameGeometry` is false.

- [ ] **Step 1: Write the failing tests**

```java
@Test void fallsBackToPopup65WhenThePopupFailsAndNeverWritesItOtherwise() {
    RecordingSink sdk = new RecordingSink(), p65 = new RecordingSink();
    int[] state = {1};
    StatusSinks s = new StatusSinks(sdk, p65, () -> state[0]);
    s.show(parts("TS up"), "TS up");
    assertEquals(Arrays.asList("show TS up"), sdk.calls); assertTrue(p65.calls.isEmpty());
    state[0] = -1;
    s.show(parts("TS up"), "TS up");
    assertEquals(Arrays.asList("show TS up"), p65.calls);
    assertEquals(Arrays.asList("show TS up", "hide"), sdk.calls);
}
```
Plus `StatusLayoutTest.cellsFollowTheUnitGrid` (indicators "SAFE" then an item with an icon and "C801": `iconX == 6 + 40 + 10`, `labelX == iconX + 22`; a 7th item is dropped), `StatusLayoutTest.textChangeOfSameLengthKeepsGeometry`, `SdkCarScreenFactoryTest.stubsAddPopup609600InSlot7AndBuildFailureMarksFailed`, `SdkCarActivatorTest.popupModelsAreCreated` (choice, 7 labels, 6 locators), `UiApplierTest.layoutChangePostsOneJobTextChangeOnlyWritesModels`.

- [ ] **Step 2: Run the tests and verify they fail**

Run: `./gradlew :adapter-hqc:test`
Expected: FAIL (compilation: `StatusSinks`, `StatusLayout` undefined)

- [ ] **Step 3: Implement** as specified. `getPartialPopupStubs` appends `new PartialPopupStub(609600, STATUS_POPUP_VISIBLE_MODEL, -1, 250, 7, 12, true, 7, terminal, 2, null, true, true)` to `super`'s array and sets `statusPopup = 1`; on Throwable it returns `super`'s array and sets `-1`. `getPartialPopup(609600, t)` builds and sets `2`, or sets `-1` and returns null. Popup-65 comments and `UiApplier`'s class doc now say popup 65 is only the fallback.

- [ ] **Step 4: Run the tests and verify they pass**

Run: `./gradlew check && python3 -m unittest discover -s installer -p 'test_*.py'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add adapter-hqc
git commit -m "feat(adapter-hqc): status line in SDK popup 609600 (slot 7) with icons; popup 65 as fallback"
```

---

### Task 5: `HMISystem=` takeover with a stock fallback — SDK repo, host-only

**Files:**
- Create: `adapter-hqc/src/main/java/mibsdk/adapter/hqc/ui/SdkSystemActivator.java`, `SdkSystemScreenFactory.java`, `MainMenuKeyHandler.java`
- Modify: `HqcUiIds.java`, `SdkGlobals.java`, `ConfiguratorCore.java` (forced-failure switch), `RuntimeConfig.java` (`uiForceFailFile()` = `<persist>/dev/ui.forcefail`), `adapter-hqc/build.gradle.kts` (host stock packages `de/audi/tghu/system/hmi/`)
- Test: `adapter-hqc/src/test/java/mibsdk/adapter/hqc/ui/SdkSystemActivatorTest.java`, `SdkSystemScreenFactoryTest.java`, `SystemTakeoverSourceTest.java`, `StockThreadCodeTest.java`; `adapter-hqc/src/test/java/mibsdk/adapter/hqc/ConfiguratorIsolationTest.java`

**Interfaces:**
- Produces:
  - `HqcUiIds`: `SYSTEM_MODULE = 0`, `SYS_SCREEN_MAINWIZARD = 46`, `SYS_TEXT_PLUGINS = 90000`, `MAIN_MENU_WIDGET_ID = 11`, `WRENCH_ATLAS_CELL = 16`, `WRENCH_DECORATOR = 9`, `MAIN_MENU_INTERNAL_ID = 90100`, `KEY_ENTER = 17`.
  - `SdkGlobals`: `MAIN_MENU_OFF = 0`, `MAIN_MENU_PENDING = 1`, `MAIN_MENU_ACTIVE = 2`, `MAIN_MENU_FAILED = 3`; `static volatile int mainMenu` (starts OFF); `static boolean drawerPluginsRowWanted()` (= OFF or FAILED); `static volatile String uiForceFail` (`null`, `"factory"` or `"screen"`).
  - `public class SdkSystemActivator extends AbstractHMIActivator` (`SpikeSystemActivator`'s shape: `super(0, "System", skin, new SystemModelBank())`, the stock `SystemConditionBank`). `getScreenFactory()` gives the SDK factory and sets `mainMenu = PENDING` only when not swdl, `uiEnabled` is true, `uiForceFail` is not `factory`, and construction succeeded. Otherwise it gives `new SystemScreenFactory(fw)` (package-private `AbstractScreenFactory factoryForTests()` returns the cached factory), logs one STATUS line `ui system=stock reason=<swdl|ui-off|forced|factory-failed>`, and leaves `mainMenu` at OFF. `uiForceFail == "factory"` makes it throw inside the try, so the real catch path runs.
  - `final class SdkSystemScreenFactory extends SystemScreenFactory`: `createScreen(46, t)` calls `super`, finds the `MainWizardController`, copies colour indices and insets from the last stock row, and appends one `MenuItemController`: type 2, label `SYS_TEXT_PLUGINS`, widgetID 11, internal id 90100, `setKeyHandler(new MainMenuKeyHandler())`, `setVisible(!SdkGlobals.uiDown)`. It sets the atlas mapping `[11] := {16, 9}` only when the entry is `{11, 11}` or already `{16, 9}` and the array has at least 12 entries (else leaves it and logs once). It then sets `mainMenu = ACTIVE` and keeps the row in `static volatile MenuItemController row`. On Throwable, or `uiForceFail == "screen"`, it returns `super.createScreen(46, t)` (a fresh stock screen) and sets `mainMenu = FAILED`. FAILED is sticky for the boot: later builds of screen 46 stay stock. `getText`/`getAltText` (and `getText(int,int)`) serve `SYS_TEXT_PLUGINS` from `SdkGlobals.uiSlots.labelAt(0)` (default `"Plugins"`).
  - `final class MainMenuKeyHandler implements IWidgetKeyHandler`: on key 17 it consumes the event and calls `UiInputs.rowPressed(SdkGlobals.UI, SdkGlobals.uiSlots, 0)`; other keys do nothing; it never throws.
  - `ConfiguratorCore`: reads `<persist>/dev/ui.forcefail` (first line, trimmed; only `factory` or `screen` count) into `SdkGlobals.uiForceFail` and logs `configurator: ui.forcefail=<v>` when set.

- [ ] **Step 1: Write the failing tests**

```java
@Test void swdlUiOffForcedOrBrokenFactoryAllGiveExactlyTheStockFactory() {
    for (Runnable setup : new Runnable[] {
            () -> System.setProperty("RUN_MODE", "swdl"),
            () -> SdkGlobals.uiEnabled = false,
            () -> SdkGlobals.uiForceFail = "factory"}) {
        reset(); setup.run();
        SdkSystemActivator a = new SdkSystemActivator();
        assertEquals(SystemScreenFactory.class, a.factoryForTests().getClass());
        assertEquals(SdkGlobals.MAIN_MENU_OFF, SdkGlobals.mainMenu);
        assertTrue(SdkGlobals.drawerPluginsRowWanted());
    }
}
```
Plus `SdkSystemScreenFactoryTest.appendsOnePluginsRowWithWidget11AndSetsTheWrench` (over a host-built screen 46 or a wizard fixture: the child count is +1, the last row has widgetID 11, label 90000 and a `MainMenuKeyHandler`; the mapping[11] is `{16, 9}`; the state is ACTIVE), `…forcedScreenFailureGivesAStockScreenAndFailedState`, `…unexpectedMappingIsLeftAlone`, `MainMenuKeyHandler` SELECT → one `ROW_SELECTED` for `mibsdk/plugins` in the ring and a recorded press, `ConfiguratorIsolationTest.forceFailFileIsRead`. `SystemTakeoverSourceTest.noStaticInitializers`: in `SdkSystemActivator.java` and `SdkSystemScreenFactory.java` there is no `static {` and no `static` field with `=` other than `static final` primitives or Strings. `StockThreadCodeTest`: add the three new files, plus `createScreen`, `getText`, `getAltText`, `getScreenFactory` and `keyPressed` to the catch-Throwable list.

- [ ] **Step 2: Run the tests and verify they fail**

Run: `./gradlew :adapter-hqc:test`
Expected: FAIL (compilation: `SdkSystemActivator` undefined)

- [ ] **Step 3: Implement** as specified, copying the spike classes' structure. Add `de/audi/tghu/system/hmi/` to `hostStockJar.packages`. If screen 46 cannot be built on the host (jxe2jar code too broken even repaired), test `createScreen`'s SDK part through a package-private `appendRow(MainWizardController w)` on a host-built `MainWizardController` with stock `MenuItemController` children. Do not stub stock classes.

- [ ] **Step 4: Run the tests and verify they pass**

Run: `./gradlew check && python3 -m unittest discover -s installer -p 'test_*.py'`
Expected: PASS (LinkCheck covers the new System classes against the car JCL and `lsd.jar`)

- [ ] **Step 5: Commit**

```bash
git add adapter-hqc runtime/src/main/java/mibsdk/runtime/RuntimeConfig.java
git commit -m "feat(adapter-hqc): HMISystem= activator adds a Plugins row to the MENU ring, stock on any failure"
```

---

### Task 6: Plugins from the main menu, Car-drawer row only as fallback — SDK repo, host-only

**Files:**
- Modify: `adapter-hqc/.../ui/PageScreens.java` (`openAllowed`), `UiApplier.java` (slot-0 rule, main-menu row in `HmiWidgetState`), `SdkCarModelBank.java` (slot-0 initial value), `HmiWidgets.java`, `HmiWidgetState.java`
- Test: `PageScreensNavigationTest.java`, `UiApplierTest.java`, `SdkCarActivatorTest.java`

**Interfaces:**
- Consumes: `SdkGlobals.mainMenu`, `drawerPluginsRowWanted()`, `SdkSystemScreenFactory.row` (Task 5); the combined job (Task 3).
- Produces:
  - `PageScreens.openAllowed(int currentId, boolean blocked, int pendingId, long sincePressMs)`: as today, plus `currentId == HqcUiIds.SYS_SCREEN_MAINWIZARD` under the same recent-press rule as a Car screen. BACK returns to the saved screen-46 data (Boot B C3).
  - `UiApplier.applyRows`: slot 0 wants visible iff `slots.visibleAt(0) && SdkGlobals.drawerPluginsRowWanted()`. It is evaluated every pass, so a FAILED main menu brings the row back within `AWAIT_MS`.
  - `SdkCarModelBank.createModel` slot 0: visible iff `!uiDown && drawerPluginsRowWanted()`.
  - `HmiWidgetState.mainMenuRowVisible` = `!down && mainMenu == ACTIVE`. `HmiWidgets.apply` calls `row.setVisible(v)` on change. Per Boot B it shows at the next ring re-layout.

- [ ] **Step 1: Write the failing tests**

```java
@Test void drawerPluginsRowReturnsWhenTheMainMenuFails() throws Exception {
    SdkGlobals.mainMenu = SdkGlobals.MAIN_MENU_PENDING;
    applier.step(0);                                   // snapshot with the Plugins row
    assertEquals(Boolean.FALSE, rows.last(0));         // hidden: the MENU ring has it
    SdkGlobals.mainMenu = SdkGlobals.MAIN_MENU_FAILED;
    applier.step(0);                                   // no new snapshot needed
    assertEquals(Boolean.TRUE, rows.last(0));
}
```
Plus `UiApplierTest.downHidesTheMainMenuRowAndClearsStatusCells` (after `UiSnapshot.down`, the job's widget state has `mainMenuRowVisible == false` and every status cell empty), `UiApplierTest.mainMenuOffKeepsTheDrawerRow` (OFF → slot 0 visible), `PageScreensNavigationTest.openFromTheMainMenuNeedsARecentPress` (46 with a press 500 ms ago → allowed; 46 with no press → dropped; screen 45 → dropped), `SdkCarActivatorTest.slot0StartsHiddenWhileTheMainMenuIsActive`.

- [ ] **Step 2: Run the tests and verify they fail**

Run: `./gradlew :adapter-hqc:test`
Expected: FAIL (assertions: slot 0 still visible; open from 46 dropped)

- [ ] **Step 3: Implement** the four rules above. Update `PageScreens`' class doc (open origins) and `UiApplier`'s (slot 0).

- [ ] **Step 4: Run the tests and verify they pass**

Run: `./gradlew check && python3 -m unittest discover -s installer -p 'test_*.py'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add adapter-hqc
git commit -m "feat(adapter-hqc): Plugins opens from the MENU ring; the Car-drawer row only while the ring has none"
```

---

### Task 7: mibpkg manages `HMISystem=` like `HMICar=` — mibpkg repo, worktree `mibpkg.worktrees/hmisystem` (branch `feat/hmisystem` from `feat/statusline-cleanup`), host-only

**Files:**
- Modify: `src/manifest.rs` (`SdkSpec`), `src/mibsdk/bundles.rs`, `src/mibsdk/sdk_pkg.rs`, `src/cli.rs` (`sdk status`), `Cargo.toml` (`version = "0.9.0"`), `README.md` (sdk section)
- Create: `tests/fixtures/manifests/sdk-mibsdk-system.toml` (the existing fixture + the two new keys + the third owned prefix)
- Test: `tests/mibsdk_bundles.rs`, `tests/sdk_pkg.rs`, `tests/manifest_kinds.rs`, `tests/cli_sdk.rs`

**Interfaces:**
- Produces:
  - `SdkSpec { …, #[serde(default)] pub hmisystem_stock: Option<String>, #[serde(default)] pub hmisystem: Option<String> }`. Both or neither (validation: `[sdk] hmisystem and hmisystem_stock go together`), each a Java class name. `bundle`'s key may not be `HMISystem`.
  - `bundles::check_line(text: &[u8], key: &str, what: &str, allowed: &[&str]) -> Result<()>`: today's `check_hmicar`, generalised over the key, with the same `Properties.load` key reading, generalised as `key_lines(lines, key)`. `check_hmicar` stays as a wrapper.
  - `bundles::generate`: when `sdk.hmisystem` is set, rewrites the single `HMISystem=` line exactly like `HMICar=`. It accepts the stock or SDK value (fixed point) and keeps the line break; a non-stock value, a duplicate key, or a bare CR is refused with `HMISystem is not stock (…); refusing`. When unset, the line is copied byte for byte.
  - `sdk_pkg::check`: the same three checks as `HMICar=` (live list on update: stock or SDK line; stock backup: stock line; fresh: stock line) for `HMISystem=` when the spec has it. On a live list that has the SDK `HMISystem=` line but a spec without it (a downgrade), `generate` from the stock backup puts the stock line back.
  - `references_sdk`: also true when the spec has `hmisystem` and the live `HMISystem=` line is not stock (so the firmware-changed refusal and the stale-record logic cover it).
  - `sdk status`: prints the live `HMISystem=` line after `HMICar=`.

- [ ] **Step 1: Write the failing tests**

```rust
#[test]
fn hmisystem_substituted_once_keeping_crlf_and_restored_byte_for_byte() {
    let out = gen_with(&stock(), spec_system(), true);
    assert_eq!(count(&out, b"HMISystem=mibsdk.adapter.hqc.ui.SdkSystemActivator\r\n"), 1);
    assert_eq!(count(&out, b"HMISystem=de.audi.tghu.system.hmi.Activator"), 0);
    assert_eq!(gen_with(&out, spec_system(), true), out); // fixed point
}
```
Plus `nonstock_hmisystem_refused`, `spec_without_hmisystem_leaves_the_line_alone`, `manifest_kinds::hmisystem_keys_go_together`, `sdk_pkg::sdk_remove_restores_stock_bundles_byte_for_byte_with_hmisystem` (install with the system fixture, then remove: the live file `==` the fixture bytes), `sdk_pkg::install_refuses_a_foreign_hmisystem_line`, `sdk_pkg::firmware_changed_remove_refuses_without_force_when_only_hmisystem_references_the_sdk`, `sdk_pkg::update_from_an_sdk_without_hmisystem_takes_the_line_over`, `sdk_pkg::owned_prefix_system_hmi_refuses_a_foreign_jar`, `cli_sdk::status_prints_both_lines`.

- [ ] **Step 2: Run the tests and verify they fail**

Run: `cargo test --test mibsdk_bundles --test sdk_pkg --test manifest_kinds --test cli_sdk`
Expected: FAIL (unknown field `hmisystem`; no `HMISystem=` rewrite)

- [ ] **Step 3: Implement** as specified, reusing the `HMICar=` code paths; no second copy of the line logic.

- [ ] **Step 4: Run the tests and verify they pass**

Run: `cargo test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src tests Cargo.toml Cargo.lock README.md
git -c user.name=complex256 -c user.email=me@complex256.dev commit -m "feat(sdk): manage the HMISystem= line like HMICar= (stock check, byte-identical restore)"
```

---

### Task 8: SDK package, dev tooling and docs — SDK repo, host-only (after Task 7)

**Files:**
- Modify: `build-logic/src/main/java/mibsdk/build/MpkStage.java`, `build-logic/src/test/java/mibsdk/build/MpkStageTest.java`, `installer/mibsdk-dev`, `installer/test_mibsdk_dev.py`, `docs/superpowers/plans/2026-10-20-mibsdk-ui.md` (one "Superseded by 2026-10-24-menu-and-icons" line under its Decisions on the Car-drawer Plugins entry and popup 65), `spikes/hmi-ui/README.md` (one line: after this feature `run.sh install` refuses, by design, because `HMISystem=` is no longer stock)

**Interfaces:**
- Consumes: mibpkg 0.9.0's `[sdk] hmisystem_stock`/`hmisystem` (Task 7).
- Produces: the staged manifest has `api = 3`, `hmisystem_stock = "de.audi.tghu.system.hmi.Activator"`, `hmisystem = "mibsdk.adapter.hqc.ui.SdkSystemActivator"`, `owned = ["de/audi/tghu/car/hmi/", "de/audi/atip/model/CarModelBank", "de/audi/tghu/system/hmi/"]`. `mibsdk-dev ui-forcefail factory|screen|off` writes or removes `/mnt/persist/mibsdk/dev/ui.forcefail` over SSH (no remount; persist is read-write) and says a restart is needed. `mibsdk-dev status` prints the `HMISystem=` line.

- [ ] **Step 1: Write the failing tests:** `MpkStageTest.sdkManifestManagesHMISystem` (the three values above, verbatim) and `test_mibsdk_dev.UiForceFailTest` (the ssh command lines for each argument; a bad argument exits 2 and sends nothing).
- [ ] **Step 2: Run them and verify they fail:** `./gradlew :build-logic:test && python3 -m unittest discover -s installer -p 'test_*.py'`. Expected: FAIL.
- [ ] **Step 3: Implement** the manifest lines, the dev command and the two doc lines.
- [ ] **Step 4: Run and verify:** `./gradlew check && python3 -m unittest discover -s installer -p 'test_*.py'`; then `./gradlew :dist:mibpkgPackage -Pmibsdk.mibpkg=<mibpkg 0.9.0 binary built from Task 7>` builds a package that mibpkg 0.9.0's manifest parser accepts. Expected: PASS.
- [ ] **Step 5: Commit:** `git add build-logic installer docs spikes/hmi-ui/README.md && git commit -m "build: SDK package takes over HMISystem= (api 3); mibsdk-dev ui-forcefail"`

---

### Task 9: Reference plugins use icons — SDK repo, host-only

**Files:**
- Modify: `plugins/hello-menu` (its plugin.json `api: 3`; a 72×72 RGBA `icons/hello.png` and a 20×20 `icons/hello-20.png` in its jar, generated by `tools/make_builtin_icons.py --plugin-sample` and committed; Car row with the PNG icon; status item with `hello-20.png`; a page entry "Next icon" that swaps the row icon between `hello.png` and `StockIcons.STAR`), `plugins/daemon-status` (api 3; status item with `StockIcons.DOT` while a daemon is up, `StockIcons.CROSS` while one is down)
- Test: `plugins/hello-menu/src/test/...`, `plugins/daemon-status/src/test/...` (over FakeUi's icon tags)

**Interfaces:** Consumes only the Task 1 API. A plugin built against api 3 does not load on an api-2 SDK (manifest check), which is intended.

- [ ] **Step 1: Write the failing tests:** `HelloMenuTest.rowAndStatusCarryIcons` (FakeUi shows `[png:<key>] Hello` and the status part with a png tag; "Next icon" switches the row tag to `[star]`), `DaemonStatusTest.statusIconFollowsDaemonState`.
- [ ] **Step 2: Run them and verify they fail:** `./gradlew :plugins:hello-menu:test :plugins:daemon-status:test`. Expected: FAIL.
- [ ] **Step 3: Implement** the plugin changes.
- [ ] **Step 4: Run and verify:** `./gradlew check`; `./gradlew :plugins:hello-menu:mibPluginJar :plugins:daemon-status:mibPluginJar` write fresh stamps. Expected: PASS.
- [ ] **Step 5: Commit:** `git add plugins tools && git commit -m "feat(plugins): hello-menu and daemon-status show icons"`

---

### Task 10: Bench E2E — BENCH (restart budget: 3 planned, 4 at most)

**Files:**
- Create: `docs/bench-e2e-menu-icons.md`, `docs/bench-e2e/menu-icons-*.jpg`, `docs/bench-e2e/menu-icons-log-r{1,2,3}.txt`

**Before any restart (no restart):**
- [ ] `curl -s 10.20.0.23:8099/status` → `bridge_connected:true`; `mibpkg sdk status` on the unit shows `HMICar=…SdkCarActivator` and the stock `HMISystem=` line; `spikes/hmi-ui/run.sh status` shows no spike files. Save a host copy of the live bundles file (`bundles.pre.properties`) for the final `cmp`.
- [ ] Upgrade mibpkg to 0.9.0 (as `docs/bench-e2e-ui.md` "Builds and deploys" did for 0.8.1), then install `mibsdk` (Task 8 package), `hello-menu` and `daemon-status` (Task 9) through mibpkg. Check: `mibpkg sdk status` shows `HMICar=…SdkCarActivator`, `HMISystem=mibsdk.adapter.hqc.ui.SdkSystemActivator`, `api: 1..3`; `cmp` of the live list against `bundles.pre.properties` differs only in the `HMISystem=` line (the rest of the install was already in place).

**R1: happy path (restart 1).** `installer/mibsdk-dev restart`, `ssh rpi-bench keypanel up`. If the HMI stays black more than 5 min after SSH answers, run the emergency revert below and stop.
- [ ] C0: home frame stock; `vol_up` volume popup; status bar clock. Log: `ui system=sdk`, `ui car=sdk`, no `ui system=stock`.
- [ ] M1: `menu` → ring ends …Settings, **Plugins** with the grey wrench; focus it → red wrench (`menu-icons-ring.jpg`, `-ring-focus.jpg`).
- [ ] M2: `knob_select` → the Plugins page (`-plugins-from-menu.jpg`); `back` → ring, focus on Plugins (`-back-to-ring.jpg`); MENU on the page is a no-op (Boot B C4).
- [ ] M3: `car`, `menu_left` → the drawer has **no** Plugins row, ends with Hello (PNG icon) and the other plugin rows (`-drawer.jpg`).
- [ ] I1: Hello's row shows its PNG icon and reflection, tinted (`-drawer-hello-icon.jpg`). The log has `getImagePath 6091xx -> ../../../../../../ramdisk/mibsdk/icons/…`. **This is the first proof of the RAM path depth.** If no icon is drawn, record it, move on, and report (see "Decisions": open point 2).
- [ ] I2: hello-menu "Next icon", then Media → Car → drawer: the row shows the built-in star (`-drawer-icon-next.jpg`).
- [ ] S1: popup 609600 at top right on home, Car, Media and the ring: indicators, hello's PNG icon (own colours) + text, daemon-status's built-in dot (tinted) + text (`-status-{home,car,media,ring}.jpg`). Popup 65 shows nothing from the SDK.
- [ ] S2: REM/GEM engineering Mem/CPU line (popup 65) on while 609600 is up: both show (slot 7, `-status-with-p65.jpg`). Close the engineering menu with its combo.
- [ ] S3: stop a daemon (daemon-status's icon → cross) and later start it again: the layout changes within ~2 s, with no stale cell (`-status-relayout.jpg`). If cells do not move until the next screen change, record it (see "Decisions": open point 3).
- [ ] P1: bike-rack arm on the bench as in `docs/bench-e2e-ui.md` Step 7 → `PDC R OFF` shows whole beside the items (`-status-pdc.jpg`); disarm.
- [ ] Save `/tmp/mibsdk.log` → `menu-icons-log-r1.txt`.

**R2: forced failure + safe mode (restart 2).** `mibsdk-dev ui-forcefail factory`, `mibpkg sdk safe-mode on`, restart, `keypanel up`.
- [ ] F1: the HMI fully works: home, `vol_up`, MENU ring **stock** (ends at Settings, no Plugins), Radio and Navigation open from the ring (`-r2-ring-stock.jpg`, `-r2-radio.jpg`). Log: `configurator: ui.forcefail=factory`, `ui system=stock reason=forced`.
- [ ] F2: `car`, `menu_left` → the drawer **has** the Plugins row back; it opens the Plugins page with the safe-mode notice (`-r2-drawer-plugins.jpg`).
- [ ] F3: popup 609600 shows `SAFE` (must-show; Car-side popup unaffected by the System fallback) (`-r2-status-safe.jpg`).
- [ ] Save the log → `menu-icons-log-r2.txt`.

**R3: back to normal (restart 3).** `mibsdk-dev ui-forcefail off`, `mibpkg sdk safe-mode off`, restart, `keypanel up`.
- [ ] N1: the ring has Plugins with the wrench again; the drawer has no Plugins row; the status popup shows both plugins' items (`-r3-ring.jpg`, `-r3-drawer.jpg`, `-r3-status.jpg`). Save the log → `menu-icons-log-r3.txt`.

**R4 (optional, only if R1–R3 passed and the owner agrees to the extra 10–12 min).** `ui-forcefail screen`, restart: the drawer shows Plugins until the first MENU; after MENU the ring is stock, and within 1 s the drawer has Plugins (FAILED is sticky). Then `ui-forcefail off` needs one more restart, so R4 really costs two; skip it unless asked. The host test (Task 5) covers this path.

**Emergency revert (blank HMI after any restart):** SSH works (QNX). `mount -uw /mnt/app`, copy the mibpkg stock backup of the bundles file back over `/mnt/app/eso/hmi/lsd/bundles_dsitracer_enabled.properties`, `chmod 777` it, `sync`, `mount -ur /mnt/app`, `slay -s11 j9`. Then `mibpkg sdk status`, and report BLOCKED with the log.

- [ ] Write `docs/bench-e2e-menu-icons.md` (findings table, frames, the restart times, and the unit state at the end: SDK + hello-menu + daemon-status installed, ring with Plugins, unit on its home screen).
- [ ] Commit: `git add docs/bench-e2e-menu-icons.md docs/bench-e2e && git commit -m "docs: bench E2E for the main-menu entry, row icons and the status popup"`

---

## Decisions in this plan

[decided] comes from the owner; [my call] is a planning choice open to review.
- [decided] The Plugins entry is a row in the top-level MENU ring, through the `HMISystem=` activator takeover (the `HMICar=` pattern), with the stock wrench: atlas cell 16 via the main-wizard icon decorator mapping.
- [my call] widgetID **11**, mapping `[11] := {16, 9}`, exactly as Boot B proved. The research note's `[10]` was a plan nit (§9). The mapping is changed only if entry 11 is still stock `{11, 11}`.
- [my call] The row is appended after Settings (the proven position). Reordering the ring is not attempted.
- [decided] "Main menu only": the SDK's Plugins row leaves the Car drawer. [decided, controller ruling] It comes back whenever the System takeover is not active: stock fallback on any Throwable, swdl, UI off or unsupported build, mibpkg not managing `HMISystem=` (`mainMenu` OFF), or a failed screen-46 build (FAILED, sticky for the boot). Plugins' own Car rows are unaffected.
- [my call] In the PENDING state (SDK System factory up, MENU not yet opened this boot) the drawer row is already hidden: the ring builds it on the first MENU, and a failure there brings the drawer row back within one applier pass.
- [decided] Row icons: Car-drawer plugin rows can carry a stock icon or a plugin PNG served via `getImagePath`, with fresh ids per content; near-static (shown at the next Car reconnect). PNGs are validated before the renderer sees them.
- [my call] Plugin PNGs live in the plugin jar, are validated by the runtime and written to `/ramdisk/mibsdk/icons/<sha1-16>.png`. Drawer rows reach them by a 6-level `../` path from `ImageRoot`. No mibpkg change is needed for icons.
- [my call] Drawer rows use `{id, id}` for a plugin PNG (the icon doubles as its reflection); built-ins ship a real 72×68 reflection.
- [decided] Status line: SDK popup 609600 in slot 7 (coexists with popup 65), items may carry a small icon, the fair share counts the icon, `SAFE` / `PDC R OFF` stay must-show, popup 65 is not written while 609600 works, and popup 65 text is the fallback.
- [my call] Icon width = 2 characters (`ICON_CHARS`); an item's icon is dropped before its text would fall below 3 characters. The popup keeps Boot B's geometry (514, 0, 255, 24), 6 item cells plus one indicator label, placed on a 10-px character grid. Cell bounds and id bitmaps change through the one coalesced HMI job, and text and file icons through model writes.
- [my call] "Stock" icons for plugins are a named SDK set (`StockIcons`: dot, circle, star, triangle, square, check, cross, plug) drawn by id, so they are tinted like stock icons. They are not firmware image ids: the firmware has no usable ≤ 20 px alpha status glyphs (its status-bar icons are 30×30 for a 24-px band, and the ≤ 24-px ones are rating faces, arrows and greyscale squares without alpha). See open point 1.
- [decided] mibpkg manages `HMISystem=` exactly like `HMICar=` (stock check, byte-identical restore, refusal on unknown builds and on firmware change without `--force`). The activator is pure stock in swdl and on any Throwable, with no throwing static initialisers.
- [my call] The `[sdk]` keys `hmisystem_stock`/`hmisystem` are optional: an SDK package without them leaves `HMISystem=` stock (the "mibpkg declined" fallback); mibpkg 0.9.0.
- [decided] A deliberate bench test of the failure path: R2 forces the System factory to fail (`ui.forcefail=factory`) and checks the stock HMI and the Car-drawer Plugins row.
- [my call] The forced-failure switch is a file in persist (`/mnt/persist/mibsdk/dev/ui.forcefail`, `factory` or `screen`), read only by the configurator, set by `mibsdk-dev ui-forcefail`. R2 combines it with safe mode to prove `SAFE` in the new popup without an extra restart.
- [my call] API level 3, additive: `Icon`, `StockIcons`, `Ui.icon`/`stockIcon`, icon overloads of `carMenuRow`/`statusItem`, `StatusItem.set(text, icon)`, `Features.UI_ICONS`.

**Open points for the owner:**
1. Status-line "stock id" icons: this plan uses an SDK-shipped, tinted built-in set instead of firmware ids (reason above). Confirm, or name firmware ids you want.
2. The RAM icon path (`../` × 6 from `ImageRoot`) is unproven. If R1 I1 fails, the fallback is that mibpkg unpacks plugin icons into `lsd/mibsdk/icons/` at install (the proven `../mibsdk/…` path). That adds a mibpkg task and needs another restart.
3. Whether changing a cell's bounds on a showing popup redraws it live is unproven (R1 S3). If not, the fix is to hide and re-show the popup around a layout change, which costs one more restart to verify.
