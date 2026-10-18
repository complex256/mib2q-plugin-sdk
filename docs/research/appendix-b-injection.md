# 03 - Java injection today, the carplay-rgi proto-framework, and the stock seams an SDK would wrap

Scope: read-only survey of `~/complex-projects/mib2q-carplay-rgi` (primary), `mib2q-hmi-lab`,
`mib-keypanel`, `mibpkg`, `mib2q-glasshud`, `mib2q-carmqtt`, `~/projects/audi-mib2/patching`, plus prior
Claude memory for carplay-rgi and hmi-lab. Date of survey: 2026-10-18.

> **Checkout caveat.** The carplay-rgi working tree is local `main` @ `c475724` (2026-09-24).
> `origin/main` @ `e39814c` (2026-10-17) is **32 commits ahead** and is the real current state
> (telephony, Siri, UpMix MMI row, VehicleData, AltScreen, CN variant, firmware-gated installer,
> `docs/LANDMINES.md`). Everything below tagged **[origin/main]** was read via `git show origin/main:...`
> (nothing was checked out or modified). Untagged facts hold on both.

---

## 1. How injection works today

### 1.1 Load mechanism: jar on j9's boot classpath, ahead of `lsd.jxe`

- The HMI is IBM J9 (CDC 1.1 + Foundation Profile 1.1, `jclfoun11`, ~J2SE 1.4, bytecode major 48,
  run with `-noverify`) started by `runHMI.sh` -> `lsd.sh`. Stock `lsd.sh`
  (`~/projects/audi-mib2/firmware/.../eso/hmi/lsd/lsd.sh`):
  ```sh
  JARS=$(find $BASE_DIR/lsd/jars/ -name '*.zip' -or -name '*.jar')
  for jar in $JARS; do BOOTCLASSPATH="$BOOTCLASSPATH:$jar"; done
  BOOTCLASSPATH="$BOOTCLASSPATH:$LSD_JXE"
  $J9 $VMOPTIONS -Xbootclasspath:$BOOTCLASSPATH -jxe:$LSD_JXE &
  ```
  `J9="on -p 12 $JAVA_HOME/bin/j9"`, heap `-Xmx60m -Xmn10m -Xmo50m`, `-DErrorDumpTriggerPort=6767`,
  JDWP `:12345` and `-Xrunprof` present but commented out. `development_activated` flag runs
  `de.dreisoft.lsd.LSD` from `hmi.zip` instead; `dsitracer_activated` adds `DSITracer.jar` and swaps the
  OSGi bundle list.
- **So: any `*.jar`/`*.zip` dropped in `/mnt/app/eso/hmi/lsd/jars/` is on the boot classpath before
  `/ifs/lsd.jxe`, and a class with the same binary name wins.** That is the entire Java "install" - no
  OSGi registration, no launcher edit (memory `unit-firmware-identity.md`).
- **Order among jars is NOT defined**: `find` returns directory order, not sorted (the hmi-lab QEMU stage
  sorts; the unit does not). `lang_data.zip` (HMI string tables) and stock jars (`fw_util_*.jar`,
  `AppDevelopment.jar`, `xTcpKeyPanel.jar`) live in the same folder.
- Older alternatives seen:
  - `~/projects/audi-mib2/patching/patched/install_NavActiveIgnore_jar.sh` sed-patches `lsd.sh` to add
    `-Xbootclasspath/p:$BASE_DIR/lsd/jars/NavActiveIgnore.jar` (guaranteed prepend, but edits `lsd.sh`,
    not idempotent).
  - carplay-rgi archive [origin/main `docs/reference/ARCHIVE_AND_HISTORY.md`]: first deployment
    prepended `carplay_hook.jar:` to `CLASSPATH` in `navigation.json`, compiled `-source 8 -target 8`.
    Superseded.
- **Shadowing only reaches classes the boot loader resolves.** A replacement of
  `de/esolutions/fw/dsi/kombisync2/DSIKombiSyncProvider` "did not shadow ... OSGi bundle isolation kept our
  class out" (commit `a36987e` header of `FilteringKombiProxy`); the fix was a reflection field swap
  (see 3.23). Conversely, boot-classpath code **cannot see bundle-private classes**: on 2026-10-10
  `VehicleDataModule$KombiListener extends de.audi.remotehmi.util.dsi.DSICarKombiListenerAdapter`
  compiled and passed the offline audit, then threw `NoClassDefFoundError` on the car, failed
  `CarPlayApp.<clinit>` and took `AppTerminalMode` + `AppNavi` down ("Initialising navigation" forever)
  [origin/main `docs/LANDMINES.md:426`, `KombiListenerBase.java` header]. Only `org.dsi.*` interfaces
  and classes inside `lsd.jxe` are safe link targets.

### 1.2 How each shadow is written: full copy of the decompiled stock class + surgical edits

All replacements are **whole-class copies** of the decompiled stock source (CFR/Vineflower output of
`jxe2jar`), edited in place. No bytecode weaving, no ASM at runtime, no java.lang.reflect.Proxy (the
runtime has no dynamic proxies worth relying on). Evidence: `tests/audit_java_sources.py` diffs each
`java_patch/` file against the stock decompile tree `Tools/jxe2jar/out/MU1316-vf/` and reports "N full
stock replacements"; `JavaStockLinkageAudit.java` checks both patch->stock links and stock->patch
callers so no non-private member of the original disappears. Rule [LANDMINES]: "Never delete a replaced
stock Java class - deleting it silently restores the stock class and the hook disappears".

Edit styles used inside a copied class:
1. **Call-out hook**: one guarded static call into `com.luka.carplay.*`, wrapped in
   `try { ... } catch (Throwable t) { /* never crash the stock flow */ }`.
2. **Behaviour gate**: an `if (CarPlayApp.isActive() && PdcSmallStageGuard.shouldKeepCarPlayScreen())`
   branch that skips a stock action.
3. **Added accessor**: new public methods exposing private stock state so custom code avoids reflection
   (`ClusterService.getDSIResponseContainer()/triggerRefreshRGIValid()/getCombiBAPListenerCombiService()/
   setCombiBAPListenerCombiService()`, "patched-in; avoid reflection in BAPBridge").
4. **Factory substitution**: construct a custom subclass where stock constructs its own
   (OSGi `Activator`s building `CarPlayPhoneSMM`/`CarPlaySystemSMM`/`UpmixToneUi.Factory`;
   `ServiceManagerPhone` wrapping the registered listener in `GatedPhoneListener`) [origin/main].
5. **Inner-class trick**: `AppConnectorTerminalMode$CoverArtProvider.java` is a *top-level* source file
   whose binary name equals the stock inner class; earlier history shipped
   `TerminalModeBapCombi$ActiveDeviceStateListener.java` the same way. Memory
   `verifying-patch-against-unit-hmi.md`: every replaced outer class must ship all its nested classes,
   or a stock nested class left beside a patched outer calls a vanished `access$NNN` bridge.

#### Shadowed stock classes (local HEAD: 18 files; origin/main: 24)

| Stock class | Edit | Purpose |
|---|---|---|
| `de.audi.app.terminalmode.combi.TerminalModeBapCombi` (+ inner `ActiveDeviceStateListener`, `EventListener`) | call-out | **Bootstrap**: `init()` -> `CarPlayApp.startTransport(ctx)` + `CoverArt.start()`; `deinit()` -> `onDeactivateAndWait()`; TMDevice ACTIVATING/ACTIVE/INVALID -> `CarPlayApp.onActivate(ctx)/onDeactivate()`; EventListener merges cover art into BAP metadata |
| `de.audi.app.combi.bap.app.audio.AppConnectorTerminalMode` (+ `$CoverArtProvider`, new `CoverArtProviderMux`) | rewrite | cluster cover art; mux the single picture-provider slot (reflection on `pictureProviders`) |
| `de.audi.tghu.navi.app.cluster.ClusterService` | accessors + gate | BAP nav gate install on `setCombiBAPService`, bargraph guard, exposed internals |
| `de.audi.tghu.navi.app.cluster.ScreenCombiBAPListener` (origin: `AltScreenCombiBAPListener`) | call-out | VC Fct44 visibility / Fct54 stage -> `ClusterLayerController` |
| `de.audi.tghu.fwhmi.DisplayManagerMIB2High` | data + gate | defines extra cluster context `dc[80]={98,101,102,33}` in `defineContexts`; `switchContext` refuses stock writes to terminal 1 unless from our writer thread; skip KDK remap for ctx>=80 |
| `de.esolutions.hmi.widgets.audi.evo.high.widgets.CombiMapController` | call-out + gate | view-size -> `ScreenModule.setViewAreaMode`; KDK layout -> `ClusterLayerController`; View-button pin while CarPlay owns cluster |
| `de.audi.app.terminalmode.dsi.carplay.CarplayDSILifecycleController` | call-out | installs `TouchpadController.TouchSink` -> `dsiCarplaySafe.postButtonEvent`; partitions touchscreen vs touchpad events; swallows key-40 copy of DDS_SELECT |
| `de.audi.app.terminalmode.ExternalEventsListener` | gate | skip `HMIDeactivated` for guarded OPS popup |
| `de.audi.app.terminalmode.interapp.HighPriorityResourceTracker` | gate | msg 108 keeps CarPlay SCREEN |
| `de.audi.app.earlyfunc.evo.parking.ParkingSystemControllerComponentEvo` | call-out | arm PDC guard before 108 |
| `de.audi.app.earlyfunc.core.parking.ParkingPartialPopupHandler` | call-out | popup show/hide notifications |
| `de.esolutions.hmi.widgets.audi.evo.high.PartialPopupManagerEvoHigh` | gate | hide status line popup 62 |
| `de.esolutions.hmi.widgets.audi.evo.widgets.StatusBarStubController` | gate | hide entertainment drawer under guard |
| `de.audi.audio.context.AudioDrawerContextImpl` | delegate | `OpsAudioDrawerPolicy` over list model 538 |
| `de.audi.tghu.phone.sm.Activator` [origin] | factory | build `CarPlayPhoneSMM` |
| `de.audi.tghu.system.sm.Activator` [origin] | factory | build `CarPlaySystemSMM` (TEL key) |
| `de.audi.tghu.tone.hmi.Activator` [origin] | factory | `UpmixToneUi` model bank + screen factory, stock fallback on any Throwable |
| `de.audi.app.combi.bap.app.phone.ServiceManagerPhone` [origin] | wrap | `GatedPhoneListener.wrap(...)`, `GatedPhoneService` |
| `de.audi.app.sdsmanager.apps.terminalmode.TerminalModeSDSHandler` [origin] | gate | short PTT -> Siri |
| `de.audi.app.terminalmode.smartphone.carplay.CarPlaySpeechRequestHandler` [origin] | rewrite | replays long-press path for Siri tap |
| `de.audi.tghu.storage.FlushValuesDict` [origin] | **shared, extensible** | stock flush list + `register()`; shipped identically by every mod |

### 1.3 Build

- Local HEAD `scripts/build_java.sh`: Docker `eclipse-temurin:8-jdk-jammy`, `javac -source 1.4 -target 1.4
  -cp MU1316-final.jar:org.osgi.framework-1.10.0.jar:org.osgi.util.tracker-1.5.4.jar`, then
  `cp -R java_resources/.` and `jar cf build/carplay_hook.jar`. `CarPlayApp.java` is sed-copied with
  `@BUILD_ID@` = `git describe`. No manifest, no signing.
- [origin/main] builds **one jar per firmware family**: `carplay_hook.jar` vs `java_stock/US_MU1316_lsd.jar`
  (US MU1316 / EU MU1329 / JP MU1017 byte-identical for every replaced class) and
  `carplay_hook_cn.jar` = `java_patch/` + `java_patch_cn/*.patch` applied with `patch -p1 -F0` (strict;
  any drift fails the build) vs `CN_MU1003_lsd.jar`. Six classes differ on CN (superclass of
  `PartialPopupManagerEvoHigh`, an extra `dc[34]`, `IDSIResource` getter arity, ...). Writes a build
  receipt JSON (sha256, commit, dirty). `scripts/audit_java_variants.sh` requires 0 linkage errors per jar.
- Stock compile jars: `jxe2jar` (Python 2) on `/ifs/lsd.jxe` + the release's `eso/bundles*`,
  `eso/hmi/lsd/jars`. jxe2jar output is lossy: ~50 classes dropped, no InnerClasses attributes
  (bogus compile errors), byte-reversed numeric constants, zero `invokeinterface` counts; only 2,526 of
  30,493 classes verify unrepaired (hmi-lab `scxml/README.md`). Memory workaround: `romstubs.py`
  signature-only stubs for all ~30.5k classes, infer nested `static` from absence of `this$`.
- Foundation 1.1 limits [LANDMINES]: no generics/enum/enhanced-for/autoboxing/`StringBuilder`;
  `-target 1.4` limits bytecode not API, so a 1.5 method (e.g. `Class.getSimpleName`) compiles and then
  `NoSuchMethodError`s on the unit -> audit against the firmware `jcl.jar`
  (`Tools/jxe2jar/libs/jcl/MHI2Q_US_AUG22_P5087_MU1316/jcl.jar`). Decompiler artifact
  `"isNativeLittleEndian"` means `""` and javac inlines it from the compile jar -> grep built bytecode.
- Tests run the shipping jar against real stock classes on host JDK 8 with the physical HMI faked
  (`tests/*Test.java`, `scripts/test_pdc.sh`, `test_route_info.sh`, `test_java_transports.sh`).

### 1.4 Package, deploy, persist, uninstall

- Payload path: `/mnt/app/eso/hmi/lsd/jars/carplay_hook.jar` (644). `/mnt/app` is read-only until
  `mount -uw /mnt/app` (restore with `mount -ur`).
- Installer: M.I.B. SD card `mod/custom.sh` (+ `command.sh` forwarder), run from GEM -> M.I.B. ->
  Advanced -> Run Custom Script. Copies each file via `<f>.carplay-new.<pid>` + atomic `mv`, keeps
  `.carplay-stock` backups once for every edited config, never stops processes, ends "Reboot the HU to
  load". [origin/main] adds: reads firmware train from E2P (`modifyE2P r 3A0 19`), refuses unverified
  trains once (second run in the same power cycle forces), picks the jar variant by region (any other
  region gets no jar), deletes `._*` AppleDouble files (a `._carplay_hook.jar` would land on the
  bootclasspath), deletes M.I.B.'s `NavActiveIgnore.jar` (it replaces `org.dsi.ifc.carplay.AppState`),
  patches `lang_data.zip` with micropython to add MMI strings, backs that up too.
- Persistence: the jar survives reboots (flash). A firmware update reflashes `/mnt/app`? (not
  established in docs; mibpkg's `gempatch.rs` refreshes stale backups because "a firmware update can
  change the stock jar"). The jar is not re-validated at boot - a mismatched jar after an update is the
  issue #24/#50 boot loop.
- Activation: **only a full reboot**. Killing j9 = critical-process death -> whole-system restart, ~50 s
  black screen, SSH drops (carplay-rgi `install.md:403`, LANDMINES). Contradicting claim in
  `mib-keypanel` docs: `slay -s11 j9` respawns j9 in ~10-15 s. Unverified which is right on which unit.
  Reboot command: `on -f rcc /usr/apps/mib2_ioc_flash fastReboot` (no `reboot` binary).
- Uninstall: M.I.B. uninstall card renames every `*.carplay-stock` back and deletes owned files; manual:
  `rm -f /mnt/app/eso/hmi/lsd/jars/carplay_hook.jar` + restore configs + reboot.
- Memory `install-via-mib.md`: the user wants installs only via the M.I.B. bundle, never hand-edits over
  SSH (it breaks the `.carplay-stock` backup logic).

### 1.5 What breaks across firmware versions

- Whole-class replacement freezes the *entire* stock class at one train. On any train where the
  original differs (superclass, constructor, method set), stock callers hit `NoSuchMethodError`/
  `IncompatibleClassChangeError`, the HMI never finishes booting, `servicemgrmibhigh` HMI supervision
  (`dsistartup.json`: 70 s HMI period, 300 s DSISystem) requests a reset -> boot loop on the Audi logo.
  Issue #24: US jar on `MHI2Q_CN_AUG22_K1004`; #50: US jar on older US P4246; also US P4171.
  Recovery analysis: [origin/main `docs/build-and-deploy/ISSUE24_RCC_MMX_RECOVERY.md`].
- Verified trains [origin/main installer]: `MHI2Q_US_AUG22_P5087` (MU1316), `MHI2Q_ER_AUG22_P5092`
  (MU1329), `MHI2Q_JP_AUG22_P1017`, `MHI2Q_CN_AUG22_K1004` (MU1003).
- Platform variance inside one train: G24 single-display cluster disables ctx 80
  (`getKombiType()==4`); `sysConst(541)==2` gates KDK backings; `IMMICombiScreenChangeManager` absent
  on A5-class; internal vs B&O amp changes the audio route; front vs rear MU (`isFrontMU()`).
- Compile-jar artifacts (jxe2jar) can encode wrong constants into copied source.

---

## 2. The proto-framework inside carplay-rgi (`com.luka.carplay.*`)

### 2.1 Bootstrap chain

```
lsd.jxe OSGi start -> TerminalMode app -> TerminalModeBapCombi(IContext)   [shadowed]
  .init()   -> CarPlayApp.startTransport(context)   // boot: bus :19810 up, FrameworkRef seeded,
             -> CoverArt.start(eventListener)        //       lifecycle worker started
                [origin] UpmixSetting.boot(fw)       //       persisted setting -> /tmp file
  ActiveDeviceStateListener: TMDevice ACTIVATING|ACTIVE && isCarplayDevice -> CarPlayApp.onActivate(ctx)
                             !isCarplayDevice && active                   -> CarPlayApp.onDeactivate()
  .deinit() -> CarPlayApp.onDeactivateAndWait()      // bounded 3 s
```
The single entry point is one shadowed ITerminalModeComponent; every hook is wrapped in `catch Throwable`.

### 2.2 Components (this is effectively a mini plugin host)

- **`core.Module`** (`java_patch/com/luka/carplay/core/Module.java`) - the plugin contract:
  `String name(); boolean start(FrameworkRef fw); void stop();` - `start` returns false = "dependency not
  up yet, retry me"; `stop` must be idempotent and callable without a prior start.
- **`core.CarPlayApp`** - the host/lifecycle manager:
  - static ordered `MODULES[]` (HEAD: `ScreenModule, RgdModule, SteeringWheelInputModule`;
    origin: `AltScreenModule, RgdModule, SteeringWheelInputModule, VehicleDataModule`). Order is a
    dependency chain: a module returning false stops the pass.
  - Stock callbacks only publish a desired generation (`active`, `desiredContext`,
    `lifecycleGeneration++`, `notifyAll`) and return. A daemon `carplay-lifecycle` worker applies it:
    stop all modules in reverse, build a new `FrameworkRef`, start pass, else `carplay-retry` thread
    (200 ms x50 then 2 s forever - no timeout, nav can appear very late on cold boot).
  - 400 ms deactivate debounce (USB replug bounces ACTIVATING<->INVALID<->ACTIVE).
  - Generation checks before/after every external `start()`; a stale partial start is stopped.
  - Lock ordering documented (ABBA deadlock on cold boot fixed: never hold the state lock across
    `Module.start`).
  - Targeted rebind: `onNavigationServiceChanged()` restarts only `RgdModule` when Navigation's OSGi
    service flips.
  - `isActive()` is the global gate the shadowed stock classes read.
  - Lesson [LANDMINES:426]: never construct modules/listeners in the static initializer; a link error
    in `<clinit>` kills the host bundle. Build listeners in `start()` under `catch Throwable`.
- **`core.FrameworkRef`** - capability/context object handed to modules: wraps
  `de.audi.app.terminalmode.IContext` -> `getFramework()` (`de.audi.atip.base.IFrameworkAccess`),
  `getServiceManager()` (`de.audi.app.terminalmode.osgi.IServiceManager`), `getDeviceManager()`,
  `IFrameworkAccess.getHmiServiceApp()` (`de.audi.atip.hmi.IHMIServiceApp`). `getServiceHandle(Class)`
  walks all `ServiceReference`s and returns a paired `ServiceHandle` with `release()` (handles
  remove/add races).
- **`bus.CarplayBus`** - singleton TCP server `127.0.0.1:19810` for the native hook; framed
  `[u32 'CPHB'][u32 seq][u16 type][u8 flags][u8 rsv][u32 len][payload]`, sticky/replay flags,
  `on(type, Listener)` / `send()` enqueue-only into a 32-entry drop-oldest ring with a dedicated writer
  thread; text payload parser `Data`. Rule: `on()` must never block on `dispatchLock` (j9 deadlock).
- **`rgd.RendererServer`** - second TCP server `:19800` for `maneuver_render`; [origin]
  `AltscreenControlServer` `:19832`. Java never spawns processes (`carplay_monitor.sh` supervises).
- **`framework.Log`** - async file logger to `/tmp/carplay_java.log` (512 KB, rotate `.1`), 256-line
  drop-oldest queue, writer thread; never `System.out/err` (lsd routes those to slog, adds boot
  contention). Level WARN unless marker `/mnt/app/carplay_verbose` or `/tmp/carplay_verbose` exists:
  read at class init only on HEAD; [origin] re-read at every CarPlay session start (`refreshLevel()`).
- Feature-local static "service locators" with listener interfaces: `ScreenModule`
  (`ViewAreaModeListener`, `InfoModeListener`, `isConnected()`, `setNavActive()`),
  `ClusterLayerController` (`ViewportListener`), `TouchpadController.TouchSink`, `CoverArt.Callback`,
  `PdcSmallStageGuard` (static state machine). These are effectively an internal event bus done with
  static volatile single-listener slots.
- Threading discipline: stock callbacks only flip volatiles and wake a worker (session-lifecycle R2/R3);
  single serialized writer for shared stock resources (ScreenModule's `carplay-cluster-switch` is the
  only `switchContext` caller); all socket I/O on dedicated threads.

### 2.3 What is missing to call it a plugin framework
No discovery (hardcoded `MODULES[]`), no isolation (one jar, one namespace, one classloader), no
versioning/compat declaration per module, no config, lifecycle tied to "CarPlay connected" rather than
HMI boot (boot-time hooks are ad hoc in `startTransport`), no shared-class arbitration except the
`FlushValuesDict` convention, no event bus beyond static listener slots, no hot reload.

---

## 3. Extension seams in the stock HMI (what custom code calls, and how it got the handle)

| # | Seam | Real stock API used | How obtained |
|---|---|---|---|
| 3.1 | **HMI framework / context** | `de.audi.app.terminalmode.IContext` (`getFramework()`, `getServiceManager()`, `getDeviceManager()`, `getEventBus()`, `getAudioManager()`, `getLogger()`); `de.audi.atip.base.IFrameworkAccess` (`getHMIService()`, `getHmiServiceApp()`, `getKombiType()`, `getSysConst(541)`, `getBundleCxt()`, `isFrontMU()`, `isDualView()`) | **captured in a shadowed constructor/init**: `TerminalModeBapCombi(IContext)` passes it to `CarPlayApp.startTransport`; parking classes use `ICarApplication.getFrameworkAccess()`; `AbstractSMMActivator.framework` / `AbstractHMIActivator.getFramework()` in shadowed Activators |
| 3.2 | **OSGi / DSI service lookup** | `IServiceManager.getServiceReferences(Class)`, `getService(ref)`, `releaseService(ref)`; services fetched: `CombiBAPServiceNavi`, `org.dsi.ifc.keypanel.DSIKeyPanel`, `de.audi.atip.mmicombi.IMMICombiScreenChangeManager`, [origin] `DSICarKombi`, `DSIGeneralVehicleStates`, `DSICarVehicleStates` | service registry via `FrameworkRef.getServiceHandle()` |
| 3.3 | **DSI listener subscription** | `IServiceManager.registerDSIListener(instance 0, listenerIfaceName, listener)` -> `ServiceRegistration`; then `dsi.setNotification(ATTR, listener)` / `clearNotification` (e.g. `DSIKeyPanel.ATTR_KEY2`) | service registry; listener bases generated from `org.dsi.ifc.*Listener` interfaces (`KombiListenerBase`, `GeneralVehicleStatesListenerBase`), not stock adapters. Note mib-keypanel found plain `ServiceTracker` on `DSIListener` injection failed; interposition needed the DSITracer proxy |
| 3.4 | **Event bus (HMI)** | `IContext.getEventBus().registerListener(...)` (stock usage inside `TerminalModeBapCombi`); `MsgDistributor` msg 107/108 (parking) consumed via shadowed `HighPriorityResourceTracker.processMsg` | captured context / shadowed listener |
| 3.5 | **Navigation app** | `de.audi.tghu.navi.app.Navigation.getInstance()` -> `getClusterService()`, `getRouteManager()` (`IRouteManager.getRoute()/stopRouteGuidance()`), `getDispatcher().execute(Runnable)` (NavigationJobs thread) | **static singleton** |
| 3.6 | **Cluster BAP (Kombi) - nav** | `CombiBAPServiceNavi` (all FctIDs 17-24,39,46,49,55 via `AppConnectorNavi`); `ClusterService.getKomoService()` (`KOMOService.updateDataRate/updateGfxState`), `ClusterViewMode`, `DSIResponseContainer.setRgActive()`, `updateRGIString`, `setRouteGuidanceAborted` | OSGi service (`RgdModule`) + singleton `Navigation` + **patched-in accessors** on `ClusterService`; stock writes blocked by a **delegate wrapper** `GatedCombiService implements CombiBAPServiceNavi` swapped into `CombiBAPListener.combiservice` on the NavigationJobs thread (`ScreenNavStatusGate`) |
| 3.7 | **Cluster BAP - VC events in** | Fct44 KDK visibility / Fct54 stage via `CombiBAPListener` subclass | shadowed `ScreenCombiBAPListener`/`AltScreenCombiBAPListener` calls out |
| 3.8 | **Cluster BAP - audio / cover art** | `AbstractCombiModule.getPictureManager()` -> `IPictureManager.registerPictureProvider(0, ...)`, `responseCoverArt(...)`, `setNotification(0)` | shadowed `AppConnectorTerminalMode` ctor; reflection on private `pictureProviders` map to mux |
| 3.9 | **Cluster BAP - phone** [origin] | `CombiBAPServicePhone` (Fct 14, 22/23/24...), `CombiBAPServicePhoneListener` | shadowed `ServiceManagerPhone.addingService` wraps with `GatedPhoneService` / `GatedPhoneListener` (delegate proxies) |
| 3.10 | **Display layers / contexts** | `HMIService.getDisplayManager()` -> `de.audi.atip.hmi.view.IDisplayManager.switchContext(ctx, terminal, null)`, `getCurrentContextID`, `setUpdateRate`; `de.audi.tghu.fwhmi.IDisplayManagerKombiControl.setOpacity/setCropping/setPosition` for displayables 98 (custom renderer), 101/102 (KDK backings), 33 (native map) | via `IFrameworkAccess.getHMIService()`; **new context table entries require the shadowed `DisplayManagerMIB2High.defineContexts()`** (dc[80]); displayable 103 not creatable; glasshud docs note this is the clean overlay pattern |
| 3.11 | **Screens / popups / partial popups** | `HMIService.showPartialPopup(0, id)` / `removePartialPopup`; `IScreenManager`, `Screen` (`setSmallStageType` 1->6 on CarPlay screen 3200000), `AbstractScreenWidget`; popup 62 status line via `PartialPopupManagerEvoHigh` | `getFrameworkAccess().getHMIService()`; behaviour changed by shadowing the popup manager |
| 3.12 | **Status bar / drawers** | `StatusBarStubController.updateStatusBar()`; `AudioDrawerContext` list model 538 (`ListModelApp`, `IntegerListCell`) | shadowed classes |
| 3.13 | **MMI menu rows, models, strings** [origin] | `AbstractHMIActivator(10,"Tone",skin,bank)`, `getScreenFactory()`; `ToneModelBank` (grow `models`/`modelIDs`, `createModel(i)`), `ChoiceModel` + `DefaultChoiceListener.itemSelected`; `ToneScreenFactory.createScreen(id, terminal)` -> insert `MenuItemController` (`CompositeRendererHigh`, `TYPE_COMBO_BOX`) into `MenuController`, keep `screen.setViews()` ids sorted (binary search); text ids 1001000.. filled into `lang_data.zip` by the installer | **shadowed OSGi `Activator`** substitutes subclassed bank/factory, falls back to stock on any Throwable (`UpmixToneUi`) |
| 3.14 | **State machines** [origin] | `AbstractSMMActivator.smmList`, `PhoneSMM`/`SystemSMM` subclasses intercept transitions (e.g. system transition 833) | shadowed `Activator`s |
| 3.15 | **HMI models (choice/list)** | `IHMIServiceApp.getChoiceModel(4095).setValue(...)` (SWITCH_TO_TERMINAL_MODE_CHOICE); `getVirtualButtonModel(...)` | `framework.getHmiServiceApp()` |
| 3.16 | **Input: steering wheel** | `DSIKeyPanel` ATTR_KEY2 raw keys (40 = left MFW roller press, 16 = centre DDS), kbd 4/8 | OSGi service + extra DSI listener (multi-listener supported) |
| 3.17 | **Input: touchpad / touchscreen -> CarPlay** | `CarplayDSILifecycleController.TerminalModeDSIKeyEventsController.updateKey/updateTouchEvents`; `dsiCarplaySafe.postButtonEvent(5..8, 0/1)`, `postTouchEvent` | shadowed controller; `TouchpadController.TouchSink` callback installed from inside it |
| 3.18 | **Voice / PTT** [origin] | `TerminalModeSDSHandler.isTerminalModeSpeechActive()`; `ICarplayDSIController.requestSIRIAction(SiriAction...)`; SDSAppHandler as `ISDSKeyInterceptor` service | shadowed handlers + service lookup |
| 3.19 | **Parking / PDC** | `IParkingSystem`, `DisplayContent`, `DSICarParkingSystem`, `AbstractParkingSystemControllerComponent.deactivateCurrentlyVisibleParkingSystems` | shadowed controller/handler |
| 3.20 | **Persistence** [origin] | `IStorageAccess` namespace/key (Sound app ns 1009, key 1000); `de.audi.tghu.storage.FlushValuesDict` flush list | framework; **shared class** convention (every mod ships the same `FlushValuesDict` with `register()`; first copy on the classpath serves all; "luka-collection.jar loads before carplay_hook.jar") |
| 3.21 | **Vehicle data** [origin] | `DSICarKombi` (BC range/outside temp), `DSIGeneralVehicleStates` (reverse gear, standstill, tank), `DSICarVehicleStates` (DynamicVehicleInfoHighFrequent speed) | OSGi + DSI listener (`VehicleDataModule.Binding`) |
| 3.22 | **Audio context** | `IContext.getAudioManager().addAudioContextListener(...)` (stock, in `TerminalModeBapCombi`); `AudioDrawerContext`; amplifier type via stock `AmplifierListener` | captured context |
| 3.23 | **Reflection (last resort)** | `CoverArtProviderMux`: `getDeclaredField("pictureProviders").setAccessible(true)`; historical `KombiInjector` replaced the private `DSIKombiSyncProxy` field of the live `DSIKombiSync` provider with `FilteringKombiProxy extends DSIKombiSyncProxy` | used when the target lives in an OSGi bundle (boot shadow can't reach) or to avoid replacing a whole class |
| 3.24 | **Native side IPC** | localhost TCP only (`:19810` hook bus, `:19800` renderer, `:19832` altscreen). J9/FP1.1 has no AF_UNIX (mib-keypanel design note); glasshud uses AF_UNIX `/var/tmp/glasshud.sock`, so Java would need a TCP shim | - |

Other repos' relation to Java:
- **mib2q-hmi-lab**: `tests/test-jar-inject.sh` only checks (in QEMU staging, never on the car) that a
  jar dropped into the staged `lsd/jars` appears in the generated boot classpath; J9 never boots in QEMU
  (`JVMEXEX015E`), so there is no emulated Java loop yet. `scripts/unit-deploy.sh`: copy to /tmp,
  `mount -uw`, `.bak-<stamp>` backup, copy, `sync`, `mount -ur`, optional shutdown. Logging channels via
  `-DSLOG=<channel>=<level>` in `lsd.sh`; slog ring wraps ~2 min (`scripts/catch-boot.sh`); cores in
  `/mnt/ota/system/core`; HMI error dumps `/mnt/ota/system/logs/error_*.zip`.
- **mib-keypanel**: no Java; drives Audi's DSITracer `KeyPanelTelnetService` on TCP 1796 after setting
  `dsitracer_activated` + `traceConfig.properties` (`activate.extension=ctrl,DSIKeyPanelEx`). Abandoned an
  OSGi bridge bundle on `:19820`. Shows a non-injection seam: the stock DSITracer extension mechanism.
- **mibpkg**: real package manager (Rust CLI + supervisor + web/GEM UI). TOML manifest
  (`[package]`, `[compat] units/firmware`, `[[file]]`, `[[config]]`, `[[service]]` with `after=["hmi-ready"...]`,
  `[scripts] post_install/pre_remove`, `[[patch]]` reserved). Installs under
  `/mnt/app/root/mibpkg/pkgs/<n>/<v>` with atomic `current` flip and rollback; one boot hook line in
  `/mnt/system/etc/boot/startup.sh`. No declarative jar type yet (`docs/ideas.md:102` lists "lsd jars"
  in a stock-file edit registry idea); `src/gempatch.rs` already binary-patches the stock GEM jar with
  sha1 pre/post checks and once-only backups. Viable SDK distribution channel once it gains a `[[jar]]`
  type + `[compat] firmware` enforcement + "reboot required".
- **mib2q-glasshud**: native Rust/egui on QNX Screen; AF_UNIX bincode protocol with provider/client roles
  (`Hello`, `RegisterScreen`, `Frame`, `Show/Hide`, `Input`). No Java today; its docs say a clean overlay
  needs the `DisplayManagerMIB2High` context patch pattern.
- **mib2q-carmqtt**: native; ExLAP `127.0.0.1:25010` -> MQTT; design reserves an `HmiBridgeSource`
  = "a Java listener injected into lsd via the class-override workflow, pushing JSON lines over a
  localhost socket". Persistence store reachable natively with `pc s:<ns>:<key>`.

---

## 4. Pain points and hazards

1. **Boot loop on mismatched firmware** (issues #24, #50): whole-class copies are train-specific; a
   wrong jar keeps the HMI on the logo, HMI supervision resets MMX repeatedly. Only recovery is SD/RCC
   paths (no GEM). Mitigations now: E2P train check, per-region jars, strict CN patch set, linkage audit.
2. **Link errors in static init kill whole stock bundles** (2026-10-10 NoClassDefFoundError via a
   bundle-private adapter class): the offline audit can't know which jar/classloader holds a stock
   class on the unit.
3. **Boot shadowing doesn't reach OSGi bundle classes** (DSIKombiSyncProvider) -> reflection field swap.
4. **Classpath order between jars is undefined**; two mods shipping the same class = first wins
   silently (FlushValuesDict, M.I.B. NavActiveIgnore replacing `org.dsi.ifc.carplay.AppState`,
   AppleDouble `._*.jar`). No conflict detection anywhere except installer special cases.
5. **No hot reload; deploy = reboot**; killing j9 = ~50 s system restart (disputed ~10-15 s respawn in
   mib-keypanel). Standing user directive: never reboot/kill j9 without asking each time.
6. **Stock-thread I/O is lethal**: a diagnostic log line in `CombiMapController.processModelUpdateEvent`
   (fires many times/s) blanked both cluster displays (memory `no-io-on-stock-hmi-thread.md`). Rule:
   stock callbacks flip volatiles and return; logging edge-triggered or off-thread.
7. **Deadlocks**: ABBA between CarPlayApp state lock and `ScreenNavStatusGate` monitor on cold boot;
   `CarplayBus.on()` blocking on `dispatchLock`. j9 has no watchdog for these - the HMI just hangs.
8. **Startup ordering**: Navigation/CombiBAP services publish late on cold boot -> unbounded
   retry-until-ready; TMDevice state bounces on replug -> debounce; `IMMICombiScreenChangeManager`
   absent on A5 -> make optional deps optional.
9. **Two writers to a stock resource** (cluster context) gave a black cluster / 30 s hang -> single
   serialized writer + blocking stock writes in shadowed `switchContext`.
10. **Clock steps** early in boot trip `NavInitializationChecker` -> nav domain restart (any plugin
    touching time must wait ~5 min uptime).
11. **Decompiler poison**: byte-reversed constants, `"isNativeLittleEndian"` == `""` inlined, missing
    InnerClasses; 1.5 APIs that compile at `-target 1.4` but fail at runtime.
12. **Nested-class/bridge drift**: replacing an outer class without all nested classes calls vanished
    `access$NNN` bridges.
13. **Matched halves**: jar + native hook + renderer versions must deploy together (protocol skew).
14. **Logging**: file logger in `/tmp` (ramdisk, wiped on reboot), WARN by default, verbose marker;
    `LOG_INFO` invisible in production cost car sessions; slog ring wraps in ~2 min; crash evidence in
    `/mnt/ota/system/logs/error_*.zip`, `sm_resetreason.txt`, `/mnt/ota/system/core`. No debugger:
    JDWP is commented out in `lsd.sh`; no QNX gdb/pdebug on the unit (memory: `corewalk.py`).
15. **Installer environment**: bare QNX shell (no scp/tar/cksum/base64, `find` exits 1 on Mac FAT cards,
    `printf` doesn't reuse formats), read-only mounts, forced reboot right after copy can truncate files
    (sync + wait 20 s).
16. **Upstream divergence**: local carplay-rgi checkout is 32 commits behind origin/main.

---

## 5. Implications for an SDK (notes, not a design)

- Keep exactly **one** loader jar that shadows a minimal set of stock classes (ideally one
  `ITerminalModeComponent`/Activator-level bootstrap plus per-seam hook points), firmware-variant built
  and audited like carplay-rgi; load plugins from a separate directory with a child classloader so
  plugin jars never sit on the boot classpath and never collide. (J9 FP1.1 has `URLClassLoader`? - must be
  verified against `jcl.jar`.) Optionally guarantee loader precedence with `-Xbootclasspath/p:`.
- Hand plugins a `FrameworkRef`-like capability object plus typed facades over the seams in section 3;
  enforce the threading rule (callbacks on an SDK executor, never on stock threads).
- Generalize `Module` (start-returns-false retry, idempotent stop, ordered deps) and `CarPlayApp`'s
  generation/debounce machinery into a host lifecycle with HMI-boot, CarPlay-session, and per-service
  scopes.
- Provide shared-class arbitration (the `FlushValuesDict.register()` pattern) as SDK services instead.
- Distribution: mibpkg with a `[[jar]]`/plugin type, `[compat] firmware` enforcement, reboot-required
  flag; or M.I.B. card with the carplay-rgi installer conventions.

## Key paths

- `~/complex-projects/mib2q-carplay-rgi/java_patch/com/luka/carplay/core/{CarPlayApp,Module,FrameworkRef,ScreenModule,RgdModule,SteeringWheelInputModule,ScreenNavStatusGate}.java`
- `~/complex-projects/mib2q-carplay-rgi/java_patch/com/luka/carplay/bus/CarplayBus.java`, `framework/Log.java`
- `~/complex-projects/mib2q-carplay-rgi/java_patch/de/audi/app/terminalmode/combi/TerminalModeBapCombi.java` (bootstrap)
- `~/complex-projects/mib2q-carplay-rgi/scripts/build_java.sh`, `scripts/audit_java_stock.sh`, `tests/JavaStockLinkageAudit.java`, `tests/audit_java_sources.py`
- `~/complex-projects/mib2q-carplay-rgi/docs/deploy/install.md`, `docs/architecture.md`, `docs/deploy/session-lifecycle.md`, `docs/hmi/pdc-small-stage.md`
- origin/main only: `docs/LANDMINES.md`, `docs/build-and-deploy/{BUILD_SYSTEM,DEPLOY_AND_RELEASE,ISSUE24_RCC_MMX_RECOVERY,PRODUCTION_AUDITS}.md`, `java_patch_cn/`, `java_stock/`, `java_patch/de/audi/tghu/{phone/sm,system/sm,tone/hmi}/Activator.java`, `java_patch/com/luka/carplay/tone/UpmixToneUi.java`, `java_patch/de/audi/tghu/storage/FlushValuesDict.java`, `install_MoreIncredibleBash/mod/custom.sh`
- `~/complex-projects/mib2q-hmi-lab/tests/test-jar-inject.sh`, `scripts/stage.sh`, `scripts/mkimage.sh`, `scripts/unit-deploy.sh`
- `~/complex-projects/mibpkg/src/manifest.rs`, `src/gempatch.rs`, `docs/ideas.md`
- `~/projects/audi-mib2/patching/` (jxe2jar pipeline, `install_NavActiveIgnore_jar.sh`)
- Prior Claude memory for the carplay-rgi project
