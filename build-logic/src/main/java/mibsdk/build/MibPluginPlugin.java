package mibsdk.build;

import java.io.File;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.Directory;
import org.gradle.api.file.RegularFile;
import org.gradle.api.file.FileCollection;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.Sync;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.api.tasks.testing.Test;

/**
 * Convention plugin {@code mibsdk.plugin}: a third-party plugin built on {@code mibsdk.car-library}.
 *
 * <p>In the SDK repo the SDK comes from the {@code :api} and {@code :compat} projects; in any other build, from the published
 * {@code dev.complex256.mibsdk} artifacts ({@code api}, plus the {@code car} classifier of {@code api} and {@code compat}).
 *
 * <p>Java: compile (JDK 17 {@code --release 8} + Jabel, {@code compileOnly} on {@code :api}) → {@code backport}
 * → {@code linkCheck} (car JCL + api + compat car jars; the Retrotranslator runtime followed transitively;
 * nothing else: the plugin class loader hides platform, runtime and stock classes) → {@code permissionScan}
 * (fails unless {@code plugin.json} lists {@code platform.unrestricted}) → {@code mibPluginJar} =
 * {@code build/mibsdk/<id>.jar} with {@code plugin.json} at the root. {@code mibPluginJar} also refuses the build
 * unless every icon {@code plugin.json} declares is a {@code src/main/icons/<name>.png} that passes {@link PngCheck}.
 *
 * <p>Tests: {@code testImplementation} gets {@code plugin-test} (the {@code :plugin-test} project in the SDK repo), and
 * each {@code Test} task gets the {@code mibPluginJar} output as an input plus {@code -Dmibsdk.pluginJar} and
 * {@code -Dmibsdk.pluginIcons}. JUnit and the JDK 17 test launcher come from {@code mibsdk.car-library}.
 *
 * <p>Kotlin ({@code org.jetbrains.kotlin.jvm} applied): {@link Toolchain#KOTLIN_FLAGS}, {@code jvmTarget=1.8},
 * and {@code kotlinShrink} ({@link KotlinShrinkTask}) between the compile and {@code backport}.
 */
public class MibPluginPlugin implements Plugin<Project> {

    /** Where the plugin's manifest lives; processResources puts it at the jar root. */
    public static final String MANIFEST = "src/main/resources/plugin.json";

    /** Where a plugin's declared icons live: {@code <name>.png} for each name in the manifest's {@code "icons"}. */
    public static final String ICONS = "src/main/icons";

    private static final String KOTLIN_PLUGIN_ID = "org.jetbrains.kotlin.jvm";

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply(CarLibraryPlugin.class);
        CarLibraryPlugin.MibsdkExtension ext = project.getExtensions().getByType(CarLibraryPlugin.MibsdkExtension.class);
        if (SdkCoordinates.inSdkRepo(project)) {
            project.evaluationDependsOn(":compat");
            project.evaluationDependsOn(":api");
            Project api = project.project(":api");
            Project compat = project.project(":compat");
            project.getDependencies().add("compileOnly", project.getDependencies().project(
                    Collections.singletonMap("path", ":api")));
            ext.getCarClasspath().from(api.getTasks().named("carJar"));
            ext.getCarClasspath().from(compat.getTasks().named("carJar"));
            project.getDependencies().add("testImplementation", project.getDependencies().project(
                    Collections.singletonMap("path", ":plugin-test")));
        } else {
            // Stand-alone: the published artifacts. The repository comes from the build's settings.gradle.kts.
            project.getDependencies().add("compileOnly", SdkCoordinates.coordinate("api"));
            Configuration carLibs = project.getConfigurations().create("mibsdkCarLibs", cfg -> {
                cfg.setCanBeConsumed(false);
                cfg.setCanBeResolved(true);
                cfg.setTransitive(false);
            });
            project.getDependencies().add("mibsdkCarLibs", SdkCoordinates.coordinate("api") + ":car@jar");
            project.getDependencies().add("mibsdkCarLibs", SdkCoordinates.coordinate("compat") + ":car@jar");
            ext.getCarClasspath().from(carLibs);
            project.getDependencies().add("testImplementation", SdkCoordinates.coordinate("plugin-test"));
        }

        File manifestFile = project.file(MANIFEST);
        File iconsDir = project.file(ICONS);
        Provider<PluginJson> manifest = project.provider(() -> PluginJson.read(manifestFile));
        SourceSet main = project.getExtensions().getByType(JavaPluginExtension.class).getSourceSets()
                .getByName(SourceSet.MAIN_SOURCE_SET_NAME);
        TaskProvider<BackportTask> backport = project.getTasks().named("backport", BackportTask.class);
        Configuration retrotranslatorRuntime = project.getConfigurations().getByName("retrotranslatorRuntime");

        // On the unit as mibsdk-rt-retrotranslator.jar: follow its bodies so a gap inside it is reported.
        project.getTasks().named("linkCheck", LinkCheckTask.class).configure(t ->
                t.getTransitiveLibs().from(retrotranslatorRuntime));
        project.getTasks().named("permissionScan", PermissionScanTask.class).configure(t -> {
            t.getFailOnFindings().set(manifest.map(m -> !m.unrestricted()));
            t.getDeclaredApi().set(manifest.map(m -> m.api));
            t.getDeclaredPermissions().set(manifest.map(m -> m.permissions));
            t.getFailOnUndeclared().set(true);
            t.getOwnClasses().from(main.getOutput().getClassesDirs());
            t.getIconSizes().set(manifest.map(m -> iconSizes(m, iconsDir)));
        });

        TaskProvider<Jar> pluginJar = project.getTasks().register("mibPluginJar", Jar.class, t -> {
            t.setGroup("build");
            t.setDescription("Packs the checked v48 classes and plugin.json into build/mibsdk/<id>.jar.");
            t.from(backport.flatMap(BackportTask::getOutputDir));
            t.from(project.getTasks().named(main.getProcessResourcesTaskName()));
            t.getDestinationDirectory().set(project.getLayout().getBuildDirectory().dir("mibsdk"));
            t.getArchiveFileName().set(manifest.map(m -> m.id + ".jar"));
            t.setPreserveFileTimestamps(false);
            t.setReproducibleFileOrder(true);
            t.dependsOn("classVersionCheck", "linkCheck", "permissionScan");
            t.getInputs().files(project.fileTree(iconsDir)).withPropertyName("icons")
                    .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE);
            t.doFirst("check icons", task -> checkIcons(PluginJson.read(manifestFile), iconsDir));
            Provider<Directory> classes = backport.flatMap(BackportTask::getOutputDir);
            t.getOutputs().file(t.getDestinationDirectory().zip(manifest, (d, m) -> new File(d.getAsFile(), m.id + ".jar.stamp")));
            List<TaskProvider<? extends Task>> required = Arrays.asList(
                    project.getTasks().named("classVersionCheck"), project.getTasks().named("linkCheck"),
                    project.getTasks().named("permissionScan"));
            t.doFirst("drop old stamp", task -> {
                File old = new File(t.getDestinationDirectory().get().getAsFile(),
                        PluginJson.read(manifestFile).id + ".jar.stamp");
                if (old.exists() && !old.delete()) {
                    throw new GradleException("mibsdk.plugin: cannot delete stale stamp " + old);
                }
            });
            t.doLast("check stamp", task -> {
                List<Task> checks = new java.util.ArrayList<>();
                for (TaskProvider<? extends Task> r : required) {
                    checks.add(r.get());
                }
                CheckStamp.requireRan(task, checks);
                try {
                    PluginJson m = PluginJson.read(manifestFile);
                    File jar = new File(t.getDestinationDirectory().get().getAsFile(), m.id + ".jar");
                    Map<String, String> attrs = new LinkedHashMap<>();
                    attrs.put("plugin.id", m.id);
                    attrs.put("plugin.unrestricted", String.valueOf(m.unrestricted()));
                    CheckStamp.write(new File(jar.getPath() + ".stamp"), "plugin",
                            Arrays.asList("classVersion48", "linkCheck", "permissionScan"), attrs,
                            Collections.singletonList(jar));
                } catch (java.io.IOException e) {
                    throw new GradleException("mibsdk.plugin: cannot write the check stamp: " + e, e);
                }
            });
            t.doFirst("verify main class", task -> {
                PluginJson m = PluginJson.read(manifestFile);
                File mainClass = classes.get().file(m.main.replace('.', '/') + ".class").getAsFile();
                if (!mainClass.isFile()) {
                    throw new GradleException("mibsdk.plugin: main class " + m.main + " from " + manifestFile
                            + " is not among the plugin's classes");
                }
            });
        });
        project.getTasks().named("assemble").configure(t -> t.dependsOn(pluginJar));
        wireHostTests(project, pluginJar);

        // mibPackage: <id>-<version>.mpk from the checked jar and its stamp (mibpkg re-checks the stamp), plus the
        // declared icons as icons/<name>.png, checked again with PngCheck as they are staged.
        Provider<Directory> mibsdkDir = project.getLayout().getBuildDirectory().dir("mibsdk");
        Provider<File> stamp = mibsdkDir.zip(manifest, (d, m) -> new File(d.getAsFile(), m.id + ".jar.stamp"));
        Provider<Directory> stageDir = project.getLayout().getBuildDirectory().dir("mibpkg-stage");
        TaskProvider<Sync> stage = project.getTasks().register("mibPackageStage", Sync.class, t -> {
            t.setDescription("Stages manifest.toml, the plugin jar, its check stamp, its icons and their reflections for mibpkg.");
            t.from(pluginJar.flatMap(Jar::getArchiveFile));
            t.from(stamp);
            t.into(stageDir);
            t.dependsOn(pluginJar);
            t.getInputs().property("manifest", manifest.map(MpkStage::pluginManifest));
            t.getInputs().files(project.fileTree(iconsDir)).withPropertyName("icons")
                    .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE);
            t.doFirst("require stamp", task -> {
                if (!stamp.get().isFile()) {
                    throw new GradleException("mibsdk.plugin: " + stamp.get() + " is missing: a check did not run,"
                            + " so the plugin is not packaged");
                }
            });
            t.doLast("write icons and manifest.toml", task -> {
                PluginJson m = PluginJson.read(manifestFile);
                MpkStage.stageIcons(m.icons, iconsDir, t.getDestinationDir(), true);
                try {
                    java.nio.file.Files.write(new File(t.getDestinationDir(), "manifest.toml").toPath(),
                            MpkStage.pluginManifest(m).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                } catch (java.io.IOException e) {
                    throw new GradleException("mibsdk.plugin: cannot write manifest.toml: " + e, e);
                }
            });
        });
        project.getTasks().register("mibPackage", MibpkgBuildTask.class, t -> {
            t.setGroup("build");
            t.setDescription("Builds build/mibsdk/<id>-<version>.mpk with the host mibpkg (-Pmibsdk.mibpkg=<path>).");
            t.dependsOn(pluginJar, stage);
            t.getStage().set(stageDir);
            t.getMibpkg().set(Toolchain.mibpkg(project));
            t.getBinary().set(Toolchain.mibpkgBinary(project));
            t.getMpk().set(mibsdkDir.zip(manifest, (d, m) -> d.file(m.id + "-" + m.version + ".mpk")));
        });

        project.getPluginManager().withPlugin(KOTLIN_PLUGIN_ID, applied ->
                configureKotlin(project, main, manifest, backport));
    }

    /**
     * Host tests load the real built plugin jar, as the unit does: every {@code Test} task runs after {@code mibPluginJar}
     * (and re-runs when the jar or the icons change) and gets {@code -Dmibsdk.pluginJar} and {@code -Dmibsdk.pluginIcons},
     * which {@code PluginTestHarness} reads.
     */
    private static void wireHostTests(Project project, TaskProvider<Jar> pluginJar) {
        Provider<RegularFile> jar = pluginJar.flatMap(Jar::getArchiveFile);
        File iconsDir = project.file(ICONS);
        project.getTasks().withType(Test.class).configureEach(t -> {
            t.getInputs().file(jar).withPropertyName("pluginJar")
                    .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.NONE);
            t.getInputs().files(project.fileTree(iconsDir)).withPropertyName("pluginIcons")
                    .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE);
            t.getJvmArgumentProviders().add(() -> Arrays.asList(
                    "-Dmibsdk.pluginJar=" + jar.get().getAsFile().getAbsolutePath(),
                    "-Dmibsdk.pluginIcons=" + iconsDir.getAbsolutePath()));
        });
    }

    /** Every declared icon exists as {@code <iconsDir>/<name>.png} and passes {@link PngCheck}; else the build fails. */
    static void checkIcons(PluginJson manifest, File iconsDir) {
        for (String name : manifest.icons) {
            File png = new File(iconsDir, name + ".png");
            if (!png.isFile()) {
                throw new GradleException("mibsdk.plugin: icon '" + name + "' is declared in plugin.json but "
                        + ICONS + "/" + name + ".png does not exist");
            }
            try {
                PngCheck.check(java.nio.file.Files.readAllBytes(png.toPath()), PngCheck.MAX_PX);
            } catch (IllegalArgumentException e) {
                throw new GradleException("mibsdk.plugin: " + png + ": " + e.getMessage(), e);
            } catch (java.io.IOException e) {
                throw new GradleException("mibsdk.plugin: cannot read " + png + ": " + e, e);
            }
        }
    }

    /**
     * Each declared icon's larger side in pixels, by name: what {@code permissionScan} needs to tell a status-bar
     * call site it has no icon small enough. A missing or invalid PNG is left out silently; {@link #checkIcons}
     * fails the build for it with the message that names the file.
     */
    static Map<String, Integer> iconSizes(PluginJson manifest, File iconsDir) {
        Map<String, Integer> sizes = new LinkedHashMap<>();
        for (String name : manifest.icons) {
            File png = new File(iconsDir, name + ".png");
            try {
                PngCheck.Info info = PngCheck.check(java.nio.file.Files.readAllBytes(png.toPath()), PngCheck.MAX_PX);
                sizes.put(name, Math.max(info.width, info.height));
            } catch (java.io.IOException | IllegalArgumentException e) {
                // checkIcons reports it.
            }
        }
        return sizes;
    }

    private static void configureKotlin(Project project, SourceSet main, Provider<PluginJson> manifest,
            TaskProvider<BackportTask> backport) {
        project.getTasks().matching(t -> t.getName().equals("compileKotlin")).configureEach(MibPluginPlugin::kotlinOptions);

        Configuration proguard = project.getConfigurations().create("proguard", cfg -> {
            cfg.setCanBeConsumed(false);
            cfg.setCanBeResolved(true);
            cfg.setTransitive(false);
        });
        for (String coordinate : Toolchain.PROGUARD) {
            project.getDependencies().add("proguard", coordinate);
        }
        // The stdlib the Kotlin plugin added (its own version), shrunk into the plugin jar.
        FileCollection stdlib = project.getConfigurations().getByName(main.getRuntimeClasspathConfigurationName())
                .filter(f -> f.getName().startsWith("kotlin-stdlib"));
        FileCollection libraries = project.getConfigurations().getByName(main.getCompileClasspathConfigurationName())
                .filter(f -> !f.getName().startsWith("kotlin-stdlib"));

        TaskProvider<KotlinShrinkTask> shrink = project.getTasks().register("kotlinShrink", KotlinShrinkTask.class, t -> {
            t.setGroup("build");
            t.setDescription("Shrinks the plugin with the kotlin-stdlib it uses and drops LocalVariableTable (ProGuard).");
            t.getProguard().from(proguard);
            t.getProgramClasses().from(main.getOutput().getClassesDirs());
            t.getKotlinStdlib().from(stdlib);
            t.getLibraries().from(libraries);
            t.getMainClass().set(manifest.map(m -> m.main));
            t.getJdk8Home().set(Toolchain.jdk8Home(project));
            t.getOutputDir().set(project.getLayout().getBuildDirectory().dir("kshrunk"));
            t.dependsOn(main.getClassesTaskName());
        });
        backport.configure(t -> t.getInputClasses().setFrom(shrink.flatMap(KotlinShrinkTask::getOutputDir)));
        project.getTasks().named("permissionScan", PermissionScanTask.class).configure(t ->
                t.getKotlinStdlib().from(stdlib));
    }

    /**
     * {@code compilerOptions { jvmTarget = 1.8; freeCompilerArgs += KOTLIN_FLAGS }}, by reflection so
     * build-logic needs no compile-time Kotlin Gradle plugin (whichever version the plugin author applies).
     */
    @SuppressWarnings("unchecked")
    private static void kotlinOptions(Task task) {
        try {
            Method getOptions = task.getClass().getMethod("getCompilerOptions");
            Object options = getOptions.invoke(task);
            Class<?> optionsType = getOptions.getReturnType();
            Class<?> jvmTarget = Class.forName("org.jetbrains.kotlin.gradle.dsl.JvmTarget", true,
                    optionsType.getClassLoader());
            Object target = jvmTarget.getMethod("valueOf", String.class)
                    .invoke(null, "JVM_" + Toolchain.KOTLIN_JVM_TARGET.replace('.', '_'));
            ((Property<Object>) optionsType.getMethod("getJvmTarget").invoke(options)).set(target);
            ((ListProperty<String>) optionsType.getMethod("getFreeCompilerArgs").invoke(options))
                    .addAll(Toolchain.KOTLIN_FLAGS);
        } catch (ReflectiveOperationException | ClassCastException e) {
            throw new GradleException("mibsdk.plugin: cannot configure " + task.getPath()
                    + " (unsupported Kotlin Gradle plugin version?): " + e, e);
        }
    }
}
