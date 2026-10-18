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
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/**
 * Extracts the car's JCL ({@code java/**}, {@code javax/**}) from the stock {@code lsd.jar} into
 * {@code build/carjcl/}. Registered once on the root project and shared.
 */
@CacheableTask
public abstract class CarJclTask extends DefaultTask {

    /** The stock lsd.jar (jxe2jar output). Never committed. */
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
