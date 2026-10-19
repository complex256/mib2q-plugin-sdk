package mibsdk.build;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Repairs a jxe2jar class file so a desktop JVM loads it (host tests only; never shipped to the car).
 *
 * <p>jxe2jar output has three defects HotSpot rejects: abstract and native methods carry an (empty) Code
 * attribute ({@code ClassFormatError: Code attribute in native or abstract methods}); {@code max_locals} and
 * {@code max_stack} are wrong ({@code Arguments can't fit into locals}); and value returns all use
 * {@code areturn} (e.g. {@code boolean isFront()}). The repair drops the Code of abstract/native methods,
 * recomputes maxs, and rewrites each return opcode to the one the method descriptor needs. Everything else
 * (names, descriptors, fields, constant values, the other instructions) is copied unchanged.
 */
final class HostLoadable {
    private HostLoadable() {}

    /** The repaired class file. Throws if ASM cannot parse the input (some jxe2jar JCL classes). */
    static byte[] repair(byte[] classFile) {
        ClassReader reader = new ClassReader(classFile);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        reader.accept(new Repair(writer), 0);
        return writer.toByteArray();
    }

    private static final class Repair extends ClassVisitor {
        Repair(ClassVisitor cv) {
            super(Opcodes.ASM9, cv);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            if ((access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                mv.visitEnd();
                return null; // skip the bogus body (and the method's annotations, which tests never need)
            }
            return new ReturnFix(mv, Type.getReturnType(descriptor).getOpcode(Opcodes.IRETURN));
        }
    }

    private static final class ReturnFix extends MethodVisitor {
        private final int returnOpcode;

        ReturnFix(MethodVisitor mv, int returnOpcode) {
            super(Opcodes.ASM9, mv);
            this.returnOpcode = returnOpcode;
        }

        @Override
        public void visitInsn(int opcode) {
            boolean isReturn = opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN;
            super.visitInsn(isReturn ? returnOpcode : opcode);
        }
    }
}
