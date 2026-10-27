package mibsdk.build;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Verifies that every class, method and field a class set references exists on the car: in the car JCL
 * (and the other classpath entries). Checks call sites, field refs, type refs, LDC class constants,
 * catch types and class headers (superclass and interfaces), resolving members up the super/interface chain.
 *
 * <p>Jars passed as {@code transitiveLibs} (the Retrotranslator runtime) are followed: the body of every
 * library method reachable from the checked code is checked too, so a gap deep inside the library is
 * attributed ("via lib/L.f()V") to the plugin call that reaches it.
 */
public final class LinkCheck {
    private LinkCheck() {}

    /** Outcome of a link check. */
    public static final class Result {
        private final List<String> missing;

        Result(List<String> missing) {
            this.missing = Collections.unmodifiableList(missing);
        }

        /** Sorted lines such as {@code class java/lang/AutoCloseable (interface of a/B)}; empty means linkable. */
        public List<String> missing() {
            return missing;
        }
    }

    public static Result run(File checkedDirOrJar, List<File> classpath, List<File> transitiveLibs) throws IOException {
        Checker c = new Checker();
        // Transitive libs first: indexing is first-wins, and only these keep their bodies. A jar listed in
        // both lists must not lose its bodies to the plain classpath entry.
        for (File f : transitiveLibs) {
            c.index(f, true);
        }
        for (File f : classpath) {
            c.index(f, false);
        }
        c.index(checkedDirOrJar, false);
        c.checkAll(checkedDirOrJar);
        return new Result(new ArrayList<String>(c.missing));
    }

    private static final class ClassInfo {
        final String superName;
        final String[] interfaces;
        final boolean isInterface;
        final Set<String> members = new HashSet<String>();
        /** Class bytes; kept only for transitive libs, whose method bodies get followed. */
        byte[] body;

        ClassInfo(String superName, String[] interfaces, boolean isInterface) {
            this.superName = superName;
            this.interfaces = interfaces == null ? new String[0] : interfaces;
            this.isInterface = isInterface;
        }
    }

    private static final class Checker {
        final Map<String, ClassInfo> index = new HashMap<String, ClassInfo>();
        final Set<String> knownUnreadable = loadKnownUnreadable();
        final Set<String> missing = new TreeSet<String>();
        final Set<String> unreadable = new HashSet<String>();
        final Set<String> followed = new HashSet<String>();
        final Set<String> libClassesChecked = new HashSet<String>();
        final Deque<String[]> work = new ArrayDeque<String[]>();

        void index(File dirOrJar, boolean keepBody) throws IOException {
            List<File> sigs = CarJclSignatures.signatureFiles(dirOrJar);
            if (!sigs.isEmpty()) {
                for (File f : sigs) {
                    try (InputStream in = new FileInputStream(f)) {
                        CarJclSignatures.readInto(in, new CarJclSignatures.SignatureSink() {
                            @Override
                            public void klass(String name, String su, String[] is, boolean itf, Set<String> members) {
                                if (!index.containsKey(name)) {
                                    ClassInfo info = new ClassInfo(su, is, itf);
                                    info.members.addAll(members);
                                    index.put(name, info);
                                }
                            }

                            @Override
                            public void unreadable(String name) {
                                unreadable.add(name);
                                knownUnreadable.add(name); // the list says the car has it: present, members unknown
                            }
                        });
                    }
                }
                return;
            }
            ClassSource.forEach(dirOrJar, e -> {
                if (index.containsKey(e.name)) {
                    return;
                }
                CarJclSignatures.Described d;
                try {
                    d = CarJclSignatures.describe(e.bytes);
                } catch (RuntimeException ex) {
                    unreadable.add(e.name); // e.g. jxe2jar's java/lang/Double; absent unless listed as known
                    return;
                }
                ClassInfo info = new ClassInfo(d.superName, d.interfaces, d.isInterface);
                info.members.addAll(d.members());
                if (keepBody) {
                    info.body = e.bytes;
                }
                index.put(e.name, info);
            });
        }

        void checkAll(File checked) throws IOException {
            ClassSource.forEach(checked, e -> {
                ClassReader cr = new ClassReader(e.bytes);
                cr.accept(new CheckingClassVisitor(), ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            });
            while (!work.isEmpty()) {
                String[] w = work.poll(); // {owner, name+desc}
                followMethod(w[0], w[1]);
            }
        }

        boolean present(String cls) {
            return index.containsKey(cls) || knownUnreadable.contains(cls);
        }

        /** Declaring class of member {@code m} found from {@code cls} upwards; null if none, "?" if unknowable. */
        String resolve(String cls, String m) {
            Deque<String> q = new ArrayDeque<String>();
            q.add(cls);
            Set<String> seen = new HashSet<String>();
            while (!q.isEmpty()) {
                String c = q.poll();
                if (!seen.add(c)) {
                    continue;
                }
                if (knownUnreadable.contains(c)) {
                    return "?";
                }
                ClassInfo i = index.get(c);
                if (i == null) {
                    continue;
                }
                if (i.members.contains(m)) {
                    return c;
                }
                if (i.superName != null) {
                    q.add(i.superName);
                }
                q.addAll(Arrays.asList(i.interfaces));
            }
            return null;
        }

        /** Checks one reference found in checked code ({@code via == null}) or in a followed lib method. */
        void need(String owner, String member, String via) {
            if (owner.startsWith("[")) {
                int i = 0;
                while (owner.charAt(i) == '[') {
                    i++;
                }
                if (owner.charAt(i) != 'L' || member != null) {
                    return; // primitive array, or a method on an array (clone, length)
                }
                owner = owner.substring(i + 1, owner.length() - 1);
            }
            String suffix = via == null ? "" : " (via " + via + ")";
            if (!present(owner)) {
                missing.add("class " + owner + suffix);
                return;
            }
            if (member == null) {
                reachClass(owner);
                return;
            }
            String declaring = resolve(owner, member);
            if (declaring == null) {
                missing.add("member " + owner + "." + member + suffix);
            } else if (!declaring.equals("?")) {
                reachClass(declaring);
                if (index.get(declaring).body != null && member.indexOf('(') >= 0) {
                    follow(declaring, member);
                }
            }
        }

        void follow(String owner, String method) {
            if (followed.add(owner + "." + method)) {
                work.add(new String[] {owner, method});
            }
        }

        /** A transitive-lib class is reached: its header is checked and its static initializer followed. */
        void reachClass(String cls) {
            ClassInfo info = index.get(cls);
            if (info == null || info.body == null || !libClassesChecked.add(cls)) {
                return;
            }
            if (info.superName != null) {
                needHeader(info.superName, "superclass of " + cls);
            }
            for (String i : info.interfaces) {
                needHeader(i, "interface of " + cls);
            }
            follow(cls, "<clinit>()V");
        }

        /** Header check of a reached lib class: the label replaces the "via" suffix. */
        void needHeader(String cls, String headerOf) {
            if (!present(cls)) {
                missing.add("class " + cls + " (" + headerOf + ")");
            } else {
                reachClass(cls);
            }
        }

        void followMethod(String owner, String method) {
            ClassInfo info = index.get(owner);
            if (info == null || info.body == null) {
                return;
            }
            String via = owner + "." + method;
            new ClassReader(info.body).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int acc, String n, String d, String sig, String[] ex) {
                    return (n + d).equals(method) ? new CheckingMethodVisitor(via) : null;
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }

        final class CheckingClassVisitor extends ClassVisitor {
            CheckingClassVisitor() {
                super(Opcodes.ASM9);
            }

            @Override
            public void visit(int v, int acc, String n, String sig, String su, String[] is) {
                if (su != null && !present(su)) {
                    missing.add("class " + su + " (superclass of " + n + ")");
                }
                if (is != null) {
                    for (String i : is) {
                        if (!present(i)) {
                            missing.add("class " + i + " (interface of " + n + ")");
                        }
                    }
                }
            }

            @Override
            public MethodVisitor visitMethod(int acc, String n, String d, String sig, String[] ex) {
                return new CheckingMethodVisitor(null);
            }
        }

        final class CheckingMethodVisitor extends MethodVisitor {
            private final String via;

            CheckingMethodVisitor(String via) {
                super(Opcodes.ASM9);
                this.via = via;
            }

            @Override
            public void visitTryCatchBlock(Label s, Label e, Label h, String type) {
                if (type != null) {
                    need(type, null, via);
                }
            }

            @Override
            public void visitMethodInsn(int op, String o, String n, String d, boolean itf) {
                need(o, n + d, via);
            }

            @Override
            public void visitFieldInsn(int op, String o, String n, String d) {
                need(o, n + ":" + d, via);
            }

            @Override
            public void visitTypeInsn(int op, String type) {
                need(type, null, via);
            }

            @Override
            public void visitMultiANewArrayInsn(String desc, int dims) {
                need(desc, null, via);
            }

            @Override
            public void visitLdcInsn(Object value) {
                if (value instanceof Type) {
                    Type t = (Type) value;
                    if (t.getSort() == Type.OBJECT) {
                        need(t.getInternalName(), null, via);
                    } else if (t.getSort() == Type.ARRAY) {
                        need(t.getDescriptor(), null, via);
                    }
                }
            }
        }

        static Set<String> loadKnownUnreadable() {
            Set<String> names = new HashSet<String>();
            try (InputStream in = LinkCheck.class.getResourceAsStream("/mibsdk/linkcheck-known-unreadable.txt")) {
                if (in != null) {
                    for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                        String t = line.trim();
                        if (!t.isEmpty() && !t.startsWith("#")) {
                            names.add(t);
                        }
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return names;
        }
    }
}
