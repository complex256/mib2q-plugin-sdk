# Spike A (Plan 2, Task 1): Car drawer rows, labels, presses, popup-65 status line

A throwaway bench spike. It is not part of the distribution. The results are in
`docs/research/2026-10-20-hmi-ui-injection.md` §8.

It needs a unit that already runs the SDK, because it logs through `SdkGlobals.LOG` into `/tmp/mibsdk.log`.

```
./gradlew :spikes:hmi-ui:carJar           # v48, linkCheck -> build/libs/mibsdk-spike-ui.jar
spikes/hmi-ui/run.sh install              # jar to lsd/jars/, HMICar= -> mibsdk.spike.ui.SpikeCarActivator
installer/mibsdk-dev restart              # 4-5 min; `ssh rpi-bench keypanel up` if the unit gets no lease
spikes/hmi-ui/run.sh log | grep spike.ui
spikes/hmi-ui/run.sh uninstall && installer/mibsdk-dev restart
```

- `SpikeCarActivator` mirrors the stock Car activator, with `SpikeBank` (11 models at 602738..602748) and
  `SpikeFactory` (9 ACTION rows appended to drawer 600043, texts 609000..609008).
- `SpikeProbes` runs its sequence 120 s after the activator is constructed:
  - T+0: row 1 is shown on even rounds and hidden on odd rounds.
  - T+10: row 0 gets new text.
  - T+20..100: popup 65 shows strings of 10/15/20/25/30 chars.
  - T+120: one `RunnableEvent` that throws.
  - T+140: popup 65 is hidden.
- `ssh ... touch /tmp/mibsdk-spike-ui.rerun` runs the sequence again without a restart, so you can watch it with
  the drawer open.
- `run.sh` is self-contained: it keeps its own copy of the push conventions of the pre-mibpkg
  `installer/mibsdk-dev` (which now delegates every unit write to mibpkg) and sources nothing. The stock bundles file uses CRLF line endings, and `run.sh` keeps the CR.

## Spike B (Plan 2, Task 2): SDK pages

Same install/uninstall. Drawer row 0 shows `SpikePage` with `FwHMI.showScreen` (S2a), row 1 fires Car event 600023 and
`SpikeFactory.createScreen` returns the page for the next Car screen (S2b), row 2 only logs (re-press check). The
probe sequence no longer runs by itself; `SpikeProbes` polls command files `/tmp/mibsdk-spike-ui.<cmd>` every 100 ms:
`rerun`, `row1-show`, `row1-hide`, `back-reshow`, `back-event`, `back-stock`, `reset-sel-on`, `reset-sel-off`,
`nolabel-on`, and `hijack` (the file holds an event id).
