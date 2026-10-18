package mibsdk.build;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.gradle.api.Project;

/** Pinned tool versions, pipeline constants and machine-local path resolution for the SDK build. */
public final class Toolchain {
    private Toolchain() {}

    public static final String RETROLAMBDA = "net.orfjackal.retrolambda:retrolambda:2.5.7";
    public static final String RETROTRANSLATOR_TRANSFORMER = "net.sf.retrotranslator:retrotranslator-transformer:1.2.9";
    public static final String RETROTRANSLATOR_RUNTIME = "net.sf.retrotranslator:retrotranslator-runtime:1.2.9";
    public static final String JABEL = "com.github.bsideup.jabel:jabel-javac-plugin:1.0.0";
    public static final String BYTE_BUDDY = "net.bytebuddy:byte-buddy:1.12.19";
    public static final String BYTE_BUDDY_AGENT = "net.bytebuddy:byte-buddy-agent:1.12.19";
    public static final String JUNIT = "org.junit.jupiter:junit-jupiter:5.10.2";
    public static final String JUNIT_LAUNCHER = "org.junit.platform:junit-platform-launcher:1.10.2";

    /** The only class-file major version J9 2.3 loads (Java 1.4). */
    public static final int CAR_CLASS_VERSION = 48;

    /** Retrotranslator {@code -backport} mapping of missing JDK packages onto the SDK compat shims. */
    public static final String BACKPORT =
            "java.util.function:mibsdk.compat.java.util.function;"
                    + "java.util:mibsdk.compat.java.util;"
                    + "java.lang:mibsdk.compat.java.lang";

    public static final String KOTLIN_JVM_TARGET = "1.8";

    public static final List<String> KOTLIN_FLAGS = Collections.unmodifiableList(Arrays.asList(
            "-Xlambdas=class",
            "-Xsam-conversions=class",
            "-Xstring-concat=inline",
            "-Xno-param-assertions",
            "-Xno-call-assertions",
            "-Xno-receiver-assertions"));

    /** Class-loader parent of a plugin may only expose these package prefixes. */
    public static final List<String> ALLOWED_PARENT_PREFIXES = Collections.unmodifiableList(Arrays.asList(
            "java.",
            "javax.",
            "mibsdk.api.",
            "mibsdk.compat.",
            "net.sf.retrotranslator.runtime."));

    /** JDK 8 home: {@code -Pmibsdk.jdk8Home}, else the Corretto 8 install under the user's home. */
    public static File jdk8Home(Project project) {
        return path(project, "mibsdk.jdk8Home",
                "Library/Java/JavaVirtualMachines/corretto-1.8.0_372/Contents/Home");
    }

    /** jxe2jar's {@code lsd.jar}; source of the car JCL. Never committed. */
    public static File stockLsdJar(Project project) {
        return path(project, "mibsdk.stockLsdJar", "projects/audi-mib2/patching/lsd.jar");
    }

    /** Stock {@code DSITracer.jar}. Never committed. */
    public static File stockDsiTracerJar(Project project) {
        return path(project, "mibsdk.stockDsiTracerJar",
                "projects/audi-mib2/firmware/mmxfs/app/eso/hmi/lsd/DSITracer.jar");
    }

    private static File path(Project project, String property, String homeRelativeDefault) {
        Object configured = project.findProperty(property);
        if (configured != null) {
            return new File(configured.toString());
        }
        return new File(new File(System.getProperty("user.home")), homeRelativeDefault);
    }
}
