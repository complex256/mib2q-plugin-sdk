package mibsdk.build;

import java.io.File;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/**
 * The root build's {@code newPlugin}: writes a stand-alone plugin project from {@code templates/plugin} at
 * {@code -Pdir=<path>}, renamed to {@code -Pid=<id>} (package {@code -Ppackage}, default {@code plugin.<id without
 * dashes>}), with {@code mibsdk.repo} pointing at this checkout's {@code build/repo}.
 *
 * <p>It refuses an id that {@link PluginJson} would refuse (same sentence), a destination that exists and is not empty,
 * and an SDK repository this checkout has not published yet.
 */
public abstract class NewPluginTask extends DefaultTask {

    @Inject
    public NewPluginTask() {
        setGroup("help");
        setDescription("Writes a new plugin project from templates/plugin: -Pid=<id> -Pdir=<path> "
                + "[-Ppackage=<java.package>].");
    }

    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getTemplateDir();

    /** The plugin id, as {@code plugin.json} would carry it; checked in the action, so a missing one reads well. */
    @Input
    @Optional
    public abstract Property<String> getPluginId();

    /** The Java package, or empty for {@code plugin.<id without dashes>}. */
    @Input
    @Optional
    public abstract Property<String> getPackageName();

    /** The SDK file repository the generated project builds against; derived from this build's layout. */
    @Internal
    public abstract DirectoryProperty getRepoDir();

    @Internal
    public abstract DirectoryProperty getDestinationDir();

    @TaskAction
    public void scaffold() {
        String id = getPluginId().getOrNull();
        if (id == null || id.trim().isEmpty()) {
            throw new GradleException("mibsdk.plugin: newPlugin: set -Pid=<id>, for example"
                    + " -Pid=my-plugin -Pdir=../my-plugin (2 to 40 characters: a-z, 0-9 and '-')");
        }
        id = id.trim();
        String problem = PluginJson.idProblem(id);
        if (problem != null) {
            throw new GradleException("mibsdk.plugin: -Pid=" + id + ": " + problem);
        }
        DirectoryProperty destination = getDestinationDir();
        if (!destination.isPresent()) {
            throw new GradleException("mibsdk.plugin: newPlugin: set -Pdir=<path>, for example"
                    + " -Pid=" + id + " -Pdir=../" + id);
        }
        String javaPackage = getPackageName().getOrNull();
        javaPackage = javaPackage == null || javaPackage.trim().isEmpty()
                ? PluginScaffold.defaultPackage(id) : javaPackage.trim();
        if (!javaPackage.matches("([A-Za-z_$][A-Za-z0-9_$]*\\.)*[A-Za-z_$][A-Za-z0-9_$]*")) {
            throw new GradleException("mibsdk.plugin: newPlugin: -Ppackage=" + javaPackage
                    + " is not a Java package name (segments start with a letter, '_' or '$')");
        }
        String className = PluginScaffold.className(id);
        if (!className.matches("[A-Za-z_$][A-Za-z0-9_$]*")) {
            throw new GradleException("mibsdk.plugin: newPlugin: the id '" + id + "' gives the class name '"
                    + className + "', which is not a Java identifier; start the id with a letter and pass"
                    + " -Ppackage=<java.package> if its package needs one");
        }
        File repo = getRepoDir().get().getAsFile();
        if (!repo.isDirectory()) {
            throw new GradleException("mibsdk.plugin: newPlugin: " + repo + " does not exist: run"
                    + " `./gradlew publishLocal` in this checkout first, so the new project can resolve the SDK"
                    + " from it");
        }
        PluginScaffold.create(getTemplateDir().get().getAsFile(), destination.get().getAsFile(), id, javaPackage,
                repo.toURI().toString());
        getLogger().lifecycle("Created the plugin '{}' in {} (package {}, class {}). It builds against {};"
                + " run ./gradlew mibPluginJar there.", id, destination.get().getAsFile(), javaPackage,
                className, repo);
    }
}
