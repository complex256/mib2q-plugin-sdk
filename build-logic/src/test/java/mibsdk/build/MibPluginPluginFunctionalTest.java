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
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
                + "include(\"compat\", \"api\", \"plugins:hello\", \"plugins:refl\", \"plugins:unrestricted\", \"plugins:undeclared\", \"plugins:badperm\", \"plugins:kt\", \"plugins:api2\", \"plugins:api0\", \"plugins:api6\", \"plugins:apifrac\", \"plugins:icons\", \"plugins:badicon\", \"plugins:noicon\", \"plugins:colour3\", \"plugins:colour4\", \"plugins:inputs4\", \"plugins:inputs5\", \"plugins:tested\", \"plugins:regex\", \"plugins:lambdaop\", \"plugin-test\")\n"
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
        // Calls ctx.net() and Ui.statusItem without declaring them; platform.unrestricted does not excuse that.
        javaPlugin("undeclared", "sample.undeclared.UndeclaredPlugin", "package sample.undeclared;\n"
                + "import mibsdk.api.*;\n"
                + "public class UndeclaredPlugin extends MibPlugin {\n"
                + "  protected void onEnable(PluginContext ctx) throws Exception {\n"
                + "    ctx.net();\n"
                + "    ctx.ui().statusItem(\"x\");\n"
                + "  }\n"
                + "}\n", "[\"platform.unrestricted\"]");
        // ctx.net() and ctx.ui() are api 2.
        write("plugins/undeclared/src/main/resources/plugin.json",
                manifest("undeclared", "sample.undeclared.UndeclaredPlugin", "[\"platform.unrestricted\"]")
                        .replace("\"api\":1", "\"api\":2"));
        javaPlugin("badperm", "sample.refl.ReflPlugin", "package sample.refl;\n"
                + "import mibsdk.api.*;\n"
                + "public class ReflPlugin extends MibPlugin {\n"
                + "  protected void onEnable(PluginContext ctx) throws Exception { }\n"
                + "}\n", "[\"netwrk\"]");

        // Calls a regex method the car lacks, so linkCheck must name the method and the hint.
        javaPlugin("regex", "sample.regex.RegexPlugin", "package sample.regex;\n"
                + "import mibsdk.api.*;\n"
                + "public class RegexPlugin extends MibPlugin {\n"
                + "  protected void onEnable(PluginContext ctx) throws Exception {\n"
                + "    if (\"x\".matches(\"y\")) { ctx.log().info(\"matched\"); }\n"
                + "  }\n"
                + "}\n", "[]");

        for (String[] a : new String[][] {{"api2", "2"}, {"api0", "0"}, {"api6", "6"}, {"apifrac", "1.5"}}) {
            javaPlugin(a[0], "sample.refl.ReflPlugin", "package sample.refl;\n"
                    + "import mibsdk.api.*;\n"
                    + "public class ReflPlugin extends MibPlugin {\n"
                    + "  protected void onEnable(PluginContext ctx) throws Exception { }\n"
                    + "}\n", "[]");
            write("plugins/" + a[0] + "/src/main/resources/plugin.json",
                    manifest(a[0], "sample.refl.ReflPlugin", "[]").replace("\"api\":1", "\"api\":" + a[1]));
        }

        // Icons (api 3): a valid one, a refused one (a shared PNG vector), and one declared without its file.
        for (String id : new String[] {"icons", "badicon", "noicon"}) {
            javaPlugin(id, "sample.refl.ReflPlugin", "package sample.refl;\n"
                    + "import mibsdk.api.*;\n"
                    + "public class ReflPlugin extends MibPlugin {\n"
                    + "  protected void onEnable(PluginContext ctx) throws Exception { }\n"
                    + "}\n", "[]");
            write("plugins/" + id + "/src/main/resources/plugin.json", manifest(id, "sample.refl.ReflPlugin", "[]")
                    .replace("\"api\":1", "\"api\":3,\"icons\":[\"row\",\"status\"]"));
        }
        copyFixture("ok-72.png", "plugins/icons/src/main/icons/row.png");
        copyFixture("ok-20.png", "plugins/icons/src/main/icons/status.png");
        copyFixture("ok-72.png", "plugins/badicon/src/main/icons/row.png");
        copyFixture("interlaced.png", "plugins/badicon/src/main/icons/status.png");
        copyFixture("ok-72.png", "plugins/noicon/src/main/icons/row.png");

        // java.util.function.BinaryOperator is not in the JDK 1.4 API: the compat interface and the
        // Retrotranslator backport of the package are what let a typed lambda link.
        javaPlugin("lambdaop", "sample.lambdaop.LambdaPlugin", "package sample.lambdaop;\n"
                + "import mibsdk.api.*;\n"
                + "import java.util.function.BinaryOperator;\n"
                + "public class LambdaPlugin extends MibPlugin {\n"
                + "  protected void onEnable(PluginContext ctx) throws Exception {\n"
                + "    BinaryOperator<Integer> add = (a, b) -> a + b;\n"
                + "    ctx.log().info(\"sum=\" + add.apply(1, 2));\n"
                + "  }\n"
                + "}\n", "[]");

        // Ui.icon(String, int) is api 4: the same plugin declaring api 3 and api 4.
        for (String[] a : new String[][] {{"colour3", "3"}, {"colour4", "4"}}) {
            javaPlugin(a[0], "sample.colour.ColourPlugin", "package sample.colour;\n"
                    + "import mibsdk.api.*;\n"
                    + "public class ColourPlugin extends MibPlugin {\n"
                    + "  protected void onEnable(PluginContext ctx) throws Exception {\n"
                    + "    ctx.ui().statusItem(\"x\", ctx.ui().icon(\"status\", Icon.OWN_COLOURS));\n"
                    + "  }\n"
                    + "}\n", "[]");
            write("plugins/" + a[0] + "/src/main/resources/plugin.json",
                    manifest(a[0], "sample.colour.ColourPlugin", "[\"ui.slot.statusbar\"]")
                            .replace("\"api\":1", "\"api\":" + a[1] + ",\"icons\":[\"status\"]"));
            copyFixture("ok-20.png", "plugins/" + a[0] + "/src/main/icons/status.png");
        }

        // PageSpec.slider is api 5: the same plugin declaring api 4 and api 5.
        for (String[] a : new String[][] {{"inputs4", "4"}, {"inputs5", "5"}}) {
            javaPlugin(a[0], "sample.inputs.InputsPlugin", "package sample.inputs;\n"
                    + "import mibsdk.api.*;\n"
                    + "public class InputsPlugin extends MibPlugin {\n"
                    + "  protected void onEnable(PluginContext ctx) throws Exception {\n"
                    + "    ctx.ui().page(PageSpec.of(\"main\", \"Inputs\").slider(\"bass\", \"Bass\", -6, 6, 1, 0, true), null);\n"
                    + "  }\n"
                    + "}\n", "[]");
            write("plugins/" + a[0] + "/src/main/resources/plugin.json",
                    manifest(a[0], "sample.inputs.InputsPlugin", "[]").replace("\"api\":1", "\"api\":" + a[1]));
        }

        // The convention depends on the project :plugin-test; a stub stands in for the real harness module here.
        write("plugin-test/build.gradle.kts", "plugins {\n    `java-library`\n}\n"
                + "tasks.withType<JavaCompile>().configureEach { options.release = 8 }\n");
        write("plugin-test/src/main/java/mibsdk/test/Marker.java", "package mibsdk.test;\npublic class Marker { }\n");

        // A plugin whose build file is only the plugins block, with a host test that reads what the convention wired.
        javaPlugin("tested", "sample.tested.TestedPlugin", "package sample.tested;\n"
                + "import mibsdk.api.*;\n"
                + "public class TestedPlugin extends MibPlugin {\n"
                + "  protected void onEnable(PluginContext ctx) throws Exception { }\n"
                + "}\n", "[]");
        write("plugins/tested/src/test/java/sample/tested/WiringTest.java", "package sample.tested;\n"
                + "import static org.junit.jupiter.api.Assertions.*;\n"
                + "import java.io.File;\n"
                + "import org.junit.jupiter.api.Test;\n"
                + "class WiringTest {\n"
                + "  @Test void pluginJar() {\n"
                + "    String jar = System.getProperty(\"mibsdk.pluginJar\");\n"
                + "    assertNotNull(jar, \"mibsdk.pluginJar\");\n"
                + "    assertTrue(jar.replace('\\\\', '/').endsWith(\"/tested.jar\"), jar);\n"
                + "    assertTrue(new File(jar).isFile(), jar);\n"
                + "  }\n"
                + "  @Test void pluginIcons() {\n"
                + "    String icons = System.getProperty(\"mibsdk.pluginIcons\");\n"
                + "    assertNotNull(icons, \"mibsdk.pluginIcons\");\n"
                + "    assertTrue(icons.replace('\\\\', '/').endsWith(\"/plugins/tested/src/main/icons\"), icons);\n"
                + "  }\n"
                + "  @Test void harnessIsOnTheTestClasspath() throws Exception {\n"
                + "    Class.forName(\"mibsdk.test.Marker\");\n"
                + "  }\n"
                + "}\n");

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

    private static void copyFixture(String name, String rel) throws IOException {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        try (InputStream in = MibPluginPluginFunctionalTest.class.getResourceAsStream("/png/" + name)) {
            Files.write(p, in.readAllBytes());
        }
    }

    private static void write(String rel, String text) throws IOException {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.write(p, text.getBytes(StandardCharsets.UTF_8));
    }

    private static GradleRunner runner(String... tasks) {
        return runner(true, tasks);
    }

    /** {@code withStockLsdJar} false leaves {@code -Pmibsdk.stockLsdJar} off, so builds use the bundled car JCL list. */
    private static GradleRunner runner(boolean withStockLsdJar, String... tasks) {
        List<String> args = new ArrayList<String>();
        Collections.addAll(args, tasks);
        args.add("--stacktrace");
        for (String prop : new String[] {"mibsdk.jdk8Home", "mibsdk.stockLsdJar"}) {
            if (!withStockLsdJar && prop.equals("mibsdk.stockLsdJar")) {
                continue;
            }
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
    void testTaskSeesThePluginJar() throws Exception {
        BuildResult result = runner(":plugins:tested:test").build();

        assertSucceeded(result, ":plugins:tested:mibPluginJar");
        assertSucceeded(result, ":plugins:tested:test");
        assertTrue(pluginJar("tested", "tested").isFile());
    }

    @Test
    void pluginBuildsWithoutStockLsdJar() throws Exception {
        BuildResult result = runner(false, ":plugins:hello:mibPluginJar").build();

        assertSucceeded(result, ":plugins:hello:linkCheck");
        assertSucceeded(result, ":plugins:hello:mibPluginJar");
        assertTrue(root.resolve("out/root/carjcl/carjcl.sigs").toFile().isFile(), "bundled list was not written");
        assertTrue(pluginJar("hello", "hello").isFile());
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
    void linkCheckFailureShowsSiteAndHint() {
        String output = runner(":plugins:regex:mibPluginJar").buildAndFail().getOutput();

        assertTrue(output.contains("used by sample/regex/RegexPlugin.onEnable"), output);
        assertTrue(output.contains("hint: no regex"), output);
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
    void undeclaredPermissionUseFailsBuildEvenWhenUnrestricted() {
        BuildResult result = runner(":plugins:undeclared:mibPluginJar").buildAndFail();

        assertEquals(TaskOutcome.FAILED, result.task(":plugins:undeclared:permissionScan").getOutcome());
        assertTrue(result.getOutput().contains("sample/undeclared/UndeclaredPlugin.onEnable -> PluginContext.net"
                + " needs \"network\": declare it in plugin.json \"permissions\""), result.getOutput());
        assertTrue(result.getOutput().contains("sample/undeclared/UndeclaredPlugin.onEnable -> Ui.statusItem"
                + " needs \"ui.slot.statusbar\""), result.getOutput());
    }

    @Test
    void unknownPermissionNameFailsBuildWithSuggestion() {
        String out = runner(":plugins:badperm:mibPluginJar").buildAndFail().getOutput();
        assertTrue(out.contains("unknown permission \"netwrk\" (did you mean \"network\"?); known: "), out);
    }

    @Test
    void binaryOperatorLambdaLinks() throws Exception {
        BuildResult result = runner(":plugins:lambdaop:mibPluginJar").build();

        assertSucceeded(result, ":plugins:lambdaop:linkCheck");
        assertSucceeded(result, ":plugins:lambdaop:mibPluginJar");
        assertTrue(pluginJar("lambdaop", "lambdaop").isFile());
    }

    @Test
    void mibpkgPackageIsTheTaskName() {
        BuildResult all = runner("tasks", "--all").build();
        assertTrue(all.getOutput().contains("plugins:hello:mibpkgPackage"), all.getOutput());
        assertTrue(all.getOutput().contains("plugins:hello:mibPackage "), all.getOutput());
        BuildResult old = runner(":plugins:hello:mibPackage", "-x", ":plugins:hello:mibpkgPackage").build();
        assertSucceeded(old, ":plugins:hello:mibPackage");
        assertTrue(old.getOutput().contains("mibPackage is renamed mibpkgPackage; the old name goes in 0.4.0"),
                old.getOutput());
    }

    @Test
    void quietBackport() {
        BuildResult result = runner(":plugins:hello:mibPluginJar", "--rerun-tasks").build();

        assertSucceeded(result, ":plugins:hello:backport");
        assertFalse(result.getOutput().contains("Retrolambda"), result.getOutput());
    }

    @Test
    void mibpkgPackageBuildsMpk() throws Exception {
        String mibpkg = System.getProperty("mibsdk.mibpkg", "");
        org.junit.jupiter.api.Assumptions.assumeTrue(new File(mibpkg).canExecute(), "no host mibpkg: -Pmibsdk.mibpkg");
        BuildResult result = runner(":plugins:hello:mibpkgPackage", "-Pmibsdk.mibpkg=" + mibpkg).build();

        assertSucceeded(result, ":plugins:hello:mibPluginJar");
        assertSucceeded(result, ":plugins:hello:mibpkgPackage");
        File mpk = root.resolve("out/plugins_hello/mibsdk/hello-1.0.0.mpk").toFile();
        assertTrue(mpk.isFile(), "missing " + mpk);
        Process tar = new ProcessBuilder("tar", "-tzf", mpk.getPath()).redirectErrorStream(true).start();
        String listing = new String(tar.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, tar.waitFor(), listing);
        for (String entry : new String[] {"manifest.toml", "hello.jar", "hello.jar.stamp"}) {
            assertTrue(listing.lines().anyMatch(l -> l.equals(entry) || l.equals("./" + entry)), entry + " in " + listing);
        }
        assertFalse(listing.contains("icons/"), listing);
    }

    @Test
    void mibpkgPackageShipsDeclaredIconsThatTheHostMibpkgAccepts() throws Exception {
        String mibpkg = System.getProperty("mibsdk.mibpkg", "");
        org.junit.jupiter.api.Assumptions.assumeTrue(new File(mibpkg).canExecute(), "no host mibpkg: -Pmibsdk.mibpkg");
        assertSucceeded(runner(":plugins:icons:mibpkgPackage", "-Pmibsdk.mibpkg=" + mibpkg).build(),
                ":plugins:icons:mibpkgPackage");
        File mpk = root.resolve("out/plugins_icons/mibsdk/icons-1.0.0.mpk").toFile();
        Process tar = new ProcessBuilder("tar", "-xzOf", mpk.getPath(), "manifest.toml").redirectErrorStream(true).start();
        String manifest = new String(tar.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, tar.waitFor(), manifest);
        assertTrue(manifest.contains("\nicons = [\"row\", \"status\"]\n"), manifest);
        for (String[] e : new String[][] {{"row", "ok-72.png"}, {"status", "ok-20.png"}}) {
            Process x = new ProcessBuilder("tar", "-xzOf", mpk.getPath(), "icons/" + e[0] + ".png").start();
            byte[] staged = x.getInputStream().readAllBytes();
            assertEquals(0, x.waitFor(), e[0]);
            try (InputStream in = MibPluginPluginFunctionalTest.class.getResourceAsStream("/png/" + e[1])) {
                org.junit.jupiter.api.Assertions.assertArrayEquals(in.readAllBytes(), staged, e[0]);
            }
        }
    }

    @Test
    void mibpkgPackageContainsTheReflection() throws Exception {
        String mibpkg = System.getProperty("mibsdk.mibpkg", "");
        org.junit.jupiter.api.Assumptions.assumeTrue(new File(mibpkg).canExecute(), "no host mibpkg: -Pmibsdk.mibpkg");
        assertSucceeded(runner(":plugins:icons:mibpkgPackage", "-Pmibsdk.mibpkg=" + mibpkg).build(),
                ":plugins:icons:mibpkgPackage");
        File mpk = root.resolve("out/plugins_icons/mibsdk/icons-1.0.0.mpk").toFile();
        Process tar = new ProcessBuilder("tar", "-tzf", mpk.getPath()).redirectErrorStream(true).start();
        String listing = new String(tar.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, tar.waitFor(), listing);
        assertTrue(listing.lines().anyMatch(l -> l.endsWith("icons/row.r.png")), listing);
        assertFalse(listing.contains("status.r.png"), listing);
        Process x = new ProcessBuilder("tar", "-xzOf", mpk.getPath(), "icons/row.r.png").start();
        byte[] staged = x.getInputStream().readAllBytes();
        assertEquals(0, x.waitFor());
        try (InputStream in = MibPluginPluginFunctionalTest.class.getResourceAsStream("/png/ok-72.png")) {
            org.junit.jupiter.api.Assertions.assertArrayEquals(
                    Reflection.encode(Reflection.of(PngCheck.rgba(in.readAllBytes()))), staged);
        }
    }

    @Test
    void mibpkgPackageWithoutAHostMibpkgFailsWithAHint() {
        BuildResult result = runner(":plugins:hello:mibpkgPackage", "-Pmibsdk.mibpkg=/nonexistent/mibpkg").buildAndFail();
        assertTrue(result.getOutput().contains("host mibpkg not found: build it (cargo build --release in the mibpkg repo)"
                + " and pass -Pmibsdk.mibpkg=<path>"), result.getOutput());
    }

    @Test
    void mibpkgPackageRerunsWhenTheBinaryChangesAndFailsWhenItIsGone() throws Exception {
        Path fake = root.resolve("fake-mibpkg.sh");
        String script = "#!/bin/sh\n: > \"$3/hello-1.0.0.mpk\"\n";
        Files.write(fake, script.getBytes(StandardCharsets.UTF_8));
        assertTrue(fake.toFile().setExecutable(true));
        String prop = "-Pmibsdk.mibpkg=" + fake;

        assertEquals(TaskOutcome.SUCCESS, runner(":plugins:hello:mibpkgPackage", prop).build()
                .task(":plugins:hello:mibpkgPackage").getOutcome());
        assertEquals(TaskOutcome.UP_TO_DATE, runner(":plugins:hello:mibpkgPackage", prop).build()
                .task(":plugins:hello:mibpkgPackage").getOutcome());

        Files.write(fake, (script + "# stricter\n").getBytes(StandardCharsets.UTF_8));
        assertEquals(TaskOutcome.SUCCESS, runner(":plugins:hello:mibpkgPackage", prop).build()
                .task(":plugins:hello:mibpkgPackage").getOutcome(), "a changed binary at the same path must rerun");

        Files.delete(fake);
        BuildResult gone = runner(":plugins:hello:mibpkgPackage", prop).buildAndFail();
        assertTrue(gone.getOutput().contains("host mibpkg not found"), gone.getOutput());
    }

    @Test
    void apiTwoManifestBuilds() throws Exception {
        assertSucceeded(runner(":plugins:api2:mibPluginJar").build(), ":plugins:api2:mibPluginJar");
    }

    @Test
    void apiOutOfRangeOrNonIntegerFailsBuild() {
        for (String id : new String[] {"api0", "api6", "apifrac"}) {
            String out = runner(":plugins:" + id + ":mibPluginJar").buildAndFail().getOutput();
            String want = id.equals("apifrac") ? "api must be an integer" : "unsupported api";
            assertTrue(out.contains(want), id + ": " + out);
        }
    }

    @Test
    void colourOverloadNeedsApi4() throws Exception {
        Path fake = root.resolve("fake-mibpkg-colour.sh");
        Files.write(fake, "#!/bin/sh\n: > \"$3/colour4-1.0.0.mpk\"\n".getBytes(StandardCharsets.UTF_8));
        assertTrue(fake.toFile().setExecutable(true));
        String prop = "-Pmibsdk.mibpkg=" + fake;

        BuildResult refused = runner(":plugins:colour3:mibpkgPackage", prop).buildAndFail();
        assertTrue(refused.getOutput().contains(
                "sample/colour/ColourPlugin.onEnable -> Ui.icon(String,int) needs api 4; plugin.json declares api 3"),
                refused.getOutput());
        assertEquals(TaskOutcome.FAILED, refused.task(":plugins:colour3:permissionScan").getOutcome());
        String report = new String(Files.readAllBytes(
                root.resolve("out/plugins_colour3/reports/mibsdk/permissions.txt")), StandardCharsets.UTF_8);
        assertEquals("sample/colour/ColourPlugin.onEnable -> Ui.icon(String,int) needs api 4;"
                + " plugin.json declares api 3\n", report);

        assertSucceeded(runner(":plugins:colour4:mibpkgPackage", prop).build(), ":plugins:colour4:mibpkgPackage");
    }

    @Test
    void inputsNeedApi5() throws Exception {
        Path fake = root.resolve("fake-mibpkg-inputs.sh");
        Files.write(fake, "#!/bin/sh\n: > \"$3/inputs5-1.0.0.mpk\"\n".getBytes(StandardCharsets.UTF_8));
        assertTrue(fake.toFile().setExecutable(true));
        String prop = "-Pmibsdk.mibpkg=" + fake;

        BuildResult refused = runner(":plugins:inputs4:mibpkgPackage", prop).buildAndFail();
        assertTrue(refused.getOutput().contains(
                "sample/inputs/InputsPlugin.onEnable -> PageSpec.slider needs api 5; plugin.json declares api 4"),
                refused.getOutput());
        assertEquals(TaskOutcome.FAILED, refused.task(":plugins:inputs4:permissionScan").getOutcome());
        String report = new String(Files.readAllBytes(
                root.resolve("out/plugins_inputs4/reports/mibsdk/permissions.txt")), StandardCharsets.UTF_8);
        assertEquals("sample/inputs/InputsPlugin.onEnable -> PageSpec.slider needs api 5;"
                + " plugin.json declares api 4\n", report);

        assertSucceeded(runner(":plugins:inputs5:mibpkgPackage", prop).build(), ":plugins:inputs5:mibpkgPackage");
    }

    @Test
    void declaredIconsAreCheckedAtPluginBuild() throws Exception {
        assertSucceeded(runner(":plugins:icons:mibPluginJar").build(), ":plugins:icons:mibPluginJar");

        String bad = runner(":plugins:badicon:mibPluginJar").buildAndFail().getOutput();
        assertTrue(bad.contains("status.png: icon PNG refused: interlace 1"), bad);
        assertFalse(pluginJar("badicon", "badicon").exists());

        String missing = runner(":plugins:noicon:mibPluginJar").buildAndFail().getOutput();
        assertTrue(missing.contains("icon 'status' is declared in plugin.json but src/main/icons/status.png does not exist"),
                missing);
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

    /**
     * A plugin build with no {@code :api}/{@code :compat} project: the SDK comes from a Maven repository by coordinate
     * (hand-made fixture jars, the second one is the v48 {@code car} classifier).
     */
    @Test
    void conventionOutsideTheSdkUsesCoordinates(@TempDir Path alone) throws Exception {
        String version = SdkCoordinates.version();
        Path repoDir = alone.resolve("repo");
        Path src = Files.createDirectories(alone.resolve("fixture-src"));
        Map<String, String> apiSources = new LinkedHashMap<>();
        apiSources.put("mibsdk.api.PluginContext", "package mibsdk.api;\npublic interface PluginContext { }\n");
        apiSources.put("mibsdk.api.MibPlugin", "package mibsdk.api;\n"
                + "public abstract class MibPlugin { protected void onEnable(PluginContext ctx) throws Exception { } }\n");
        File apiClasses = Fixtures.compileV48(src.resolve("api"), apiSources);
        Map<String, String> compatSources = Collections.singletonMap("mibsdk.compat.Marker",
                "package mibsdk.compat;\npublic class Marker { }\n");
        File compatClasses = Fixtures.compileV48(src.resolve("compat"), compatSources);
        for (String m : new String[] {"api", "compat"}) {
            File classes = m.equals("api") ? apiClasses : compatClasses;
            Path dir = Files.createDirectories(repoDir.resolve("dev/complex256/mibsdk/" + m + "/" + version));
            Fixtures.jar(dir.resolve(m + "-" + version + ".jar"), classes);
            Fixtures.jar(dir.resolve(m + "-" + version + "-car.jar"), classes);
            Files.write(dir.resolve(m + "-" + version + ".pom"), ("<project><modelVersion>4.0.0</modelVersion>"
                    + "<groupId>dev.complex256.mibsdk</groupId><artifactId>" + m + "</artifactId>"
                    + "<version>" + version + "</version></project>").getBytes(StandardCharsets.UTF_8));
        }
        String repoUri = repoDir.toUri().toString();
        Files.write(alone.resolve("settings.gradle.kts"), ("dependencyResolutionManagement {\n"
                + "    repositories {\n"
                + "        maven { url = uri(\"" + repoUri + "\") }\n"
                + "        mavenCentral()\n"
                + "    }\n"
                + "}\n"
                + "rootProject.name = \"alone\"\n").getBytes(StandardCharsets.UTF_8));
        Files.write(alone.resolve("build.gradle.kts"), "plugins {\n    id(\"mibsdk.plugin\")\n}\n"
                .getBytes(StandardCharsets.UTF_8));
        Files.write(alone.resolve("gradle.properties"), "org.gradle.jvmargs=-Xmx1g\n".getBytes(StandardCharsets.UTF_8));
        Path res = Files.createDirectories(alone.resolve("src/main/resources"));
        Files.write(res.resolve("plugin.json"), manifest("alone", "sample.alone.AlonePlugin", "[]")
                .getBytes(StandardCharsets.UTF_8));
        Path java = Files.createDirectories(alone.resolve("src/main/java/sample/alone"));
        Files.write(java.resolve("AlonePlugin.java"), ("package sample.alone;\n"
                + "import mibsdk.api.*;\n"
                + "public class AlonePlugin extends MibPlugin {\n"
                + "  protected void onEnable(PluginContext ctx) throws Exception { }\n"
                + "}\n").getBytes(StandardCharsets.UTF_8));

        List<String> args = new ArrayList<String>(Arrays.asList("dependencies", "--configuration", "compileClasspath",
                "mibPluginJar", "--stacktrace"));
        String jdk8 = System.getProperty("mibsdk.jdk8Home");
        if (jdk8 != null && !jdk8.isEmpty()) {
            args.add("-Pmibsdk.jdk8Home=" + jdk8);
        }
        BuildResult result = GradleRunner.create().withProjectDir(alone.toFile()).withPluginClasspath()
                .withArguments(args).forwardOutput().build();

        assertTrue(result.getOutput().contains("dev.complex256.mibsdk:api:" + version), result.getOutput());
        assertSucceeded(result, ":linkCheck");
        assertSucceeded(result, ":mibPluginJar");
        assertTrue(alone.resolve("build/mibsdk/alone.jar").toFile().isFile());
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
