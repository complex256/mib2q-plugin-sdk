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
        "TextIds", "UiInputs", "DrawerAssignment", "HmiPoster", "PageEvents", "PageRenderer", "PageScreens",
        "PageWidgets", "IconIds", "HmiWidgets", "HmiWidgetState", "StatusLayout", "SdkStatusPopup",
        "SdkSystemActivator", "SdkSystemScreenFactory", "MainMenuKeyHandler", "MainMenuWrench", "PageRow",
        "PageLayout", "ItemIcons", "SliderSession", "SliderScreen", "InputTextIds", "ConfirmScreen", "SpellerScreen",
        "SpellerSession", "SdkToastPopup", "StatusPopupController", "SdkPopups"};

    private static final Pattern FORBIDDEN = Pattern.compile("java\\.io\\.|java\\.nio\\.|java\\.net\\.|\\bnew Thread\\b"
            + "|\\bThread\\.sleep\\b|\\.start\\(\\)|\\.join\\(|\\.wait\\(|\\bnew Timer\\b|System\\.(out|err)"
            + "|mibsdk\\.api\\.|mibsdk\\.runtime\\.(?!Clock;|RingLog;)|\\bawaitNewer\\b|\\.poll\\(");
    // mibsdk.runtime.Clock is allowed: HmiPoster reads the monotonic clock (one short monitor), never runtime code.
    // mibsdk.runtime.RingLog is allowed: the one way stock-thread code may log (PageScreens).

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
            {"SdkCarScreenFactory", "String getAltText"},
            {"SdkCarScreenFactory", "getImagePath"},
            {"SdkCarScreenFactory", "getPartialPopupStubs"},
            {"SdkCarScreenFactory", "getPartialPopup"},
            {"HmiWidgets", "void apply"},
            {"SdkCarModelBank", "createModel"},
            {"SdkCarModelBank", "itemSelected"},
            {"SdkCarActivator", "newBank"},
            {"UiInputs", "rowPressed"},
            {"SdkCarScreenFactory", "boolean install"},
            {"HmiPoster", "void runJob"},
            {"UiApplier", "void wake"},
            {"PageScreens", "void open"},
            {"PageScreens", "void refresh"},
            {"PageScreens", "void close"},
            {"PageScreens", "void listSelected"},
            {"PageScreens", "void retryClose"},
            {"PageScreens", "void backPressed"},
            {"PageScreens", "void screenConnected"},
            {"PageScreens", "void screenDisconnecting"},
            {"PageScreens", "void runPendingNav"},
            {"PageWidgets", "void keyPressed"},
            {"PageWidgets", "void keyReleased"},
            {"SdkCarModelBank", "void createPageList"},
            {"SdkSystemActivator", "AbstractScreenFactory getScreenFactory"},
            {"SdkSystemScreenFactory", "Screen createScreen"},
            {"SdkSystemScreenFactory", "Screen withPluginsRow"},
            {"SdkSystemScreenFactory", "String getText"}, // both overloads: getText(int), getText(int, int)
            {"SdkSystemScreenFactory", "String getAltText"},
            {"MainMenuKeyHandler", "void keyPressed"},
            {"MainMenuWrench", "boolean apply"},
            {"PageScreens", "void typedChosen"},
            {"PageScreens", "void sliderPressed"},
            {"PageScreens", "void sliderTurned"},
            {"PageScreens", "void subBack"},
            {"PageScreens", "void subConnected"},
            {"PageScreens", "void subDisconnecting"},
            {"SdkCarModelBank", "void increment"},
            {"SdkCarModelBank", "void decrement"},
            {"SdkCarModelBank", "void createInputModel"},
            {"SdkCarModelBank", "UiApplier.TypedModels typedHandle"},
            {"SliderScreen", "void keyPressed"},
            {"SliderScreen", "void keyReleased"},
            {"PageScreens", "void dialogChanged"},
            {"PageScreens", "void confirmSelected"},
            {"PageScreens", "void spellerTextChanged"},
            {"PageScreens", "void spellerCommand"},
            {"SdkCarModelBank", "void textChanged"},
            {"SdkCarModelBank", "void commandPressed"},
            {"StatusPopupController", "int hide"},
        };
        for (String[] o : overrides) {
            String src = source(o[0]);
            int at = declaration(src, o[1], 0);
            assertTrue(at >= 0, o[0] + "." + o[1]);
            // A name with its return type ("String getText") only matches declarations: check every overload.
            while (at >= 0) {
                String body = body(src, at);
                assertTrue(body.contains("catch (Throwable") || body.trim().isEmpty(),
                        o[0] + "." + o[1] + " must catch Throwable (or be an empty no-op): " + body);
                at = o[1].indexOf(' ') < 0 ? -1 : declaration(src, o[1], at + 1);
            }
        }
    }

    /** The checker itself: a nested-class method's body ends at its own brace, not at a later sibling's. */
    @Test
    void methodBodyEndsAtItsOwnBrace() {
        String src = "class A {\n    static final class B {\n        void f() {\n            g(\"}\");\n        }\n\n"
                + "        void h() {\n            try { g(); } catch (Throwable t) { }\n        }\n    }\n}\n";
        int f = declaration(src, "void f", 0);
        assertEquals("\n            g(\"}\");\n        ", body(src, f), "a brace in a string is not the end");
        assertTrue(!body(src, f).contains("catch (Throwable"), "h's catch is not f's");
        assertTrue(body(src, declaration(src, "void h", 0)).contains("catch (Throwable"));
        assertEquals(-1, declaration("class A { void x() { f(); } }", "f", 0), "a call is no declaration");
    }

    /** The checker itself: a comment's apostrophe or brace is no literal and no brace (Task 5 re-review). */
    @Test
    void methodBodySkipsComments() {
        String apostrophe = "class A {\n    void f() {\n        g(); // don't stop\n    }\n\n"
                + "    void h() {\n        try { g(); } catch (Throwable t) { }\n    }\n}\n";
        String f = body(apostrophe, declaration(apostrophe, "void f", 0));
        assertEquals("\n        g(); // don't stop\n    ", f, "a line comment's apostrophe starts no char literal");
        assertTrue(!f.contains("catch (Throwable"), "h's catch is not f's");
        String brace = "class A {\n    void f() {\n        g(); /* } it's */ h('}', \"/*\"); // {\n    }\n\n"
                + "    void h() {\n        try { g(); } catch (Throwable t) { }\n    }\n}\n";
        f = body(brace, declaration(brace, "void f", 0));
        assertEquals("\n        g(); /* } it's */ h('}', \"/*\"); // {\n    ", f,
                "a comment's brace is skipped; a comment opener inside a literal is no comment");
        assertTrue(!f.contains("catch (Throwable"), "h's catch is not f's");
    }

    /** The next declaration of {@code name} at or after {@code from} (a call, followed by ';', is skipped); or -1. */
    static int declaration(String src, String name, int from) {
        int at = src.indexOf(" " + name + "(", from);
        while (at >= 0) {
            int paren = src.indexOf(')', at);
            int open = src.indexOf('{', paren);
            int semi = src.indexOf(';', paren);
            if (open >= 0 && (semi < 0 || open < semi)) {
                return at;
            }
            at = src.indexOf(" " + name + "(", at + 1);
        }
        return -1;
    }

    /**
     * The body (between its braces) of the method declared at {@code at}, by brace depth; comments, then literals,
     * skipped (an apostrophe or brace in a comment is neither).
     */
    static String body(String src, int at) {
        int open = src.indexOf('{', src.indexOf(')', at));
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);
            if (src.startsWith("//", i)) {
                int nl = src.indexOf('\n', i);
                i = nl < 0 ? src.length() : nl;
            } else if (src.startsWith("/*", i)) {
                int end = src.indexOf("*/", i + 2);
                i = end < 0 ? src.length() : end + 1;
            } else if (c == '"' || c == '\'') {
                for (i++; i < src.length() && src.charAt(i) != c; i++) {
                    if (src.charAt(i) == '\\') {
                        i++;
                    }
                }
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return src.substring(open + 1, i);
            }
        }
        return src.substring(open + 1);
    }
}
