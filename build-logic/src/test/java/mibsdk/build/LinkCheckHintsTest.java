package mibsdk.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class LinkCheckHintsTest {

    @Test
    void backportNamesMapBack() {
        assertEquals("java/util/concurrent/ConcurrentHashMap", LinkCheckHints.jdkName(
                "edu/emory/mathcs/backport/java/util/concurrent/ConcurrentHashMap"));
        assertEquals("java/lang/String", LinkCheckHints.jdkName(
                "net/sf/retrotranslator/runtime/java/lang/_String"));
        assertEquals("java/lang/String", LinkCheckHints.jdkName("java/lang/String"));
    }

    @Test
    void stringFormatMapsBack() {
        assertEquals("java/lang/String", LinkCheckHints.jdkName(
                "net/sf/retrotranslator/runtime/java/lang/_String"));
    }

    @Test
    void regexHint() {
        assertEquals("no regex on the car: use indexOf/substring or a loop",
                LinkCheckHints.hint("member java/lang/String.matches(Ljava/lang/String;)Z"));
        assertEquals("no regex on the car: use indexOf/substring or a loop",
                LinkCheckHints.hint("member java/lang/String.split(Ljava/lang/String;)[Ljava/lang/String;"));
    }

    @Test
    void concurrentAndFunctionHints() {
        assertEquals("no java.util.concurrent: the plugin runs on one SDK worker, so plain fields need no locks",
                LinkCheckHints.hint(
                        "class edu/emory/mathcs/backport/java/util/concurrent/ConcurrentHashMap"));
        assertEquals("compat has only some java.util.function interfaces;"
                        + " see compat/src/main/java/mibsdk/compat/java/util/function",
                LinkCheckHints.hint("class java/util/function/BinaryOperator"));
        assertNull(LinkCheckHints.hint("class java/lang/AutoCloseable (interface of a/B)"));
    }
}
