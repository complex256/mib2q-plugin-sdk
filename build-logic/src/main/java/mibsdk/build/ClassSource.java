package mibsdk.build;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Reads every {@code .class} file of a directory or jar. Shared by LinkCheck and PermissionScan. */
final class ClassSource {
    /** A class file's internal name (derived from its path) and bytes. */
    static final class Entry {
        final String name;
        final byte[] bytes;

        Entry(String name, byte[] bytes) {
            this.name = name;
            this.bytes = bytes;
        }
    }

    interface Visitor {
        void accept(Entry entry);
    }

    private ClassSource() {}

    static void forEach(File dirOrJar, Visitor visitor) throws IOException {
        if (dirOrJar.isDirectory()) {
            Path root = dirOrJar.toPath();
            List<Path> files;
            try (Stream<Path> s = Files.walk(root)) {
                files = s.filter(p -> p.toString().endsWith(".class")).sorted().collect(Collectors.toList());
            }
            for (Path p : files) {
                String rel = root.relativize(p).toString().replace(File.separatorChar, '/');
                visitor.accept(new Entry(rel.substring(0, rel.length() - ".class".length()), Files.readAllBytes(p)));
            }
        } else if (dirOrJar.isFile()) {
            try (ZipFile zip = new ZipFile(dirOrJar)) {
                List<? extends ZipEntry> entries = new ArrayList<ZipEntry>(Collections.list(zip.entries()));
                for (ZipEntry e : entries) {
                    if (e.isDirectory() || !e.getName().endsWith(".class")) {
                        continue;
                    }
                    try (InputStream in = zip.getInputStream(e)) {
                        visitor.accept(new Entry(e.getName().substring(0, e.getName().length() - ".class".length()),
                                in.readAllBytes()));
                    }
                }
            }
        }
    }
}
