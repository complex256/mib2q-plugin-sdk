package mibsdk.build;

import java.util.HashMap;
import java.util.Map;

/**
 * The permission each {@code mibsdk.api} call needs, keyed by owner and method name (any descriptor): the runtime
 * refuses the call unless {@code plugin.json} declares that permission and the owner grants it. The build checks
 * the declaration ({@link PermissionScanTask}); a plugin only sees the api interfaces, so a call arrives with the
 * interface as its owner.
 */
public final class ApiPermissions {
    private ApiPermissions() {}

    private static final Map<String, String> TABLE = new HashMap<String, String>();

    static {
        TABLE.put("mibsdk/api/Ui.carMenuRow", "ui.slot.carmenu");
        TABLE.put("mibsdk/api/Ui.statusItem", "ui.slot.statusbar");
        TABLE.put("mibsdk/api/Ui.toast", "ui.slot.statusbar");
        TABLE.put("mibsdk/api/PluginContext.net", "network");
        TABLE.put("mibsdk/api/PluginContext.vehicle", "vehicle.read");
        TABLE.put("mibsdk/api/Parking.overrideSoundDirections", "vehicle.parking.write");
        TABLE.put("mibsdk/api/PluginContext.hooks", "hooks.parking");
    }

    /** The permission id the call needs, or null if it needs none. {@code desc} is accepted for symmetry, unused. */
    public static String required(String owner, String name, String desc) {
        return TABLE.get(owner + "." + name);
    }

    /** {@code Ui.carMenuRow}: the call as the scan reports it. */
    static String label(String owner, String name) {
        return owner.substring(owner.lastIndexOf('/') + 1) + "." + name;
    }
}
