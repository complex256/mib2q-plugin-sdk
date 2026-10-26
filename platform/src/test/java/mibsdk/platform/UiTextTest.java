package mibsdk.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class UiTextTest {

    @Test
    void cleansAndTruncates() {
        assertEquals("", UiText.clean(null, 5));
        assertEquals("a b c", UiText.clean("a\nb\tc", 10));
        StringBuffer big = new StringBuffer();
        for (int i = 0; i < 10240; i++) {
            big.append('x');
        }
        String cut = UiText.clean(big.toString(), 40);
        assertEquals(40, cut.length());
        assertTrue(cut.endsWith("..."));
        assertEquals("?x", UiText.clean("😀x", 10));
        assertEquals("ab", UiText.clean("a‏b", 10));
        assertEquals("é", UiText.clean("é", 10));
        assertEquals("?", UiText.clean("中", 10));
        assertEquals("abc", UiText.clean("abcdef", 3));
    }

    @Test
    void collapsesTrimsAndHandlesLoneSurrogates() {
        assertEquals("a b", UiText.clean("  a \u0085\u007F  b  ", 10));
        assertEquals("a?b?", UiText.clean("a\uD800b\uDC00", 10));
        assertEquals("ab", UiText.clean("﻿a‮b⁠", 10));
        assertEquals("ab...", UiText.clean("abcdefgh", 5));
    }

    /** An editable value (the speller's): cleaned the same way, but cut hard, with no "...". */
    @Test
    void cutIsCleanWithoutEllipsis() {
        assertEquals("", UiText.cut(null, 5));
        assertEquals("", UiText.cut("abc", 0));
        assertEquals("abcde", UiText.cut("abcdefgh", 5));
        assertEquals("ab cd", UiText.cut("  ab \t cd ef", 6), "no trailing space after the cut");
        assertEquals("a b c", UiText.cut("a\nb\tc", 10));
        assertEquals("?x", UiText.cut("\uD83D\uDE00x", 10));
        assertEquals("ab", UiText.cut("\uFEFFa\u202Eb", 10));
    }
}
