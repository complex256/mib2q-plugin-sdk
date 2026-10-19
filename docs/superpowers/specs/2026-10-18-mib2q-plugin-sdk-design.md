# MHI2Q Plugin SDK — Design

Date: 2026-10-18 · Status: draft for review

Background reading (same repo):
- [Java onboard map](../../research/2026-10-18-java-onboard-map.md): how the HMI's Java runs, what's in it, how we inject today (with appendices A/B)
- [Modern-Java spike](../../../spikes/modern-java/README.md): modern Java and Kotlin proven on the car's J9

Decisions are marked **[decided]** (made by the project owner in conversation) or **[my call]**
(made by the designer; open to change in review). Everything else is left to the builder.

---

## 1. Intent

The Audi MHI2Q's HMI is one Java program: ~35k classes on IBM J9 2.3, launched from `lsd.jxe`. We
already change its behaviour (carplay-rgi), but by shipping whole-class copies of decompiled stock
classes in a jar that shadows the originals. That approach boot-loops the unit when firmware differs,
lets mods silently overwrite each other's classes, and makes every mod a reverse-engineering project.

We want what Bukkit/Spigot/Fabric gave Minecraft: **a plugin platform**. Anyone writes a small plugin
in modern Java or Kotlin against a stable, documented API. It installs through **mibpkg**, shows up in
the car, and can't take the head unit down with it. **[decided]** The audience is the owner *and*
other people writing and sharing plugins.

Motivating plugins [decided]. These are not all v1 deliverables; see §3.
1. **Status info**: show state from native daemons (mib2q-carmqtt, mib2q-tailscale) in the HMI.
2. **New menu items and pages.**
3. **Hooks on existing behaviour.** Concrete case: a manually toggled "bike rack" plugin that silences
   the rear parking sensors while a hitch-mounted rack is fitted.
4. **An entire open-source nav stack.** A separate future project built *on* the SDK.
5. **IFTTT-style automation.** A future *plugin* (web UI, on-screen UI or a Home Assistant bridge),
   not part of the SDK. [decided]

## 2. Goals (v1)

- **G1 Modern languages.** Authors write modern Java (lambdas, `var`, switch expressions, records-free
  Java 17 syntax) or Kotlin. [decided] The SDK toolchain turns this into J9-loadable class files. Proven by
  the spike.
- **G2 Stable, firmware-neutral API.** Plugins compile only against SDK interfaces and never see an
  Audi/e.solutions/DSI class. [decided: abstraction layer]
- **G3 Firmware adapter layer.** All firmware-specific code lives behind an internal adapter interface.
  v1 ships **one adapter, for `HMI_AU_MIB2_HQC_NAR_G22_C10_180803`** (the owner's unit; variant
  `FMQ-P-TNSL-US-AU-MLE`). [decided] If no adapter matches the running HMI version, **the SDK stays off
  and no plugin loads.**
- **G4 Containment.** A faulty plugin can break itself but must never stop the HMI from booting, and must
  never block the stock HMI threads. (Details in §6.)
- **G5 Permissions.** Plugins declare capabilities; the user approves them at install time and can
  revoke them later. Enforced at the API. [decided: "permissions would be a nice touch"]
- **G6 UI.** One SDK-owned **Plugins menu** lists every plugin. Plugins can *also* place items in SDK-owned
  **slots** elsewhere in the HMI. [decided] Pages use **native-looking templates first**; a
  custom-drawn panel is the acceptable fallback. [decided]
- **G7 Hooks.** Plugins can observe, modify or veto at the **service (DSI) level** and the **HMI level**
  (keys, screen changes, menu actions). [my call] Plugin code never runs on a stock thread, so
  modify/veto is declarative (see §6). [my call]
- **G8 Distribution via mibpkg.** [decided]
- **G9 Fast dev loop.** Host-side unit tests against a fake car, plus push-and-hot-reload of a single
  plugin on a bench unit without restarting the HMI. [my call; per-plugin class loading proven by the spike]

## 3. Non-goals / anti-goals

**Non-goals (v1):**
- Firmware other than the one build in G3. Other Audi trains and VW/Porsche units come later as new
  adapters.
- The IFTTT/automation plugin and the nav stack. The API just has to be rich enough for them later
  (events catalogue, actions, network, full-screen surface).
- A real security sandbox. Permissions are a seat belt (see §7).
- Letting *plugins* patch arbitrary stock methods ("hook anywhere"). Only adapters may shadow stock
  classes. [my call]

**Anti-goals** (failures even if the SDK "works"):
- The SDK or any plugin causes a boot loop, or a boot that needs SD-card recovery.
- Anything blocks or does I/O on the HMI event thread or other stock threads. (One log call there once
  blanked both cluster displays.)
- Breaking DSITracer and so mib-keypanel (TCP 1796): the bench's way to drive and see the HMI.
- Requiring an IFS reflash. Everything installs on the writable app partition.
- Plugins able to stop the reverse camera image, eCall, or cluster warnings from appearing.
  Silencing PDC *tones* is allowed, but only with an explicit permission and an on-screen indicator.
- A plugin author having to decompile anything.

## 4. Constraints (facts, with sources in the onboard map)

**Runtime**
- IBM J9 2.3, CDC Foundation 1.1, which is a Java 1.4 API.
- **Only v48 (Java 1.4) class files load.** v49/v50 are rejected even with `-noverify` (spike).
- The whole HMI runs in `-Xmx60m`. `-noverify` is on.

**Missing APIs** (confirmed absent from the car's JCL): `StringBuilder`, `java.util.regex`,
`AutoCloseable`, `java.util.function`, `Objects`, `Map.merge`, `Long.sum`, `Integer.hashCode(int)`,
`Locale.ROOT`. There is no `java.util.concurrent` and no `String.format`.

**Class loading**
- `lsd.sh` puts every jar in `/mnt/app/eso/hmi/lsd/jars/` on `-Xbootclasspath` ahead of `lsd.jxe`, in
  unsorted `find` order. Class name collisions are silent.
- The LSD "OSGi" is 3SOFT's own kernel. Bundles are activator classes listed in `bundles.properties`;
  `-Dlsd.bundles` selects an on-disk file. When `lsd/dsitracer_activated` exists, that file is
  `bundles_dsitracer_enabled.properties`. It also installs `TraceConfigurator` as the
  `de.audi.tghu.serviceDelegateConfigurator`, a hook that wraps every DSI service.

**Threads and services**
- One HMI event dispatcher thread. There are named e.solutions dispatchers. Services can arrive late
  and connections bounce.

**Tooling and debugging**
- No debugger (JDWP is commented out) and no emulator (J9 doesn't boot in QEMU).
- Decompiled stock classes (jxe2jar) have byte-swapped numeric constants and can be missing
  InnerClasses attributes.

**Unit environment**
- `/tmp` is `/dev/shmem` (files only, no directories). `/ramdisk` is volatile.
- The `j9` launcher is absent from `/ifs/jre/bin` after boot.
- `lsd.sh`'s command line contains the VIN and IMSI. Never log it.

**Coexistence**
- `NavActiveIgnore.jar` (an M.I.B. patch) is installed and shadows stock CarPlay/AA proxies.
- carplay-rgi shadows `DisplayManagerMIB2High`, `PartialPopupManagerEvoHigh`,
  `StatusBarStubController` and other classes the SDK adapter is also likely to need.

**Authorship**: repos in `~/complex-projects` use GitHub `complex256` / `me@complex256.dev` (see the
CLAUDE.md there).

## 5. Architecture

```
 plugin jars (each in its own class loader, filtered parent)
   │  compile-time: mibsdk-api only
   ▼
 mibsdk-api        ← stable, firmware-neutral interfaces (events, actions, ui, hooks, storage, net, log)
 mibsdk-runtime    ← loader, lifecycle, permissions, threading, crash accounting, Plugins menu logic,
                     shared compat shims + Retrotranslator runtime (shipped once)
   │  calls only through
   ▼
 mibsdk-platform SPI ← internal adapter contract
   ▼
 adapter-hqc-nar-g22-c10   ← the only code that touches de.audi.* / de.esolutions.* / org.dsi.*
                             (service lookups, DSI delegates, menu/screen wiring; minimal shadowed classes)
```

### 5.1 Bootstrap [my call: preferred path, to be proven first]
- The SDK runtime and the adapter go in `lsd/jars/` (boot classpath), so their classes can see stock
  classes.
- **Preferred:** start the SDK as an ordinary **LSD bundle** by adding it to the on-disk bundle list.
  This path needs zero shadowed classes to start.
  - **How it gets loaded.** `lsd.sh` only uses an on-disk list outside developer mode when
    `lsd/dsitracer_activated`, `DSITracer.jar` and `bundles_dsitracer_enabled.properties` all exist.
    The **SDK installer writes that properties file itself**, so its contents are ours.
  - **What it contains.** It lists the SDK bundle and sets the SDK's own **chaining
    service-delegate configurator**. **It does not autostart the DSITracer bundle on end-user units**,
    so no tracer and no KeyPanel service on 1796 there. DSITracer.jar stays on the boot classpath but
    inert. [my call]
  - **Where DSITracer was already active** (the bench, for mib-keypanel), the installer keeps it: it
    autostarts DSITracer and the SDK configurator delegates to `TraceConfigurator`.
  - **Backup and restore.** The installer backs up and restores whatever marker, properties file and
    DSITracer.jar it found.
- **Fallback:** one shadowed stock class whose `init` starts the SDK, the way carplay-rgi uses
  `TerminalModeBapCombi`.
- The builder proves which works as the first milestone.
- The SDK starts **plugins only after the first HMI screen is shown** (stock `WaitForFirstScreen`
  phase), so the stock boot path doesn't depend on plugin code.

### 5.2 Adapter selection
At startup the runtime reads the HMI version. The source is the builder's call; candidates are
`/ifs/version_info.txt` and the lsd `version.txt`. It loads the matching adapter, or none. The adapter
reports a **feature set** (e.g. `slot.statusbar`, `hook.dsi.carparkingsystem`), and the API exposes
`platform.has(feature)` so plugins degrade gracefully.

### 5.3 Plugin packaging and loading
- A plugin is a jar plus a manifest. Fields: id, name, version, author, SDK API version, entry class,
  permissions, optional dependencies on other plugins, and placement declarations. The format is the
  builder's call; JSON parsed by the runtime is the [my call] default.
- Each plugin gets its own `URLClassLoader`. Its parent **filters out** `de.audi.*`, `de.esolutions.*`,
  `org.dsi.*`, `de.dreisoft.*`, `de.vw.*`, so plugins can only link against JCL + mibsdk-api + compat.
  Proven feasible: loading the same plugin twice in separate class loaders worked on the car.
- **Lifecycle**: `onEnable(ctx)` / `onDisable()`. Disable must release everything the plugin registered
  (listeners, slots, hooks, threads, sockets). The runtime tracks registrations per plugin so it can
  force-release them.
- Plugins live outside `lsd/jars/`, so they never enter the boot classpath. The directory is the
  builder's call; it must be on a writable, persistent partition that mibpkg manages.
- **On-unit paths** (decided after the bench run: `/mnt/app` is read-only while the HMI runs, and mibpkg and
  `mibsdk-dev` remount it read-write only around their own writes). The runtime never names these paths: they
  come from the platform side (`SdkRoots`, supplied by adapter-hqc's `SdkFiles`; tests use temp dirs).

  | Root | Path | Writable at runtime | Holds |
  |------|------|---------------------|-------|
  | install | `/mnt/app/eso/hmi/lsd/mibsdk/` | no (installer only, after `mount -uw`) | `sdk.properties`, `grants.properties`, `SAFE_MODE`, `plugins/<id>.jar`, `plugins/<id>.reload`, `stock/` |
  | persist | `/mnt/persist/mibsdk/` | yes (qnx6, from boot) | `boot.state`, `ledger.properties`, `data/<id>.properties` |
  | ram | `/ramdisk/mibsdk/` | yes (lost at power-off) | `cache/<id>-<n>.jar` |
  | SDK jars | `/mnt/app/eso/hmi/lsd/jars/` | no | `mibsdk-runtime.jar`, `mibsdk-adapter-hqc.jar`, `mibsdk-rt-retrotranslator.jar` |
  | log | `/tmp/mibsdk.log` | yes (`/dev/shmem`: no rename, no directories) | the ring log, rewritten in place |

  - `/mnt/persist` is about 8 MB (6.6 MB free) and shared with the stock `persistence.sqlite`, so `data/` is
    capped (default 256 KiB per plugin, 1 MiB in total; `storage.*.max.bytes` in `sdk.properties`). A plugin
    write past the cap fails; it never fills the partition.
  - The runtime never deletes a reload marker. It reloads once per new or changed marker, tracking the ones
    it consumed in memory; markers present at start count as consumed. `mibsdk-dev push` writes a fresh token.
  - The configurator reads and writes `boot.state` in the persistent root on the LSD thread at init, with no
    remount and no exec.
  - A root the runtime cannot write is reported (`STATUS mibsdk state persist=… UNWRITABLE … bootGuard=inert`);
    an inert boot guard never reports a healthy boot.
  - Uninstall keeps `/mnt/persist/mibsdk/` (plugin data) unless `--purge`.

## 6. Containment (G4)

- **Exceptions:** every plugin callback runs inside the runtime's catch-all. Repeated failures
  **quarantine** the plugin: it is disabled and its status is shown in the Plugins menu with the reason.
- **Threads:** plugin code never runs on stock HMI threads.
  - The runtime gives each plugin its own worker, or a shared pool with per-plugin queues (builder's call).
  - UI changes are marshalled by the runtime.
  - **Plugin code never runs on a stock thread, not even with a deadline.** [my call] Modify and veto
    hooks are **declarative**. The plugin publishes a rule ahead of time, e.g. "veto key X while my
    flag is set", "override attribute Y with value Z", "drop DSI call C when predicate P". The runtime
    evaluates the rule on the stock thread **without running plugin code**: precompiled matchers over
    immutable state the plugin swaps atomically. Plugins are told about events **asynchronously, after
    the fact**, and react by updating their rules or calling actions. The bike-rack "re-apply if changed
    back" fits this: an async observer plus an action.
- **Logging:** a non-blocking, in-memory ring buffer drained off-thread. It is readable over the dev
  channel and in the Plugins menu.
- **Boot safety:**
  - A persisted crash counter: if the HMI restarts N times within T minutes with plugins enabled, the
    next boot comes up in **safe mode** (SDK on, no plugins) and the Plugins menu says so.
  - A **manual kill switch** (a marker file on the app partition, plus some on-car gesture: builder's
    call) forces safe mode.
  - The adapter mismatch rule in §5.2 makes "wrong firmware" mean "no plugins", never a boot loop.
- **Memory:** there's no per-plugin heap limit on J9 1.4. The runtime watches free heap and can disable
  the most recently enabled or most allocation-heavy plugin when it's low (heuristic: builder's call).

### 6.1 Reversible vehicle writes [my call]
Actions that change persistent vehicle or ECU state go through a runtime-owned **ledger** that records
the prior value. Example: `setPDCSoundReproduction`, which probably persists in the parking ECU. The
**runtime**, not the plugin, restores the prior value whenever the plugin is disabled, quarantined,
revoked or uninstalled, and at **safe-mode boot**: safe mode means the SDK is on with no plugins, and
it replays pending restores. The runtime also owns the on-screen indicator for active safety-relevant
overrides (e.g. "rear PDC muted"), so a plugin can't forget to show it. Plugins can declare an override
as "until ignition off" (the default for safety-relevant writes) or "persistent". The ledger itself is
persisted. Known gap: if a firmware update leaves no matching adapter, the SDK is off and can't restore.
The mibpkg SDK package must therefore replay the ledger, or warn, before any firmware update it knows
about.

## 7. Permissions (G5)

- Manifest-declared capability strings, grouped for display. Examples: `vehicle.read`,
  `vehicle.parking.write`, `vehicle.climate.write`, `media.control`, `input.inject`,
  `ui.slot.<slot>`, `hooks.dsi.<service>`, `hooks.hmi.keys`, `network`, `native.exec`.
- **mibpkg shows them in plain words and asks for approval at install time**, in whatever mibpkg UI did
  the install (CLI, web or GEM). No on-car approval flow in v1. [my call]
- **Grants live on the unit** in a runtime-owned grants file next to the plugins. mibpkg writes it at
  install; the runtime reads it at load. The Plugins menu shows what each plugin was granted and lets
  you revoke grants by editing that file; a revoked capability disables the features that need it.
  mibpkg re-reads the file rather than keeping its own copy, so no live control channel is needed.
  [my call]
- **Enforcement is at the API:** a plugin only receives the service handles it was granted.
- **Two guards beyond the API:**
  - the filtered class loader (§5.3);
  - a **bytecode scan** at build and install time that flags reflection, `Class.forName`,
    `Runtime.exec`, `System.exit` and similar escape routes.

  Plugins that need raw access must declare `platform.unrestricted`, which gets a loud warning at
  install.
- The docs must say plainly this is not a sandbox: it stops accidents and makes intent visible, but it
  does not stop a determined malicious plugin.

## 8. API surface

**v1 scope is what §11's reference plugins and forced-failure test need** [my call]:
- the Plugins menu, a Car-menu row, native list/settings/info templates and the status-bar slot;
- per-plugin storage, a TCP client, ignition events and PDC events/actions;
- one DSI service-level hook path, plus the containment and permissions machinery.

Everything else below is **v1.x**, roughly in this order:
1. Settings-menu and popup slots
2. HMI key/knob hooks
3. media control and key injection
4. TCP server/HTTP helper
5. the broader event catalogue
6. the custom panel
7. more slots (Media source, cluster)

The API should be shaped so these add without breaking changes.

The builder designs the exact shapes. The style should be lambda-friendly for Java and Kotlin
(`events.on(GearChanged.class, e -> …)`), with no runtime annotations required.

- **Events (read):** ignition/terminal state, gear, speed, doors, PDC state and settings including
  "trailer hitched", media source and now-playing, phone connected/call state, CarPlay/AA connection,
  day/night, HMI screen changes. The builder picks the initial catalogue from the DSI services the
  adapter wraps, and it should grow toward what an automation plugin needs.
- **Actions (write):** the matching DSI setters, each behind a permission. Examples: PDC sound
  reproduction per direction (`setPDCSoundReproduction`), volume and mute; media control; key/knob
  injection.
- **Hooks:**
  - Service level: intercept DSI calls and updates to observe, modify or veto.
  - HMI level: key/knob events before stock handling, screen transitions, menu actions.
  - Modify/veto is declarative and evaluated without plugin code; observation is async (§6).
- **UI:**
  - The **Plugins menu**. [decided] It lists plugins with version, status, on/off switch, permissions,
    settings page and the plugin's own pages. Its entry point is the builder's call: the main MMI menu
    is preferred, the Car menu is the fallback.
  - **Native templates:** list menu, settings page (toggles, choices, sliders), info/detail page,
    popup/notification. Plugins describe content and actions; the adapter renders them with stock
    widgets and stock knob/back handling.
  - **Slots** [decided: plugins can place themselves anywhere, in addition to the Plugins menu]. v1 set
    [my call]: Car-menu row, Settings-menu row, status-bar icon/text, popup/notification. More slots
    (Media source, home tiles, cluster) come as the adapter grows. Slots are SDK-owned and merge
    contributions from all plugins.
  - **Custom panel:** a full-screen or region surface drawn by the plugin, the fallback when templates
    don't fit. Whether it goes through stock EAL/Kanzi or a native overlay like mib2q-glasshud is the
    builder's call after a spike.
- **Storage:** per-plugin persistent key/value settings. Writes are batched off-thread.
- **Network / native IPC:** TCP client/server and a small HTTP helper behind the `network` permission.
  This is how plugins talk to carmqtt or tailscale daemons, or serve a web UI.
- **Utilities:** compat shims, a scheduler, JSON, and logging.

## 9. Toolchain and dev loop (G1, G9)

- A build plugin (Gradle is [my call]; Gradle is already installed) wraps the spike's proven pipeline:
  1. javac 17 + Jabel, or kotlinc `-jvm-target 1.8 -Xlambdas=class` plus ProGuard (shrinks the stdlib
     and strips `LocalVariableTable`);
  2. Retrolambda → v49;
  3. Retrotranslator → v48, with `-backport` onto the `mibsdk.compat` shims;
  4. **LinkCheck** against the car's JCL and the SDK API: call sites, class headers and catch types
     (the spike showed headers matter);
  5. the permission bytecode scan;
  6. packaging for mibpkg.
- The Retrotranslator runtime and compat shims ship **once** in mibsdk-runtime, not inside each plugin.
- The **JCL reference** comes from `jxe2jar` output of `lsd.jxe`. Note that `java/lang/Double.class`
  is unreadable there; treat it as present.
- **Fake platform adapter** for host unit tests: scripted events, recorded actions, and in-memory slots.
- **Dev channel on the bench unit:** push one plugin jar, hot-reload it, and stream its log. The bench
  is `10.20.0.23`; SSH needs `-o HostKeyAlgorithms=ssh-rsa -o PubkeyAcceptedKeyTypes=ssh-rsa`. The
  transport (SSH vs a runtime TCP port behind a dev flag) is the builder's call.
- On-car verification can use mib-keypanel (inject keys, grab `/screen.jpg`).
- **Hardware.** The bench unit (`10.20.0.23`) was checked on 2026-10-18. It runs the same build,
  `HMI_AU_MIB2_HQC_NAR_G22_C10_180803`, with an identically sized `/ifs/lsd.jxe` (60,604,861 bytes).
  - Its `lsd/jars` differ from the car's: dev/tracer jars (`xTcpKeyPanel`, `DevelopmentStubEvo`,
    `xEventRecorder*`, `xTcpLog`) and **no** NavActiveIgnore or carplay-rgi.
  - The bench has no parking ECU, ignition or gear signals. PDC and ignition behaviour can only be
    verified on the car (see open questions for the owner).

## 10. Distribution (G8)

mibpkg (`~/complex-projects/mibpkg`) gains a **declarative plugin package type**. It:
- checks SDK API compatibility and the adapter/firmware;
- shows permissions for approval;
- installs into the plugins directory;
- hot-loads the plugin if the SDK is running, or flags it for next boot otherwise.

The SDK runtime itself is also a mibpkg package. It needs a reboot because it changes `lsd/jars` and
the bundle list, and it must back up and restore any stock file it edits, the same pattern as
carplay-rgi's `.carplay-stock`.

## 11. Acceptance: reference plugins

v1 is done when these three run on the owner's unit, are installed through mibpkg, and survive a forced
failure test:

1. **hello-menu:** adds a page to the Plugins menu and a row in the Car menu that opens a native list
   template.
2. **daemon-status:** shows mib2q-tailscale and mib2q-carmqtt state as a status-bar item plus a detail
   page, over local sockets. The daemon side may need a tiny status endpoint (builder's call).
3. **bike-rack:** a manual toggle (Plugins menu plus a Car-menu row).
   - **On:** rear PDC tones are silenced via `setPDCSoundReproduction(rear=false)`, keeping
     front/left/right as they were. A service hook re-applies the setting if something else changes
     it back. A status-bar icon is shown while active.
   - **Off:** the previous values are restored.
   - Reverts automatically on ignition off unless the user chose otherwise. [my call; plugin-level default]
   - Unknown: whether this car's parking ECU honours per-direction reproduction. That has to be verified
     on the car early; the fallbacks are `setPDCVolumeRear` / `setPDCMute`.

Forced-failure test: a deliberately broken plugin (throws in `onEnable`, blocks in a hook, infinite
loop on its worker) gets quarantined. The HMI stays responsive and the other plugins keep working.

## 12. Open questions for the builder to resolve by spike

1. Does the bundle-list bootstrap (§5.1) work? Specifically: are on-disk bundle files honoured on the
   owner's unit, can a second `serviceDelegateConfigurator` chain with `TraceConfigurator`, and what
   are the side effects of keeping `dsitracer_activated` on permanently?
2. How to inject a Plugins entry and slots into stock menus with the fewest shadowed classes. Leads:
   carplay-rgi's `ToneModelBank`/`ToneScreenFactory`/`getChoiceModel` substitution, and the HMI
   framework's `AbstractHMIActivator`/`ScreenFactory`.
3. Whether native templates can be built from stock widgets at runtime (generated `ScreenFactory`
   style), or whether existing stock screens have to be reused with SDK-supplied models.
4. What respawns j9 after a crash, and how fast (affects the crash-counter thresholds).
5. How carplay-rgi's overrides split between "adapter feature" and "privileged module" (§13).

## 13. carplay-rgi [decided: option (c)]

The SDK adapter becomes the **sole owner** of every stock class it needs to shadow. This includes the
ones carplay-rgi shadows today: `DisplayManagerMIB2High`, `PartialPopupManagerEvoHigh`,
`StatusBarStubController`, and the Tone/Phone/System activator substitutions. carplay-rgi **migrates
onto the SDK**:
- as a regular plugin wherever the API covers its needs;
- otherwise as a **privileged built-in module** that ships with the adapter and may use
  adapter-internal seams (e.g. cluster BAP gating, display contexts).

Its `com.luka.carplay.*` proto-framework (`Module`, `FrameworkRef`, retry and debounce) is prior art
for the runtime.

**Until the migration lands** [my call], the SDK installer **refuses to install** while carplay-rgi's
jar is present. It detects that jar and its `.carplay-stock` backups, and says why. This keeps the two
from ever shadowing the same class. The bench has no carplay-rgi installed, so this doesn't block bench
work.

## 14. Test hardware and sequencing [decided: bench only for the next week]

The owner is away from the car until about 2026-10-25; **only the bench unit is available until then.**
The plan should do bench-provable work first:
- the bootstrap spike (bundle-list path plus chained delegate configurator, on the bench's own
  DSITracer setup);
- the runtime and containment, including the forced-failure test;
- permissions, the toolchain and the fake adapter;
- the Plugins menu, templates and status-bar slot;
- hello-menu and daemon-status.

It should schedule **one car session after the owner returns** for the things only the car can prove:
- per-direction PDC muting and the ledger's restore on ignition off (bike-rack);
- the bootstrap coexisting with the car's own `lsd/jars` (NavActiveIgnore, and the carplay-rgi guard);
- j9 respawn timing for the crash counter.

Until then, bike-rack is built and tested against the fake adapter, whose PDC model simulates
per-direction reproduction, persistence and ignition cycles.
