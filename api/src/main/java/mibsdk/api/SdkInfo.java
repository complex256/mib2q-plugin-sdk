package mibsdk.api;

/**
 * The plugin API range this SDK implements.
 *
 * <p>Declare the newest level your code uses in {@code plugin.json} {@code "api"}. The build refuses an
 * older one; mibpkg refuses a plugin newer than the SDK on the unit.
 *
 * @since api 1
 */
public final class SdkInfo {

    /** The newest plugin api this SDK implements. */
    public static final int API_VERSION = 7;

    /** The oldest plugin api this SDK still loads. */
    public static final int MIN_API_VERSION = 1;

    private SdkInfo() {
    }
}
