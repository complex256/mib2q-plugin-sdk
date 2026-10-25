# Main-menu Plugins entry, row icons and status-line icons Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The SDK's Plugins entry moves to the top-level MENU ring with the wrench icon, through an `HMISystem=` takeover that mibpkg manages like `HMICar=`. Car-drawer plugin rows and status items can carry icons. The status line moves to an SDK-owned popup 609600 in slot 7.

**Architecture:**
- **Main menu.** `SdkSystemActivator` (on `HMISystem=`) copies the stock System activator. Its factory, `SdkSystemScreenFactory`, appends one row after Settings on screen 46: widgetID 11, with atlas mapping `[11] := {16, 9}` (the wrench). SELECT drops the existing Plugins-row input into `UiTable`, and the page opens through the existing `PageScreens` path. Any Throwable, `RUN_MODE=swdl` or the UI being off gives exactly the stock factory. A shared state, `SdkGlobals.mainMenu`, tells the Car side whether the Car-drawer Plugins row is still needed.
- **Icons are static files installed by mibpkg.** A plugin declares named PNGs in its package. The build validates them, and mibpkg validates them again at install. mibpkg writes them content-addressed under `/mnt/app/eso/hmi/lsd/mibsdk/icons/<plugin-id>/<sha16>.png`, with an `index.properties` (`<name>=<sha16>,<w>,<h>`), and removes them on remove or uninstall. The SDK's own named set ships the same way in the SDK package, under `mibsdk/icons-sdk/`. From `ImageRoot` (`lsd/images`) these files sit at `../mibsdk/…`, the depth spike C proved with `../mibsdk-spike/icons/`.
- **Image ids.** The runtime reads only the index. The adapter gives each content key (`sha16`) a fresh Car-module image id, of session lifetime, served by `SdkCarScreenFactory.getImagePath`. A new icon means a new package version, so it has a new hash and a new id, and the renderer cache never serves a stale image. Runtime-generated icons are out of scope.
- **Status popup.** Popup 609600 is registered through the Car factory's stubs (spike S1). It holds a backdrop, one indicator label and 6 item cells (id icon, file icon, label). Text and file icons are model writes from `mibsdk-ui`. Cell bounds and id bitmaps are applied by the applier's one coalesced HMIEvent job. If the popup cannot be registered or built, the text goes to popup 65 as today.
- **Stock-thread code stays minimal.** It reads volatile immutable state, builds or adjusts stock widgets, and drops input into the ring. It never calls plugin code and does no I/O.

**Tech Stack:** Unchanged from Plan 2: Gradle 8.13 with `build-logic`; JDK 17/Jabel, then Retrolambda, then Retrotranslator; class-file v48; JUnit 5.10.2; Python 3 `unittest`. mibpkg: Rust stable (`flate2`, `sha2` already dependencies), `cargo test`.

**Spec:** `docs/research/2026-10-24-main-menu-and-icons.md`. Its §1–§4, §9 (Boot A) and §10 (Boot B) are the authority, together with the owner and controller decisions recorded under "Decisions in this plan".
- Spike code to copy from: `spikes/hmi-ui/src/main/java/mibsdk/spike/ui/`:
  - `SpikeSystemActivator`, `SpikeSystemFactory`, `SpikeWizardKeyHandler`;
  - `SpikeFactory.getImagePath`/`getPartialPopupStubs`/`getPartialPopup`;
  - `SpikePopup`;
  - the `SpikeProbes` `wrench` and `pop-slot` commands.
- Existing design:
  - `docs/superpowers/specs/2026-10-18-mib2q-plugin-sdk-design.md`;
  - `docs/superpowers/plans/2026-10-20-mibsdk-ui.md` (Global Constraints, "Status line: fair share and user controls", Decisions);
  - `docs/superpowers/plans/2026-10-20-mibpkg-distribution.md`.
- Status-line handoff: `.superpowers/sdd-archive/status-line-report.md` in the main checkout. Its "Icon readiness" section says the icon plugs into `StatusLine.Item`.

**Repos and worktrees:**
- SDK tasks (1–7, 10–12): `mib2q-plugin-sdk.worktrees/menu-icons`, branch `feat/menu-icons` (= `feat/status-line` + `spike/menu-icons`).
- mibpkg tasks (8, 9): a new worktree `mibpkg.worktrees/hmisystem`, branch `feat/hmisystem`, created from `feat/statusline-cleanup` (HEAD `620b00f`), not from `main`.

**Execution order:**
- SDK: 1 → 2 → 3 → 4 → 5, and 6 → 7. Task 7 needs Task 4's HMI job.
- mibpkg: 8 → 9, any time before Task 10.
- Then 10 (needs 2, 8 and 9), then 11 (needs 3 and 10), then 12.
- Everything except Task 12 is host-only. Nobody touches the bench before Task 12.

## Global Constraints

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

**System takeover (new; owner decision 5)**
- `SdkSystemActivator` and `SdkSystemScreenFactory` have **no static initializer and no static field with an initializer**, other than compile-time constants. Every reference to an SDK class other than `HqcUiIds` constants sits inside `try { … } catch (Throwable t)`.
- These always get the stock factory, exactly:
  - `"swdl".equals(System.getProperty("RUN_MODE"))`, or reading it throws;
  - `SdkGlobals.uiEnabled` is false;
  - the forced-failure switch says `factory`;
  - any Throwable while building the SDK factory.
- **Zero shadowed classes.** mibpkg:
  - writes `HMISystem=mibsdk.adapter.hqc.ui.SdkSystemActivator` only if the stock line reads exactly `HMISystem=de.audi.tghu.system.hmi.Activator` (CRLF kept);
  - restores the file byte for byte on uninstall;
  - refuses unknown HMI builds;
  - refuses an uninstall after a firmware change unless `--force`.
- Owned prefix `de/audi/tghu/system/hmi/` joins the existing two.

**Firmware and safety** (Plan 2, verbatim)
- Supported HMI, compared as an exact string: `HMI_AU_MIB2_HQC_NAR_G22_C10_180803`.
- Never add SDK settings to `bundles.properties` (every unknown key is a bundle). Never log, print or read the j9 command line.
- Plugins can't hide or cover the reverse camera, eCall or cluster warnings. SDK UI lives only in the MENU ring (one row), the Car drawer, SDK pages and the status popup.

**Icons (new; owner decisions 3 and 4, controller ruling 2)**
- An icon is a PNG file declared by name in a package. Names match `[a-z0-9-]{1,32}`. Plugins declare at most 8 icons, the SDK's built-in set at most 32.
- It is validated **at package build** (build-logic `PngCheck`) and **at install** (mibpkg `png_check`), by the same rules:
  - size ≤ 32768 bytes; PNG signature;
  - IHDR first: 8-bit, colour type 6 (RGBA) or 4 (grey + alpha), compression 0, filter 0, interlace 0;
  - width and height 1..72;
  - every chunk's CRC correct; no `acTL`/`fcTL`/`fdAT`; IEND last;
  - the IDAT data inflates to exactly `height * (1 + width * channels)` bytes.
- Status icons must also be ≤ 20×20. The runtime enforces that from the index's `w,h`.
- Installed layout, written only by mibpkg:
  - `/mnt/app/eso/hmi/lsd/mibsdk/icons/<plugin-id>/<sha16>.png` plus `index.properties`;
  - the SDK set in `/mnt/app/eso/hmi/lsd/mibsdk/icons-sdk/`, same format.
- `<sha16>` = the first 16 lowercase hex characters of SHA-256 of the file bytes. Index lines are `<name>=<sha16>,<w>,<h>`, sorted by name, LF line ends.
- From `ImageRoot` (`/mnt/app/eso/hmi/lsd/images`), the paths are `../mibsdk/icons/<id>/<sha16>.png` and `../mibsdk/icons-sdk/<sha16>.png`. That is one `../`, the depth Boot A I1 proved.
- Image ids: one per content key per HMI session, never reused or re-pointed. The range is `609100..609399` (Car module, served by `SdkCarScreenFactory.getImagePath`).
- Icon changes on Car-drawer rows show at the next Car screen connect, not live (Boot A I3). The API says so.

**Status line** (Plan 2 rules unchanged, plus icons)
- `SAFE` and `PDC R OFF` are must-show. Fair share, order and the user controls are unchanged. An icon counts as `StatusLine.ICON_CHARS = 2` characters of its item's width.
- The line is drawn in popup **609600, slot 7**, at (514, 0, 255, 24), zpm slot 12, priority 250 (Boot B).
- Once 609600 is in use, the SDK never writes popup 65. If 609600 cannot be registered or built, the line goes to popup 65 as text only.

**API** (owner constraint)
- Additive only. `SdkInfo.API_VERSION = 3` and `MIN_API_VERSION = 1`, so manifests with api 1–3 load. Plugin limits are unchanged: ≤ 2 Car rows, ≤ 1 status item of ≤ 12 chars.

**Bench** (Plan 2, plus the owner's numbers)
- SSH: `ssh -o HostKeyAlgorithms=ssh-rsa -o PubkeyAcceptedKeyTypes=ssh-rsa root@10.20.0.23`. Keypanel: `http://10.20.0.23:8099` (poll `/screen.jpg` twice).
- **Every HMI restart costs 10–12 min and can hang the unit** (P3-F12, Boot B).
  - After a restart, run `ssh rpi-bench keypanel up`.
  - If the unit is still unreachable after two tries, stop as BLOCKED and ask the owner to power-cycle. Never power the bench yourself.
  - Batch checks so Task 12 stays within its restart budget.
- Never select "Start update". Leave the unit on its home screen.

**Repo hygiene**
- Authorship `complex256` / `me@complex256.dev`: commit with `git -c user.name=complex256 -c user.email=me@complex256.dev commit`.
- No personal names or home paths in committed files. Stock jars are never committed.
- Before every commit:
  - SDK: `./gradlew check` and `python3 -m unittest discover -s installer -p 'test_*.py'` pass;
  - mibpkg: `cargo test` passes.

## Review Focus

1. **A hostile or broken PNG in a package**: truncated, wrong CRC, palette or 16-bit, interlaced, APNG, a 72×72 header with a 40 MB IDAT stream, 1×100000. The build must refuse it; if the package was built by other means, mibpkg must refuse it at install, with nothing written under `/mnt/app`.
   - **Test:** Task 2 `PngCheckTest.refusesEveryMalformedOrOversizedFile`.
   - **Test:** Task 9 `icons::png_check_refuses_every_malformed_or_oversized_file`, `plugin_install_with_a_bad_icon_writes_nothing`.
2. **A plugin update changes an icon's content under the same name.** The new content needs a new id, and the old id must never be re-pointed, even while the old drawer texture is still cached.
   - **Test:** Task 4 `IconIdsTest.sameNameNewContentGetsANewIdAndTheOldIdKeepsItsFile`.
3. **The System takeover fails after the Car drawer was built**: screen 46 throws on its first MENU press, after the drawer has already hidden its Plugins row. The drawer row must come back within one applier pass, and the ring must be stock.
   - **Test:** Task 7 `UiApplierTest.drawerPluginsRowReturnsWhenTheMainMenuFails`.
4. **The status popup cannot be built**: the stub is registered, but `getPartialPopup(609600)` throws on the first show. The line must reach popup 65 within one pass, and popup 65 must never be written while 609600 works.
   - **Test:** Task 5 `StatusSinksTest.fallsBackToPopup65WhenThePopupFailsAndNeverWritesItOtherwise`.
5. **A plugin installed by `mibpkg sdk push`** (jar only, no icons on the unit), **or removed while its row is cached.**
   - An icon that is declared but not installed must give an inert `Icon` (the row or item shows without it) and one WARN. It must not throw into the plugin, and the HMI must never be asked for a path that does not exist.
   - Remove deletes the icon dir only after the plugin is gone.
   - **Test:** Task 1 `UiImplTest.declaredButNotInstalledIconIsInert`; Task 9 `plugin_remove_deletes_its_icon_dir`.

---

## File Structure

```
mib2q-plugin-sdk/
├── api/src/main/java/mibsdk/api/
│   ├── Icon, StockIcons                                   (new)
│   └── Ui, StatusItem, Features, SdkInfo                  (modified: additive)
├── platform/src/main/java/mibsdk/platform/
│   ├── UiIcon, UiStatusPart, IconIndex                    (new)
│   └── UiRow, UiSnapshot                                  (modified: icon, statusParts)
├── runtime/src/main/java/mibsdk/runtime/
│   └── UiImpl, UiHub, StatusLine, PluginContextImpl, ManifestParser, RuntimeConfig   (modified)
├── adapter-fake/.../FakeUi.java                           (icon tags in its text rendering)
├── adapter-hqc/src/main/java/mibsdk/adapter/hqc/
│   ├── SdkGlobals, ConfiguratorCore, HqcPlatform          (mainMenu state, forced failure, built-in index)
│   └── ui/
│       ├── IconIds, ImagePaths, BuiltinIcons              (new, stock-free)
│       ├── HmiWidgets, HmiWidgetState                     (new: the combined HMI job's widget part)
│       ├── StatusLayout, SdkStatusPopup, SdkPopupStatusLine, StatusSinks   (new)
│       ├── SdkSystemActivator, SdkSystemScreenFactory, MainMenuKeyHandler  (new)
│       └── HqcUiIds, SdkCarScreenFactory, SdkCarModelBank, DrawerAssignment, DrawerSlots,
│           UiApplier, PageScreens, Popup65StatusLine      (modified)
├── build-logic/src/main/java/mibsdk/build/
│   ├── PngCheck                                           (new: build-time validation)
│   └── PluginJson, MpkStage, (plugin package task)        (icons, hmisystem, api 3)
├── dist/icons/<name>-{72,72r,20}.png                      (the SDK's built-in set, generated, committed)
├── tools/make_builtin_icons.py                            (new; deterministic, pure Python)
├── installer/mibsdk-dev, installer/test_mibsdk_dev.py     (ui-forcefail; status shows HMISystem=)
├── plugins/hello-menu, plugins/daemon-status              (use icons; api 3)
└── docs/bench-e2e-menu-icons.md (+ docs/bench-e2e/menu-icons-*.jpg)
mibpkg/
├── src/manifest.rs                                        (SdkSpec.hmisystem*, SdkSpec.icons, PluginSpec.icons)
├── src/mibsdk/bundles.rs, sdk_pkg.rs, plugin_pkg.rs, cli.rs  (HMISystem=, icons install/remove, status)
├── src/mibsdk/icons.rs                                    (new: png_check, content-addressed install, index)
└── tests/mibsdk_bundles.rs, sdk_pkg.rs, plugin_pkg.rs (or the existing plugin test file), mibsdk_icons.rs,
    manifest_kinds.rs, cli_sdk.rs, fixtures/manifests/sdk-mibsdk-system.toml, fixtures/icons/*.png
```

---

### Task 1: Icon API (api 3) and the installed-icon index — SDK repo, host-only

**Files:**
- Create:
  - `api/src/main/java/mibsdk/api/Icon.java`, `api/src/main/java/mibsdk/api/StockIcons.java`;
  - `platform/src/main/java/mibsdk/platform/UiIcon.java`, `platform/src/main/java/mibsdk/platform/IconIndex.java`.
- Modify:
  - `api/.../Ui.java`, `api/.../StatusItem.java`, `api/.../Features.java`, `api/.../SdkInfo.java` (`API_VERSION = 3`);
  - `build-logic/src/main/java/mibsdk/build/PluginJson.java` (`MAX_API = 3`; parses `"icons"`);
  - `runtime/.../ManifestParser.java` (parses `"icons"`), `runtime/.../UiImpl.java`, `runtime/.../PluginContextImpl.java`;
  - `runtime/.../RuntimeConfig.java` (`pluginIconDir(String id)` = `<install>/icons/<id>`, `sdkIconDir()` = `<install>/icons-sdk`).
- Test:
  - `platform/src/test/java/mibsdk/platform/IconIndexTest.java`;
  - `runtime/src/test/java/mibsdk/runtime/UiImplTest.java`, `ManifestParserTest.java`;
  - `build-logic/src/test/java/mibsdk/build/PluginJsonTest.java`.

**Interfaces:**
- Produces (api, all additive):
  - `public interface Icon {}`: an opaque handle. Only handles made by the runtime are accepted.
  - `public final class StockIcons { DOT="dot", CIRCLE="circle", STAR="star", TRIANGLE="triangle", SQUARE="square", CHECK="check", CROSS="cross", PLUG="plug"; static boolean isKnown(String) }`: the SDK's built-in set.
  - `Ui`:
    - `int ROW_ICON_MAX_PX = 72`;
    - `Icon stockIcon(String name)`: an unknown name throws `IllegalArgumentException`;
    - `Icon icon(String name)`: a PNG the plugin declared in its manifest's `icons`. An undeclared name throws `IllegalArgumentException`. A declared name that is not installed (a `sdk push` without a package install) gives an inert icon and logs one WARN.
    - `Subscription carMenuRow(String label, Page opens, Icon icon)`: a null icon means none;
    - `StatusItem statusItem(String text, Icon icon)`.
  - `StatusItem`: `int ICON_MAX_PX = 20`; `void set(String text, Icon icon)`. An installed icon larger than 20 px throws `IllegalArgumentException`.
  - `Features.UI_ICONS = "ui.icons"`. Without it, every `Icon` is accepted and drawn nowhere.
  - Plugin manifest (`plugin.json`): `"icons": ["hello", "hello-20"]`. Optional; names match `[a-z0-9-]{1,32}`; at most 8; no duplicates. Accepted only with `"api": 3`.
- Produces (platform):
  - `public final class UiIcon { static final int STOCK = 0, FILE = 1; static UiIcon stock(String name); static UiIcon file(String path, String key, int width, int height); int kind(); String name(); String path(); String key(); int width(); int height(); }`, with equals/hashCode on (kind, name or key).
  - `public final class IconIndex { static IconIndex read(File dir); UiIcon get(String name); }`. It reads `<dir>/index.properties`. A missing, unreadable or malformed file, or a bad line, gives an empty entry or index and never throws. The path is `<dir>/<sha16>.png`.
- Consumes: `UiImpl`'s existing `PageHandle`/`RowHandle`/`StatusHandle`. `UiImpl` accepts an `Icon` only if it is its own `IconHandle` (holding a `UiIcon`, or null when inert), as it does for `PageHandle`.

- [ ] **Step 1: Write the failing tests**

```java
@Test void readsTheIndexAndIgnoresBadLines(@TempDir File d) throws Exception {
    write(new File(d, "index.properties"), "hello=0123456789abcdef,72,72\nbad=xyz,1\nsmall=fedcba9876543210,20,20\n");
    IconIndex i = IconIndex.read(d);
    assertEquals(new File(d, "0123456789abcdef.png").getPath(), i.get("hello").path());
    assertEquals(20, i.get("small").width());
    assertNull(i.get("bad"));
    assertNull(IconIndex.read(new File(d, "missing")).get("hello"));
}
```
Also:
- `UiImplTest.declaredButNotInstalledIconIsInert`;
- `UiImplTest.undeclaredOrForeignIconThrows`;
- `UiImplTest.bigIconOnAStatusItemThrows` (72 px on `statusItem(text, icon)` throws; on `carMenuRow` it is fine);
- `ManifestParserTest.acceptsApi1To3AndIconsOnlyWithApi3`;
- `PluginJsonTest.maxApiIs3AndIconNamesAreChecked`.

- [ ] **Step 2: Run the tests and verify they fail**

Run: `./gradlew :platform:test :runtime:test --tests '*UiImplTest' --tests '*ManifestParserTest' :build-logic:test`
Expected: FAIL (compilation: `IconIndex`, `Icon`, `Ui.icon` undefined)

- [ ] **Step 3: Implement** the API additions, `UiIcon`, `IconIndex` and the `UiImpl` methods.
- `UiImpl.icon(name)` reads the plugin's `IconIndex` once (lazily, on the plugin's thread, cached per `UiImpl`).
- `stockIcon(name)` returns `UiIcon.stock(name)`.
- The existing `carMenuRow(label, opens)` and `statusItem(text)` delegate with a null icon.
- Javadoc on `carMenuRow(…, Icon)`: "an icon change shows the next time the Car screen opens". Javadoc on `icon(String)`: icons are static per installed package version.

- [ ] **Step 4: Run the tests and verify they pass**

Run: the Step 2 command, then `./gradlew check`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add api platform runtime build-logic
git commit -m "feat(api): icons for Car rows and status items (api 3), read from the installed icon index"
```

---

### Task 2: Build-time PNG checks and the SDK's built-in icon set — SDK repo, host-only

**Files:**
- Create: `build-logic/src/main/java/mibsdk/build/PngCheck.java`, `tools/make_builtin_icons.py`, `dist/icons/` (24 PNGs: `<name>-72.png`, `<name>-72r.png`, `<name>-20.png` for the 8 `StockIcons` names)
- Modify: the plugin build in `build-logic` (`mibPluginJar` checks every declared icon `src/main/icons/<name>.png` with `PngCheck` and fails the build on any refusal)
- Test: `build-logic/src/test/java/mibsdk/build/PngCheckTest.java`, `build-logic/src/test/resources/png/` (fixtures), `installer/test_make_builtin_icons.py`

**Interfaces:**
- Produces: `public final class PngCheck { static final class Info { final int width, height, colorType; } static Info check(byte[] png, int maxPx) }`. It throws `IllegalArgumentException` with the rule that failed.
  - Rules: exactly the "Icons" Global Constraints. Inflate with `java.util.zip.Inflater`, stopping at expected + 1 bytes.
  - The test fixtures in `build-logic/src/test/resources/png/` are the shared vectors. Task 9 copies the same files into mibpkg's `tests/fixtures/icons/`.
- Produces: `tools/make_builtin_icons.py [--plugin-sample DIR]`. It uses only zlib and struct, and its output is deterministic.
  - Glyphs are white on a transparent RGBA background, as the spike icons were.
  - `-72r` is the 72×72 glyph flipped and cut to 72×68, with alpha fading from 40 % to 0.
  - `--plugin-sample` writes hello-menu's `hello.png` (72×72) and `hello-20.png` (20×20) for Task 11.

- [ ] **Step 1: Write the failing tests**

```java
@Test void refusesEveryMalformedOrOversizedFile() {
    byte[] ok = Pngs.rgba(20, 20);                       // test helper: a valid RGBA PNG
    assertEquals(20, PngCheck.check(ok, 72).width);
    for (byte[] bad : new byte[][] {
            Pngs.truncate(ok, 40), Pngs.flipCrc(ok, "IDAT"), Pngs.palette(8, 8), Pngs.depth16(8, 8),
            Pngs.interlaced(8, 8), Pngs.withChunk(ok, "acTL"), Pngs.rgba(73, 20), Pngs.rgba(1, 100000),
            Pngs.idatBomb(72, 72, 40_000_000), new byte[32769], new byte[0]}) {
        assertThrows(IllegalArgumentException.class, () -> PngCheck.check(bad, 72));
    }
}
```
Also `PngCheckTest.everyBuiltinIconPasses` (all 24 files in `dist/icons/`; `-72` is 72×72, `-72r` 72×68, `-20` 20×20) and `test_make_builtin_icons.py` (the script's output is byte-identical to the committed `dist/icons/`).

- [ ] **Step 2: Run the tests and verify they fail**

Run: `./gradlew :build-logic:test && python3 -m unittest discover -s installer -p 'test_*.py'`
Expected: FAIL (compilation: `PngCheck` undefined)

- [ ] **Step 3: Implement** `PngCheck`, the plugin-build check and the generator. Generate and commit `dist/icons/` and the fixture PNGs (write them with the test helper, then save them as files).

- [ ] **Step 4: Run the tests and verify they pass**

Run: `./gradlew check && python3 -m unittest discover -s installer -p 'test_*.py'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add build-logic tools dist/icons installer/test_make_builtin_icons.py
git commit -m "build: PNG icon checks at plugin build; the SDK's built-in icon set"
```

---

### Task 3: Icons in the snapshot and the status line — SDK repo, host-only

**Files:**
- Create: `platform/src/main/java/mibsdk/platform/UiStatusPart.java`
- Modify: `platform/.../UiRow.java`, `platform/.../UiSnapshot.java`, `runtime/.../StatusLine.java`, `runtime/.../UiHub.java`, `runtime/.../UiImpl.java`, `adapter-fake/.../FakeUi.java`
- Test: `runtime/src/test/java/mibsdk/runtime/StatusLineTest.java`, `UiHubTest.java`; `platform/src/test/java/mibsdk/platform/UiSnapshotTest.java`; `adapter-fake/src/test/.../FakeUiTest.java`

**Interfaces:**
- Consumes: `UiIcon` (Task 1).
- Produces:
  - `UiRow(String key, String label, String pageKey, UiIcon icon)` and `UiIcon icon()` (null means none). The 3-argument constructor stays.
  - `public final class UiStatusPart { UiStatusPart(String text, UiIcon icon, boolean indicator); String text(); UiIcon icon(); boolean indicator(); }`. Its text is already cut to its share.
  - `UiSnapshot(…existing…, List<UiStatusPart> statusParts)` and `List<UiStatusPart> statusParts()`.
    - The old constructor makes one text part, without the indicator flag, from a non-empty `statusLine`.
    - `statusLine()` stays: it is the text-only line, composed **without** icons, for the popup-65 fallback.
  - `StatusLine.ICON_CHARS = 2`, and `StatusLine.Item(String owner, String text, UiIcon icon)`:
    - `width()` = `text.length() + (icon != null ? ICON_CHARS : 0)`;
    - `UiIcon iconAt(int width)` returns the icon only when `width >= ICON_CHARS + MIN_ITEM_CHARS`, else null;
    - `render(width)` cuts the text to `width - ICON_CHARS` when the icon is kept, else to `width`.
  - `static List<UiStatusPart> StatusLine.parts(List<String> indicators, List<Item> items, int maxChars)`: the indicators that `line` keeps, joined into one part with `indicator = true`, then one part per item shown. `line(…)` keeps its current signature, and its output for icon-less items does not change.
  - `UiHub.setStatusItem(String owner, String text, UiIcon icon)` and `UiHub.addCarRow(String owner, String rowKey, String label, String pageKey, UiIcon icon)`. The old signatures delegate with null.

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
Also:
- `StatusLineTest.lineWithoutIconsIsUnchanged`;
- `UiHubTest.rowsAndStatusPartsCarryIcons`;
- `UiHubTest.textOnlyLineIgnoresIcons`;
- `UiSnapshotTest.oldConstructorMakesOneTextPart`;
- `FakeUiTest.rendersIconTags`: FakeUi shows a row as `[star] Label`, and a FILE icon as `[png:<key>]`.

- [ ] **Step 2: Run the tests and verify they fail**

Run: `./gradlew :runtime:test :platform:test :adapter-fake:test`
Expected: FAIL (compilation: `UiStatusPart`, `StatusLine.parts` undefined)

- [ ] **Step 3: Implement** the constructors and accessors above.
- `UiHub.build` makes both outputs from one ordered item list: `parts` with icons, and `line` from the same items with `icon = null`.
- `UiImpl`'s row and status handles pass their `UiIcon` (null when inert).

- [ ] **Step 4: Run the tests and verify they pass**

Run: `./gradlew check && python3 -m unittest discover -s installer -p 'test_*.py'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add platform runtime adapter-fake
git commit -m "feat(runtime): icons on Car rows and status items in the UI snapshot"
```

---

### Task 4: Adapter icon ids and drawer row icons — SDK repo, host-only

**Files:**
- Create: `adapter-hqc/src/main/java/mibsdk/adapter/hqc/ui/IconIds.java`, `ImagePaths.java`, `BuiltinIcons.java`, `HmiWidgets.java`, `HmiWidgetState.java`
- Modify:
  - `HqcUiIds.java`;
  - `SdkCarScreenFactory.java`: `getImagePath`; bitmaps in `addRows`; static refs to the SDK rows;
  - `DrawerAssignment.java` (`iconAt`), `DrawerSlots.java`;
  - `UiApplier.java`: the combined job;
  - `SdkGlobals.java` (`ICON_IDS`, `builtinIcons`), `HqcPlatform.java`: reads the built-in index at start, on the SDK thread.
- Test: `adapter-hqc/src/test/java/mibsdk/adapter/hqc/ui/IconIdsTest.java`, `ImagePathsTest.java`, `BuiltinIconsTest.java`, `DrawerSlotsTest.java`, `UiApplierTest.java`, `SdkCarScreenFactoryTest.java`, `StockThreadCodeTest.java`

**Interfaces:**
- Consumes: `UiRow.icon()`, `UiIcon`, `IconIndex` (Tasks 1 and 3).
- Produces:
  - `HqcUiIds`: `ICON_ID_FIRST = 609100`, `ICON_ID_LAST = 609399`.
  - `public final class IconIds { IconIds(int first, int last); int idFor(String key, String absFile); String fileFor(int id); boolean exhausted(); }`.
    - `idFor` returns the id already given to `key`. Otherwise it returns the next unused id, or -1 when the range is spent. The applier logs the first -1 once as a WARN.
    - An id never changes its file.
    - It holds one session-lifetime instance, `SdkGlobals.ICON_IDS`. Writes are synchronized; stock threads read a volatile copy-on-write array without a lock.
  - `final class ImagePaths { static File imageRoot(File installRoot); static String relative(File imageRoot, File target) }`.
    - `imageRoot` = `<installRoot>/../images`, normalised.
    - `relative` gives the `../`-relative path between two absolute, normalised paths.
  - `final class BuiltinIcons { static BuiltinIcons read(File sdkIconDir); UiIcon get(String name, int size, boolean reflex); }`. It reads `icons-sdk/index.properties` (names `<name>-72`, `<name>-72r`, `<name>-20`) once, at `HqcPlatform.start()`, into `SdkGlobals.builtinIcons`.
  - `DrawerAssignment.iconAt(int slot)` returns `int[]` bitmaps, or null. `DrawerSlots.assign` resolves each row's `UiIcon` through `IconIds`:
    - STOCK: `{id(<name>-72), id(<name>-72r)}`;
    - FILE: `{id, id}`;
    - unknown, inert or spent: null.
  - `HmiWidgetState` is immutable and built on `mibsdk-ui`. Here it holds `int[][] slotBitmaps`. Task 5 adds the status layout and Task 7 the main-menu row. `HmiWidgets.apply(HmiWidgetState want)` runs on HMIEvent and applies only what differs from the last applied state.
  - `UiApplier`: one combined HMI job replaces `pageJob`.
    - It is posted when pages need rendering **or** the published `HmiWidgetState` differs from the applied one, and only once the first screen is up (the existing `SdkGlobals.uiRowModels != null` guard).
    - The job re-evaluates `needsRender(table.snapshot(), false)` and renders pages only if that is true. Then it calls `HmiWidgets.apply`. Each part has its own try/catch. `wake` is unchanged.
  - `SdkCarScreenFactory.getImagePath(int id, int terminal)`: for an id in `ICON_ID_FIRST..ICON_ID_LAST` with a known file, it returns `ImagePaths.relative(imageRoot, file)`; otherwise `super`. It catches Throwable.

- [ ] **Step 1: Write the failing tests**

```java
@Test void sameNameNewContentGetsANewIdAndTheOldIdKeepsItsFile() {
    IconIds ids = new IconIds(609100, 609101);
    assertEquals(609100, ids.idFor("aaaa000000000000", "/i/hello/aaaa000000000000.png"));
    assertEquals(609100, ids.idFor("aaaa000000000000", "/i/hello/aaaa000000000000.png"));
    assertEquals(609101, ids.idFor("bbbb000000000000", "/i/hello/bbbb000000000000.png")); // plugin update
    assertEquals("/i/hello/aaaa000000000000.png", ids.fileFor(609100));
    assertEquals(-1, ids.idFor("cccc000000000000", "/i/hello/cccc000000000000.png"));
}
@Test void installedIconsAreOneLevelUpFromTheImageRoot() {
    File root = ImagePaths.imageRoot(new File("/mnt/app/eso/hmi/lsd/mibsdk"));
    assertEquals("../mibsdk/icons/hello-menu/0123456789abcdef.png",
        ImagePaths.relative(root, new File("/mnt/app/eso/hmi/lsd/mibsdk/icons/hello-menu/0123456789abcdef.png")));
}
```
Also:
- `IconIdsTest.idsSurviveARuntimeRestartInTheSameSession`: two `UiApplier`s over `SdkGlobals.ICON_IDS` give the same id for the same key;
- `DrawerSlotsTest.iconsResolveToBitmapPairs`;
- `SdkCarScreenFactoryTest.getImagePathServesSdkIdsAndDefersTheRest`;
- `UiApplierTest.slotIconChangePostsOneJobAndAppliesBitmapsOnce`;
- `BuiltinIconsTest.readsTheSdkIndex`.

In `StockThreadCodeTest`, add `IconIds`, `ImagePaths`, `HmiWidgets` and `HmiWidgetState` to `STOCK_THREAD_FILES`, and `getImagePath` to the catch-Throwable list. `BuiltinIcons` does I/O and runs only on the SDK thread, so it is not on that list.

- [ ] **Step 2: Run the tests and verify they fail**

Run: `./gradlew :adapter-hqc:test`
Expected: FAIL (compilation: `IconIds`, `ImagePaths` undefined)

- [ ] **Step 3: Implement** as specified.
- `SdkCarScreenFactory.addRows` sets each slot's bitmaps from `SdkGlobals.uiSlots.iconAt(slot)` when the drawer is built. It keeps the 9 `MenuItemController` refs in a static volatile array for `HmiWidgets`.
- `HmiWidgets.apply` calls `setBitmaps` on the rows whose bitmaps changed. Per Boot A I3 the change shows at the next Car screen connect, and nothing tries to force it.

- [ ] **Step 4: Run the tests and verify they pass**

Run: `./gradlew check && python3 -m unittest discover -s installer -p 'test_*.py'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add adapter-hqc
git commit -m "feat(adapter-hqc): installed icons served by getImagePath with per-content ids; icons on Car drawer rows"
```

---

### Task 5: SDK status popup 609600 with icons, popup-65 fallback — SDK repo, host-only

**Files:**
- Create: `adapter-hqc/src/main/java/mibsdk/adapter/hqc/ui/StatusLayout.java`, `SdkStatusPopup.java`, `SdkPopupStatusLine.java`, `StatusSinks.java`
- Modify:
  - `HqcUiIds.java`;
  - `SdkCarModelBank.java` (the popup's models);
  - `SdkCarScreenFactory.java` (`getPartialPopupStubs`, `getPartialPopup`);
  - `StatusLineSink.java`, `Popup65StatusLine.java`;
  - `UiApplier.java` (status phase on parts), `HmiWidgetState.java`/`HmiWidgets.java` (layout);
  - `SdkGlobals.java` (`statusPopup` state).
- Test: `adapter-hqc/src/test/java/mibsdk/adapter/hqc/ui/StatusLayoutTest.java`, `StatusSinksTest.java`, `SdkCarActivatorTest.java` (models), `SdkCarScreenFactoryTest.java` (stub and build), `UiApplierTest.java`, `StockThreadCodeTest.java`

**Interfaces:**
- Consumes: `UiSnapshot.statusParts()` (Task 3); `IconIds`, `SdkGlobals.builtinIcons` and the combined HMI job (Task 4).
- Produces:
  - `HqcUiIds`, from `SpikeFactory`/`SpikePopup` and Boot A/B:
    - popup: `POPUP_SDK_STATUS = 609600`, `POPUP_SDK_X = 514`, `POPUP_SDK_W = 255`, `POPUP_SDK_H = 24`, `POPUP_SDK_SLOT = 7`, `POPUP_SDK_ZPM_SLOT = 12`, `POPUP_SDK_PRIORITY = 250`, `POPUP_BACKDROP_BITMAP = 251`;
    - layout: `STATUS_CELLS = 6`, `STATUS_PAD_PX = 6`, `STATUS_CHAR_PX = 10`, `STATUS_ICON_PX = 20`;
    - models: `STATUS_POPUP_VISIBLE_MODEL = MODEL_BASE + 12`; `STATUS_LABEL_MODEL_0 = MODEL_BASE + 13` (+0 is the indicators, +1..+6 the cells); `STATUS_FILE_ICON_MODEL_1 = MODEL_BASE + 20` (+0..+5). So `MODEL_COUNT = 26`.
  - `final class StatusLayout { static final class Cell { final int iconX; final int labelX; final int labelW; final int stockIconId; final String filePath; final String text; } static StatusLayout of(List<UiStatusPart> parts, IconIds ids, BuiltinIcons builtins); Cell indicators(); Cell cell(int i); boolean sameGeometry(StatusLayout o); }`.
    - Placement starts at `x = STATUS_PAD_PX`. Before each part after the first, `x += STATUS_CHAR_PX`.
    - A part with an icon gets `iconX = x`, then `x += STATUS_ICON_PX + 2`.
    - Then `labelX = x`, `labelW = POPUP_SDK_W - STATUS_PAD_PX - x`, and `x += text.length() * STATUS_CHAR_PX`.
    - A STOCK icon becomes `stockIconId`: the built-in `<name>-20`, drawn by id and tinted.
    - A FILE icon becomes `filePath`: the absolute installed path, drawn in its own colours.
    - A part beyond `STATUS_CELLS` is dropped.
  - `SdkStatusPopup.build(SdkCarScreenFactory f, int terminal)` follows `SpikePopup`'s recipe at (514, 0, 255, 24), slot 7:
    - a backdrop;
    - an indicator label;
    - 6 cells, each an id `IconController`, a file `IconController` on its ResourceLocatorModel, and a `LabelController` on its LabelModel.

    Bounds come from the latest `HmiWidgetState.status`. The popup is kept in a static volatile for `HmiWidgets`.
  - `SdkGlobals.statusPopup`: `0` unknown, `1` registered (the stub was added), `2` built, `-1` failed (the stub or the build threw).
  - `StatusLineSink.show(String)` becomes `void show(List<UiStatusPart> parts, String textLine)`; `hide()` is unchanged.
    - `SdkPopupStatusLine` writes the 7 label models and 6 locator models the way `RowModels` writes the bank's models. Locators are set with `setResourceLocator(-1, path, 1)` and cleared with `(-1, "", 0)`. It then sets the visible model.
    - `Popup65StatusLine.show(parts, textLine)` shows `textLine`.
  - `final class StatusSinks implements StatusLineSink { interface State { int get(); } StatusSinks(StatusLineSink sdkPopup, StatusLineSink popup65, State state) }`. In production the state reads `SdkGlobals.statusPopup`.
    - It delegates to the SDK popup while the state is `1` or `2`, else to popup 65.
    - On a switch it hides the old sink, but only if the SDK showed text on it.
  - `UiApplier` status phase:
    - it calls `status.show(current.statusParts(), current.statusLine())`, with the existing re-assert rule;
    - the layout (`StatusLayout.of`) goes into `HmiWidgetState`;
    - `HmiWidgets.apply` sets bounds and id bitmaps (an empty `int[0]` hides), but only when `sameGeometry` is false.

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
Also:
- `StatusLayoutTest.cellsFollowTheUnitGrid`: with the indicator "SAFE" followed by an item with an icon and the text "C801", `iconX == 6 + 40 + 10` and `labelX == iconX + 22`; a 7th item is dropped;
- `StatusLayoutTest.textChangeOfSameLengthKeepsGeometry`;
- `SdkCarScreenFactoryTest.stubsAddPopup609600InSlot7AndBuildFailureMarksFailed`;
- `SdkCarActivatorTest.popupModelsAreCreated`: one choice, 7 label and 6 locator models;
- `UiApplierTest.layoutChangePostsOneJobTextChangeOnlyWritesModels`.

- [ ] **Step 2: Run the tests and verify they fail**

Run: `./gradlew :adapter-hqc:test`
Expected: FAIL (compilation: `StatusSinks`, `StatusLayout` undefined)

- [ ] **Step 3: Implement** as specified.
- `getPartialPopupStubs` appends `new PartialPopupStub(609600, STATUS_POPUP_VISIBLE_MODEL, -1, 250, 7, 12, true, 7, terminal, 2, null, true, true)` to `super`'s array and sets `statusPopup = 1`. On Throwable it returns `super`'s array and sets `-1`.
- `getPartialPopup(609600, t)` builds the popup and sets `2`; on failure it sets `-1` and returns null.
- The popup-65 comments and `UiApplier`'s class doc now say popup 65 is only the fallback.

- [ ] **Step 4: Run the tests and verify they pass**

Run: `./gradlew check && python3 -m unittest discover -s installer -p 'test_*.py'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add adapter-hqc
git commit -m "feat(adapter-hqc): status line in SDK popup 609600 (slot 7) with icons; popup 65 as fallback"
```

- [ ] **Step 6 (pre-written fallback; run only if Task 12 R1 check S3 shows cells do not move live): hide and re-show around a layout change**
  - `UiApplier` keeps `int relayoutPhase` (on `mibsdk-ui` only): 0 idle, 1 hidden, 2 job posted. When the published layout's `sameGeometry` is false and the popup is up:
    - write `STATUS_POPUP_VISIBLE_MODEL := 0` and set phase 1;
    - on the next pass, publish the new `HmiWidgetState`, so the job applies the bounds, and set phase 2;
    - on the first pass after `HmiWidgets` reports that the layout was applied (a volatile `appliedLayoutSeq` it sets), write the visible model back to 1 and set phase 0.
  - Text-only changes never enter this path. The popup is blank for at most 2 × `AWAIT_MS` per layout change.
  - Test `UiApplierTest.layoutChangeHidesAppliesThenShows`: the model writes are `0`, then the job, then `1`, in that order; a text-only change writes no visibility.
  - Run: `./gradlew check`. Commit: `fix(adapter-hqc): re-show the status popup after a layout change (bench R1)`.

---

### Task 6: `HMISystem=` takeover with a stock fallback — SDK repo, host-only

**Files:**
- Create: `adapter-hqc/src/main/java/mibsdk/adapter/hqc/ui/SdkSystemActivator.java`, `SdkSystemScreenFactory.java`, `MainMenuKeyHandler.java`
- Modify:
  - `HqcUiIds.java`, `SdkGlobals.java`;
  - `ConfiguratorCore.java` (the forced-failure switch);
  - `RuntimeConfig.java` (`uiForceFailFile()` = `<persist>/dev/ui.forcefail`);
  - `adapter-hqc/build.gradle.kts` (add the host stock package `de/audi/tghu/system/hmi/`).
- Test: `adapter-hqc/src/test/java/mibsdk/adapter/hqc/ui/SdkSystemActivatorTest.java`, `SdkSystemScreenFactoryTest.java`, `SystemTakeoverSourceTest.java`, `StockThreadCodeTest.java`; `adapter-hqc/src/test/java/mibsdk/adapter/hqc/ConfiguratorIsolationTest.java`

**Interfaces:**
- Produces:
  - `HqcUiIds`: `SYSTEM_MODULE = 0`, `SYS_SCREEN_MAINWIZARD = 46`, `SYS_TEXT_PLUGINS = 90000`, `MAIN_MENU_WIDGET_ID = 11`, `WRENCH_ATLAS_CELL = 16`, `WRENCH_DECORATOR = 9`, `MAIN_MENU_INTERNAL_ID = 90100`, `KEY_ENTER = 17`.
  - `SdkGlobals`:
    - `MAIN_MENU_OFF = 0`, `MAIN_MENU_PENDING = 1`, `MAIN_MENU_ACTIVE = 2`, `MAIN_MENU_FAILED = 3`;
    - `static volatile int mainMenu`, which starts at OFF;
    - `static boolean drawerPluginsRowWanted()`: true when the state is OFF or FAILED;
    - `static volatile String uiForceFail`: `null`, `"factory"` or `"screen"`.
  - `public class SdkSystemActivator extends AbstractHMIActivator`, with `SpikeSystemActivator`'s shape: `super(0, "System", skin, new SystemModelBank())` and the stock `SystemConditionBank`.
    - `getScreenFactory()` gives the SDK factory, and sets `mainMenu = PENDING`, only when not in swdl, `uiEnabled` is true, `uiForceFail` is not `factory`, and construction succeeded.
    - Otherwise it gives `new SystemScreenFactory(fw)` and logs one STATUS line, `ui system=stock reason=<swdl|ui-off|forced|factory-failed>`. `mainMenu` stays OFF.
    - The package-private `AbstractScreenFactory factoryForTests()` returns the cached factory.
    - `uiForceFail == "factory"` makes it throw inside the try, so the real catch path runs.
  - `final class SdkSystemScreenFactory extends SystemScreenFactory`:
    - `createScreen(46, t)` calls `super` and finds the `MainWizardController`. It copies colour indices and insets from the last stock row and appends one `MenuItemController`: type 2, label `SYS_TEXT_PLUGINS`, widgetID 11, internal id 90100, `setKeyHandler(new MainMenuKeyHandler())`, `setVisible(!SdkGlobals.uiDown)`.
    - It sets the atlas mapping `[11] := {16, 9}` only when the array has at least 12 entries and entry 11 is `{11, 11}` or already `{16, 9}`. Otherwise it leaves the mapping alone and logs once.
    - It then sets `mainMenu = ACTIVE` and keeps the row in `static volatile MenuItemController row`.
    - On Throwable, or when `uiForceFail == "screen"`, it returns `super.createScreen(46, t)` (a fresh stock screen) and sets `mainMenu = FAILED`. FAILED is sticky for the boot: later builds of screen 46 stay stock.
    - `getText`/`getAltText` (and `getText(int,int)`) serve `SYS_TEXT_PLUGINS` from `SdkGlobals.uiSlots.labelAt(0)`, defaulting to `"Plugins"`.
  - `final class MainMenuKeyHandler implements IWidgetKeyHandler`: on key 17 it consumes the event and calls `UiInputs.rowPressed(SdkGlobals.UI, SdkGlobals.uiSlots, 0)`. Other keys do nothing. It never throws.
  - `ConfiguratorCore` reads the first line of `<persist>/dev/ui.forcefail`, trimmed. Only `factory` or `screen` count; the value goes into `SdkGlobals.uiForceFail`, and the configurator logs `configurator: ui.forcefail=<v>` when it is set.

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
Also:
- `SdkSystemScreenFactoryTest.appendsOnePluginsRowWithWidget11AndSetsTheWrench`: the child count is +1; the last row has widgetID 11, label 90000 and a `MainMenuKeyHandler`; mapping[11] is `{16, 9}`; the state is ACTIVE;
- `…forcedScreenFailureGivesAStockScreenAndFailedState`;
- `…unexpectedMappingIsLeftAlone`;
- `MainMenuKeyHandler`: SELECT puts one `ROW_SELECTED` for `mibsdk/plugins` in the ring and records a press;
- `ConfiguratorIsolationTest.forceFailFileIsRead`;
- `SystemTakeoverSourceTest.noStaticInitializers`: in `SdkSystemActivator.java` and `SdkSystemScreenFactory.java` there is no `static {` and no `static` field with `=`, other than `static final` primitives or Strings;
- `StockThreadCodeTest`: add the three new files, and add `createScreen`, `getText`, `getAltText`, `getScreenFactory` and `keyPressed` to the catch-Throwable list.

- [ ] **Step 2: Run the tests and verify they fail**

Run: `./gradlew :adapter-hqc:test`
Expected: FAIL (compilation: `SdkSystemActivator` undefined)

- [ ] **Step 3: Implement** as specified, copying the spike classes' structure, and add `de/audi/tghu/system/hmi/` to `hostStockJar.packages`. If screen 46 cannot be built on the host (the jxe2jar code is too broken even after repair), test the SDK part of `createScreen` through a package-private `appendRow(MainWizardController w)`, on a host-built `MainWizardController` with stock `MenuItemController` children. Do not stub stock classes.

- [ ] **Step 4: Run the tests and verify they pass**

Run: `./gradlew check && python3 -m unittest discover -s installer -p 'test_*.py'`
Expected: PASS. LinkCheck covers the new System classes.

- [ ] **Step 5: Commit**

```bash
git add adapter-hqc runtime/src/main/java/mibsdk/runtime/RuntimeConfig.java
git commit -m "feat(adapter-hqc): HMISystem= activator adds a Plugins row to the MENU ring, stock on any failure"
```

---

### Task 7: Plugins from the main menu, Car-drawer row only as fallback — SDK repo, host-only

**Files:**
- Modify:
  - `adapter-hqc/.../ui/PageScreens.java` (`openAllowed`);
  - `UiApplier.java` (the slot-0 rule; the main-menu row in `HmiWidgetState`);
  - `SdkCarModelBank.java` (slot 0's initial value);
  - `HmiWidgets.java`, `HmiWidgetState.java`.
- Test: `PageScreensNavigationTest.java`, `UiApplierTest.java`, `SdkCarActivatorTest.java`

**Interfaces:**
- Consumes: `SdkGlobals.mainMenu`, `drawerPluginsRowWanted()`, `SdkSystemScreenFactory.row` (Task 6); the combined job (Task 4).
- Produces:
  - `PageScreens.openAllowed(int currentId, boolean blocked, int pendingId, long sincePressMs)`: as today, and it also allows `currentId == HqcUiIds.SYS_SCREEN_MAINWIZARD`, under the same recent-press rule as a Car screen. BACK returns to the saved screen-46 data (Boot B C3).
  - `UiApplier.applyRows`: slot 0 is wanted visible iff `slots.visibleAt(0) && SdkGlobals.drawerPluginsRowWanted()`. This is evaluated on every pass, so a FAILED main menu brings the row back within `AWAIT_MS`.
  - `SdkCarModelBank.createModel` for slot 0: visible iff `!uiDown && drawerPluginsRowWanted()`.
  - `HmiWidgetState.mainMenuRowVisible` = `!down && mainMenu == ACTIVE`. `HmiWidgets.apply` calls `row.setVisible(v)` when it changes. Per Boot B, the change shows at the next ring re-layout.

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
Also:
- `UiApplierTest.downHidesTheMainMenuRowAndClearsStatusCells`;
- `UiApplierTest.mainMenuOffKeepsTheDrawerRow`;
- `PageScreensNavigationTest.openFromTheMainMenuNeedsARecentPress`: screen 46 with a press 500 ms ago is allowed; screen 46 with no press is dropped; screen 45 is dropped;
- `SdkCarActivatorTest.slot0StartsHiddenWhileTheMainMenuIsActive`.

- [ ] **Step 2: Run the tests and verify they fail**

Run: `./gradlew :adapter-hqc:test`
Expected: FAIL (assertions: slot 0 is still visible; an open from screen 46 is dropped)

- [ ] **Step 3: Implement** the four rules above. Update the class docs of `PageScreens` (which screens a page may open from) and `UiApplier` (the slot-0 rule).

- [ ] **Step 4: Run the tests and verify they pass**

Run: `./gradlew check && python3 -m unittest discover -s installer -p 'test_*.py'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add adapter-hqc
git commit -m "feat(adapter-hqc): Plugins opens from the MENU ring; the Car-drawer row only while the ring has none"
```

---

### Task 8: mibpkg manages `HMISystem=` like `HMICar=` — mibpkg repo, worktree `mibpkg.worktrees/hmisystem` (branch `feat/hmisystem` from `feat/statusline-cleanup`), host-only

**Files:**
- Modify: `src/manifest.rs` (`SdkSpec`), `src/mibsdk/bundles.rs`, `src/mibsdk/sdk_pkg.rs`, `src/cli.rs` (`sdk status`), `Cargo.toml` (`version = "0.9.0"`), `README.md` (the sdk section)
- Create: `tests/fixtures/manifests/sdk-mibsdk-system.toml`: the existing fixture plus the two new keys and the third owned prefix
- Test: `tests/mibsdk_bundles.rs`, `tests/sdk_pkg.rs`, `tests/manifest_kinds.rs`, `tests/cli_sdk.rs`

**Interfaces:**
- Produces:
  - `SdkSpec { …, #[serde(default)] pub hmisystem_stock: Option<String>, #[serde(default)] pub hmisystem: Option<String> }`.
    - The two keys go together; validation fails with `[sdk] hmisystem and hmisystem_stock go together`.
    - Each is a Java class name.
    - `bundle`'s key may not be `HMISystem`.
  - `bundles::check_line(text: &[u8], key: &str, what: &str, allowed: &[&str]) -> Result<()>`: today's `check_hmicar`, generalised over the key. It reads keys the way `Properties.load` does, as now, through `key_lines(lines, key)`. `check_hmicar` stays as a wrapper.
  - `bundles::generate`, when `sdk.hmisystem` is set, rewrites the single `HMISystem=` line exactly as it does `HMICar=`:
    - it accepts the stock or the SDK value, so the output is a fixed point;
    - it keeps the line break;
    - it refuses a non-stock value, a duplicate key or a bare CR with `HMISystem is not stock (…); refusing`.

    When `hmisystem` is unset, the line is copied byte for byte.
  - `sdk_pkg::check` runs the same three checks on `HMISystem=` as on `HMICar=` when the spec has it: on an update, the live list has the stock or the SDK line; the stock backup has the stock line; on a fresh install, the live list has the stock line.
  - `references_sdk` is also true when the spec has `hmisystem` and the live `HMISystem=` line is not stock. The firmware-changed refusal and the stale-record logic then cover it.
  - `sdk status` prints the live `HMISystem=` line after `HMICar=`.

- [ ] **Step 1: Write the failing tests**

```rust
#[test]
fn hmisystem_substituted_once_keeping_crlf_and_fixed_point() {
    let out = gen_with(&stock(), spec_system(), true);
    assert_eq!(count(&out, b"HMISystem=mibsdk.adapter.hqc.ui.SdkSystemActivator\r\n"), 1);
    assert_eq!(count(&out, b"HMISystem=de.audi.tghu.system.hmi.Activator"), 0);
    assert_eq!(gen_with(&out, spec_system(), true), out);
}
```
Also:
- `nonstock_hmisystem_refused`;
- `spec_without_hmisystem_leaves_the_line_alone`;
- `manifest_kinds::hmisystem_keys_go_together`;
- `sdk_pkg::sdk_remove_restores_stock_bundles_byte_for_byte_with_hmisystem`;
- `sdk_pkg::install_refuses_a_foreign_hmisystem_line`;
- `sdk_pkg::firmware_changed_remove_refuses_without_force_when_only_hmisystem_references_the_sdk`;
- `sdk_pkg::update_from_an_sdk_without_hmisystem_takes_the_line_over`;
- `sdk_pkg::owned_prefix_system_hmi_refuses_a_foreign_jar`;
- `cli_sdk::status_prints_both_lines`.

- [ ] **Step 2: Run the tests and verify they fail**

Run: `cargo test --test mibsdk_bundles --test sdk_pkg --test manifest_kinds --test cli_sdk`
Expected: FAIL (unknown field `hmisystem`; no `HMISystem=` rewrite)

- [ ] **Step 3: Implement** as specified, reusing the `HMICar=` code paths. Do not write a second copy of the line logic.

- [ ] **Step 4: Run the tests and verify they pass**

Run: `cargo test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src tests Cargo.toml Cargo.lock README.md
git -c user.name=complex256 -c user.email=me@complex256.dev commit -m "feat(sdk): manage the HMISystem= line like HMICar= (stock check, byte-identical restore)"
```

---

### Task 9: mibpkg installs, validates and removes icons — mibpkg repo, same worktree, host-only

**Files:**
- Create: `src/mibsdk/icons.rs`, `tests/mibsdk_icons.rs`, `tests/fixtures/icons/*.png` (copies of the SDK repo's `build-logic/src/test/resources/png/`)
- Modify:
  - `src/manifest.rs`: `PluginSpec.icons`, `SdkSpec.icons`, and their validation;
  - `src/mibsdk/plugin_pkg.rs`: deploy, redeploy and remove;
  - `src/mibsdk/sdk_pkg.rs`: deploy and undeploy;
  - `src/mibsdk/mod.rs`: the constants `ICONS = "/mnt/app/eso/hmi/lsd/mibsdk/icons"` and `SDK_ICONS = "/mnt/app/eso/hmi/lsd/mibsdk/icons-sdk"`;
  - `README.md`.
- Test: `tests/mibsdk_icons.rs`, `tests/sdk_pkg.rs`, the plugin package tests (the existing file that covers `plugin_pkg::deploy`/`remove`), `tests/manifest_kinds.rs`

**Interfaces:**
- Produces:
  - `PluginSpec { …, #[serde(default)] pub icons: Vec<String> }` and `SdkSpec { …, #[serde(default)] pub icons: Vec<String> }`.
    - Names match `[a-z0-9-]{1,32}`, with no duplicates; at most 8 for a plugin and 32 for the SDK.
    - Each name needs the package file `icons/<name>.png`. A missing file fails manifest validation.
  - `icons::png_check(bytes: &[u8], max_px: u32) -> Result<(u32, u32)>`: the same rules as `PngCheck`. CRC via `flate2::Crc`, inflate via `flate2::read::ZlibDecoder` with an expected+1 read cap.
  - `icons::install(ctx: &Ctx, dir_abs: &str, pkg_dir: &Path, names: &[String]) -> Result<()>`:
    - it checks every icon before it writes anything;
    - then it writes `<dir_abs>/<sha16>.png`, where `sha16` is the first 16 lowercase hex characters of SHA-256 (`sha2`);
    - then it writes `index.properties` (`<name>=<sha16>,<w>,<h>`, sorted, LF);
    - last, it removes files in the dir that the new index does not list.

    It writes through `put`, under /mnt/app read-write. An empty `names` removes the dir.
  - `icons::remove(ctx: &Ctx, dir_abs: &str) -> Result<()>`.
  - Wiring:
    - plugin deploy and redeploy call `install(ICONS/<id>, …)` after the jar;
    - plugin remove calls `remove(ICONS/<id>)` after the jar and the ack (the plugin is stopped);
    - SDK deploy calls `install(SDK_ICONS, …)`; SDK undeploy calls `remove(SDK_ICONS)` and also `remove(ICONS)` once no plugins remain;
    - `sdk push` (jar only) leaves the icons alone.
  - A check failure aborts the install with `icon <name>: <rule>; nothing changed`, and the package state is untouched.

- [ ] **Step 1: Write the failing tests**

```rust
#[test]
fn png_check_refuses_every_malformed_or_oversized_file() {
    assert_eq!(icons::png_check(&fixture("ok-20.png"), 72).unwrap(), (20, 20));
    for bad in ["truncated.png", "bad-crc.png", "palette.png", "depth16.png", "interlaced.png",
                "apng.png", "73x20.png", "1x100000.png", "idat-bomb.png", "too-big.png", "empty.png"] {
        assert!(icons::png_check(&fixture(bad), 72).is_err(), "{bad}");
    }
}
```
Also:
- `plugin_install_writes_content_addressed_icons_and_index`: the index line is `hello=<sha16>,72,72`, and the file bytes equal the source;
- `plugin_update_with_new_content_replaces_the_file_and_index`: the old file is gone;
- `plugin_install_with_a_bad_icon_writes_nothing`;
- `plugin_remove_deletes_its_icon_dir`;
- `sdk_install_writes_icons_sdk_and_uninstall_removes_it`;
- `manifest_kinds::icon_names_are_checked_and_need_their_files`.

- [ ] **Step 2: Run the tests and verify they fail**

Run: `cargo test --test mibsdk_icons --test sdk_pkg --test manifest_kinds`
Expected: FAIL (unknown field `icons`; `icons` module missing)

- [ ] **Step 3: Implement** as specified.

- [ ] **Step 4: Run the tests and verify they pass**

Run: `cargo test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src tests README.md
git -c user.name=complex256 -c user.email=me@complex256.dev commit -m "feat(sdk): install, validate and remove plugin and SDK icons (content-addressed, indexed)"
```

---

### Task 10: SDK and plugin packages, dev tooling and docs — SDK repo, host-only (after Tasks 2, 8, 9)

**Files:**
- Modify:
  - `build-logic/src/main/java/mibsdk/build/MpkStage.java` and the plugin package task (`mibPackage`), with `MpkStageTest.java`;
  - `dist/build.gradle.kts` (stage `dist/icons/*.png` as `icons/<name>.png`);
  - `installer/mibsdk-dev`, `installer/test_mibsdk_dev.py`;
  - `docs/superpowers/plans/2026-10-20-mibsdk-ui.md`: one line under its Decisions saying the Car-drawer Plugins entry and popup 65 are superseded by 2026-10-24-menu-and-icons;
  - `spikes/hmi-ui/README.md`: one line saying that after this feature `run.sh install` refuses by design, because `HMISystem=` is no longer stock.

**Interfaces:**
- Consumes: mibpkg 0.9.0's `[sdk] hmisystem_stock`/`hmisystem`/`icons` and `[plugin] icons` (Tasks 8 and 9).
- Produces:
  - The staged SDK manifest has:
    - `api = 3`;
    - `hmisystem_stock = "de.audi.tghu.system.hmi.Activator"`;
    - `hmisystem = "mibsdk.adapter.hqc.ui.SdkSystemActivator"`;
    - `owned = ["de/audi/tghu/car/hmi/", "de/audi/atip/model/CarModelBank", "de/audi/tghu/system/hmi/"]`;
    - `icons = [<the 24 built-in names, sorted>]`, with the files staged as `icons/<name>.png`.
  - A plugin package stages `plugin.json`'s `icons` as `[plugin] icons` and `icons/<name>.png`, taken from `src/main/icons/` and checked with `PngCheck` at stage time.
  - `mibsdk-dev ui-forcefail factory|screen|off` writes or removes `/mnt/persist/mibsdk/dev/ui.forcefail` over SSH. It needs no remount, because persist is read-write, and it says that a restart is needed.
  - `mibsdk-dev status` prints the `HMISystem=` line.

- [ ] **Step 1: Write the failing tests:**
  - `MpkStageTest.sdkManifestManagesHMISystemAndShipsIcons`: the values above, verbatim;
  - `MpkStageTest.pluginPackageStagesDeclaredIcons`: a bad PNG fails staging;
  - `test_mibsdk_dev.UiForceFailTest`: the ssh command lines for each argument; a bad argument exits 2 and sends nothing.
- [ ] **Step 2: Run them and verify they fail:** `./gradlew :build-logic:test && python3 -m unittest discover -s installer -p 'test_*.py'`. Expected: FAIL.
- [ ] **Step 3: Implement** the manifest lines, the staging, the dev command and the two doc lines.
- [ ] **Step 4: Run and verify:**
  - `./gradlew check && python3 -m unittest discover -s installer -p 'test_*.py'`;
  - `./gradlew :dist:mibpkgPackage -Pmibsdk.mibpkg=<mibpkg 0.9.0 built from Tasks 8–9>` builds a package that mibpkg 0.9.0 accepts.

  Expected: PASS.
- [ ] **Step 5: Commit:** `git add build-logic dist installer docs spikes/hmi-ui/README.md && git commit -m "build: SDK package takes over HMISystem= and ships its icons (api 3); plugin packages ship icons; mibsdk-dev ui-forcefail"`

---

### Task 11: Reference plugins use icons — SDK repo, host-only

**Files:**
- Modify:
  - `plugins/hello-menu`:
    - `plugin.json` gets `api: 3` and `icons: ["hello", "hello-20"]`;
    - `src/main/icons/hello.png` (72×72) and `hello-20.png` (20×20), from `tools/make_builtin_icons.py --plugin-sample`;
    - the Car row uses `ui.icon("hello")` and the status item `ui.icon("hello-20")`;
    - a page entry "Next icon" swaps the row icon between `hello` and `StockIcons.STAR`. Per Task 1, the change shows at the next Car connect.
  - `plugins/daemon-status`: api 3; the status item shows `StockIcons.DOT` while every daemon is up, and `StockIcons.CROSS` while one is down.
- Test: `plugins/hello-menu/src/test/...`, `plugins/daemon-status/src/test/...` (over FakeUi's icon tags)

**Interfaces:** Consumes only the Task 1 API. A plugin built against api 3 does not load on an api-2 SDK (the manifest check), as intended.

- [ ] **Step 1: Write the failing tests:**
  - `HelloMenuTest.rowAndStatusCarryIcons`: FakeUi shows `[png:<key>] Hello` and the status part with a png tag; "Next icon" switches the row tag to `[star]`;
  - `DaemonStatusTest.statusIconFollowsDaemonState`.
- [ ] **Step 2: Run them and verify they fail:** `./gradlew :plugins:hello-menu:test :plugins:daemon-status:test`. Expected: FAIL.
- [ ] **Step 3: Implement** the plugin changes.
- [ ] **Step 4: Run and verify:** `./gradlew check`; `./gradlew :plugins:hello-menu:mibPackage :plugins:daemon-status:mibPackage -Pmibsdk.mibpkg=<mibpkg 0.9.0>` builds both packages. Expected: PASS.
- [ ] **Step 5: Commit:** `git add plugins && git commit -m "feat(plugins): hello-menu and daemon-status show icons"`

---

### Task 12: Bench E2E — BENCH (restart budget: 3 planned, 4 at most)

**Files:**
- Create: `docs/bench-e2e-menu-icons.md`, `docs/bench-e2e/menu-icons-*.jpg`, `docs/bench-e2e/menu-icons-log-r{1,2,3}.txt`

**Before any restart (no restart):**
- [ ] Check the starting state:
  - `curl -s 10.20.0.23:8099/status` → `bridge_connected:true`;
  - `mibpkg sdk status` on the unit shows `HMICar=…SdkCarActivator` and the stock `HMISystem=` line;
  - `spikes/hmi-ui/run.sh status` shows no spike files.

  Save a host copy of the live bundles file as `bundles.pre.properties`.
- [ ] Install:
  - upgrade mibpkg to 0.9.0, as `docs/bench-e2e-ui.md` "Builds and deploys" did for 0.8.1;
  - install `mibsdk` (Task 10), `hello-menu` and `daemon-status` (Task 11) through mibpkg.

  Check:
  - `mibpkg sdk status` shows `HMICar=…SdkCarActivator`, `HMISystem=mibsdk.adapter.hqc.ui.SdkSystemActivator` and `api: 1..3`;
  - `cmp` of the live list against `bundles.pre.properties` differs only in the `HMISystem=` line;
  - `ls /mnt/app/eso/hmi/lsd/mibsdk/icons/hello-menu/ /mnt/app/eso/hmi/lsd/mibsdk/icons-sdk/` shows the `<sha16>.png` files and an `index.properties`.

**R1: happy path (restart 1).** Run `installer/mibsdk-dev restart`, then `ssh rpi-bench keypanel up`. If the HMI stays black more than 5 min after SSH answers, run the emergency revert below and stop.
- [ ] C0: the home frame looks stock; `vol_up` shows the volume popup; the status bar has its clock. The log has `ui system=sdk` and `ui car=sdk`, and no `ui system=stock`.
- [ ] M1: `menu` → the ring ends …Settings, then **Plugins** with the grey wrench. Focus it → red wrench (`menu-icons-ring.jpg`, `-ring-focus.jpg`).
- [ ] M2: `knob_select` → the Plugins page (`-plugins-from-menu.jpg`). `back` → the ring, with focus on Plugins (`-back-to-ring.jpg`). MENU on the page is a no-op (Boot B C4).
- [ ] M3: `car`, then `menu_left` → the drawer has **no** Plugins row and ends with the plugin rows (`-drawer.jpg`).
- [ ] I1: Hello's row shows its PNG icon, tinted, with the icon reused as its reflection (`-drawer-hello-icon.jpg`). The log has `getImagePath 6091xx -> ../mibsdk/icons/hello-menu/<sha16>.png`.
- [ ] I2: run hello-menu's "Next icon", then Media → Car → drawer. The row shows the built-in star with its reflection (`-drawer-icon-next.jpg`).
- [ ] S1: popup 609600 sits at the top right on home, Car, Media and the ring (`-status-{home,car,media,ring}.jpg`). It shows the indicators, hello's PNG icon (in its own colours) with its text, and daemon-status's built-in dot (tinted) with its text. Popup 65 shows nothing from the SDK.
- [ ] S2: with 609600 up, turn on the REM/GEM engineering Mem/CPU line (popup 65). Both show (slot 7, `-status-with-p65.jpg`). Close the engineering menu with its combo.
- [ ] S3: stop a daemon, so daemon-status's icon turns to a cross, and later start it again. The cells move within ~2 s, with no stale cell (`-status-relayout.jpg`).
  - **If the cells do not move until the next screen change**, run Task 5 Step 6 on the host now (it is pre-written).
  - Rebuild and reinstall `mibsdk` through mibpkg before R2. The fix is then checked at R3's restart, so the budget stays at 3.
- [ ] P1: arm the bike rack on the bench as in `docs/bench-e2e-ui.md` Step 7 → `PDC R OFF` shows whole beside the items (`-status-pdc.jpg`). Disarm.
- [ ] Save `/tmp/mibsdk.log` as `menu-icons-log-r1.txt`.

**R2: forced failure plus safe mode (restart 2).** Run `mibsdk-dev ui-forcefail factory` and `mibpkg sdk safe-mode on`, restart, then `keypanel up`.
- [ ] F1: the HMI fully works (`-r2-ring-stock.jpg`, `-r2-radio.jpg`):
  - home and `vol_up` work;
  - the MENU ring is **stock**: it ends at Settings, with no Plugins row;
  - Radio and Navigation open from the ring.

  The log has `configurator: ui.forcefail=factory` and `ui system=stock reason=forced`.
- [ ] F2: `car`, then `menu_left` → the drawer **has** its Plugins row back, and the row opens the Plugins page with the safe-mode notice (`-r2-drawer-plugins.jpg`).
- [ ] F3: popup 609600 shows `SAFE` (`-r2-status-safe.jpg`). It is a must-show indicator, and the popup belongs to the Car side, which the System fallback does not touch.
- [ ] Save the log as `menu-icons-log-r2.txt`.

**R3: back to normal (restart 3).** Run `mibsdk-dev ui-forcefail off` and `mibpkg sdk safe-mode off`, restart, then `keypanel up`.
- [ ] N1 (`-r3-ring.jpg`, `-r3-drawer.jpg`, `-r3-status.jpg`):
  - the ring has Plugins with the wrench again;
  - the drawer has no Plugins row;
  - the status popup shows both plugins' items.

  If Task 5 Step 6 was applied, repeat S3 here (`-r3-status-relayout.jpg`). Save the log as `menu-icons-log-r3.txt`.

**R4 (optional; only if R1–R3 passed and the owner agrees to the extra time).** Run `ui-forcefail screen` and restart.
- The drawer shows Plugins until the first MENU.
- After MENU the ring is stock, and within 1 s the drawer has Plugins: FAILED is sticky.
- `ui-forcefail off` then needs one more restart, so R4 really costs two restarts. Skip it unless the owner asks; the Task 6 host test covers this path.

**Emergency revert (blank HMI after any restart):** SSH still works (QNX).
1. `mount -uw /mnt/app`.
2. Copy mibpkg's stock backup of the bundles file over `/mnt/app/eso/hmi/lsd/bundles_dsitracer_enabled.properties`, then `chmod 777` it.
3. `sync`, then `mount -ur /mnt/app`, then `slay -s11 j9`.
4. Run `mibpkg sdk status` and report BLOCKED with the log.

- [ ] Write `docs/bench-e2e-menu-icons.md`:
  - the findings table and the frames;
  - the restart times;
  - the unit's state at the end: SDK, hello-menu and daemon-status installed, the ring with Plugins, the unit on its home screen.
- [ ] Commit: `git add docs/bench-e2e-menu-icons.md docs/bench-e2e && git commit -m "docs: bench E2E for the main-menu entry, row icons and the status popup"`

---

## Decisions in this plan

[decided] comes from the owner or a controller ruling; [my call] is a planning choice open to review.

**Main menu**
- [decided] The Plugins entry is a row in the top-level MENU ring, through the `HMISystem=` activator takeover (the `HMICar=` pattern). It uses the stock wrench: atlas cell 16, through the main-wizard icon decorator mapping.
- [my call] widgetID **11**, with mapping `[11] := {16, 9}`, exactly as Boot B proved it. The research note's `[10]` was a slip in its plan, corrected in §9. The mapping is changed only while entry 11 is still the stock `{11, 11}`.
- [my call] The row is appended after Settings, the position Boot B proved. Reordering the ring is not attempted.
- [decided] "Main menu only": the SDK's Plugins row leaves the Car drawer. Plugins' own Car rows are unaffected.
- [decided, controller ruling] The drawer row comes back whenever the System takeover is not active:
  - `mainMenu` OFF: the stock fallback on any Throwable, swdl, the UI off or an unsupported build, or mibpkg not managing `HMISystem=`;
  - FAILED: a screen-46 build that failed. FAILED is sticky for the boot.
- [my call] In the PENDING state (the SDK System factory is up, but MENU has not been opened yet this boot) the drawer row is already hidden. The ring builds its row on the first MENU, and a failure there brings the drawer row back within one applier pass.

**Icons**
- [decided] Row icons: Car-drawer plugin rows can carry a stock icon or a plugin PNG, served through `getImagePath` with fresh ids per content. They are near-static: a change shows at the next Car reconnect.
- [decided, controller ruling 2] Plugin icons are declared in the plugin package.
  - mibpkg extracts them at install to `/mnt/app/eso/hmi/lsd/mibsdk/icons/<plugin-id>/`, which is `../mibsdk/…` from `ImageRoot`, the depth the `../mibsdk-spike/` spike proved. mibpkg removes them on remove and uninstall.
  - Icons are static per installed version: a new icon means a new package version.
  - Ids follow a content hash, so the renderer cache never serves a stale image.
  - PNGs are validated at build and again at install.
  - Runtime-generated plugin icons are out of scope; the runtime reads only the index.
- [my call] Files are content-addressed (`<sha16>.png`, SHA-256) and found through `index.properties`. A changed icon therefore gets a new path as well as a new id, and an old id never points at new bytes. The adapter hands out ids from the content key (session-lifetime allocation). The id is not `hash mod range`, which would collide.
- [my call] A plugin PNG on a drawer row is drawn as `{id, id}`: the icon doubles as its reflection. The built-in icons ship a real 72×68 reflection.
- [my call, owner informed] The status line's "stock" icons are the SDK's own named set (`StockIcons`: dot, circle, star, triangle, square, check, cross, plug), shipped in the SDK package and drawn by id, so they are tinted. Firmware ids are not used: the firmware has no usable ≤ 20 px glyphs with alpha.

**Status line**
- [decided] The status line moves to SDK popup 609600 in slot 7, where it coexists with popup 65. Items may carry a small icon, and the fair share counts the icon's width. `SAFE` / `PDC R OFF` stay must-show. Popup 65 is not written while 609600 works, and its text is the fallback.
- [my call] An icon is worth 2 characters (`ICON_CHARS`). An item's icon is dropped before its text would fall below 3 characters.
- [my call] The popup keeps Boot B's geometry, (514, 0, 255, 24): one indicator label and 6 item cells on a 10-px character grid. Cell bounds and id bitmaps go through the one coalesced HMI job; text and file icons are model writes.
- [decided, controller ruling 3] Live re-layout is checked at R1. The hide/re-show fallback is pre-written (Task 5 Step 6), and if it is needed it is verified at R3, inside the budget.

**System takeover and the bench**
- [decided] mibpkg manages `HMISystem=` exactly like `HMICar=`: a stock-value check, a byte-identical restore, and refusal on unknown builds and on a firmware change without `--force`. The activator is pure stock in swdl and on any Throwable, and it has no throwing static initialisers.
- [my call] The `[sdk]` keys `hmisystem_stock`/`hmisystem`/`icons` and `[plugin] icons` are optional. An SDK package without the first two leaves `HMISystem=` stock (the "mibpkg declined" fallback). They arrive with mibpkg 0.9.0.
- [decided] The failure path gets a deliberate bench test: R2 forces the System factory to fail (`ui.forcefail=factory`), then checks that the HMI is stock and the Car-drawer Plugins row is back.
- [my call] The forced-failure switch is a file in persist, `/mnt/persist/mibsdk/dev/ui.forcefail` (`factory` or `screen`). Only the configurator reads it, and `mibsdk-dev ui-forcefail` sets it. R2 combines it with safe mode, which proves `SAFE` in the new popup without an extra restart.
- [decided] The restart budget is 3, at most 4.
- [my call] API level 3 is additive: `Icon`, `StockIcons`, `Ui.icon`/`stockIcon`, icon overloads of `carMenuRow`/`statusItem`, `StatusItem.set(text, icon)`, `Features.UI_ICONS`, and the manifest field `icons`.

**Open points for the owner:** none.
