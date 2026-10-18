package mibsdk.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
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
    void cleanFixturePasses() throws Exception {
        LinkCheck.Result r = check(Map.of("fixture.Clean",
                "package fixture; public class Clean { public Object m() { return new StringBuffer().append(\"x\"); } }"));
        assertEquals(List.of(), r.missing());
    }

    @Test
    void realStockLsdJarFlagsAutoCloseableAndAcceptsDouble() throws Exception {
        File lsd = new File(System.getProperty("mibsdk.stockLsdJar",
                System.getProperty("user.home") + "/projects/audi-mib2/patching/lsd.jar"));
        Assumptions.assumeTrue(lsd.isFile(), "stock lsd.jar not present");
        File classes = Fixtures.compileV48(tmp.resolve("classes"), Map.of(
                "fixture.Res", "package fixture; public class Res implements AutoCloseable { public void close() {} }",
                "fixture.Boxer", "package fixture; public class Boxer { public Object m() { return Double.valueOf(1.0); } }"));
        LinkCheck.Result r = LinkCheck.run(classes, List.of(lsd, classes), Collections.emptyList());
        assertEquals(List.of("class java/lang/AutoCloseable (interface of fixture/Res)"), r.missing());
    }
}
