package mibsdk.adapter.hqc.ui;

/**
 * The open speller screen's text and its one "done" (spike D, D9: no OK key is reachable, so BACK is done). The band
 * opening and closing ({@link HqcUiIds#SPELLER_OPENED}/{@link HqcUiIds#SPELLER_CLOSED}) never finishes it: the
 * typed text is delivered once, on BACK or when the screen leaves without it (a stock screen). HMIEvent only;
 * stock-free.
 */
final class SpellerSession {

    private final long seq;
    private final String initial;
    private String lastChanged;
    private boolean done;

    SpellerSession(long seq, String initial) {
        this.seq = seq;
        this.initial = initial;
    }

    /** The dialog this session edits. */
    long seq() {
        return seq;
    }

    /** The SpellerListener's {@code textChanged}: the field's text after a key. */
    void onTextChanged(String t) {
        if (t != null) {
            lastChanged = t;
        }
    }

    /** The SpellerListener's {@code commandPressed}: the band opened or closed; neither finishes the speller. */
    void onCommand(int c) {
        // F21: whether a BACK the band consumes reaches the screen is a bench record; nothing to do here
    }

    /** The text to deliver: {@code modelText} (the model's) if non-null, else the last change, else the initial. */
    String doneText(String modelText) {
        if (modelText != null) {
            return modelText;
        }
        return lastChanged != null ? lastChanged : initial;
    }

    /** True only the first time: the one done. */
    boolean takeDone() {
        if (done) {
            return false;
        }
        done = true;
        return true;
    }
}
