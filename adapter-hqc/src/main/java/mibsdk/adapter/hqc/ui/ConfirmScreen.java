package mibsdk.adapter.hqc.ui;

import de.audi.atip.hmi.model.ListModel;
import de.audi.atip.hmi.view.HMIView;
import de.esolutions.hmi.widgets.audi.base.widgets.AbstractWidgetController;
import de.esolutions.hmi.widgets.audi.evo.InstructionTextContoller;
import de.esolutions.hmi.widgets.audi.evo.high.widgets.MultiLineLabelRendererHigh;
import de.esolutions.hmi.widgets.audi.evo.widgets.LabelController;
import de.esolutions.hmi.widgets.audi.evo.widgets.menu.ListController;
import de.esolutions.hmi.widgets.audi.evo.widgets.menu.MenuController;
import mibsdk.platform.UiDialog;

/**
 * The confirm screen {@link HqcUiIds#CONFIRM_SCREEN_ID} (spike D, D7; the {@code cARPOPUPDRIVESELECTLIFTNA} recipe of
 * {@code SpikeScreens.confirm}, shown as a non-popup screen like the page): an {@code InstructionTextContoller} whose
 * hint is a wrapping multi-line label on factory text {@link HqcUiIds#TEXT_CONFIRM_TEXT} (F9: served by
 * {@link InputTextIds} from the dialog's text, its lines split at {@code '\n'}), and whose options are a dressed menu
 * holding a {@code ListController} on {@link HqcUiIds#CONFIRM_LIST_MODEL} with the page's item factory: the Yes and No
 * rows. Titled {@link HqcUiIds#TEXT_CONFIRM_TITLE}. Row 0 is Yes ({@link PageScreens#confirmSelected}), row 1 No, BACK
 * No ({@link PageScreens#subBack}). Built on HMIEvent in the applier's job; the rows are written there too (F15).
 */
final class ConfirmScreen {

    private ConfirmScreen() {
    }

    /** The two rows: Yes then No, ACTION rows, enabled. */
    static PageRow[] rows(UiDialog d) {
        return new PageRow[] {
            new PageRow(d.yes(), 0, PageScreens.ROW_ACTION, true, null),
            new PageRow(d.no(), 0, PageScreens.ROW_ACTION, true, null)};
    }

    /** Writes {@code d}'s rows to the confirm ListModel {@code m} in place, then trims it. */
    static void writeRows(ListModel m, UiDialog d) {
        if (m == null) {
            throw new IllegalStateException("no confirm list model");
        }
        PageWidgets.write(m, rows(d));
    }

    static SliderScreen.Screen build(SdkCarScreenFactory f, int t, PageScreens owner) {
        AbstractWidgetController ref0 = f.getRefWidget(t, 0, f);
        ListController list = PageWidgets.list(HqcUiIds.CONFIRM_LIST_MODEL, PageLayout.EMPTY);
        MenuController menu = PageWidgets.dressMenu(new MenuController(), f, t, HqcUiIds.DIALOG_MENU_RIGHT_OFFSET);
        menu.add(list);
        LabelController hint = new LabelController();
        MultiLineLabelRendererHigh hr = new MultiLineLabelRendererHigh(hint);
        hint.setRenderer(hr);
        hr.setAutoWrap(HqcUiIds.CONFIRM_AUTOWRAP);
        hint.setTextIds(new int[] {HqcUiIds.TEXT_CONFIRM_TEXT});
        hint.setBounds(0, 0, 0, 0);
        hint.setTextOrientationHints((int[]) HqcUiIds.DIALOG_HINT_ORIENTATION.clone());
        InstructionTextContoller it = instruction(menu, hint);
        SliderScreen.Screen s = new SliderScreen.Screen(HqcUiIds.CONFIRM_SCREEN_ID, owner);
        PageWidgets.shell(s, f, t, ref0, it, HqcUiIds.TEXT_CONFIRM_TITLE, HqcUiIds.CONFIRM_SMALL_STAGE,
                new int[] {HqcUiIds.CONFIRM_LIST_MODEL}, new HMIView[][] {{list}});
        return s;
    }

    /** The dialogs' body: an instruction text (0, 0, 100, 100) with {@code menu} as its options and {@code hint}. */
    static InstructionTextContoller instruction(MenuController menu, LabelController hint) {
        InstructionTextContoller it = new InstructionTextContoller();
        it.setBounds(0, 0, 100, 100);
        it.setColorIndices((int[]) HqcUiIds.DIALOG_TEXT_COLOURS.clone());
        it.setOptions(menu);
        it.addHintText(hint);
        return it;
    }
}
