package mibsdk.adapter.hqc.ui;

import java.io.File;
import mibsdk.platform.IconIndex;
import mibsdk.platform.UiIcon;

/**
 * The SDK's built-in icon set as mibpkg installed it ({@code icons-sdk/index.properties}, names
 * {@code <name>-72}, {@code <name>-72r} (the reflection) and {@code <name>-20}), owned by {@code mibsdk}. Read once,
 * on an SDK thread ({@code HqcPlatform.start}), into {@code SdkGlobals.builtinIcons}; immutable after.
 */
public final class BuiltinIcons {

    /** The owner of the built-in set: the plugin id reserved for the SDK. */
    static final String OWNER = "mibsdk";

    /** No built-in icons (none installed, or not read yet). */
    public static final BuiltinIcons NONE = new BuiltinIcons(IconIndex.read(OWNER, null));

    private final IconIndex index;

    private BuiltinIcons(IconIndex index) {
        this.index = index;
    }

    /** Reads {@code <sdkIconDir>/index.properties}; never throws (a missing or bad index is an empty set). */
    public static BuiltinIcons read(File sdkIconDir) {
        return new BuiltinIcons(IconIndex.read(OWNER, sdkIconDir));
    }

    /** The installed file of icon {@code name} at {@code size} px ({@code reflex}: its reflection); null if none. */
    public UiIcon get(String name, int size, boolean reflex) {
        if (name == null) {
            return null;
        }
        return index.get(name + "-" + size + (reflex ? "r" : ""));
    }
}
