package mibsdk.build;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * The car JCL as a text signature list: class names, supertypes, interface flags and member names with
 * descriptors, never bytecode. {@link LinkCheck} indexes it in place of the proprietary class library.
 *
 * <pre>
 * # mibsdk car JCL signatures v1
 * # source: &lt;label&gt;
 * C java/lang/Object - - 0          class: name, super (-: none), interfaces (comma-joined, -: none), 1 if interface
 * M hashCode()I                     method: name + descriptor
 * F out:Ljava/io/PrintStream;       field: name : descriptor
 * U java/lang/Double                 a class ASM could not read; present, members unknown
 * </pre>
 *
 * jxe2jar gives java/lang/Object an empty super class name; that is written as {@code -}.
 * UTF-8, LF line ends; classes sorted by name, then the unreadable names; per class methods then fields, each sorted.
 */
final class CarJclSignatures {
    /** File name suffix of a signature list. */
    public static final String SUFFIX = ".sigs";

    private static final String HEADER = "# mibsdk car JCL signatures v1";
    private static final String NONE = "-";

    private CarJclSignatures() {}

    /** Receives what {@link #readInto} (and {@link #describe}'s callers) find. */
    public interface SignatureSink {
        /** {@code members} holds {@code name+desc} for methods and {@code name:desc} for fields. */
        void klass(String name, String superName, String[] interfaces, boolean isInterface, Set<String> members);

        /** A class that exists but whose members are unknown. */
        void unreadable(String name);
    }

    /** One class header and member list, read by ASM without code. */
    static final class Described {
        final String superName;
        final String[] interfaces;
        final boolean isInterface;
        final Set<String> methods = new TreeSet<String>();
        final Set<String> fields = new TreeSet<String>();

        Described(String superName, String[] interfaces, boolean isInterface) {
            this.superName = superName;
            this.interfaces = interfaces == null ? new String[0] : interfaces;
            this.isInterface = isInterface;
        }

        /** The member keys {@link LinkCheck} resolves against: {@code name+desc} and {@code name:desc}. */
        Set<String> members() {
            Set<String> all = new HashSet<String>(methods);
            all.addAll(fields);
            return all;
        }
    }

    /** Reads one class file; throws a RuntimeException if ASM cannot parse it. */
    static Described describe(byte[] classBytes) {
        Described[] out = new Described[1];
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(int v, int acc, String n, String sig, String su, String[] is) {
                out[0] = new Described(su, is, (acc & Opcodes.ACC_INTERFACE) != 0);
            }

            @Override
            public MethodVisitor visitMethod(int acc, String n, String d, String sig, String[] ex) {
                out[0].methods.add(n + d);
                return null;
            }

            @Override
            public FieldVisitor visitField(int acc, String n, String d, String sig, Object v) {
                out[0].fields.add(n + ":" + d);
                return null;
            }
        }, ClassReader.SKIP_CODE);
        return out[0];
    }

    /** Writes the {@code java/**} and {@code javax/**} classes of a directory or jar as a signature list. */
    public static void write(File dirOrJar, String sourceLabel, Writer out) throws IOException {
        Map<String, Described> classes = new TreeMap<String, Described>();
        Set<String> unreadable = new TreeSet<String>();
        ClassSource.forEach(dirOrJar, e -> {
            if (!(e.name.startsWith("java/") || e.name.startsWith("javax/")) || classes.containsKey(e.name)) {
                return;
            }
            try {
                classes.put(e.name, describe(e.bytes));
            } catch (RuntimeException ex) {
                unreadable.add(e.name);
            }
        });
        out.write(HEADER + "\n");
        out.write("# source: " + sourceLabel.replaceAll("[\r\n]+", " ") + "\n");
        for (Map.Entry<String, Described> c : classes.entrySet()) {
            Described d = c.getValue();
            out.write("C " + c.getKey() + " " + (d.superName == null || d.superName.isEmpty() ? NONE : d.superName) + " "
                    + (d.interfaces.length == 0 ? NONE : String.join(",", d.interfaces)) + " "
                    + (d.isInterface ? "1" : "0") + "\n");
            for (String m : d.methods) {
                out.write("M " + m + "\n");
            }
            for (String f : d.fields) {
                out.write("F " + f + "\n");
            }
        }
        for (String u : unreadable) {
            if (!classes.containsKey(u)) {
                out.write("U " + u + "\n");
            }
        }
        out.flush();
    }

    /** Reads a signature list, calling {@code sink} once per class (members complete) and per unreadable name. */
    public static void readInto(InputStream in, SignatureSink sink) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String name = null;
        String superName = null;
        String[] interfaces = null;
        boolean isInterface = false;
        Set<String> members = new HashSet<String>();
        String line;
        while ((line = r.readLine()) != null) {
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String rest = line.length() > 2 ? line.substring(2) : "";
            switch (line.charAt(0)) {
                case 'C': {
                    flush(sink, name, superName, interfaces, isInterface, members);
                    String[] f = rest.split(" ");
                    if (f.length != 4) {
                        throw new IOException("bad class line: " + line);
                    }
                    name = f[0];
                    superName = f[1].equals(NONE) ? null : f[1];
                    interfaces = f[2].equals(NONE) ? new String[0] : f[2].split(",");
                    isInterface = f[3].equals("1");
                    members = new HashSet<String>();
                    break;
                }
                case 'M':
                case 'F':
                    if (name == null) {
                        throw new IOException("member before any class: " + line);
                    }
                    members.add(rest);
                    break;
                case 'U':
                    flush(sink, name, superName, interfaces, isInterface, members);
                    name = null;
                    sink.unreadable(rest);
                    break;
                default:
                    throw new IOException("bad signature line: " + line);
            }
        }
        flush(sink, name, superName, interfaces, isInterface, members);
    }

    private static void flush(SignatureSink sink, String name, String superName, String[] interfaces,
            boolean isInterface, Set<String> members) {
        if (name != null) {
            sink.klass(name, superName, interfaces, isInterface, members);
        }
    }

    /** The {@code *.sigs} files a classpath entry stands for: itself, or those at the top of a directory; else none. */
    static List<File> signatureFiles(File entry) {
        List<File> found = new ArrayList<File>();
        if (entry.isFile() && entry.getName().endsWith(SUFFIX)) {
            found.add(entry);
        } else if (entry.isDirectory()) {
            File[] kids = entry.listFiles((d, n) -> n.endsWith(SUFFIX));
            if (kids != null) {
                for (File k : kids) {
                    if (k.isFile()) {
                        found.add(k);
                    }
                }
                found.sort(null);
            }
        }
        return found;
    }
}
