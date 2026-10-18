package mibsdk.build;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileTree;
import org.gradle.api.tasks.IgnoreEmptyDirectories;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/** Fails listing every class whose major version is not {@link Toolchain#CAR_CLASS_VERSION}. */
public abstract class ClassVersionCheckTask extends DefaultTask {

    @InputFiles
    @IgnoreEmptyDirectories
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getClasses();

    @TaskAction
    public void check() throws IOException {
        List<String> bad = new ArrayList<String>();
        FileTree tree = getClasses().getAsFileTree().matching(p -> p.include("**/*.class"));
        for (File f : tree.getFiles()) {
            int major = majorVersion(f);
            if (major != Toolchain.CAR_CLASS_VERSION) {
                bad.add(f.getName() + " (" + describe(major) + ") " + f);
            }
        }
        Collections.sort(bad);
        if (!bad.isEmpty()) {
            throw new GradleException("classes not at class-file version " + Toolchain.CAR_CLASS_VERSION
                    + " (the car's J9 rejects anything else):\n  " + String.join("\n  ", bad));
        }
        getLogger().info("{} classes checked, all version {}", tree.getFiles().size(), Toolchain.CAR_CLASS_VERSION);
    }

    private static String describe(int major) {
        return major < 0 ? "not a class file" : "major " + major;
    }

    /** Major version, or -1 if the file lacks the 0xCAFEBABE magic. */
    static int majorVersion(File f) throws IOException {
        try (DataInputStream in = new DataInputStream(new FileInputStream(f))) {
            if (in.readInt() != 0xCAFEBABE) {
                return -1;
            }
            in.readUnsignedShort(); // minor
            return in.readUnsignedShort();
        } catch (java.io.EOFException e) {
            return -1;
        }
    }
}
