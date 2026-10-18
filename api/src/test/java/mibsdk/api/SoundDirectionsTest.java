package mibsdk.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class SoundDirectionsTest {

    @Test
    void withRearReplacesOnlyRear() {
        assertEquals(new SoundDirections(true, false, false, false),
                new SoundDirections(true, true, false, false).withRear(false));
    }

    @Test
    void equalValuesHaveEqualHashCodes() {
        SoundDirections a = new SoundDirections(true, false, true, false);
        SoundDirections b = new SoundDirections(true, false, true, false);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, new SoundDirections(true, false, true, true));
    }

    @Test
    void toStringIsStable() {
        assertEquals("SoundDirections{front=true, rear=false, left=false, right=false}",
                new SoundDirections(true, false, false, false).toString());
    }
}
