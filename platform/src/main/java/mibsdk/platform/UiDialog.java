package mibsdk.platform;

/**
 * A confirm or speller the runtime asks the adapter to show over a plugin's open page, in a {@link UiSnapshot}.
 * Immutable; its text is already clean. The adapter answers it once with {@link UiInput#DIALOG_DONE}
 * ({@code pageKey} = {@link #ownerPageKey}, {@code entryKey} = {@code String.valueOf(seq)}).
 */
public final class UiDialog {

    /** Yes/No: {@link UiInput#value} 1 for Yes, 0 for No or BACK. */
    public static final int CONFIRM = 0;
    /** The on-screen keyboard: {@link UiInput#text} is the typed text (BACK is done). */
    public static final int SPELLER = 1;

    private final long seq;
    private final int kind;
    private final String ownerPageKey;
    private final String title;
    private final String text;
    private final String yes;
    private final String no;
    private final int maxLen;
    private final boolean secret;

    public UiDialog(long seq, int kind, String ownerPageKey, String title, String text, String yes, String no,
                    int maxLen) {
        this(seq, kind, ownerPageKey, title, text, yes, no, maxLen, false);
    }

    public UiDialog(long seq, int kind, String ownerPageKey, String title, String text, String yes, String no,
                    int maxLen, boolean secret) {
        this.seq = seq;
        this.kind = kind;
        this.ownerPageKey = ownerPageKey;
        this.title = title == null ? "" : title;
        this.text = text == null ? "" : text;
        this.yes = yes;
        this.no = no;
        this.maxLen = maxLen;
        this.secret = secret;
    }

    /** Increases with every dialog the runtime opens; the answer names it. */
    public long seq() {
        return seq;
    }

    /** {@link #CONFIRM} or {@link #SPELLER}. */
    public int kind() {
        return kind;
    }

    /** The plugin page it shows over: always a page key, never a choice page's {@code #} key. */
    public String ownerPageKey() {
        return ownerPageKey;
    }

    public String title() {
        return title;
    }

    /** A confirm's text, at most 3 lines split at {@code '\n'}; or the speller's initial text. */
    public String text() {
        return text;
    }

    /** A confirm's Yes row; null for a speller. */
    public String yes() {
        return yes;
    }

    /** A confirm's No row; null for a speller. */
    public String no() {
        return no;
    }

    /** A speller's longest text; 0 for a confirm. */
    public int maxLen() {
        return maxLen;
    }

    /**
     * True when a speller's field must mask its characters (a password). The answer is still the real text.
     *
     * @since api 7
     */
    public boolean secret() {
        return secret;
    }
}
