package mibsdk.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** The module layers point one way: api, then platform, then runtime, then the adapters. */
class LayeringTest {
    @Test
    void apiImportsNothingAbove() throws IOException {
        assertEquals(new ArrayList<String>(), offenders("../api", "mibsdk.platform", "mibsdk.runtime", "mibsdk.adapter"));
    }

    @Test
    void platformImportsNeitherRuntimeNorAdapters() throws IOException {
        assertEquals(new ArrayList<String>(), offenders("../platform", "mibsdk.runtime", "mibsdk.adapter"));
    }

    @Test
    void runtimeImportsNoAdapter() throws IOException {
        assertEquals(new ArrayList<String>(), offenders(".", "mibsdk.adapter"));
    }

    private static List<String> offenders(String module, String... forbidden) throws IOException {
        Path root = Paths.get(module, "src/main/java");
        List<String> hits = new ArrayList<String>();
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.filter(f -> f.toString().endsWith(".java")).collect(Collectors.toList())) {
                for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                    for (String f : forbidden) {
                        if (line.startsWith("import " + f + ".") || line.startsWith("import static " + f + ".")) {
                            hits.add(root.relativize(p) + ": " + line);
                        }
                    }
                }
            }
        }
        return hits;
    }
}
