package mibsdk.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The count limits are public, so a plugin can plan for them instead of discovering them on the unit. */
class UiLimitsTest {

    @Test
    void constants() {
        assertEquals(8, Ui.MAX_PAGES);
        assertEquals(2, Ui.MAX_CAR_ROWS);
        assertEquals(1, Ui.MAX_STATUS_ITEMS);
    }
}
