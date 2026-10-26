package mibsdk.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ManifestParserTest {

    private static String manifest(String id, String api, String extra) {
        return "{\"id\":\"" + id + "\",\"name\":\"Bike Rack\",\"version\":\"1.0.0\",\"author\":\"complex256\","
                + "\"api\":" + api + ",\"main\":\"dev.example.Main\"" + extra + "}";
    }

    @Test
    void parsesFullManifest() throws Exception {
        PluginManifest m = ManifestParser.parse(manifest("bike-rack", "1",
                ",\"permissions\":[\"vehicle.read\",\"parking.write\"],\"dependencies\":[\"other-plugin\"]"));
        assertEquals("bike-rack", m.id());
        assertEquals("Bike Rack", m.name());
        assertEquals("1.0.0", m.version());
        assertEquals("complex256", m.author());
        assertEquals(1, m.api());
        assertEquals("dev.example.Main", m.main());
        assertEquals(Arrays.asList("vehicle.read", "parking.write"), m.permissions());
        assertEquals(Arrays.asList("other-plugin"), m.dependencies());
    }

    @Test
    void rejectsBadId() {
        ManifestException e = assertThrows(ManifestException.class,
                () -> ManifestParser.parse(manifest("Bike_Rack", "1", "")));
        assertTrue(e.getMessage().contains("id"), e.getMessage());
        for (String bad : new String[] {"a", "-ab", "ab_c", "Ab", "", "a b",
            "abcdefghijabcdefghijabcdefghijabcdefghijab" /* 42 chars */}) {
            assertThrows(ManifestException.class, () -> ManifestParser.parse(manifest(bad, "1", "")), bad);
        }
    }

    @Test
    void acceptsIdBoundaries() throws Exception {
        assertEquals("ab", ManifestParser.parse(manifest("ab", "1", "")).id());
        assertEquals("0-", ManifestParser.parse(manifest("0-", "1", "")).id());
        String max = "abcdefghijabcdefghijabcdefghijabcdefghija"; // 41 chars
        assertEquals(41, max.length());
        assertEquals(max, ManifestParser.parse(manifest(max, "1", "")).id());
    }

    @Test
    void acceptsApi1To5AndIconsOnlyWithApi3() throws Exception {
        for (int api = 1; api <= 5; api++) {
            assertEquals(api, ManifestParser.parse(manifest("bike-rack", String.valueOf(api), "")).api());
        }
        assertEquals(Arrays.asList("hello"),
                ManifestParser.parse(manifest("bike-rack", "5", ",\"icons\":[\"hello\"]")).icons());

        assertEquals(Collections.emptyList(), ManifestParser.parse(manifest("bike-rack", "3", "")).icons());
        assertEquals(Collections.emptyList(), ManifestParser.parse(manifest("bike-rack", "2", "")).icons());
        assertEquals(Arrays.asList("hello", "hello-20"),
                ManifestParser.parse(manifest("bike-rack", "3", ",\"icons\":[\"hello\",\"hello-20\"]")).icons());
        String max = "abcdefghijabcdefghijabcdefghij-2"; // 32 characters
        assertEquals(Arrays.asList(max),
                ManifestParser.parse(manifest("bike-rack", "3", ",\"icons\":[\"" + max + "\"]")).icons());
        assertEquals(8, ManifestParser.parse(manifest("bike-rack", "3",
                ",\"icons\":[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\",\"g\",\"h\"]")).icons().size());

        ManifestException e = assertThrows(ManifestException.class,
                () -> ManifestParser.parse(manifest("bike-rack", "2", ",\"icons\":[\"hello\"]")));
        assertEquals("icons need api 3", e.getMessage());
        e = assertThrows(ManifestException.class,
                () -> ManifestParser.parse(manifest("bike-rack", "3", ",\"icons\":[\"hello\",\"hello\"]")));
        assertEquals("duplicate icon 'hello'", e.getMessage());
        e = assertThrows(ManifestException.class, () -> ManifestParser.parse(manifest("bike-rack", "3",
                ",\"icons\":[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\",\"g\",\"h\",\"i\"]")));
        assertEquals("at most 8 icons", e.getMessage());
        for (String bad : new String[] {"", "Hello", "a_b", "a.png", max + "x"}) {
            e = assertThrows(ManifestException.class, () -> ManifestParser.parse(
                    manifest("bike-rack", "3", ",\"icons\":[\"" + bad + "\"]")), bad);
            assertEquals("invalid icon name '" + bad + "': must match [a-z0-9-]{1,32}", e.getMessage());
        }
        for (String bad : new String[] {"\"hello\"", "[1]", "[null]"}) {
            e = assertThrows(ManifestException.class,
                    () -> ManifestParser.parse(manifest("bike-rack", "3", ",\"icons\":" + bad)), bad);
            assertEquals("icons must be an array of strings", e.getMessage());
        }
    }

    @Test
    void apiAboveFiveIsRefused() {
        for (String bad : new String[] {"6", "0", "-1"}) {
            ManifestException e = assertThrows(ManifestException.class,
                    () -> ManifestParser.parse(manifest("bike-rack", bad, "")), bad);
            assertEquals("unsupported api " + bad + ", this SDK implements api 1..5", e.getMessage());
        }
    }

    @Test
    void rejectsReservedIdMibsdk() {
        ManifestException e = assertThrows(ManifestException.class,
                () -> ManifestParser.parse(manifest("mibsdk", "1", "")));
        assertEquals("reserved plugin id 'mibsdk'", e.getMessage());
    }

    @Test
    void rejectsEmptyOrMissingMain() {
        assertThrows(ManifestException.class, () -> ManifestParser.parse(
                "{\"id\":\"bike-rack\",\"api\":1,\"main\":\"\"}"));
        assertThrows(ManifestException.class, () -> ManifestParser.parse("{\"id\":\"bike-rack\",\"api\":1}"));
    }

    @Test
    void defaultsOptionalLists() throws Exception {
        PluginManifest m = ManifestParser.parse(manifest("bike-rack", "1", ""));
        assertEquals(Collections.emptyList(), m.permissions());
        assertEquals(Collections.emptyList(), m.dependencies());
    }

    @Test
    void miniJsonHandlesEscapesAndNesting() throws Exception {
        Object o = MiniJson.parse(
                " {\"k\":\"a\\\"b\\n\\u0041\\\\\",\"n\":[[1,[-2]],[],{\"x\":null}],\"t\":true,\"f\":false} ");
        Map<?, ?> m = (Map<?, ?>) o;
        assertEquals("a\"b\nA\\", m.get("k"));
        List<?> n = (List<?>) m.get("n");
        assertEquals(Arrays.asList(Long.valueOf(1), Arrays.asList(Long.valueOf(-2))), n.get(0));
        assertEquals(Collections.emptyList(), n.get(1));
        assertNull(((Map<?, ?>) n.get(2)).get("x"));
        assertEquals(Boolean.TRUE, m.get("t"));
        assertEquals(Boolean.FALSE, m.get("f"));
    }

    @Test
    void miniJsonRejectsMalformed() {
        for (String bad : new String[] {"", "{", "[1,]", "{\"a\":1,}", "{\"a\" 1}", "\"abc", "1.5", "tru",
            "{} x", "[\"\\q\"]", "\"\\u12\""}) {
            assertThrows(ManifestException.class, () -> MiniJson.parse(bad), bad);
        }
    }
}
