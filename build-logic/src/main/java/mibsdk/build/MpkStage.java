package mibsdk.build;

import java.util.List;

/** The {@code manifest.toml} texts of the two kinds of mibpkg package the build produces. */
public final class MpkStage {
    private MpkStage() {}

    /** Package-level constants (they moved here from gen_bundles.py and mibsdk-dev). */
    static final String UNIT = "MHI2Q";
    static final String HMI = "HMI_AU_MIB2_HQC_NAR_G22_C10_180803";
    static final List<String> SDK_JARS = List.of(
            "mibsdk-runtime.jar", "mibsdk-adapter-hqc.jar", "mibsdk-rt-retrotranslator.jar");
    static final String UNIT_STAMP = "mibsdk-unit.stamp";
    static final String PERMISSIONS = "permissions.json";

    static String pluginManifest(PluginJson p) {
        return "[package]\n"
                + "name = " + str(p.id) + "\n"
                + "version = " + str(p.version) + "\n"
                + "description = " + str(p.name + " (mibsdk plugin)") + "\n"
                + "\n"
                + "[compat]\n"
                + "units = [" + str(UNIT) + "]\n"
                + "\n"
                + "[plugin]\n"
                + "jar = " + str(p.id + ".jar") + "\n"
                + "api = " + p.api + "\n"
                + "permissions = " + array(p.permissions) + "\n";
    }

    public static String sdkManifest(String version) {
        return "[package]\n"
                + "name = \"mibsdk\"\n"
                + "version = " + str(version) + "\n"
                + "description = \"mibsdk runtime for the MIB2 head unit\"\n"
                + "\n"
                + "[compat]\n"
                + "units = [" + str(UNIT) + "]\n"
                + "\n"
                + "[sdk]\n"
                + "api = " + PluginJson.MAX_API + "\n"
                + "min_api = " + PluginJson.MIN_API + "\n"
                + "hmi = [" + str(HMI) + "]\n"
                + "jars = " + array(SDK_JARS) + "\n"
                + "stamp = " + str(UNIT_STAMP) + "\n"
                + "configurator = " + str("mibsdk.adapter.hqc.ChainingConfigurator") + "\n"
                + "bundle = " + str("MibSdk=mibsdk.adapter.hqc.SdkBundleActivator") + "\n"
                + "tracer_configurator = " + str("de.audi.tghu.dsitrace.TraceConfigurator") + "\n"
                + "hmicar_stock = " + str("de.audi.tghu.car.hmi.Activator") + "\n"
                + "hmicar = " + str("mibsdk.adapter.hqc.ui.SdkCarActivator") + "\n"
                + "owned = " + array(List.of("de/audi/tghu/car/hmi/", "de/audi/atip/model/CarModelBank")) + "\n"
                + "properties = " + array(List.of(
                        "hook.services=org.dsi.ifc.carparkingsystem.DSICarParkingSystem", "ui.enabled=true")) + "\n"
                + "permissions = " + str(PERMISSIONS) + "\n";
    }

    private static String array(List<String> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            sb.append(i == 0 ? "" : ", ").append(str(items.get(i)));
        }
        return sb.append(']').toString();
    }

    /** A TOML basic string. */
    static String str(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20 || c == 0x7f) {
                        sb.append(String.format("\\u%04X", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append('"').toString();
    }
}
