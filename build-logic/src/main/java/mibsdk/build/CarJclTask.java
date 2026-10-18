package mibsdk.build;

import java.io.File;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;

/**
 * Extracts the car's JCL ({@code java/**}, {@code javax/**}) from the stock {@code lsd.jar} into
 * {@code build/carjcl/}. Registered once on the root project and shared.
 *
 * <p>STUB: the extraction itself is implemented with LinkCheck; for now this only creates the output dir.
 */
@CacheableTask
public abstract class CarJclTask extends DefaultTask {

    /** Path of the stock lsd.jar (not an input file yet: the stub does not read it). */
    @Input
    public abstract Property<String> getStockLsdJar();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDir();

    @TaskAction
    public void extract() {
        File out = getOutputDir().get().getAsFile();
        if (!out.isDirectory() && !out.mkdirs()) {
            throw new org.gradle.api.GradleException("cannot create " + out);
        }
    }
}
