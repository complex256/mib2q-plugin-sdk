package mibsdk.platform;

/** One text format for an SDK page entry, shared by the fake UI and the HMI adapter. */
public final class UiPageText {

    private UiPageText() {
    }

    /**
     * The whole entry as one text line (the fake UI's format). INFO: text; ITEM: "label" or "label: value"; TOGGLE:
     * "[on] label"/"[off] label"; LINK: "label >" or "label: value >" (the Plugins menu's state words); CHOICE:
     * "label: option >"; DROPDOWN: "label: option v"; SLIDER: "label: value [min..max]"; PROGRESS: "label: n%";
     * RADIO: "(*) option"/"( ) option". An item icon's tag ({@code "[png:<key>] "}, {@code "[stock:<name>] "}) comes
     * first and a disabled entry ends with {@code " [disabled]"}.
     */
    public static String line(UiEntry e) {
        String text = body(e);
        if (e.icon() != null) {
            UiIcon i = e.icon();
            text = (i.kind() == UiIcon.FILE ? "[png:" + i.key() : "[stock:" + i.name()) + "] " + text;
        }
        return e.enabled() ? text : text + " [disabled]";
    }

    private static String body(UiEntry e) {
        String label = e.label() == null ? "" : e.label();
        String value = e.value();
        switch (e.kind()) {
            case UiEntry.TOGGLE:
                return (e.on() ? "[on] " : "[off] ") + label;
            case UiEntry.LINK:
                return labelled(label, value) + " >";
            case UiEntry.INFO:
                return label;
            case UiEntry.CHOICE:
                return labelled(label, option(e)) + " >";
            case UiEntry.DROPDOWN:
                return labelled(label, option(e)) + " v";
            case UiEntry.SLIDER:
                return labelled(label, String.valueOf(e.number())) + " [" + e.min() + ".." + e.max() + "]";
            case UiEntry.PROGRESS:
                return labelled(label, e.number() + "%");
            case UiEntry.RADIO:
                return (e.on() ? "(*) " : "( ) ") + label;
            default:
                return labelled(label, value);
        }
    }

    /**
     * The HMI's label cell: the label alone (no {@code [on]}/{@code [off]}, no icon tag). A LINK keeps {@code " >"}
     * only when {@link #valueText} is null, so there is never a second chevron.
     */
    public static String label(UiEntry e) {
        String label = e.label() == null ? "" : e.label();
        return e.kind() == UiEntry.LINK && valueText(e) == null ? label + " >" : label;
    }

    /** The HMI's value cell: an ITEM's value, a CHOICE's option, a LINK's value + {@code " >"}; else null. */
    public static String valueText(UiEntry e) {
        String value = e.value();
        boolean has = value != null && value.length() > 0;
        switch (e.kind()) {
            case UiEntry.ITEM:
                return has ? value : null;
            case UiEntry.CHOICE:
                return e.option(e.number());
            case UiEntry.LINK:
                return has ? value + " >" : null;
            default:
                return null;
        }
    }

    private static String option(UiEntry e) {
        String o = e.option(e.number());
        return o == null ? "" : o;
    }

    private static String labelled(String label, String value) {
        return value == null || value.length() == 0 ? label : label + ": " + value;
    }
}
