> **Internal engineering record.** This page is kept for history and may be out of date. See [the internal docs index](../README.md).

# Icon colour (tinted or own colours) and drawer-row reflections Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A plugin chooses per icon whether a status-popup icon is tinted (the default) or drawn in its own colours, and every drawer-sized PNG gets a build-generated faded reflection that the Car drawer draws instead of the `{id, id}` copy (bench B5).

**Architecture:**
- **Colour** is a runtime widget property, not content. `Ui.icon(name, colour)` (api 4) sets a `UiIcon` flag that is in `equals` but not in the `IconIds` key. It travels snapshot → `StatusLayout.Cell` → `SdkStatusPopup.icons`, which sets `setColorIndices({1,0,4,0})` or `null` plus `setCompositesDirty(true)` on the cell's id and file icon. Drawer rows ignore it (firmware: the selected row is always multiplied by the app colour).
- **Reflections** are files. The plugin build writes `icons/<name>.r.png` (flipped, alpha 0.40 → 0, the built-in `-72r` transform), encoded with stored (uncompressed) deflate. mibpkg installs it as an extra `<sha16>.png` with an index line `<name>.r=<rsha16>,<w>,<h>`. `IconIndex` attaches it to `<name>`'s `UiIcon`, and `DrawerSlots` gives `{id, rid}`, else `{id, id}`.

**Tech Stack:** Unchanged: Gradle 8.13 with `build-logic`; JDK 17/Jabel → Retrolambda → Retrotranslator; class-file v48; JUnit 5.10.2; Python 3 `unittest`. mibpkg: Rust stable, `cargo test`.

**Spec:** `docs/research/2026-10-25-icon-colour-and-reflection.md` (611cf2b), §5 design, §7 bench check B1–B5. Prior design: `docs/superpowers/plans/2026-10-24-menu-and-icons.md`, `docs/bench-e2e-menu-icons.md` (findings B5, B10).

**Repos and order:**
- SDK, worktree `mib2q-plugin-sdk.worktrees/icon-colour`, branch `feat/icon-colour` (from main e3240cb): Tasks 1 → 2 → 3 → 5, strictly in sequence.
- mibpkg, worktree `mibpkg.worktrees/icon-colour`, branch `feat/icon-colour` (from main 4385df1, 0.9.0): Task 4 starts **after Task 3 is committed** (it needs the `.r` packaging contract and copies a Task 3 vector), and then runs in parallel with Task 5.
- Task 6 (bench) needs all of 1–5. Nobody touches the bench before Task 6.

## Global Constraints

Copied verbatim from the 2026-10-24 plan where they still bind:

**Bytecode and linking** (Plan 2, verbatim)
- Every class that runs on the unit is **class-file version 48**. v49/v50 are rejected by J9 2.3 even with `-noverify`. The build fails on anything else.
- Every on-unit artifact passes **LinkCheck** against the car JCL (`java/**`, `javax/**` extracted from `$mibsdk.stockLsdJar`) plus its declared car classpath.
- No stock constant in `adapter-hqc/src/main/java/mibsdk/adapter/hqc/ui/`. Every stock id (model, screen, event, text, popup, widgetID, atlas cell, key code) is a literal in `HqcUiIds`, taken from `lsd-src` (real values) and confirmed by a spike. `NoStockConstantsTest` enforces it.
- Platform and runtime code is Java 1.4 API: `StringBuffer`, no `codePointAt`, no try-with-resources.

**Threads** (Plan 2, verbatim)
- **No plugin code ever runs on a stock thread.**
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
- `<sha16>` = the first 16 lowercase hex characters of SHA-256 of the file bytes. Index lines are `<name>=<sha16>,<w>,<h>`, sorted by name, LF line ends.
- Image ids: one per `<owner>/<sha16>` key per HMI session, never reused or re-pointed. The owner is the plugin id, or `mibsdk` (the reserved plugin id) for the built-in set, so identical bytes in two packages never share an id or a file. The range is `609100..609399` (Car module, served by `SdkCarScreenFactory.getImagePath` from a precomputed relative path).
- Icon changes on Car-drawer rows show at the next Car screen connect, not live (Boot A I3). The API says so.

**Bench** (verbatim)
- SSH: `ssh -o HostKeyAlgorithms=ssh-rsa -o PubkeyAcceptedKeyTypes=ssh-rsa root@10.20.0.23`. Keypanel: `http://10.20.0.23:8099` (poll `/screen.jpg` twice).
- **Every HMI restart costs 10–12 min and can hang the unit** (P3-F12, Boot B). After a restart, run `ssh rpi-bench keypanel up`. If the unit is still unreachable after two tries, stop as BLOCKED and ask the owner to power-cycle. Never power the bench yourself. Batch checks so the bench task stays within its restart budget.
- Never select "Start update". Leave the unit on its home screen.

**Repo hygiene** (verbatim)
- Authorship `complex256` / `me@complex256.dev`: commit with `git -c user.name=complex256 -c user.email=me@complex256.dev commit`.
- No personal names or home paths in committed files. Stock jars are never committed.
- Before every commit: SDK: `./gradlew check` and `python3 -m unittest discover -s installer -p 'test_*.py'` pass; mibpkg: `cargo test` passes.

**Amended by this plan**
- API: additive only. `SdkInfo.API_VERSION = 4` and `MIN_API_VERSION = 1`, so manifests with api 1–4 load. Plugin limits are unchanged: ≤ 2 Car rows, ≤ 1 status item of ≤ 12 chars.
- API use: a plugin whose classes reference `mibsdk/api/Ui.icon(Ljava/lang/String;I)Lmibsdk/api/Icon;` must declare `"api": 4` or higher; the build refuses it otherwise (Task 1 scan).
- Index: one extra line kind, `<name>.r=<rsha16>,<w>,<h>`, only for a declared `<name>`, sorted with the rest by key bytes (`hello` < `hello-20` < `hello.r`). A `.r` file is never declared in `plugin.json` and never counts toward the 8 / 32 limits.
- Reflection recipe (from `tools/make_builtin_icons.py` `reflection`), authoritative here:
  - generated for every **plugin** icon with `h > 20`; the SDK set gets none (its built-ins already ship `-72r`, which `BuiltinIcons` uses);
  - height, integer arithmetic: `H = (h * 68 + 36) / 72` in Java, `(h * 68 + 36) // 72` in Python (72 → 68, 45 → 43, 40 → 38, 21 → 20; so `H ≥ 20`);
  - row `i` (0..H−1) = source row `h − 1 − i`, same width, RGB kept (LA expands to `(g, g, g, a)`);
  - alpha, exactly this Java expression: `(int) Math.rint(a * (0.4 * (1.0 - (double) i / (H - 1))))`, which is Python's `round(a * (0.4 * (1 - i / (H - 1))))` (both half-even, same operation order);
  - written as 8-bit RGBA (colour type 6), filter 0 on every row, one zlib stream of **stored** deflate blocks (BTYPE 00), no compression. Deterministic and independent of the JDK's zlib.
  - Size bound: raw data ≤ 68 · (1 + 72 · 4) = 19652 bytes; with the zlib header, one stored-block header (5 bytes), Adler-32 and the PNG framing (signature, IHDR, IDAT and IEND chunks), the file is ≤ **19720 bytes**, under the 32768 cap. `PngCheck` still checks every `.r`.
- Bench restart budget for Task 6: **1**, at most **2** (the 2nd only to recover from a failed boot).

## Review Focus

1. **A missing `.r`**: an old package, an index `.r` line whose file is gone, or a `.r` line with no base. The row shows `{id, id}` and nothing throws. **Tests:** Task 5 `IconIndexTest.reflectionLineWithoutItsFileOrBaseIsIgnored`, `DrawerSlotsTest.fileIconWithoutReflectionFallsBackToIdId`; Task 4 `plugin_install_without_reflection_writes_no_r_line`.
2. **An old runtime (api 3) reads a new index.** It must keep `hello` intact and skip `hello.r`. **Tests:** Task 5 `IconIndexTest.reflectionKeyIsNotAName`; Task 6 host check H3 runs e3240cb's `IconIndex` on a 0.9.1 index.
3. **A toggled colour not redrawing live.** Only the flag changes (same id, same file): `sameIcons` must see it and both controllers must get the indices and `setCompositesDirty(true)`. **Tests:** Task 2 `SdkStatusPopupTest.colourToggleReappliesIndicesAndMarksBothIconsDirty`, `StatusLayoutTest.cellCarriesOwnColoursAndSameIconsSeesIt`; Task 1 `UiImplTest.statusSetWithOtherColourIsASnapshotChange`.
4. **A flag not re-applied after a popup rebuild.** New controllers start with `null` indices; an own-colour cell must be set again by the build's `icons(wanted, EMPTY)`. **Test:** Task 2 `SdkCarScreenFactoryTest.rebuiltPopupReappliesOwnColours`.
5. **The selected drawer row.** The flag must not touch drawer bitmaps or ids, and the Javadoc must say the selected row is multiplied by the app colour. **Tests:** Task 2 `DrawerSlotsTest.ownColoursDoesNotChangeBitmaps`, `DrawerSlotsTest.colourDoesNotChangeTheId`; bench B4.

---

### Task 1: API level 4 colour choice, carried by `UiIcon`, with a build-time api check; hello-menu 1.2.0 demonstrates it — SDK repo

**Files:**
- Modify:
  - `api/src/main/java/mibsdk/api/Icon.java`, `Ui.java`, `SdkInfo.java`; `api/permissions.json` (`"api": 4`);
  - `platform/src/main/java/mibsdk/platform/UiIcon.java`; `runtime/src/main/java/mibsdk/runtime/UiImpl.java`; `adapter-fake/src/main/java/mibsdk/adapter/fake/FakeUi.java`;
  - `build-logic/src/main/java/mibsdk/build/PluginJson.java` (`MAX_API = 4`), `PermissionScan.java`, `PermissionScanTask.java`, `MibPluginPlugin.java` (wires the declared api into the scan task);
  - `tools/make_builtin_icons.py` (`plugin_sample` only);
  - `plugins/hello-menu/src/main/resources/plugin.json`, `src/main/icons/hello.png`, `hello-20.png`, `HelloMenuPlugin.java`.
- Test: `runtime/src/test/java/mibsdk/runtime/UiImplTest.java`, `ManifestParserTest.java`; `api/src/test/java/mibsdk/api/PermissionCatalogTest.java` (passes once the json says 4); `adapter-fake/src/test/java/mibsdk/adapter/fake/FakeUiTest.java`; `build-logic/src/test/java/mibsdk/build/PluginJsonTest.java`, `PermissionScanTest.java`, `MpkStageTest.java`, `MibPluginPluginFunctionalTest.java`; `plugins/hello-menu/src/test/java/sample/hellomenu/HelloMenuPluginTest.java`; `installer/test_make_builtin_icons.py`

**Interfaces:**
- Produces:
  - `mibsdk.api.Icon`: `int TINTED = 0; int OWN_COLOURS = 1;` (constants only).
  - `Ui.icon(String name, int colour)`; `icon(name) == icon(name, Icon.TINTED)`. Any other `colour` → `IllegalArgumentException("colour must be Icon.TINTED or Icon.OWN_COLOURS")`. The Javadoc says "Needs api 4." and then, verbatim from the spec: "Status items honour it. Car rows ignore it: the drawer draws every row icon in its own colours and multiplies the selected row by the app colour, so ship a white glyph for the stock look."
  - `stockIcon(name)` is unchanged and tinted only (its Javadoc says why: the built-ins are white glyphs).
  - `UiIcon.ownColours()`, `UiIcon withOwnColours(boolean)`: in `equals`/`hashCode`/`toString`; `key()` and `owner()` unchanged.
  - `UiImpl` caches one handle per (name, colour). A declared-but-not-installed icon is inert in both colours.
  - `FakeUi` tag: `[png:<key>+own] ` for an own-colour file icon **in status parts only**. Car-row tags never show `+own`, because rows ignore the flag.
  - `SdkInfo.API_VERSION = 4`; `PluginJson.MAX_API = 4`; `MpkStage.sdkManifest` follows `MAX_API`, so it stages `api = 4`.
  - `PermissionScan.Result.needsApi() -> int`: 4 when any class references `mibsdk/api/Ui.icon(Ljava/lang/String;I)Lmibsdk/api/Icon;`, else 1. Each such site is also listed by `apiFindings()` as `owner.method -> Ui.icon(String,int)`.
  - `PermissionScanTask` gets `@Input Property<Integer> declaredApi` (from `plugin.json`). It fails with `<site> needs api 4; plugin.json declares api <n>` when `needsApi() > declaredApi`.

- [ ] **Step 1: Write the failing tests:**
  - `UiImplTest.iconWithoutColourIsTinted`: `icon("hello")` equals `icon("hello", Icon.TINTED)` and its `UiIcon.ownColours()` is false;
  - `UiImplTest.ownColoursKeepsTheKey`: `icon("hello", OWN_COLOURS)` has `ownColours()` true and the same `key()` as the tinted one, but is not `equals`;
  - `UiImplTest.unknownColourIsRefused`: colour 2 throws `IllegalArgumentException`;
  - `UiImplTest.statusSetWithOtherColourIsASnapshotChange`: `set("Hello", icon("hello-20", OWN_COLOURS))` after the tinted one publishes a new snapshot;
  - `ManifestParserTest.acceptsApi1To4AndIconsOnlyWithApi3` (renamed from `…1To3…`): api 4 loads, and api 5 is refused with "1..4";
  - `PluginJsonTest`: `MAX_API` is 4; the out-of-range probe `fail4` becomes `fail5`;
  - `MibPluginPluginFunctionalTest`: the `api4` fixture becomes `api5` (it must still fail);
  - `MpkStageTest`: the staged SDK manifest has `api = 4`;
  - `PermissionScanTest.findsTheColourIconOverload`: a class calling `ui.icon("x", Icon.OWN_COLOURS)` gives `needsApi() == 4`, and one calling only `icon(String)` gives 1;
  - `MibPluginPluginFunctionalTest.colourOverloadNeedsApi4`: a fixture plugin with `"api": 3` that calls `icon(String,int)` fails `mibPackage` with "needs api 4"; the same plugin with api 4 builds;
  - `FakeUiTest.ownColourIconTag`: the status part reads `[png:<key>+own] Hello`, and a Car row with the same own-colour icon reads `[png:<key>] Hello`;
  - `HelloMenuPluginTest.statusIconIsOwnColoursAndToggles`: the status part starts `[png:<key>+own]`; "Status icon colour" makes it `[png:<key>]`, and again `+own`; the choice is kept in storage key `colour` across disable/enable;
  - `HelloMenuPluginTest.manifestIsApi4Version120`;
  - `test_make_builtin_icons.PluginSampleTest.test_left_white_right_blue`: in the tool's `plugin_sample()` output, opaque pixels left of the glyph box's centre (`x < 36` at 72 px, `x < 9` at 20 px) are (255, 255, 255) and the rest are (0, 0, 255); and the committed `plugins/hello-menu/src/main/icons/hello*.png` are byte-equal to that output.
- [ ] **Step 2: Run them and verify they fail:** `./gradlew :runtime:test :adapter-fake:test :api:test :plugins:hello-menu:test :build-logic:test && python3 -m unittest installer.test_make_builtin_icons`. Expected: FAIL (no `Icon.TINTED`, no overload, `MAX_API` 3).
- [ ] **Step 3: Implement.**
  - `plugin_sample()`: the same ring-and-dot shape, white left of the glyph box's centre (the 18-px box's centre for `hello-20`), (0, 0, 255) right of it. Regenerate both PNGs with `--plugin-sample`.
  - hello-menu: `plugin.json` gets `api: 4` and `version: "1.2.0"`. The status item uses `ui.icon("hello-20", colour)`, default `OWN_COLOURS`. A page item `status-colour`, "Status icon colour", flips it. The STAR/DOT stages of "Next status icon" stay stock (tinted). The row keeps `ui.icon("hello")`.
- [ ] **Step 4: Run and verify:** `./gradlew check && ./gradlew :build-logic:test && python3 -m unittest discover -s installer -p 'test_*.py'`. Expected: PASS.
- [ ] **Step 5: Commit:** `git add api platform runtime adapter-fake build-logic tools installer plugins/hello-menu && git commit -m "feat(api): per-icon colour choice, Icon.TINTED / OWN_COLOURS (api 4, checked at build); hello-menu 1.2.0 shows it"`

---

### Task 2: The status popup honours the flag; the drawer ignores it — SDK repo

**Files:**
- Modify: `adapter-hqc/src/main/java/mibsdk/adapter/hqc/ui/StatusLayout.java`, `SdkStatusPopup.java`, `HqcUiIds.java`, `DrawerSlots.java` (comment only)
- Test: `StatusLayoutTest.java`, `SdkCarScreenFactoryTest.java`, `DrawerSlotsTest.java`, new `SdkStatusPopupTest.java` (all in `adapter-hqc/src/test/java/mibsdk/adapter/hqc/ui/`)

**Interfaces:**
- Consumes: `UiIcon.ownColours()`, `withOwnColours` (Task 1).
- Produces:
  - `HqcUiIds.STATUS_ICON_OWN_COLOURS = {1, 0, 4, 0}` (popup vis state 1 → plate entry 0, white).
  - `StatusLayout.Cell.ownColours` (final boolean), set from `icon.ownColours()` at the cell build (`StatusLayout.java:89`). `sameIcons` also compares it. That also feeds `HmiWidgetState.equals`, so a toggle posts a job. The stale `Cell.filePath` doc ("currently tinted … B10") is updated.
  - `SdkStatusPopup.icons(want, last)`: when cell `i`'s `ownColours` differs from `last`, call `setColorIndices(own ? HqcUiIds.STATUS_ICON_OWN_COLOURS : null)` and then `setCompositesDirty(true)` on **both** `idIcons[i]` and `fileIcons[i]`. The existing bitmap logic is unchanged. The class doc's "a per-icon choice is a planned follow-up" is replaced by a description of the flag.
  - Rebuilds need no new code: `SdkStatusPopup.build` already runs `place(wanted)` and `icons(wanted, EMPTY)`, and `EMPTY` cells are tinted, so every own cell is applied. `HmiWidgets` is **not** changed.
  - Tests use the existing package-private `SdkStatusPopup(LabelController[], IconController[], IconController[])` constructor with real `IconController`s after `HostWidgets.init()`. They read back through `getColorIndices()` and `getRenderer().areCompositesDirty()`.

- [ ] **Step 1: Write the failing tests:**
  - `StatusLayoutTest.cellCarriesOwnColoursAndSameIconsSeesIt`: two layouts that differ only in the flag are `sameGeometry`, not `sameIcons`;
  - `SdkStatusPopupTest.colourToggleReappliesIndicesAndMarksBothIconsDirty`: tinted → own gives `{1,0,4,0}` and dirty on both controllers of that cell only; own → tinted gives `null` and dirty;
  - `SdkStatusPopupTest.unchangedColourTouchesNothing`: indices unchanged, nothing marked dirty;
  - `SdkCarScreenFactoryTest.rebuiltPopupReappliesOwnColours`: `getPartialPopup(609600)` with an own-colour `wanted` layout gives the new popup's `idIcons`/`fileIcons` for that cell `getColorIndices()` = `{1,0,4,0}`, and `null` for the other cells;
  - regression guards (they pass before any Task 2 code, so they are not TDD failures):
    - `DrawerSlotsTest.ownColoursDoesNotChangeBitmaps`: `bitmaps(f.withOwnColours(true))` equals `bitmaps(f)`;
    - `DrawerSlotsTest.colourDoesNotChangeTheId`: `DrawerSlots.id(f) == DrawerSlots.id(f.withOwnColours(true))` with a single allocation (the next free id is unchanged), and the status cell's `filePath` is the same for both.
- [ ] **Step 2: Run and verify the new tests fail:** `./gradlew :adapter-hqc:test`. Expected: FAIL for the four non-guard tests.
- [ ] **Step 3: Implement** as specified. `DrawerSlots` gets only a one-line comment saying the flag is ignored on purpose (spec §2.2).
- [ ] **Step 4: Run and verify:** `./gradlew check` (it includes `NoStockConstantsTest` and LinkCheck). Expected: PASS.
- [ ] **Step 5: Commit:** `git add adapter-hqc && git commit -m "feat(hqc): status popup draws own-colour icons (setColorIndices per cell, live)"`

---

### Task 3: The plugin build generates faded reflections — SDK repo

**Files:**
- Create: `build-logic/src/main/java/mibsdk/build/Reflection.java`; `build-logic/src/test/java/mibsdk/build/ReflectionTest.java`
- Modify:
  - `build-logic/src/main/java/mibsdk/build/PngCheck.java` (exposes the decoded pixels; one decoder);
  - `MpkStage.java`, `MibPluginPlugin.java` (the 3-argument `stageIcons` call at `:157`);
  - `dist/build.gradle.kts` (passes `reflect = false`);
  - `tools/make_builtin_icons.py` (`fixtures()` gains the reflection and filter vectors; `reflection()` callers use the integer height);
  - `build-logic/src/test/resources/png/*` (regenerated by `--fixtures`), `installer/test_make_builtin_icons.py`.
- Test: `ReflectionTest.java`, `PngCheckTest.java`, `MpkStageTest.java`, `MibPluginPluginFunctionalTest.java`, `installer/test_make_builtin_icons.py`

**Interfaces:**
- Consumes: the recipe in Global Constraints.
- Produces:
  - `PngCheck.rgba(byte[] png) -> Pixels {int width, height; byte[] rgba}`: it runs `check(png, 72)`, then reuses the same inflate (refactor the private `inflateExactly` so it returns the rows), un-filters types 0–4, and expands LA to RGBA. There is no second chunk walker.
  - `Reflection.of(Pixels src) -> Pixels`: the recipe.
  - `Reflection.encode(Pixels p) -> byte[]`: RGBA, filter 0, stored deflate (one or more BTYPE-00 blocks of ≤ 65535 bytes, zlib header `78 01`, Adler-32), CRCs via `java.util.zip.CRC32`.
  - `MpkStage.stageIcons(List<String> names, File from, File stage, boolean reflect)`: when `reflect`, every icon with `h > 20` also gets `icons/<name>.r.png`, which is checked with `PngCheck.check(bytes, 72)`. A failure fails the stage with `icon <name>.r: <rule>`. `MibPluginPlugin` passes `true`, and `dist` passes `false` (Global Constraints). The `.r` names never go into `icons = [...]`.
  - `fixtures()` also writes, under `build-logic/src/test/resources/png/`:
    - `reflect-rgba-48x45.png` (coloured) with its Python `reflect-rgba-48x45.r.png` (48×43);
    - `reflect-la-72.png` (grey + alpha) with `reflect-la-72.r.png` (72×68);
    - `filters-1234.png`: RGBA rows with Sub, Up, Average and Paeth filters, plus `filters-1234.rgba`, its raw pixels.

    The Python vectors use the same recipe (integer `H`, the same alpha expression) and the tool's own compressor. They are compared by **pixels**, not bytes.

- [ ] **Step 1: Write the failing tests:**
  - `ReflectionTest.matchesTheBuiltinReflectionsPixelForPixel`: for each of the 8 glyphs, `rgba(encode(of(rgba(dist/icons/<g>-72.png))))` equals `rgba(dist/icons/<g>-72r.png)`;
  - `ReflectionTest.matchesThePythonVectors`: each `reflect-*.png` → pixels equal to its `reflect-*.r.png`;
  - `ReflectionTest.keepsRgbAndFadesAlpha`: an opaque (0, 0, 255) source gives row 0 alpha 102, the last row alpha 0, and RGB (0, 0, 255) kept;
  - `ReflectionTest.heightScales`: 72 → 68, 45 → 43, 40 → 38, 21 → 20;
  - `ReflectionTest.encodingIsStoredDeterministicAndBounded`: encoding twice gives identical bytes; `PngCheck.check` accepts the result; a 72×72 noise source gives ≤ 19720 bytes;
  - `PngCheckTest.rgbaUnfiltersAllFiveTypes`: `rgba(filters-1234.png)` equals `filters-1234.rgba`;
  - `MpkStageTest.pluginStageWritesReflectionsForDrawerSizedIconsOnly`: `hello` → `icons/hello.r.png` at 72×68; `hello-20` gets none; `[plugin] icons` is unchanged;
  - `MpkStageTest.sdkStageWritesNoReflections`: with `reflect = false`, no `*.r.png` is staged;
  - `MibPluginPluginFunctionalTest.mibPackageContainsTheReflection`: the `icons` fixture plugin's package contains `icons/<name>.r.png` for its drawer-sized icon;
  - `test_make_builtin_icons`: `test_named_fixtures_are_the_shared_vectors` lists the 6 new names, and `test_output_is_byte_identical_to_the_committed_files` still passes (one generator, no subdirectory).
- [ ] **Step 2: Run and verify they fail:** `./gradlew :build-logic:test`. Expected: FAIL (no `Reflection`, no `PngCheck.rgba`).
- [ ] **Step 3: Implement** as specified. Regenerate with `python3 tools/make_builtin_icons.py --fixtures build-logic/src/test/resources/png` and commit the vectors.
- [ ] **Step 4: Run and verify:** `./gradlew check && ./gradlew :build-logic:test && python3 -m unittest discover -s installer -p 'test_*.py'`. Then `./gradlew :plugins:hello-menu:mibPackage :dist:mibpkgPackage -Pmibsdk.mibpkg=<mibpkg 0.9.0>` builds both; the hello-menu package contains `icons/hello.r.png`, and the SDK package contains no `.r`. Expected: PASS.
- [ ] **Step 5: Commit:** `git add build-logic dist tools installer && git commit -m "build: plugin packages carry a faded drawer reflection (<name>.r.png, stored deflate)"`

---

### Task 4: mibpkg 0.9.1 installs reflections and writes `.r` index lines — mibpkg repo (after Task 3)

**Files:**
- Modify: `src/mibsdk/icons.rs`, `Cargo.toml`, `Cargo.lock` (version 0.9.1), `README.md`
- Create: `tests/fixtures/icons/reflect-la-72.r.png` (a byte copy of Task 3's `build-logic/src/test/resources/png/reflect-la-72.r.png`, under the same name, as the fixtures' header comment requires)
- Test: `tests/plugin_pkg.rs` (the plugin tests; its helpers `plugin_src_icons`, `install_src`, `with_sdk`, `icon_dir`, `plugin_jar`, `GRANTS` live there), `tests/mibsdk_icons.rs` (one `check_from` test), `tests/sdk_pkg.rs`

**Interfaces:**
- Produces:
  - `icons::reflection_file_of(name: &str) -> String` = `icons/<name>.r.png`.
  - `check_from(src, names)`: for each declared name it also reads the **optional** `reflection_file_of(name)`.
    - Absent (`NotFound`, or `Source::Package(p).file()` is `None`): skipped.
    - Present: it is checked with `png_check(&bytes, MAX_PX)` and pushed as one more `Checked { name: "<name>.r", .. }`. An error reads `icon <name>.r: <rule>`, and the callers add `; nothing changed`.
    - A `.r` file for an undeclared name is never read.

    `install`, `index_text` and `drop_files_except` work unchanged on the longer list.
  - No count check covers the `.r` entries (manifest limits count declared names only).
  - `tests/plugin_pkg.rs` gains a helper `plugin_src_icons_with_reflection(..)`, which also writes `icons/<name>.r.png` and optionally an undeclared `icons/other.r.png`.

- [ ] **Step 1: Write the failing tests:**
  - in `tests/plugin_pkg.rs`:
    - `plugin_install_writes_reflection_file_and_index_line`: the index is `hello=<s>,72,72\nhello-20=<s2>,20,20\nhello.r=<rs>,72,68\n`, and `<rs>.png` equals the source bytes;
    - `plugin_install_without_reflection_writes_no_r_line`: the index has only the 0.9.0 lines;
    - `plugin_install_with_a_bad_reflection_writes_nothing`: a `.r` that is `bad-crc.png` refuses with `icon hello.r:` and leaves no jar, grants line or icon dir;
    - `reflection_of_an_undeclared_name_is_ignored`;
    - `update_dropping_the_reflection_removes_its_file`;
  - `mibsdk_icons::check_from_adds_an_optional_reflection_entry` (both the `Dir` and the `Package` source);
  - `sdk_pkg::sdk_install_writes_reflection_lines`: uses a fixture SDK package that carries one `.r` (the real SDK package has none).
- [ ] **Step 2: Run and verify they fail:** `cargo test --test plugin_pkg --test mibsdk_icons --test sdk_pkg`. Expected: FAIL.
- [ ] **Step 3: Implement**, bump the version to 0.9.1, and add one README paragraph on the `.r` line and on why old runtimes ignore it.
- [ ] **Step 4: Run and verify:** `cargo test`. Expected: PASS.
- [ ] **Step 5: Commit:** `git add src tests Cargo.toml Cargo.lock README.md && git commit -m "feat(sdk): install optional icon reflections (<name>.r index line); 0.9.1"`

---

### Task 5: The runtime reads `.r` and drawer rows use `{id, rid}` — SDK repo (after Task 3)

**Files:**
- Modify: `platform/src/main/java/mibsdk/platform/UiIcon.java`, `IconIndex.java`; `adapter-hqc/src/main/java/mibsdk/adapter/hqc/ui/DrawerSlots.java`
- Test: `platform/src/test/java/mibsdk/platform/IconIndexTest.java`, `adapter-hqc/src/test/java/mibsdk/adapter/hqc/ui/DrawerSlotsTest.java`

**Interfaces:**
- Consumes: the index line `<name>.r=<rsha16>,<w>,<h>` (the Task 4 contract, fixed in Global Constraints); `UiIcon.withOwnColours` (Task 1).
- Produces:
  - `UiIcon.reflection() -> UiIcon` (a FILE icon with the same owner, or null) and `UiIcon withReflection(UiIcon r)`. Both are in `equals`. `withOwnColours` keeps the reflection. `UiImpl` needs no change: its handle wraps the `IconIndex` entry.
  - `IconIndex`: the value parsing moves out of `entry` into `private static UiIcon value(String owner, File dir, String v)` (key, w, h, file present), and both passes use it. The name lines are parsed exactly as today. Then each key with `key.endsWith(".r")` and `base = key.substring(0, key.length() - 2)` that satisfies `isName(base)`, has a present `<base>` entry and has a non-null `value(...)` replaces `<base>` with `base.withReflection(...)`. Anything else is ignored. It is Java 1.4 API (no `contains`/`isEmpty`). `isName` is unchanged, and `get("<base>.r")` is null.
  - `DrawerSlots.bitmaps` for FILE: `f = id(icon)`, `r = icon.reflection() == null ? -1 : id(icon.reflection())`. It returns `{f, r}` when `r ≥ 0`, else `{f, f}`. The reflection's id comes from `IconIds.idFor(owner, rsha16, abs, rel)` under `owner/<rsha16>` and is served by `getImagePath` like any file id. The Javadoc of `bitmaps` is updated.

- [ ] **Step 1: Write the tests** (each old/new mix a unit test can reach):
  - failing first:
    - `IconIndexTest.readsReflectionIntoItsBase` (new runtime, new index);
    - `IconIndexTest.reflectionLineWithoutItsFileOrBaseIsIgnored`;
    - `DrawerSlotsTest.fileIconWithReflectionUsesItsOwnId`: two distinct ids, and the reflection's path is `../mibsdk/icons/<plugin>/<rsha16>.png`;
    - `DrawerSlotsTest.fileIconWithoutReflectionFallsBackToIdId`;
    - `DrawerSlotsTest.spentRangeForTheReflectionFallsBackToIdId`;
  - regression guards (they pass on today's code, and they test this jar's reader; H3 tests the old one):
    - `IconIndexTest.indexWithoutReflectionsReadsAsBefore` (new runtime, old index);
    - `IconIndexTest.reflectionKeyIsNotAName`: `isName("hello.r")` is false, `get("hello.r")` is null, and `hello`'s key, path and size match an index without the line.

  The two mixes with old binaries (old mibpkg + new package; old runtime + new index) cannot be unit tests. They are Task 6's host checks H2 and H3.
- [ ] **Step 2: Run and verify the new tests fail:** `./gradlew :platform:test :adapter-hqc:test`. Expected: FAIL for the five non-guard tests.
- [ ] **Step 3: Implement** as specified.
- [ ] **Step 4: Run and verify:** `./gradlew check && python3 -m unittest discover -s installer -p 'test_*.py'`. Expected: PASS.
- [ ] **Step 5: Commit:** `git add platform adapter-hqc && git commit -m "feat(ui): drawer rows draw the package's faded reflection ({id, rid}), else {id, id}"`

---

### Task 6: Bench check B1–B5 — BENCH (restart budget: 1 planned, 2 at most; the 2nd only to recover)

**Files:**
- Create: `docs/bench-e2e-icon-colour.md`, `docs/bench-e2e/icon-colour-*.jpg`, `docs/bench-e2e/icon-colour-log-r1.txt`

**Host first (no unit):** build mibpkg 0.9.1 (Task 4), plus mibpkg 0.9.0 from main 4385df1. Build `mibsdk` (api 4) and hello-menu 1.2.0 with a `+bench.N` version.
- [ ] H1: `mibpkg-0.9.1 --root <fake1> install-file` of both packages. `<fake1>/…/icons/hello-menu/index.properties` has `hello.r=<rs>,72,68` and `<rs>.png` exists. `icons-sdk/index.properties` has no `.r` line.
- [ ] H2 (old mibpkg + new package): `mibpkg-0.9.0 --root <fake2>` installs both, and the index has no `.r` line.
- [ ] H3 = spec B5 (old runtime + new index): with `jshell --class-path <platform.jar>:<api.jar>`, both built at e3240cb,, `IconIndex.read("hello-menu", <fake1 dir>)`: `get("hello")` is the 72×72 entry and `get("hello.r")` is null. The same call with this branch's jar on `<fake2>` gives `hello` with no reflection.

**On the unit, before the restart:**
- [ ] `curl -s 10.20.0.23:8099/status` → `bridge_connected:true`. Upgrade mibpkg to 0.9.1 as `docs/bench-e2e-ui.md` "Builds and deploys" did. Install `mibsdk` and hello-menu 1.2.0 with mibpkg. Check that `mibpkg sdk status` shows `api: 1..4`, and that the unit's hello-menu index has `hello.r`.

**R1 (restart 1).** Run `installer/mibsdk-dev restart`, then `ssh rpi-bench keypanel up`. Two failed tries → BLOCKED, and the owner power-cycles. If the HMI stays black more than 5 min after SSH answers, run the emergency revert below and stop.
- [ ] B1: the status popup on Car and on Media (`-status-own-{car,media}.jpg`): hello-20 is white/blue. Then "Status icon colour" → tinted (`-status-tinted-{car,media}.jpg`): red/black on Car, yellow/black on Media. daemon-status's built-in cross stays tinted in all four frames.
- [ ] B2: with the popup up, toggle twice. The cell changes within ~1 s with no screen change (`-status-toggle.jpg`).
- [ ] B3: Car → drawer. A zoomed crop of the Hello row beside a stock row (`-drawer-reflection-zoom.png`): the icon is white/blue, and the reflection is faded with no "8". The log has two distinct `getImagePath 6091xx` ids for `hello-menu`.
- [ ] B4: open the Hello page from the drawer, then open the drawer again (`-drawer-selected.jpg`). The Hello icon and its reflection are multiplied by red, and the blue half is almost black.
- [ ] Save `/tmp/mibsdk.log` as `icon-colour-log-r1.txt`.

If B2 fails, record it as a finding. The spec's fallback (`processStateChange` 0 at build plus a popup rebuild on a change) is a follow-up, with no second restart for it. If B3's reflection looks wrong next to stock, record the spec §3.3 polish (a 20-row band, ramp to row 57) as a follow-up.

**Emergency revert** (blank HMI): SSH still works. Run `mount -uw /mnt/app`, then copy mibpkg's stock backup over `/mnt/app/eso/hmi/lsd/bundles_dsitracer_enabled.properties` and `chmod 777` it. Then `sync; mount -ur /mnt/app; slay -s11 j9`, run `mibpkg sdk status`, and report BLOCKED with the log.

- [ ] Write `docs/bench-e2e-icon-colour.md`: H1–H3, B1–B4, the findings table, the frames, the restart time, and the end state (SDK and hello-menu 1.2.0 installed, unit on its home screen).
- [ ] Commit: `git add docs/bench-e2e-icon-colour.md docs/bench-e2e && git commit -m "docs: bench check for icon colour and drawer reflections"`

---

## Decisions in this plan

[decided] comes from the owner or a controller ruling; [my call] is a planning choice open to review.

- [decided, owner] Plugins choose per icon: TINTED (the default) or OWN_COLOURS.
- [decided, controller] The API is `Icon.TINTED = 0`, `Icon.OWN_COLOURS = 1` and `Ui.icon(String, int)`, at api 4, additive (manifests api 1–4 load). The flag is in `UiIcon.equals`, not in the `IconIds` key. Status popup only: `setColorIndices({1,0,4,0})`/`null` plus `setCompositesDirty(true)` on both of a cell's icons. Drawer rows ignore it (firmware limit), and the Javadoc says so.
- [decided, from the spec §5.1] `stockIcon(...)` gets no colour choice: the built-ins are white glyphs, so own colours would only draw them white.
- [decided, controller] Faded reflections are generated by the build with the built-in `-72r` recipe, validated by `PngCheck`, installed by mibpkg with a separate `<name>.r=` line, and validated with `png_check`. The runtime uses `{id, rid}`, else `{id, id}`.
- [decided, preflight 10] The build writes `.r` with stored (uncompressed) deflate. There is no Java port of the Python compressor. Tests compare decoded pixels (through `PngCheck`'s own inflate) with the Python `-72r` output and vectors. Packages stay reproducible, and a `.r` is ≤ 19720 bytes, under the 32 KB cap.
- [decided, preflight 12] The SDK set gets no `.r`. Its `-72.r` would be a copy of `-72r`, which `BuiltinIcons` already uses. Moving `BuiltinIcons` to `.r` and retiring `-72r` is a possible later change.
- [decided, preflight 13] The build refuses a plugin that references `Ui.icon(String,int)` with api < 4 (an extension of the permission scan), so an api-3 package can never hit `NoSuchMethodError` on an api-3 runtime.
- [decided, ledger] Task 4 runs after Task 3, then in parallel with Task 5.
- [my call] mibpkg is **0.9.1**, not 0.10.0. The change is additive in both directions: an old mibpkg ignores the extra package file (H2), an old runtime ignores the `.r` key (`isName`), and no existing line or package changes meaning.
- [my call, preflight 9] The integer height `(h·68 + 36)/72` (half up) is authoritative, rather than the spec's `round(h·68/72)` (half-even). They differ only at h = 45 (43 against 42). The Python vectors use the same formula.
- [my call] In mibpkg a reflection is just one more `Checked` entry named `<name>.r`, so install, index writing and cleanup need no new code path. A bad `.r` refuses the whole install, the same as a bad icon.
- [my call] The mixes with old binaries (old mibpkg + new package, old runtime + new index) are host checks H2/H3 in the bench task, because unit tests cannot run old binaries. Task 5 pins the reader invariant (`reflectionKeyIsNotAName`) that makes H3 hold.
- [my call] B1 uses one status item and the toggle, because the plugin limit is one status item (Global Constraints). This differs from the spec's "two status items"; the four frames show the same comparison.
- [my call] hello-menu's sample art becomes the ring-and-dot shape coloured left white, right (0, 0, 255), so the shipped plugin is also the bench's tint-proof test PNG. Its status icon defaults to OWN_COLOURS, with a "Status icon colour" page toggle stored in `colour`. Its version is 1.2.0.
- [my call] With a restart budget of 1, the B2 fallback and the reflection polish are recorded as follow-ups, not run.

**Open points for the owner:** none.
