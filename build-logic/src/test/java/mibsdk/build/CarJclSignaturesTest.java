package mibsdk.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class CarJclSignaturesTest {
    @TempDir Path tmp;

    /** One class as a sink or an ASM pass sees it, in a comparable form. */
    private static String describe(String name, String superName, String[] interfaces, boolean isInterface, Set<String> members) {
        return name + " super=" + superName + " ifaces=" + Arrays.toString(interfaces)
                + " interface=" + isInterface + " members=" + new TreeSet<>(members);
    }

    private static final class Recorder implements CarJclSignatures.SignatureSink {
        final Map<String, String> classes = new TreeMap<>();
        final Set<String> unreadable = new TreeSet<>();

        @Override
        public void klass(String name, String superName, String[] interfaces, boolean isInterface, Set<String> members) {
            classes.put(name, describe(name, superName, interfaces, isInterface, members));
        }

        @Override
        public void unreadable(String name) {
            unreadable.add(name);
        }
    }

    private static String sigs(File src, String label) throws Exception {
        StringWriter w = new StringWriter();
        CarJclSignatures.write(src, label, w);
        return w.toString();
    }

    @Test
    void roundTripsAFixtureDirectory() throws Exception {
        File jcl = Fixtures.stubJcl(tmp.resolve("jcl"));
        String text = sigs(jcl, "TEST");

        Recorder read = new Recorder();
        CarJclSignatures.readInto(new java.io.ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), read);

        Recorder asm = new Recorder();
        ClassSource.forEach(jcl, e -> {
            String[] header = new String[3]; // super, interfaces joined, interface flag
            Set<String> members = new TreeSet<>();
            String[][] ifaces = new String[1][];
            boolean[] isItf = new boolean[1];
            String[] sup = new String[1];
            try {
                new ClassReader(e.bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public void visit(int v, int acc, String n, String sig, String su, String[] is) {
                        sup[0] = su;
                        ifaces[0] = is == null ? new String[0] : is;
                        isItf[0] = (acc & Opcodes.ACC_INTERFACE) != 0;
                    }

                    @Override
                    public MethodVisitor visitMethod(int acc, String n, String d, String sig, String[] ex) {
                        members.add(n + d);
                        return null;
                    }

                    @Override
                    public FieldVisitor visitField(int acc, String n, String d, String sig, Object v) {
                        members.add(n + ":" + d);
                        return null;
                    }
                }, ClassReader.SKIP_CODE);
            } catch (RuntimeException ex) {
                asm.unreadable(e.name);
                return;
            }
            asm.klass(e.name, sup[0], ifaces[0], isItf[0], members);
        });

        assertTrue(asm.classes.size() >= 7, asm.classes.toString());
        assertEquals(asm.classes, read.classes);
        assertEquals(asm.unreadable, read.unreadable);
        assertEquals(new TreeSet<>(List.of("java/lang/Double")), read.unreadable);

        assertEquals(text, sigs(jcl, "TEST"), "write is deterministic");
    }

    @Test
    void headerNamesTheSource() throws Exception {
        String[] lines = sigs(Fixtures.stubJcl(tmp.resolve("jcl")), "TEST").split("\n");
        assertEquals("# mibsdk car JCL signatures v1", lines[0]);
        assertEquals("# source: TEST", lines[1]);
    }

    @Test
    void writesTheDocumentedLineShapes() throws Exception {
        String text = sigs(Fixtures.stubJcl(tmp.resolve("jcl")), "TEST");
        assertTrue(text.contains("C java/lang/Object - - 0\nM <init>()V\nM hashCode()I\nM toString()Ljava/lang/String;\n"), text);
        assertTrue(text.contains("C java/util/Map java/lang/Object - 1\n"), text);
        assertTrue(text.endsWith("U java/lang/Double\n"), text);
    }

    @Test
    void indexesOnlyJavaAndJavaxClasses() throws Exception {
        File jcl = Fixtures.stubJcl(tmp.resolve("jcl"));
        File extra = Fixtures.compileV48(tmp.resolve("extra"), Map.of("fixture.Extra", "package fixture; public class Extra { }"));
        Files.createDirectories(jcl.toPath().resolve("fixture"));
        Files.copy(extra.toPath().resolve("fixture/Extra.class"), jcl.toPath().resolve("fixture/Extra.class"));
        String text = sigs(jcl, "TEST");
        assertTrue(text.contains("C java/lang/Object "), text);
        assertTrue(!text.contains("fixture/Extra"), text);
        // A jar takes the same path as a directory.
        assertEquals(text, sigs(Fixtures.jar(tmp.resolve("jcl.jar"), jcl), "TEST"));
    }

    @Test
    void readsFieldsInterfacesAndIgnoresCommentsAndBlankLines() throws Exception {
        String text = "# comment\n\nC a/B a/Super x/I,y/J 0\nM m()V\nF f:I\n\nC a/Super - - 0\nU a/Broken\n";
        Recorder r = new Recorder();
        CarJclSignatures.readInto(new java.io.ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), r);
        assertEquals("a/B super=a/Super ifaces=[x/I, y/J] interface=false members=[f:I, m()V]", r.classes.get("a/B"));
        assertEquals("a/Super super=null ifaces=[] interface=false members=[]", r.classes.get("a/Super"));
        assertEquals(new TreeSet<>(List.of("a/Broken")), r.unreadable);
        assertEquals(new ArrayList<>(List.of("a/B", "a/Super")), new ArrayList<>(r.classes.keySet()));
    }
}
