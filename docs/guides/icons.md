# Icons

Plugins can ship PNG icons for Car rows, page items and status items. The HMI draws them; you never draw
graphics yourself.

## Declare icons

Add the names to `plugin.json`:

```json
{
  "api": 3,
  "icons": ["cup", "cup-20"]
}
```

Put the files at `src/main/icons/<name>.png`. Names match `[a-z0-9-]{1,32}`; a plugin may declare at
most 8.

The build fails if a declared file is missing or does not pass the PNG rules. `mibpkgPackage` also stages
a faded reflection `<name>.r.png` for every icon taller than 20 px; the Car drawer draws it under the
row icon. You do not create the reflection yourself.

## PNG rules

| Rule | Value |
|---|---|
| Format | 8-bit RGBA, or 8-bit grey + alpha. Not palette, not 16-bit |
| Interlacing | None |
| Animation | None |
| Maximum size | 72 px either way |
| Maximum file size | 32 KiB |

White glyphs on transparent backgrounds look like the stock icons. The Car drawer draws row icons in
their own colours, so a white glyph is the stock look.

## Sizes by use

| Use | API | Maximum | Notes |
|---|---|---|---|
| Car row | `ui.carMenuRow(label, page, icon)` | 72×72 (`Ui.ROW_ICON_MAX_PX`) | Own colours |
| Status item, toast | `ui.statusItem(text, icon)`, `ui.toast(text, icon)` | 20×20 (`StatusItem.ICON_MAX_PX`) | Tinted by default; `Icon.OWN_COLOURS` keeps the PNG colours |
| Page item | `PageSpec.item(id, label, value, icon)` | 24×24 (`PageSpec.ITEM_ICON_MAX_PX`) | The cell does not scale |
| Built-in | `ui.stockIcon(...)` | — | No file |

The size check is **per use, at runtime**. A 72 px icon passed to `statusItem` builds and packages, then
throws `IllegalArgumentException` when the plugin runs. Ship a small icon for the status line:

```text
src/main/icons/cup.png       72×72   Car row
src/main/icons/cup-20.png    20×20   status item and toast
```

`permissionScan` warns when a call names an oversized icon literally, but only for a literal
`ui.icon("name")`; a field or a local is not judged.

## Getting an icon

```java
Icon cup = ctx.ui().icon("cup");                    // tinted, the default
Icon own = ctx.ui().icon("logo", Icon.OWN_COLOURS); // keep the PNG colours
Icon star = ctx.ui().stockIcon(StockIcons.STAR);
```

- An undeclared name throws `IllegalArgumentException`.
- An icon from another plugin (or a hand-made implementation) is rejected.
- An icon declared but not installed — for example after a jar push without a package — is inert: rows
  and items show without it, and the runtime logs one WARN.
- Icons are static per installed package version. A new picture needs a new package.

### Tinted or own colours

`Icon.TINTED` (the default) draws the icon in the app colour, like the stock icons. `Icon.OWN_COLOURS`
keeps the PNG's colours.

- Status items and toasts honour the flag.
- Car rows ignore it: the drawer always draws row icons in their own colours.
- Built-in icons are always tinted; they are white glyphs.

## Built-in icons

`StockIcons` provides `DOT`, `CIRCLE`, `STAR`, `TRIANGLE`, `SQUARE`, `CHECK`, `CROSS` and `PLUG`. They
need no file and are always small enough for any use.

```java
ctx.ui().carMenuRow("Plugins", page, ctx.ui().stockIcon(StockIcons.PLUG));
```

## Generating icons

The tutorial's generator writes 8-bit RGBA PNGs with the standard library:

```sh
python3 plugins/coffee-timer/make_icons.py src/main/icons
```

Any image editor that exports 8-bit RGBA works. Keep the two right-most columns of a 20 px status icon
clear: the status text starts right after the icon cell.

## Related

- [Status line](status-line.md)
- [Pages and inputs](pages-and-inputs.md)
- [Reference: limits](../reference/limits.md)
