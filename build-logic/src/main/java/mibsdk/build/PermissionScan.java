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
 * loading, {@code Thread.stop} and raw sockets (plugins use {@code ctx.net()}). Ignores the {@code class$} helper javac emits for class literals
 * and, when given, the Kotlin stdlib classes shrunk into a plugin (see {@link #run(File, Set)}). Also finds uses of API
 * that needs a newer {@code "api"} than 1 ({@link Result#needsApi()}).
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
            "java/lang/Thread.stop",
            "java/net/Socket.<init>",
            "java/net/ServerSocket.<init>",
            "java/net/URL.openConnection",
            "java/net/URL.openStream",
            "java/net/URLConnection.connect",
            "java/net/HttpURLConnection.connect",
            "java/net/DatagramSocket.<init>",
            "java/net/MulticastSocket.<init>",
            "java/net/InetAddress.getByName",
            "java/net/InetAddress.getAllByName",
            "javax/microedition/io/Connector.open");

    /** {@code Ui.icon(String, int)}, the colour overload: api 4. */
    private static final String UI = "mibsdk/api/Ui";
    private static final String ICON_COLOUR = "icon";
    private static final String ICON_COLOUR_DESC = "(Ljava/lang/String;I)Lmibsdk/api/Icon;";
    private static final int ICON_COLOUR_API = 4;

    /** Outcome of a permission scan. */
    public static final class Result {
        private final List<String> findings;
        private final List<String> apiFindings;

        Result(List<String> findings, List<String> apiFindings) {
            this.findings = Collections.unmodifiableList(findings);
            this.apiFindings = Collections.unmodifiableList(apiFindings);
        }

        /** Sorted {@code owner.method -> java/lang/reflect/Method.invoke} lines. */
        public List<String> findings() {
            return findings;
        }

        /** Sorted {@code owner.method -> Ui.icon(String,int)} lines: the sites that raise {@link #needsApi()}. */
        public List<String> apiFindings() {
            return apiFindings;
        }

        /** The lowest {@code "api"} these classes need: 4 if any calls {@code Ui.icon(String, int)}, else 1. */
        public int needsApi() {
            return apiFindings.isEmpty() ? 1 : ICON_COLOUR_API;
        }
    }

    public static Result run(File dirOrJar) throws IOException {
        return run(dirOrJar, Collections.<String>emptySet());
    }

    /**
     * As {@link #run(File)}, skipping the {@code kotlin/} classes named in {@code exemptKotlinStdlib} (internal
     * names of the kotlin-stdlib classes the plugin did not declare itself). Any other {@code kotlin/} class,
     * such as one from the plugin's own sources, is scanned like the rest.
     */
    public static Result run(File dirOrJar, Set<String> exemptKotlinStdlib) throws IOException {
        Set<String> findings = new TreeSet<String>();
        Set<String> apiFindings = new TreeSet<String>();
        ClassSource.forEach(dirOrJar, e -> {
            if (e.name.startsWith("kotlin/") && exemptKotlinStdlib.contains(e.name)) {
                return;
            }
            new ClassReader(e.bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int acc, String name, String desc, String sig, String[] ex) {
                    boolean classLiteralHelper = name.equals("class$") && (acc & Opcodes.ACC_STATIC) != 0;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int op, String owner, String n, String d, boolean itf) {
                            if (owner.equals(UI) && n.equals(ICON_COLOUR) && d.equals(ICON_COLOUR_DESC)) {
                                apiFindings.add(e.name + "." + name + " -> Ui.icon(String,int)");
                            }
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
        return new Result(new ArrayList<String>(findings), new ArrayList<String>(apiFindings));
    }
}
