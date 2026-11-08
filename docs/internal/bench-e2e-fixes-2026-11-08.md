> **Internal engineering record.** This page is kept for history and may be out of date. See [the internal docs index](README.md).

# Bench end-to-end: the 2026-11 on-car fixes (mibpkg 0.11.4, mibsdk 0.7.0)

Run 2026-11-08 on the bench MHI2Q (10.20.0.23, `HMI_AU_MIB2_HQC_NAR_G22_C10_180803`), from a working
tree carrying all five fixes (uncommitted). The combined product package `tailscale-1.102.10.mpk` from
`mib2q-plugins` (`[package] name = "tailscale"`, `[plugin] id = "tailscale-status"`, the car's shape) was
installed so the permissions fix had something to test against: the bench otherwise keeps the product and
the plugin in two separate packages.

**Overall: pass for all five fixes. The page transitions (fix 2) were checked visually by the owner on this
unit and look right; the frame bursts here only show that pages arrive complete.**

## Builds and install

- QNX `mibpkg` 0.11.4 from the working tree (`scripts/build.sh`); host `mibpkg` `cargo build --release`
  packaged the stamp with `mibpkg build`.
- `mibsdk-0.7.0.mpk` (786766 bytes, sha256 `4802cee0…`) with `./gradlew :dist:mibpkgPackage`.
- `tailscale-1.102.10.mpk` from `mib2q-plugins`; `requires.toml` says `[mibsdk] min = "0.7.0"`, so it
  doubles as a check of that gate.
- `mibpkg 0.11.3 -> 0.11.4` (self-restart), `mibsdk 0.6.3+bench.15 -> 0.7.0` (reboot needed: yes), then
  `tailscale 1.102.5 -> 1.102.10` with `--approve=network,ui.slot.statusbar`.
- Before the restart: safe mode cleared (`mibsdk-dev safe-mode off`: `unhealthyBoots=0`, `wrapBoots=0`), and
  one restoring ledger entry seeded by hand (`parking.soundDirections=bike-rack|UNTIL_IGNITION_OFF|F1R1L1X1|F1R0L1X1|R`)
  because the bench has no PDC ECU and never holds that override on its own.

## Restart

`installer/mibsdk-dev restart` at 14:05:03; the keypanel answered `bridge_connected:true` at 14:07:41. The
log has `ui system=sdk`, `safeMode=false`, `ui car=sdk` at 20694, and every plugin loaded (4 enabled).

## Checks

| # | Check | Result |
|---|---|---|
| 1a | `GET /api/packages` shows the combined `tailscale` as `kind: plugin` with `network` and `ui.slot.statusbar` both `granted: true`, `blocked: []` | **pass** (before the fix both read granted false, because grants were looked up under the package name) |
| 1b | The web UI's own action (`POST /api/actions {action: grant, id: "tailscale", …}`) revoking one permission | **pass.** `OK: granted tailscale-status: ui.slot.statusbar`; `grants.properties` holds `tailscale-status=ui.slot.statusbar`; the page then shows `network` granted false and blocked |
| 1c | The same action revoking all and re-granting all | **pass.** `OK: granted tailscale-status: <none>` then `… network,ui.slot.statusbar`; `ENABLED` after the re-grant |
| 1d | `mibpkg sdk data-clear tailscale` (package name) and `… tailscale-status` (plugin id) | **pass.** Both clear `data/tailscale-status.properties` |
| 3 | MENU ring → Plugins: the first row is `SDK` | **pass.** `SDK >`, then Bike Rack, MQTT, Crashy, Hello Menu, Hello, Inputs Demo, Tailscale |
| 4a | Ring → Plugins → SDK → Internet, then BACK | **pass.** One level up, to the SDK page, with the focus back on Internet |
| 4b | … then MENU from Internet | **pass.** Straight to the ring (screen 46) in one press, not one level |
| 4c | MENU on a slider sub-screen (Plugins → Inputs Demo → Inputs → Volume) | **pass.** Straight to the ring |
| 4d | A page opened from the Car drawer (Bike rack), then MENU | **pass.** Stock's MENU takes it to the ring; the SDK does not consume it |
| 5a | The seeded `\|R` entry at boot | **pass.** `WARN ledger restored parking.soundDirections=F1R1L1X1 (the car did not report; the write returned) (override of bike-rack ended)` at 7123 ms (5 s after the write returned at 2108); ledger empty; no `PDC R OFF` |
| 5b | Arm Bike rack on the page | **pass.** Ledger `…|F1R0L1X1|A`, `Rear tones: muted`, `PDC R OFF` in the status band |
| 5c | Toggle Rack fitted off | **pass.** `restoring` at once, `WARN … (the car did not report; the write returned)` 5.001 s later, ledger 0 bytes, `PDC R OFF` gone within the same window; `data/bike-rack.properties` lost its `active` key |
| 2 | Plugins ↔ SDK transitions | **pass** (owner-verified on the unit: the forward and backward animations and the incoming rows look right). A ~10 fps frame burst over each change shows the outgoing screen and then the incoming page complete, with no frame of the incoming rows inside the outgoing screen |

The log for the whole run has no UI error and no exception: the only ERROR is the expected first restore
attempt before `DSICarParkingSystem` attaches (retried 5 s later), plus an unrelated `dhcp.client` warning.

## Findings

| # | Finding | Status |
|---|---|---|
| B1 | `sdk data-clear` as first written resolved the plugin id to the *owning package* and so looked for `data/tailscale.properties`. Fixed to resolve a package name to the plugin id and keep the id otherwise; the corrected 0.11.4 was deployed (rollback to 0.11.3, reinstall) and re-checked on the unit | **fixed in this run** |
| B2 | The bench now has two packages carrying the plugin id `tailscale-status` (the product `tailscale 1.102.10` and the plugin-only `tailscale-status 1.0.1`). `sdk status` lists one line, keyed by the plugin id, with the standalone package's version. Harmless here; the car has only the combined package | recorded (bench-only) |
| B3 | The status band did not redraw on a screen change until the next item update (`PDC R OFF` showed on Media only after the switch). Pre-existing, not part of these fixes | recorded |

## End state

- `mibpkg 0.11.4`, `mibsdk 0.7.0` running (`api: 1..7`, `reboot needed: no`), safe mode off, no marker,
  `unhealthyBoots=0`.
- `ledger: 0 override(s)`, `ledger.properties` 0 bytes; Bike rack disarmed (`active` removed).
- Grants: `tailscale-status=network,ui.slot.statusbar`; the combined package's installed jar has the
  package's sha256 (`012505f6…`).
- Plugins: bike-rack, carmqtt-status, inputs-demo and tailscale-status enabled; crashy, hello and
  hello-menu off in the Plugins menu (as before the run).
- `tailscale` is now 1.102.10 (was 1.102.5).
- The unit is on the Car screen, `Vehicle: Audi drive select`.

The bench ran the same code that shipped as 0.7.1 (0.7.0 was already released on 2026-10-30, so the
fixes are a patch release; the package version is the only difference).
