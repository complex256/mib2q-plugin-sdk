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
    void rejectsWrongApi() {
        ManifestException e = assertThrows(ManifestException.class,
                () -> ManifestParser.parse(manifest("bike-rack", "2", "")));
        assertTrue(e.getMessage().contains("api"), e.getMessage());
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
