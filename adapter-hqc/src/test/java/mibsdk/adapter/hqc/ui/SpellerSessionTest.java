package mibsdk.adapter.hqc.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The open speller's text and its one "done" (D9: BACK is done; the band's open/close never finishes it). */
class SpellerSessionTest {

    @Test
    void backUsesModelText() {
        SpellerSession s = new SpellerSession(4, "Bob");
        s.onTextChanged("Bo");
        assertEquals("Bobby", s.doneText("Bobby"), "the model holds what the GUI wrote last");
        assertEquals(4, s.seq());
    }

    @Test
    void backWithoutModelTextUsesLastChanged() {
        SpellerSession s = new SpellerSession(4, "Bob");
        s.onTextChanged("B");
        s.onTextChanged("BC");
        assertEquals("BC", s.doneText(null));
        s.onTextChanged("");
        assertEquals("", s.doneText(null), "everything deleted is an empty text, not the initial one");
    }

    @Test
    void noTypingGivesInitial() {
        assertEquals("Bob", new SpellerSession(4, "Bob").doneText(null));
    }

    @Test
    void bandCommandsNeverFinish() {
        SpellerSession s = new SpellerSession(4, "");
        s.onCommand(HqcUiIds.SPELLER_OPENED);
        s.onTextChanged("A");
        s.onCommand(HqcUiIds.SPELLER_CLOSED);
        s.onCommand(-1);
        assertTrue(s.takeDone(), "still open: BACK is the one done");
        assertEquals("A", s.doneText(null));
    }

    /** BACK, then the screen leaving (a disconnect): one done. */
    @Test
    void doneOnlyOnce() {
        SpellerSession s = new SpellerSession(4, "Bob");
        assertTrue(s.takeDone(), "BACK");
        assertFalse(s.takeDone(), "the disconnect after it");
        assertFalse(s.takeDone());
    }
}
