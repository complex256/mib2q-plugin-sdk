package mibsdk.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginJarCacheTest {

    @TempDir
    Path tmp;

    /** Compiles {@code package p; public class V { public String toString() { return "<tag>"; } }} into a jar at {@code jar}. */
    private void writeJar(File jar, String tag) throws Exception {
        String src = "package p; public class V { public String toString() { return \"" + tag + "\"; } }";
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Path classes = Files.createTempDirectory(tmp, "classes");
        JavaFileObject file = new SimpleJavaFileObject(URI.create("string:///p/V.java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return src;
            }
        };
        Boolean ok = compiler.getTask(null, null, null,
                java.util.Arrays.asList("-d", classes.toString()), null, Collections.singletonList(file)).call();
        assertTrue(ok, "fixture compile failed");
        byte[] bytes = Files.readAllBytes(classes.resolve("p").resolve("V.class"));
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(jar))) {
            out.putNextEntry(new JarEntry("p/V.class"));
            out.write(bytes);
            out.closeEntry();
        }
    }

    private static String loadAndDescribe(File jarCopy) throws Exception {
        ClassLoader cl = PluginClassLoaders.create(jarCopy, new FilteringParentLoader(
                PluginJarCacheTest.class.getClassLoader(), RuntimeConstants.PLUGIN_PARENT_PREFIXES));
        return cl.loadClass("p.V").getDeclaredConstructor().newInstance().toString();
    }

    @Test
    void reloadAfterInPlaceOverwriteLoadsNewBytes() throws Exception {
        PluginJarCache cache = new PluginJarCache(tmp.resolve("cache").toFile());
        File plugin = tmp.resolve("demo.jar").toFile();

        writeJar(plugin, "v1");
        File snap1 = cache.snapshot(plugin);
        ClassLoader old = PluginClassLoaders.create(snap1, new FilteringParentLoader(
                PluginJarCacheTest.class.getClassLoader(), RuntimeConstants.PLUGIN_PARENT_PREFIXES));
        Object oldInstance = old.loadClass("p.V").getDeclaredConstructor().newInstance();

        // overwrite the SAME path
        writeJar(plugin, "v2");
        File snap2 = cache.snapshot(plugin);

        assertNotEquals(snap1, snap2);
        assertEquals("v2", loadAndDescribe(snap2));
        assertEquals("v1", oldInstance.toString());
        assertTrue(snap1.exists());
    }

    @Test
    void purgeRemovesOldSnapshots() throws Exception {
        File dir = tmp.resolve("cache").toFile();
        PluginJarCache cache = new PluginJarCache(dir);
        File plugin = tmp.resolve("demo.jar").toFile();
        writeJar(plugin, "v1");
        File a = cache.snapshot(plugin);
        File b = cache.snapshot(plugin);
        File c = cache.snapshot(plugin);

        Set<File> keep = new HashSet<>();
        keep.add(b);
        cache.purgeExcept(keep);

        assertFalse(a.exists());
        assertTrue(b.exists());
        assertFalse(c.exists());
    }

    @Test
    void snapshotUsesGivenBaseNameNotJarFileName() throws Exception {
        File jar = tmp.resolve("we#ird%name.jar").toFile();
        writeJar(jar, "x");
        File copy = new PluginJarCache(tmp.resolve("cache").toFile()).snapshot(jar, "plugin-id");
        assertTrue(copy.getName().startsWith("plugin-id-"), copy.getName());
        assertEquals("x", loadAndDescribe(copy));
    }
}
