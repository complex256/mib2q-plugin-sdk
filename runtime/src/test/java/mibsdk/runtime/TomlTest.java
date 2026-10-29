package mibsdk.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TomlTest {

    @Test
    void readsStringsBooleansAndIntegers() {
        Toml.Table t = Toml.parse("# comment\n"
                + "name = \"beepboop\" # trailing\n"
                + "literal = 'a\\b'\n"
                + "enabled = true\n"
                + "disabled = false\n"
                + "port = 1234\n"
                + "escaped = \"a\\\"b\\\\c\\td\"\n");
        assertEquals("beepboop", t.string("name", ""));
        assertEquals("a\\b", t.string("literal", ""));
        assertTrue(t.bool("enabled", false));
        assertFalse(t.bool("disabled", true));
        assertEquals(1234L, t.integer("port", 0));
        assertEquals("a\"b\\c\td", t.string("escaped", ""));
    }

    @Test
    void missingKeysUseTheDefault() {
        Toml.Table t = Toml.parse("a = 1\n");
        assertEquals("x", t.string("b", "x"));
        assertTrue(t.bool("c", true));
        assertEquals(7L, t.integer("d", 7));
        assertFalse(t.has("b"));
    }

    @Test
    void duplicateUnknownAndUnsupportedSyntaxAreErrors() {
        assertThrows(Toml.TomlException.class, () -> Toml.parse("a = 1\na = 2\n"));
        assertThrows(Toml.TomlException.class, () -> Toml.parse("[table]\na = 1\n"));
        assertThrows(Toml.TomlException.class, () -> Toml.parse("a = [1, 2]\n"));
        assertThrows(Toml.TomlException.class, () -> Toml.parse("a = \"unterminated\n"));
        assertThrows(Toml.TomlException.class, () -> Toml.parse("a = 1 bad\n"));
        assertThrows(Toml.TomlException.class, () -> Toml.parse("a b = 1\n"));
        Toml.Table t = Toml.parse("a = 1\n");
        assertThrows(Toml.TomlException.class, () -> t.rejectUnknown(new String[] {"b"}));
    }

    @Test
    void writerRoundTripsEveryValue() {
        String text = new Toml.Writer()
                .comment("cfg")
                .string("ssid", "a\"b\\c\td")
                .bool("enabled", true)
                .integer("n", -3)
                .toString();
        Toml.Table t = Toml.parse(text);
        assertEquals("a\"b\\c\td", t.string("ssid", ""));
        assertTrue(t.bool("enabled", false));
        assertEquals(-3L, t.integer("n", 0));
    }
}
