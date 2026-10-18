package mibsdk.build;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.IgnoreEmptyDirectories;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/** Fails listing every class, member or header the checked classes need that the car does not have. */
@CacheableTask
public abstract class LinkCheckTask extends DefaultTask {

    /** Directories or jars holding the classes that will run on the car. */
    @InputFiles
    @IgnoreEmptyDirectories
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getChecked();

    /** Car JCL, the module's own output and the declared car classpath. */
    @InputFiles
    @IgnoreEmptyDirectories
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getClasspath();

    /** Jars whose method bodies are followed from each referenced member (empty by default). */
    @InputFiles
    @PathSensitive(PathSensitivity.NONE)
    public abstract ConfigurableFileCollection getTransitiveLibs();

    @OutputFile
    public abstract RegularFileProperty getReport();

    @TaskAction
    public void check() throws IOException {
        List<File> classpath = List.copyOf(getClasspath().getFiles());
        List<File> libs = List.copyOf(getTransitiveLibs().getFiles());
        java.util.TreeSet<String> missing = new java.util.TreeSet<String>();
        for (File checked : getChecked().getFiles()) {
            if (checked.exists()) {
                missing.addAll(LinkCheck.run(checked, classpath, libs).missing());
            }
        }
        String report = String.join("\n", missing) + (missing.isEmpty() ? "" : "\n");
        Files.write(getReport().get().getAsFile().toPath(), report.getBytes(StandardCharsets.UTF_8));
        if (!missing.isEmpty()) {
            throw new GradleException("LinkCheck: " + missing.size() + " reference(s) the car cannot resolve:\n  "
                    + String.join("\n  ", missing));
        }
    }
}
