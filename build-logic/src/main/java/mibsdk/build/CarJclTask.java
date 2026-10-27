package mibsdk.build;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/**
 * Provides the car's JCL in {@code build/carjcl/} for {@code linkCheck}. With a stock {@code lsd.jar} it
 * extracts {@code java/**} and {@code javax/**}; without one it writes the bundled signature list
 * ({@code carjcl.sigs}). Registered once on the root project and shared.
 */
@CacheableTask
public abstract class CarJclTask extends DefaultTask {

    /** Classpath location of the committed signature list for the supported HMI build. */
    static final String BUNDLED_LIST = "/mibsdk/build/carjcl" + CarJclSignatures.SUFFIX;

    static final String BUNDLED_LIST_NAME = "carjcl" + CarJclSignatures.SUFFIX;

    /** The stock lsd.jar (jxe2jar output), never committed. Absent: the bundled signature list is used. */
    @Optional
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getStockLsdJar();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDir();

    @TaskAction
    public void extract() throws IOException {
        Path out = getOutputDir().get().getAsFile().toPath().toAbsolutePath().normalize();
        deleteRecursively(out);
        Files.createDirectories(out);
        if (!getStockLsdJar().isPresent()) {
            writeBundledList(out);
            return;
        }
        int count = 0;
        File jar = getStockLsdJar().get().getAsFile();
        try (ZipFile zip = new ZipFile(jar)) {
            for (Enumeration<? extends ZipEntry> en = zip.entries(); en.hasMoreElements();) {
                ZipEntry e = en.nextElement();
                String name = e.getName();
                if (e.isDirectory() || !(name.startsWith("java/") || name.startsWith("javax/"))) {
                    continue;
                }
                Path target = out.resolve(name).normalize();
                if (!target.startsWith(out)) {
                    throw new GradleException("unsafe entry in " + jar + ": " + name);
                }
                Files.createDirectories(target.getParent());
                try (InputStream in = zip.getInputStream(e)) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
                count++;
            }
        }
        if (count == 0) {
            throw new GradleException("no java/** or javax/** entries in " + jar + ": not a stock lsd.jar?");
        }
        getLogger().info("car JCL: {} entries extracted from {}", count, jar);
    }

    private void writeBundledList(Path out) throws IOException {
        try (InputStream in = CarJclTask.class.getResourceAsStream(BUNDLED_LIST)) {
            if (in == null) {
                throw new GradleException("mibsdk build logic is missing its bundled car JCL list " + BUNDLED_LIST);
            }
            Files.copy(in, out.resolve(BUNDLED_LIST_NAME), StandardCopyOption.REPLACE_EXISTING);
        }
        getLogger().info("car JCL: bundled signature list");
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (java.util.stream.Stream<Path> s = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) s.sorted(java.util.Comparator.reverseOrder())::iterator) {
                Files.delete(p);
            }
        }
    }
}
