package mibsdk.build;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.gradle.api.GradleException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginScaffoldTest {
    @TempDir Path dir;

    private File template;

    /** The template's shape, with the same three placeholders in the same places. */
    @BeforeEach
    void template() throws IOException {
        template = dir.resolve("templates/plugin").toFile();
        write("build.gradle.kts", "plugins {\n    id(\"mibsdk.plugin\") version \"0.3.0\"\n}\n");
        write("settings.gradle.kts", "rootProject.name = \"my-plugin\"\n");
        write("gradle.properties", "# the SDK repository\nmibsdk.repo=\n");
        write(".gitignore", "build/\n");
        write("gradlew", "#!/bin/sh\necho my-plugin\n");
        write("src/main/java/example/myplugin/MyPlugin.java",
                "package example.myplugin;\n\npublic class MyPlugin extends MibPlugin {\n}\n");
        write("src/test/java/example/myplugin/MyPluginTest.java",
                "package example.myplugin;\n\nclass MyPluginTest {\n}\n");
        write("src/main/resources/plugin.json",
                "{\n  \"id\": \"my-plugin\",\n  \"main\": \"example.myplugin.MyPlugin\"\n}\n");
        Files.createDirectories(template.toPath().resolve("gradle/wrapper"));
        Files.write(template.toPath().resolve("gradle/wrapper/gradle-wrapper.jar"),
                new byte[] {(byte) 0xca, (byte) 0xfe, (byte) 0x00, 0x00});
    }

    private void write(String relative, String content) throws IOException {
        Path file = template.toPath().resolve(relative);
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    private String read(File project, String relative) throws IOException {
        return new String(Files.readAllBytes(project.toPath().resolve(relative)), StandardCharsets.UTF_8);
    }

    @Test
    void renamesTheIdPackageAndClassEverywhere() throws IOException {
        File project = dir.resolve("coffee-timer").toFile();
        PluginScaffold.create(template, project, "coffee-timer", "tutorial.coffeetimer", "file:/sdk/build/repo/");

        assertTrue(new File(project, "src/main/java/tutorial/coffeetimer/CoffeeTimer.java").isFile());
        assertTrue(new File(project, "src/test/java/tutorial/coffeetimer/CoffeeTimerTest.java").isFile());
        assertFalse(new File(project, "src/main/java/example").exists(), "the template's package directory stays behind");
        assertEquals("package tutorial.coffeetimer;\n\npublic class CoffeeTimer extends MibPlugin {\n}\n",
                read(project, "src/main/java/tutorial/coffeetimer/CoffeeTimer.java"));
        assertEquals("package tutorial.coffeetimer;\n\nclass CoffeeTimerTest {\n}\n",
                read(project, "src/test/java/tutorial/coffeetimer/CoffeeTimerTest.java"));
        assertTrue(read(project, "src/main/resources/plugin.json").contains("\"id\": \"coffee-timer\""));
        assertTrue(read(project, "src/main/resources/plugin.json").contains("\"main\": \"tutorial.coffeetimer.CoffeeTimer\""));
        assertTrue(read(project, "settings.gradle.kts").contains("rootProject.name = \"coffee-timer\""));
        assertTrue(read(project, "gradlew").contains("echo coffee-timer"));
        assertTrue(new File(project, "gradlew").canExecute());
    }

    @Test
    void pointsMibsdkRepoAtTheSdkCheckoutAndWritesAGitignore() throws IOException {
        File project = dir.resolve("hello").toFile();
        PluginScaffold.create(template, project, "hello", "plugin.hello", "file:/sdk/build/repo/");

        assertTrue(read(project, "gradle.properties").contains("\nmibsdk.repo=file:/sdk/build/repo/\n"),
                read(project, "gradle.properties"));
        assertEquals("build/\n.gradle/\n.kotlin/\n", read(project, ".gitignore"));
    }

    @Test
    void copiesNonUtf8FilesByteForByte() throws IOException {
        File project = dir.resolve("hello").toFile();
        PluginScaffold.create(template, project, "hello", "plugin.hello", "file:/sdk/build/repo/");

        assertArrayEquals(new byte[] {(byte) 0xca, (byte) 0xfe, (byte) 0x00, (byte) 0x00},
                Files.readAllBytes(project.toPath().resolve("gradle/wrapper/gradle-wrapper.jar")));
    }

    @Test
    void refusesANonEmptyDestinationAndLeavesItAlone() throws IOException {
        File project = dir.resolve("taken").toFile();
        Files.createDirectories(project.toPath());
        Files.write(project.toPath().resolve("keep.txt"), "mine".getBytes(StandardCharsets.UTF_8));

        GradleException e = assertThrows(GradleException.class,
                () -> PluginScaffold.create(template, project, "hello", "plugin.hello", "file:/sdk/build/repo/"));
        assertTrue(e.getMessage().contains("already exists and is not empty"), e.getMessage());
        assertEquals("mine", read(project, "keep.txt"));
        assertEquals(1, project.list().length, "nothing was written into the destination");
        try (java.util.stream.Stream<Path> siblings = Files.list(dir)) {
            assertTrue(siblings.noneMatch(p -> p.getFileName().toString().startsWith(".new-plugin-")),
                    "the staging directory was cleaned up");
        }
    }

    @Test
    void fillsAnEmptyDestination() throws IOException {
        File project = dir.resolve("empty").toFile();
        Files.createDirectories(project.toPath());

        PluginScaffold.create(template, project, "hello", "plugin.hello", "file:/sdk/build/repo/");

        assertTrue(new File(project, "build.gradle.kts").isFile());
        assertEquals("build/\n.gradle/\n.kotlin/\n", read(project, ".gitignore"));
    }

    @Test
    void derivesTheDefaultPackageAndClassName() {
        assertEquals("plugin.myplugin", PluginScaffold.defaultPackage("my-plugin"));
        assertEquals("MyPlugin", PluginScaffold.className("my-plugin"));
        assertEquals("plugin.checkplugin", PluginScaffold.defaultPackage("check-plugin"));
        assertEquals("CheckPlugin", PluginScaffold.className("check-plugin"));
        assertEquals("Hello", PluginScaffold.className("hello"));
    }

    @Test
    void refusesAnIdThatStartsWithADigit() {
        String problem = PluginJson.idProblem("2go");
        assertNotNull(problem, "a digit-leading id is refused");
        assertTrue(problem.contains("must start with a letter"), problem);
        assertFalse(problem.contains("-Ppackage"), problem);
    }
}
