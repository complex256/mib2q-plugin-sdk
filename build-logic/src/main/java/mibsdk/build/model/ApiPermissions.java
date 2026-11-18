package mibsdk.build.model;

import java.util.HashMap;
import java.util.Map;
import mibsdk.build.tasks.PermissionScanTask;

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
        TABLE.put("mibsdk/api/ui/Ui.carMenuRow", "ui.slot.carmenu");
        TABLE.put("mibsdk/api/ui/Ui.statusItem", "ui.slot.statusbar");
        TABLE.put("mibsdk/api/ui/Ui.toast", "ui.slot.statusbar");
        TABLE.put("mibsdk/api/PluginContext.net", "network");
        TABLE.put("mibsdk/api/PluginContext.vehicle", "vehicle.read");
        TABLE.put("mibsdk/api/vehicle/Parking.overrideSoundDirections", "vehicle.parking.write");
        TABLE.put("mibsdk/api/PluginContext.hooks", "hooks.parking");
    }

    /** The permission id the call needs, or null if it needs none. {@code desc} is accepted for symmetry, unused. */
    public static String required(String owner, String name, String desc) {
        return TABLE.get(owner + "." + name);
    }

    /**
     * {@code Events.on(HookObserved.class, ...)} needs {@code hooks.parking} (hook observations travel on the event
     * bus), but the permission depends on the class argument, which the call's descriptor does not show: a method
     * that calls {@link #EVENTS_ON} and loads the {@link #isHookObserved class constant} needs it.
     */
    public static final String EVENTS_ON_OWNER = "mibsdk/api/Events";
    public static final String EVENTS_ON = "on";
    public static final String HOOK_OBSERVED_LABEL = "Events.on(HookObserved)";
    public static final String HOOK_OBSERVED_PERMISSION = "hooks.parking";

    /**
     * True for a constant that loads the class {@code mibsdk.api.hooks.HookObserved}: an {@code LDC} of its type, or the
     * class-name string that the {@code class$} helper of older javac / Retrotranslator output takes.
     */
    public static boolean isHookObserved(Object ldcConstant) {
        if (ldcConstant instanceof org.objectweb.asm.Type) {
            return "mibsdk/api/hooks/HookObserved".equals(((org.objectweb.asm.Type) ldcConstant).getInternalName());
        }
        return "mibsdk.api.hooks.HookObserved".equals(ldcConstant);
    }

    /** {@code Ui.carMenuRow}: the call as the scan reports it. */
    public static String label(String owner, String name) {
        return owner.substring(owner.lastIndexOf('/') + 1) + "." + name;
    }
}
