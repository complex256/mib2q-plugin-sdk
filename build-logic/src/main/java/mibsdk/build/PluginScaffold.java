package mibsdk.build;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.gradle.api.GradleException;

/**
 * Copies {@code templates/plugin} into a new plugin project, renaming the template's three placeholders:
 * {@code my-plugin} (the id, in the manifest, the settings file and the log line), {@code example.myplugin} (the Java
 * package, in the sources' paths and their {@code package} lines) and {@code MyPlugin} (the class, in its file name,
 * the test and the manifest's {@code main}).
 *
 * <p>The copy is staged next to the destination and moved into place only once it is complete, so a failure never
 * leaves a half-written project behind. The destination must not be a non-empty directory.
 */
final class PluginScaffold {

    static final String ID = "my-plugin";
    static final String PACKAGE = "example.myplugin";
    /** The same package as a source directory. */
    static final String PACKAGE_PATH = "example/myplugin";
    static final String CLASS = "MyPlugin";

    /** What a generated project ignores: its own build outputs, and nothing of its sources. */
    static final String GITIGNORE = "build/\n.gradle/\n.kotlin/\n";

    private PluginScaffold() {
    }

    /** The package a bare id gets: dashes are not allowed in a Java package name, so they simply go. */
    static String defaultPackage(String id) {
        return "plugin." + id.replace("-", "");
    }

    /** The class name for an id: each dash-separated word capitalised, so {@code my-plugin} gives {@code MyPlugin}. */
    static String className(String id) {
        StringBuilder name = new StringBuilder();
        for (String word : id.split("-")) {
            if (word.isEmpty()) {
                continue;
            }
            name.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return name.toString();
    }

    /**
     * Writes the project. {@code repoUri} is the {@code file:} URL of the SDK repository the generated build reads
     * its artifacts from; it goes into the new {@code gradle.properties}.
     */
    static void create(File template, File destination, String id, String javaPackage, String repoUri) {
        if (!template.isDirectory()) {
            throw new GradleException("mibsdk.plugin: newPlugin: the template directory " + template + " is missing");
        }
        if (destination.exists()) {
            String[] entries = destination.isDirectory() ? destination.list() : null;
            if (entries == null || entries.length > 0) {
                throw new GradleException("mibsdk.plugin: newPlugin: " + destination
                        + " already exists and is not empty; pick another -Pdir=<path> or remove it first");
            }
        }
        File parent = destination.getAbsoluteFile().getParentFile();
        Path staging = null;
        try {
            Files.createDirectories(parent.toPath());
            staging = Files.createTempDirectory(parent.toPath(), ".new-plugin-");
            copy(template.toPath(), staging, id, javaPackage, className(id), repoUri);
            Files.write(staging.resolve(".gitignore"), GITIGNORE.getBytes(StandardCharsets.UTF_8));
            if (destination.exists()) {
                // Empty, so this removes nothing but the directory itself.
                Files.delete(destination.toPath());
            }
            Files.move(staging, destination.toPath(), StandardCopyOption.ATOMIC_MOVE);
            staging = null;
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            try {
                Files.move(staging, destination.toPath());
                staging = null;
            } catch (IOException move) {
                throw new GradleException("mibsdk.plugin: newPlugin: could not write " + destination + ": "
                        + move.getMessage(), move);
            }
        } catch (IOException e) {
            throw new GradleException("mibsdk.plugin: newPlugin: could not write " + destination + ": "
                    + e.getMessage(), e);
        } finally {
            if (staging != null) {
                deleteRecursively(staging.toFile());
            }
        }
    }

    private static void copy(Path template, Path staging, String id, String javaPackage, String className,
            String repoUri) throws IOException {
        for (Path source : files(template)) {
            String relative = template.relativize(source).toString().replace(File.separatorChar, '/');
            String renamed = relative.replace(PACKAGE_PATH, javaPackage.replace('.', '/')).replace(CLASS, className);
            Path target = staging.resolve(renamed);
            Files.createDirectories(target.getParent());
            byte[] bytes = Files.readAllBytes(source);
            String text = decode(bytes);
            if (text == null) {
                Files.write(target, bytes);
            } else {
                String rewritten = text.replace(ID, id).replace(PACKAGE, javaPackage).replace(CLASS, className);
                if (renamed.equals("gradle.properties")) {
                    rewritten = withRepo(rewritten, repoUri);
                }
                Files.write(target, rewritten.getBytes(StandardCharsets.UTF_8));
            }
            if (relative.equals("gradlew")) {
                target.toFile().setExecutable(true);
            }
        }
    }

    /** The template's {@code gradle.properties} points nowhere; the new project points at this checkout. */
    private static String withRepo(String properties, String repoUri) {
        StringBuilder out = new StringBuilder();
        for (String line : properties.split("\n", -1)) {
            out.append(line.startsWith("mibsdk.repo=") ? "mibsdk.repo=" + repoUri : line).append('\n');
        }
        String text = out.toString();
        return text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
    }

    /** The file's text, or null when it is not UTF-8 (the Gradle wrapper jar and its friends). */
    private static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private static List<Path> files(Path root) throws IOException {
        List<Path> files = new ArrayList<Path>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile).forEach(files::add);
        }
        return files;
    }

    private static void deleteRecursively(File file) {
        if (!file.exists()) {
            return;
        }
        try (Stream<Path> walk = Files.walk(file.toPath())) {
            walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        } catch (IOException ignored) {
            // Best effort: the staging directory is invisible to the user and sits next to the project.
        }
    }
}
