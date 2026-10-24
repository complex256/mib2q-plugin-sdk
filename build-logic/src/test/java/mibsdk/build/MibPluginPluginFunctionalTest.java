package mibsdk.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * TestKit: {@code mibsdk.plugin} against the real compat and api modules. One temp root build maps the
 * repo's {@code compat}, {@code api} and {@code plugins/hello} in by path (outputs redirected under the temp
 * root, so the repo's own build dirs are untouched) next to throwaway plugins written here.
 */
class MibPluginPluginFunctionalTest {

    @TempDir(cleanup = CleanupMode.ON_SUCCESS) // kept on failure for inspection
    static Path root;

    private static File repo;

    @BeforeAll
    static void writeRootBuild() throws IOException {
        repo = new File(System.getProperty("mibsdk.repoRoot")).getCanonicalFile();
        String r = repo.getPath().replace('\\', '/');
        write("settings.gradle.kts", "rootProject.name = \"tk\"\n"
                + "include(\"compat\", \"api\", \"plugins:hello\", \"plugins:refl\", \"plugins:unrestricted\", \"plugins:kt\", \"plugins:api2\", \"plugins:api0\", \"plugins:api3\", \"plugins:apifrac\")\n"
                + "project(\":compat\").projectDir = file(\"" + r + "/compat\")\n"
                + "project(\":api\").projectDir = file(\"" + r + "/api\")\n"
                + "project(\":plugins:hello\").projectDir = file(\"" + r + "/plugins/hello\")\n"
                + "gradle.beforeProject {\n"
                + "    layout.buildDirectory.set(rootDir.resolve(\"out/\" + path.trim(':').replace(':', '_').ifEmpty { \"root\" }))\n"
                + "}\n");
        write("build.gradle.kts", "");
        write("gradle.properties", "kotlin.compiler.execution.strategy=in-process\norg.gradle.jvmargs=-Xmx1g\n");

        String reflBody = "package sample.refl;\n"
                + "import mibsdk.api.*;\n"
                + "public class ReflPlugin extends MibPlugin {\n"
                + "  protected void onEnable(PluginContext ctx) throws Exception { ctx.log().info(load(\"java.lang.Object\").getName()); }\n"
                + "  Class<?> load(String name) throws Exception { return Class.forName(name); }\n"
                + "}\n";
        javaPlugin("refl", "sample.refl.ReflPlugin", reflBody, "[]");
        javaPlugin("unrestricted", "sample.refl.ReflPlugin", reflBody, "[\"platform.unrestricted\"]");

        for (String[] a : new String[][] {{"api2", "2"}, {"api0", "0"}, {"api3", "3"}, {"apifrac", "1.5"}}) {
            javaPlugin(a[0], "sample.refl.ReflPlugin", "package sample.refl;\n"
                    + "import mibsdk.api.*;\n"
                    + "public class ReflPlugin extends MibPlugin {\n"
                    + "  protected void onEnable(PluginContext ctx) throws Exception { }\n"
                    + "}\n", "[]");
            write("plugins/" + a[0] + "/src/main/resources/plugin.json",
                    manifest(a[0], "sample.refl.ReflPlugin", "[]").replace("\"api\":1", "\"api\":" + a[1]));
        }

        write("plugins/kt/build.gradle.kts", "plugins {\n"
                + "    id(\"org.jetbrains.kotlin.jvm\") version \"2.0.21\"\n"
                + "    id(\"mibsdk.plugin\")\n"
                + "}\n");
        write("plugins/kt/src/main/resources/plugin.json", manifest("kt", "sample.kt.KtPlugin", "[]"));
        write("plugins/kt/src/main/kotlin/sample/kt/KtPlugin.kt", "package sample.kt\n"
                + "import mibsdk.api.IgnitionChanged\n"
                + "import mibsdk.api.MibPlugin\n"
                + "import mibsdk.api.PluginContext\n"
                + "class KtPlugin : MibPlugin() {\n"
                + "    override fun onEnable(ctx: PluginContext) {\n"
                + "        val doubled = listOf(1, 2).map { it * 2 }\n"
                + "        ctx.log().info(\"doubled=\" + doubled)\n"
                + "        ctx.events().on(IgnitionChanged::class.java) { e -> ctx.log().info(\"ignition=${e.isOn}\") }\n"
                + "    }\n"
                + "}\n");
    }

    private static void javaPlugin(String id, String main, String source, String permissions) throws IOException {
        write("plugins/" + id + "/build.gradle.kts", "plugins {\n    id(\"mibsdk.plugin\")\n}\n");
        write("plugins/" + id + "/src/main/resources/plugin.json", manifest(id, main, permissions));
        write("plugins/" + id + "/src/main/java/" + main.replace('.', '/') + ".java", source);
    }

    private static String manifest(String id, String main, String permissions) {
        return "{\"id\":\"" + id + "\",\"name\":\"" + id + "\",\"version\":\"1.0.0\",\"author\":\"complex256\","
                + "\"api\":1,\"main\":\"" + main + "\",\"permissions\":" + permissions + ",\"dependencies\":[]}\n";
    }

    private static void write(String rel, String text) throws IOException {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.write(p, text.getBytes(StandardCharsets.UTF_8));
    }

    private static GradleRunner runner(String... tasks) {
        List<String> args = new ArrayList<String>();
        Collections.addAll(args, tasks);
        args.add("--stacktrace");
        for (String prop : new String[] {"mibsdk.jdk8Home", "mibsdk.stockLsdJar"}) {
            String v = System.getProperty(prop);
            if (v != null && !v.isEmpty()) {
                args.add("-P" + prop + "=" + v);
            }
        }
        return GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath().withArguments(args)
                .forwardOutput();
    }

    private static File pluginJar(String project, String id) {
        return root.resolve("out/plugins_" + project + "/mibsdk/" + id + ".jar").toFile();
    }

    @Test
    void javaPluginProducesV48JarWithManifest() throws Exception {
        BuildResult result = runner(":plugins:hello:mibPluginJar").build();

        assertSucceeded(result, ":plugins:hello:mibPluginJar");
        assertSucceeded(result, ":plugins:hello:linkCheck");
        assertSucceeded(result, ":plugins:hello:permissionScan");
        File jar = pluginJar("hello", "hello");
        assertTrue(jar.isFile(), "missing " + jar);
        try (ZipFile zip = new ZipFile(jar)) {
            ZipEntry json = zip.getEntry("plugin.json");
            assertNotNull(json, "plugin.json at the jar root");
            String text = new String(zip.getInputStream(json).readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(text.contains("\"sample.hello.HelloPlugin\""), text);
            assertNotNull(zip.getEntry("sample/hello/HelloPlugin.class"));
            assertAllV48(zip);
        }
    }

    @Test
    void pluginJarComesWithAStampMatchingItsHash() throws Exception {
        runner(":plugins:hello:mibPluginJar").build();

        File jar = pluginJar("hello", "hello");
        File stamp = new File(jar.getPath() + ".stamp");
        assertTrue(stamp.isFile(), "missing " + stamp);
        java.util.Properties p = new java.util.Properties();
        try (java.io.InputStream in = new java.io.FileInputStream(stamp)) {
            p.load(in);
        }
        assertEquals("1", p.getProperty("format"));
        assertEquals("plugin", p.getProperty("kind"));
        assertEquals("classVersion48,linkCheck,permissionScan", p.getProperty("checks"));
        assertEquals("hello", p.getProperty("plugin.id"));
        assertEquals("false", p.getProperty("plugin.unrestricted"));
        assertEquals(CheckStamp.sha256(jar), p.getProperty("sha256.hello.jar"));
    }

    @Test
    void excludingACheckWritesNoStamp() throws Exception {
        File stamp = new File(pluginJar("hello", "hello").getPath() + ".stamp");
        runner(":plugins:hello:mibPluginJar").build();
        assertTrue(stamp.isFile());
        Files.delete(stamp.toPath());
        Files.delete(pluginJar("hello", "hello").toPath()); // forces the jar task to run again
        BuildResult result = runner(":plugins:hello:mibPluginJar", "-x", "linkCheck").buildAndFail();
        assertTrue(result.getOutput().contains("refusing to write a check stamp"), result.getOutput());
        assertFalse(stamp.exists(), "stale stamp must not survive");
    }

    @Test
    void reflectionWithoutUnrestrictedFailsBuild() {
        BuildResult result = runner(":plugins:refl:mibPluginJar").buildAndFail();

        assertTrue(result.getOutput().contains("platform.unrestricted"), result.getOutput());
        assertTrue(result.getOutput().contains("sample/refl/ReflPlugin.load -> java/lang/Class.forName"),
                result.getOutput());
        assertEquals(TaskOutcome.FAILED, result.task(":plugins:refl:permissionScan").getOutcome());
    }

    @Test
    void mibPackageBuildsMpk() throws Exception {
        String mibpkg = System.getProperty("mibsdk.mibpkg", "");
        org.junit.jupiter.api.Assumptions.assumeTrue(new File(mibpkg).canExecute(), "no host mibpkg: -Pmibsdk.mibpkg");
        BuildResult result = runner(":plugins:hello:mibPackage", "-Pmibsdk.mibpkg=" + mibpkg).build();

        assertSucceeded(result, ":plugins:hello:mibPluginJar");
        assertSucceeded(result, ":plugins:hello:mibPackage");
        File mpk = root.resolve("out/plugins_hello/mibsdk/hello-1.0.0.mpk").toFile();
        assertTrue(mpk.isFile(), "missing " + mpk);
        Process tar = new ProcessBuilder("tar", "-tzf", mpk.getPath()).redirectErrorStream(true).start();
        String listing = new String(tar.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, tar.waitFor(), listing);
        for (String entry : new String[] {"manifest.toml", "hello.jar", "hello.jar.stamp"}) {
            assertTrue(listing.lines().anyMatch(l -> l.equals(entry) || l.equals("./" + entry)), entry + " in " + listing);
        }
    }

    @Test
    void mibPackageWithoutAHostMibpkgFailsWithAHint() {
        BuildResult result = runner(":plugins:hello:mibPackage", "-Pmibsdk.mibpkg=/nonexistent/mibpkg").buildAndFail();
        assertTrue(result.getOutput().contains("host mibpkg not found: build it (cargo build --release in the mibpkg repo)"
                + " and pass -Pmibsdk.mibpkg=<path>"), result.getOutput());
    }

    @Test
    void mibPackageRerunsWhenTheBinaryChangesAndFailsWhenItIsGone() throws Exception {
        Path fake = root.resolve("fake-mibpkg.sh");
        String script = "#!/bin/sh\n: > \"$3/hello-1.0.0.mpk\"\n";
        Files.write(fake, script.getBytes(StandardCharsets.UTF_8));
        assertTrue(fake.toFile().setExecutable(true));
        String prop = "-Pmibsdk.mibpkg=" + fake;

        assertEquals(TaskOutcome.SUCCESS, runner(":plugins:hello:mibPackage", prop).build()
                .task(":plugins:hello:mibPackage").getOutcome());
        assertEquals(TaskOutcome.UP_TO_DATE, runner(":plugins:hello:mibPackage", prop).build()
                .task(":plugins:hello:mibPackage").getOutcome());

        Files.write(fake, (script + "# stricter\n").getBytes(StandardCharsets.UTF_8));
        assertEquals(TaskOutcome.SUCCESS, runner(":plugins:hello:mibPackage", prop).build()
                .task(":plugins:hello:mibPackage").getOutcome(), "a changed binary at the same path must rerun");

        Files.delete(fake);
        BuildResult gone = runner(":plugins:hello:mibPackage", prop).buildAndFail();
        assertTrue(gone.getOutput().contains("host mibpkg not found"), gone.getOutput());
    }

    @Test
    void apiTwoManifestBuilds() throws Exception {
        assertSucceeded(runner(":plugins:api2:mibPluginJar").build(), ":plugins:api2:mibPluginJar");
    }

    @Test
    void apiOutOfRangeOrNonIntegerFailsBuild() {
        for (String id : new String[] {"api0", "api3", "apifrac"}) {
            String out = runner(":plugins:" + id + ":mibPluginJar").buildAndFail().getOutput();
            String want = id.equals("apifrac") ? "api must be an integer" : "unsupported api";
            assertTrue(out.contains(want), id + ": " + out);
        }
    }

    @Test
    void reflectionWithUnrestrictedBuilds() throws Exception {
        BuildResult result = runner(":plugins:unrestricted:mibPluginJar").build();

        assertSucceeded(result, ":plugins:unrestricted:mibPluginJar");
        assertTrue(pluginJar("unrestricted", "unrestricted").isFile());
    }

    @Test
    void kotlinPluginShrinksAndPasses() throws Exception {
        BuildResult result = runner(":plugins:kt:mibPluginJar").build();

        assertSucceeded(result, ":plugins:kt:kotlinShrink");
        assertSucceeded(result, ":plugins:kt:linkCheck");
        assertSucceeded(result, ":plugins:kt:permissionScan");
        assertSucceeded(result, ":plugins:kt:mibPluginJar");
        // KOTLIN_FLAGS and jvmTarget 1.8 reached kotlinc: Java 8 class files, lambdas and SAM conversions as classes.
        Path kotlinOut = root.resolve("out/plugins_kt/classes/kotlin/main");
        List<Path> compiled;
        try (java.util.stream.Stream<Path> s = Files.walk(kotlinOut)) {
            compiled = s.filter(p -> p.toString().endsWith(".class")).collect(java.util.stream.Collectors.toList());
        }
        assertTrue(compiled.size() >= 2, "plugin class plus the listener class: " + compiled);
        for (Path c : compiled) {
            byte[] bytes = Files.readAllBytes(c);
            assertEquals(52, ((bytes[6] & 0xff) << 8) | (bytes[7] & 0xff), c.toString());
            assertTrue(!hasInvokeDynamic(bytes), c + " uses invokedynamic");
        }
        File jar = pluginJar("kt", "kt");
        try (ZipFile zip = new ZipFile(jar)) {
            assertNotNull(zip.getEntry("plugin.json"));
            assertNotNull(zip.getEntry("sample/kt/KtPlugin.class"));
            int kotlinClasses = 0;
            int allClasses = 0;
            for (ZipEntry e : Collections.list(zip.entries())) {
                if (!e.getName().endsWith(".class")) {
                    continue;
                }
                allClasses++;
                if (e.getName().startsWith("kotlin/")) {
                    kotlinClasses++;
                }
                byte[] bytes;
                try (InputStream in = zip.getInputStream(e)) {
                    bytes = in.readAllBytes();
                }
                List<String> bad = kotlinInlineLocals(bytes);
                assertTrue(bad.isEmpty(), e.getName() + " has LocalVariableTable entries " + bad);
            }
            assertTrue(kotlinClasses > 0, "the used part of kotlin-stdlib is shrunk into the jar");
            assertTrue(allClasses < 400, "stdlib was shrunk, not copied whole: " + allClasses + " classes");
            assertAllV48(zip);
        }
    }

    private static void assertSucceeded(BuildResult result, String task) {
        assertNotNull(result.task(task), task + " did not run");
        TaskOutcome o = result.task(task).getOutcome();
        assertTrue(o == TaskOutcome.SUCCESS || o == TaskOutcome.UP_TO_DATE || o == TaskOutcome.FROM_CACHE,
                task + " " + o);
    }

    private static void assertAllV48(ZipFile zip) throws IOException {
        List<String> bad = new ArrayList<String>();
        int classes = 0;
        for (ZipEntry e : Collections.list(zip.entries())) {
            if (!e.getName().endsWith(".class")) {
                continue;
            }
            classes++;
            try (InputStream in = zip.getInputStream(e)) {
                byte[] head = in.readNBytes(8);
                int major = ((head[6] & 0xff) << 8) | (head[7] & 0xff);
                if (major != 48) {
                    bad.add(e.getName() + "=" + major);
                }
            }
        }
        assertTrue(classes > 0, "no classes in " + zip.getName());
        assertEquals(List.of(), bad, "classes not at version 48");
    }

    private static boolean hasInvokeDynamic(byte[] bytes) {
        boolean[] found = new boolean[1];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int acc, String name, String desc, String sig, String[] ex) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitInvokeDynamicInsn(String n, String d, org.objectweb.asm.Handle h, Object... args) {
                        found[0] = true;
                    }
                };
            }
        }, 0);
        return found[0];
    }

    /** LocalVariableTable names carrying Kotlin's inline-lambda marker ({@code $i$a$-...}). */
    private static List<String> kotlinInlineLocals(byte[] bytes) {
        List<String> bad = new ArrayList<String>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int acc, String name, String desc, String sig, String[] ex) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitLocalVariable(String n, String d, String s, Label a, Label b, int i) {
                        if (n.contains("$i$a$")) {
                            bad.add(name + ":" + n);
                        }
                    }
                };
            }
        }, 0);
        return bad;
    }
}
