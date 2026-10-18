package mibsdk.build;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.TreeSet;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.IgnoreEmptyDirectories;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/**
 * Lists calls that need {@code platform.unrestricted} (reflection, exec, exit, ...). Report-only unless
 * {@link #getFailOnFindings()} is set; the {@code mibsdk.plugin} convention (Task 13) sets it unless
 * the manifest grants {@code platform.unrestricted}.
 */
@CacheableTask
public abstract class PermissionScanTask extends DefaultTask {

    @InputFiles
    @IgnoreEmptyDirectories
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getClasses();

    /** Fail the build on findings (default false: report only). */
    @Input
    public abstract Property<Boolean> getFailOnFindings();

    @OutputFile
    public abstract RegularFileProperty getReport();

    @TaskAction
    public void scan() throws IOException {
        TreeSet<String> findings = new TreeSet<String>();
        for (File f : getClasses().getFiles()) {
            if (f.exists()) {
                findings.addAll(PermissionScan.run(f).findings());
            }
        }
        String report = String.join("\n", findings) + (findings.isEmpty() ? "" : "\n");
        Files.write(getReport().get().getAsFile().toPath(), report.getBytes(StandardCharsets.UTF_8));
        if (findings.isEmpty()) {
            return;
        }
        String text = findings.size() + " call(s) need platform.unrestricted:\n  " + String.join("\n  ", findings);
        if (getFailOnFindings().get()) {
            throw new GradleException("permissionScan: " + text);
        }
        getLogger().lifecycle("permissionScan (report only): {}", text);
    }
}
