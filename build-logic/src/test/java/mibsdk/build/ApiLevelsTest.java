package mibsdk.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
                if (!ApiLevels.listed(owner, name, api)) {
                    missing.add(where + " -> no api " + api + " entry for " + owner + "." + name);
                }
            }
        }
        assertEquals(List.of(), missing);
        assertTrue(checked > 40, "the scan found the tagged members, not an empty source tree: " + checked);
    }
}
