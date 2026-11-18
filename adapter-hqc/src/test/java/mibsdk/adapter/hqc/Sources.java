package mibsdk.adapter.hqc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Locates a main-source file by simple class name, wherever it sits in the adapter's package tree. */
public final class Sources {
    private static final Path ROOT = Paths.get("src/main/java/mibsdk/adapter/hqc");

    private Sources() {
    }

    public static Path find(String simpleName) throws IOException {
        List<Path> hits;
        try (Stream<Path> s = Files.walk(ROOT)) {
            hits = s.filter(p -> p.getFileName().toString().equals(simpleName + ".java")).collect(Collectors.toList());
        }
        if (hits.size() != 1) {
            throw new IOException("expected exactly one " + simpleName + ".java under " + ROOT.toAbsolutePath()
                    + " (run from the adapter-hqc project dir), found " + hits);
        }
        return hits.get(0);
    }
}
