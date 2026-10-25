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
        assertEquals(List.of("fixture/C.status -> Ui.icon(String,int)"), r.apiFindings());
        assertEquals(List.of(), r.findings(), "not a permission finding");

        File plain = Fixtures.compileV48(tmp.resolve("plain"), Map.of("mibsdk.api.Icon", ICON_STUB,
                "mibsdk.api.Ui", UI_STUB, "fixture.T",
                "package fixture; import mibsdk.api.*; public class T {"
                        + " public Icon row(Ui ui) { return ui.icon(\"x\"); } }"));
        assertEquals(1, PermissionScan.run(plain).needsApi());
        assertEquals(List.of(), PermissionScan.run(plain).apiFindings());
    }
}
