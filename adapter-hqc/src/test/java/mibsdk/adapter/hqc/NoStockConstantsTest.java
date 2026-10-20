package mibsdk.adapter.hqc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * jxe2jar constants are byte-swapped and javac inlines them: the adapter must read them via DsiConstants, and the UI
 * package takes every stock id from HqcUiIds' literals.
 */
class NoStockConstantsTest {

    private static final Pattern STOCK_CONSTANT = Pattern.compile("\\b[A-Z]\\w*\\.(ATTR|RT|ATTRVALIDFLAG)_[A-Z0-9_]+");

    @Test
    void patternCatchesStockConstantRefs() {
        assertTrue(STOCK_CONSTANT.matcher("int f = DSIListener.ATTRVALIDFLAG_VALID;").find());
        assertTrue(STOCK_CONSTANT.matcher("x(DSICarParkingSystem.ATTR_PDCSOUNDREPRODUCTION)").find());
        assertTrue(STOCK_CONSTANT.matcher("DSIListener.RT_NONE").find());
        assertTrue(!STOCK_CONSTANT.matcher("DsiConstants.get(\"org.dsi.ifc.base.DSIListener\", \"RT_NONE\")").find());
    }

    @Test
    void adapterSourceHasNoStockConstantRefs() throws IOException {
        Path src = Paths.get("src/main/java");
        assertTrue(Files.isDirectory(src), "run from the adapter-hqc project dir: " + src.toAbsolutePath());
        List<Path> files;
        try (Stream<Path> s = Files.walk(src)) {
            files = s.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
        }
        assertTrue(files.size() >= 6, "adapter sources found: " + files);
        List<String> hits = new ArrayList<>();
        for (Path f : files) {
            String text = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
            Matcher m = STOCK_CONSTANT.matcher(text);
            while (m.find()) {
                hits.add(src.relativize(f) + ": " + m.group());
            }
        }
        assertEquals(new ArrayList<String>(), hits);
    }

    // ---- UI package: no stock constant at all (Global Constraints, "New for UI") ----

    private static final Pattern IMPORT = Pattern.compile("(?m)^\\s*import\\s+(static\\s+)?([\\w.]+?)(\\.\\*)?\\s*;");
    private static final Pattern QUALIFIED_CONSTANT =
            Pattern.compile("(?<![\\w.$])([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)\\.([A-Z][A-Z0-9_]+)\\b");
    private static final Pattern COMMENT_OR_LITERAL =
            Pattern.compile("//[^\\n]*|/\\*[\\s\\S]*?\\*/|\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'");

    private static boolean allowedPackage(String name) {
        return name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("mibsdk.");
    }

    /**
     * Every {@code Q.NAME} (NAME matching {@code [A-Z][A-Z0-9_]+}) where Q is a fully qualified {@code de.} name or a
     * simple name imported from a package outside java., javax., mibsdk.; plus static or wildcard imports from such a
     * package (they would hide a stock constant behind a bare or unresolvable name).
     */
    static List<String> uiStockConstantRefs(String source) {
        String code = COMMENT_OR_LITERAL.matcher(source).replaceAll(" ");
        List<String> hits = new ArrayList<String>();
        Map<String, String> imported = new HashMap<String, String>();
        Matcher im = IMPORT.matcher(code);
        while (im.find()) {
            String name = im.group(2);
            boolean outside = !allowedPackage(name);
            if (outside && (im.group(1) != null || im.group(3) != null)) {
                hits.add(im.group().trim());
            } else if (im.group(1) == null && im.group(3) == null) {
                imported.put(name.substring(name.lastIndexOf('.') + 1), name);
            }
        }
        String body = IMPORT.matcher(code).replaceAll(" ").replaceAll("(?m)^\\s*package\\s+[\\w.]+\\s*;", " ");
        Matcher m = QUALIFIED_CONSTANT.matcher(body);
        while (m.find()) {
            String q = m.group(1);
            String first = q.indexOf('.') < 0 ? q : q.substring(0, q.indexOf('.'));
            String from = imported.get(first);
            if (q.startsWith("de.") || (from != null && !allowedPackage(from))) {
                hits.add(m.group());
            }
        }
        return hits;
    }

    @Test
    void uiPackageHasNoStockConstants() throws IOException {
        // The rule itself.
        assertEquals(java.util.Collections.singletonList("Foo.TYPE_X"),
                uiStockConstantRefs("package x;\nimport de.audi.Foo;\nclass A { int a = Foo.TYPE_X; }\n"));
        assertEquals(java.util.Collections.singletonList("de.audi.Foo.TYPE_X"),
                uiStockConstantRefs("class A { int a = de.audi.Foo.TYPE_X; }"));
        assertEquals(java.util.Collections.singletonList("Bar.Inner.KEY_UP"),
                uiStockConstantRefs("import de.audi.Bar;\nclass A { int a = Bar.Inner.KEY_UP; }"));
        assertEquals(1, uiStockConstantRefs("import static de.audi.Foo.TYPE_X;\nclass A { int a = TYPE_X; }").size());
        assertEquals(1, uiStockConstantRefs("import de.audi.*;\nclass A { int a = Foo.TYPE_X; }").size());
        assertEquals(new ArrayList<String>(), uiStockConstantRefs("package mibsdk.adapter.hqc.ui;\n"
                + "import mibsdk.platform.UiInput;\nimport de.audi.Foo;\n"
                + "class A { int a = HqcUiIds.TEXT_BASE; int b = UiInput.ROW_SELECTED; int c = Thread.NORM_PRIORITY;\n"
                + "  int d = java.lang.Integer.MAX_VALUE; Foo f = new Foo(); int e = Foo.lowerCase;\n"
                + "  String s = \"Foo.TYPE_X\"; // Foo.TYPE_Y\n /* Foo.TYPE_Z */ }\n"));

        // The package.
        Path ui = Paths.get("src/main/java/mibsdk/adapter/hqc/ui");
        assertTrue(Files.isDirectory(ui), "run from the adapter-hqc project dir: " + ui.toAbsolutePath());
        List<Path> files;
        try (Stream<Path> s = Files.walk(ui)) {
            files = s.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
        }
        assertTrue(files.size() >= 9, "ui sources found: " + files);
        List<String> hits = new ArrayList<>();
        for (Path f : files) {
            for (String h : uiStockConstantRefs(new String(Files.readAllBytes(f), StandardCharsets.UTF_8))) {
                hits.add(ui.relativize(f) + ": " + h);
            }
        }
        assertEquals(new ArrayList<String>(), hits);
    }
}
