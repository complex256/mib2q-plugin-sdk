package mibsdk.build;

import java.io.File;
import java.lang.reflect.Method;
import java.util.Collections;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.Directory;
import org.gradle.api.file.FileCollection;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.Jar;

/**
 * Convention plugin {@code mibsdk.plugin}: a third-party plugin built on {@code mibsdk.car-library}.
 *
 * <p>Java: compile (JDK 17 {@code --release 8} + Jabel, {@code compileOnly} on {@code :api}) → {@code backport}
 * → {@code linkCheck} (car JCL + api + compat car jars; the Retrotranslator runtime followed transitively;
 * nothing else: the plugin class loader hides platform, runtime and stock classes) → {@code permissionScan}
 * (fails unless {@code plugin.json} lists {@code platform.unrestricted}) → {@code mibPluginJar} =
 * {@code build/mibsdk/<id>.jar} with {@code plugin.json} at the root.
 *
 * <p>Kotlin ({@code org.jetbrains.kotlin.jvm} applied): {@link Toolchain#KOTLIN_FLAGS}, {@code jvmTarget=1.8},
 * and {@code kotlinShrink} ({@link KotlinShrinkTask}) between the compile and {@code backport}.
 */
public class MibPluginPlugin implements Plugin<Project> {

    /** Where the plugin's manifest lives; processResources puts it at the jar root. */
    public static final String MANIFEST = "src/main/resources/plugin.json";

    private static final String KOTLIN_PLUGIN_ID = "org.jetbrains.kotlin.jvm";

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply(CarLibraryPlugin.class);
        project.evaluationDependsOn(":compat");
        project.evaluationDependsOn(":api");
        Project api = project.project(":api");
        Project compat = project.project(":compat");

        project.getDependencies().add("compileOnly", project.getDependencies().project(
                Collections.singletonMap("path", ":api")));
        CarLibraryPlugin.MibsdkExtension ext = project.getExtensions().getByType(CarLibraryPlugin.MibsdkExtension.class);
        ext.getCarClasspath().from(api.getTasks().named("carJar"));
        ext.getCarClasspath().from(compat.getTasks().named("carJar"));

        File manifestFile = project.file(MANIFEST);
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
            t.getOwnClasses().from(main.getOutput().getClassesDirs());
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
            Provider<Directory> classes = backport.flatMap(BackportTask::getOutputDir);
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

        project.getPluginManager().withPlugin(KOTLIN_PLUGIN_ID, applied ->
                configureKotlin(project, main, manifest, backport));
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
            t.getJdk8Home().set(project.provider(() -> Toolchain.jdk8Home(project).getPath()));
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
