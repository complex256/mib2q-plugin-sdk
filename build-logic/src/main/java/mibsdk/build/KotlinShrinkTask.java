package mibsdk.build;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ArchiveOperations;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.CompileClasspath;
import org.gradle.api.tasks.IgnoreEmptyDirectories;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.process.ExecOperations;
import org.gradle.process.ExecResult;

/**
 * Kotlin plugins: shrinks the plugin's classes together with kotlin-stdlib (ProGuard under JDK 8), so the
 * jar carries only the stdlib it uses. Keeps the plugin's main class whole; the {@link
 * Toolchain#KOTLIN_PROGUARD_RULES} then keep every name but drop LocalVariableTable, whose Kotlin
 * {@code $i$a$-...} names are illegal in v48 class files. The output feeds {@code backport}.
 */
@CacheableTask
public abstract class KotlinShrinkTask extends DefaultTask {

    /** ProGuard and its runtime ({@link Toolchain#PROGUARD}). */
    @Classpath
    public abstract ConfigurableFileCollection getProguard();

    /** The plugin's compiled classes (Kotlin and Java output dirs). */
    @InputFiles
    @IgnoreEmptyDirectories
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getProgramClasses();

    /** kotlin-stdlib jars: shrunk into the output. */
    @Classpath
    public abstract ConfigurableFileCollection getKotlinStdlib();

    /** Compile classpath minus the stdlib (the SDK api, ...): resolution only, never copied. */
    @CompileClasspath
    public abstract ConfigurableFileCollection getLibraries();

    /** The plugin's main class (from {@code plugin.json}), kept with all its members. */
    @Input
    public abstract Property<String> getMainClass();

    @Input
    public abstract Property<String> getJdk8Home();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDir();

    @Inject
    protected abstract ExecOperations getExecOps();

    @Inject
    protected abstract FileSystemOperations getFileOps();

    @Inject
    protected abstract ArchiveOperations getArchiveOps();

    @TaskAction
    public void shrink() throws IOException {
        File jdk8 = new File(getJdk8Home().get());
        File out = getOutputDir().get().getAsFile();
        // One output jar, unpacked afterwards: a directory -outjars would keep the stdlib as a nested jar.
        File shrunk = new File(getTemporaryDir(), "shrunk.jar");
        getFileOps().delete(d -> d.delete(out, shrunk));

        List<String> config = new ArrayList<String>();
        for (File f : getProgramClasses().getFiles()) {
            if (f.exists()) {
                config.add("-injars " + quote(f));
            }
        }
        for (File f : getKotlinStdlib().getFiles()) {
            config.add("-injars " + quote(f) + "(!META-INF/**)");
        }
        config.add("-outjars " + quote(shrunk));
        config.add("-libraryjars " + quote(new File(jdk8, "jre/lib/rt.jar")));
        for (File f : getLibraries().getFiles()) {
            if (f.exists()) {
                config.add("-libraryjars " + quote(f));
            }
        }
        config.add("-keep class " + getMainClass().get() + " { *; }");
        config.addAll(Toolchain.KOTLIN_PROGUARD_RULES);
        config.add(Toolchain.KOTLIN_PROGUARD_INTRINSICS_RULE);

        File pro = new File(getTemporaryDir(), "shrink.pro");
        Files.write(pro.toPath(), String.join("\n", config).concat("\n").getBytes(StandardCharsets.UTF_8));

        ByteArrayOutputStream log = new ByteArrayOutputStream();
        ExecResult result = getExecOps().exec(e -> {
            e.setExecutable(new File(jdk8, "bin/java"));
            e.args("-cp", getProguard().getAsPath(), "proguard.ProGuard", "@" + pro.getPath());
            e.setStandardOutput(log);
            e.setErrorOutput(log);
            e.setIgnoreExitValue(true);
        });
        String text = log.toString(StandardCharsets.UTF_8);
        Files.write(new File(getTemporaryDir(), "proguard.log").toPath(), text.getBytes(StandardCharsets.UTF_8));
        if (result.getExitValue() != 0) {
            throw new GradleException("ProGuard failed (exit " + result.getExitValue() + "), config " + pro + ":\n" + text);
        }
        getFileOps().copy(c -> c.from(getArchiveOps().zipTree(shrunk)).into(out));
    }

    private static String quote(File f) {
        return "'" + f.getAbsolutePath() + "'";
    }
}
