package mibsdk.runtime;

/** Runtime-wide constants. */
public final class RuntimeConstants {

    /**
     * Class-name prefixes a plugin class loader's parent may expose. Mirrors the build's
     * {@code Toolchain.ALLOWED_PARENT_PREFIXES}; the runtime cannot depend on build-logic.
     */
    public static final String[] PLUGIN_PARENT_PREFIXES = {
        "java.",
        "javax.",
        "mibsdk.api.",
        "mibsdk.compat.",
        "net.sf.retrotranslator.runtime."
    };

    private RuntimeConstants() {
    }
}
