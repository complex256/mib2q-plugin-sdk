# Car readiness

The foundation is verified on the bench (`docs/bench-e2e.md`). Nothing from it goes into a car until every gate
below is closed. Plan 4 (car session, carplay-rgi migration) starts with these.

## Gates (must close before any car install)

| Gate | What has to be true | Status |
|------|---------------------|--------|
| I4 | A ledger restore is confirmed by observing the ECU value, not by the write returning. Needs a FakePlatform ECU model that can drop writes, and a ledger design that keeps an entry until the restore is observed. | closed (host tests; d438c5f, 8cbc792, 0a4f85c) |
| T18 | Ignition-off vs. mute: an override applied while KL15 drops must still be recorded in the ledger and restored on the next boot. | closed (host tests; 5c8db6b, 3893fba; KL15 plumbing on the bench 2026-10-24) |
| M6 | bike-rack: debounce `active=false`, and handle a failed storage write without leaving the sensors muted. | closed (host tests; 894e45b, 22883d0; arm/disarm/re-arm window on the bench 2026-10-24) |
| M7 | Only jars checked by `:dist:assembleUnit` (v48 + LinkCheck + permission scan) can be installed; `mibsdk-dev` refuses anything else. | closed (host tests; d4e3f22, 6c108a9, f719800, 0803704, c36f7b4; refusal and stamped installs on the bench 2026-10-24) |
| T11 | A restore still pending when the runtime stops is marked in the ledger file, so the next boot finishes it. | closed (host tests; cf187ac) |
| fsync | `boot.state` and `ledger.properties` survive power loss: fsync the file and its directory on QNX6 after each rename. | closed: file fsync + post-rename fsync of the target (870b4f2, d02e0a8); directory fsync unsupported on J9/QNX: `java.io.FileNotFoundException: /mnt/persist/mibsdk at java.io.RandomAccessFile.<init>` (J9 cannot open a directory); power-cut not verified: relies on qnx6 committing a snapshot on fsync |
| F7 | bike-rack's override, ledger, restore and HookObserved paths run end to end on a unit with a real PDC ECU (the bench has none). | open (car) |

## Known follow-ups (not gates)

- `/mnt/app` writes go through mibpkg only: its remount holders (`/tmp/mibpkg.rw.<tag>.<pid>`) keep the partition
  read-write while any mibpkg process writes, and every write is a `put` (verified, then renamed into place).
  `mibsdk-dev` no longer remounts anything.
- `mibpkg sdk remove <id>` (`mibsdk-dev remove <id>` on the bench) uninstalls a single plugin, a package or a
  pushed jar. `mibsdk-dev remove <id>` leaves `plugins/<id>.reload` behind (harmless: the token only matters while
  a jar of that id exists; a later push writes a fresh one).
- M7 is also enforced on the unit by mibpkg: it verifies the check stamp of the SDK and of every plugin it installs
  or receives by push.
- The `boot.state` wrap count written at boot is only proven indirectly on the bench (`wrapping=true` with no
  `reason=`): the unit is off the network for the first 2–3 minutes after a reset.
- `mibsdk-dev restart` (`slay -s11 j9`) resets the whole bench unit after 2–3 minutes instead of respawning j9.
- When every plugin FAILED, the unhealthy-boot count is still reset (the HMI itself did not crash). Only the log
  wording says so.
- An I2 refusal should recommend `safe-mode on && restart`.
- If `pluginStartDelayMs > healthyAfterMs`, there is a gap in the boot counter.
- The wrap-loop latch has no on-car indicator by design: the SDK UI is off under the latch. Clear it with
  `mibsdk-dev safe-mode off`.
- M1: plugins that read `has()` only in `onEnable` still miss a late feature (no API event), e.g. when the parking
  service registers after the initial load. This can only cause a false negative.
- bike-rack: a power cut within 2 s of ignition off can keep the rack armed for the next boot (storage debounce).
- I4 on the car: whether the PDC ECU reports every write, including unchanged values, decides how often the
  `last report` rule is used (F7).

## Car-only UI checks (Plan 4)

The bench has no reverse camera and no PDC ECU (`docs/bench-e2e-ui.md`), so these wait for the car:

- Popup 65 (the SDK status line) never covers the reverse-camera image or the PDC display.
- `PDC R OFF` shows on the status line while bike-rack's override is held and while it is restoring.
