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

    /** The binary: a path, or a bare name looked up on {@code PATH}. Its path (not its content) is the input. */
    @Input
    public abstract Property<String> getMibpkg();

    @OutputFile
    public abstract RegularFileProperty getMpk();

    @TaskAction
    public void build() throws IOException, InterruptedException {
        File binary = Toolchain.resolveExecutable(getMibpkg().get());
        if (binary == null) {
            throw new GradleException("host mibpkg not found: build it (cargo build --release in the mibpkg repo)"
                    + " and pass -Pmibsdk.mibpkg=<path> (looked for '" + getMibpkg().get() + "')");
        }
        File mpk = getMpk().get().getAsFile();
        File outDir = mpk.getParentFile();
        if (!outDir.isDirectory() && !outDir.mkdirs()) {
            throw new GradleException("cannot create " + outDir);
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
