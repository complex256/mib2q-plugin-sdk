package mibsdk.build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/**
 * Host-test copy of stock classes: the entries of the stock jars under {@link #getPackages()} (never
 * {@code java/**} or {@code javax/**}), each repaired by {@link HostLoadable} so a desktop JVM loads them.
 * Raw jxe2jar classes fail on the host with {@code ClassFormatError}. Test classpath only; never shipped.
 * Earlier jars win on duplicate entries. Classes ASM cannot parse are left out (logged).
 */
@CacheableTask
public abstract class HostStockJarTask extends DefaultTask {

    @InputFiles
    @PathSensitive(PathSensitivity.NONE)
    public abstract ConfigurableFileCollection getStockJars();

    /** Package prefixes to copy, slash form with a trailing slash, e.g. {@code org/dsi/ifc/base/}. */
    @Input
    public abstract ListProperty<String> getPackages();

    @OutputFile
    public abstract RegularFileProperty getOutputJar();

    @TaskAction
    public void build() throws IOException {
        List<String> packages = getPackages().get();
        File out = getOutputJar().get().getAsFile();
        out.getParentFile().mkdirs();
        Set<String> written = new HashSet<>();
        int skipped = 0;
        try (JarOutputStream jar = new JarOutputStream(new FileOutputStream(out))) {
            for (File stock : getStockJars().getFiles()) {
                if (!stock.isFile()) {
                    throw new GradleException("stock jar not found: " + stock);
                }
                try (ZipFile zip = new ZipFile(stock)) {
                    for (Enumeration<? extends ZipEntry> en = zip.entries(); en.hasMoreElements();) {
                        ZipEntry e = en.nextElement();
                        String name = e.getName();
                        if (e.isDirectory() || !name.endsWith(".class") || !selected(name, packages)
                                || !written.add(name)) {
                            continue;
                        }
                        byte[] repaired;
                        try (InputStream in = zip.getInputStream(e)) {
                            repaired = HostLoadable.repair(in.readAllBytes());
                        } catch (RuntimeException ex) {
                            getLogger().info("host stock jar: cannot repair {} ({}), left out", name, ex);
                            skipped++;
                            continue;
                        }
                        jar.putNextEntry(new JarEntry(name));
                        jar.write(repaired);
                        jar.closeEntry();
                    }
                }
            }
        }
        if (written.isEmpty()) {
            throw new GradleException("no classes under " + packages + " in " + getStockJars().getFiles());
        }
        getLogger().info("host stock jar: {} classes ({} left out) -> {}", written.size() - skipped, skipped, out);
    }

    private static boolean selected(String name, List<String> packages) {
        if (name.startsWith("java/") || name.startsWith("javax/")) {
            return false;
        }
        for (String p : packages) {
            if (name.startsWith(p)) {
                return true;
            }
        }
        return false;
    }
}
