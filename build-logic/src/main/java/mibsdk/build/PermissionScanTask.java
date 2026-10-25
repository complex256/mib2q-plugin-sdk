package mibsdk.build;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.IgnoreEmptyDirectories;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/**
 * Lists calls that need {@code platform.unrestricted} (reflection, exec, exit, ...). Report-only unless
 * {@link #getFailOnFindings()} is set; the {@code mibsdk.plugin} convention sets it unless the manifest
 * grants {@code platform.unrestricted}. Always fails when the classes use API newer than {@link #getDeclaredApi()}
 * ({@link PermissionScan.Result#needsApi()}).
 */
@CacheableTask
public abstract class PermissionScanTask extends DefaultTask {

    @InputFiles
    @IgnoreEmptyDirectories
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getClasses();

    /**
     * kotlin-stdlib jars shrunk into the scanned classes. Their {@code kotlin/} classes are exempt (the stdlib
     * legitimately reflects), except those {@link #getOwnClasses()} also declares.
     */
    @Classpath
    public abstract ConfigurableFileCollection getKotlinStdlib();

    /** The module's own compiled classes (pre-shrink); a {@code kotlin/} class found here is never exempt. */
    @InputFiles
    @IgnoreEmptyDirectories
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getOwnClasses();

    /** Fail the build on findings (default false: report only). */
    @Input
    public abstract Property<Boolean> getFailOnFindings();

    /** The manifest's {@code "api"}; the {@code mibsdk.plugin} convention reads it from {@code plugin.json}. */
    @Input
    public abstract Property<Integer> getDeclaredApi();

    @OutputFile
    public abstract RegularFileProperty getReport();

    @TaskAction
    public void scan() throws IOException {
        Set<String> exempt = kotlinClassNames(getKotlinStdlib().getFiles());
        exempt.removeAll(kotlinClassNames(getOwnClasses().getFiles()));
        TreeSet<String> findings = new TreeSet<String>();
        TreeSet<String> tooNew = new TreeSet<String>();
        int declared = getDeclaredApi().get();
        for (File f : getClasses().getFiles()) {
            if (f.exists()) {
                PermissionScan.Result r = PermissionScan.run(f, exempt);
                findings.addAll(r.findings());
                if (r.needsApi() > declared) {
                    for (String site : r.apiFindings()) {
                        tooNew.add(site + " needs api " + r.needsApi() + "; plugin.json declares api " + declared);
                    }
                }
            }
        }
        TreeSet<String> lines = new TreeSet<String>(findings);
        lines.addAll(tooNew); // the api-check reason is in the report too, not only in the exception
        String report = String.join("\n", lines) + (lines.isEmpty() ? "" : "\n");
        Files.write(getReport().get().getAsFile().toPath(), report.getBytes(StandardCharsets.UTF_8));
        if (!tooNew.isEmpty()) {
            throw new GradleException("permissionScan: " + String.join("\n  ", tooNew));
        }
        if (findings.isEmpty()) {
            return;
        }
        String text = findings.size() + " call(s) need platform.unrestricted:\n  " + String.join("\n  ", findings);
        if (getFailOnFindings().get()) {
            throw new GradleException("permissionScan: " + text);
        }
        getLogger().lifecycle("permissionScan (report only): {}", text);
    }

    private static Set<String> kotlinClassNames(Set<File> dirsOrJars) throws IOException {
        Set<String> names = new HashSet<String>();
        for (File f : dirsOrJars) {
            ClassSource.forEach(f, e -> {
                if (e.name.startsWith("kotlin/")) {
                    names.add(e.name);
                }
            });
        }
        return names;
    }
}
