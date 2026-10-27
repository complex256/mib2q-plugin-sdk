package mibsdk.build;

import java.io.File;
import org.gradle.api.JavaVersion;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.plugins.JavaLibraryPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.api.tasks.testing.Test;
import org.gradle.jvm.toolchain.JavaToolchainService;

/**
 * Convention plugin {@code mibsdk.car-library}: Java code that ends up on the car as class-file v48.
 *
 * <p>Default compile: JDK 17, {@code --release 8}, Jabel ({@code -Xplugin:jabel}) so authors may use
 * modern syntax. Tests compile and run on JDK 17. The {@code backport} task then lowers the classes to
 * v48. Modules that already are Java 1.4 source (compat) set {@code mibsdk.legacyJava14 = true}.
 */
public class CarLibraryPlugin implements Plugin<Project> {

    /** The {@code mibsdk { ... }} extension. */
    public abstract static class MibsdkExtension {
        /** Classes the module links against on the car (besides the car JCL). */
        public abstract ConfigurableFileCollection getCarClasspath();

        /** Source is plain Java 1.4: compile with JDK 8 javac {@code -source 1.4 -target 1.4}, no Jabel. */
        public abstract Property<Boolean> getLegacyJava14();
    }

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply(JavaLibraryPlugin.class);
        project.getRepositories().mavenCentral();

        MibsdkExtension ext = project.getExtensions().create("mibsdk", MibsdkExtension.class);
        ext.getLegacyJava14().convention(false);

        JavaPluginExtension java = project.getExtensions().getByType(JavaPluginExtension.class);
        java.setSourceCompatibility(JavaVersion.VERSION_17);
        java.setTargetCompatibility(JavaVersion.VERSION_17);

        Configuration retrolambda = tool(project, "retrolambda", false, Toolchain.RETROLAMBDA);
        Configuration retrotranslator = tool(project, "retrotranslator", true,
                Toolchain.RETROTRANSLATOR_TRANSFORMER, Toolchain.RETROTRANSLATOR_RUNTIME);
        // Non-embedded Retrotranslator runtime ships on the car as mibsdk-rt-retrotranslator.jar; LinkCheck resolves against it.
        Configuration retrotranslatorRuntime = tool(project, "retrotranslatorRuntime", false,
                Toolchain.RETROTRANSLATOR_RUNTIME);
        project.getDependencies().add("annotationProcessor", Toolchain.JABEL);
        project.getDependencies().add("annotationProcessor", Toolchain.BYTE_BUDDY);
        project.getDependencies().add("annotationProcessor", Toolchain.BYTE_BUDDY_AGENT);
        project.getDependencies().add("testImplementation", Toolchain.JUNIT);
        project.getDependencies().add("testRuntimeOnly", Toolchain.JUNIT_LAUNCHER);

        JavaToolchainService toolchains = project.getExtensions().getByType(JavaToolchainService.class);
        project.getTasks().withType(Test.class).configureEach(t -> {
            t.useJUnitPlatform();
            t.getJavaLauncher().set(toolchains.launcherFor(spec -> spec.getLanguageVersion()
                    .set(org.gradle.jvm.toolchain.JavaLanguageVersion.of(17))));
        });
        project.getTasks().named("compileTestJava", JavaCompile.class).configure(t ->
                t.getJavaCompiler().set(toolchains.compilerFor(spec -> spec.getLanguageVersion()
                        .set(org.gradle.jvm.toolchain.JavaLanguageVersion.of(17)))));

        // Main compile is configured after evaluation because it depends on the legacyJava14 flag.
        project.afterEvaluate(p -> configureMainCompile(p, ext, toolchains));

        SourceSet main = java.getSourceSets().getByName(SourceSet.MAIN_SOURCE_SET_NAME);
        TaskProvider<CarJclTask> carJcl = registerCarJcl(project);

        TaskProvider<BackportTask> backport = project.getTasks().register("backport", BackportTask.class, t -> {
            t.setGroup("build");
            t.setDescription("Lowers compiled classes to class-file v48 (Retrolambda + Retrotranslator).");
            t.getInputClasses().from(main.getOutput().getClassesDirs());
            t.getRetrolambda().from(retrolambda);
            t.getRetrotranslator().from(retrotranslator);
            t.getCarClasspath().from(ext.getCarClasspath());
            t.getJdk8Home().set(Toolchain.jdk8Home(project));
            t.getOutputDir().set(project.getLayout().getBuildDirectory().dir("v48"));
            if (project.getName().equals("compat")) {
                t.getCompatClasses().from(main.getOutput().getClassesDirs());
            } else {
                t.getCompatClasses().from(compatClasses(project));
            }
            t.dependsOn(main.getClassesTaskName());
        });

        TaskProvider<ClassVersionCheckTask> versionCheck = project.getTasks().register(
                "classVersionCheck", ClassVersionCheckTask.class, t -> {
                    t.setGroup("verification");
                    t.setDescription("Fails if any backported class is not class-file version 48.");
                    t.getClasses().from(backport.flatMap(BackportTask::getOutputDir));
                });

        TaskProvider<LinkCheckTask> linkCheck = project.getTasks().register("linkCheck", LinkCheckTask.class, t -> {
            t.setGroup("verification");
            t.setDescription("Fails if the v48 classes reference anything the car JCL / car classpath lacks.");
            t.getChecked().from(backport.flatMap(BackportTask::getOutputDir));
            t.getClasspath().from(carJcl.flatMap(CarJclTask::getOutputDir));
            t.getClasspath().from(backport.flatMap(BackportTask::getOutputDir));
            t.getClasspath().from(ext.getCarClasspath());
            t.getClasspath().from(retrotranslatorRuntime);
            t.getReport().set(project.getLayout().getBuildDirectory().file("reports/mibsdk/linkcheck.txt"));
        });

        // Report-only here: SDK internals legitimately use reflection. The mibsdk.plugin convention enforces.
        TaskProvider<PermissionScanTask> permissionScan = project.getTasks().register(
                "permissionScan", PermissionScanTask.class, t -> {
                    t.setGroup("verification");
                    t.setDescription("Lists calls that need platform.unrestricted (reflection, exec, exit, ...).");
                    t.getClasses().from(backport.flatMap(BackportTask::getOutputDir));
                    t.getFailOnFindings().convention(false);
                    t.getDeclaredApi().convention(PluginJson.MAX_API); // a library has no plugin.json
                    t.getReport().set(project.getLayout().getBuildDirectory().file("reports/mibsdk/permissions.txt"));
                });

        project.getTasks().named("check").configure(t -> t.dependsOn(versionCheck, linkCheck, permissionScan));

        project.getTasks().register("carJar", Jar.class, t -> {
            t.setGroup("build");
            t.setDescription("Packs the v48 classes into build/libs/<project>-car.jar.");
            t.from(backport.flatMap(BackportTask::getOutputDir));
            t.getArchiveFileName().set(project.getName() + "-car.jar");
            t.dependsOn(versionCheck);
        });
    }

    private static void configureMainCompile(Project project, MibsdkExtension ext, JavaToolchainService toolchains) {
        project.getTasks().named("compileJava", JavaCompile.class).configure(t -> {
            if (ext.getLegacyJava14().get()) {
                File jdk8 = new File(Toolchain.jdk8Home(project).get());
                t.setSourceCompatibility("1.4");
                t.setTargetCompatibility("1.4");
                t.getOptions().setFork(true);
                t.getOptions().getForkOptions().setExecutable(new File(jdk8, "bin/javac").getPath());
                t.getOptions().setBootstrapClasspath(project.files(new File(jdk8, "jre/lib/rt.jar")));
                t.getOptions().getCompilerArgs().add("-Xlint:-options");
            } else {
                t.getJavaCompiler().set(toolchains.compilerFor(spec -> spec.getLanguageVersion()
                        .set(org.gradle.jvm.toolchain.JavaLanguageVersion.of(17))));
                t.getOptions().getRelease().set(8);
                t.getOptions().getCompilerArgs().add("-Xplugin:jabel");
            }
        });
        if (ext.getLegacyJava14().get()) {
            // Jabel is only for the modern-source default.
            project.getConfigurations().getByName("annotationProcessor").getDependencies().clear();
        }
    }

    private static Configuration tool(Project project, String name, boolean transitive, String... coordinates) {
        Configuration c = project.getConfigurations().create(name, cfg -> {
            cfg.setCanBeConsumed(false);
            cfg.setCanBeResolved(true);
            cfg.setTransitive(transitive);
        });
        for (String coordinate : coordinates) {
            project.getDependencies().add(name, coordinate);
        }
        return c;
    }

    /** Compat shim classes for non-compat modules, resolved from the compat project. */
    private static Configuration compatClasses(Project project) {
        Configuration c = project.getConfigurations().create("mibsdkCompat", cfg -> {
            cfg.setCanBeConsumed(false);
            cfg.setCanBeResolved(true);
        });
        project.getDependencies().add("mibsdkCompat", project.getDependencies().project(
                java.util.Collections.singletonMap("path", ":compat")));
        return c;
    }

    /**
     * One {@code carJcl} task on the root project, shared by every module. With {@code -Pmibsdk.stockLsdJar}
     * it extracts the JCL from that jar and the maintainer tasks that regenerate and verify the bundled list
     * are registered; without it the bundled list is used.
     */
    private static TaskProvider<CarJclTask> registerCarJcl(Project project) {
        Project root = project.getRootProject();
        if (!root.getTasks().getNames().contains("carJcl")) {
            boolean haveJar = Toolchain.stockLsdJarConfigured(root);
            root.getTasks().register("carJcl", CarJclTask.class, t -> {
                t.setGroup("build");
                t.setDescription("Provides the car JCL for linkCheck in build/carjcl/ (stock lsd.jar, else the bundled list).");
                if (haveJar) {
                    t.getStockLsdJar().set(root.getLayout().file(root.provider(() -> Toolchain.stockLsdJar(root))));
                }
                t.getOutputDir().set(root.getLayout().getBuildDirectory().dir("carjcl"));
            });
            if (haveJar) {
                registerSignatureTasks(root);
            }
        }
        return root.getTasks().named("carJcl", CarJclTask.class);
    }

    /** Maintainer tasks: regenerate the bundled car JCL list from lsd.jar, and check it is current. */
    private static void registerSignatureTasks(Project root) {
        root.getTasks().register("carJclSignatures", CarJclSignaturesTask.class, t -> {
            t.setGroup("build");
            t.setDescription("Regenerates the bundled car JCL signature list from the stock lsd.jar.");
            t.getStockLsdJar().set(root.getLayout().file(root.provider(() -> Toolchain.stockLsdJar(root))));
            t.getSourceLabel().set(MpkStage.HMI);
            t.getOutputFile().set(root.getLayout().getProjectDirectory()
                    .file("build-logic/src/main/resources/mibsdk/build/carjcl" + CarJclSignatures.SUFFIX));
        });
        TaskProvider<CarJclSignaturesTask> check = root.getTasks().register(
                "carJclSignaturesCheck", CarJclSignaturesTask.class, t -> {
                    t.setGroup("verification");
                    t.setDescription("Fails if the bundled car JCL signature list differs from what the stock lsd.jar yields.");
                    t.getStockLsdJar().set(root.getLayout().file(root.provider(() -> Toolchain.stockLsdJar(root))));
                    t.getSourceLabel().set(MpkStage.HMI);
                    t.getCompareWithBundled().set(true);
                    t.getOutputFile().set(root.getLayout().getBuildDirectory().file("carjcl-signatures/carjcl.sigs"));
                });
        root.getTasks().matching(t -> t.getName().equals("check")).configureEach(t -> t.dependsOn(check));
    }
}
