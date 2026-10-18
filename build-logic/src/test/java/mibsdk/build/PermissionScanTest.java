package mibsdk.build;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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

        assertEquals(List.of(), PermissionScan.run(dir.toFile()).findings());
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
}
