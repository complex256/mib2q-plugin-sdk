# Icon colour (tinted or own colours) and drawer-row reflections Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A plugin chooses per icon whether a status-popup icon is tinted (the default) or drawn in its own colours, and every drawer-sized PNG gets a build-generated faded reflection that the Car drawer draws instead of the `{id, id}` copy (bench B5).

**Architecture:**
- **Colour** is a runtime widget property, not content. `Ui.icon(name, colour)` (api 4) sets a `UiIcon` flag that is in `equals` but not in the `IconIds` key. It travels snapshot → `StatusLayout.Cell` → `SdkStatusPopup.icons`, which sets `setColorIndices({1,0,4,0})` or `null` plus `setCompositesDirty(true)` on the cell's id and file icon. Drawer rows ignore it (firmware: the selected row is always multiplied by the app colour).
- **Reflections** are files. The build writes `icons/<name>.r.png` (flipped, alpha 0.40 → 0, the built-in `-72r` transform). mibpkg installs it as an extra `<sha16>.png` with an index line `<name>.r=<rsha16>,<w>,<h>`. `IconIndex` attaches it to `<name>`'s `UiIcon`, and `DrawerSlots` gives `{id, rid}`, else `{id, id}`.

**Tech Stack:** Unchanged: Gradle 8.13 with `build-logic`; JDK 17/Jabel → Retrolambda → Retrotranslator; class-file v48; JUnit 5.10.2; Python 3 `unittest`. mibpkg: Rust stable, `cargo test`.

**Spec:** `docs/research/2026-10-25-icon-colour-and-reflection.md` (611cf2b), §5 design, §7 bench check B1–B5. Prior design: `docs/superpowers/plans/2026-10-24-menu-and-icons.md`, `docs/bench-e2e-menu-icons.md` (findings B5, B10).

**Repos and order:**
- SDK, worktree `mib2q-plugin-sdk.worktrees/icon-colour`, branch `feat/icon-colour` (from main e3240cb): Tasks 1 → 2 → 3 → 5.
- mibpkg, worktree `mibpkg.worktrees/icon-colour`, branch `feat/icon-colour` (from main 4385df1, 0.9.0): Task 4, in parallel with 1–3.
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
- Index: one extra line kind, `<name>.r=<rsha16>,<w>,<h>`, only for a declared `<name>`, sorted with the rest by key. A `.r` file is never declared in `plugin.json` and never counts toward the 8 / 32 limits.
- Reflection recipe (from `tools/make_builtin_icons.py` `reflection`): same width, `H = (h·68 + 36) / 72` rows (integer division; 68 for 72), row `i` = source row `h−1−i`, RGB kept, alpha `= rint(a · 0.4 · (1 − i/(H−1)))` in `double` with half-even rounding (Python `round`), written RGBA 8-bit. Generated for every icon with `h > 20`.
- Bench restart budget for Task 6: **1**, at most **2** (the 2nd only to recover from a failed boot).

## Review Focus

1. **A missing `.r`**: an old package, an index `.r` line whose file is gone, or a `.r` line with no base. The row shows `{id, id}` and nothing throws. **Tests:** Task 5 `IconIndexTest.reflectionLineWithoutItsFileOrBaseIsIgnored`, `DrawerSlotsTest.fileIconWithoutReflectionFallsBackToIdId`; Task 4 `plugin_install_without_reflection_writes_no_r_line`.
2. **An old runtime (api 3) reads a new index.** It must keep `hello` intact and skip `hello.r`. **Tests:** Task 5 `IconIndexTest.reflectionKeyIsNotAName`; Task 6 host check H3 runs e3240cb's `IconIndex` on a 0.9.1 index.
3. **A toggled colour not redrawing live.** Only the flag changes (same id, same file): `sameIcons` must see it and both controllers must get the indices and `setCompositesDirty(true)`. **Tests:** Task 2 `SdkStatusPopupTest.colourToggleReappliesIndicesAndMarksBothIconsDirty`; Task 1 `UiImplTest.statusSetWithOtherColourIsASnapshotChange`.
4. **A flag not re-applied after a popup rebuild.** New controllers start with `null` indices; an own-colour cell must be set again. **Test:** Task 2 `HmiWidgetsTest.rebuiltPopupReappliesOwnColours`.
5. **The selected drawer row.** The flag must not touch drawer bitmaps or ids, and the Javadoc must say the selected row is multiplied by the app colour. **Tests:** Task 2 `DrawerSlotsTest.ownColoursDoesNotChangeBitmaps`, `StatusLayoutTest.colourDoesNotChangeTheId`; bench B4.

---

### Task 1: API level 4 colour choice, carried by `UiIcon`; hello-menu 1.2.0 demonstrates it — SDK repo

**Files:**
- Modify: `api/src/main/java/mibsdk/api/Icon.java`, `Ui.java`, `SdkInfo.java`; `platform/src/main/java/mibsdk/platform/UiIcon.java`; `runtime/src/main/java/mibsdk/runtime/UiImpl.java` (and the manifest api check, if it has its own bound); `adapter-fake/src/main/java/mibsdk/adapter/fake/FakeUi.java`; `tools/make_builtin_icons.py` (`plugin_sample` only); `plugins/hello-menu/src/main/resources/plugin.json`, `src/main/icons/hello.png`, `hello-20.png`, `HelloMenuPlugin.java`
- Test: `runtime/src/test/java/mibsdk/runtime/UiImplTest.java`, `adapter-fake/src/test/java/mibsdk/adapter/fake/FakeUiTest.java`, `plugins/hello-menu/src/test/java/sample/hellomenu/HelloMenuPluginTest.java`, `installer/test_make_builtin_icons.py`

**Interfaces:**
- Produces:
  - `mibsdk.api.Icon`: `int TINTED = 0; int OWN_COLOURS = 1;` (constants only).
  - `Ui.icon(String name, int colour)`; `icon(name) == icon(name, Icon.TINTED)`. Any other `colour` → `IllegalArgumentException("colour must be Icon.TINTED or Icon.OWN_COLOURS")`. Javadoc, verbatim from the spec: "Status items honour it. Car rows ignore it: the drawer draws every row icon in its own colours and multiplies the selected row by the app colour, so ship a white glyph for the stock look."
  - `stockIcon(name)` is unchanged and tinted only (its Javadoc says why: the built-ins are white glyphs).
  - `UiIcon.ownColours()`, `UiIcon withOwnColours(boolean)`: in `equals`/`hashCode`/`toString`; `key()` and `owner()` unchanged.
  - `UiImpl` caches one handle per (name, colour). A declared-but-not-installed icon is inert in both colours.
  - `FakeUi` tag: `[png:<key>+own] ` for an own-colour file icon; other tags unchanged.

- [ ] **Step 1: Write the failing tests:**
  - `UiImplTest.iconWithoutColourIsTinted`: `icon("hello")` equals `icon("hello", Icon.TINTED)` and its `UiIcon.ownColours()` is false;
  - `UiImplTest.ownColoursKeepsTheKey`: `icon("hello", OWN_COLOURS)` has `ownColours()` true and the same `key()` as the tinted one, but is not `equals`;
  - `UiImplTest.unknownColourIsRefused`: colour 2 throws `IllegalArgumentException`;
  - `UiImplTest.statusSetWithOtherColourIsASnapshotChange`: `set("Hello", icon("hello-20", OWN_COLOURS))` after the tinted one publishes a new snapshot;
  - `UiImplTest.api4ManifestLoads`: api 4 loads, api 5 is refused;
  - `FakeUiTest.ownColourIconTag`: `[png:<key>+own] Hello`;
  - `HelloMenuPluginTest.statusIconIsOwnColoursAndToggles`: the status part starts `[png:<key>+own]`; "Status icon colour" makes it `[png:<key>]`, and again `+own`; the choice is kept in storage key `colour` across disable/enable;
  - `HelloMenuPluginTest.manifestIsApi4Version120`;
  - `test_make_builtin_icons.PluginSampleTest.test_left_white_right_blue`: in `hello` and `hello-20`, opaque pixels with `x < w/2` are (255, 255, 255) and those with `x ≥ w/2` are (0, 0, 255).
- [ ] **Step 2: Run them and verify they fail:** `./gradlew :runtime:test :adapter-fake:test :plugins:hello-menu:test && python3 -m unittest installer.test_make_builtin_icons`. Expected: FAIL (no `Icon.TINTED`, no overload).
- [ ] **Step 3: Implement.**
  - `SdkInfo.API_VERSION = 4`.
  - `plugin_sample()`: the same ring-and-dot shape, coloured left half white, right half (0, 0, 255). Regenerate both PNGs with `--plugin-sample`.
  - hello-menu: `plugin.json` gets `api: 4` and `version: "1.2.0"`. The status item uses `ui.icon("hello-20", colour)`, default `OWN_COLOURS`. A page item `status-colour`, "Status icon colour", flips it. The STAR/DOT stages of "Next status icon" stay stock (tinted). The row keeps `ui.icon("hello")`.
- [ ] **Step 4: Run and verify:** `./gradlew check && python3 -m unittest discover -s installer -p 'test_*.py'`. Expected: PASS.
- [ ] **Step 5: Commit:** `git add api platform runtime adapter-fake tools installer plugins/hello-menu && git commit -m "feat(api): per-icon colour choice, Icon.TINTED / OWN_COLOURS (api 4); hello-menu 1.2.0 shows it"`

---

### Task 2: The status popup honours the flag; the drawer ignores it — SDK repo

**Files:**
- Modify: `adapter-hqc/src/main/java/mibsdk/adapter/hqc/ui/StatusLayout.java`, `SdkStatusPopup.java`, `HqcUiIds.java`, `HmiWidgets.java`
- Test: `StatusLayoutTest.java`, `HmiWidgetsTest.java`, `DrawerSlotsTest.java`, new `SdkStatusPopupTest.java` (all in `adapter-hqc/src/test/java/mibsdk/adapter/hqc/ui/`)

**Interfaces:**
- Consumes: `UiIcon.ownColours()` (Task 1).
- Produces:
  - `HqcUiIds.STATUS_ICON_OWN_COLOURS = {1, 0, 4, 0}` (popup vis state 1 → plate entry 0, white).
  - `StatusLayout.Cell.ownColours` (final boolean), set from `icon.ownColours()` at the cell build (`StatusLayout.java:89`). `sameIcons` also compares it.
  - `SdkStatusPopup.icons(want, last)`: when cell `i`'s `ownColours` differs from `last`, call `setColorIndices(own ? HqcUiIds.STATUS_ICON_OWN_COLOURS : null)` and then `setCompositesDirty(true)` on **both** `idIcons[i]` and `fileIcons[i]`. The existing bitmap logic is unchanged.
  - After a popup rebuild (`SdkStatusPopup.current` is a new instance), `HmiWidgets` applies icons against `StatusLayout.EMPTY`. Use the rebuild detection that already re-places bounds, or add it if there is none.
  - A package-private `SdkStatusPopup(IconController[] idIcons, IconController[] fileIcons)` test constructor, so the tests can pass recording `IconController` subclasses.

- [ ] **Step 1: Write the failing tests:**
  - `StatusLayoutTest.cellCarriesOwnColoursAndSameIconsSeesIt`: two layouts that differ only in the flag are `sameGeometry`, not `sameIcons`;
  - `StatusLayoutTest.colourDoesNotChangeTheId`: the tinted and own-colour versions of one file get the same `IconIds` id;
  - `SdkStatusPopupTest.colourToggleReappliesIndicesAndMarksBothIconsDirty`: tinted → own sets `{1,0,4,0}` and dirty on both controllers of that cell only; own → tinted sets `null` and dirty;
  - `SdkStatusPopupTest.unchangedColourTouchesNothing`: no `setColorIndices` call;
  - `HmiWidgetsTest.rebuiltPopupReappliesOwnColours`: with an own cell applied, a new popup instance gets `{1,0,4,0}` on its next pass;
  - `DrawerSlotsTest.ownColoursDoesNotChangeBitmaps`: `bitmaps(f.withOwnColours(true))` equals `bitmaps(f)`.
- [ ] **Step 2: Run and verify they fail:** `./gradlew :adapter-hqc:test`. Expected: FAIL.
- [ ] **Step 3: Implement** as specified. `DrawerSlots` gets only a one-line comment saying the flag is ignored on purpose (spec §2.2).
- [ ] **Step 4: Run and verify:** `./gradlew check` (it includes `NoStockConstantsTest` and LinkCheck). Expected: PASS.
- [ ] **Step 5: Commit:** `git add adapter-hqc && git commit -m "feat(hqc): status popup draws own-colour icons (setColorIndices per cell, live)"`

---

### Task 3: The build generates faded reflections — SDK repo

**Files:**
- Create: `build-logic/src/main/java/mibsdk/build/PngImage.java`, `PngEncoder.java`, `Reflection.java`; `build-logic/src/test/java/mibsdk/build/ReflectionTest.java`; `build-logic/src/test/resources/png/reflect-*.png` (vectors written by the tool)
- Modify: `build-logic/src/main/java/mibsdk/build/MpkStage.java`, `MpkStageTest.java`; `dist/build.gradle.kts`; `tools/make_builtin_icons.py`; `installer/test_make_builtin_icons.py`

**Interfaces:**
- Produces:
  - `PngImage.decode(byte[] png) -> PngImage {int width, height; byte[] rgba}`: for a file that `PngCheck` accepted; filters 0–4; LA is expanded to RGBA `(g, g, g, a)`.
  - `PngEncoder.encode(int w, int h, byte[] rgba) -> byte[]`: a port of the tool's `png()`, `idat()` and `deflate()` with distances `(1, 4, 1 + 4w)`, filter 0 on every row, colour type 6. The output is byte-identical to the tool's.
  - `Reflection.of(PngImage src) -> PngImage`: the recipe in Global Constraints.
  - `MpkStage.stageIcons(List<String> names, File from, File stage, Predicate<String> reflect)`: for every name with `h > 20` that `reflect` accepts, it also writes `icons/<name>.r.png` and checks it with `PngCheck.check(bytes, 72)`. A failure fails the stage with `icon <name>.r: <rule>`. Plugin packages pass `n -> true`. `dist` passes `n -> !n.endsWith("-72r")`, because those files are already reflections. The `.r` names never go into `icons = [...]`.
  - `MpkStage.sdkManifest(...)` stages `api = 4`.
  - The tool gets `--reflection-vectors DIR`, which writes `reflect-rgba-48x40.png` (coloured), `reflect-la-72.png` and the `.r.png` of each into DIR.

- [ ] **Step 1: Write the failing tests:**
  - `ReflectionTest.matchesTheBuiltinReflectionsByteForByte`: for each of the 8 glyphs, `encode(of(decode(dist/icons/<g>-72.png)))` equals the bytes of `dist/icons/<g>-72r.png`;
  - `ReflectionTest.matchesThePythonVectors`: each `reflect-*.png` → its `reflect-*.r.png`, byte for byte;
  - `ReflectionTest.keepsRgbAndFadesAlpha`: an opaque (0, 0, 255) source gives row 0 alpha 102, the last row alpha 0, and RGB (0, 0, 255) kept;
  - `ReflectionTest.heightScales`: 72 → 68, 40 → 38, 21 → 20;
  - `MpkStageTest.pluginStageWritesReflectionsForDrawerSizedIconsOnly`: `hello` → `icons/hello.r.png` at 72×68; `hello-20` gets none; `[plugin] icons` is unchanged;
  - `MpkStageTest.sdkStageSkipsExistingReflectionsAndIsApi4`: `star-72.r.png` is staged, `star-72r.r.png` is not, and the manifest has `api = 4`;
  - `test_make_builtin_icons.ReflectionVectorsTest`: re-running `--reflection-vectors` reproduces the committed vectors.
- [ ] **Step 2: Run and verify they fail:** `./gradlew :build-logic:test`. Expected: FAIL (no `Reflection`).
- [ ] **Step 3: Implement** as specified. Generate the vectors with `python3 tools/make_builtin_icons.py --reflection-vectors build-logic/src/test/resources/png` and commit them.
- [ ] **Step 4: Run and verify:** `./gradlew check && python3 -m unittest discover -s installer -p 'test_*.py'`; `./gradlew :plugins:hello-menu:mibPackage :dist:mibpkgPackage -Pmibsdk.mibpkg=<mibpkg 0.9.0>` builds both, and both packages contain `icons/*.r.png`. Expected: PASS.
- [ ] **Step 5: Commit:** `git add build-logic dist tools installer && git commit -m "build: generate faded drawer reflections (<name>.r.png) for plugin and SDK icons"`

---

### Task 4: mibpkg 0.9.1 installs reflections and writes `.r` index lines — mibpkg repo

**Files:**
- Modify: `src/mibsdk/icons.rs`, `Cargo.toml`, `Cargo.lock` (version 0.9.1), `README.md`
- Create: `tests/fixtures/icons/ok-72r.png` (a byte copy of the SDK's `dist/icons/star-72r.png`, 72×68)
- Test: `tests/mibsdk_icons.rs`, `tests/sdk_pkg.rs`

**Interfaces:**
- Produces:
  - `icons::reflection_file_of(name: &str) -> String` = `icons/<name>.r.png`.
  - `check_from(src, names)`: for each declared name it also reads the **optional** `reflection_file_of(name)`. If the file is absent, it is skipped. If present, it is `png_check(&bytes, MAX_PX)`'d and pushed as one more `Checked { name: "<name>.r", .. }`. An error reads `icon <name>.r: <rule>`, and the caller adds `; nothing changed`. A `.r` file for an undeclared name is never read. `install`, `index_text` (sorted by key: `hello`, `hello-20`, `hello.r`) and the removal of unlisted files work unchanged on the longer list.
  - No count check covers the `.r` entries (manifest limits count declared names only).

- [ ] **Step 1: Write the failing tests:**
  - `plugin_install_writes_reflection_file_and_index_line`: the index is `hello=<s>,72,72\nhello-20=<s2>,20,20\nhello.r=<rs>,72,68\n`, and `<rs>.png` equals the source bytes;
  - `plugin_install_without_reflection_writes_no_r_line`: the index has only the 0.9.0 lines;
  - `plugin_install_with_a_bad_reflection_writes_nothing`: a `.r` that is `bad-crc.png` refuses with `icon hello.r:` and leaves no jar, grants line or icon dir;
  - `reflection_of_an_undeclared_name_is_ignored`;
  - `update_dropping_the_reflection_removes_its_file`;
  - `sdk_install_writes_reflection_lines`.
- [ ] **Step 2: Run and verify they fail:** `cargo test --test mibsdk_icons --test sdk_pkg`. Expected: FAIL.
- [ ] **Step 3: Implement**, bump the version to 0.9.1, and add one README paragraph on the `.r` line and on why old runtimes ignore it.
- [ ] **Step 4: Run and verify:** `cargo test`. Expected: PASS.
- [ ] **Step 5: Commit:** `git add src tests Cargo.toml Cargo.lock README.md && git commit -m "feat(sdk): install optional icon reflections (<name>.r index line); 0.9.1"`

---

### Task 5: The runtime reads `.r` and drawer rows use `{id, rid}` — SDK repo (after Task 1)

**Files:**
- Modify: `platform/src/main/java/mibsdk/platform/UiIcon.java`, `IconIndex.java`; `adapter-hqc/src/main/java/mibsdk/adapter/hqc/ui/DrawerSlots.java`
- Test: `platform/src/test/java/mibsdk/platform/IconIndexTest.java`, `adapter-hqc/src/test/java/mibsdk/adapter/hqc/ui/DrawerSlotsTest.java`

**Interfaces:**
- Consumes: the index line `<name>.r=<rsha16>,<w>,<h>` (Task 4); `UiIcon.withOwnColours` (Task 1).
- Produces:
  - `UiIcon.reflection() -> UiIcon` (a FILE icon with the same owner, or null) and `UiIcon withReflection(UiIcon r)`. Both are in `equals`. `withOwnColours` keeps the reflection.
  - `IconIndex.read`: the name lines are parsed exactly as today. Then each key `<base>.r` with `isName(base)`, a present `<base>` entry, a value valid under `entry`'s rules and an existing `<rsha16>.png` replaces `<base>` with `base.withReflection(...)`. Anything else is ignored. `isName` is unchanged, and `get("<base>.r")` is null.
  - `DrawerSlots.bitmaps` for FILE: `f = id(icon)`, `r = icon.reflection() == null ? -1 : id(icon.reflection())`. It returns `{f, r}` when `r ≥ 0`, else `{f, f}`. The reflection's id comes from `IconIds` under `owner/<rsha16>` and is served by `getImagePath` like any file id. The Javadoc of `bitmaps` is updated.

- [ ] **Step 1: Write the failing tests** (each old/new mix):
  - `IconIndexTest.readsReflectionIntoItsBase` (new runtime, new index);
  - `IconIndexTest.indexWithoutReflectionsReadsAsBefore` (new runtime, old index);
  - `IconIndexTest.reflectionKeyIsNotAName` (old runtime, new index: the api-3 rule is `isName`, which rejects `hello.r`): `isName("hello.r")` is false, `get("hello.r")` is null, and `hello` is identical to its entry in an index without the line;
  - `IconIndexTest.reflectionLineWithoutItsFileOrBaseIsIgnored`;
  - `DrawerSlotsTest.fileIconWithReflectionUsesItsOwnId`: two distinct ids, and the reflection's path is `../mibsdk/icons/<id>/<rsha16>.png`;
  - `DrawerSlotsTest.fileIconWithoutReflectionFallsBackToIdId`;
  - `DrawerSlotsTest.spentRangeForTheReflectionFallsBackToIdId`.

  The two mixes with an old mibpkg binary (0.9.0 + new package; new package + old SDK) cannot be unit tests. They are Task 6's host checks H2 and H3.
- [ ] **Step 2: Run and verify they fail:** `./gradlew :platform:test :adapter-hqc:test`. Expected: FAIL.
- [ ] **Step 3: Implement** as specified.
- [ ] **Step 4: Run and verify:** `./gradlew check && python3 -m unittest discover -s installer -p 'test_*.py'`. Expected: PASS.
- [ ] **Step 5: Commit:** `git add platform adapter-hqc && git commit -m "feat(ui): drawer rows draw the package's faded reflection ({id, rid}), else {id, id}"`

---

### Task 6: Bench check B1–B5 — BENCH (restart budget: 1 planned, 2 at most; the 2nd only to recover)

**Files:**
- Create: `docs/bench-e2e-icon-colour.md`, `docs/bench-e2e/icon-colour-*.jpg`, `docs/bench-e2e/icon-colour-log-r1.txt`

**Host first (no unit):** build mibpkg 0.9.1 (Task 4), plus mibpkg 0.9.0 from main 4385df1. Build `mibsdk` (api 4) and hello-menu 1.2.0 with a `+bench.N` version.
- [ ] H1: `mibpkg-0.9.1 --root <fake1> install-file` of both packages. `<fake1>/…/icons/hello-menu/index.properties` has `hello.r=<rs>,72,68` and `<rs>.png` exists. `icons-sdk/index.properties` has the `-72.r` lines.
- [ ] H2 (old mibpkg + new package): `mibpkg-0.9.0 --root <fake2>` installs both, and the index has no `.r` line.
- [ ] H3 = spec B5 (old runtime + new index): with `jshell --class-path <platform.jar built at e3240cb>`, `IconIndex.read("hello-menu", <fake1 dir>)`: `get("hello")` is the 72×72 entry and `get("hello.r")` is null. The same call with this branch's jar on `<fake2>` gives `hello` with no reflection.

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
- [my call] mibpkg is **0.9.1**, not 0.10.0. The change is additive in both directions: an old mibpkg ignores the extra package file (H2), an old runtime ignores the `.r` key (`isName`), and no existing line or package changes meaning.
- [my call] The Java port matches the Python tool byte for byte, which proves the shared recipe. The built-in `-72r` files are the test vectors, so the tool keeps generating them and `BuiltinIcons` keeps using `-72r`. The SDK set skips `.r` for names ending in `-72r`.
- [my call] In mibpkg a reflection is just one more `Checked` entry named `<name>.r`, so install, index writing and cleanup need no new code path. A bad `.r` refuses the whole install, the same as a bad icon.
- [my call] The mixes with old binaries (old mibpkg + new package, old runtime + new index) are host checks H2/H3 in the bench task, because unit tests cannot run old binaries. Task 5 pins the reader invariant (`reflectionKeyIsNotAName`) that makes H3 hold.
- [my call] B1 uses one status item and the toggle, because the plugin limit is one status item (Global Constraints). This differs from the spec's "two status items"; the four frames show the same comparison.
- [my call] hello-menu's sample art becomes the ring-and-dot shape coloured left white, right (0, 0, 255), so the shipped plugin is also the bench's tint-proof test PNG. Its status icon defaults to OWN_COLOURS, with a "Status icon colour" page toggle stored in `colour`. Its version is 1.2.0.
- [my call] With a restart budget of 1, the B2 fallback and the reflection polish are recorded as follow-ups, not run.

**Open points for the owner:** none.
