package mibsdk.adapter.hqc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class DsiConstantsTest {

    @Test
    void readsTheRuntimeValueReflectively() throws Exception {
        // Whatever the loaded class holds (byte-swapped in the host jxe2jar copy), never a javac-inlined value.
        int expected = Class.forName("org.dsi.ifc.base.DSIListener").getField("ATTRVALIDFLAG_VALID").getInt(null);
        assertEquals(expected, DsiConstants.get("org.dsi.ifc.base.DSIListener", "ATTRVALIDFLAG_VALID"));
        assertEquals(expected, DsiConstants.get("org.dsi.ifc.base.DSIListener", "ATTRVALIDFLAG_VALID"), "cached");
    }

    @Test
    void failureIsMinusOneAndLoggedOnce() {
        assertEquals(-1, DsiConstants.get("no.such.Clazz", "X_1"));
        assertEquals(-1, DsiConstants.get("org.dsi.ifc.base.DSIListener", "NO_SUCH_FIELD"));
        assertEquals(-1, DsiConstants.get("org.dsi.ifc.base.DSIListener", "NO_SUCH_FIELD"));
        assertEquals(-1, DsiConstants.get(null, null));
        int n = 0;
        List<String> lines = SdkGlobals.LOG.snapshot();
        for (String line : lines) {
            if (line.indexOf("NO_SUCH_FIELD") >= 0) {
                n++;
            }
        }
        assertEquals(1, n, "a failed lookup is cached and logged once");
        assertTrue(lines.toString().indexOf("no.such.Clazz") >= 0);
    }
}
