package mibsdk.build;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/** Writes a {@link CheckStamp} for a set of jars whose checks have already run (the task's dependencies). */
public abstract class CheckStampTask extends DefaultTask {
    @InputFiles
    @PathSensitive(PathSensitivity.NAME_ONLY)
    public abstract ConfigurableFileCollection getJars();

    @Input
    public abstract Property<String> getKind();

    @Input
    public abstract ListProperty<String> getChecks();

    @OutputFile
    public abstract RegularFileProperty getStamp();

    @TaskAction
    public void writeStamp() throws IOException {
        CheckStamp.write(getStamp().get().getAsFile(), getKind().get(), getChecks().get(),
                Collections.emptyMap(), new ArrayList<>(getJars().getFiles()));
    }
}
