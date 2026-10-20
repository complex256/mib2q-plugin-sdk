package mibsdk.adapter.hqc.ui;

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
import org.junit.jupiter.api.Test;

/**
 * Carried requirements 1 and 5, at the source: the classes that run on stock threads (LSD startup, HMIEvent) do no
 * I/O, create or wait on no thread, never block on the SDK, and never reach plugin code. Each stock-thread override
 * catches Throwable.
 */
class StockThreadCodeTest {

    private static final String[] STOCK_THREAD_FILES = {"SdkCarActivator", "SdkCarModelBank", "SdkCarScreenFactory",
        "TextIds", "UiInputs", "DrawerAssignment"};

    private static final Pattern FORBIDDEN = Pattern.compile("java\\.io\\.|java\\.nio\\.|java\\.net\\.|\\bnew Thread\\b"
            + "|\\bThread\\.sleep\\b|\\.start\\(\\)|\\.join\\(|\\.wait\\(|\\bnew Timer\\b|System\\.(out|err)"
            + "|mibsdk\\.api\\.|mibsdk\\.runtime\\.|\\bawaitNewer\\b|\\.poll\\(");

    private static String source(String name) throws IOException {
        Path p = Paths.get("src/main/java/mibsdk/adapter/hqc/ui/" + name + ".java");
        assertTrue(Files.isRegularFile(p), "run from the adapter-hqc project dir: " + p.toAbsolutePath());
        String s = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        return s.replaceAll("//[^\\n]*|/\\*[\\s\\S]*?\\*/", " "); // code only
    }

    @Test
    void stockThreadClassesDoNoIoStartNoThreadAndRunNoPluginCode() throws IOException {
        List<String> hits = new ArrayList<String>();
        for (String f : STOCK_THREAD_FILES) {
            Matcher m = FORBIDDEN.matcher(source(f));
            while (m.find()) {
                hits.add(f + ": " + m.group());
            }
        }
        assertEquals(new ArrayList<String>(), hits);
    }

    @Test
    void stockThreadOverridesCatchThrowable() throws IOException {
        String[][] overrides = {
            {"SdkCarScreenFactory", "getSelectionDrawers"},
            {"SdkCarScreenFactory", "getText"},
            {"SdkCarScreenFactory", "getAltText"},
            {"SdkCarModelBank", "createModel"},
            {"SdkCarModelBank", "itemSelected"},
            {"SdkCarActivator", "newBank"},
            {"UiInputs", "rowPressed"},
        };
        for (String[] o : overrides) {
            String src = source(o[0]);
            int at = src.indexOf(" " + o[1] + "(");
            assertTrue(at >= 0, o[0] + "." + o[1]);
            int next = src.indexOf("\n    }", at); // end of the method (4-space indented class member)
            String body = src.substring(at, next < 0 ? src.length() : next);
            assertTrue(body.contains("catch (Throwable"), o[0] + "." + o[1] + " must catch Throwable");
        }
    }
}
