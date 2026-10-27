package mibsdk.build;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/**
 * Writes the car JCL signature list ({@link CarJclSignatures}) from the stock {@code lsd.jar}. As
 * {@code carJclSignatures} it regenerates the committed list; as {@code carJclSignaturesCheck} it writes
 * under {@code build/} and fails when the result differs from the bundled list. Maintainer tooling: the
 * tasks exist only when {@code -Pmibsdk.stockLsdJar} is given. Always runs, so a stale output cannot hide drift.
 */
public abstract class CarJclSignaturesTask extends DefaultTask {

    /** The stock lsd.jar (jxe2jar output). Never committed. */
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getStockLsdJar();

    /** The {@code # source:} header value; the supported HMI build string. */
    @Input
    public abstract Property<String> getSourceLabel();

    /** Fail when the written list differs from the one bundled in the build logic. */
    @Internal
    public abstract Property<Boolean> getCompareWithBundled();

    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    public CarJclSignaturesTask() {
        getCompareWithBundled().convention(false);
        getOutputs().upToDateWhen(t -> false);
    }

    @TaskAction
    public void generate() throws IOException {
        File out = getOutputFile().get().getAsFile();
        Files.createDirectories(out.toPath().getParent());
        try (Writer w = new OutputStreamWriter(Files.newOutputStream(out.toPath()), StandardCharsets.UTF_8)) {
            CarJclSignatures.write(getStockLsdJar().get().getAsFile(), getSourceLabel().get(), w);
        }
        if (getCompareWithBundled().get() && !Arrays.equals(Files.readAllBytes(out.toPath()), bundled())) {
            throw new GradleException("car JCL signatures differ from the bundled list: run ./gradlew carJclSignatures");
        }
    }

    private static byte[] bundled() throws IOException {
        try (InputStream in = CarJclTask.class.getResourceAsStream(CarJclTask.BUNDLED_LIST)) {
            if (in == null) {
                throw new GradleException("mibsdk build logic is missing its bundled car JCL list");
            }
            return in.readAllBytes();
        }
    }
}
