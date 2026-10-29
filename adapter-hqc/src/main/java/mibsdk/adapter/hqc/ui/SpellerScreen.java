package mibsdk.adapter.hqc.ui;

import de.audi.atip.base.IFrameworkAccess;
import de.audi.atip.hmi.model.SpellerModel;
import de.audi.atip.hmi.view.HMIView;
import de.audi.tghu.connectivity.hmi.evohigh.FontFactory;
import de.esolutions.hmi.widgets.audi.base.widgets.AbstractWidgetController;
import de.esolutions.hmi.widgets.audi.base.widgets.ModelStubController;
import de.esolutions.hmi.widgets.audi.evo.InstructionTextContoller;
import de.esolutions.hmi.widgets.audi.evo.high.widgets.MultiLineLabelRendererHigh;
import de.esolutions.hmi.widgets.audi.evo.high.widgets.SpellerRendererEuropeHigh;
import de.esolutions.hmi.widgets.audi.evo.high.widgets.TouchRendererHigh;
import de.esolutions.hmi.widgets.audi.evo.high.widgets.factories.SpellerRendererHighFactory;
import de.esolutions.hmi.widgets.audi.evo.high.widgets.factories.TouchRendererHighFactory;
import de.esolutions.hmi.widgets.audi.evo.widgets.LabelController;
import de.esolutions.hmi.widgets.audi.evo.widgets.SpellerController;
import de.esolutions.hmi.widgets.audi.evo.widgets.TouchController;
import de.esolutions.hmi.widgets.audi.evo.widgets.factories.SpellerControllerFactory;
import de.esolutions.hmi.widgets.audi.evo.widgets.factories.TouchControllerFactory;
import mibsdk.adapter.hqc.DsiConstants;
import de.esolutions.hmi.widgets.audi.evo.widgets.menu.MenuController;
import mibsdk.platform.UiDialog;

/**
 * The experimental speller screen {@link HqcUiIds#SPELLER_SCREEN_ID} (spike D, D9; the {@code cMOPTBLUETOOTHNAMEEDIT}
 * recipe of {@code SpikeScreens.speller}, C15): in a dressed menu inside an instruction text, one input field
 * ({@code TouchController}) on the SDK SpellerModel {@link HqcUiIds#SPELLER_MODEL} with an inline band
 * ({@code SpellerController}, {@code setOpenSpellerOnOtherScreen(false)}) and the Connectivity model stubs
 * {@link HqcUiIds#SPELLER_STUB_A}/{@link HqcUiIds#SPELLER_STUB_B}; info text {@link HqcUiIds#TEXT_SPELLER_INFO}, hint
 * {@link HqcUiIds#TEXT_SPELLER_HINT}, title {@link HqcUiIds#TEXT_SPELLER_TITLE}. No OK key is reachable: BACK is done
 * ({@link PageScreens#subBack}, with the model's text). The model's max length and initial text are written here, in
 * the applier's job, before the screen connects (F15). Built on HMIEvent.
 */
final class SpellerScreen {

    private SpellerScreen() {
    }

    static SliderScreen.Screen build(SdkCarScreenFactory f, int t, SpellerModel model, UiDialog d,
                                     PageScreens owner) {
        if (model == null) {
            throw new IllegalStateException("no speller model");
        }
        model.setMaxLength(d.maxLen());
        model.setText(d.text());
        AbstractWidgetController ref0 = f.getRefWidget(t, 0, f);
        IFrameworkAccess fw = f.framework();
        SpellerController band = SpellerControllerFactory.create();
        SpellerRendererEuropeHigh bandR = SpellerRendererHighFactory.create(band);
        band.setRenderer(bandR);
        band.setBitmaps((int[]) HqcUiIds.SPELLER_BITMAPS.clone());
        bandR.setFonts(FontFactory.getFonts(HqcUiIds.SPELLER_FONTS, t, fw));
        band.setColorIndices((int[]) HqcUiIds.SPELLER_COLOURS.clone());
        ModelStubController stubA = stub(HqcUiIds.SPELLER_STUB_A);
        ModelStubController stubB = stub(HqcUiIds.SPELLER_STUB_B);
        TouchController field = TouchControllerFactory.create();
        TouchRendererHigh fieldR = TouchRendererHighFactory.create(field);
        field.setRenderer(fieldR);
        field.setBitmaps((int[]) HqcUiIds.SPELLER_FIELD_BITMAPS.clone());
        fieldR.setFonts(FontFactory.getFonts(HqcUiIds.SPELLER_FIELD_FONTS, t, fw));
        field.setModelID(HqcUiIds.SPELLER_MODEL);
        field.setInfoTextIDs(new int[] {HqcUiIds.TEXT_SPELLER_INFO});
        field.setColorIndices((int[]) HqcUiIds.SPELLER_FIELD_COLOURS.clone());
        field.setCursorOffsetBottom(HqcUiIds.SPELLER_CURSOR_OFFSET_BOTTOM);
        field.setCursorOffsetTop(HqcUiIds.SPELLER_CURSOR_OFFSET_TOP);
        field.setGlassplateInsetsBottom(HqcUiIds.SPELLER_INSET_BOTTOM);
        field.setGlassplateInsetsTop(HqcUiIds.SPELLER_INSET_TOP);
        field.setPreferredHeight(HqcUiIds.SPELLER_FIELD_HEIGHT);
        field.setPrefilledInputField(true);
        field.setTouchControllerScreenLevel(HqcUiIds.SPELLER_SCREEN_LEVEL);
        field.setWidgetID(HqcUiIds.SPELLER_WIDGET_ID);
        field.setOpenSpellerOnOtherScreen(false);
        if (d.secret()) {
            // the stock Wi-Fi password field: masks its characters, and the typed text is still delivered. The
            // constant is read at run time: the jxe2jar jar's constant is byte-swapped and javac would inline it.
            int mode = DsiConstants.get("de.esolutions.hmi.widgets.audi.evo.widgets.TouchController",
                    "MODE_PASSWORD_INPUT_WLAN_CLIENT");
            if (mode >= 0) {
                field.setTouchpadMode(mode);
            }
        }
        field.setSpeller(band);
        field.add(stubA, HqcUiIds.SPELLER_STUB_A_LAYER);
        field.add(stubB, HqcUiIds.SPELLER_STUB_B_LAYER);
        MenuController menu = PageWidgets.dressMenu(new MenuController(), f, t, HqcUiIds.DIALOG_MENU_RIGHT_OFFSET);
        menu.add(field);
        LabelController hint = new LabelController();
        hint.setRenderer(new MultiLineLabelRendererHigh(hint));
        hint.setTextIds(new int[] {HqcUiIds.TEXT_SPELLER_HINT});
        InstructionTextContoller it = ConfirmScreen.instruction(menu, hint);
        SliderScreen.Screen s = new SliderScreen.Screen(HqcUiIds.SPELLER_SCREEN_ID, owner);
        PageWidgets.shell(s, f, t, ref0, it, HqcUiIds.TEXT_SPELLER_TITLE, HqcUiIds.SPELLER_SMALL_STAGE,
                new int[] {HqcUiIds.SPELLER_STUB_A, HqcUiIds.SPELLER_STUB_B, HqcUiIds.SPELLER_MODEL},
                new HMIView[][] {{stubA}, {stubB}, {field}});
        return s;
    }

    private static ModelStubController stub(int model) {
        ModelStubController s = new ModelStubController();
        s.setModelID(model);
        s.setBounds(0, 0, 100, 100);
        return s;
    }
}
