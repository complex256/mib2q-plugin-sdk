package mibsdk.adapter.hqc.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The slider screen's knob: turns accumulate from the last requested index, clamped; a new plugin value resets it. */
class SliderSessionTest {

    /** -6..+6 step 1 (12 steps) at 0: index 6. */
    private static SliderSession bass() {
        return new SliderSession(-6, 1, 12, 0);
    }

    @Test
    void accumulatesFromLastRequest() {
        SliderSession s = bass();
        assertEquals(6, s.index());
        assertEquals(2, s.onTurn(2));
        assertEquals(5, s.onTurn(3), "from the requested 2, not the plugin's 0");
        assertEquals(11, s.index());
        assertEquals(4, s.onTurn(-1));
    }

    @Test
    void clampsAtBothEnds() {
        SliderSession s = bass();
        assertEquals(6, s.onTurn(20));
        assertEquals(12, s.index());
        assertEquals(6, s.onTurn(1), "stays at the end");
        assertEquals(-6, s.onTurn(-30));
        assertEquals(0, s.index());
        assertEquals(-5, s.onTurn(1), "F18: reversing at the end moves at once (the session never lags)");
    }

    @Test
    void modelResetsIndex() {
        SliderSession s = bass();
        s.onTurn(4); // requested 4
        s.onModel(1); // the plugin settled on 1
        assertEquals(7, s.index());
        assertEquals(2, s.onTurn(1));
        s.onModel(1); // the same value again: no reset
        assertEquals(8, s.index());
        s.onModel(99); // out of range: clamped
        assertEquals(12, s.index());
    }

    @Test
    void stepUnitsMapToValues() {
        SliderSession s = new SliderSession(0, 2, 5, 4);
        assertEquals(2, s.index());
        assertEquals(6, s.onTurn(1));
        assertEquals(10, s.onTurn(5));
        assertEquals(0, s.onTurn(-9));
        assertEquals(-1, s.slot(), "no typed slot");
        assertEquals(3, new SliderSession(0, 2, 5, 4, 3).slot());
    }
}
