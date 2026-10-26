package mibsdk.adapter.hqc.ui;

import mibsdk.platform.UiDialog;
import mibsdk.platform.UiEntry;

/**
 * The input texts {@code 609040..609082} ({@link HqcUiIds#TEXT_INPUT_BASE} .. {@link HqcUiIds#TEXT_SPELLER_HINT}),
 * served by the Car factory's {@code getText} and {@code getAltText} from the volatile state they are drawn for: the
 * shown page's typed rows (labels at {@code 609040 + 9s}, which double as the slider screen's title, and options at
 * {@code +1 .. +8}), the open slider's min and max ({@link HqcUiIds#TEXT_SLIDER_MIN}/{@link HqcUiIds#TEXT_SLIDER_MAX}:
 * a centred slider's positive end carries its {@code "+"}), and the open dialog's title and text. Every id in the range
 * is ours: with nothing to serve it is {@code ""}, never the stock text. Stock thread (HMIEvent): no lock, no state,
 * never throws for any id.
 */
final class InputTextIds {

    private InputTextIds() {
    }

    /** The text of input text id {@code id}, or null if {@code id} is not one. Any argument may be null. */
    static String textFor(int id, PageScreens.Shown shown, SliderSession slider, UiDialog dialog) {
        if (id < HqcUiIds.TEXT_INPUT_BASE || id > HqcUiIds.TEXT_SPELLER_HINT) {
            return null;
        }
        if (id < HqcUiIds.TEXT_SLIDER_MIN) {
            int k = id - HqcUiIds.TEXT_INPUT_BASE;
            PageLayout.TypedRow row = row(shown, k / HqcUiIds.TEXT_TYPED_STRIDE);
            int part = k % HqcUiIds.TEXT_TYPED_STRIDE;
            if (row == null) {
                return "";
            }
            if (part == 0) {
                return row.label;
            }
            String o = row.option(part - 1);
            return o == null ? "" : o;
        }
        switch (id) {
            case HqcUiIds.TEXT_SLIDER_MIN:
            case HqcUiIds.TEXT_SLIDER_MAX: {
                PageLayout.TypedRow row = slider == null ? null : row(shown, slider.slot());
                if (row == null || row.kind != UiEntry.SLIDER) {
                    return "";
                }
                return number(id == HqcUiIds.TEXT_SLIDER_MIN ? row.min : row.max(), row.centred);
            }
            case HqcUiIds.TEXT_CONFIRM_TITLE:
                return dialog != null && dialog.kind() == UiDialog.CONFIRM ? dialog.title() : "";
            case HqcUiIds.TEXT_CONFIRM_TEXT:
                return dialog != null && dialog.kind() == UiDialog.CONFIRM ? dialog.text() : "";
            case HqcUiIds.TEXT_SPELLER_TITLE:
                return dialog != null && dialog.kind() == UiDialog.SPELLER ? dialog.title() : "";
            default: // the speller's info and hint: Task 6
                return "";
        }
    }

    private static PageLayout.TypedRow row(PageScreens.Shown shown, int slot) {
        return shown == null ? null : shown.layout.typedAt(slot);
    }

    /** {@code v} as the slider screen labels it: a centred slider's positive end as {@code "+v"}. */
    static String number(int v, boolean centred) {
        return centred && v > 0 ? "+" + v : String.valueOf(v);
    }
}
