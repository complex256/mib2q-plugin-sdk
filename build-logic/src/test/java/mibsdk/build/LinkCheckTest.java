package mibsdk.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LinkCheckTest {
    @TempDir Path tmp;

    private LinkCheck.Result check(Map<String, String> sources) throws Exception {
        File jcl = Fixtures.stubJcl(tmp.resolve("jcl"));
        File classes = Fixtures.compileV48(tmp.resolve("classes"), sources);
        return LinkCheck.run(classes, List.of(jcl, classes), Collections.emptyList());
    }

    @Test
    void reportsMissingInterfaceInClassHeader() throws Exception {
        LinkCheck.Result r = check(Map.of("fixture.Res",
                "package fixture; public class Res implements AutoCloseable { public void close() {} }"));
        assertTrue(r.missing().contains("class java/lang/AutoCloseable (interface of fixture/Res)"), r.missing().toString());
    }

    @Test
    void reportsMissingCatchType() throws Exception {
        LinkCheck.Result r = check(Map.of("fixture.Catcher",
                "package fixture; public class Catcher { public void m() {"
                        + " try { new Object().hashCode(); } catch (java.util.regex.PatternSyntaxException e) { } } }"));
        assertTrue(r.missing().contains("class java/util/regex/PatternSyntaxException"), r.missing().toString());
    }

    @Test
    void reportsMissingMember() throws Exception {
        LinkCheck.Result r = check(Map.of("fixture.Merger",
                "package fixture; public class Merger { @SuppressWarnings({\"rawtypes\", \"unchecked\"})"
                        + " public void m(java.util.Map map) { map.merge(\"a\", \"b\", (java.util.function.BiFunction) null); } }"));
        assertTrue(r.missing().contains(
                "member java/util/Map.merge(Ljava/lang/Object;Ljava/lang/Object;Ljava/util/function/BiFunction;)Ljava/lang/Object;"),
                r.missing().toString());
    }

    @Test
    void followsTransitiveLibraryBodies() throws Exception {
        File jcl = Fixtures.stubJcl(tmp.resolve("jcl"));
        File libClasses = Fixtures.compileV48(tmp.resolve("libclasses"), Map.of("lib.L",
                "package lib; public class L { public static void f() { java.util.regex.Pattern.compile(\"x\"); } }"));
        File lib = Fixtures.jar(tmp.resolve("lib.jar"), libClasses);
        File classes = Fixtures.compileV48(tmp.resolve("classes"), Map.of("fixture.Caller",
                "package fixture; public class Caller { public void m() { lib.L.f(); } }"), libClasses);
        LinkCheck.Result r = LinkCheck.run(classes, List.of(jcl, classes), List.of(lib));
        assertTrue(r.missing().contains("class java/util/regex/Pattern (via lib/L.f()V)"), r.missing().toString());
        // A lib that is only on the classpath (not transitive) has its bodies left alone.
        LinkCheck.Result plain = LinkCheck.run(classes, List.of(jcl, classes, lib), Collections.emptyList());
        assertEquals(List.of(), plain.missing());
    }

    @Test
    void followsTransitiveLibraryBodiesWhenLibIsAlsoOnClasspath() throws Exception {
        File jcl = Fixtures.stubJcl(tmp.resolve("jcl"));
        File libClasses = Fixtures.compileV48(tmp.resolve("libclasses"), Map.of("lib.L",
                "package lib; public class L { public static void f() { java.util.regex.Pattern.compile(\"x\"); } }"));
        File lib = Fixtures.jar(tmp.resolve("lib.jar"), libClasses);
        File classes = Fixtures.compileV48(tmp.resolve("classes"), Map.of("fixture.Caller",
                "package fixture; public class Caller { public void m() { lib.L.f(); } }"), libClasses);
        LinkCheck.Result r = LinkCheck.run(classes, List.of(jcl, classes, lib), List.of(lib));
        assertTrue(r.missing().contains("class java/util/regex/Pattern (via lib/L.f()V)"), r.missing().toString());
    }

    @Test
    void treatsKnownUnreadableAsPresent() throws Exception {
        LinkCheck.Result r = check(Map.of("fixture.Boxer",
                "package fixture; public class Boxer { public Object m() { return Double.valueOf(1.0); } }"));
        assertEquals(List.of(), r.missing());
    }

    @Test
    void sitesNameTheReferencingMethod() throws Exception {
        LinkCheck.Result r = check(Map.of(
                "a.B", "package a; public class B { public boolean run() { return \"x\".matches(\"y\"); } }",
                "a.Res", "package a; public class Res implements AutoCloseable { public void close() {} }"));
        String member = "member java/lang/String.matches(Ljava/lang/String;)Z";
        assertTrue(r.missing().contains(member), r.missing().toString());
        assertEquals(List.of("a/B.run"), r.sites().get(member));
        // A header line names no method of the checked code.
        assertEquals(List.of(), r.sites().get("class java/lang/AutoCloseable (interface of a/Res)"));
    }

    @Test
    void cleanFixturePasses() throws Exception {
        LinkCheck.Result r = check(Map.of("fixture.Clean",
                "package fixture; public class Clean { public Object m() { return new StringBuffer().append(\"x\"); } }"));
        assertEquals(List.of(), r.missing());
    }

    /**
     * Checks {@code sources} against the fixture JCL as classes, as a signature list and as a directory holding
     * that list; asserts all three agree and returns the verdict. Each case works in its own sub-directory.
     */
    private List<String> sameVerdictFromSignatureList(String caseName, Map<String, String> sources) throws Exception {
        Path dir = Files.createDirectory(tmp.resolve(caseName));
        File jcl = Fixtures.stubJcl(dir.resolve("jcl"));
        File sigDir = Files.createDirectory(dir.resolve("sigdir")).toFile();
        File sigs = new File(sigDir, "jcl.sigs");
        try (Writer w = Files.newBufferedWriter(sigs.toPath(), StandardCharsets.UTF_8)) {
            CarJclSignatures.write(jcl, "TEST", w);
        }
        File classes = Fixtures.compileV48(dir.resolve("classes"), sources);
        List<String> fromClasses = LinkCheck.run(classes, List.of(jcl), Collections.emptyList()).missing();
        assertEquals(fromClasses, LinkCheck.run(classes, List.of(sigs), Collections.emptyList()).missing(), caseName);
        assertEquals(fromClasses, LinkCheck.run(classes, List.of(sigDir), Collections.emptyList()).missing(), caseName);
        return fromClasses;
    }

    @Test
    void signatureListGivesTheSameResultAsTheClasses() throws Exception {
        assertEquals(List.of("class java/lang/AutoCloseable (interface of fixture/Res)"),
                sameVerdictFromSignatureList("header", Map.of("fixture.Res",
                        "package fixture; public class Res implements AutoCloseable { public void close() {} }")));
        assertEquals(List.of("class java/util/regex/PatternSyntaxException"),
                sameVerdictFromSignatureList("catch", Map.of("fixture.Catcher",
                        "package fixture; public class Catcher { public void m() {"
                                + " try { new Object().hashCode(); } catch (java.util.regex.PatternSyntaxException e) { } } }")));
        assertTrue(sameVerdictFromSignatureList("member", Map.of("fixture.Merger",
                "package fixture; public class Merger { @SuppressWarnings({\"rawtypes\", \"unchecked\"})"
                        + " public void m(java.util.Map map) { map.merge(\"a\", \"b\", (java.util.function.BiFunction) null); } }"))
                .contains("member java/util/Map.merge(Ljava/lang/Object;Ljava/lang/Object;Ljava/util/function/BiFunction;)Ljava/lang/Object;"));
        assertEquals(List.of(), sameVerdictFromSignatureList("unreadable", Map.of("fixture.Boxer",
                "package fixture; public class Boxer { public Object m() { return Double.valueOf(1.0); } }")));
        assertEquals(List.of(), sameVerdictFromSignatureList("clean", Map.of("fixture.Clean",
                "package fixture; public class Clean { public Object m() { return new StringBuffer().append(\"x\"); } }")));
    }

    @Test
    void signatureListMatchesJarForUnreadableClasses() throws Exception {
        File jcl = Fixtures.stubJcl(tmp.resolve("jcl"));
        File sigs = tmp.resolve("jcl.sigs").toFile();
        try (Writer w = Files.newBufferedWriter(sigs.toPath(), StandardCharsets.UTF_8)) {
            CarJclSignatures.write(Fixtures.jar(tmp.resolve("jcl.jar"), jcl), "TEST", w);
        }
        assertTrue(Files.readString(sigs.toPath()).contains("U java/lang/Double\n"));
        File classes = Fixtures.compileV48(tmp.resolve("classes"), Map.of("fixture.Boxer",
                "package fixture; public class Boxer { public Object m() { return Double.valueOf(1.0); } }"));
        assertEquals(List.of(), LinkCheck.run(classes, List.of(sigs), Collections.emptyList()).missing());
    }

    @Test
    void signatureListUnreadableEntryIsPresentButAbsentWithoutIt() throws Exception {
        // java/lang/Long is corrupt here and, unlike Double, not on the built-in known-unreadable list.
        File jcl = Fixtures.stubJcl(tmp.resolve("jcl"));
        Files.write(jcl.toPath().resolve("java/lang/Long.class"),
                new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0, 0, 48, 0, 1});
        File classes = Fixtures.compileV48(tmp.resolve("classes"), Map.of("fixture.Boxer",
                "package fixture; public class Boxer { public Object m() { return Long.valueOf(1L); } }"));
        assertEquals(List.of("class java/lang/Long"), LinkCheck.run(classes, List.of(jcl), Collections.emptyList()).missing());
        File sigs = tmp.resolve("jcl.sigs").toFile();
        try (Writer w = Files.newBufferedWriter(sigs.toPath(), StandardCharsets.UTF_8)) {
            CarJclSignatures.write(jcl, "TEST", w);
        }
        assertTrue(Files.readString(sigs.toPath()).contains("U java/lang/Long\n"));
        assertEquals(List.of(), LinkCheck.run(classes, List.of(sigs), Collections.emptyList()).missing());
    }

    @Test
    void realStockLsdJarFlagsAutoCloseableAndAcceptsDouble() throws Exception {
        String property = System.getProperty("mibsdk.stockLsdJar");
        Assumptions.assumeTrue(property != null && new File(property).isFile(),
                "needs -Pmibsdk.stockLsdJar=<path to lsd.jar>");
        File lsd = new File(property);
        File classes = Fixtures.compileV48(tmp.resolve("classes"), Map.of(
                "fixture.Res", "package fixture; public class Res implements AutoCloseable { public void close() {} }",
                "fixture.Boxer", "package fixture; public class Boxer { public Object m() { return Double.valueOf(1.0); } }"));
        LinkCheck.Result r = LinkCheck.run(classes, List.of(lsd, classes), Collections.emptyList());
        assertEquals(List.of("class java/lang/AutoCloseable (interface of fixture/Res)"), r.missing());
    }
}
