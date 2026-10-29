> **Internal engineering record.** This page is kept for history and may be out of date. See [the internal docs index](../README.md).

# Icon colour (tinted or own colours) and drawer-row reflections

Date: 2026-10-25. Scope: why package PNGs in status popup 609600 are tinted (bench B10) when Spike C S2 reported a
file icon "in its own colours"; which widget property controls the tint, for id icons and file icons, and per cell;
whether Car-drawer rows can show own-colour icons; how stock drawer rows get a faded reflection, and how the build
should make one for plugin PNGs (bench B5). Build `HMI_AU_MIB2_HQC_NAR_G22_C10_180803`. Host-only, read-only survey of
the decompiled HMI, the firmware tree, the bench frames and this repo. Nothing new has run on a unit. Everything marked
**[spike]** is settled by the bench check in §7.

Sources (all outside this repo unless a repo path is given):
- Decompiled stock HMI, `patching/lsd-src/lsd/` (real constants, cited for every number) and `patching/lsd_java/` (CFR).
- Firmware tree `firmware/mmxfs/app/eso/hmi/lsd/`: `images/<factory>/<id>.png` (the stock bitmaps) and
  `kzbs/HMISystemEvoHigh/SELECTION_DRAWER.kzb` (the drawer scene; its GLSL shaders and property bindings are plain
  text inside the file, read with `strings`).
- This repo: `docs/research/2026-10-24-main-menu-and-icons.md` (the "icons note"; §9 is Spike C Boot A),
  `docs/bench-e2e-menu-icons.md` (the "bench run"), `spikes/hmi-ui`, `adapter-hqc/.../ui/*`, commit `5340cd3`.
- mibpkg `src/mibsdk/icons.rs` (the package icon rules and `index.properties`).

## 1. Root cause: nothing changed; the spike's green icon was tinted too

**Both builds tint every popup icon, id or file, through the same call.** The spike result was misread: a green
PNG multiplied by the yellow Media colour stays green.

### 1.1 The tint is `IconRendererHigh.applyProperties` → `setModulateColor`, gated by `processStateChange == 2`

`lsd-src …/evo/high/widgets/IconRendererHigh.java:291-310`:

```java
this.node.setOpacity(this.controller.getRenderOpacity());
...
if (this.controller.getProcessStateChange() == 2) {
   int var3 = var1.getColor(this.getColorIndex());      // getColorIndex() = controller.getCurrentVisState()
   int var4 = EALManager.createColorCode(var3);         // ARGB -> RGBA (EALManager.java:1820)
   this.node.setModulateColor(var4);
}
```

- This runs **after** the content is resolved (`calculateDisplayData`, `:126-164`), and it does not look at what the
  content is. An `Integer` id (bitmap), an `HMIResourceLocator` URI and a `String` path all reach the same
  `applyProperties`. **Tint and source are independent.**
- `setModulateColor` is a multiply. The node starts at modulate `-1` (white, which means no change):
  `WrappedNode3DImage.resetTransformation` (`base/eal/WrappedNode3DImage.java:63-68`) calls
  `node.setModulateColor(-1L)`. `setModulateColor(int)` (`:79-86`) skips the native call when the colour equals the
  one it stored last.
- The colour is `RedrawContextHigh.getColorFromPalette(i) = activeColorPlate[colorIndices[i]]`
  (`evo/high/RedrawContextHigh.java:20-36`), where `i` is the icon's vis state:
  - `colorIndices` is the **nearest ancestor's** `setColorIndices`. `AbstractWidget.managePaint`
    (`base/AbstractWidget.java:515-530`) pushes a widget's own array, if it has one, for itself and its children,
    then restores the parent's. A widget's own array therefore overrides the popup's.
  - `activeColorPlate` is **the current screen's** plate for the app colour scheme (`AbstractScreenWidget.getColorPlate`,
    `base/AbstractScreenWidget.java:1408-1421`). `PartialPopupController.setColorPalettes` is an empty method
    (`evo/widgets/PartialPopupController.java:471-472`), so the palettes that `SdkStatusPopup.build` and `SpikePopup`
    pass are ignored.
  - The vis state comes from `AbstractWidget.getCurrentVisState` (`:182-194`): 1 for enabled-and-active, 3 when
    focused or highlighted, else 2.
- Stock plates have 9 entries: `{white, black, app colour, app dark, grey c4, c5, c6, c4, app dark}` (e.g. the decoded
  rows in `SdkStatusPopup.java:101-106`). **Entry 0 is `-1` (white) in all 7157 palette rows** of the decompiled
  bags (`grep setColorPalettes` over `lsd-src`, first element of every row). `-1` reads the same in either byte
  order.
- The popup's `setColorIndices({1, 2, 4, 0})` (`SdkStatusPopup.java:97`, `SpikePopup.java:65`) maps vis state 1 to
  entry 2, the app colour: red on Car, yellow on Media. That is the observed tint. The labels'
  `{1, 0, 4, 0}` (`SdkStatusPopup.java:76`) maps vis state 1 to entry 0, white. That is why the label text is white on
  every screen. Together these two observations show that popup icons are drawn in vis state 1.

### 1.2 Spike and product are the same on every tint input

| | Spike C (`SpikePopup.icon`, `SpikeProbes` `pop-file`) | Product (`SdkStatusPopup.icon`, `SdkCarModelBank.StatusModels.fileIcon`) |
|---|---|---|
| `setProcessStateChange` | 2 (`SpikePopup.java:110`) | 2 (`SdkStatusPopup.java:148`) |
| icon `setColorIndices` | none (inherits the popup's `{1,2,4,0}`) | none (inherits `{1,2,4,0}`) |
| locator | `setResourceLocator(new HMIResourceLocator(path))` → copy ctor, `status = 0`, id -1 (`HMIResourceLocator.java:21-23, 38-41`) | `setResourceLocator(-1, path, 0)` (`SdkCarModelBank.java:281`) → the same locator |

The product's only differences are things that do not affect colour: label alignment, slot 7, the model ids, and the
`setCompositesDirty` added in 5340cd3.

**Status 0 and 1 are not about colour.** For a locator with status 1, `calculateDisplayData` returns the controller's
bitmap 0 (`IconRendererHigh.java:137-138`) and never loads the URI. The product's file icons have no bitmaps, so the PNG
was never drawn (B2). Status 0 goes to `getHMIImage(locator…)` (`:148-150`). In both cases the drawn node is then
modulated the same way.

### 1.3 The spike frame agrees once the colours are worked out

`spikes/hmi-ui/icons/p20.png` is a single colour, RGB (60, 220, 90). S2's frame (`ui-spike-c-popup-file-icon.jpg`) was
taken on the MENU ring after Media, so the tint was yellow: `-19456` = (255, 180, 0) in the plate. The JPEG blurs this
thin ring at 512 px, so the pixel values are compared by chroma angle (the CbCr angle, which keeps hue through the
blur), not by RGB:

| | RGB | CbCr angle |
|---|---|---|
| p20 untinted | (60, 220, 90) | −119° |
| p20 × yellow (255, 180, 0) | (60, 155, 0) | −150° |
| observed, brightest pixel of the ring | (104, 151, 79) | −148° |
| observed, median | (85, 123, 64) | −148° |

The observed hue matches the tinted prediction (yellow-green), not the PNG's own (blue-green). On Car, the same icon
would have been (60, 0, 2), almost black, and then nobody would have read it as its own colours. Bench B10 (white
`hello-20.png` drawn red on Car and yellow on Media) is the same mechanism, seen with a PNG where it is obvious.
**Proven** from the code. The frame analysis is consistent with it.

### 1.4 What controls the tint, per cell

Each status cell has its own `IconController` (`SdkStatusPopup` fields `idIcons[i]` and `fileIcons[i]`), so every
control below is per cell:

| Control | Effect | Live toggle |
|---|---|---|
| **`icon.setColorIndices({1, 0, 4, 0})`** (own) / `setColorIndices(null)` (tinted, inherit the popup's `{1,2,4,0}`) | vis state 1 → plate entry 0 = white → modulate is the identity → own colours. Disabled (2) still maps to grey 4 and focus (3) to 0. Tinted is today's behaviour | yes: `applyProperties` re-applies the modulate on every dirty render. Needs `setCompositesDirty(true)` (as 5340cd3 does for `setBitmaps`) **[spike]** |
| `icon.setProcessStateChange(0)` | `applyProperties` skips `setModulateColor`, so the node keeps its last modulate (white on a new node) | **no**: a node that has been tinted stays tinted until it is rebuilt (`WrappedNode3DImage` stores the colour; only `resetTransformation` sets it back to white). Fine at build time, wrong for a toggle |
| `setFixedColorScheme(n)` (`AbstractWidgetController.java:393`; used in `AbstractRendererHigh.storeOwnRedrawContextFields`) | picks another scheme's plate (e.g. 0, the white scheme) | not needed |

**Recommended: `setColorIndices`.** It uses the stock colour machinery, toggles live, and gets its white from the
same plate entry the labels already draw with.

## 2. The matrix: {id, file} × {tinted, own colours}

### 2.1 Status popup 609600 (`IconController` + `IconRendererHigh`)

| | Tinted (stock look) | Own colours |
|---|---|---|
| **id** (`setBitmaps({id})`; `SdkCarScreenFactory.getImagePath` maps 609100..609399 to a file) | today: built-ins `<name>-20` (bench S1, R3) — **proven** | `setColorIndices({1,0,4,0})` on `idIcons[i]` — possible (code); **[spike]** on screen. The id can name any file, a package PNG included, but the built-ins are white, so they would show white |
| **file** (`ResourceLocatorModel`, status 0) | today: B10 (`hello-20` red on Car, yellow on Media) — **proven** | `setColorIndices({1,0,4,0})` on `fileIcons[i]` — possible (code); **[spike]** |

No cell is impossible. Source (id or file) and colour are independent.

### 2.2 Car drawer rows (`MenuItemController` bitmaps → `SelectionMenuRendererHigh`)

There is no per-row tint property. `SelectionMenuRendererHigh.applyPropertiesMain` (`:203`, item loop `:268-356`)
sets each item node's Kanzi properties as follows:
- `Texture` = `getIconData(0)` and `ReflexTexture` = `getIconData(1)`. An `Integer` id goes through `getGuideTexture`;
  anything else, a `String` path or a locator, goes through `getTexture`.
- `Color` = **`var17 && var18 ? ealColors[0] : colorWhite`** (`:326`, `:347`). Here `var17` means the row is the
  **selection cursor's** target (`controller.getSelectionCursor()`, `:228`), that is the entry of the screen now
  shown, and `var18` means the row is enabled. `ealColors[0]` = `createColorCode(rc.getColor(0))` (`:145-151`), the app
  colour.

The drawer's icon shader (`SELECTION_DRAWER.kzb`) is a plain multiply:

```glsl
gl_FragColor.rgb = (lookup.rgb * Color.rgb * fade) * passiveMultiplier;   // fade = 1 - ds_inactive*0.7
gl_FragColor.a   = lookup.a * BlendIntensity;
```

| | Unselected row (most of the time) | The selected row (its page is the current screen) |
|---|---|---|
| **id** (what the SDK uses: `{id, id}` / `{id, id-r}`) | `Color` = white → **own colours**. A white PNG looks "tinted" only because it is white | `Color` = app colour → **tinted** (multiply) |
| **file** (`String` path in icon data; the SDK does not use it) | the same | the same |

- **Own colours on the selected row: not possible** without changing the stock renderer. It is shared and static
  (`mainItemNodes`), and the SDK does not own it.
- **App-colour tint on an unselected row: not possible**, and stock never does it either: stock white glyphs are white
  until selected.

The look follows from the art:
- a white glyph matches the stock rows;
- a coloured PNG is drawn in its own colours (bench B3).

**On this HMI a plugin drawer row is never the drawer's selected row** (observed on the bench, finding C2,
`docs/bench-e2e-icon-colour.md`). The selected row is the current screen's row, and a plugin page carries no drawer:
on it `menu_left` does nothing, and `back` returns to Audi drive select, so the drawer always opens with a stock row
selected. The knob focus does not tint. So the selected-row multiply in the table above never applies to plugin rows,
and a plugin row icon always shows in its own colours.

A per-icon tint flag therefore **has no effect on the drawer**, and the API should say so.

**Boot A I1 ("tinted").** The word comes from the §8.2 step-12 *expectation* of the icons note. The I1 result row
says "drawn like a stock icon". The spike plug was white and on an unselected row (`Color` = white), so I1 is no
evidence of a tint either way.

## 3. Drawer-row reflections

### 3.1 Stock reflections are separate pre-faded, pre-flipped images; the renderer adds no per-row fade

- The row's bitmaps are `{icon, reflection, small}`, e.g. Car `{600023, 600060, 600140}` and System `{422, 423}` (icons
  note §3.1). `getIconData(1)` goes into the item's `ReflexTexture` unchanged (`SelectionMenuRendererHigh.java:341-348`).
- The kzb binds each row's reflection object as follows (the binding strings in `SELECTION_DRAWER.kzb`):
  - `Texture ← #ds_itemN.ReflexTexture`;
  - `Color ← #ds_…itemN_ds_icon.Color`, so the reflection takes the same tint as its icon;
  - `BlendIntensity ← #ds_animControls.reflectionStrength`;
  - `passiveMultiplier ← #ds_animControls.passiveMultiplier`.

  The reflections are then composited onto the floor plate by the plate shader
  `rgb = color.r + color.g * reflexIcons.rgb` (screen-space `reflexCoord`).
- `reflectionStrength` is the only opacity knob. It is **one value on `#ds_animControls`** for the whole drawer:
  `propertyCache.setProperty(animNode, "reflectionStrength", this.reflexStrength)` (`:181`), default `1.0F` (`:94`).
  `setReflexStrength(float)` exists (`:883`), but nothing in the HMI calls it. Lowering it would fade every row's
  reflection, stock rows included. **There is no per-row alpha, effect or property for the reflection.** B5 shows the
  outcome: `{id, id}` draws a full-opacity, upright copy.
- So **the build must ship a pre-faded variant.** The renderer does not flip the reflection either. Stock art is
  pre-flipped, and the SDK's pre-flipped built-in `-72r` looks like a stock reflection on the bench (I2).

### 3.2 What the stock reflection images look like

There are 125 stock 72×68 images under `images/*/`, all `LA` (8-bit grey + alpha). Two pairs were measured, 600023/600060
and 422/423:

- **Size:** the icon is 72×72 and its reflection 72×68.
- **Flip:** the reflection is the icon flipped vertically. The top of 600060 is the bottom of the shock absorber and
  arrow in 600023. The grey levels (detail shading) are kept. There may be a slight vertical squash: the best
  mask fit for 600023 is about 0.8, which is weak evidence.
- **A transparent band at the top:** content starts at row **18–22** (31 of the 125 start at row 20). For 600060 that
  is row 21, while the icon's lowest content row is 66 (5 px margin). For 423 it is row 22, with the card ending at row
  70.
- **A fixed linear alpha ramp:** the peak alpha is at the first content row, 86–103 of 255 (**≈0.35–0.40**), and it
  falls by about 2.6/255 per row to **0 at row 56–58** in every image. Row-max alpha of 600060 from row 22: 59, 89, 86,
  83, 79, 76, 73, 69 … 9, 7, 5, 4, 2, 0 at row 57. The ramp is anchored to the image rows, not to the glyph. Rows
  57–67 are always empty.
- 17 of the 125 peak at 47–48 instead, about half strength. They are probably dimmer variants. Not needed here.

### 3.3 How the SDK's built-in `-72r` are made, and how close they are

`tools/make_builtin_icons.py:161` `reflection(px, h)`: the 72×72 white glyph flipped (`px[::-1]`), cut to its first
68 rows, and alpha × `0.4 · (1 − i/67)` (40 % → 0 over all 68 rows). It writes RGBA, not LA, and is deterministic.
Measured: star content from rows 8–64 with a peak of 87 at row 10; plug from rows 5–64 with a peak of 93 at row 6.

Compared with stock:
- the starting opacity is the same (≈0.35–0.40);
- the fade is longer (68 rows against about 37);
- there is no top band, so the reflection sits closer to the icon.

The bench accepted it as stock-like (I2: "star and reflection look like a stock row").

**Recommendation:** reuse this exact transform for plugin PNGs (it is proven on the unit). Matching stock more
closely, with a 20-row band and a ramp ending at row 57, is optional polish. Compare it side by side at the bench check
(§7, B3).

## 4. Tinting on drawer rows, and own-colour drawer icons

Covered in §2.2:
- an unselected row is drawn with `Color` = white, so a PNG keeps its own colours;
- the selected row is multiplied by the app colour;
- the SDK cannot change either.

Own-colour drawer icons are therefore possible, except while the row is the selected one **[spike]**. The reflection
takes the same `Color` (kzb binding), so a coloured icon's reflection is coloured too.

## 5. Recommended design

### 5.1 API: an additive runtime choice, not a manifest field

```java
// mibsdk.api.Icon (constants only; the interface stays empty of methods)
int TINTED = 0;       // drawn in the app colour like stock icons (white glyphs); the default
int OWN_COLOURS = 1;  // drawn in the PNG's own colours
// mibsdk.api.Ui
Icon icon(String name, int colour);   // icon(name) == icon(name, Icon.TINTED)
```

- **Runtime, not manifest.** The tint is a widget property, not content, so the same PNG can be used both ways, and
  nothing in mibpkg, the package or `index.properties` changes. A manifest field would need a new `plugin.json` form,
  a mibpkg schema change and a new index form, for no gain.
- `stockIcon(name)` stays tinted-only. The built-ins are white glyphs, so in own colours they would only be white.
- The overload is new API: plugins that call it declare `"api": 4`, and `icon(name)` keeps its current behaviour, so
  api-3 plugins are unchanged.
- Javadoc: "Status items honour it. Car rows ignore it: the drawer draws every row icon in its own colours and
  multiplies the selected row by the app colour, so ship a white glyph for the stock look."

### 5.2 How the choice travels

1. **Runtime:** `UiImpl.icon(name, colour)` (next to `UiImpl.java:190`) caches a handle per (name, colour), so it
   wraps `UiIcon u.withOwnColours(colour == OWN_COLOURS)`.
2. **`mibsdk.platform.UiIcon`:** add `boolean ownColours` with `ownColours()` and `withOwnColours(boolean)`. Include
   it in `equals`/`hashCode` and `toString`, so a change shows up as a snapshot change. It stays **out** of the
   `IconIds` key (owner/sha16): the same file keeps the same id, and colour is not content.
3. **Snapshot:** `UiRow.icon()` and `UiStatusPart.icon()` already carry the `UiIcon`, so nothing new is needed.
4. **Adapter, status:**
   - `StatusLayout.Cell` gains `ownColours`, set at `StatusLayout.java:89` from `icon.ownColours()`;
   - `sameIcons` (`:131`) also compares it, so `HmiWidgets` calls `icons(want, last)`;
   - `SdkStatusPopup.icons` sets, on **both** `idIcons[i]` and `fileIcons[i]`,
     `setColorIndices(own ? HqcUiIds.STATUS_ICON_OWN_COLOURS : null)` with `STATUS_ICON_OWN_COLOURS = {1, 0, 4, 0}`,
     plus `setCompositesDirty(true)`;
   - the build of `SdkStatusPopup` applies the wanted layout as today.
5. **Adapter, drawer:** `DrawerSlots.bitmaps` ignores `ownColours` (§2.2).
6. **Fake adapter:** `FakeUi` records the flag, so the sample plugins' tests can assert it.

### 5.3 Build-generated faded reflection

- **Build (`MpkStage.stageIcons`):** for each declared icon taller than the status size (h > 20, i.e. one that can be
  a row icon), also stage `icons/<name>.r.png`. It is the same width, `round(h · 68 / 72)` rows (68 for a 72-px icon),
  the PNG flipped vertically, alpha × `0.4 · (1 − i/(H−1))`. The RGB is kept, so an own-colour icon gets a coloured
  reflection.
  - Port `reflection()` and the deterministic encoder of `tools/make_builtin_icons.py` to `build-logic` (Java). Decode
    with the inflater `PngCheck` already validates against. Encode with the same fixed-Huffman deflate, so the output is
    byte-identical everywhere.
  - Check the output with `PngCheck`.
  - The plugin author ships only `<name>.png`; the `.r` file is not declared in `plugin.json`.
- **mibpkg:**
  - for each declared name, also read the **optional** `icons/<name>.r.png`, `png_check` it, and install it as
    `<rsha16>.png`;
  - write an extra index line **`<name>.r=<rsha16>,<w>,<h>`**;
  - the `.r` files do not count toward `PLUGIN_MAX` (8).

  Why a separate line and not a 4th field on `<name>=…`:
  - today's `IconIndex.entry` drops any value with a third comma (`IconIndex.java:83`), so an old runtime would lose
    the icon itself;
  - an old runtime ignores a `<name>.r` key, because `IconIndex.isName` (`:100`) accepts only `[a-z0-9-]`. It then
    falls back to `{id, id}`, which is today's look.

  An old mibpkg reads only `icons/<name>.png` for declared names (`icons.rs` `check_from`), so it ignores the extra
  file. Every mix of old and new is safe.
- **Runtime:**
  - `IconIndex` reads `<name>.r` lines into the `UiIcon` of `<name>` (a reflection path and key);
  - `DrawerSlots.bitmaps` (`DrawerSlots.java:101-115`) returns `{id(f), id(f.reflection)}` when there is one, else
    `{id(f), id(f)}`;
  - the reflection gets its own id from `IconIds` (key `owner/<rsha16>`).
- Optional: let `make_builtin_icons.py` and the Java port share test vectors, so the built-in `-72r` and the plugin
  `.r` come from one transform.

## 6. Proven and [spike]

| Claim | Status |
|---|---|
| Popup icons are tinted by `setModulateColor(plate[colorIndices[visState]])` because `processStateChange == 2`; id and file icons alike | proven (code) |
| Spike S2's green icon was tinted (green × yellow ≈ green) | proven (code); frame hue consistent |
| Status 1 vs 0 is unrelated to colour (status 1 draws bitmap 0) | proven (code, B2) |
| `PartialPopupController.setColorPalettes` is a no-op; the plate is the screen's | proven (code) |
| Plate entry 0 is white in every stock palette row | proven (all 7157 rows) |
| `setColorIndices({1,0,4,0})` on a popup icon draws it in own colours | verified: bench B1 |
| Toggling own ↔ tinted on a built popup shows live after `setCompositesDirty(true)` | **no** (bench C1): it only marks the widgets dirty and nothing repaints them, because no model write follows. The fix (one `triggerRepaint` on the popup after a colour change) is verified on the bench (B2 re-run: the new colour is drawn 0.56–0.81 s after each press, both directions) |
| Drawer rows: `Color` is white except on the selected row (app colour); the shader multiplies | proven (code + kzb shader) |
| A coloured PNG on an unselected drawer row shows its own colours; on the selected row it is multiplied | own colours: bench B3. The selected row is never a plugin row on this HMI (observed on the bench, C2), so the multiply is not seen |
| No per-row reflection alpha; `reflectionStrength` is one value for the whole drawer | proven (code + kzb bindings) |
| Stock reflections: pre-flipped, top band ≈20 rows, linear ramp ≈0.38 → 0 ending at row ≈57 | proven (125 stock images, 2 pairs fitted) |
| A build-generated `.r` (the built-in transform) looks like the stock reflection on a plugin row | verified: bench B3 (the transform is proven for built-ins, I2) |

## 7. Bench check (one boot)

Build a bench hello-menu (api 4). Its test PNG must not survive a tint by accident: **left half white, right half pure
blue (0, 0, 255)**, as `hello` (72×72) and `hello-20` (20×20). Tinted on Car (255, 0, 25), the blue half turns almost
black and the white half red. In own colours they stay blue and white.

| # | Step | Expect |
|---|---|---|
| B1 | Two status items: `icon("hello-20", OWN_COLOURS)` and `icon("hello-20")`; frames on Car and on Media | own: white/blue on both; tinted: red/black on Car, yellow/black on Media. Built-in cross on daemon-status unchanged (tinted) |
| B2 | "Toggle colour" button on the Hello page flips item 1 between tinted and own while the popup is up | the cell changes within a second, no screen change needed (if not: fall back to `processStateChange` at build and rebuild the popup on a change) |
| B3 | Car → drawer, Hello row with `hello` (own colours irrelevant) and its build `.r`; zoomed crop beside a stock row | icon white/blue; reflection faded (no "8"), opacity close to the stock rows' |
| B4 | Open the Hello page from the drawer, then open the drawer again (the Hello row is now selected) | the Hello icon and its reflection are multiplied by red: blue half almost black. **Not reachable on this HMI** (C2): a plugin row is never the selected row |
| B5 | Optional: `mibpkg` host dry run with an old SDK runtime index reader (host test, not on the unit) | `<name>.r` ignored, row falls back to `{id, id}` |
