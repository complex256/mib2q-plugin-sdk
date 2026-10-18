# MHI2Q Java functional map — inventory, subsystems, seams

Scope: the Java software on the Audi MIB2 High Qualcomm ("MHI2Q", MU1316, train `MHI2Q_US_AUG22_P5087`,
variant `FMQ-P-TNSL-US-AU-MLE`). Paths below are relative to `~/projects/audi-mib2/` unless noted.
Decompiled LSD source: `patching/lsd_java/` (CFR 0.152 over `patching/lsd.jar`, produced by `jxe2jar`).

## 0. Provenance: is audi-mib2 the same unit/train as the MHI2Q backup?

**Yes, for the Java.** Evidence:
- `MU1316-lsd.jxe` (backup) = `re/lsd.jxe` = `patching/lsd.jxe` = `~/projects/audi/lsd.jxe`, all MD5 `d9bd61764e8bf04a3843c1fc47ea0269`.
- `MU1316-AppDevelopment.jar` = `re/jars/AppDevelopment.jar` = `re/jars/GEM.jar`, MD5 `99e07f43...`.
- Backup `MU1316-version.txt` Variant `FMQ-P-TNSL-US-AU-MLE` matches ARCHITECTURE.md §3.9. `allversions.txt`: `J9:DSI: 2_69`, `HMI: HMI_AU_MIB2_HQC_NAR_G22_C10_180803`, same as the jxe's embedded `resources/version.properties`.
- The `~/projects/audi` root has the same `jars/` listing and `hmi/lsd/` tree as `re/`; it is the older copy.

Differences that do exist:
- The backup's live `/mnt/app/eso/hmi/lsd/jars/` contains **`NavActiveIgnore.jar` (dated 2022-02-05)**, a community patch. The extracted `firmware/mmxfs/app` tree does not have it. See §6.
- `ARCHITECTURE.md` has some errors about the Java side. Each one below was checked against the code:
  - The OSGi framework is **not Equinox**. It is 3SOFT/Elektrobit's own **`de.dreisoft.lsd`**. "LSD" is that framework's name; it does not stand for "Look-and-Sound Display".
  - Most screens are **not** in `.kzb` files. Only 63 kzbs ship, for 3D or animated elements such as car models, ARA/parking, compass, glassplate, speller, rotary and statusbar. Regular screens are generated Java widget trees (§3.3).
  - The tier packages are `de.audi.app.*` (App), `de.audi.tghu.<f>.sm` (SM) and `de.audi.tghu.<f>.hmi` (HMI). Those names are correct. The VW-style `de.vw.mib.asl.*` that the patching README uses is **not present** here; only `de.vw.mib.asl.api.bap*` (7 classes) and `de.vw.mib.bap` exist.
  - The **CGW (`re/gateway/`, ESR Labs Mindroid `audi-cgw.jar` v8.2.19) is not on the MMX app partition.** No `audi-cgw.jar` exists under `firmware/mmxfs/app`. Its paths (`/app/atop/audi-cgw/...`) suggest it runs on a different ECU/process image, probably the online/telematics unit. Treat it as out of scope for "Java on the head unit" until confirmed.

## 1. Inventory of Java artifacts on the unit

### 1.1 Runtime and launch
- JVM: IBM J9 (`/ifs/jre/bin/j9`, from `ifs_hmi.img`). `J9_Version: 1.16+ObjectSwapper_1.02+XIPLoader_1.10+MCFObjectReader_1.01C+JavaCrypto_1.06+Socks5Support_1.00`.
- Launcher: `/mnt/app/eso/hmi/lsd/lsd.sh` runs `j9 ... -Xbootclasspath:<jars>:lsd.jxe -jxe:/ifs/lsd.jxe`, with heap `-Xmn10m -Xmo50m -Xmx60m`, gencon GC and `-Xmjit`.
  - Every `*.jar`/`*.zip` in `/mnt/app/eso/hmi/lsd/jars/` goes on the bootclasspath **ahead of lsd.jxe**, unconditionally. This is why overrides placed there win.
  - When `lsd/dsitracer_activated` exists, `DSITracer.jar` is added and `-Dlsd.bundles=bundles_dsitracer_enabled.properties` is set.
  - When `lsd/development_activated` exists, dev mode runs `de.dreisoft.lsd.LSD` from `hmi.zip`, or from `lsd/lsd.jxe` if present.
  - `RUN_MODE=eso_screening` selects `bundles_headless.properties`.
- `lsd.jxe` header `META-INF/JXE.MF`: `builderName jar2jxe 2.3`, `containsJCLs 1`, `startupClass de.dreisoft.lsd.LSD`. 60.6 MB, of which `rom.classes` is 60.0 MB.
- Resources embedded in the jxe (uncompressed zip entries; `jxe2jar` drops them; recoverable by scanning `PK\3\4` headers):
  - `resources/bundles.properties`: byte-identical to the on-disk `re/hmi/lsd/bundles.properties`. The default autostart is `FwUtilServices,Profiling,Tracing,ATIPImpl`.
  - `resources/hmi_startup.json`: byte-identical to `hmi_startup.json.example`.
  - `resources/fwservices.json`: thread pools and dispatchers.
  - `resources/atip.properties`, `resources/version.properties`, `resources/TTVersion.properties`, `java/lang/jxeLink.rules`, `lib/armle/libjprof.so`.
- `version.properties`:
  - HMI `HMI_AU_MIB2_HQC_NAR_G22_C10_180803`; Framework `5.51.6 MIB2MAIN I202 CI70 QC`.
  - DSI IFC `mib-dsiifc-DSI-17.13.0.E20170328-b2_69`.
  - **Kanzi `2_5_2_286_r82961_2015-11-24`**, **EAL `1.6.7`**, GEM `4.14`.
  - `variant.skin/widgets: EvoHigh`; build tag `jenkins-RB_Evo_Strauss_QC_AllVariants-71`.

### 1.2 lsd.jxe: 30,493 classes (no resources in the jar form)

| Top-level | Classes | Contents |
|---|---:|---|
| `de.audi` | 20,363 | tghu 9,780 · app 6,575 · atip 2,072 · tuner 927 · tv 553 · remotehmi 177 · audio 116 · tone 115 · kbd 27 · swdiagnosis 14 · mib 7 |
| `de.esolutions` | 6,207 | fw.comm 4,226 (comm.dsi 2,214 · comm.asi 1,672 · agent 177 · core 70 · persistence 64 · comm 27) · hmi.widgets 1,022 (audi.evo 854, audi.base 168) · fw.util 490 · fw.dsi 342 · graphics.eal 122 (+ipl 2) |
| `org.dsi` | 1,270 | `org.dsi.ifc.*`: **99 DSI interface packages** (DTOs, `DSIxxx` and `DSIxxxListener` interfaces) |
| `java.*` / `javax.*` / `com.ibm` / `com.microdoc` | 675 / 21 / 339 / 13 | J9 class library (JCL) bundled in the jxe; javax.microedition (CDC/Foundation) |
| `de.vw` | 658 | `de.vw.mib.bap` 651 (BAP protocol stack; 569 generated) · `de.vw.mib.asl.api.bap*` 7 |
| `org.apache` | 475 | xerces 261 · commons.jexl 92 · commons.scxml 79 · commons.id 40 · commons.logging 3 |
| `edu.emory` | 239 | backport-util-concurrent |
| `de.eso` | 120 | vcalendar 31, widgets 30, vcard 27, remotehmi 11, IconExtractor |
| `de.dreisoft` | 59 | **`de.dreisoft.lsd`: the OSGi framework** (LSD, BundleRegistry, ServiceRegistry, ServiceFilter, ServiceDelegator, LSDWatchdog) |
| `org.osgi` / `org.json` / `org.elektrobit` | 25 / 12 / 12 | OSGi R3/R4 API subset, json-simple (2 copies) |
| `arc` | 5 | `arc.launcher.BundleLoader`: bootstraps the ARC plugin runtime (§1.4) |

About 13.5k of the 30.5k classes (44%) are inner or anonymous classes. About 6.0k sit in generated `*/hmi/evohigh/` packages, and 5,599 of those are `*ConditionBank$N` condition closures.

Second-level detail:
- `de.audi.tghu.*` (integration tier: SM + HMI + some app logic): navi 3,460 (app 2,235, hmi 1,216) · online 886 · car 856 · phone 575 · tuner 462 · media 449 · system 392 · earlyapps 304 · swdl 275 · messaging 271 · tone 248 · exlap 240 · connectivity 228 · addressbook 168 · tv 156 · info 156 · settings 79 · hmi 65 · fwhmi 58 · picturestore 56 · engineering 43 · smi 33 · power 31 · ecall 28 · terminalmode 27 · tts 26 · iconrenderer 23 · redengineering 19 · jdsi 13 · browser 13 · storage 11 · wirelesscharging 11 · dsi 6 · …
- `de.audi.app.*` (App tier): phone 890 · media 863 · navi 718 · terminalmode 671 · messaging 662 · sdsmanager 557 · car 525 · bap 268 · addressbook 215 · combi 209 · earlyfunc 171 · online 151 · ecall 110 · bluetooth 108 · data 74 · tuner 60 · wlan 58 · settings 47 · connectivity 45 · system 41 · eni 28 · wirelesscharging 27 · info 20 · testsupport 17 · onlinefunctions 13 · swdl 11 · obex 10 · sds 6
- `de.audi.atip.*` (framework, "ATIP"): interapp 567 · hmi 545 · utils 256 · statemachine 116 · model 76 · startup 60 · util 34 · sysapp 30 · metrics 30 · mmicombi 28 · benchmark 28 · base 27 · error 19 · odp 17 · sdis 15 · log 15 · … · displaymanager 11 · keyhandling 2
- `org.dsi.ifc` (99): admin albumbrowser androidauto androidauto2 asiainput asiatrafficinfomenu audio bap base bluetooth boot browser calendar caraircondition carauxheatercooler carcomfort cardriverassistance cardrivingcharacteristics careco carhybrid carkombi carlife carlight carparkingsystem carplay carseat carstopwatch cartimeunitslanguage carvehiclestates connectedradio ddp20 diagnose displaycontroller displaymanagement exboxm exlap filebrowser generalvehiclestates global has iconhandling infotainmentrecorder internal keypanel kombifastlist kombipictureserver kombisync kombisync2 komogfxstreamsink komonavinfo komoview map media messaging mirrorlink mobilityhorizon modelapi navfleetservices navigation navservicesapi networking online organizer persistence personalization phonetic picturehandling picturestore powermanagement predictivenavigation radio radiodata sdars search sfa smartphoneintegration speechrec sse startup swap swdldeviceinfo swdllogging swdlprogress swdlselection system telephone telephoneng tmc tollcollect tpegservices trafficregulation travelguide travellink tts tvtuner uota upnp waveplayer wirelesscharging
- `de.esolutions.fw.comm.asi.*` (ASI, generated): hmisync 599 · diagnosis 483 · navigation 140 · connectivity 96 · fec 61 · explorer 44 · online 42 · speech 38 · calendar 32 · persistence 26 · organizer 22 · ooc 22 · imageserver 22 · sdis 18 · sdisConfigProvider 15 · onlineservices 12

### 1.3 Boot-classpath jars in `/mnt/app/eso/hmi/lsd/jars/` (and `lsd/DSITracer.jar`)

| Jar | Classes | What it is |
|---|---:|---|
| `AppDevelopment.jar` (= GEM.jar) | 189 | **GEM, the Green Engineering Menu** v4.14 (`Component-Title: Green Engineering Menu`, build gem-201611140803). `de.audi.gem.*` parses `.esd` screen scripts from `/mnt/app/eso/hmi/engdefs/` (151 files) and renders them (`parser/ESDFileParser`, `controller/*Controller`, `model/*`, `access/EngineeringInfoService`, `PersistenceAccessor`, `doas/` script calls, `util/exec`). |
| `DevelopmentStubEvo.jar` | 18 | GEM host bundles: `de.audi.tghu.development.Activator` (AppDevelopment), `development.sm.SMActivator`, `hmi.evohigh.HMIActivator/DevelopmentScreenFactory`, and an `de.audi.gem.stub.EALGEMRenderer` that renders GEM through EAL. |
| `diag.jar` | 311 | `de.mib.swdiagnosis.*` (FwSwDiag bundle): software-diagnosis screens and widgets (car, earlyfunc, evo-high widgets). |
| `RemoteHMI-Libs-Ext.jar` | 172 | XML stack for RemoteHMI (`com.bluecast.xml` Piccolo, `org.w3c.dom`, `org.xml.sax`). |
| `fw_util_{commons,config,os,serializer,services,tracing,transport}.jar` | 98/54/1/45/10/209/85 | e.solutions fw utilities. **Each class also exists in lsd.jxe**; the jar copies shadow them. Probably a field-update channel. |
| `xEventRecorder.jar`, `xEventRecorderEvo.jar` | 21+3 | `de.audi.tghu.recorder`: records HMI events. |
| `xTcpKeyPanel.jar` | 12 | `de.audi.tghu.sim.tcpkeypanel`: TCP key injection on port 2225. It disables itself in production. |
| `xTcpLog.jar` | 22 | `de.audi.tghu.log.tcp`: TCP log sink. |
| `DSITracer.jar` (lsd/, only with the flag) | 560 | `de.audi.tghu.dsitrace` (423), with appenders, client and emulator. Plugs in as `de.audi.tghu.serviceDelegateConfigurator`, an LSD `IServiceDelegateConfigurator`, to wrap and trace every DSI service. Provides the KeyPanel telnet emulator on :1796 that mib-keypanel uses. |
| `lang_data.zip`, `locale.zip` | — | Text and locale data, also on the bootclasspath. |
| `NavActiveIgnore.jar` (**backup unit only**) | 8 | Community patch (IBM 1.8 build). See §6. |

JRE extension jars (in the J9 JRE, not on the app fs; copies in `re/jars/`): `j9jce`, `j9jceprov`, `j9jsse`, `javacrypto` (Conscrypt 96), `nio`, `objectSwapper`, `socks5support`, `xiploader`, `MCFObjectReader`.

### 1.4 ARC plugin bundles in `/mnt/app/eso/bundles/` (+ `bundles_prod/`, `/mnt/ota/app/bundles`)

These are loaded by `arc.launcher.BundleLoader`, which lives inside lsd.jxe. `BundleLoader` collects `name_version.jar` from `arc.bundle.store` (default `/mnt/ota/app/bundles`, OTA-updatable) **and** `/mnt/app/eso/bundles`, and picks the highest version per name. It validates jars (`JarValidator`), builds a `URLClassLoader`, and calls `arc.internal.ArcStarter.start(BundleContext, Dictionary, URL[])` (config `/mnt/ota/app/config.ini`). ARC is therefore a nested plugin runtime inside LSD. Duplicate `-1020/-1041` builds coexist on disk and the newest wins.

- **ARC core** (`arc.*`): runtime 99 (`arc.internal.PlatformActivator`), jobs 58 + jobs.external 21, task 70, persistence 58, net 66 (`arc.net.NetCore`), update 171 (`arc.internal.update.UpdateCore`, UOTA events), winstone 91 (embedded servlet container), event 24, ext 39, gem 41 (ARC GEM layer), crypto 24, lang 18, log 4, interface 5, carcoding 7, certificateswapper 13, disclaimer 3, sdis 13, ot 108 (online tracing, `bundles_prod`).
- **Online services** (`de.eso.mib.online.*`, e.solutions):
  - Core: `service.core.registration` 514 (MBB vehicle registration, realms), `core.proxy` 52 (HTTP proxy), `core.diagnose` 21, `core.time` 9, `service.http` 77 (Weberknecht websocket), `service.jobs` 23, `service.lib.util` 333, `service.javalogger` 17.
  - DSI services: `service.dsi.{destimport 16, onlinedictation 23 (us.erl), operatorCall 36, picnav 34, poi 32, tourimport 19}`.
  - Others: `service.generic.ols` 19 (ASI-based OLS mini-apps), `service.onlinetraffic` 42 (Inrix), `service.vehiclehealth` 6, `service.trafficlight` 471 (Audi C2X "traffic light information", `de.aio.audi`), `service.test.ols` 95.
- **Connected nav** (`de.esolutions.navigation.connectednav.*`): common 22, mapcare 26, regioncounter 29.
- **`RemoteHMI-Implementation.jar`** 626 (`Bundle-SymbolicName de.audi.remotehmi`, Activator `de.audi.remotehmi.impl.RemoteHMIActivator`, `Bundle-Autostart: 20`). Server-driven UI engine: parser, caching, an `scxml` engine plus actions, `apps`, `ext.applistxml`, `ext.onlinemedia`, `ext.mobile`, `sds`, `ui.evo` views including `ViewGridAction`. Its widget-side counterpart is in lsd.jxe (`de.audi.remotehmi.ui.mib2` 70, `ui.pag` 26, …).
- `/mnt/app/eso/ulp/arc-ulp-launcher.jar` (1 class).

### 1.5 Not on the head unit
- `re/gateway/` (CGW): see §0.
- RCC: no Java at all (ARCHITECTURE.md §6).

## 2. Subsystem map

### 2.1 Platform and infrastructure
- **LSD OSGi framework** (`de.dreisoft.lsd`, 59 classes).
  - `LSD.main` loads the bundle registry from `-Dlsd.bundles` or the embedded `resources/bundles` and starts the `Bundle.Startup.Autostart` list.
  - A "bundle" is just an Activator class on the single boot classpath. There is no per-bundle classloader and no manifests.
  - `ServiceRegistry`/`ServiceFilter` implement OSGi service lookup (LDAP filters).
  - `ServiceDelegator` + `IServiceDelegateConfigurator` is the interception hook that DSITracer uses.
  - `LSDWatchdog` and `AuditTrail` handle health and history.
- **e.solutions fw.util** (`de.esolutions.fw.util.*`, 490; also the `fw_util_*.jar`s):
  - commons: `Buffer`, `RingBuffer`, timeouts, and `job.IDispatcherManager` (named dispatchers and thread pools configured by `fwservices.json`).
  - config (JSON/BCF), serializer, transport (socket/SSL/async), tracing (`TraceClient` into the native trace system and MIBRoot), os (JNI `jni_fw_util_os`).
  - `FWUtilServicesActivator` registers these as OSGi services.
- **Threading** (`fwservices.json`):
  - Pools: `HMIThreadPool` (up to 60), `UtilThreadPool` (20), `DsiDefaultPool` (11, one reserved for DSIKeyPanel), `httpRequest`.
  - Dispatchers (one thread each): **`HMIEvent`** (prio 10, the HMI "EDT"), `TimerDispatcher-5/-10`, `Startup`, `Radio`, `TV`, `PowerJobs`, `dsi` (on-demand), `dsi.KeyPanel` (prio 6), `dsi.CarParkingSystem` (prio 9), `poiWorker`, `App.Swdl`, `RemoteHMI`.
  - History, Logger and Statistic interceptors give per-job time limits; `HMIEvent` has a 200 ms job limit.
- **ATIP framework core** (`de.audi.atip.base`; "ATIP" is the Audi/e.solutions HMI platform):
  - `Framework` is the `ATIPImpl` BundleActivator. It loads `atip.properties` and `sys_const.properties`, creates `FrameworkAccess`, and registers it as OSGi service **`de.audi.atip.base.IFrameworkAccess`** (the god-object every bundle uses).
  - It sets the timezone to GMT, creates the log channel factory and ErrorManager, initialises `TimerDispatcher`, logs versions, creates SysApp, and starts `StartupManager`.
  - `FwServices` is the factory and holder for PowerManager, JDSIManager, LanguageManager, StorageManager, StartupManager, IconRenderer, InfotainmentRecorder, WavePlayer, MsgDistributor, BulkCopyManager, SMInterpreter, HMIWatchDog, OnlineLogo, DisplayManagerService, SWaPHandler, CacheHandler, DomainActivator/DomainHandler(MU).
- **Startup and lifecycle** (`de.audi.atip.startup`):
  - `StartupManager` reads **`hmi_startup.json`** through `startup.config.StartupConfigProvider` (`ipl.config.dir.hmi_startup`).
  - Each component carries `bundles[]`, `guide_module {id, hmi_bundle, sm_bundle}`, `domain {id, required_state, minimum_state}` hex masks, `dependencies`, `audio_context`, `lastmode_audio` and `key_code` (hard-key binding).
  - `ComponentState`, `BundleHandler`, `AppStateManager`, `DomainHandler(MU)`, `GUIDEModuleManager`, `LastmodeHandler` and `StartupQueue/Syncer` drive the phases.
  - Phase activators: `InitDSI` → `InitSystem` → `InitHMI` (`de.audi.tghu.fwhmi.InitHMIActivator`) → `InitModelBanks` → `ShowScreen` → `WaitForFirstScreen`/`WaitForKombiSync` → `PostStartup`.
  - Startup groups in hmi_startup.json: StaticInit (COMMAgent, JDSIAdmin, DSISimulation, ATIPDSIHigh, InitDSI, ATIPImplWin, InitSystem, ATIPHMIEvo, ATIPKbdServices, WidgetsEvoHigh, InitHMI, EAL), then BeforeAudio, BeforeApp, the feature components, then Addon/GEM.
  - Power: `de.audi.atip.power.PowerEventListener` and PowerManager; `StartupManager` is registered as a PowerEventListener.
- **Logging and diagnostics:**
  - `de.audi.atip.log.LogChannel` (named channels such as `Fw.Startup`) and `de.audi.tghu.log.LogServImpl`. Sinks: console, SLOG and TraceClient.
  - Controlled by `-DLOG=` and `-DSLOG=`; `traceConfig.properties`.
  - ErrorManager dumps to `ErrorDumpDir`, with a trigger port at 6767.
  - "COMM Doctor" commands in the DSI adapter.
  - `xTcpLog`, `xEventRecorder`, `InfotainmentRecorder`, and Profiling (`libjprof`).
- **Persistence:** `de.audi.atip.storage.IStorageAccess` → `de.audi.tghu.storage.StorageAccess` → `DSIStorageProvider` → `org.dsi.ifc.persistence.DSIPersistence` (native persistence server). ARC plugins use `arc.persistence` (ASI instance 5 ranges from `persistence.json`).
- **Inter-app messaging:**
  - `de.audi.atip.interapp` (567 classes) holds the OSGi service interfaces that apps expose to each other, for example `BluetoothService`, `CarPhoneService`, `SDSService`, `ExternalKeyListener`, `IEjectHandler`, `sdis.ISDISHeadUnitService`.
  - `de.audi.atip.msg.MsgDistributor`/`MsgListener` is a lightweight broadcast.

### 2.2 Service layer (Java to native)
- **DSI** (Delphi/Harman "Distributed Service Interface"; the native servers are on MMX or on RCC through DSIIPCBridge):
  - API: `org.dsi.ifc.<x>.DSI<X>` (requests) and `DSI<X>Listener` (async replies), with DTOs. 99 interfaces.
  - Wire proxies (generated): `de.esolutions.fw.comm.dsi.<x>.impl.DSI<X>Proxy`, plus `*Reply` and serializers. 2,214 classes.
  - OSGi adapters: `de.esolutions.fw.dsi.<x>.DSI<X>Provider` (`extends AbstractProvider implements DSI<X>`) and `DSI<X>Dispatcher` (`extends AbstractDispatcher implements DSI<X>Reply`, fans out to every listener).
  - Admin: `de.esolutions.fw.dsi.AdapterActivator` (bundle `JDSIAdmin`) creates `admin.DSIAdmin`, which uses `config.AdapterConfig`, `ProviderTracker`, `ListenerTracker(OSGi)` and `comm.DSIServiceWorker`. It needs the `IDispatcherManager` service.
  - `org.dsi.ifc.base.ServiceAdmin` is the start/stop API.
- **COMM** (`de.esolutions.fw.comm.*`): the e.solutions IPC runtime under DSI and ASI. It includes `agent` (Agent, AgentStarter, broker, naming/directory, doctor, watch), `core` (Proxy, Stub, IService, ServiceInstanceID, method/message/protocol), `persistence`, and `attributes`. `COMMAgent` (`comm.agent.osgi.AgentActivator`) starts the agent. The transport is `fw.util.transport` (sockets).
- **ASI** (`de.esolutions.fw.comm.asi.*`, 1,672): e.solutions-internal service interfaces, generated in `X`/`XC`/`XS`/`XReply` (+impl) form.
  - `hmisync.*` (599): `audio`/`car`/`headunit`/`mastercontrol`/`media`/`navigation`/`radio`/`sound`… This is the HMI state-sync API used by the **SDIS** bundles (`*SDIS` activators, `de.audi.audio.sdis`, `de.audi.atip.sdis.IHMISync*`). SDIS appears to be the rear-seat **Smart Display/tablet integration**, judging by `INaviTabletService` and `SDISConnectionState`.
  - `diagnosis` (483), `fec` (FecManager, ComponentProtection), `navigation`, `connectivity`, `explorer`, `online`, `speech`, `persistence`, `imageserver`, `onlineservices`.
- **JDSI layer in ATIP:**
  - `de.audi.tghu.jdsi.JDSIManager` is reached via `IFrameworkAccess.startDSIService(name, instance)`/`stopDSIService`. It reference-counts, queues starts until `ServiceAdmin` appears, and checks for a registered `DSIListener` with OSGi filter `(&(DEVICE_NAME=<svc>Listener)(DEVICE_INSTANCE=<n>))`.
  - `de.audi.tghu.dsi.high.Activator`/`HighFacadeFactory` (bundle `ATIPDSIHigh`) provides High-variant DSI facades (e.g. `DSISoundExt`).
  - `org.dsi.ifc.internal.AdapterPlugin`/`AdapterManager` (bundle `adaptermanager`, started with Navi) provides navigation adapters (EB location, charging stations, phoneme parser).
- **BAP** (VW Bedien- und Anzeigeprotokoll, used for the cluster, HUD, ENI and eCall):
  - Stack: `de.vw.mib.bap` (651, generated FctID marshalling) and `de.audi.app.bap.fw` (142), over `org.dsi.ifc.bap`.
  - Users: `AppCombiBAP` (`de.audi.app.combi.bap`, 209) for cluster menus, media, nav and phone; `AppOnlineFunctionsBAP`, `AppMediaCombiBAP`, `AppBAPEcall`, `AppBAPENI`, `AppBAPRemoteServices`.
  - `de.vw.mib.asl.api.bap*` is a tiny shared observer and timer API.
- **ExLAP** (`de.audi.tghu.exlap`, 240): an external/OEM data-access protocol server, `org.dsi.ifc.exlap`.

### 2.3 HMI framework
- **HMI service and core:**
  - `de.audi.tghu.fwhmi.FwHMI` is the central HMI service. It provides the terminal registry, event dispatcher, display manager, focus manager, `HMIRegistry` (model banks, HMI applications, screens, texts, conditions) and per-terminal Screen/Popup/PartialPopup managers.
  - It has the `fire*Event` entry points: SM, key, joystick, wheel, touch, gesture and model update.
  - `HMIEventDispatcher` and `HMIEventQueue` run on the `HMIEvent` dispatcher, which has a heartbeat timer and statistics.
  - `ATIPHMIEvo` (`de.audi.tghu.hmi.evo.ATIPHMIActivatorEvo`) and `de.audi.atip.hmi.HMIService` handle per-terminal context through `de.audi.tghu.hmi.{ScreenManager, ScreenChangeManager, PopupManager, ResourceManager, AbstractTerminalContext}`.
- **State machines** (`de.audi.atip.statemachine`):
  - These are generated, table-driven hierarchical state machines, one **SMM** per feature: `MediaSMM`, `NaviSMM`, `PhoneSMM`, `CarSMM`, `SystemSMM`, `TunerSMM`, `TerminalModeSMM`, … (23 in total) under `de.audi.tghu.<f>.sm`.
  - Each SMM has generated `*SMMInitStates`, `*SMMInitTransitions`, `*SMMInitMediators` and `*SMMActions`. State, superstate, trigger and transition data are `int[]` tables, and each state carries a `screenID`.
  - Engine classes: `AbstractSMM`, `State` (composite, history and include flags), `Transition`, `SMModule`, `SMInterpreter`, `EventMediator`/`MediatorRegistry` (with `mediator.*DataBuffer`), `SyncTargetManager`, `Popup`, and `SMEvent<Feature>` constant classes.
  - SMMs are instantiated per terminal ("main", "frontPassenger", "rearSeatLeft/Right") by `AbstractSMMActivator.init()`.
  - **Actions** go out through **ActionProxy** interfaces (`statemachine.ap.MediaActionProxy`, `NaviActionProxy`, … 31 of them). The App tier implements them, e.g. `de.audi.app.media.evo.actionproxy.MediaActionProxyImpl`, and registers them as OSGi services. `AbstractSMMActivator` tracks the required proxies through a `ServiceTracker`.
  - There is **no SCXML in the core HMI**. The source modelling tool is unknown (Open Questions); the output is Java.
- **Screens:**
  - These are generated Java, one factory per feature: `de.audi.tghu.<f>.hmi.evohigh.<F>ScreenFactory` (21 factories) plus `<F>ScreenBagN` (static methods like `MediaScreenBag1.mEDIALOADING()`), `<F>ConditionBank` (visibility and enable conditions, thousands of `$N` closures) and `FontFactory`.
  - A screen method builds a tree of `*Controller` widgets (`de.esolutions.hmi.widgets.audi.evo.*`) paired with `*RendererHigh` renderers (`...evo.high.widgets.*`). Bounds, colour palettes, bitmap ids and model ids are hard-coded constants, and the root is a `ScreenWidgetEVO`.
  - Layout is done by `evo.gridlayout.GridLayout`/`AbstractGridLayout`.
- **Widget library** (`de.esolutions.hmi.widgets`, 1,022):
  - `audi.base` holds the abstract widget/controller base.
  - `audi.evo` holds controllers (Container, Glassplate, CoverStack, FocusCursor, Menu, Speller, Touch, StatusBar, CombiMap, …).
  - `audi.evo.high` holds renderers and **`HMITerminalImplMIB2High`** (one per display terminal: GUIManager, KanziResourceLoader, EALManager, PreloadManager, skin and layout, car-coding helper).
  - The `WidgetsEvoHigh` bundle is `WidgetsActivatorMIB2High`. Porsche, Bentley and PGen2 widget sets are referenced in bundles.properties but **not present** in this jxe.
- **Rendering** (`de.esolutions.graphics.eal`, "EAL" 1.6.7):
  - A Java object API (`api.IManager/IContext/INode2D/INode3D/IImage/IFont/IAnimation/IRenderer…`) over **SWIG JNI `ealswig`** (`de.esolutions.graphics.ealswigJNI`, ~1,206 native methods) to native **Kanzi 2.5.2**.
  - `EAL` bundle (`eal.dumper.Activator`). `.kzb` assets come from `-DKzbRoot` (63 scenes, `initialKZBs=6`, `-DealMemorySize=80`).
  - This is the only large Java-to-native JNI surface. Others: `jni_fw_util_os`, `styledbservice` (IconExtractor), `jprof` and `xip`.
- **Model and data binding:**
  - `de.audi.atip.hmi.model` contains `ChoiceModel`, `LabelModel`, `ListModel`, `ButtonModel`, `RangeModel`, `SpellerModel`, `Buffered*` variants and `AbstractModelBank`.
  - `de.audi.atip.hmi.modelaccess` splits each model into `*ModelApp` (App-side writer) and `*ModelGUI` (HMI-side reader).
  - Per-feature model banks live in `de.audi.atip.model.<F>ModelBank`, with `ICore*`/`IEvo*` id interfaces.
  - The HMI activator registers the bank as `HMIModelBank` and itself as `HMIBundle`/`I18NTarget`. Model updates travel as `ModelUpdateEvent` on the HMI event queue.
- **Display and layers:**
  - `de.audi.tghu.fwhmi.DisplayManager` / **`DisplayManagerMIB2High`** (`defineContexts`, `switchContext`, `setUpdateRate`; context ids such as 74 = cluster map+KDK).
  - `de.audi.atip.displaymanager.{DisplayManagerServiceImpl, DSIDisplayManagerControllerImpl, Start/StopComponentCommand, SetCroppingCommand}` sit over `org.dsi.ifc.displaymanagement`/`displaycontroller`, which reach the native DisplayManager (`libdm_modMain.so`). That service owns terminals, contexts and displayables, and the MOST video encoder that feeds the cluster.
  - Also: `IDisplayManagerKombiControl`, `KombiMapControl` (`de.audi.tghu.combi.mapcontrol`), `JointManager` (dual-view/joint use), `LegalDisclaimer`, and OSD providers.
- **Input:**
  - Native KeyPanel DSI server → `org.dsi.ifc.keypanel.DSIKeyPanelListener` implemented by **`de.audi.kbd.dsi.KbdListener`**, which handles `updateKey2`, `updateEncoder2` (rotary), `updateCharacterEvent2` (touchpad handwriting), `updateProximity`, `supplyExternalKey`, clamp state and usage restrictions.
  - Supporting classes: `KbdHandler`, `ComboKeyDetector` (GEM/REM 2-key holds), `GestureStateMachine` and `KbdScreenShotManager`.
  - Events are posted to `FwHMI.fireKeyEvent`/`fireWheelButtonEvent`/`fireTouchEvent`/`fireGestureEvent`, then `HMIEvent` queue → **`de.audi.kbd.hmi.evo.KeyEventDistributorEvo`** (`keyPressed`/`keyReleased`/`keyTurned`/`touchPad*`/`triggerGestureEvent`) → `FocusManager`/`VirtualGUIManager` (hardkeys) → the focused widget, or `fireSMEvent` into the active SMM.
  - Component `key_code`s in hmi_startup.json map mode keys to components.
  - Bundle `ATIPKbdServices` = `de.audi.kbd.KbdServicesActivator`. DSITracer (:1796) and xTcpKeyPanel (:2225) inject at the DSIKeyPanel level.
  - Terminal-mode apps take keys through `de.audi.app.terminalmode.keyevents` and `TerminalModeDSIKeyEventsController`.

### 2.4 Feature modules (App / SM / HMI trios unless noted; all in lsd.jxe)

| Feature | App tier | SM/HMI tier | Notes |
|---|---|---|---|
| System / Home | `de.audi.app.system` (41), `AppSystemSDIS` | `tghu.system` (392) | Main menu, status bar, standby, popups. `JointUseSystemSMM`. |
| Media | `de.audi.app.media` (863: content, dsi, source, combi, transfer, sdis, sds, browser) | `tghu.media` (449) | USB, SD, BT, DVD and online sources via DSI media/albumbrowser. MediaTransfer(Core); CombiBAP feed. |
| Tuner | `de.audi.app.tuner` (60) + **`de.audi.tuner.app`** (830: amfm, dab, sdars, epg, history, rsdb, gracenote, rthyperlinking) | `tghu.tuner` (462) | DSI radio/sdars/radiodata/connectedradio. |
| TV | `de.audi.tv.app` (553) | `tghu.tv` (156) | DSI tvtuner. |
| Navigation | `de.audi.app.navi` (718, evo) + **`de.audi.tghu.navi.app`** (2,235: addressinput, map, di, dsi, command, routeguidance, poi, sds, cluster, …) | `tghu.navi.hmi` (1,216) | The largest feature. DSI navigation/map/search/navservicesapi/predictivenavigation/mobilityhorizon. `OperationManager` (NavInitializationChecker). GEOnDemand/StreetView are referenced but absent. |
| Phone | `de.audi.app.phone` (890) | `tghu.phone` (575) | DSI telephone(ng), bluetooth, organizer. `de.audi.app.bluetooth` (108), `obex`. |
| Messaging | `de.audi.app.messaging` (662) | `tghu.messaging` (271) | SMS and e-mail, dictation. |
| Address book | `de.audi.app.addressbook` (215), `de.eso.vcard`/`vcalendar` | `tghu.addressbook` (168) | |
| Car (vehicle setup) | `de.audi.app.car` (525: core, common, evo, sdis) | `tghu.car` (856) | DSI car* (comfort, aircondition, kombi, parkingsystem, hybrid, eco, light, seat, driverassistance, …). Climate is part of Car. |
| Early functions | `de.audi.app.earlyfunc` (171: parking, hybrid) | `tghu.earlyapps` (304) | RVC and parking display that must start early. |
| Tone / Audio | `de.audi.tone.app` (112), `de.audi.audio` (116: init, volume, sdis, earlysound, sse) | `tghu.tone` (248) | DSI audio/sse. Bundles ATIPAudio, ATIPAudioSDIS. |
| Terminal mode | `de.audi.app.terminalmode` (671: smartphone.{carplay 34, androidauto2 40, carlife 43}, dsi, statemachine, device, audio, combi, keyevents) | `tghu.terminalmode` (27) | CarPlay, Android Auto, Baidu CarLife, MirrorLink via DSI carplay/androidauto2/carlife/smartphoneintegration. |
| Online (Audi connect) | `de.audi.app.online` (151) + `tghu.online.app` (447) | `tghu.online.hmi` (433) | OLS UI and the RemoteHMI host. `HMIViewGridListener`. Back-end plugins are ARC bundles (§1.4). |
| RemoteHMI | `RemoteHMI-Implementation.jar` (626) + lsd `de.audi.remotehmi.ui.*` (177) | — | Server-delivered **SCXML** UIs (Apache Commons SCXML 0.9 + JEXL in lsd) rendered with native widgets: applist mini-apps, online media. |
| Speech | `de.audi.app.sdsmanager` (557: apps, dictation, oneshot, nbest, grammar), `de.audi.tghu.tts`, `de.audi.app.wordprediction` (absent) | `tghu.sds` (SMSDS; HMI = `DummyMIBHigh`) | DSI speechrec/tts/phonetic. `externalSDS=true`, `enableNLU=true`. |
| Settings | `de.audi.app.settings` (47) | `tghu.settings` (79) | |
| Connectivity | `de.audi.app.connectivity` (45), `de.audi.app.wlan` (58) | `tghu.connectivity` (228) | Wi-Fi hotspot, data connection, SIM. |
| SWDL (update) | `de.audi.app.swdl` (11) + `tghu.swdl.app/ude` | `tghu.swdl.hmi` | DSI swdl*. Has its own run-mode (`RUN_MODE=swdl`). |
| eCall | `de.audi.app.ecall` (110), AppBAPEcall | `tghu.ecall` | |
| Info / traffic | `de.audi.app.info` | `tghu.info` (156) | TMC/TPEG; JP/KR variants absent. |
| Wireless charging | `de.audi.app.wirelesscharging` | `tghu.wirelesscharging` | |
| ENI / Remote services | `de.audi.app.eni` (28), BAP ENI and RemoteServices | — | |
| Cluster ("Combi") | `de.audi.app.combi.bap` (209), `tghu.combi.mapcontrol` | — | MMI-Kombi / VC over BAP plus display-manager map contexts. DDP2 and MMISync variants absent. |
| Browser / Explorer | `de.audi.tghu.browser` (ATIPBrowser), `de.audi.tghu.filebrowser` (ATIPFileBrowser), `picturestore` | — | |
| Engineering: **REM** | `de.audi.tghu.redengineering.app.EngineeringActivator` (19) | `tghu.engineering` (43) | Red Engineering Menu, opened with the NAV+MEDIA hold. Native Java HMI screens. |
| Engineering: **GEM** | `AppDevelopment.jar` + `DevelopmentStubEvo.jar` | `development.sm.DevelopmentSMM` | Green Engineering Menu, opened with the NAV+RADIO hold. Scripted `.esd` screens from `engdefs/`, rendered via EAL. `-DenableGEM=true`. |
| Test support | `de.audi.app.testsupport` | `tghu.testsupport` | HMI engineering/test menu, `EngineeringInfoService`. |
| SW diagnosis | `diag.jar` (`de.mib.swdiagnosis`) | — | |

## 3. Key architectural seams (actual classes)

1. **Boot:**
   - `lsd.sh` → `j9 -jxe:lsd.jxe` → `de.dreisoft.lsd.LSD.main` → `BundleRegistry` (`resources/bundles.properties`) → autostart `FwUtilServices`, `Profiling`, `Tracing`, then `ATIPImpl` = `de.audi.atip.base.Framework.start()`.
   - `Framework.start()` registers `IFrameworkAccess`, then `StartupManager.start()`, which walks `hmi_startup.json` components and starts their bundle activators in dependency, domain and phase order.
2. **Central objects:**
   - `IFrameworkAccess`/`FrameworkAccess` (OSGi service) is the gateway to:
     - `FwServices`, `StartupManager`, `AppStateManager`, `LastmodeHandler`, `PowerManager`
     - `JDSIManager` (`startDSIService`), `HMIService`/`FwHMI`, `SMInterpreter`, `MsgDistributor`
     - `IDispatcherManager`, `ThreadPool`s, `LogChannel`s, `StorageAccess`, `SysConst`/variant predicates (`isEvoHigh()`, `isNar()`, …)
   - `FwHMI` is the HMI hub, and `HMIRegistry` maps module id to model bank, application, screens and texts.
3. **App registration:**
   - App bundles extend `de.audi.atip.activator.AbstractFrameworkActivator`, implement their feature's `ActionProxy`, register OSGi services (ActionProxy, interapp services, DSI listeners) and write models via `*ModelApp`.
   - SM bundles extend `AbstractSMMActivator`, build per-terminal SMMs and track ActionProxies.
   - HMI bundles extend `AbstractHMIActivator(moduleId, name, skin, ModelBank)` and supply `getScreenFactory()` and `getConditionBank()`. Example: `de.audi.tghu.media.hmi.Activator` with module id 2.
   - `hmi_startup.json` `guide_module.id` ties these together (System 0, Tuner 1, Media 2, Phone 3, Navi 4, Info 5, Car 6, AddressBook 7, Tone 10, Settings 11, Engineering 13, SWDL 17, EarlyApps 21, Messaging 22, Online 23, TestSupport 24, Connectivity 25, TV 26, SDS 31, TerminalMode 32, Ecall 33, WirelessCharging 34).
4. **Screens:** generated Java (`<F>ScreenFactory` / `<F>ScreenBagN` / `<F>ConditionBank`) builds widget-controller/renderer trees. An SMM state's `screenID` selects the screen. Kanzi `.kzb` provides only templates and 3D assets, loaded via `KanziResourceLoader`/EAL.
5. **Getting DSI services:**
   - The app registers a `DSI<X>Listener` OSGi service with `DEVICE_NAME=DSI<X>Listener` and `DEVICE_INSTANCE=n`, then calls `framework.startDSIService("org.dsi.ifc.<x>.DSI<X>", n)`. The media example is `de.audi.app.media.dsi.AbstractDSIController` → `osgi.ServiceManagerImpl`.
   - `JDSIManager` → `ServiceAdmin.startService` → `DSIAdmin` instantiates `DSI<X>Provider` (wrapping the COMM `DSI<X>Proxy`) and registers it as the `DSI<X>` service. Replies arrive on `DSI<X>Dispatcher` (the `dsi` dispatcher pool) and fan out to the listeners.
   - This OSGi registration path is where `ServiceDelegator`/DSITracer and class-override patches hook in.
6. **Input:** DSIKeyPanel (native) → `de.audi.kbd.dsi.KbdListener` → `FwHMI.fire*Event` → `HMIEvent` dispatcher → `KeyEventDistributorEvo` → focus/widget or `SMEvent` → SMM transition → ActionProxy call into the App.
7. **Display:** `DisplayManagerMIB2High` and `de.audi.atip.displaymanager.DSIDisplayManagerControllerImpl` → DSI displaymanagement → native DisplayManager (contexts, displayables, MOST encoder for the VC/cluster). Each terminal's GUI is `HMITerminalImplMIB2High` → EAL → Kanzi.
8. **Online plugin runtime:** `ArcLauncher` (`arc.launcher.BundleLoader`) → `arc.internal.ArcStarter` → ARC plugins (`arc.*`, `de.eso.mib.online.*`, RemoteHMI), which talk DSI and ASI back to the HMI.

## 4. Size summary
- On-unit Java: lsd.jxe has **30,493** classes; boot jars add **~1,550** unique classes (GEM 189, DevStub 18, diag 311, RemoteHMI-Libs 172, recorders/TCP 58, DSITracer 560 when enabled) plus 502 fw_util duplicates; ARC/service bundles add **~2,950**, including RemoteHMI-Implementation 626. That totals **~35k classes**. App-level classes, excluding the JCL, xerces and similar libraries, number about **28.5k**.
- Biggest packages:

  | Package | Classes |
  |---|---:|
  | `de.audi.tghu.navi` | 3,460 |
  | `de.esolutions.fw.comm.dsi` | 2,214 |
  | `de.esolutions.fw.comm.asi` | 1,672 |
  | `org.dsi.ifc` | 1,270 |
  | `de.esolutions.hmi.widgets` | 1,022 |
  | `de.audi.app.phone` | 890 |
  | `de.audi.tghu.online` | 886 |
  | `de.audi.app.media` | 863 |
  | `de.audi.tghu.car` | 856 |
  | `de.audi.tuner.app` | 830 |
  | `de.audi.app.navi` | 718 |
  | `de.audi.app.terminalmode` | 671 |
  | `de.audi.app.messaging` | 662 |
  | `de.vw.mib.bap` | 651 |
  | `de.audi.tghu.phone` | 575 |
  | `de.audi.atip.interapp` | 567 |
  | `de.audi.app.sdsmanager` | 557 |
  | `de.audi.tv` | 553 |

## 5. bundles.properties versus what ships
- 115 of 162 activator entries resolve inside lsd.jxe.
- Resolve in jars: DSITracer, TcpLog, FwSwDiag (diag.jar), TcpKeyPanel, xEventRecorder, AppDevelopment and SMDevelopment (DevelopmentStubEvo.jar), RemoteHMI (eso/bundles via ARC).
- **Absent** (variant leftovers): JDSIDelphi, TimerSPI, ATIPHMIPGen2, DSISimulation, TimeSource, ATIPDSIStandard, ATIPImplWin, the MyScreen trio, AppMediaCombiDDP2, AppMediaExlap, AppGoogleMaps, AppStreetView, AppCarPCSimulation, the TrafficInfoJP and InfoKR/TpegKR trios, the Home trio, all non-EvoHigh widget sets plus Lightscreen, AppWordPrediction, the CombiDDP2/MMISync bundles, TestFramework, HMIDevelopment and GoogleNav.
- hmi_startup.json still lists DSISimulation and ATIPImplWin in StaticInit. LSD logs "An Autostart-Bundle can't be found" and continues.

## 6. Caveats and risks
- jxe2jar output is lossy. Abstract and interface methods carry `Code` (CFR shows `default public`). **Numeric constants are byte-swapped** in the constant pool, so state ids, model ids and colours in the decompiled SMM and ScreenBag tables appear byte-reversed (e.g. `1326252800` = `0x4F0D0300`). Floats are retagged as ints, and `invokeinterface` counts are zeroed. See `~/complex-projects/mib2q-hmi-lab/scxml/README.md`; only 2,526 of 30,493 classes verify unrepaired.
- **NavActiveIgnore.jar on the backup unit** is a VW-targeted patch. 5 of its 8 classes are `de.vw.mib.asl.internal.*`, which do not exist in this Audi jxe, so they are inert. It *also* ships `de.esolutions.fw.comm.dsi.carplay.impl.DSICarplayProxy`, `de.esolutions.fw.dsi.androidauto.DSIAndroidAutoDispatcher` and `de.esolutions.fw.dsi.androidauto2.DSIAndroidAuto2Dispatcher`. All three exist in the Audi jxe, so on the bootclasspath they **shadow the stock Audi copies**. Diff these against stock; it is a possible source of CarPlay or AA behaviour differences.

## 7. Open questions
1. What HMI design tool generated the SMM tables and ScreenBags? Candidates are e.solutions "GUIDE" (`guide_module`, `GUIDEModuleManager`) or an Audi/eso modeller. Is there a source model (e.g. XML) anywhere on the unit or in the jxe?
2. Is the CGW (`audi-cgw.jar`) on a separate ECU (OCU/"atop"), or in another MMX partition not dumped yet?
3. Is RemoteHMI actually loaded via ARC from `eso/bundles`, given that its LSD bundles.properties activator is not on the boot classpath? Confirm with a runtime log.
4. Are the `fw_util_*.jar` shadows byte-identical to the jxe copies or newer?
5. What exactly is SDIS (rear-seat "Smart Display" tablets?), and which native process consumes ASI HMISync?
6. Which `org.dsi.ifc` services terminate on MMX native processes and which go through DSIIPCBridge to RCC? A service→process map is needed (e.g. from the DSITracer client data or COMM directory).
7. Where are the generated id→name tables (model ids, screen ids, SM event ids)? `HMIKzbConstantsSystem`/`SMEvent*` hold some, but they are byte-swapped in the decompile.
