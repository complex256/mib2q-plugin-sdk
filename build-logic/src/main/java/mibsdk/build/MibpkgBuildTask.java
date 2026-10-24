package mibsdk.build;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/** Runs the host {@code mibpkg build <stage> <outDir>}: the stage directory becomes one {@code .mpk}. */
@CacheableTask
public abstract class MibpkgBuildTask extends DefaultTask {
    /** {@code manifest.toml} and the files it names. */
    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getStage();

    /** The command as configured (a path, or a bare name looked up on {@code PATH}); only for messages. */
    @Internal
    public abstract Property<String> getMibpkg();

    /**
     * The resolved binary, hashed by content: a rebuilt (stricter) mibpkg reruns the task, and a missing one
     * (absent here) always reruns it, so the missing-binary failure cannot be hidden by an up-to-date check.
     */
    @Optional
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getBinary();

    /** When set, other {@code <prefix>*.mpk} files beside the output are deleted once the binary is found. */
    @Optional
    @Input
    public abstract Property<String> getStalePrefix();

    @OutputFile
    public abstract RegularFileProperty getMpk();

    @TaskAction
    public void build() throws IOException, InterruptedException {
        File binary = getBinary().isPresent() ? getBinary().get().getAsFile() : null;
        if (binary == null || !binary.canExecute()) {
            throw new GradleException("host mibpkg not found: build it (cargo build --release in the mibpkg repo)"
                    + " and pass -Pmibsdk.mibpkg=<path> (looked for '" + getMibpkg().get() + "')");
        }
        File mpk = getMpk().get().getAsFile();
        File outDir = mpk.getParentFile();
        if (!outDir.isDirectory() && !outDir.mkdirs()) {
            throw new GradleException("cannot create " + outDir);
        }
        File[] stale = outDir.listFiles((d, n) -> getStalePrefix().isPresent()
                && n.startsWith(getStalePrefix().get()) && n.endsWith(".mpk"));
        for (File f : stale == null ? new File[0] : stale) {
            if (!f.delete()) {
                throw new GradleException("cannot delete the old " + f);
            }
        }
        if (mpk.exists() && !mpk.delete()) {
            throw new GradleException("cannot delete the old " + mpk);
        }
        Process p = new ProcessBuilder(binary.getPath(), "build", getStage().get().getAsFile().getPath(), outDir.getPath())
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) {
            throw new GradleException("mibpkg build failed for " + getStage().get().getAsFile() + ":\n" + out);
        }
        if (!mpk.isFile()) {
            throw new GradleException("mibpkg build did not produce " + mpk + ":\n" + out);
        }
    }
}
