package mibsdk.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Keeps api/permissions.json (shipped to mibpkg) in sync with {@link Permissions} and {@link SdkInfo}. */
class PermissionCatalogTest {
    private static final Pattern ENTRY = Pattern.compile(
            "\\{\"id\":\"([^\"]+)\",\"group\":\"([^\"]*)\",\"text\":\"([^\"]*)\",\"loud\":(true|false)\\}");

    private static String json() throws Exception {
        Path p = Paths.get("permissions.json");
        if (!Files.isRegularFile(p)) {
            p = Paths.get("api", "permissions.json");
        }
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    private static List<String[]> entries() throws Exception {
        List<String[]> out = new ArrayList<String[]>();
        Matcher m = ENTRY.matcher(json());
        while (m.find()) {
            out.add(new String[] {m.group(1), m.group(2), m.group(3), m.group(4)});
        }
        return out;
    }

    @Test
    void everyPermissionsConstantHasExactlyOneEntryAndViceVersa() throws Exception {
        List<String> constants = new ArrayList<String>();
        for (Field f : Permissions.class.getDeclaredFields()) {
            if (Modifier.isPublic(f.getModifiers()) && Modifier.isStatic(f.getModifiers())
                    && Modifier.isFinal(f.getModifiers()) && f.getType() == String.class) {
                constants.add((String) f.get(null));
            }
        }
        List<String> ids = new ArrayList<String>();
        for (String[] e : entries()) {
            ids.add(e[0]);
        }
        assertEquals(7, constants.size());
        assertEquals(7, ids.size());
        assertEquals(constants.size(), new java.util.HashSet<String>(ids).size(), "duplicate ids");
        assertTrue(ids.containsAll(constants), "missing from json: " + constants);
        assertTrue(constants.containsAll(ids), "unknown in json: " + ids);
    }

    @Test
    void textIsNonEmptyAndOnlyUnrestrictedIsLoud() throws Exception {
        for (String[] e : entries()) {
            assertFalse(e[2].trim().isEmpty(), e[0] + " has no text");
            assertEquals(Permissions.PLATFORM_UNRESTRICTED.equals(e[0]), Boolean.parseBoolean(e[3]), e[0]);
        }
    }

    @Test
    void apiRangeMatchesSdkInfo() throws Exception {
        String j = json();
        Matcher api = Pattern.compile("\"api\":(\\d+)").matcher(j);
        Matcher min = Pattern.compile("\"min_api\":(\\d+)").matcher(j);
        assertTrue(api.find() && min.find());
        assertEquals(SdkInfo.API_VERSION, Integer.parseInt(api.group(1)));
        assertEquals(SdkInfo.MIN_API_VERSION, Integer.parseInt(min.group(1)));
    }
}
