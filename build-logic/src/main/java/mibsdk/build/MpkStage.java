package mibsdk.build;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.gradle.api.GradleException;

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
                + "permissions = " + array(p.permissions) + "\n"
                + (p.icons.isEmpty() ? "" : "icons = " + array(p.icons) + "\n");
    }

    /**
     * A plugin package's {@code requires.toml}. It is a sidecar, not a manifest field, so older
     * mibpkg releases (which reject unknown manifest fields) still install the package.
     */
    static String pluginRequires() {
        return "[mibsdk]\nmin = " + str(SdkCoordinates.version()) + "\n";
    }

    /**
     * The SDK package's manifest. {@code icons} names the built-in set, staged as {@code icons/<name>.png}
     * ({@link #stageIcons}); mibpkg 0.9.0 needs {@code hmisystem_stock} and {@code hmisystem} together.
     */
    public static String sdkManifest(String version, List<String> icons) {
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
                + "hmisystem_stock = " + str("de.audi.tghu.system.hmi.Activator") + "\n"
                + "hmisystem = " + str("mibsdk.adapter.hqc.ui.SdkSystemActivator") + "\n"
                + "owned = " + array(List.of("de/audi/tghu/car/hmi/", "de/audi/atip/model/CarModelBank",
                        "de/audi/tghu/system/hmi/")) + "\n"
                + "properties = " + array(List.of(
                        "hook.services=org.dsi.ifc.carparkingsystem.DSICarParkingSystem", "ui.enabled=true")) + "\n"
                + "permissions = " + str(PERMISSIONS) + "\n"
                + "icons = " + array(icons) + "\n";
    }

    /** The names of the {@code <name>.png} files in {@code dir}, sorted (none if it does not exist). */
    public static List<String> iconNames(File dir) {
        List<String> names = new ArrayList<String>();
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile() && f.getName().endsWith(".png")) {
                    names.add(f.getName().substring(0, f.getName().length() - ".png".length()));
                }
            }
        }
        Collections.sort(names);
        return names;
    }

    /**
     * Copies {@code <from>/<name>.png} to {@code <stage>/icons/<name>.png} for each name, after every one has passed
     * {@link PngCheck}: a missing or refused file fails the build and stages none of them. The bytes written are the
     * bytes checked. With {@code reflect} (plugin packages; the SDK's set has its own {@code -72r}), every icon taller
     * than 20 px also gets its {@link Reflection}, {@code icons/<name>.r.png}, checked by the same rules. The
     * {@code .r} files are never declared: the manifest's {@code icons} stays the declared names.
     */
    public static void stageIcons(List<String> names, File from, File stage, boolean reflect) {
        List<String> files = new ArrayList<String>();
        List<byte[]> checked = new ArrayList<byte[]>();
        for (String name : names) {
            File png = new File(from, name + ".png");
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(png.toPath());
            } catch (IOException e) {
                throw new GradleException("mibpkg stage: cannot read icon " + png + ": " + e, e);
            }
            PngCheck.Info info;
            try {
                info = PngCheck.check(bytes, PngCheck.MAX_PX);
            } catch (IllegalArgumentException e) {
                throw new GradleException("mibpkg stage: " + png + ": " + e.getMessage(), e);
            }
            files.add(name + ".png");
            checked.add(bytes);
            if (reflect && info.height > 20) {
                byte[] r = Reflection.encode(Reflection.of(PngCheck.rgba(bytes)));
                try {
                    PngCheck.check(r, PngCheck.MAX_PX);
                } catch (IllegalArgumentException e) {
                    throw new GradleException("mibpkg stage: icon " + name + ".r: " + e.getMessage(), e);
                }
                files.add(name + ".r.png");
                checked.add(r);
            }
        }
        if (names.isEmpty()) {
            return;
        }
        File dir = new File(stage, "icons");
        try {
            Files.createDirectories(dir.toPath());
            for (int i = 0; i < files.size(); i++) {
                Files.write(new File(dir, files.get(i)).toPath(), checked.get(i));
            }
        } catch (IOException e) {
            throw new GradleException("mibpkg stage: cannot write " + dir + ": " + e, e);
        }
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
