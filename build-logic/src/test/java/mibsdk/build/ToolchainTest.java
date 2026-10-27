package mibsdk.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ToolchainTest {
    @TempDir Path tmp;

    private Project project() {
        return ProjectBuilder.builder().withProjectDir(tmp.toFile()).build();
    }

    private static void set(Project project, String property, String value) {
        project.getExtensions().getExtraProperties().set(property, value);
    }

    @Test
    void missingPropertyNamesItAndTheReadme() {
        GradleException e = assertThrows(GradleException.class, () -> Toolchain.stockDsiTracerJar(project()));

        assertTrue(e.getMessage().contains("mibsdk.stockDsiTracerJar is not set"), e.getMessage());
        assertTrue(e.getMessage().contains("README.md#toolchain"), e.getMessage());
        assertTrue(e.getMessage().contains("-Pmibsdk.stockDsiTracerJar=<path>"), e.getMessage());
    }

    @Test
    void messageHasTheExactShape() {
        GradleException e = assertThrows(GradleException.class,
                () -> Toolchain.required(project(), "mibsdk.example", "example.jar is the thing"));

        assertEquals("mibsdk: mibsdk.example is not set\n  example.jar is the thing\n"
                + "  Set it with -Pmibsdk.example=<path> or in ~/.gradle/gradle.properties; see README.md#toolchain",
                e.getMessage());
    }

    @Test
    void missingFileNamesThePath() {
        Project project = project();
        set(project, "mibsdk.stockDsiTracerJar", "/nonexistent/x.jar");

        GradleException e = assertThrows(GradleException.class, () -> Toolchain.stockDsiTracerJar(project));

        assertTrue(e.getMessage().contains("mibsdk.stockDsiTracerJar points at /nonexistent/x.jar, which does not exist"),
                e.getMessage());
        assertTrue(e.getMessage().contains("README.md#toolchain"), e.getMessage());
    }

    @Test
    void existingFileIsReturned() throws Exception {
        Path jar = Files.write(tmp.resolve("DSITracer.jar"), new byte[] {1});
        Project project = project();
        set(project, "mibsdk.stockDsiTracerJar", jar.toString());

        assertEquals(jar.toFile(), Toolchain.stockDsiTracerJar(project));
    }

    @Test
    void unsetLsdJarIsOptional() {
        Project project = project();

        assertNull(Toolchain.stockLsdJar(project));
        assertFalse(Toolchain.stockLsdJarConfigured(project));
    }

    @Test
    void setLsdJarWithAMissingFileNamesTheProperty() {
        Project project = project();
        set(project, "mibsdk.stockLsdJar", "/nonexistent/lsd.jar");

        assertTrue(Toolchain.stockLsdJarConfigured(project));
        GradleException e = assertThrows(GradleException.class, () -> Toolchain.stockLsdJar(project));
        assertTrue(e.getMessage().contains("mibsdk.stockLsdJar points at /nonexistent/lsd.jar, which does not exist"),
                e.getMessage());
    }

    @Test
    void modulesThatCompileAgainstTheStockJarsRequireBoth() throws Exception {
        Project project = project();
        GradleException none = assertThrows(GradleException.class, () -> Toolchain.requireStockJars(project));
        assertTrue(none.getMessage().contains("mibsdk.stockDsiTracerJar is not set"), none.getMessage());

        Path dsi = Files.write(tmp.resolve("DSITracer.jar"), new byte[] {1});
        set(project, "mibsdk.stockDsiTracerJar", dsi.toString());
        GradleException noLsd = assertThrows(GradleException.class, () -> Toolchain.requireStockJars(project));
        assertTrue(noLsd.getMessage().contains("mibsdk.stockLsdJar is not set"), noLsd.getMessage());
    }

    @Test
    void readingTheStockJarsNeverFailsButTheVerifyTaskDoes() {
        Project project = project();

        assertTrue(Toolchain.stockJars(project).getFiles().isEmpty());
        assertTrue(Toolchain.stockJars(ProjectBuilder.builder().withParent(project).withName("other").build())
                .getBuildDependencies().getDependencies(null).stream()
                .anyMatch(t -> t.getName().equals("verifyStockJars")));
    }

    @Test
    void explicitJdk8HomeWinsOverTheToolchain() {
        Project project = project();
        set(project, "mibsdk.jdk8Home", "/opt/jdk8");

        assertEquals("/opt/jdk8", Toolchain.jdk8Home(project).get());
    }

    @Test
    void noHomeRelativeDefaults() throws Exception {
        Path buildLogicSrc = Path.of(System.getProperty("mibsdk.repoRoot"), "build-logic/src");
        // Assembled so this file does not contain the string it forbids.
        String forbidden = "user" + ".home";
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(buildLogicSrc)) {
            for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)
                    .filter(f -> f.toString().endsWith(".java"))::iterator) {
                if (new String(Files.readAllBytes(file), StandardCharsets.UTF_8).contains(forbidden)) {
                    offenders.add(buildLogicSrc.relativize(file).toString());
                }
            }
        }
        assertEquals(List.of(), offenders, "no default may point under the home directory");

        String toolchain = new String(Files.readAllBytes(
                buildLogicSrc.resolve("main/java/mibsdk/build/Toolchain.java")), StandardCharsets.UTF_8);
        assertFalse(toolchain.contains("JavaVirtualMachines"), "Toolchain must not name a machine-local JDK path");
        assertFalse(toolchain.contains("audi-mib2"), "Toolchain must not name a machine-local project path");
    }
}
