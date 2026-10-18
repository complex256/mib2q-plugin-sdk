# MHI2Q onboard Java — map

Scope: the Java software on the Audi MIB2 High (MHI2Q) MMX, HMI build
`HMI_AU_MIB2_HQC_NAR_G22_C10_180803`, variant `FMQ-P-TNSL-US-AU-MLE`.

Sources: `~/projects/audi-mib2` (the same unit — its `lsd.jxe` / `AppDevelopment.jar`
are byte-identical to the MU1316 backup), `~/projects/audi-mib-backup/backup/MU1316-*`,
`mib2q-hmi-lab`, `mib2q-carplay-rgi` (`origin/main`), `mib-keypanel`, `mibpkg`.
Stock files are cited relative to `audi-mib2/firmware/mmxfs/app/eso/hmi/` unless noted.

Detail lives in the appendices:
- [appendix-a-inventory.md](appendix-a-inventory.md): every artifact, package sizes, subsystem tables, central classes
- [appendix-b-injection.md](appendix-b-injection.md): how carplay-rgi injects today, the seams it uses, hazards

> **Privacy:** the stock `lsd.sh` command line embeds the car's VIN and IMSI (`lsd.sh:302-303`).
> Never paste a live `ps`/`pidin` of j9 into docs or issues.

---

## 1. Runtime

| | |
|---|---|
| VM | IBM J9 2.3, QNX 6.5 ARMv7 build 20110831 (`mib2q-hmi-lab/README.md:79`) |
| Class library | CDC **Foundation Profile 1.1** (`-jcl:foun11`), which is a Java 1.4 API. No generics, `StringBuilder`, `java.util.concurrent` or annotations at runtime. |
| Binaries | `/ifs/jre/bin` (`j9`, `libj9vm23.so`, `libjclfoun11_23.so`, `libj9mjit23.so`, …), inside the `ifs_hmi` IFS |
| Image | `lsd.jxe`, a jar2jxe 2.3 ROM-class image (it also contains the JCLs). Main class `de.dreisoft.lsd.LSD`. |
| Execution | micro-JIT (`-Xmjit:code=2000,singleCache -Xquickstart`); no AOT found |
| Heap | `-Xmn10m -Xmo50m -Xmx60m`, gencon GC, `-noverify` (`lsd.sh:45-65`). **The whole HMI runs in a 60 MB heap.** |
| Security | No SecurityManager installed. |
| Processes | One HMI JVM. The other Java launch is a ULP-mode launcher (`startup.sh:1355-1369`). |

### Launch chain

`fastboot.sh` → `start_qcore.sh:433` → `/etc/boot/boot.sh:66` → `startup.sh`. The run mode is read from
`/dev/ooc/startup` (`startup.sh:1919`). Normal mode runs `start_hmi` → `runHMI.sh`, which sources `lsd/lsd.sh`.
That ends in:

```
on -p 12 /ifs/jre/bin/j9 $VMOPTIONS -Xbootclasspath:$BOOTCLASSPATH:$LSD_JXE -jxe:$LSD_JXE     (lsd.sh:434)
```

`servicemgr` lists the HMI as process 200 with `exec:null` (`framework.json:219`), so it does not launch it.
It does supervise it: its watchdog runs `slay -s11 j9` (`dsistartup.json:18`). What respawns j9 is still open:
the carplay notes describe a full reboot of about 50 s, while the keypanel notes describe a 10–15 s respawn.

### Bootclasspath assembly (`lsd/lsd.sh:361-434`, checked by hand)

Entries in order, all of them **ahead of** `lsd.jxe`:
1. `lsd/hmi.zip`, only in developer mode (`$DEV_ACTIVATED`) (`:375-378`)
2. `lsd/DSITracer.jar`, if `lsd/dsitracer_activated` exists (`:385-388`)
3. **every `*.jar` / `*.zip` under `lsd/jars/`**, in raw `find` order (`:392-396`)
4. `lsd.jxe`. Developer mode swaps in `lsd/lsd.jxe` from the app partition (`:381-383`).

The app partition is writable (M.I.B. / SD `custom.sh`), so dropping a jar into `lsd/jars/` makes any class
override the stock one with nothing reflashed. That is the injection mechanism used today. `find` order is
undefined, so the first copy of a duplicated class wins silently.

### Bundle-list selection (`lsd/lsd.sh:260-270`) — a non-shadowing entry point

`-Dlsd.bundles=` chooses which on-disk properties file defines the bundles:
- `RUN_MODE=eso_screening` → `bundles_headless.properties`
- `lsd/dsitracer_activated` + `DSITracer.jar` + `bundles_dsitracer_enabled.properties` → **that file**
- developer mode → `bundles.properties`
- otherwise → the copy embedded in the jxe

The DSITracer variant differs from stock in exactly two lines:
```
de.audi.tghu.serviceDelegateConfigurator=de.audi.tghu.dsitrace.TraceConfigurator
Bundle.Startup.Autostart = DSITracer,FwUtilServices,Profiling,Tracing,ATIPImpl
```
So the stock firmware already supports **(a)** adding an autostart bundle activator by name, from a
writable file, and **(b)** installing a `IServiceDelegateConfigurator` that wraps *every* DSI service
proxy. Both work with **zero shadowed stock classes**. The bench already turns on the DSITracer path
for mib-keypanel (TCP 1796), so any SDK loader must chain to `TraceConfigurator` rather than replace it.

---

## 2. What the Java software is

About **35k classes**: 30,493 in `lsd.jxe`, about 1,550 in `lsd/jars/*`, and about 2,950 in the ARC/online
plugin bundles. Kanzi 2.5.2 does the rendering.

### Layers (bottom → top)

| Layer | What it is | Key classes / packages |
|---|---|---|
| **LSD kernel** | 3SOFT's own small OSGi-API implementation, *not* Equinox. One flat classpath; a "bundle" is just an Activator class named in `bundles.properties`. | `de.dreisoft.lsd.LSD`, `BundleRegistry` |
| **Startup orchestration** | Brings domains up in phases, driven by `hmi_startup.json` (embedded in the jxe) | `StartupManager`, `AppStateManager`, `DomainHandler` |
| **e.solutions util / comm** | Thread pools and named dispatchers (`fwservices.json`), plus the IPC transport | `de.esolutions.fw.util.*`, `de.esolutions.fw.comm.*` |
| **DSI service layer** | 99 generated DSI interfaces (`org.dsi.ifc.*`) with generated proxies (2,214 classes). On the MMX they are reached as files under `/dev/eso`; between nodes, over TCP/SSL. | `JDSIManager.startDSIService`, `de.esolutions.fw.dsi.AdapterActivator`, `DSIAdmin`, `ServiceDelegator` |
| **ASI** | A second family of generated interfaces (1,672 classes): rear-seat/HMI-sync, diagnosis, FEC, navigation | `de.esolutions.fw.comm.asi.*` |
| **Audi framework core** | The hub every bundle goes through | `IFrameworkAccess`/`FrameworkAccess` (an OSGi service), `FwServices`, `IServiceManager`, `IStorageAccess` |
| **HMI framework** | Generated, integer-table **state machines** (23 `*SMM`s); generated **screen factories** that build e.solutions widget trees; one `HMIEvent` dispatcher thread; the EAL Java layer → `ealswig` JNI (about 1,200 natives) → Kanzi | `FwHMI`, `HMIRegistry`, `AbstractSMMActivator`, `AbstractHMIActivator`, `<F>ScreenFactory`, `ScreenBag`, `ConditionBank`, `de.esolutions.hmi.widgets.*` |
| **Feature modules** | One App / SMM / HMI trio per feature, joined by `ActionProxy` (SMM→app) and `*ModelApp`→`*ModelGUI` data models | see below |
| **ARC plugin runtime** | A second tier with its own class loader for online/OTA bundles | `arc.launcher.BundleLoader` |

### Feature modules

System · Media · Tuner (radio, SDARS) · TV · **Navi** (biggest, ~3.5k classes) · Phone · Messaging ·
AddressBook · **Car** (vehicle setup *and* climate) · EarlyApps (parking/reverse camera) · Tone ·
**TerminalMode** (CarPlay, Android Auto, CarLife) · Online · Speech · Settings · Connectivity · SWDL ·
eCall · Engineering (red menu / REM) · TestSupport. The green menu (GEM) is `AppDevelopment.jar`,
which interprets `.esd` script screens. `DSITracer.jar` (`de.audi.tghu.dsitrace`) is the DSI tracer
plus the KeyPanel telnet emulator.

### Cross-cutting flows

- **Input:** native key panel → `de.audi.kbd.dsi.KbdListener` → `FwHMI.fire*Event` → `KeyEventDistributorEvo` → the focused widget or an SMM event
- **Display:** `DisplayManagerMIB2High` + `DSIDisplayManagerControllerImpl` drive the native DisplayManager; one `HMITerminalImplMIB2High` per screen; contexts are defined in `DisplayManagerMIB2High.defineContexts`
- **Cluster:** `de.vw.mib.bap` + `de.audi.app.bap`, e.g. `CombiBAPServiceNavi`, `CombiBAPListener`, `DSICarKombi`
- **Persistence:** `IStorageAccess` over DSI persistence
- **Native (JNI):** `ealswig`, `jni_fw_util_os`, `styledbservice`, `jprof`

### Corrections to `audi-mib2/ARCHITECTURE.md`

It says Equinox (wrong: LSD is 3SOFT's own kernel). It expands LSD as "Look-and-Sound Display" (wrong).
It says every screen is a `.kzb` (wrong: the 63 kzbs are 3D/template assets; screens are generated Java).
And `audi-cgw.jar` is not on the head unit.

---

## 3. How we inject today (carplay-rgi)

- **Mechanism:** whole-class copies of decompiled stock classes (from `jxe2jar`, then CFR/Vineflower), edited in place, shipped in a jar under `lsd/jars/`. No bytecode weaving, no runtime proxies. Two audit scripts check that no stock member goes missing.
- **Build:** JDK 8 `javac -source 1.4 -target 1.4` against jars produced by `jxe2jar`; one jar per firmware family.
- **Deploy:** an M.I.B. SD `custom.sh` with atomic copies, `.carplay-stock` backups and a restoring uninstall. It checks the firmware version first. Activation needs a reboot.
- **Proto-framework:** `TerminalModeBapCombi.init()` (overridden) → `CarPlayApp.startTransport(IContext)`. The `Module` contract is `start(FrameworkRef)`, where `false` means retry, plus an idempotent `stop()`. Modules sit in a hardcoded `MODULES[]` list with one worker thread, retry/backoff and disconnect debounce. `FrameworkRef` gives service lookup and release. `CarplayBus` is TCP :19810, `RendererServer` :19800, and logs go to `/tmp/carplay_java.log`.

### Seams used (stock class → how we get it)

| Seam | Stock API | Obtained by |
|---|---|---|
| Framework / context | `IContext`, `IFrameworkAccess` | captured in a shadowed constructor |
| Services / DSI | `IServiceManager.getServiceReferences`, `registerDSIListener` (`DSIKeyPanel`, `DSICarKombi`, vehicle states…) | service registry |
| Navigation | `Navigation.getInstance()` → `getRouteManager`, `getClusterService`, `getDispatcher` | static singleton |
| Cluster BAP | `CombiBAPServiceNavi`, `CombiBAPListener.combiservice` | wrapper swapped into a field; listener shadowed |
| Display layers | `HMIService.getDisplayManager()`, `IDisplayManagerKombiControl` | service; new contexts need a shadowed `DisplayManagerMIB2High` |
| Popups / status bar | `showPartialPopup`, `PartialPopupManagerEvoHigh`, `StatusBarStubController` | shadowed classes |
| Input | `DSIKeyPanel` (steering-wheel key 40), `dsiCarplaySafe.postButtonEvent` | DSI listener |
| Menus / SMMs / models | `ToneModelBank`, `ToneScreenFactory`, `PhoneSMM`, `SystemSMM`, `getChoiceModel` | shadowed OSGi Activators substitute subclasses |
| Persistence | `IStorageAccess`, `FlushValuesDict.register()` | service, plus a helper every mod copies |
| Last resort | cover-art provider map, `DSIKombiSync` proxy | reflection |

### Hazards (each one a requirement for the SDK)

1. **Boot loop on the wrong firmware** (#24, #50): a copied class doesn't match that firmware's stock code → the HMI never finishes booting → supervision resets the unit.
2. **Link errors in a static initializer take down whole bundles** (on the car, 2026-10-10: TerminalMode and Navi).
3. Boot-classpath overrides can't see classes that live in OSGi/ARC bundle loaders.
4. **Undefined jar order and silent class collisions** (M.I.B.'s `NavActiveIgnore.jar`, the shared `FlushValuesDict`, macOS `._*.jar`).
5. **No I/O or blocking on stock HMI threads.** One log line once blanked both cluster displays.
6. Deadlocks (lock order, bus registration), with no hang detection.
7. Late services and connect/disconnect bounce need retries and debounce.
8. `jxe2jar` output is unreliable: byte-swapped constants, missing InnerClasses, and Java 1.5 APIs that compile but fail on the car.
9. No debugger or emulator: JDWP is commented out (`lsd.sh:250`), and J9 doesn't boot under QEMU.

### Live risk on this unit

`lsd/jars/NavActiveIgnore.jar` is a VW-targeted M.I.B. patch. Five of its classes target packages that don't
exist here, so they do nothing. But it **also replaces stock `DSICarplayProxy`, `DSIAndroidAutoDispatcher`
and `DSIAndroidAuto2Dispatcher`**. Diff those against stock before blaming our code for CarPlay or Android Auto oddities.

---

## 4. Open questions

- What respawns j9 after the watchdog `slay -s11`, and how long it takes (a full reboot vs a 10–15 s respawn).
- Which native server provides `/dev/eso/hmi`; which DSI services are local to the MMX and which are bridged to the RCC.
- Whether the empty first bootclasspath entry acts as the current directory.
- Whether developer mode (`$DEV_ACTIVATED`) works, given `lsd/jre` only contains `j9`.
- Where the model/screen/SMM-event ID→name tables are, and which tool generated the SMM tables and screens.
- Whether RemoteHMI is really loaded through the ARC loader.
- Whether `fw_util_*.jar` in `lsd/jars` is newer than the copy in the jxe.
