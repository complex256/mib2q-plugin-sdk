import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
import jdk.internal.org.objectweb.asm.*;

// Throwaway spike: verify every class/method/field a jar's bytecode references exists in the given
// classpath dirs (the car's JCL + our own output). Walks superclasses/interfaces for members.
// usage: java --add-exports java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED LinkCheck.java <checkDir> <cpDir>...
public class LinkCheck {
    record Info(String sup, String[] ifaces, Set<String> members) {}
    static final Map<String, Info> index = new HashMap<>();

    public static void main(String[] a) throws Exception {
        for (int i = 0; i < a.length; i++) load(Paths.get(a[i]));
        Set<String> missing = new TreeSet<>();
        try (Stream<Path> s = Files.walk(Paths.get(a[0]))) {
            for (Path p : s.filter(f -> f.toString().endsWith(".class")).collect(Collectors.toList())) {
                String owner = p.toString();
                new ClassReader(Files.readAllBytes(p)).accept(new ClassVisitor(Opcodes.ASM8) {
                    // class header: superclass + implemented interfaces must exist too (e.g. AutoCloseable)
                    public void visit(int v, int acc, String n, String sig, String su, String[] is) {
                        if (su != null && !index.containsKey(su)) missing.add("class  " + su + "  (superclass of " + n + ")");
                        if (is != null) for (String i : is) if (!index.containsKey(i)) missing.add("class  " + i + "  (interface of " + n + ")");
                    }
                    public MethodVisitor visitMethod(int acc, String n, String d, String sig, String[] ex) {
                        return new MethodVisitor(Opcodes.ASM8) {
                            public void visitTryCatchBlock(Label s0, Label e0, Label h, String t) { if (t != null) need(t, null); }
                            public void visitMethodInsn(int op, String o, String n2, String d2, boolean itf) { need(o, n2 + d2); }
                            public void visitFieldInsn(int op, String o, String n2, String d2) { need(o, n2 + ":" + d2); }
                            public void visitTypeInsn(int op, String t) { if (!t.startsWith("[")) need(t, null); }
                            public void visitLdcInsn(Object v) { if (v instanceof Type t && t.getSort() == Type.OBJECT) need(t.getInternalName(), null); }
                            void need(String o, String m) {
                                if (o.startsWith("[")) return;
                                if (!index.containsKey(o)) missing.add("class  " + o);
                                else if (m != null && !has(o, m)) missing.add("member " + o + "." + m);
                            }
                        };
                    }
                }, 0);
            }
        }
        missing.forEach(System.out::println);
        System.out.println("LINKCHECK missing=" + missing.size());
        System.exit(missing.isEmpty() ? 0 : 1);
    }

    static boolean has(String cls, String m) {
        Deque<String> q = new ArrayDeque<>(List.of(cls));
        Set<String> seen = new HashSet<>();
        while (!q.isEmpty()) {
            String c = q.pop();
            if (!seen.add(c)) continue;
            Info i = index.get(c);
            if (i == null) continue;
            if (i.members.contains(m)) return true;
            if (i.sup != null) q.add(i.sup);
            q.addAll(Arrays.asList(i.ifaces));
        }
        return false;
    }

    static void load(Path root) throws IOException {
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.filter(f -> f.toString().endsWith(".class")).collect(Collectors.toList())) {
                Set<String> mem = new HashSet<>();
                String[] hdr = new String[1]; String[] sup = new String[1]; String[][] ifs = new String[1][];
                ClassReader cr;
                try { cr = new ClassReader(Files.readAllBytes(p)); } catch (IllegalArgumentException e) { System.out.println("unreadable " + p); continue; }
                cr.accept(new ClassVisitor(Opcodes.ASM8) {
                    public void visit(int v, int acc, String n, String sig, String su, String[] is) { hdr[0] = n; sup[0] = su; ifs[0] = is == null ? new String[0] : is; }
                    public MethodVisitor visitMethod(int acc, String n, String d, String sig, String[] ex) { mem.add(n + d); return null; }
                    public FieldVisitor visitField(int acc, String n, String d, String sig, Object v) { mem.add(n + ":" + d); return null; }
                }, ClassReader.SKIP_CODE);
                index.putIfAbsent(hdr[0], new Info(sup[0], ifs[0], mem));
            }
        }
    }
}
