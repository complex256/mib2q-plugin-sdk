package mibsdk.build;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.RegularFile;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.jvm.toolchain.JavaToolchainService;

/** Pinned tool versions, pipeline constants and machine-local path resolution (no defaults) for the SDK build. */
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

    /**
     * JDK 8 home: {@code -Pmibsdk.jdk8Home}, else the installation Gradle's toolchain detection finds for
     * Java 8 (provisioned through the foojay resolver when none is installed). Resolved when read.
     */
    public static Provider<String> jdk8Home(Project project) {
        return project.provider(() -> {
            Object configured = project.findProperty("mibsdk.jdk8Home");
            if (configured != null && !configured.toString().isEmpty()) {
                return configured.toString();
            }
            JavaToolchainService toolchains = project.getExtensions().getByType(JavaToolchainService.class);
            return toolchains.launcherFor(spec -> spec.getLanguageVersion().set(JavaLanguageVersion.of(8)))
                    .get().getMetadata().getInstallationPath().getAsFile().getPath();
        });
    }

    private static final String STOCK_LSD_JAR = "mibsdk.stockLsdJar";
    private static final String STOCK_DSI_TRACER_JAR = "mibsdk.stockDsiTracerJar";

    /** Whether {@code -Pmibsdk.stockLsdJar} is given (the one test {@code carJcl} and {@link #stockLsdJar} share). */
    public static boolean stockLsdJarConfigured(Project project) {
        return configured(project, STOCK_LSD_JAR) != null;
    }

    /**
     * jxe2jar's {@code lsd.jar}, or null when {@code mibsdk.stockLsdJar} is unset: plugin builds then check
     * against the bundled car JCL list. Set but missing is an error. Never committed.
     */
    public static File stockLsdJar(Project project) {
        return stockLsdJarConfigured(project) ? requiredStockLsdJar(project) : null;
    }

    /** {@code lsd.jar} for the modules that compile against its classes; unset is an error. */
    public static File requiredStockLsdJar(Project project) {
        return required(project, STOCK_LSD_JAR,
                "lsd.jar is jxe2jar's conversion of the HMI's lsd.jxe; it holds the LSD, OSGi and DSI classes.");
    }

    /** Stock {@code DSITracer.jar}; unset is an error. Never committed. */
    public static File stockDsiTracerJar(Project project) {
        return required(project, STOCK_DSI_TRACER_JAR,
                "DSITracer.jar is the HMI's stock DSITracer jar (de.audi.tghu.dsitrace).");
    }

    /**
     * Both stock jars, for the modules that compile and link against them ({@code adapter-hqc}, {@code dist},
     * the UI spike); nothing else touches them. Reading the collection never fails, so an unset property does
     * not break the rest of the build. Anything that uses it depends on this project's {@code verifyStockJars}
     * task, which fails with a message naming the property. Call once per project.
     */
    public static FileCollection stockJars(Project project) {
        TaskProvider<Task> verify = project.getTasks().register("verifyStockJars", t -> {
            t.setGroup("verification");
            t.setDescription("Fails with the property name when a stock jar this module needs is unset or missing.");
            t.doLast(task -> requireStockJars(project));
        });
        ConfigurableFileCollection jars = project.files(project.provider(() -> {
            List<File> configured = new ArrayList<File>();
            for (String property : new String[] {STOCK_LSD_JAR, STOCK_DSI_TRACER_JAR}) {
                String value = configured(project, property);
                if (value != null) {
                    configured.add(new File(value));
                }
            }
            return configured;
        }));
        jars.builtBy(verify);
        return jars;
    }

    /** Checks both stock jars, the DSITracer first. @throws GradleException naming the property that is wrong. */
    public static void requireStockJars(Project project) {
        stockDsiTracerJar(project);
        requiredStockLsdJar(project);
    }

    /**
     * The file a required machine-local property points at.
     *
     * @param what one sentence saying what the file is, shown in the error
     * @throws GradleException naming the property when it is unset or points at a missing file
     */
    public static File required(Project project, String property, String what) {
        String value = configured(project, property);
        if (value == null) {
            throw new GradleException("mibsdk: " + property + " is not set" + hint(property, what));
        }
        File file = new File(value);
        if (!file.isFile()) {
            throw new GradleException("mibsdk: " + property + " points at " + value + ", which does not exist"
                    + hint(property, what));
        }
        return file;
    }

    private static String hint(String property, String what) {
        return "\n  " + what + "\n  Set it with -P" + property
                + "=<path> or in ~/.gradle/gradle.properties; see README.md#toolchain";
    }

    private static String configured(Project project, String property) {
        Object value = project.findProperty(property);
        return value == null || value.toString().isEmpty() ? null : value.toString();
    }

    /** Host {@code mibpkg}: {@code -Pmibsdk.mibpkg=<path>}, else {@code mibpkg} on {@code PATH}. */
    public static String mibpkg(Project project) {
        Object configured = project.findProperty("mibsdk.mibpkg");
        return configured == null || configured.toString().isEmpty() ? "mibpkg" : configured.toString();
    }

    /** The host mibpkg as a provider: absent when it cannot be found (the task then fails every run). */
    public static Provider<RegularFile> mibpkgBinary(Project project) {
        return project.getLayout().file(project.provider(() -> resolveExecutable(mibpkg(project))));
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
}
