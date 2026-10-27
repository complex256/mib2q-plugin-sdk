package mibsdk.build;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
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
        TreeSet<String> missing = new TreeSet<String>();
        Map<String, TreeSet<String>> sites = new HashMap<String, TreeSet<String>>();
        for (File checked : getChecked().getFiles()) {
            if (checked.exists()) {
                LinkCheck.Result result = LinkCheck.run(checked, classpath, libs);
                missing.addAll(result.missing());
                for (Map.Entry<String, List<String>> e : result.sites().entrySet()) {
                    TreeSet<String> named = sites.get(e.getKey());
                    if (named == null) {
                        named = new TreeSet<String>();
                        sites.put(e.getKey(), named);
                    }
                    named.addAll(e.getValue());
                }
            }
        }
        List<String> blocks = new ArrayList<String>();
        for (String line : missing) {
            blocks.add(block(line, sites.get(line)));
        }
        String report = blocks.isEmpty() ? "" : String.join("\n", blocks) + "\n";
        Files.write(getReport().get().getAsFile().toPath(), report.getBytes(StandardCharsets.UTF_8));
        if (!missing.isEmpty()) {
            throw new GradleException("LinkCheck: " + missing.size() + " reference(s) the car cannot resolve:\n  "
                    + String.join("\n  ", blocks));
        }
    }

    /** One missing reference: the line, its source name when rewritten, the referencing methods and a hint. */
    private static String block(String line, Collection<String> sites) {
        StringBuilder b = new StringBuilder(line);
        String owner = LinkCheckHints.ownerOf(line);
        if (owner != null) {
            String jdk = LinkCheckHints.jdkName(owner);
            if (!jdk.equals(owner)) {
                b.append(" (").append(jdk).append(" in your source)");
            }
        }
        if (sites != null) {
            for (String site : sites) {
                b.append("\n    used by ").append(site);
            }
        }
        String hint = LinkCheckHints.hint(line);
        if (hint != null) {
            b.append("\n    hint: ").append(hint);
        }
        return b.toString();
    }
}
