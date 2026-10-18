package mibsdk.build;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Finds calls that need {@code platform.unrestricted}: reflection, process execution, VM exit, native
 * loading and {@code Thread.stop}. Ignores the {@code class$} helper javac emits for class literals
 * and the Kotlin stdlib (classes under {@code kotlin/}).
 */
public final class PermissionScan {
    private PermissionScan() {}

    /** Calls that require the grant, as {@code owner.name}. */
    private static final Set<String> RESTRICTED = Set.of(
            "java/lang/reflect/Method.invoke",
            "java/lang/reflect/Constructor.newInstance",
            "java/lang/Class.forName",
            "java/lang/Class.newInstance",
            "java/lang/Runtime.exec",
            "java/lang/Runtime.halt",
            "java/lang/Runtime.load",
            "java/lang/Runtime.loadLibrary",
            "java/lang/System.exit",
            "java/lang/System.load",
            "java/lang/System.loadLibrary",
            "java/lang/Thread.stop");

    /** Outcome of a permission scan. */
    public static final class Result {
        private final List<String> findings;

        Result(List<String> findings) {
            this.findings = Collections.unmodifiableList(findings);
        }

        /** Sorted {@code owner.method -> java/lang/reflect/Method.invoke} lines. */
        public List<String> findings() {
            return findings;
        }
    }

    public static Result run(File dirOrJar) throws IOException {
        Set<String> findings = new TreeSet<String>();
        ClassSource.forEach(dirOrJar, e -> {
            if (e.name.startsWith("kotlin/")) {
                return;
            }
            new ClassReader(e.bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int acc, String name, String desc, String sig, String[] ex) {
                    boolean classLiteralHelper = name.equals("class$") && (acc & Opcodes.ACC_STATIC) != 0;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int op, String owner, String n, String d, boolean itf) {
                            String target = owner + "." + n;
                            if (!RESTRICTED.contains(target)) {
                                return;
                            }
                            if (classLiteralHelper && target.equals("java/lang/Class.forName")) {
                                return;
                            }
                            findings.add(e.name + "." + name + " -> " + target);
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        });
        return new Result(new ArrayList<String>(findings));
    }
}
