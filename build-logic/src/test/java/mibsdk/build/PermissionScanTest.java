package mibsdk.build;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class PermissionScanTest {
    @TempDir Path tmp;

    @Test
    void flagsReflectionAndExec() throws Exception {
        File classes = Fixtures.compileV48(tmp.resolve("classes"), Map.of("fixture.P",
                "package fixture; @SuppressWarnings(\"all\") public class P {"
                        + " public void reflect(java.lang.reflect.Method m) throws Exception { m.invoke(null); }"
                        + " public void forName() throws Exception { Class.forName(\"x\"); }"
                        + " public void exec() throws Exception { Runtime.getRuntime().exec(\"ls\"); }"
                        + " public void exit() { System.exit(0); }"
                        + " public void stop(Thread t) { t.stop(); }"
                        + " public void clean() { new Object().hashCode(); } }"));
        assertEquals(List.of(
                "fixture/P.exec -> java/lang/Runtime.exec",
                "fixture/P.exit -> java/lang/System.exit",
                "fixture/P.forName -> java/lang/Class.forName",
                "fixture/P.reflect -> java/lang/reflect/Method.invoke",
                "fixture/P.stop -> java/lang/Thread.stop"),
                PermissionScan.run(classes).findings());
    }

    @Test
    void flagsRawSocketConstruction() throws Exception {
        File classes = Fixtures.compileV48(tmp.resolve("classes"), Map.of("fixture.S",
                "package fixture; @SuppressWarnings(\"all\") public class S {"
                        + " public Object open() { return new java.net.Socket(); } }"));
        assertEquals(List.of("fixture/S.open -> java/net/Socket.<init>"),
                PermissionScan.run(classes).findings());
    }

    @Test
    void flagsOtherNetworkEntryPoints() throws Exception {
        File classes = Fixtures.compileV48(tmp.resolve("classes"), Map.of("fixture.N",
                "package fixture; @SuppressWarnings(\"all\") public class N {"
                        + " public Object a() throws Exception { return new java.net.ServerSocket(); }"
                        + " public Object b(java.net.URL u) throws Exception { return u.openConnection(); }"
                        + " public Object c() throws Exception { return new java.net.DatagramSocket(); }"
                        + " public Object d(java.net.URL u) throws Exception { return u.openStream(); }"
                        + " public Object e() throws Exception { return java.net.InetAddress.getByName(\"x\"); }"
                        + " public Object f(java.net.URLConnection c) throws Exception { c.connect(); return c; }"
                        + " public Object g() throws Exception { return new java.net.MulticastSocket(); } }"));
        assertEquals(List.of(
                "fixture/N.a -> java/net/ServerSocket.<init>",
                "fixture/N.b -> java/net/URL.openConnection",
                "fixture/N.c -> java/net/DatagramSocket.<init>",
                "fixture/N.d -> java/net/URL.openStream",
                "fixture/N.e -> java/net/InetAddress.getByName",
                "fixture/N.f -> java/net/URLConnection.connect",
                "fixture/N.g -> java/net/MulticastSocket.<init>"),
                PermissionScan.run(classes).findings());
    }

    @Test
    void flagsMicroeditionConnectorOnlyWhenReferenced() throws Exception {
        Path dir = tmp.resolve("classes");
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_4, Opcodes.ACC_PUBLIC, "fixture/C", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "open", "()V", null, null);
        mv.visitCode();
        mv.visitLdcInsn("socket://x:1");
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "javax/microedition/io/Connector", "open",
                "(Ljava/lang/String;)Ljavax/microedition/io/Connection;", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 0);
        mv.visitEnd();
        cw.visitEnd();
        write(dir.resolve("fixture/C.class"), cw.toByteArray());
        assertEquals(List.of("fixture/C.open -> javax/microedition/io/Connector.open"),
                PermissionScan.run(dir.toFile()).findings());
    }

    @Test
    void ignoresSyntheticClassDollarAndKotlinStdlib() throws Exception {
        Path dir = tmp.resolve("classes");
        // javac -target 1.4 emits `static Class class$(String)` wrapping Class.forName for class literals.
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_4, Opcodes.ACC_PUBLIC, "fixture/Lit", null, "java/lang/Object", null);
        forNameMethod(cw, Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, "class$");
        cw.visitEnd();
        write(dir.resolve("fixture/Lit.class"), cw.toByteArray());
        // Kotlin stdlib classes legitimately use reflection.
        ClassWriter kw = new ClassWriter(0);
        kw.visit(Opcodes.V1_4, Opcodes.ACC_PUBLIC, "kotlin/internal/Impl", null, "java/lang/Object", null);
        forNameMethod(kw, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "load");
        kw.visitEnd();
        write(dir.resolve("kotlin/internal/Impl.class"), kw.toByteArray());

        assertEquals(List.of(), PermissionScan.run(dir.toFile(), Set.of("kotlin/internal/Impl")).findings());
    }

    @Test
    void kotlinPackageClassNotFromTheStdlibIsFlagged() throws Exception {
        // A plugin's own class in a kotlin.* package is not exempt: only the stdlib's classes are.
        Path dir = tmp.resolve("classes");
        ClassWriter kw = new ClassWriter(0);
        kw.visit(Opcodes.V1_4, Opcodes.ACC_PUBLIC, "kotlin/internal/Sneaky", null, "java/lang/Object", null);
        forNameMethod(kw, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "load");
        kw.visitEnd();
        write(dir.resolve("kotlin/internal/Sneaky.class"), kw.toByteArray());

        assertEquals(List.of("kotlin/internal/Sneaky.load -> java/lang/Class.forName"),
                PermissionScan.run(dir.toFile(), Set.of("kotlin/internal/Impl")).findings());
        assertEquals(List.of("kotlin/internal/Sneaky.load -> java/lang/Class.forName"),
                PermissionScan.run(dir.toFile()).findings());
    }

    @Test
    void classForNameOutsideClassDollarIsStillFlagged() throws Exception {
        Path dir = tmp.resolve("classes");
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_4, Opcodes.ACC_PUBLIC, "fixture/Lit", null, "java/lang/Object", null);
        forNameMethod(cw, Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, "other$");
        cw.visitEnd();
        write(dir.resolve("fixture/Lit.class"), cw.toByteArray());
        assertEquals(List.of("fixture/Lit.other$ -> java/lang/Class.forName"), PermissionScan.run(dir.toFile()).findings());
    }

    private static void forNameMethod(ClassWriter cw, int acc, String name) {
        MethodVisitor mv = cw.visitMethod(acc, name, "(Ljava/lang/String;)Ljava/lang/Class;", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Class", "forName",
                "(Ljava/lang/String;)Ljava/lang/Class;", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
    }

    private static void write(Path p, byte[] b) throws Exception {
        Files.createDirectories(p.getParent());
        Files.write(p, b);
    }

    private static final String ICON_STUB = "package mibsdk.api; public interface Icon { int TINTED = 0; int OWN_COLOURS = 1; }";
    private static final String UI_STUB = "package mibsdk.api; public interface Ui {"
            + " Icon icon(String name); Icon icon(String name, int colour); }";

    @Test
    void findsTheColourIconOverload() throws Exception {
        File colour = Fixtures.compileV48(tmp.resolve("colour"), Map.of("mibsdk.api.Icon", ICON_STUB,
                "mibsdk.api.Ui", UI_STUB, "fixture.C",
                "package fixture; import mibsdk.api.*; public class C {"
                        + " public Icon status(Ui ui) { return ui.icon(\"x\", Icon.OWN_COLOURS); }"
                        + " public Icon row(Ui ui) { return ui.icon(\"x\"); } }"));
        PermissionScan.Result r = PermissionScan.run(colour);
        assertEquals(4, r.needsApi());
        assertEquals(List.of("fixture/C.row -> Ui.icon(String)", "fixture/C.status -> Ui.icon(String,int)"),
                r.apiFindings());
        assertEquals(List.of(), r.findings(), "not a permission finding");

        File plain = Fixtures.compileV48(tmp.resolve("plain"), Map.of("mibsdk.api.Icon", ICON_STUB,
                "mibsdk.api.Ui", UI_STUB, "fixture.T",
                "package fixture; import mibsdk.api.*; public class T {"
                        + " public Icon row(Ui ui) { return ui.icon(\"x\"); } }"));
        assertEquals(3, PermissionScan.run(plain).needsApi());
        assertEquals(List.of("fixture/T.row -> Ui.icon(String)"), PermissionScan.run(plain).apiFindings());

        // The plugin's own icon(String,int), with the same name and descriptor, is not Ui's.
        File own = Fixtures.compileV48(tmp.resolve("own"), Map.of("mibsdk.api.Icon", ICON_STUB,
                "fixture.O", "package fixture; import mibsdk.api.*; public class O {"
                        + " public Icon icon(String name, int colour) { return null; }"
                        + " public Icon use() { return icon(\"x\", Icon.OWN_COLOURS); } }"));
        assertEquals(1, PermissionScan.run(own).needsApi());
        assertEquals(List.of(), PermissionScan.run(own).apiFindings());
    }

    private static final String PAGESPEC_STUB = "package mibsdk.api; public final class PageSpec {"
            + " public enum Kind { ITEM, TOGGLE, INFO, LINK, CHOICE, DROPDOWN, SLIDER, PROGRESS }"
            + " public PageSpec toggle(String id, String label, boolean on) { return this; }"
            + " public PageSpec choice(String id, String label, String[] options, int selected) { return this; } }";

    @Test
    void findsApi5MembersAndKindFields() throws Exception {
        // The stub is on the classpath, not scanned: a plugin's classes never include the SDK's own.
        File stub = Fixtures.compileV48(tmp.resolve("stub"), Map.of("mibsdk.api.PageSpec", PAGESPEC_STUB));
        File choice = Fixtures.compileV48(tmp.resolve("choice"), Map.of(
                "fixture.C", "package fixture; import mibsdk.api.*; public class C {"
                        + " public PageSpec page(PageSpec p) { return p.choice(\"c\", \"C\", new String[] {\"a\"}, 0); } }"), stub);
        PermissionScan.Result r = PermissionScan.run(choice);
        assertEquals(5, r.needsApi());
        assertEquals(List.of("fixture/C.page -> PageSpec.choice"), r.apiFindings());

        File kind = Fixtures.compileV48(tmp.resolve("kind"), Map.of(
                "fixture.K", "package fixture; import mibsdk.api.*; public class K {"
                        + " public boolean slider(PageSpec.Kind k) { return k == PageSpec.Kind.SLIDER; }"
                        + " public boolean toggle(PageSpec.Kind k) { return k == PageSpec.Kind.TOGGLE; } }"), stub);
        r = PermissionScan.run(kind);
        assertEquals(5, r.needsApi());
        assertEquals(List.of("fixture/K.slider -> PageSpec.Kind.SLIDER", "fixture/K.toggle -> PageSpec.Kind.TOGGLE"),
                r.apiFindings());
        assertEquals(List.of("fixture/K.slider -> PageSpec.Kind.SLIDER needs api 5"), r.tooNew(4));

        File toggle = Fixtures.compileV48(tmp.resolve("toggle"), Map.of(
                "fixture.T", "package fixture; import mibsdk.api.*; public class T {"
                        + " public PageSpec page(PageSpec p) { return p.toggle(\"t\", \"T\", true); } }"), stub);
        assertEquals(2, PermissionScan.run(toggle).needsApi());
        assertEquals(List.of("fixture/T.page -> PageSpec.toggle"), PermissionScan.run(toggle).apiFindings());

        assertEquals(2, ApiLevels.since("mibsdk/api/PageSpec", "toggle", "(Ljava/lang/String;Ljava/lang/String;Z)Lmibsdk/api/PageSpec;"));
        assertEquals(4, ApiLevels.since("mibsdk/api/Ui", "icon", "(Ljava/lang/String;I)Lmibsdk/api/Icon;"));
        assertEquals(5, ApiLevels.since("mibsdk/api/UiAction", "<init>",
                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Boolean;Ljava/lang/Integer;Ljava/lang/String;)V"));
        assertEquals(2, ApiLevels.since("mibsdk/api/UiAction", "<init>",
                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Boolean;)V"));
    }

    private static final String DAEMON_STATUS_CONTEXT = "package mibsdk.api; public interface PluginContext {"
            + " Ui ui(); Net net(); }";
    private static final String DAEMON_STATUS_UI = "package mibsdk.api; public interface Ui {"
            + " Page page(PageSpec spec, Listener<UiAction> onAction); StatusItem statusItem(String text);"
            + " Icon stockIcon(String name); }";

    @Test
    void daemonStatusAtApi1IsRefused() throws Exception {
        // The daemon-status sample's calls, rebuilt against stubs: its page, status item, stock icon and Net query.
        File classes = Fixtures.compileV48(tmp.resolve("daemon"), Map.ofEntries(
                Map.entry("mibsdk.api.Icon", ICON_STUB),
                Map.entry("mibsdk.api.Listener", "package mibsdk.api; public interface Listener<T> { void on(T t); }"),
                Map.entry("mibsdk.api.UiAction", "package mibsdk.api; public final class UiAction {}"),
                Map.entry("mibsdk.api.Page", "package mibsdk.api; public interface Page { void update(PageSpec s); }"),
                Map.entry("mibsdk.api.PageSpec", "package mibsdk.api; public final class PageSpec {"
                        + " public static PageSpec of(String id, String title) { return null; }"
                        + " public PageSpec info(String text) { return this; } }"),
                Map.entry("mibsdk.api.StatusItem", "package mibsdk.api; public interface StatusItem {"
                        + " void set(String text, Icon icon); }"),
                Map.entry("mibsdk.api.Net", "package mibsdk.api; public interface Net {"
                        + " String query(String host, int port, String request, int timeoutMs) throws java.io.IOException; }"),
                Map.entry("mibsdk.api.Ui", DAEMON_STATUS_UI),
                Map.entry("mibsdk.api.PluginContext", DAEMON_STATUS_CONTEXT),
                Map.entry("fixture.D", "package fixture; import mibsdk.api.*; public class D {"
                        + " public void enable(PluginContext ctx) throws Exception {"
                        + " Ui ui = ctx.ui();"
                        + " Page page = ui.page(PageSpec.of(\"status\", \"Daemons\").info(\"x\"), null);"
                        + " page.update(PageSpec.of(\"status\", \"Daemons\"));"
                        + " StatusItem item = ui.statusItem(null);"
                        + " item.set(\"TS up\", ui.stockIcon(\"dot\"));"
                        + " ctx.net().query(\"127.0.0.1\", 1, null, 1); } }")));
        PermissionScan.Result r = PermissionScan.run(classes);
        assertEquals(3, r.needsApi());
        assertEquals(List.of(
                "fixture/D.enable -> Net.query needs api 2",
                "fixture/D.enable -> Page.update needs api 2",
                "fixture/D.enable -> PageSpec.info needs api 2",
                "fixture/D.enable -> PageSpec.of needs api 2",
                "fixture/D.enable -> PluginContext.net needs api 2",
                "fixture/D.enable -> PluginContext.ui needs api 2",
                "fixture/D.enable -> StatusItem.set(String,Icon) needs api 3",
                "fixture/D.enable -> Ui.page needs api 2",
                "fixture/D.enable -> Ui.statusItem(String) needs api 2",
                "fixture/D.enable -> Ui.stockIcon needs api 3"), r.tooNew(1));
    }

    @Test
    void tooNewReportsEachSitesOwnLevel() throws Exception {
        String ui = "package mibsdk.api; public interface Ui {"
                + " Icon icon(String name); Icon icon(String name, int colour); void toast(String text, Icon icon); }";
        File both = Fixtures.compileV48(tmp.resolve("both"), Map.of("mibsdk.api.Icon", ICON_STUB,
                "mibsdk.api.Ui", ui, "fixture.B",
                "package fixture; import mibsdk.api.*; public class B {"
                        + " public void status(Ui ui) { ui.icon(\"x\", Icon.OWN_COLOURS); }"
                        + " public void toast(Ui ui) { ui.toast(\"hi\", null); } }"));
        PermissionScan.Result r = PermissionScan.run(both);
        assertEquals(5, r.needsApi());
        assertEquals(List.of("fixture/B.status -> Ui.icon(String,int) needs api 4",
                "fixture/B.toast -> Ui.toast needs api 5"), r.tooNew(3));
        assertEquals(List.of("fixture/B.toast -> Ui.toast needs api 5"), r.tooNew(4));
        assertEquals(List.of(), r.tooNew(5));
    }

    private static final String UI_PERMISSION_STUB = "package mibsdk.api; public interface Ui {"
            + " void carMenuRow(String label); void statusItem(String text); void toast(String text); }";
    private static final String CTX_STUB = "package mibsdk.api; public interface PluginContext {"
            + " Ui ui(); boolean granted(String permission); Object net(); }";
    private static final String CAR_MENU_DECLARE = "needs \"ui.slot.carmenu\": declare it in plugin.json \"permissions\"";

    private File carMenuCaller(String body) throws Exception {
        return Fixtures.compileV48(tmp.resolve("car"), Map.of("mibsdk.api.Ui", UI_PERMISSION_STUB,
                "mibsdk.api.PluginContext", CTX_STUB, "fixture.R",
                "package fixture; import mibsdk.api.*; public class R { public void run(PluginContext ctx) { "
                        + body + " } }"));
    }

    @Test
    void carMenuRowNeedsItsPermission() throws Exception {
        PermissionScan.Result r = PermissionScan.run(carMenuCaller("ctx.ui().carMenuRow(\"x\");"));
        assertEquals(Map.of("fixture/R.run -> Ui.carMenuRow", "ui.slot.carmenu"), r.permissionSites());
        assertEquals(List.of("fixture/R.run -> Ui.carMenuRow " + CAR_MENU_DECLARE), r.undeclared(List.of()));
        assertEquals(List.of(), r.findings(), "not a platform.unrestricted finding");
    }

    @Test
    void guardedCallStillNeedsDeclaration() throws Exception {
        PermissionScan.Result r = PermissionScan.run(carMenuCaller(
                "if (ctx.granted(\"ui.slot.carmenu\")) { ctx.ui().carMenuRow(\"x\"); }"));
        assertEquals(List.of("fixture/R.run -> Ui.carMenuRow " + CAR_MENU_DECLARE), r.undeclared(List.of()));
    }

    @Test
    void declaredPermissionPasses() throws Exception {
        PermissionScan.Result r = PermissionScan.run(carMenuCaller("ctx.ui().carMenuRow(\"x\");"));
        assertEquals(List.of(), r.undeclared(List.of("ui.slot.carmenu")));
        assertEquals(List.of(), PermissionScan.run(carMenuCaller("ctx.ui();")).undeclared(List.of()));
    }

    @Test
    void everyListedCallMapsToItsPermission() {
        assertEquals("ui.slot.carmenu", ApiPermissions.required("mibsdk/api/Ui", "carMenuRow", "(x)V"));
        assertEquals("ui.slot.statusbar", ApiPermissions.required("mibsdk/api/Ui", "statusItem", "()V"));
        assertEquals("ui.slot.statusbar", ApiPermissions.required("mibsdk/api/Ui", "toast", "()V"));
        assertEquals("network", ApiPermissions.required("mibsdk/api/PluginContext", "net", "()V"));
        assertEquals("vehicle.read", ApiPermissions.required("mibsdk/api/PluginContext", "vehicle", "()V"));
        assertEquals("vehicle.parking.write",
                ApiPermissions.required("mibsdk/api/Parking", "overrideSoundDirections", "()V"));
        assertEquals("hooks.parking", ApiPermissions.required("mibsdk/api/PluginContext", "hooks", "()V"));
        assertEquals(null, ApiPermissions.required("mibsdk/api/PluginContext", "log", "()V"));
        assertEquals(null, ApiPermissions.required("fixture/Ui", "carMenuRow", "()V"));
    }

    private static final String OBSERVER_STUBS_EVENTS = "package mibsdk.api; public interface Events {"
            + " Object on(Class<?> type, Object listener); }";
    private static final String OBSERVER_STUB_HOOK = "package mibsdk.api; public final class HookObserved {}";
    private static final String OBSERVER_DECLARE = "needs \"hooks.parking\": declare it in plugin.json \"permissions\"";

    private File observerCaller(String body) throws Exception {
        return Fixtures.compileV48(tmp.resolve("observer"), Map.of("mibsdk.api.Events", OBSERVER_STUBS_EVENTS,
                "mibsdk.api.HookObserved", OBSERVER_STUB_HOOK, "mibsdk.api.Other", "package mibsdk.api; public final class Other {}",
                "fixture.O", "package fixture; import mibsdk.api.*; public class O { public void run(Events ev) { "
                        + body + " } }"));
    }

    @Test
    void hookObserverNeedsHooksParking() throws Exception {
        PermissionScan.Result r = PermissionScan.run(observerCaller("ev.on(HookObserved.class, null);"));
        assertEquals(Map.of("fixture/O.run -> Events.on(HookObserved)", "hooks.parking"), r.permissionSites());
        assertEquals(List.of("fixture/O.run -> Events.on(HookObserved) " + OBSERVER_DECLARE), r.undeclared(List.of()));
        assertEquals(List.of(), r.undeclared(List.of("hooks.parking")));
    }

    @Test
    void otherEventObserversNeedNothing() throws Exception {
        assertEquals(Map.of(), PermissionScan.run(observerCaller("ev.on(Other.class, null);")).permissionSites());
        assertEquals(Map.of(), PermissionScan.run(observerCaller("Object c = HookObserved.class;")).permissionSites(),
                "loading the class without registering for it");
    }

    @Test
    void hookObserverViaClassDollarHelperIsFound() throws Exception {
        // Older javac / Retrotranslator output: Events.on(class$("mibsdk.api.HookObserved"), ...) with a string constant.
        Path dir = tmp.resolve("legacy");
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_4, Opcodes.ACC_PUBLIC, "fixture/L", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "(Lmibsdk/api/Events;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitLdcInsn("mibsdk.api.HookObserved");
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "fixture/L", "class$", "(Ljava/lang/String;)Ljava/lang/Class;", false);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "mibsdk/api/Events", "on",
                "(Ljava/lang/Class;Ljava/lang/Object;)Ljava/lang/Object;", true);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(3, 1);
        mv.visitEnd();
        cw.visitEnd();
        write(dir.resolve("fixture/L.class"), cw.toByteArray());
        assertEquals(Map.of("fixture/L.run -> Events.on(HookObserved)", "hooks.parking"),
                PermissionScan.run(dir.toFile()).permissionSites());
    }

    private static final String STATUS_ICON_UI = "package mibsdk.api; public interface Ui {"
            + " Icon icon(String name); StatusItem statusItem(String text, Icon icon);"
            + " void toast(String text, Icon icon); }";
    private static final String STATUS_ICON_ITEM = "package mibsdk.api; public interface StatusItem {"
            + " void set(String text, Icon icon); }";

    private File statusIconCaller() throws Exception {
        return Fixtures.compileV48(tmp.resolve("statusIcon"), Map.of(
                "mibsdk.api.Icon", ICON_STUB,
                "mibsdk.api.StatusItem", STATUS_ICON_ITEM,
                "mibsdk.api.Ui", STATUS_ICON_UI,
                "fixture.S", "package fixture; import mibsdk.api.*; public class S {"
                        + " public void run(Ui ui, StatusItem item) {"
                        + " ui.statusItem(\"x\", ui.icon(\"cup\"));"
                        + " item.set(\"Y\", ui.icon(\"cup\"));"
                        + " ui.toast(\"hi\", ui.icon(\"cup\")); } }"));
    }

    @Test
    void statusIconWarningWhenNoSmallIcon() throws Exception {
        PermissionScan.Result r = PermissionScan.run(statusIconCaller());
        assertEquals(List.of("mibsdk.plugin: fixture/S.run passes an icon to the status bar, but no declared icon"
                + " is 20x20 or smaller; a larger one throws at runtime"), r.warnings(Map.of("cup", 72)));
    }

    @Test
    void noWarningWithA20pxIcon() throws Exception {
        PermissionScan.Result r = PermissionScan.run(statusIconCaller());
        assertEquals(List.of(), r.warnings(Map.of("cup", 72, "cup-20", 20)));
        assertEquals(List.of(), r.warnings(Map.of()), "a manifest with no icons has nothing to point at");
        assertEquals(List.of(), PermissionScan.run(carMenuCaller("ctx.ui();")).warnings(Map.of("cup", 72)),
                "no status-bar call site");
    }

    @Test
    void reportOnlyTaskLogsUndeclaredUseAndDoesNotFail() throws Exception {
        // The car-library default: failOnUndeclared is false, so undeclared use is reported, not fatal.
        org.gradle.api.Project project = org.gradle.testfixtures.ProjectBuilder.builder()
                .withProjectDir(tmp.resolve("proj").toFile()).build();
        PermissionScanTask task = project.getTasks().register("permissionScan", PermissionScanTask.class).get();
        task.getClasses().from(carMenuCaller("ctx.ui().carMenuRow(\"x\");"));
        task.getFailOnFindings().set(false);
        task.getFailOnUndeclared().convention(false);
        task.getDeclaredApi().set(PluginJson.MAX_API);
        File report = tmp.resolve("permissions.txt").toFile();
        task.getReport().set(report);

        task.scan();

        assertEquals("fixture/R.run -> Ui.carMenuRow " + CAR_MENU_DECLARE + "\n",
                new String(Files.readAllBytes(report.toPath()), java.nio.charset.StandardCharsets.UTF_8));

        task.getFailOnUndeclared().set(true);
        org.gradle.api.GradleException e = org.junit.jupiter.api.Assertions.assertThrows(
                org.gradle.api.GradleException.class, task::scan);
        org.junit.jupiter.api.Assertions.assertTrue(e.getMessage().contains("Ui.carMenuRow " + CAR_MENU_DECLARE), e.getMessage());
    }
}
