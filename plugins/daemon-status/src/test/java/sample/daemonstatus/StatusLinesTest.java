package sample.daemonstatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class StatusLinesTest {

    @Test
    void parsesKeyValueAndIgnoresJunk() {
        Map<String, String> m = StatusLines.parse("v=1\r\ndaemon=carmqtt\njunk\n=nokey\n\nexlap_err=a=b c\nempty=\n");
        assertEquals("carmqtt", m.get("daemon"));
        assertEquals("a=b c", m.get("exlap_err"));
        assertEquals("", m.get("empty"));
        assertEquals(null, m.get("junk"));
        assertEquals(4, m.size(), m.toString());
    }

    @Test
    void unknownVersionIsUnknown() {
        assertTrue(StatusLines.parse("v=2\nstate=Running\n").isEmpty());
        assertTrue(StatusLines.parse("state=Running\n").isEmpty());
        assertTrue(StatusLines.parse(null).isEmpty());
    }
}
