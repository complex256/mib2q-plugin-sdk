# Car readiness

The foundation is verified on the bench (`docs/bench-e2e.md`). Nothing from it goes into a car until every gate
below is closed. Plan 4 (car session, carplay-rgi migration) starts with these.

## Gates (must close before any car install)

| Gate | What has to be true |
|------|---------------------|
| I4 | A ledger restore is confirmed by observing the ECU value, not by the write returning. Needs a FakePlatform ECU model that can drop writes, and a ledger design that keeps an entry until the restore is observed. |
| T18 | Ignition-off vs. mute: an override applied while KL15 drops must still be recorded in the ledger and restored on the next boot. |
| M6 | bike-rack: debounce `active=false`, and handle a failed storage write without leaving the sensors muted. |
| M7 | Only jars checked by `:dist:assembleUnit` (v48 + LinkCheck + permission scan) can be installed; `mibsdk-dev` refuses anything else. |
| T11 | A restore still pending when the runtime stops is marked in the ledger file, so the next boot finishes it. |
| fsync | `boot.state` and `ledger.properties` survive power loss: fsync the file and its directory on QNX6 after each rename. |
| F7 | bike-rack's override, ledger, restore and HookObserved paths run end to end on a unit with a real PDC ECU (the bench has none). |

## Known follow-ups (not gates)

- `mibpkg sdk remove <id>` (`mibsdk-dev remove <id>` on the bench) uninstalls a single plugin, a package or a
  pushed jar.
- `/mnt/app` writes go through mibpkg only: its remount holders (`/tmp/mibpkg.rw.<tag>.<pid>`) keep the partition
  read-write while any mibpkg process writes, and every write is a `put` (verified, then renamed into place).
  `mibsdk-dev` no longer remounts anything.
- M7 is now enforced on the unit by mibpkg (it verifies the check stamp of the SDK and of every plugin it
  installs or receives by push); the gate stays open until that is proven on the bench.
- The `boot.state` wrap count written at boot is only proven indirectly on the bench (`wrapping=true` with no
  `reason=`): the unit is off the network for the first 2–3 minutes after a reset.
- `mibsdk-dev restart` (`slay -s11 j9`) resets the whole bench unit after 2–3 minutes instead of respawning j9.
- When every plugin FAILED, the unhealthy-boot count is still reset (the HMI itself did not crash). Only the log
  wording says so.
- An I2 refusal should recommend `safe-mode on && restart`.
- If `pluginStartDelayMs > healthyAfterMs`, there is a gap in the boot counter.
- The wrap-loop latch has no indicator the user can see (Plan 2, UI).
- M1: plugin features are not refreshed if the parking service registers after the initial load. This can only
  cause a false negative.
