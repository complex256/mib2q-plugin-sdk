package mibsdk.adapter.hqc;

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

/** jxe2jar constants are byte-swapped and javac inlines them: the adapter must read them via DsiConstants. */
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
}
