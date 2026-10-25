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
- SDK, strictly in sequence: 1 → 2 → 3 → 4 → 5 → 6 → 7. Tasks 4–7 share `HqcUiIds`, `SdkGlobals`, `UiApplier` and `StockThreadCodeTest`, and Task 7 needs Task 4's HMI job.
- mibpkg: 8 → 9, in parallel with the SDK tasks, but Task 9 starts only after Task 2 is committed, because it copies Task 2's named PNG fixtures.
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
- Image ids: one per `<owner>/<sha16>` key per HMI session, never reused or re-pointed. The owner is the plugin id, or `mibsdk` (the reserved plugin id) for the built-in set, so identical bytes in two packages never share an id or a file. The range is `609100..609399` (Car module, served by `SdkCarScreenFactory.getImagePath` from a precomputed relative path).
- Icon changes on Car-drawer rows show at the next Car screen connect, not live (Boot A I3). The API says so.

**Status line** (Plan 2 rules unchanged, plus icons)
- `SAFE` and `PDC R OFF` are must-show. Fair share, order and the user controls are unchanged. An icon counts as `StatusLine.ICON_CHARS = 2` characters of its item's width.
- The line is drawn in popup **609600, slot 7**, at (514, 0, 255, 24), zpm slot 12, priority 250 (Boot B).
- Once 609600 is in use, the SDK never writes popup 65. If 609600 cannot be registered or built, the line goes to popup 65 as text only. That failure is sticky for the boot.
- Boot window (documented, no timer): until the popup manager has collected the stub, the line goes to popup 65, and the switch to 609600 then hides it.

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
2. **A plugin update changes an icon's content under the same name, or two packages ship identical bytes.** The new content needs a new id, and the old id must never be re-pointed, even while the old drawer texture is still cached. Identical bytes in two packages get two ids and two files.
   - **Test:** Task 4 `IconIdsTest.sameNameNewContentGetsANewIdAndTheOldIdKeepsItsFile`, `IconIdsTest.identicalBytesInTwoPackagesGetTwoIds`.
3. **The System takeover fails after the Car drawer was built**: screen 46 throws on its first MENU press, after the drawer has already hidden its Plugins row. The drawer row must come back within one applier pass, and the ring must be stock.
   - **Test:** Task 7 `UiApplierTest.drawerPluginsRowReturnsWhenTheMainMenuFails`.
4. **The status popup cannot be built**: the stub is registered, but `getPartialPopup(609600)` throws on the first show. The line must reach popup 65 within one pass, and popup 65 must never be written while 609600 works.
   - **Test:** Task 5 `StatusSinksTest.fallsBackToPopup65WhenThePopupFailsAndNeverWritesItOtherwise`, `UiApplierTest.sinkSwitchReshowsWithinOnePass`.
5. **A plugin installed by `mibpkg sdk push`** (jar only, no icons on the unit), **or removed while its row is cached.**
   - An icon that is declared but not installed must give an inert `Icon` (the row or item shows without it) and one WARN. It must not throw into the plugin, and the HMI must never be asked for a path that does not exist.
   - Remove deletes the icon dir only after the plugin is gone, whatever the ack outcome, and a re-run finishes a remove that failed half-way.
   - **Test:** Task 1 `UiImplTest.declaredButNotInstalledIconIsInert`; Task 9 `plugin_remove_deletes_its_icon_dir`, `remove_rerun_finishes_an_orphaned_icon_dir`.

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
  - `Features.UI_ICONS = "ui.icons"`. Without it, icons are still validated exactly as with it (undeclared names, foreign handles and the 20-px status limit all throw), but the row or item is published without its icon. Only drawing is gated. Task 4 advertises the feature on the unit.
  - Plugin manifest (`plugin.json`): `"icons": ["hello", "hello-20"]`. Optional; names match `[a-z0-9-]{1,32}`; at most 8; no duplicates. Accepted only with `"api": 3`.
- Produces (platform):
  - `public final class UiIcon { static final int STOCK = 0, FILE = 1; static UiIcon stock(String name); static UiIcon file(String owner, String path, String key, int width, int height); int kind(); String name(); String owner(); String path(); String key(); int width(); int height(); }`, with equals/hashCode on (kind, name or key).
  - `public final class IconIndex { static IconIndex read(String owner, File dir); UiIcon get(String name); }`. `owner` is the plugin id, or `mibsdk` (the reserved plugin id) for the built-in set, and goes into each `UiIcon`. It reads `<dir>/index.properties`. A missing, unreadable or malformed file, or a bad line, gives an empty entry or index and never throws. The path is `<dir>/<sha16>.png`.
- Consumes: `UiImpl`'s existing `PageHandle`/`RowHandle`/`StatusHandle`. `UiImpl` accepts an `Icon` only if it is its own `IconHandle` (holding a `UiIcon`, or null when inert), as it does for `PageHandle`.
- Changes: the `UiImpl` constructor keeps its six parameters and gains `List<String> declaredIcons, File iconDir` at the end. `PluginContextImpl` passes the manifest's `icons` and `RuntimeConfig.pluginIconDir(id)`.

- [ ] **Step 1: Write the failing tests**

```java
@Test void readsTheIndexAndIgnoresBadLines(@TempDir File d) throws Exception {
    write(new File(d, "index.properties"), "hello=0123456789abcdef,72,72\nbad=xyz,1\nsmall=fedcba9876543210,20,20\n");
    IconIndex i = IconIndex.read("hello", d);
    assertEquals(new File(d, "0123456789abcdef.png").getPath(), i.get("hello").path());
    assertEquals(20, i.get("small").width());
    assertNull(i.get("bad"));
    assertNull(IconIndex.read("hello", new File(d, "missing")).get("hello"));
}
```
Also:
- `UiImplTest.declaredButNotInstalledIconIsInert`;
- `UiImplTest.undeclaredOrForeignIconThrows`;
- `UiImplTest.bigIconOnAStatusItemThrows` (72 px on `statusItem(text, icon)` throws; on `carMenuRow` it is fine). It runs once with `UI_ICONS` enabled and once without, and both runs throw alike;
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
- Test: `build-logic/src/test/java/mibsdk/build/PngCheckTest.java`, `installer/test_make_builtin_icons.py`
- Create (committed fixtures, the shared vectors Task 9 copies): `build-logic/src/test/resources/png/`
  - valid: `ok-20.png`, `ok-72.png`;
  - invalid: `empty.png`, `too-big.png` (32769 bytes), `truncated.png`, `bad-crc.png`, `palette.png`, `rgb.png` (colour type 2), `depth16.png`, `interlaced.png`, `apng.png` (has `acTL`), `73x20.png`, `1x100000.png`, `ihdr-not-first.png`, `iend-not-last.png`, `short-idat.png`, `compression1.png`, `filter1.png`, and `idat-bomb.png`. The bomb is a 72×72 RGBA header whose IDAT inflates to 1 MB of zeros. The file is under 32 KiB, so only the inflate cap refuses it.
  - Generated once by `tools/make_builtin_icons.py --fixtures DIR`, and the generator's output is pinned by the Python test.

**Interfaces:**
- Produces: `public final class PngCheck { static final class Info { final int width, height, colorType; } static Info check(byte[] png, int maxPx) }`. It throws `IllegalArgumentException` with the rule that failed.
  - Rules: exactly the "Icons" Global Constraints. Inflate with `java.util.zip.Inflater`, stopping at expected + 1 bytes.
  - The named fixtures above are the shared vectors. Task 9 copies the same files, under the same names, into mibpkg's `tests/fixtures/icons/`.
- Produces: `tools/make_builtin_icons.py [--plugin-sample DIR] [--fixtures DIR]`. It uses only zlib and struct, and its output is deterministic.
  - Glyphs are white on a transparent RGBA background, as the spike icons were.
  - The `-20` glyphs keep a 2-px transparent margin on their right edge. The status layout puts the text straight after the 20-px icon cell (Task 5), and this margin is the gap.
  - `-72r` is the 72×72 glyph flipped and cut to 72×68, with alpha fading from 40 % to 0.
  - `--plugin-sample` writes hello-menu's `hello.png` (72×72) and `hello-20.png` (20×20) for Task 11.

- [ ] **Step 1: Write the failing tests**

```java
@Test void refusesEveryMalformedOrOversizedFile() throws Exception {
    assertEquals(20, PngCheck.check(fixture("ok-20.png"), 72).width);
    assertEquals(72, PngCheck.check(fixture("ok-72.png"), 72).height);
    for (String bad : new String[] {"empty.png", "too-big.png", "truncated.png", "bad-crc.png", "palette.png",
            "rgb.png", "depth16.png", "interlaced.png", "apng.png", "73x20.png", "1x100000.png",
            "ihdr-not-first.png", "iend-not-last.png", "short-idat.png", "compression1.png", "filter1.png",
            "idat-bomb.png"}) {
        assertThrows(IllegalArgumentException.class, () -> PngCheck.check(fixture(bad), 72), bad);
    }
    assertTrue(fixture("idat-bomb.png").length < 32768);   // the inflate cap, not the size rule, refuses it
}
```
Also `PngCheckTest.everyBuiltinIconPasses` (all 24 files in `dist/icons/`; `-72` is 72×72, `-72r` 72×68, `-20` 20×20) and `test_make_builtin_icons.py` (the script's output is byte-identical to the committed `dist/icons/` and the committed fixtures).

- [ ] **Step 2: Run the tests and verify they fail**

Run: `./gradlew :build-logic:test && python3 -m unittest discover -s installer -p 'test_*.py'`
Expected: FAIL (compilation: `PngCheck` undefined)

- [ ] **Step 3: Implement** `PngCheck`, the plugin-build check and the generator. Generate and commit `dist/icons/` and the named fixtures. `mibPluginJar` is the authoritative build-time check; Task 10's staging check is a second guard for packages assembled without it.

- [ ] **Step 4: Run the tests and verify they pass**

Run: `./gradlew check && python3 -m unittest discover -s installer -p 'test_*.py'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add build-logic tools dist/icons installer/test_make_builtin_icons.py
git commit -m "build: PNG icon checks at plugin build; the SDK's built-in icon set; shared PNG fixtures"
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
    - `UiIcon iconAt(int width)` returns the icon only when `width >= ICON_CHARS + Math.min(text.length(), MIN_ITEM_CHARS)`, else null. An item with 1–2 characters of text keeps its icon whenever its whole text fits beside it;
    - `render(width)` cuts the text to `width - ICON_CHARS` when the icon is kept, else to `width`.
    - Icon-only items do not exist: an empty text still hides the item, as today.
  - `static List<UiStatusPart> StatusLine.parts(List<String> indicators, List<Item> items, int maxChars)`: the indicators that `line` keeps, joined into one part with `indicator = true`, then one part per item shown.
  - `line` and `parts` share one private routine (the indicator budget and the `shares` loop) that returns the parts. `line(…)` is the joined text of the parts computed with every icon removed. It keeps its current signature, and its output for icon-less items does not change.
  - `UiHub.setStatusItem(String owner, String text, UiIcon icon)` and `UiHub.addCarRow(String owner, String rowKey, String label, String pageKey, UiIcon icon)`. The old signatures delegate with null.

- [ ] **Step 1: Write the failing tests**

```java
@Test void iconCountsTowardTheFairShareAndIsDroppedBeforeTheTextGetsTooShort() {
    UiIcon i = UiIcon.stock("star");
    List<StatusLine.Item> items = Arrays.asList(new StatusLine.Item("a", "ABCDEFGHIJ", i),
            new StatusLine.Item("b", "XYZ", null));
    List<UiStatusPart> p = StatusLine.parts(Collections.<String>emptyList(), items, 24);
    assertEquals("ABCDEFGHIJ", p.get(0).text()); assertSame(i, p.get(0).icon());   // 12 + 1 + 3 <= 24
    List<String> must = Arrays.asList("SAFE", "PDC R OFF");
    List<UiStatusPart> five = StatusLine.parts(must, items, 24);                    // room 9: b 3, a 5
    assertEquals("SAFE PDC R OFF", five.get(0).text());                             // must-show kept whole
    assertSame(i, five.get(1).icon()); assertEquals("ABC", five.get(1).text());     // 5 >= 2 + 3
    List<UiStatusPart> four = StatusLine.parts(must, items, 23);                    // room 8: b 3, a 4
    assertNull(four.get(1).icon()); assertEquals("ABCD", four.get(1).text());       // 4 < 2 + 3
}
```
Also:
- `StatusLineTest.shortTextKeepsItsIcon`: the item "OK" with an icon, at a share of 4, keeps its icon;
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
  - `DrawerAssignment.java`, `DrawerSlots.java`;
  - `UiApplier.java`: the combined job;
  - `SdkGlobals.java` (`ICON_IDS`, `builtinIcons`);
  - `HqcPlatform.java`: adds `Features.UI_ICONS` to `FEATURES_UI` and `FEATURES_HOOKED_UI`, and reads the built-in index at start, on the SDK thread;
  - `SdkBundleActivator.java`: passes `new File(installDir, "icons-sdk")` to `HqcPlatform`.
- Test: `adapter-hqc/src/test/java/mibsdk/adapter/hqc/ui/IconIdsTest.java`, `ImagePathsTest.java`, `BuiltinIconsTest.java`, `DrawerSlotsTest.java`, `UiApplierTest.java`, `SdkCarScreenFactoryTest.java`, `StockThreadCodeTest.java`; `adapter-hqc/src/test/java/mibsdk/adapter/hqc/HqcPlatformTest.java`

**Interfaces:**
- Consumes: `UiRow.icon()`, `UiIcon`, `IconIndex` (Tasks 1 and 3).
- Produces:
  - `HqcUiIds`: `ICON_ID_FIRST = 609100`, `ICON_ID_LAST = 609399`; `INSTALL_ROOT = "/mnt/app/eso/hmi/lsd/mibsdk"` (the root that image paths are computed from).
  - `public final class IconIds { IconIds(int first, int last); int idFor(String owner, String sha16, String absFile, String relPath); String fileFor(int id); String relPathFor(int id); boolean exhausted(); }`.
    - The key is `owner + "/" + sha16`; `owner` is the plugin id, or `mibsdk` (the reserved plugin id) for the built-ins.
    - `idFor` returns the id already given to the key. Otherwise it returns the next unused id, or -1 when the range is spent. The applier logs the first -1 once as a WARN.
    - An id never changes its file or its path.
    - It holds one session-lifetime instance, `SdkGlobals.ICON_IDS`. `HqcPlatform` and `UiApplier.create` use that instance and never construct another. Writes are synchronized; stock threads read a volatile copy-on-write array without a lock.
  - `final class ImagePaths { static String imageRoot(String installRoot); static String relative(String imageRoot, String target) }`. These are pure String functions, called only on `mibsdk-ui` when an id is allocated.
    - `imageRoot` = the parent of `installRoot` plus `/images`.
    - `relative` gives the `../`-relative path between two absolute paths that are already normalised.
  - `final class BuiltinIcons { static BuiltinIcons read(File sdkIconDir); UiIcon get(String name, int size, boolean reflex); }`.
    - It reads `icons-sdk/index.properties` (names `<name>-72`, `<name>-72r`, `<name>-20`) once, in `HqcPlatform.start()`, into `SdkGlobals.builtinIcons`.
    - The dir comes from the new constructor parameter `HqcPlatform(BundleContext ctx, Clock clock, int statusMaxChars, File sdkIconDir)`. It equals `RuntimeConfig.sdkIconDir()`.
  - `DrawerAssignment(String[] keys, String[] labels, String[] pageKeys, int[][] bitmaps, List<String> overflow)` and `int[] iconAt(int slot)` (bitmaps, or null).
  - `DrawerSlots.assign(DrawerAssignment prev, List<UiRow> carRows, int pluginSlots, IconIds ids, BuiltinIcons builtins)` resolves each row's `UiIcon`:
    - STOCK: `{id(<name>-72), id(<name>-72r)}`;
    - FILE: `{id, id}`;
    - unknown, inert or spent: null.
    - The id key's owner is `UiIcon.owner()`.
  - `HmiWidgetState` is immutable and built on `mibsdk-ui`. Here it holds `int[][] slotBitmaps`; Task 5 adds the status layout and Task 7 the main-menu row. `HmiWidgets.apply(HmiWidgetState want)` runs on HMIEvent and applies only what differs from the last applied state.
  - `UiApplier`: one combined HMI job replaces `pageJob`.
    - It is posted when pages need rendering **or** the published `HmiWidgetState` differs from the applied one, and only once the first screen is up (the existing `SdkGlobals.uiRowModels != null` guard).
    - The job re-evaluates `needsRender(table.snapshot(), false)` and renders pages only if that is true. Then it calls `HmiWidgets.apply`. Each part has its own try/catch. `wake` is unchanged.
  - `SdkCarScreenFactory.getImagePath(int id, int terminal)`: for an id in `ICON_ID_FIRST..ICON_ID_LAST` with a known path, it returns `ICON_IDS.relPathFor(id)` (an array lookup, no `File` work); otherwise `super`. It catches Throwable.
    - The first serve of each id is logged through `RingLog.append` as `getImagePath <id> -> <relPath>`. At most 64 lines a session, tracked in a fixed `boolean[]` over the range.

- [ ] **Step 1: Write the failing tests**

```java
@Test void sameNameNewContentGetsANewIdAndTheOldIdKeepsItsFile() {
    IconIds ids = new IconIds(609100, 609101);
    assertEquals(609100, ids.idFor("hello", "aaaa000000000000", "/i/hello/aaaa000000000000.png", "../i/a.png"));
    assertEquals(609100, ids.idFor("hello", "aaaa000000000000", "/i/hello/aaaa000000000000.png", "../i/a.png"));
    assertEquals(609101, ids.idFor("hello", "bbbb000000000000", "/i/hello/bbbb000000000000.png", "../i/b.png"));
    assertEquals("/i/hello/aaaa000000000000.png", ids.fileFor(609100));
    assertEquals(-1, ids.idFor("hello", "cccc000000000000", "/i/hello/cccc000000000000.png", "../i/c.png"));
}
@Test void installedIconsAreOneLevelUpFromTheImageRoot() {
    String root = ImagePaths.imageRoot("/mnt/app/eso/hmi/lsd/mibsdk");
    assertEquals("../mibsdk/icons/hello-menu/0123456789abcdef.png",
        ImagePaths.relative(root, "/mnt/app/eso/hmi/lsd/mibsdk/icons/hello-menu/0123456789abcdef.png"));
}
```
Also:
- `IconIdsTest.identicalBytesInTwoPackagesGetTwoIds`: the same `sha16` for owners `a` and `b` gives two ids with two files;
- `IconIdsTest.platformAndApplierShareTheSessionInstance`: after `HqcPlatform.start` and a second `UiApplier.create`, both use `SdkGlobals.ICON_IDS` (the same object), and an id given before the second applier still maps to its file;
- `DrawerSlotsTest.iconsResolveToBitmapPairs`;
- `SdkCarScreenFactoryTest.getImagePathServesSdkIdsAndDefersTheRest`;
- `SdkCarScreenFactoryTest.firstServeOfEachIdIsLoggedOnce`;
- `UiApplierTest.slotIconChangePostsOneJobAndAppliesBitmapsOnce`;
- `BuiltinIconsTest.readsTheSdkIndex`;
- `HqcPlatformTest.uiFeaturesIncludeIcons`: `UI_ICONS` is in both UI feature sets and in neither of the others.

In `StockThreadCodeTest`, add `IconIds`, `HmiWidgets` and `HmiWidgetState` to `STOCK_THREAD_FILES`, and `getImagePath` to the catch-Throwable list. `ImagePaths` and `BuiltinIcons` run only on SDK threads, so they are not on that list.

- [ ] **Step 2: Run the tests and verify they fail**

Run: `./gradlew :adapter-hqc:test`
Expected: FAIL (compilation: `IconIds`, `ImagePaths` undefined)

- [ ] **Step 3: Implement** as specified.
- Ids are allocated only on `mibsdk-ui`, in `DrawerSlots.assign` and later in `StatusLayout.of`. Each passes `ImagePaths.relative(ImagePaths.imageRoot(HqcUiIds.INSTALL_ROOT), absFile)` as `relPath`.
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
    - popup: `POPUP_SDK_STATUS = 609600`, `POPUP_SDK_X = 514`, `POPUP_SDK_W = 255`, `POPUP_SDK_H = 24`, `POPUP_SDK_SLOT = 7`, `POPUP_SDK_ZPM_SLOT = 12`, `POPUP_SDK_PRIORITY = 250`, `POPUP_SDK_TYPE = 7`, `POPUP_SDK_STUB_NO_MODEL = -1`, `POPUP_SDK_STUB_KIND = 2` (the stub's third and tenth arguments, as popup 65's stub has them), `POPUP_BACKDROP_BITMAP = 251`;
    - layout: `STATUS_CELLS = 6`, `STATUS_PAD_PX = 6`, `STATUS_CHAR_PX = 10`, `STATUS_ICON_PX = 20`. An icon's cell is exactly `ICON_CHARS × STATUS_CHAR_PX` = 20 px; the built-in `-20` glyphs carry their own 2-px right margin as the gap (Task 2). `STATUS_CHAR_PX` is an estimate (popup 65 fits 24 chars in 235 px), checked at R1 S1;
    - models: `STATUS_POPUP_VISIBLE_MODEL = MODEL_BASE + 12`; `STATUS_LABEL_MODEL_0 = MODEL_BASE + 13` (+0 is the indicators, +1..+6 the cells); `STATUS_FILE_ICON_MODEL_1 = MODEL_BASE + 20` (+0..+5). So `MODEL_COUNT = 26`, and ids 602750..602763 are used. Only up to 602753 was proven by spike C; Step 0 confirms the rest are free.
  - `final class StatusLayout { static final class Cell { final int iconX; final int labelX; final int labelW; final int stockIconId; final String filePath; final String text; } static StatusLayout of(List<UiStatusPart> parts, IconIds ids, BuiltinIcons builtins); Cell indicators(); Cell cell(int i); boolean sameGeometry(StatusLayout o); boolean sameIcons(StatusLayout o); }`. `sameGeometry` compares bounds only. `sameIcons` compares each cell's `stockIconId`.
    - Placement starts at `x = STATUS_PAD_PX`. Before each part after the first, `x += STATUS_CHAR_PX`.
    - A part with an icon gets `iconX = x`, then `x += StatusLine.ICON_CHARS * STATUS_CHAR_PX` (20).
    - Then `labelX = x`, `labelW = Math.max(0, POPUP_SDK_W - STATUS_PAD_PX - x)`, and `x += text.length() * STATUS_CHAR_PX`. At 24 units the line ends at 6 + 240 = 246 px, inside the 249 available.
    - A STOCK icon becomes `stockIconId`: the built-in `<name>-20`, drawn by id and tinted.
    - A FILE icon becomes `filePath`: the absolute installed path, drawn in its own colours.
    - A part beyond `STATUS_CELLS` is dropped.
  - `SdkStatusPopup.build(SdkCarScreenFactory f, int terminal)` follows `SpikePopup`'s recipe at (514, 0, 255, 24), slot 7:
    - a backdrop;
    - an indicator label;
    - 6 cells, each an id `IconController`, a file `IconController` on its ResourceLocatorModel, and a `LabelController` on its LabelModel.

    Bounds come from the latest `HmiWidgetState.status`. The popup is kept in a static volatile for `HmiWidgets`.
  - `SdkGlobals.statusPopup`: `0` unknown, `1` registered (the stub was added), `2` built, `-1` failed (the stub or the build threw). `-1` is sticky for the boot: a later stub collection or build never moves it back to 1 or 2, and once it is `-1`, `getPartialPopupStubs` adds no stub.
  - Boot window (documented, no timer): while the state is 0, because the popup manager has not collected the stubs yet, the line goes to popup 65. The switch to 609600 then hides popup 65. Put this in `StatusSinks`' class doc.
  - `StatusLineSink.show(String)` becomes `void show(List<UiStatusPart> parts, String textLine)`; `hide()` is unchanged.
    - `SdkPopupStatusLine` writes the 7 label models and 6 locator models the way `RowModels` writes the bank's models. Locators are set with `setResourceLocator(-1, path, 0)` and cleared with `(-1, "", 0)` (status 0, as spike C; status 1 draws bitmap 0 and never loads the URI, bench R1, 5340cd3). It then sets the visible model.
    - `Popup65StatusLine.show(parts, textLine)` shows `textLine`.
  - `final class StatusSinks implements StatusLineSink { interface State { int get(); } StatusSinks(StatusLineSink sdkPopup, StatusLineSink popup65, State state) }`. In production the state reads `SdkGlobals.statusPopup`.
    - It delegates to the SDK popup while the state is `1` or `2`, else to popup 65.
    - On a switch it hides the old sink, but only if the SDK showed text on it.
    - `int active()` returns 0 for popup 65 and 1 for the SDK popup.
  - `UiApplier` status phase:
    - it calls `status.show(current.statusParts(), current.statusLine())`, with the existing re-assert rule. It also re-shows whenever `StatusSinks.active()` changed since the last show, so a fallback reaches popup 65 within one pass;
    - the layout (`StatusLayout.of`) goes into `HmiWidgetState`;
    - `HmiWidgets.apply` sets bounds when `sameGeometry` is false, and sets id bitmaps (an empty `int[0]` hides) for each cell whose `stockIconId` changed. A same-length DOT→CROSS swap is therefore drawn.

- [ ] **Step 0: Confirm the model ids are free.** In `lsd-src`, confirm that nothing references Car model ids 602738..602763: `CarModelBank`'s `models[]` has 2738 slots, and a grep for `6027[3-6][0-9]` (decoded values) finds no stock use. Record the result in `HqcUiIds`' javadoc. If an id is taken, pick the next free block above `MODEL_BASE + 25`, record it, and use those ids everywhere below.

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
- `StatusLayoutTest.cellsFollowTheUnitGrid`: with the indicator "SAFE" followed by an item with an icon and the text "C801", `iconX == 6 + 40 + 10` and `labelX == iconX + 20`; a 7th item is dropped; no `labelW` is negative;
- `StatusLayoutTest.textChangeOfSameLengthKeepsGeometry`;
- `UiApplierTest.stockIconSwapOfSameLengthAppliesBitmaps`: "TS up" with DOT, then "TS dn" with CROSS, gives one job that sets the cell's bitmaps and no bounds;
- `UiApplierTest.sinkSwitchReshowsWithinOnePass`: after the state flips from 2 to -1 with an unchanged line, the next `step` shows the line on popup 65;
- `SdkCarScreenFactoryTest.stubsAddPopup609600InSlot7AndBuildFailureMarksFailed`;
- `SdkCarScreenFactoryTest.failedStaysFailedWhenStubsAreCollectedAgain`;
- `SdkCarActivatorTest.popupModelsAreCreated`: one choice, 7 label and 6 locator models;
- `UiApplierTest.layoutChangePostsOneJobTextChangeOnlyWritesModels`.

- [ ] **Step 2: Run the tests and verify they fail**

Run: `./gradlew :adapter-hqc:test`
Expected: FAIL (compilation: `StatusSinks`, `StatusLayout` undefined)

- [ ] **Step 3: Implement** as specified.
- `getPartialPopupStubs` appends `new PartialPopupStub(POPUP_SDK_STATUS, STATUS_POPUP_VISIBLE_MODEL, POPUP_SDK_STUB_NO_MODEL, POPUP_SDK_PRIORITY, POPUP_SDK_SLOT, POPUP_SDK_ZPM_SLOT, true, POPUP_SDK_TYPE, terminal, POPUP_SDK_STUB_KIND, null, true, true)` to `super`'s array and sets `statusPopup = 1`. Every number comes from `HqcUiIds`; none is a literal in `ui/`. On Throwable it returns `super`'s array and sets `-1`.
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
  - The trigger is a change of bounds only (`sameGeometry` false). An icon-only change (`sameIcons` false) never triggers it.
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
- Create: `adapter-hqc/src/main/java/mibsdk/adapter/hqc/ui/SdkSystemActivator.java`, `SdkSystemScreenFactory.java`, `MainMenuKeyHandler.java`, `MainMenuWrench.java`
- Modify:
  - `HqcUiIds.java`, `SdkGlobals.java`;
  - `ConfiguratorCore.java` (the forced-failure switch);
  - `RuntimeConfig.java` (`uiForceFailFile()` = `<persist>/dev/ui.forcefail`);
  - `adapter-hqc/build.gradle.kts` (add the host stock package `de/audi/tghu/system/hmi/`).
- Test: `adapter-hqc/src/test/java/mibsdk/adapter/hqc/ui/SdkSystemActivatorTest.java`, `SdkSystemScreenFactoryTest.java`, `MainMenuWrenchTest.java`, `SystemTakeoverSourceTest.java`, `StockThreadCodeTest.java`; `adapter-hqc/src/test/java/mibsdk/adapter/hqc/ConfiguratorIsolationTest.java`

**Interfaces:**
- Produces:
  - `HqcUiIds`: `SYSTEM_MODULE = 0`, `SYS_SCREEN_MAINWIZARD = 46`, `SYS_TEXT_PLUGINS = 90000`, `MAIN_MENU_WIDGET_ID = 11`, `WRENCH_ATLAS_CELL = 16`, `WRENCH_DECORATOR = 9`, `MAIN_MENU_INTERNAL_ID = 90100`, `KEY_ENTER = 17`.
  - `SdkGlobals`:
    - `MAIN_MENU_OFF = 0`, `MAIN_MENU_PENDING = 1`, `MAIN_MENU_ACTIVE = 2`, `MAIN_MENU_FAILED = 3`;
    - `static volatile int mainMenu`, which starts at OFF;
    - `static boolean drawerPluginsRowWanted()`: true when the state is OFF or FAILED;
    - `static volatile String uiForceFail`: `null`, `"factory"` or `"screen"`. It is always compared with `"factory".equals(…)` and `"screen".equals(…)`, never with `==`;
    - `static volatile Object mainMenuRow` and `static volatile Object mainMenuWizard`: the appended `MenuItemController` and its `MainWizardController`, typed `Object`, so Car-side code never loads a `SystemScreenFactory` subclass to read them. Both are set to null when the state becomes FAILED.
  - `public class SdkSystemActivator extends AbstractHMIActivator`, with `SpikeSystemActivator`'s shape: `super(0, "System", skin, new SystemModelBank())` and the stock `SystemConditionBank`.
    - `getScreenFactory()` gives the SDK factory, and sets `mainMenu = PENDING`, only when not in swdl, `uiEnabled` is true, `uiForceFail` is not `factory`, and construction succeeded.
    - When it chooses the SDK factory it logs one STATUS line, `ui system=sdk`.
    - Otherwise it gives `new SystemScreenFactory(fw)` and logs one STATUS line, `ui system=stock reason=<swdl|ui-off|forced|factory-failed>`. `mainMenu` stays OFF.
    - The package-private `AbstractScreenFactory factoryForTests()` calls `getScreenFactory()`, so the lazy choice runs, and returns the result.
    - `"factory".equals(SdkGlobals.uiForceFail)` makes it throw inside the try, so the real catch path runs.
  - `final class SdkSystemScreenFactory extends SystemScreenFactory`:
    - `createScreen(46, t)` calls `super` and finds the `MainWizardController`. It copies colour indices and insets from the last stock row and appends one `MenuItemController`: type 2, label `SYS_TEXT_PLUGINS`, widgetID 11, internal id 90100, `setKeyHandler(new MainMenuKeyHandler())`, `setVisible(!SdkGlobals.uiDown)`.
    - It does **not** touch the atlas mapping: the spike set it on a connected wizard, and Task 7's HMI job does the same through `MainMenuWrench.apply`.
    - It then sets `mainMenu = ACTIVE`, `SdkGlobals.mainMenuRow` and `SdkGlobals.mainMenuWizard`.
    - On Throwable, or when `"screen".equals(SdkGlobals.uiForceFail)`, it returns `super.createScreen(46, t)` (a fresh stock screen), sets `mainMenu = FAILED` and clears both refs. FAILED is sticky for the boot: later builds of screen 46 stay stock.
  - `final class MainMenuWrench { static boolean apply(Object wizard) }`. Task 7's HMI job calls it once for each new `mainMenuWizard`, on HMIEvent, after the wizard is connected.
    - It reads `((MainWizardController) wizard).getInitContext().getLayout().getMainWizardIconDecoratorMapping()`, as the spike's `wrench` probe did.
    - It sets `[11] := {16, 9}` only when the array has at least 12 entries and entry 11 is `{11, 11}` or already `{16, 9}`; otherwise it leaves the mapping alone and logs once.
    - It has its own try/catch Throwable and returns false on any failure. It never changes `mainMenu`: without the wrench the row still works, with the night-vision icon.
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
- `SdkSystemScreenFactoryTest.appendsOnePluginsRowWithWidget11`: the child count is +1; the last row has widgetID 11, label 90000 and a `MainMenuKeyHandler`; the state is ACTIVE and both refs are set;
- `…forcedScreenFailureGivesAStockScreenAndFailedState`: the returned screen has exactly one `MainWizardController` and no SDK row (no internal id 90100); the state is FAILED; both refs are null;
- `SdkSystemActivatorTest.logsUiSystemSdkOnceWhenTheSdkFactoryIsChosen`;
- `MainMenuWrenchTest.setsTheWrenchOnAStockMapping`, `…unexpectedMappingIsLeftAlone`, `…wizardWithoutALayoutReturnsFalseAndLeavesTheStateAlone`;
- `MainMenuKeyHandler`: SELECT puts one `ROW_SELECTED` for `mibsdk/plugins` in the ring and records a press;
- `ConfiguratorIsolationTest.forceFailFileIsRead`;
- `SystemTakeoverSourceTest.noStaticInitializers`: in `SdkSystemActivator.java` and `SdkSystemScreenFactory.java` there is no `static {` and no `static` field with `=`, other than `static final` primitives or Strings;
- `StockThreadCodeTest`: add the four new files, and add `createScreen`, `getText`, `getAltText`, `getScreenFactory`, `keyPressed` and `MainMenuWrench.apply` to the catch-Throwable list.

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
- Consumes: `SdkGlobals.mainMenu`, `drawerPluginsRowWanted()`, `SdkGlobals.mainMenuRow`/`mainMenuWizard`, `MainMenuWrench.apply` (Task 6); the combined job (Task 4). Car-side code never names `SdkSystemScreenFactory`.
- Produces:
  - `PageScreens.openAllowed(int currentId, boolean blocked, int pendingId, long sincePressMs)`: as today, and it also allows `currentId == HqcUiIds.SYS_SCREEN_MAINWIZARD`, under the same recent-press rule as a Car screen. BACK returns to the saved screen-46 data (Boot B C3).
  - `UiApplier.applyRows`: slot 0 is wanted visible iff `slots.visibleAt(0) && SdkGlobals.drawerPluginsRowWanted()`. This is evaluated on every pass, so a FAILED main menu brings the row back within `AWAIT_MS`.
  - `SdkCarModelBank.createModel` for slot 0: visible iff `!uiDown && drawerPluginsRowWanted()`.
  - `HmiWidgetState.mainMenuRowVisible` = `!down && mainMenu == ACTIVE`. `HmiWidgets.apply` calls `((MenuItemController) SdkGlobals.mainMenuRow).setVisible(v)` when it changes. Per Boot B, the change shows at the next ring re-layout.
  - `HmiWidgets.apply` also calls `MainMenuWrench.apply(SdkGlobals.mainMenuWizard)` once for each new wizard object, after the wizard is connected (`isConnected()`), and retries on later jobs until that is true. A new `mainMenuWizard` from a rebuilt screen 46 gets its own call. The applier keeps the HMI job posted while a wizard is pending its wrench.

- [ ] **Step 1: Write the failing tests**

```java
@Test void drawerPluginsRowReturnsWhenTheMainMenuFails() throws Exception {
    SdkGlobals.mainMenu = SdkGlobals.MAIN_MENU_PENDING;
    applier.step(0);                                   // snapshot with the Plugins row
    assertTrue(writes.contains("row0=off"));           // hidden: the MENU ring has it
    writes.clear();
    SdkGlobals.mainMenu = SdkGlobals.MAIN_MENU_FAILED;
    applier.step(0);                                   // no new snapshot needed
    assertEquals(Arrays.asList("row0=on"), rowWrites());
}
```
Here `writes` is `UiApplierTest`'s existing recording list, and `rowWrites()` is a new test helper returning its `row…` entries.

Also:
- `UiApplierTest.wrenchIsAppliedOncePerConnectedWizard`;
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
  - `bundles::check_line(text: &[u8], key: &str, what: &str, allowed: &[&str]) -> Result<()>`: today's `check_hmicar`, generalised over the key. It reads keys the way `Properties.load` does, as now, through `key_lines(lines, key)`, which is today's private `hmicar_lines(lines)` renamed and generalised over the key. `check_hmicar` stays as a wrapper.
  - `bundles::generate`, when `sdk.hmisystem` is set, rewrites the single `HMISystem=` line exactly as it does `HMICar=`:
    - it accepts the stock or the SDK value, so the output is a fixed point;
    - it keeps the line break;
    - it refuses a non-stock value with `HMISystem is not stock (…); refusing`, a missing or duplicate key with `expected exactly one HMISystem= line, found <n>`, and a bare CR with the existing bare-CR message. These are the generalised `HMICar=` messages, and the tests assert them.

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
- `nonstock_hmisystem_refused` and `duplicate_hmisystem_refused` (each asserts its exact message);
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

### Task 9: mibpkg installs, validates and removes icons — mibpkg repo, same worktree, host-only (starts after Task 2 is committed)

**Files:**
- Create: `src/mibsdk/icons.rs`, `tests/mibsdk_icons.rs`, `tests/fixtures/icons/*.png`: byte copies of the 19 named files Task 2 committed in the SDK repo's `build-logic/src/test/resources/png/`, under the same names
- Modify:
  - `src/manifest.rs`: `PluginSpec.icons`, `SdkSpec.icons`, their validation, and `Manifest::kind_files` (adds `icons/<name>.png` for every declared name, so `archive::pack` refuses a package without them);
  - `src/mibsdk/plugin_pkg.rs`: deploy, redeploy and remove;
  - `src/mibsdk/sdk_pkg.rs`: deploy and undeploy;
  - `src/mibsdk/mod.rs`: the constants `ICONS = "/mnt/app/eso/hmi/lsd/mibsdk/icons"` and `SDK_ICONS = "/mnt/app/eso/hmi/lsd/mibsdk/icons-sdk"`;
  - `README.md`.
- Test: `tests/mibsdk_icons.rs`, `tests/sdk_pkg.rs`, the plugin package tests (the existing file that covers `plugin_pkg::deploy`/`remove`), `tests/manifest_kinds.rs`

**Interfaces:**
- Produces:
  - `PluginSpec { …, #[serde(default)] pub icons: Vec<String> }` and `SdkSpec { …, #[serde(default)] pub icons: Vec<String> }`.
    - Names match `[a-z0-9-]{1,32}`, with no duplicates; at most 8 for a plugin and 32 for the SDK.
    - Each name needs the package file `icons/<name>.png`. `Manifest::validate` has no package dir, so the file is required through `kind_files`, checked by `archive::pack`, and again by `icons::check_all` at install.
  - `icons::png_check(bytes: &[u8], max_px: u32) -> Result<(u32, u32)>`: the same rules as `PngCheck`. CRC via `flate2::Crc`, inflate via `flate2::read::ZlibDecoder` with an expected+1 read cap.
  - `icons::check_all(pkg_dir: &Path, names: &[String]) -> Result<Vec<Checked>>`: reads and `png_check`s every declared icon, and computes its `sha16`. It writes nothing.
  - `icons::install(ctx: &Ctx, dir_abs: &str, checked: &[Checked]) -> Result<()>`:
    - it writes `<dir_abs>/<sha16>.png`, where `sha16` is the first 16 lowercase hex characters of SHA-256 (`sha2`);
    - then it writes `index.properties` (`<name>=<sha16>,<w>,<h>`, sorted, LF);
    - last, it removes files in the dir that the new index does not list.

    It writes through `put`, under /mnt/app read-write. An empty `names` removes the dir.
  - `icons::remove(ctx: &Ctx, dir_abs: &str) -> Result<()>`.
  - `icons::snapshot(l: &Layout, dir_abs: &str) -> Result<Option<Vec<(String, Vec<u8>)>>>` and `icons::restore(ctx, dir_abs, snap)`: the prior icon dir, kept for rollback.
  - Wiring, plugin deploy:
    - `plugin_pkg::check` calls `icons::check_all` before anything is written. A failure aborts with `icon <name>: <rule>; nothing changed`;
    - `Prior` gains `icons: Option<Vec<(String, Vec<u8>)>>` (the prior icon dir), and `restore` puts it back;
    - `deploy` writes the icons after the jar and the grants have succeeded. If writing the icons fails, it restores jar, grants and icons from `Prior`;
    - redeploy does the same.
  - Wiring, plugin remove:
    - `present` also counts the icon dir `ICONS/<id>`, so a re-run after a half-done remove is not refused as "not installed";
    - the icons are removed after the ack wait, whatever its outcome, in a fresh `/mnt/app` read-write scope.
  - Wiring, SDK:
    - SDK deploy runs `check_all` in `sdk_pkg::check`, then calls `install(SDK_ICONS, …)`;
    - SDK undeploy calls `remove(SDK_ICONS)`. `undeploy` already refuses while plugins remain, so it also removes the (empty) `ICONS` dir.
  - `sdk push` (jar only) leaves the icons alone.

- [ ] **Step 1: Write the failing tests**

```rust
#[test]
fn png_check_refuses_every_malformed_or_oversized_file() {
    assert_eq!(icons::png_check(&fixture("ok-20.png"), 72).unwrap(), (20, 20));
    assert_eq!(icons::png_check(&fixture("ok-72.png"), 72).unwrap(), (72, 72));
    for bad in ["empty.png", "too-big.png", "truncated.png", "bad-crc.png", "palette.png", "rgb.png",
                "depth16.png", "interlaced.png", "apng.png", "73x20.png", "1x100000.png", "ihdr-not-first.png",
                "iend-not-last.png", "short-idat.png", "compression1.png", "filter1.png", "idat-bomb.png"] {
        assert!(icons::png_check(&fixture(bad), 72).is_err(), "{bad}");
    }
    assert!(fixture("idat-bomb.png").len() < 32768); // refused by the inflate cap
}
```
Also:
- `plugin_install_writes_content_addressed_icons_and_index`: the index line is `hello=<sha16>,72,72`, and the file bytes equal the source;
- `plugin_update_with_new_content_replaces_the_file_and_index`: the old file is gone;
- `plugin_install_with_a_bad_icon_writes_nothing`: no jar, no grants line, no icon dir, and the package state is unchanged;
- `plugin_update_whose_icon_write_fails_restores_the_prior_jar_grants_and_icons` (through the existing `FileOps` failure injection);
- `plugin_remove_deletes_its_icon_dir`;
- `remove_rerun_finishes_an_orphaned_icon_dir`: with only `ICONS/<id>` left, remove succeeds and deletes it;
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
  - `MpkStage.sdkManifest(String version, List<String> icons)` (it gains the icon list; `dist/build.gradle.kts` passes the names of `dist/icons/*.png`, sorted). The staged SDK manifest has:
    - `api = 3`;
    - `hmisystem_stock = "de.audi.tghu.system.hmi.Activator"`;
    - `hmisystem = "mibsdk.adapter.hqc.ui.SdkSystemActivator"`;
    - `owned = ["de/audi/tghu/car/hmi/", "de/audi/atip/model/CarModelBank", "de/audi/tghu/system/hmi/"]`;
    - `icons = [<the 24 built-in names, sorted>]`, with the files staged as `icons/<name>.png`.
  - A plugin package stages `plugin.json`'s `icons` as `[plugin] icons` and `icons/<name>.png`, taken from `src/main/icons/` and checked with `PngCheck` at stage time.
  - `mibsdk-dev ui-forcefail factory|screen|off` writes or removes `/mnt/persist/mibsdk/dev/ui.forcefail` over SSH. It needs no remount, because persist is read-write, and it says that a restart is needed.
  - `mibsdk-dev status` already prints `mibpkg sdk status`, so it shows the `HMISystem=` line through Task 8 with no change here.

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
    - `src/main/resources/plugin.json` gets `api: 3`, `icons: ["hello", "hello-20"]`, and the permission `ui.slot.statusbar` beside `ui.slot.carmenu` (the new status item needs it);
    - `src/main/icons/hello.png` (72×72) and `hello-20.png` (20×20), from `tools/make_builtin_icons.py --plugin-sample`;
    - the Car row uses `ui.icon("hello")` and the status item `ui.icon("hello-20")`;
    - a page entry "Next icon" swaps the row icon between `hello` and `StockIcons.STAR`. Per Task 1, the change shows at the next Car connect.
    - a page entry "Next status icon" cycles the status item's icon `hello-20` → `STAR` → `DOT` → `hello-20`, with the text unchanged. STAR → DOT is the bench's same-length stock-icon swap (Task 12 S3).
  - `plugins/daemon-status`: api 3; the status item shows `StockIcons.DOT` while every daemon field is `up`, and `StockIcons.CROSS` otherwise (`dn` or `?`).
  - `adapter-fake/.../FakePlatform.java`: a test-only way to advertise `Features.UI_ICONS` and to set the install root that `RuntimeConfig.pluginIconDir(id)` resolves.
- Test: extend the existing `plugins/hello-menu/src/test/java/sample/hellomenu/HelloMenuPluginTest.java` and `plugins/daemon-status/src/test/java/sample/daemonstatus/DaemonStatusPluginTest.java`, over FakeUi's icon tags.
  - Fixture: before the plugin loads, the test writes `<tmp install>/icons/hello-menu/index.properties` (`hello=<sha16>,72,72` and `hello-20=<sha16>,20,20`) and the two PNGs under their `<sha16>.png` names, as mibpkg would.
  - The fake platform advertises `UI_ICONS`.

**Interfaces:** Consumes only the Task 1 API. A plugin built against api 3 does not load on an api-2 SDK (the manifest check), as intended.

- [ ] **Step 1: Write the failing tests:**
  - `HelloMenuPluginTest.rowAndStatusCarryIcons`: FakeUi shows `[png:<key>] Hello` and the status part with a png tag; "Next icon" switches the row tag to `[star]`;
  - `HelloMenuPluginTest.manifestDeclaresTheStatusbarPermission`;
  - `HelloMenuPluginTest.nextStatusIconCycles`: the status part's tag goes png → `[star]` → `[dot]` → png, with the text unchanged;
  - `DaemonStatusPluginTest.statusIconFollowsDaemonState`: DOT for all `up`, CROSS for a `dn` and for a `?`.
- [ ] **Step 2: Run them and verify they fail:** `./gradlew :plugins:hello-menu:test :plugins:daemon-status:test`. Expected: FAIL.
- [ ] **Step 3: Implement** the plugin changes.
- [ ] **Step 4: Run and verify:** `./gradlew check`; `./gradlew :plugins:hello-menu:mibPackage :plugins:daemon-status:mibPackage -Pmibsdk.mibpkg=<mibpkg 0.9.0>` builds both packages. Expected: PASS.
- [ ] **Step 5: Commit:** `git add plugins && git commit -m "feat(plugins): hello-menu and daemon-status show icons"`

---

### Task 12: Bench E2E — BENCH (restart budget: 3 planned, 4 at most; the 4th only to recover from a failed boot)

**Files:**
- Create: `docs/bench-e2e-menu-icons.md`, `docs/bench-e2e/menu-icons-*.jpg`, `docs/bench-e2e/menu-icons-log-r{1,2,3}.txt`

**Before any restart (no restart):**
- [ ] Check the starting state:
  - `curl -s 10.20.0.23:8099/status` → `bridge_connected:true`;
  - `spikes/hmi-ui/run.sh status` shows the stock `HMISystem=` line and no spike files. The unit's mibpkg 0.8.1 prints only `HMICar=`, so check that line with `ssh … grep -n '^HMI' /mnt/app/eso/hmi/lsd/bundles_dsitracer_enabled.properties` (`HMICar=…SdkCarActivator`, `HMISystem=de.audi.tghu.system.hmi.Activator`);

  Save a host copy of the live bundles file as `bundles.pre.properties`.
- [ ] Install:
  - upgrade mibpkg to 0.9.0, as `docs/bench-e2e-ui.md` "Builds and deploys" did for 0.8.1;
  - install `mibsdk` (Task 10), `hello-menu` and `daemon-status` (Task 11) through mibpkg. hello-menu's update asks for the new `ui.slot.statusbar` permission: approve it, as `docs/bench-e2e-ui.md` describes for approvals, and check that `mibpkg sdk status` lists the grant.

  Check:
  - `mibpkg sdk status` shows `HMICar=…SdkCarActivator`, `HMISystem=mibsdk.adapter.hqc.ui.SdkSystemActivator` and `api: 1..3`;
  - `cmp` of the live list against `bundles.pre.properties` differs only in the `HMISystem=` line;
  - `ls /mnt/app/eso/hmi/lsd/mibsdk/icons/hello-menu/ /mnt/app/eso/hmi/lsd/mibsdk/icons-sdk/` shows the `<sha16>.png` files and an `index.properties`.

**R1: happy path (restart 1).** Run `installer/mibsdk-dev restart`, then `ssh rpi-bench keypanel up`. If the HMI stays black more than 5 min after SSH answers, run the emergency revert below and stop.
- [ ] C0: the home frame looks stock; `vol_up` shows the volume popup; the status bar has its clock. The log has `ui system=sdk` and `ui car=sdk`, and no `ui system=stock`.
- [ ] M1: `menu` → the ring ends …Settings, then **Plugins** with the grey wrench. Focus it → red wrench (`menu-icons-ring.jpg`, `-ring-focus.jpg`).
- [ ] M2: `knob_select` → the Plugins page (`-plugins-from-menu.jpg`). `back` → the ring, with focus on Plugins (`-back-to-ring.jpg`). MENU on the page is a no-op (Boot B C4).
- [ ] M3: `car`, then `menu_left` → the drawer has **no** Plugins row and ends with the plugin rows (`-drawer.jpg`).
- [ ] I1: Hello's row shows its PNG icon, tinted (`-drawer-hello-icon.jpg`). The log has `getImagePath 6091xx -> ../mibsdk/icons/hello-menu/<sha16>.png`.
  - **Reflection check (explicit):** the row draws the full-opacity icon as its own reflection (`{id, id}`). Take a zoomed crop (`-drawer-hello-reflection-zoom.png`) and record whether it looks acceptable next to the stock rows. If it does not, record it as a finding: the follow-up is a build-generated faded `-r` variant, not a change in this run.
- [ ] I2: run hello-menu's "Next icon", then Media → Car → drawer. The row shows the built-in star with its reflection (`-drawer-icon-next.jpg`).
- [ ] S1: popup 609600 sits at the top right on home, Car, Media and the ring (`-status-{home,car,media,ring}.jpg`). It shows the indicators, hello's PNG icon (in its own colours) with its text, and daemon-status's built-in dot (tinted) with its text. Popup 65 shows nothing from the SDK.
- [ ] S2: with 609600 up, turn on the REM/GEM engineering Mem/CPU line (popup 65). Both show (slot 7, `-status-with-p65.jpg`). Close the engineering menu with its combo.
- [ ] S3, in two parts:
  - **Icon swap, same length:** hello-menu's "Next status icon" twice (PNG → star → dot). The star turns into a dot within ~2 s, with the text and the cell in place (`-status-icon-swap.jpg`). The bench has no MQTT broker, so daemon-status stays on its cross (`MQ dn`), and this check uses hello-menu instead.
  - **Movement:** stop the tailscale `tsstatus` sidecar, so daemon-status's field goes from `TS up` to `TS ?`. The length changes, and the cells must move within ~2 s with no stale cell (`-status-relayout.jpg`). Restart the sidecar. If tailscale is not `up` on the bench, start from `TS ?` and start the sidecar instead.
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

There is no R4. The screen-46 failure path (`ui-forcefail screen`) is covered by the Task 6 host tests only, by ruling.

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
- [my call] widgetID **11**, with mapping `[11] := {16, 9}`, exactly as Boot B proved it. The research note's `[10]` was a slip in its plan, corrected in §9. The mapping is changed only while entry 11 is still the stock `{11, 11}`, from the HMI job on the connected wizard (as the spike's probe did), and a failure there never marks the entry FAILED (preflight 7).
- [my call] The row is appended after Settings, the position Boot B proved. Reordering the ring is not attempted.
- [decided] "Main menu only": the SDK's Plugins row leaves the Car drawer. Plugins' own Car rows are unaffected.
- [decided, controller ruling] The drawer row comes back whenever the System takeover is not active:
  - `mainMenu` OFF: the stock fallback on any Throwable, swdl, the UI off or an unsupported build, or mibpkg not managing `HMISystem=`;
  - FAILED: a screen-46 build that failed. FAILED is sticky for the boot.
- [controller ruling] Only the ACTIVE state hides the drawer row. In the PENDING state (the SDK System factory is up, but screen 46 has not been built yet this boot) the drawer row still shows, so a PENDING that never moves on cannot lock the user out of Plugins (`SdkGlobals.drawerPluginsRowWanted`). The ring builds its row on the first MENU, and a failure there (FAILED) keeps or brings the drawer row back within one applier pass.

**Icons**
- [decided] Row icons: Car-drawer plugin rows can carry a stock icon or a plugin PNG, served through `getImagePath` with fresh ids per content. They are near-static: a change shows at the next Car reconnect.
- [decided, controller ruling 2] Plugin icons are declared in the plugin package.
  - mibpkg extracts them at install to `/mnt/app/eso/hmi/lsd/mibsdk/icons/<plugin-id>/`, which is `../mibsdk/…` from `ImageRoot`, the depth the `../mibsdk-spike/` spike proved. mibpkg removes them on remove and uninstall.
  - Icons are static per installed version: a new icon means a new package version.
  - Ids follow a content hash, so the renderer cache never serves a stale image.
  - PNGs are validated at build and again at install.
  - Runtime-generated plugin icons are out of scope; the runtime reads only the index.
- [my call] Files are content-addressed (`<sha16>.png`, SHA-256) and found through `index.properties`. A changed icon therefore gets a new path as well as a new id, and an old id never points at new bytes. The adapter hands out ids per `<owner>/<sha16>` key (session-lifetime allocation), so identical bytes in two packages never share an id. The id is not `hash mod range`, which would collide.
- [decided, preflight 35] A plugin PNG on a drawer row is drawn as `{id, id}`: the icon doubles as its reflection. This is an explicit bench check at R1 I1; if it looks wrong, a follow-up makes the build generate a faded variant. The built-in icons ship a real 72×68 reflection.
- [my call, owner informed] The status line's "stock" icons are the SDK's own named set (`StockIcons`: dot, circle, star, triangle, square, check, cross, plug), shipped in the SDK package and drawn by id, so they are tinted. Firmware ids are not used: the firmware has no usable ≤ 20 px glyphs with alpha.

**Status line**
- [decided] The status line moves to SDK popup 609600 in slot 7, where it coexists with popup 65. Items may carry a small icon, and the fair share counts the icon's width. `SAFE` / `PDC R OFF` stay must-show. Popup 65 is not written while 609600 works, and its text is the fallback.
- [my call] An icon is worth 2 characters (`ICON_CHARS`), and its popup cell is exactly 2 × 10 px. An item's icon is dropped before its text would fall below `min(text length, 3)` characters.
- [my call] The popup keeps Boot B's geometry, (514, 0, 255, 24): one indicator label and 6 item cells on a 10-px character grid. Cell bounds and id bitmaps go through the one coalesced HMI job; text and file icons are model writes.
- [decided, preflight 19] Boot window: until the stub is collected, the text goes to popup 65, with no hold timer. A popup failure is sticky for the boot.
- [decided, controller ruling 3] Live re-layout is checked at R1. The hide/re-show fallback is pre-written (Task 5 Step 6), and if it is needed it is verified at R3, inside the budget.

**System takeover and the bench**
- [decided] mibpkg manages `HMISystem=` exactly like `HMICar=`: a stock-value check, a byte-identical restore, and refusal on unknown builds and on a firmware change without `--force`. The activator is pure stock in swdl and on any Throwable, and it has no throwing static initialisers.
- [my call] The `[sdk]` keys `hmisystem_stock`/`hmisystem`/`icons` and `[plugin] icons` are optional. An SDK package without the first two leaves `HMISystem=` stock (the "mibpkg declined" fallback). They arrive with mibpkg 0.9.0.
- [decided] The failure path gets a deliberate bench test. As run (docs/bench-e2e-menu-icons.md), R2b forced the screen-46 failure (`ui.forcefail=screen`) with safe mode, and checked that the main menu is stock and the Car-drawer Plugins row is back. The `factory` failure is covered by host tests.
- [my call] The forced-failure switch is a file in persist, `/mnt/persist/mibsdk/dev/ui.forcefail` (`factory` or `screen`). Only the configurator reads it, and `mibsdk-dev ui-forcefail` sets it. R2 combines it with safe mode, which proves `SAFE` in the new popup without an extra restart.
- [decided] The restart budget is 3, at most 4. There is no R4 boot (preflight 26). The screen-46 failure path ran on the unit at R2b; the `factory` failure path is host-tested only.
- [my call] API level 3 is additive: `Icon`, `StockIcons`, `Ui.icon`/`stockIcon`, icon overloads of `carMenuRow`/`statusItem`, `StatusItem.set(text, icon)`, `Features.UI_ICONS`, and the manifest field `icons`.

**Open points for the owner:** none.
