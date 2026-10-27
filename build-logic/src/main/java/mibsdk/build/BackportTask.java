package mibsdk.build;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.process.ExecOperations;
import org.gradle.process.ExecResult;

/**
 * Class files of any version up to 52 to Java 1.4 class files (v48):
 * Retrolambda (lambdas, default methods, try-with-resources; to v49) then Retrotranslator
 * (v49 to v48, missing JDK APIs redirected to {@code mibsdk.compat.*}). Retrotranslator's runtime is
 * not embedded: output references {@code net/sf/retrotranslator/runtime/**}, shipped once.
 */
@CacheableTask
public abstract class BackportTask extends DefaultTask {

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getInputClasses();

    /** Exactly one jar: retrolambda-2.5.7. */
    @Classpath
    public abstract ConfigurableFileCollection getRetrolambda();

    /** retrotranslator-transformer and -runtime jars. */
    @Classpath
    public abstract ConfigurableFileCollection getRetrotranslator();

    /** The compat shim classes Retrotranslator looks up for the {@code -backport} mapping. */
    @Classpath
    public abstract ConfigurableFileCollection getCompatClasses();

    /** The module's declared car classpath (resolution only for Retrolambda). */
    @Classpath
    public abstract ConfigurableFileCollection getCarClasspath();

    @Input
    public abstract Property<String> getJdk8Home();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDir();

    @Inject
    protected abstract ExecOperations getExecOps();

    @Inject
    protected abstract FileSystemOperations getFileOps();

    @TaskAction
    public void backport() {
        File jdk8 = new File(getJdk8Home().get());
        File java = new File(jdk8, "bin/java");
        File rtJar = new File(jdk8, "jre/lib/rt.jar");
        File work = getTemporaryDir();
        File in = new File(work, "v52");
        File v49 = new File(work, "v49");
        File out = getOutputDir().get().getAsFile();

        getFileOps().delete(d -> d.delete(in, v49, out));
        getFileOps().copy(c -> c.from(getInputClasses()).into(in));
        v49.mkdirs();
        out.mkdirs();

        String sep = File.pathSeparator;
        String carCp = getCarClasspath().getAsPath();
        String retrolambdaCp = in + sep + (carCp.isEmpty() ? "" : carCp + sep) + rtJar;
        run("Retrolambda", java,
                "-Dretrolambda.inputDir=" + in,
                "-Dretrolambda.outputDir=" + v49,
                "-Dretrolambda.classpath=" + retrolambdaCp,
                "-Dretrolambda.bytecodeVersion=49",
                "-Dretrolambda.defaultMethods=true",
                "-jar", getRetrolambda().getSingleFile().getPath());

        String compatCp = getCompatClasses().getAsPath();
        String rtCp = getRetrotranslator().getAsPath() + (compatCp.isEmpty() ? "" : sep + compatCp);
        run("Retrotranslator", java,
                "-cp", rtCp,
                "net.sf.retrotranslator.transformer.Retrotranslator",
                "-srcdir", v49.getPath(),
                "-destdir", out.getPath(),
                "-target", "1.4",
                "-stripsign",
                "-backport", Toolchain.BACKPORT);
    }

    /**
     * Runs one step and logs its output at INFO: a default build shows nothing, {@code --info} shows the tool's
     * own log and the classpath it was given. A line that says WARNING or ERROR, and anything on stderr, is logged
     * at WARN or ERROR so it stays visible; a non-zero exit logs both streams at ERROR and fails the task.
     */
    private void run(String step, File java, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        ExecResult result = getExecOps().exec(e -> {
            e.setExecutable(java);
            e.args((Object[]) args);
            e.setStandardOutput(out);
            e.setErrorOutput(err);
            e.setIgnoreExitValue(true);
        });
        String stdout = new String(out.toByteArray(), StandardCharsets.UTF_8);
        String stderr = new String(err.toByteArray(), StandardCharsets.UTF_8);
        if (result.getExitValue() != 0) {
            getLogger().error("{} failed (exit {}):\n{}{}", step, result.getExitValue(), stdout, stderr);
            throw new GradleException(step + " failed (exit " + result.getExitValue() + "), see the output above");
        }
        logLines(stdout, false);
        logLines(stderr, true);
    }

    private void logLines(String text, boolean stderr) {
        for (String line : text.split("\n")) {
            String l = line.trim();
            if (l.isEmpty()) {
                continue;
            }
            if (stderr) {
                getLogger().warn(l);
            } else if (l.contains("ERROR") || l.contains("error:")) {
                getLogger().error(l);
            } else if (l.contains("WARNING") || l.contains("Warning") || l.contains("warning:")) {
                getLogger().warn(l);
            } else {
                getLogger().info(l);
            }
        }
    }
}
