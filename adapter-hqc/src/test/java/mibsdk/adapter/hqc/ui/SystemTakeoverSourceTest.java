package mibsdk.adapter.hqc.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import org.junit.jupiter.api.Test;

/**
 * System takeover (Global Constraints): the classes {@code HMISystem=} loads in the stock System module run no class
 * initializer of their own. A failed one would stay failed for the JVM and take the main menu (and the firmware-update
 * screens) with it. Only compile-time constants are allowed: {@code static final} primitives and Strings.
 */
class SystemTakeoverSourceTest {

    private static final String[] FILES = {"SdkSystemActivator", "SdkSystemScreenFactory"};

    private static final Pattern STATIC_BLOCK = Pattern.compile("\\bstatic\\s*\\{");
    /** A static field with an initializer: {@code static ... name = ...;} (a method has a '(' before any '='). */
    private static final Pattern STATIC_INIT_FIELD = Pattern.compile("\\bstatic\\b([^;{}()]*?)=[^;]*;");
    private static final Pattern CONSTANT = Pattern.compile(
            "\\s*final\\s+(int|long|short|byte|char|boolean|float|double|String)\\s+[A-Z][A-Z0-9_]*\\s*");

    private static String code(String name) throws IOException {
        Path p = Paths.get("src/main/java/mibsdk/adapter/hqc/ui/" + name + ".java");
        assertTrue(Files.isRegularFile(p), "run from the adapter-hqc project dir: " + p.toAbsolutePath());
        String s = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        return s.replaceAll("//[^\\n]*|/\\*[\\s\\S]*?\\*/|\"(?:\\\\.|[^\"\\\\])*\"", "\"\""); // code, no literals
    }

    static List<String> violations(String code) {
        List<String> hits = new ArrayList<String>();
        Matcher b = STATIC_BLOCK.matcher(code);
        while (b.find()) {
            hits.add(b.group());
        }
        Matcher f = STATIC_INIT_FIELD.matcher(code);
        while (f.find()) {
            if (!CONSTANT.matcher(f.group(1)).matches()) {
                hits.add(f.group().trim());
            }
        }
        return hits;
    }

    @Test
    void patternCatchesInitializers() {
        assertFalse(violations("private static final String SOURCE = \"\";").size() > 0);
        assertFalse(violations("static final int N = 3;").size() > 0);
        assertFalse(violations("private static volatile boolean warned;").size() > 0);
        assertFalse(violations("static void f() { int x = 1; }").size() > 0);
        assertEquals(1, violations("static { x(); }").size());
        assertEquals(1, violations("private static volatile Object o = new Object();").size());
        assertEquals(1, violations("static final Object O = new Object();").size());
        assertEquals(1, violations("static int n = 0;").size());
        assertEquals(1, violations("private static final int[] IDS = {1};").size());
    }

    @Test
    void noStaticInitializers() throws IOException {
        List<String> hits = new ArrayList<String>();
        for (String f : FILES) {
            for (String v : violations(code(f))) {
                hits.add(f + ": " + v);
            }
        }
        assertEquals(new ArrayList<String>(), hits);
    }
}
