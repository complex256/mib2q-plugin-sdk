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

    /** ProGuard (run as {@code proguard.ProGuard} under JDK 8) and its runtime, pinned and non-transitive. */
    public static final List<String> PROGUARD = Collections.unmodifiableList(Arrays.asList(
            "com.guardsquare:proguard-base:7.6.0",
            "com.guardsquare:proguard-core:9.1.6",
            "org.apache.logging.log4j:log4j-api:2.23.1",
            "org.apache.logging.log4j:log4j-core:2.23.1",
            "com.google.code.gson:gson:2.11.0",
            "org.jetbrains.kotlin:kotlin-stdlib:2.0.21"));

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

    /**
     * ProGuard rules for Kotlin plugins, after the {@code -keep class <main> { *; }} rule. The keepnames
     * pass exists only so LocalVariableTable is dropped: Kotlin's {@code $i$a$-...} names are illegal in v48.
     */
    public static final List<String> KOTLIN_PROGUARD_RULES = Collections.unmodifiableList(Arrays.asList(
            "-keepnames class ** { *; }",
            "-keepattributes !LocalVariableTable,!LocalVariableTypeTable,*",
            "-dontoptimize",
            "-dontwarn **",
            "-ignorewarnings"));

    /**
     * Added to the Kotlin ProGuard rules. ProGuard's obfuscation pass (needed to drop LocalVariableTable)
     * rewrites stdlib calls {@code Intrinsics.checkNotNull(Object, String)} to {@code checkNotNull(Object)}
     * after shrinking, so that overload must survive the shrink even when nothing called it before.
     */
    public static final String KOTLIN_PROGUARD_INTRINSICS_RULE =
            "-keepclassmembers class kotlin.jvm.internal.Intrinsics { public static void checkNotNull(java.lang.Object); }";

    /** The manifest permission that lifts the build's (and the runtime's) escape-route ban. */
    public static final String PLATFORM_UNRESTRICTED = "platform.unrestricted";

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

    /** Host {@code mibpkg}: {@code -Pmibsdk.mibpkg=<path>}, else {@code mibpkg} on {@code PATH}. */
    public static String mibpkg(Project project) {
        Object configured = project.findProperty("mibsdk.mibpkg");
        return configured == null || configured.toString().isEmpty() ? "mibpkg" : configured.toString();
    }

    /** An executable file for a path, or a bare command name found on {@code PATH}; null when there is none. */
    public static File resolveExecutable(String command) {
        if (command.indexOf(File.separatorChar) >= 0) {
            File f = new File(command);
            return f.isFile() && f.canExecute() ? f : null;
        }
        String path = System.getenv("PATH");
        for (String dir : (path == null ? "" : path).split(File.pathSeparator)) {
            File f = new File(dir, command);
            if (!dir.isEmpty() && f.isFile() && f.canExecute()) {
                return f;
            }
        }
        return null;
    }

    private static File path(Project project, String property, String homeRelativeDefault) {
        Object configured = project.findProperty(property);
        if (configured != null) {
            return new File(configured.toString());
        }
        return new File(new File(System.getProperty("user.home")), homeRelativeDefault);
    }
}
