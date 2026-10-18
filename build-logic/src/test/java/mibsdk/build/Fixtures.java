package mibsdk.build;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

/** Test helpers: tiny fake car JCL, in-test javac, and v48 rewriting by ASM. */
final class Fixtures {
    private Fixtures() {}

    /** A fake car JCL: only what the fixtures need. No AutoCloseable, no regex, no Map.merge. */
    static File stubJcl(Path dir) throws IOException {
        stub(dir, "java/lang/Object", null, false, "<init>()V", "hashCode()I", "toString()Ljava/lang/String;");
        stub(dir, "java/lang/String", "java/lang/Object", false, "<init>()V");
        stub(dir, "java/lang/StringBuffer", "java/lang/Object", false,
                "<init>()V", "append(Ljava/lang/String;)Ljava/lang/StringBuffer;");
        stub(dir, "java/lang/Throwable", "java/lang/Object", false, "<init>()V");
        stub(dir, "java/lang/Exception", "java/lang/Throwable", false, "<init>()V");
        stub(dir, "java/lang/RuntimeException", "java/lang/Exception", false, "<init>()V");
        stub(dir, "java/util/Map", "java/lang/Object", true,
                "put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
        // jxe2jar's Double.class is not parseable by ASM.
        Path dbl = dir.resolve("java/lang/Double.class");
        Files.createDirectories(dbl.getParent());
        Files.write(dbl, new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0, 0, 48, 0, 1});
        return dir.toFile();
    }

    private static void stub(Path dir, String name, String sup, boolean iface, String... members) throws IOException {
        ClassWriter cw = new ClassWriter(0);
        int acc = Opcodes.ACC_PUBLIC | (iface ? Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT : 0);
        cw.visit(Opcodes.V1_4, acc, name, null, sup, null);
        for (String m : members) {
            int p = m.indexOf('(');
            cw.visitMethod(Opcodes.ACC_PUBLIC | (iface ? Opcodes.ACC_ABSTRACT : Opcodes.ACC_NATIVE),
                    m.substring(0, p), m.substring(p), null, null).visitEnd();
        }
        cw.visitEnd();
        Path out = dir.resolve(name + ".class");
        Files.createDirectories(out.getParent());
        Files.write(out, cw.toByteArray());
    }

    /**
     * Compiles {@code sources} (fully qualified class name to source text) with {@code javac --release 8}
     * into {@code out}, then rewrites every class file as v48. Extra classpath entries are optional.
     */
    static File compileV48(Path out, Map<String, String> sources, File... classpath) throws IOException {
        Path src = Files.createTempDirectory(out.getParent(), "src");
        Files.createDirectories(out);
        List<String> args = new ArrayList<>(Arrays.asList("--release", "8", "-proc:none", "-nowarn", "-Xlint:-options", "-d", out.toString()));
        if (classpath.length > 0) {
            args.add("-classpath");
            args.add(Arrays.stream(classpath).map(File::getPath).collect(Collectors.joining(File.pathSeparator)));
        }
        for (Map.Entry<String, String> e : sources.entrySet()) {
            Path f = src.resolve(e.getKey().replace('.', '/') + ".java");
            Files.createDirectories(f.getParent());
            Files.write(f, e.getValue().getBytes(StandardCharsets.UTF_8));
            args.add(f.toString());
        }
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        if (javac.run(null, null, err, args.toArray(new String[0])) != 0) {
            throw new IllegalStateException("fixture does not compile:\n" + err);
        }
        try (Stream<Path> s = Files.walk(out)) {
            for (Path p : s.filter(f -> f.toString().endsWith(".class")).collect(Collectors.toList())) {
                ClassReader cr = new ClassReader(Files.readAllBytes(p));
                ClassWriter cw = new ClassWriter(0);
                cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
                    @Override
                    public void visit(int v, int acc, String n, String sig, String su, String[] is) {
                        super.visit(Opcodes.V1_4, acc, n, sig, su, is);
                    }
                }, ClassReader.SKIP_FRAMES);
                Files.write(p, cw.toByteArray());
            }
        }
        return out.toFile();
    }

    static File jar(Path jar, File classesDir) throws IOException {
        try (OutputStream o = Files.newOutputStream(jar);
             JarOutputStream j = new JarOutputStream(o);
             Stream<Path> s = Files.walk(classesDir.toPath())) {
            for (Path p : s.filter(Files::isRegularFile).collect(Collectors.toList())) {
                j.putNextEntry(new JarEntry(classesDir.toPath().relativize(p).toString().replace('\\', '/')));
                j.write(Files.readAllBytes(p));
                j.closeEntry();
            }
        }
        return jar.toFile();
    }
}
