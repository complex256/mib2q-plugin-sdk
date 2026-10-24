package mibsdk.platform;

/** One text format for an SDK page entry, shared by the fake UI and the HMI adapter. */
public final class UiPageText {

    private UiPageText() {
    }

    /**
     * INFO: text; ITEM: "label" or "label: value"; TOGGLE: "[on] label"/"[off] label"; LINK: "label >" or
     * "label: value >" (the Plugins menu's state words).
     */
    public static String line(UiEntry e) {
        String label = e.label() == null ? "" : e.label();
        String value = e.value();
        switch (e.kind()) {
            case UiEntry.TOGGLE:
                return (e.on() ? "[on] " : "[off] ") + label;
            case UiEntry.LINK:
                return (value == null || value.length() == 0 ? label : label + ": " + value) + " >";
            case UiEntry.INFO:
                return label;
            default:
                return value == null || value.length() == 0 ? label : label + ": " + value;
        }
    }
}
