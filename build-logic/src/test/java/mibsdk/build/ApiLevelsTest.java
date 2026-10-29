package mibsdk.build;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ApiLevelsTest {
    @Test
    void api2And3Members() {
        assertEquals(2, ApiLevels.since("mibsdk/api/Ui", "statusItem", "(Ljava/lang/String;)Lmibsdk/api/StatusItem;"));
        assertEquals(3, ApiLevels.since("mibsdk/api/Ui", "statusItem",
                "(Ljava/lang/String;Lmibsdk/api/Icon;)Lmibsdk/api/StatusItem;"));
        assertEquals(3, ApiLevels.since("mibsdk/api/Ui", "stockIcon", "(Ljava/lang/String;)Lmibsdk/api/Icon;"));
        assertEquals(2, ApiLevels.since("mibsdk/api/Net", "query", "(Ljava/lang/String;ILjava/lang/String;I)Ljava/lang/String;"));
        assertEquals(2, ApiLevels.since("mibsdk/api/PluginContext", "net", "()Lmibsdk/api/Net;"));
        assertEquals(1, ApiLevels.since("mibsdk/api/PluginContext", "log", "()Lmibsdk/api/Log;"), "api 1 stays unlisted");
        assertEquals(6, ApiLevels.since("mibsdk/api/StatusItem", "setColour", "(Lmibsdk/api/UiColor;)V"));
        assertEquals(6, ApiLevels.since("mibsdk/api/UiColor", "rgb", "(III)Lmibsdk/api/UiColor;"));
        assertEquals(6, ApiLevels.since("mibsdk/api/StatusItem", "visibleIn", "([I)V"));
        assertEquals(6, ApiLevels.since("mibsdk/api/StatusItem", "setIcon", "(Lmibsdk/api/Icon;)V"));
        assertEquals(7, ApiLevels.since("mibsdk/api/PluginContext", "wifi", "()Lmibsdk/api/Wifi;"));
        assertEquals(7, ApiLevels.since("mibsdk/api/Ui", "textInput",
                "(Ljava/lang/String;Ljava/lang/String;IZLmibsdk/api/Listener;)Lmibsdk/api/Subscription;"));
    }

    private static final Pattern TAG = Pattern.compile("^\\s*\\*\\s*@since api (\\d+)\\s*$");
    /** An inlined constant: a primitive or String field with an initialiser, in a class or an interface. */
    private static final Pattern CONSTANT = Pattern.compile(
            "^\\s*(?:public\\s+|static\\s+|final\\s+)*(?:int|long|boolean|String)\\s+([A-Z][A-Z0-9_]*)\\s*=.*");
    private static final Pattern METHOD = Pattern.compile("^\\s*[^=]*?([A-Za-z_][A-Za-z0-9_]*)\\s*\\(.*");
    private static final Pattern ENUM_CONSTANT = Pattern.compile("^\\s*([A-Z][A-Z0-9_]*)\\s*[,;]?\\s*$");
    private static final Pattern NESTED = Pattern.compile(
            "^    (?:public\\s+|static\\s+|final\\s+|abstract\\s+)*(?:class|enum|interface)\\s+(\\w+).*");

    @Test
    void everySinceTagHasAnEntry() throws IOException {
        Path root = Paths.get(System.getProperty("mibsdk.repoRoot"));
        List<Path> sources;
        try (Stream<Path> s = Files.list(root.resolve("api/src/main/java/mibsdk/api"))) {
            sources = s.filter(p -> p.toString().endsWith(".java")).sorted().collect(Collectors.toList());
        }
        List<String> missing = new ArrayList<>();
        int checked = 0;
        for (Path src : sources) {
            String top = src.getFileName().toString().replace(".java", "");
            List<String> lines = Files.readAllLines(src, StandardCharsets.UTF_8);
            String nested = null;
            for (int i = 0; i < lines.size(); i++) {
                Matcher n = NESTED.matcher(lines.get(i));
                if (n.matches()) {
                    nested = n.group(1);
                }
                Matcher tag = TAG.matcher(lines.get(i));
                if (!tag.matches() || Integer.parseInt(tag.group(1)) < 2) {
                    continue;
                }
                int api = Integer.parseInt(tag.group(1));
                int j = i + 1;
                while (j < lines.size() && (lines.get(j).trim().startsWith("*") || lines.get(j).trim().startsWith("/*")
                        || lines.get(j).trim().isEmpty())) {
                    j++;
                }
                String decl = lines.get(j);
                boolean member = decl.startsWith("        "); // a member of the nested type, else of the file's own
                String owner = "mibsdk/api/" + top + (member && nested != null ? "$" + nested : "");
                String where = src.getFileName() + ":" + (j + 1) + " " + decl.trim();
                if (CONSTANT.matcher(decl).matches()) {
                    continue; // inlined by the compiler: no reference to catch
                }
                String name;
                Matcher m = METHOD.matcher(decl);
                Matcher e = ENUM_CONSTANT.matcher(decl);
                if (m.matches()) {
                    name = m.group(1);
                    String cls = member && nested != null ? nested : top;
                    if (name.equals(cls)) {
                        name = "<init>";
                    }
                } else if (e.matches()) {
                    name = e.group(1);
                } else {
                    missing.add(where + " (not a method, constructor, enum constant or constant)");
                    continue;
                }
                checked++;
                String desc = descriptor(decl, owner, name);
                int actual = ApiLevels.since(owner, name, desc);
                if (actual != api) {
                    missing.add(where + " -> api " + api + " expected for " + owner + "." + name + desc
                            + " but found api " + actual);
                }
            }
        }
        assertEquals(List.of(), missing);
        assertEquals(72, checked, "the scan found the tagged members (update this number deliberately"
                + " when the API grows): " + checked);
    }

    /** The JVM descriptor a source declaration compiles to, for the api table's key. */
    private static String descriptor(String decl, String owner, String name) {
        String d = decl.trim();
        if (ENUM_CONSTANT.matcher(d).matches()) {
            return "L" + owner + ";";
        }
        int paren = d.indexOf('(');
        String pre = d.substring(0, paren).trim();
        String params = d.substring(paren + 1);
        int close = params.lastIndexOf(')');
        params = params.substring(0, close).trim();
        String ret;
        if (name.equals("<init>")) {
            ret = "V";
        } else {
            String[] toks = pre.split("\\s+");
            int idx = toks.length - 1;
            for (int i = toks.length - 1; i >= 0; i--) {
                if (toks[i].equals(name)) {
                    idx = i;
                    break;
                }
            }
            ret = jvmType(toks[idx - 1]);
        }
        StringBuilder sb = new StringBuilder("(");
        if (!params.isEmpty()) {
            for (String p : params.split(",")) {
                p = p.trim();
                String[] toks = p.split("\\s+");
                String type = toks.length > 1 ? toks[toks.length - 2] : toks[0];
                sb.append(jvmType(type));
            }
        }
        return sb.append(')').append(ret).toString();
    }

    /** The descriptor of a Java type as the api table spells it; generics erase to their raw type. */
    private static String jvmType(String javaType) {
        String t = javaType.replaceAll("<.*>", "");
        switch (t) {
            case "String": return "Ljava/lang/String;";
            case "int": return "I";
            case "int...": return "[I";
            case "boolean": return "Z";
            case "void": return "V";
            case "Page": return "Lmibsdk/api/Page;";
            case "PageSpec": return "Lmibsdk/api/PageSpec;";
            case "StatusItem": return "Lmibsdk/api/StatusItem;";
            case "Subscription": return "Lmibsdk/api/Subscription;";
            case "Icon": return "Lmibsdk/api/Icon;";
            case "Ui": return "Lmibsdk/api/Ui;";
            case "Net": return "Lmibsdk/api/Net;";
            case "Wifi": return "Lmibsdk/api/Wifi;";
            case "Boolean": return "Ljava/lang/Boolean;";
            case "Integer": return "Ljava/lang/Integer;";
            case "List": return "Ljava/util/List;";
            case "Listener": return "Lmibsdk/api/Listener;";
            case "UiAction": return "Lmibsdk/api/UiAction;";
            case "UiColor": return "Lmibsdk/api/UiColor;";
            case "Kind": return "Lmibsdk/api/PageSpec$Kind;";
            case "Entry": return "Lmibsdk/api/PageSpec$Entry;";
            case "String[]": return "[Ljava/lang/String;";
            default: throw new IllegalArgumentException("unmapped type: " + javaType);
        }
    }
}
