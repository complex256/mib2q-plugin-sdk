package mibsdk.runtime;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import mibsdk.api.MibPlugin;

/**
 * Builds fixture plugin jars in-test: compiles one {@code fx.Main extends MibPlugin} from source strings
 * against the api classes and packs it with a {@code plugin.json} at the jar root.
 */
final class PluginJars {

    private final File workDir;
    private int counter;

    PluginJars(File workDir) {
        this.workDir = workDir;
    }

    /**
     * Writes {@code pluginsDir/<id>.jar}. {@code onEnableBody} sees {@code ctx} (also stored in field
     * {@code ctx}); {@code members} adds fields/methods to the class. onDisable logs {@code "disabled"}.
     */
    File write(File pluginsDir, String id, String version, String[] dependencies, String[] permissions,
            String onEnableBody, String members) throws IOException {
        String source = "package fx;\n"
                + "import mibsdk.api.*;\n"
                + "public class Main extends MibPlugin {\n"
                + "  PluginContext ctx;\n"
                + (members == null ? "" : members) + "\n"
                + "  protected void onEnable(final PluginContext ctx) throws Exception {\n"
                + "    this.ctx = ctx;\n"
                + onEnableBody + "\n"
                + "  }\n"
                + "  protected void onDisable() throws Exception { ctx.log().info(\"disabled\"); }\n"
                + "}\n";
        String json = "{\"id\":\"" + id + "\",\"version\":\"" + version + "\",\"api\":1,\"main\":\"fx.Main\""
                + ",\"permissions\":" + array(permissions) + ",\"dependencies\":" + array(dependencies) + "}";
        return writeRaw(new File(pluginsDir, id + ".jar"), source, json);
    }

    File writeRaw(File jar, String mainSource, String pluginJson) throws IOException {
        File dir = new File(workDir, "build-" + (++counter));
        File src = new File(dir, "src/fx/Main.java");
        File out = new File(dir, "classes");
        src.getParentFile().mkdirs();
        out.mkdirs();
        Files.write(src.toPath(), mainSource.getBytes(StandardCharsets.UTF_8));
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        String apiClasses;
        try {
            apiClasses = new File(MibPlugin.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getPath();
        } catch (Exception e) {
            throw new IOException(e);
        }
        int rc = javac.run(null, null, null, "-nowarn", "-cp", apiClasses, "-d", out.getPath(), src.getPath());
        if (rc != 0) {
            throw new IOException("fixture compile failed for " + jar.getName());
        }
        jar.getParentFile().mkdirs();
        JarOutputStream jos = new JarOutputStream(new FileOutputStream(jar));
        try {
            jos.putNextEntry(new JarEntry("plugin.json"));
            Writer w = new OutputStreamWriter(jos, StandardCharsets.UTF_8);
            w.write(pluginJson);
            w.flush();
            jos.closeEntry();
            List<File> classes = new ArrayList<File>();
            collect(out, classes);
            for (File c : classes) {
                String name = out.toPath().relativize(c.toPath()).toString().replace(File.separatorChar, '/');
                jos.putNextEntry(new JarEntry(name));
                jos.write(Files.readAllBytes(c.toPath()));
                jos.closeEntry();
            }
        } finally {
            jos.close();
        }
        return jar;
    }

    private static void collect(File dir, List<File> out) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                collect(f, out);
            } else if (f.getName().endsWith(".class")) {
                out.add(f);
            }
        }
    }

    private static String array(String[] values) {
        StringBuilder sb = new StringBuilder("[");
        if (values != null) {
            for (int i = 0; i < values.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append('"').append(values[i]).append('"');
            }
        }
        return sb.append(']').toString();
    }
}
