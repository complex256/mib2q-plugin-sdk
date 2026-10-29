# Limits

The head unit is small and the SDK is shared. These are the hard limits; counts throw, text is
truncated.

## UI

| What | Limit | Constant | When exceeded |
|---|---|---|---|
| Pages per plugin | 8 | `Ui.MAX_PAGES` | `IllegalStateException` |
| Car rows per plugin | 2 | `Ui.MAX_CAR_ROWS` | `IllegalStateException` |
| Status items per plugin | 1 | `Ui.MAX_STATUS_ITEMS` | `IllegalStateException` |
| Entries per page | 24 | `PageSpec.MAX_ENTRIES` | `IllegalStateException` |
| Dropdown + slider rows per page | 4 | `PageSpec.MAX_INPUT_ROWS` | `IllegalStateException` |
| Options per choice/dropdown | 8 | `PageSpec.MAX_OPTIONS` | `IllegalArgumentException` |
| Slider steps | 24 | `PageSpec.MAX_SLIDER_STEPS` | `IllegalArgumentException` |
| Icons per plugin | 8 | — | build failure |

## Text

| Text | Limit | Constant | When exceeded |
|---|---|---|---|
| Page title | 32 | `PageSpec.MAX_TITLE` | truncated |
| Item label | 40 | `PageSpec.MAX_LABEL` | truncated |
| Item value | 20 | `PageSpec.MAX_VALUE` | truncated |
| Info line | 80 | `PageSpec.MAX_INFO` | truncated |
| Status item | 12 | `StatusItem.MAX_TEXT` | truncated |
| Toast | 20 | `Ui.MAX_TOAST` | truncated |
| Confirm | 120 | `Ui.MAX_CONFIRM_TEXT` | truncated |
| Speller input | 32 | `Ui.MAX_SPELLER` | argument error above |

## Icons

| Use | Maximum | Constant |
|---|---|---|
| Car row | 72×72 px | `Ui.ROW_ICON_MAX_PX` |
| Status item, toast | 20×20 px | `StatusItem.ICON_MAX_PX` |
| Page item | 24×24 px | `PageSpec.ITEM_ICON_MAX_PX` |
| File size | 32 KiB | — |
| Format | 8-bit RGBA or grey + alpha | — |

Icon size is checked at runtime for the use, not at build. See [Icons](../guides/icons.md).

## Ids

| Id | Rule |
|---|---|
| Page id | `[A-Za-z0-9._-]{1,32}` |
| Item id | `[A-Za-z0-9._-]{1,32}`, unique within its page |
| Plugin id | `[a-z][a-z0-9-]{1,39}` |
| Icon name | `[a-z0-9-]{1,32}` |

## Storage

| What | Default | Where set |
|---|---|---|
| Per-plugin storage | 256 KiB | `sdk.properties` |
| All plugins | 1 MiB | `sdk.properties` |
| A `put` past the cap | `IllegalStateException`, nothing stored | — |

## Networking

| What | Limit | Constant |
|---|---|---|
| Request | 2048 UTF-8 bytes | — |
| Response | 8192 bytes | `Net.MAX_RESPONSE_BYTES` |
| Timeout | 3000 ms | `Net.MAX_TIMEOUT_MS` |
| Host | `127.0.0.1` / `localhost`, or any host with `network.outbound` | — |

## Time and failure

| What | Default | What happens |
|---|---|---|
| Slow callback | 5 s | Logged; plugin keeps running |
| Hung callback | 30 s | Plugin quarantined |
| Callback failures | 3 in 60 s | Plugin quarantined |
| Start delay after boot | 30 s | Plugins load later |
| Healthy boot | 60 s without a crash | Counts toward safe mode |
| Unhealthy boots before safe mode | 3 | Next boot has no plugins |
| Toast duration | 3 s | `Ui.TOAST_MS` |

The timeouts and caps are defaults in `sdk.properties`; the unit owner can change them. See
[Safety model](../explanation/safety-model.md).

## Memory

The whole HMI runs with a 60 MB heap (`-Xmx60m`). When free heap stays low, the SDK quarantines the most
recently enabled plugin. Keep data small and do not cache large strings or images.

## Related

- [Pages and inputs](../guides/pages-and-inputs.md)
- [Status line](../guides/status-line.md)
- [plugin.json](plugin-manifest.md)
