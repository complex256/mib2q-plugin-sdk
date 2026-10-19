package mibsdk.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class HostLoadableTest {

    /** Defines classes from bytes. */
    private static final class Loader extends ClassLoader {
        Loader() {
            super(HostLoadableTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] b) {
            return defineClass(name, b, 0, b.length);
        }
    }

    /** Like jxe2jar: an interface whose abstract method carries a Code attribute. */
    private static byte[] ifaceWithCode() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_6, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, "p/Iface", null,
                "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "run", "()V", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.NOP);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Like jxe2jar: locals=0 and {@code areturn} for a boolean getter; dead nops after the return. */
    private static byte[] structWithBadBodies() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_6, Opcodes.ACC_PUBLIC, "p/Struct", null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PUBLIC, "front", "Z", null, null).visitEnd();
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(Z)V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitVarInsn(Opcodes.ILOAD, 1);
        init.visitFieldInsn(Opcodes.PUTFIELD, "p/Struct", "front", "Z");
        init.visitInsn(Opcodes.RETURN);
        init.visitInsn(Opcodes.NOP);
        init.visitMaxs(2, 0);
        init.visitEnd();
        MethodVisitor get = cw.visitMethod(Opcodes.ACC_PUBLIC, "isFront", "()Z", null, null);
        get.visitCode();
        get.visitVarInsn(Opcodes.ALOAD, 0);
        get.visitFieldInsn(Opcodes.GETFIELD, "p/Struct", "front", "Z");
        get.visitInsn(Opcodes.ARETURN);
        get.visitInsn(Opcodes.NOP);
        get.visitMaxs(1, 0);
        get.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    void rawJxe2jarShapesDoNotLoad() {
        assertThrows(ClassFormatError.class, () -> new Loader().define("p.Iface", ifaceWithCode()));
        assertThrows(ClassFormatError.class, () -> new Loader().define("p.Struct", structWithBadBodies()));
    }

    @Test
    void repairedClassesLoadAndRun() throws Exception {
        Loader l = new Loader();
        Class<?> iface = l.define("p.Iface", HostLoadable.repair(ifaceWithCode()));
        assertEquals(1, iface.getMethods().length);
        Class<?> struct = l.define("p.Struct", HostLoadable.repair(structWithBadBodies()));
        Object o = struct.getConstructor(boolean.class).newInstance(true);
        Method isFront = struct.getMethod("isFront");
        assertEquals(Boolean.TRUE, isFront.invoke(o));
    }
}
