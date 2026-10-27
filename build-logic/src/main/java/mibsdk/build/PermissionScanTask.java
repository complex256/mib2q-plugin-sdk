package mibsdk.build;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
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
 * ({@link ApiLevels}), naming each such site with its own level ({@link PermissionScan.Result#tooNew(int)}).
 * With {@link #getFailOnUndeclared()} set (the {@code mibsdk.plugin} convention, also under
 * {@code platform.unrestricted}) it fails when the classes call API that needs a permission {@link
 * #getDeclaredPermissions()} lacks ({@link PermissionScan.Result#undeclared}); otherwise it only reports.
 *
 * <p>It warns, but never fails, when a status-bar call site passes an icon and no icon in {@link #getIconSizes()}
 * is within {@link PermissionScan#STATUS_ICON_MAX_PX}: that icon throws at runtime.
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

    /** The manifest's {@code "permissions"}; the {@code mibsdk.plugin} convention reads them from {@code plugin.json}. */
    @Input
    public abstract ListProperty<String> getDeclaredPermissions();

    /** Fail the build on API calls whose permission is undeclared (default false: report only, as for a library). */
    @Input
    public abstract Property<Boolean> getFailOnUndeclared();

    /**
     * Each declared icon's larger side, in pixels, by name (the {@code mibsdk.plugin} convention reads them from
     * {@code src/main/icons} with {@link PngCheck}). Used only to warn: a status-bar call site that passes an icon
     * when no declared icon is within {@link PermissionScan#STATUS_ICON_MAX_PX} throws at runtime.
     */
    @Input
    public abstract MapProperty<String, Integer> getIconSizes();

    @OutputFile
    public abstract RegularFileProperty getReport();

    @TaskAction
    public void scan() throws IOException {
        Set<String> exempt = kotlinClassNames(getKotlinStdlib().getFiles());
        exempt.removeAll(kotlinClassNames(getOwnClasses().getFiles()));
        TreeSet<String> findings = new TreeSet<String>();
        TreeSet<String> tooNew = new TreeSet<String>();
        TreeSet<String> undeclared = new TreeSet<String>();
        TreeSet<String> warnings = new TreeSet<String>();
        List<String> declaredPermissions = getDeclaredPermissions().get();
        Map<String, Integer> iconSizes = getIconSizes().getOrElse(java.util.Collections.<String, Integer>emptyMap());
        int declared = getDeclaredApi().get();
        for (File f : getClasses().getFiles()) {
            if (f.exists()) {
                PermissionScan.Result r = PermissionScan.run(f, exempt);
                findings.addAll(r.findings());
                undeclared.addAll(r.undeclared(declaredPermissions));
                warnings.addAll(r.warnings(iconSizes));
                for (String line : r.tooNew(declared)) {
                    tooNew.add(line + "; plugin.json declares api " + declared);
                }
            }
        }
        TreeSet<String> lines = new TreeSet<String>(findings);
        lines.addAll(tooNew); // the api-check reason is in the report too, not only in the exception
        lines.addAll(undeclared);
        lines.addAll(warnings);
        String report = String.join("\n", lines) + (lines.isEmpty() ? "" : "\n");
        Files.write(getReport().get().getAsFile().toPath(), report.getBytes(StandardCharsets.UTF_8));
        for (String warning : warnings) {
            getLogger().warn(warning); // not a failure: the icon only throws if the plugin passes that one
        }
        if (!tooNew.isEmpty()) {
            throw new GradleException("permissionScan: " + String.join("\n  ", tooNew));
        }
        if (!undeclared.isEmpty()) {
            String undeclaredText = "API calls need permissions plugin.json does not declare:\n  "
                    + String.join("\n  ", undeclared);
            if (getFailOnUndeclared().get()) {
                throw new GradleException("permissionScan: " + undeclaredText);
            }
            getLogger().lifecycle("permissionScan (report only): {}", undeclaredText);
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
