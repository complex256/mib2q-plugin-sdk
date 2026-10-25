package mibsdk.spike.ui;

import de.audi.atip.hmi.HMIService;
import de.audi.atip.hmi.event.KeyEvent;
import de.audi.atip.hmi.event.RunnableEvent;
import de.audi.atip.hmi.event.WheelButtonEvent;
import de.audi.atip.hmi.model.AbstractModelBank;
import de.audi.atip.hmi.model.RangeModel;
import de.audi.atip.hmi.view.HMIView;
import de.audi.atip.hmi.view.Screen;
import de.audi.tghu.car.hmi.evohigh.FontFactory;
import de.esolutions.hmi.widgets.audi.base.InitializationContext;
import de.esolutions.hmi.widgets.audi.base.widgets.AbstractWidgetController;
import de.esolutions.hmi.widgets.audi.base.widgets.ModelStubController;
import de.esolutions.hmi.widgets.audi.evo.InstructionTextContoller;
import de.esolutions.hmi.widgets.audi.evo.ScreenWidgetEVO;
import de.esolutions.hmi.widgets.audi.evo.high.widgets.GlassplateContainerRendererHigh;
import de.esolutions.hmi.widgets.audi.evo.high.widgets.GlassplateRendererHigh;
import de.esolutions.hmi.widgets.audi.evo.high.widgets.LabelRendererHigh;
import de.esolutions.hmi.widgets.audi.evo.high.widgets.MirrorRendererHigh;
import de.esolutions.hmi.widgets.audi.evo.high.widgets.MirroredContainerController;
import de.esolutions.hmi.widgets.audi.evo.high.widgets.MultiLineLabelRendererHigh;
import de.esolutions.hmi.widgets.audi.evo.high.widgets.RotaryBigRendererHigh;
import de.esolutions.hmi.widgets.audi.evo.high.widgets.SpellerRendererEuropeHigh;
import de.esolutions.hmi.widgets.audi.evo.high.widgets.TouchRendererHigh;
import de.esolutions.hmi.widgets.audi.evo.high.widgets.factories.SpellerRendererHighFactory;
import de.esolutions.hmi.widgets.audi.evo.high.widgets.factories.TouchRendererHighFactory;
import de.esolutions.hmi.widgets.audi.evo.widgets.ContainerController;
import de.esolutions.hmi.widgets.audi.evo.widgets.GlassplateController;
import de.esolutions.hmi.widgets.audi.evo.widgets.LabelController;
import de.esolutions.hmi.widgets.audi.evo.widgets.RotaryController;
import de.esolutions.hmi.widgets.audi.evo.widgets.SpellerController;
import de.esolutions.hmi.widgets.audi.evo.widgets.TouchController;
import de.esolutions.hmi.widgets.audi.evo.widgets.factories.SpellerControllerFactory;
import de.esolutions.hmi.widgets.audi.evo.widgets.factories.TouchControllerFactory;
import de.esolutions.hmi.widgets.audi.evo.widgets.menu.ListController;

/**
 * Spike D: the second-level SDK screens, each a prebuilt Screen shown with FwHMI.showScreen like the page (no SMM
 * state). BACK on each re-shows the page (rebuilt; the page keeps the screen it first came from for its own BACK).
 * - 609501 slider: the tONESOUNDBASS recipe (ToneScreenBag1, screen 1000008): RotaryController +
 *   RotaryBigRendererHigh on RangeModel 602756 in a MirroredContainerController, min/max labels, glassplate.
 * - 609502 confirm: the cARPOPUPDRIVESELECTLIFTNA recipe (CarScreenBag1, screen 600138): InstructionTextContoller
 *   with a multi-line hint label and a menu holding a ListController on 602758 (rows Yes, No).
 * - 609503 speller (stretch): the cMOPTBLUETOOTHNAMEEDIT recipe (ConnectivityScreenBag1, screen 2500003): one
 *   TouchController input field with an inline SpellerController (openSpellerOnOtherScreen false) on the SDK
 *   SpellerModel 602759, the 5602/5606 model stubs.
 * Constants are the decoded (byte-swapped back) values of the stock bags.
 */
final class SpikeScreens {
    /** Slider fallback (command "slider-fwd"): the screen forwards unconsumed knob turns to the RangeModel. */
    static volatile boolean sliderForward;
    /** The last slider rotary built (for "slider-focus"). Touched on HMIEvent. */
    static volatile SliderRotary rotary;

    private SpikeScreens() {
    }

    static void postShow(final int id, final String why) {
        post(new Runnable() {
            public void run() {
                try {
                    SpikeFactory f = SpikeFactory.instance;
                    long t0 = System.currentTimeMillis();
                    Screen s;
                    if (id == SpikeIds.SLIDER_SCREEN_ID) {
                        s = slider(f, 0);
                    } else if (id == SpikeIds.CONFIRM_SCREEN_ID) {
                        s = confirm(f, 0);
                    } else {
                        s = speller(f, 0);
                    }
                    SpikePage.showBuilt(s, id);
                    SpikeLog.log("screen-show " + id + " (" + why + ") build+show=" + (System.currentTimeMillis() - t0)
                            + "ms thread=" + Thread.currentThread().getName());
                } catch (Throwable t) {
                    SpikeLog.log("screen-show " + id + " FAILED: " + t);
                }
            }
        });
    }

    /** Re-shows the page (rebuilt) without changing the page's own BACK target. */
    static void backToPage(String why) {
        SpikeLog.log("screen-back " + why + " -> page");
        SpikePage.postShowPage(false, why);
    }

    private static Runnable back(final String why) {
        return new Runnable() {
            public void run() {
                backToPage(why);
            }
        };
    }

    // ---- 609501 slider ------------------------------------------------------------------------------------------

    /** The slider's RotaryController: logs every key with its widget state (needs state 36 for RangeModelKeyHandler). */
    static final class SliderRotary extends RotaryController {
        public void keyPressed(KeyEvent e) {
            SpikeLog.log("slider-rotary keyPressed code=" + e.getKeyCode() + " state=" + widgetState + " has36="
                    + hasState(36));
            super.keyPressed(e);
        }

        public void keyTurned(WheelButtonEvent e) {
            SpikeLog.log("slider-rotary keyTurned code=" + e.getKeyCode() + " dir=" + e.getDirection() + " clicks="
                    + e.getClickCount() + " state=" + widgetState + " has36=" + hasState(36));
            super.keyTurned(e);
            SpikeLog.log("slider-rotary keyTurned consumed=" + e.isConsumed() + " value=" + getValue());
        }

        public void connected(InitializationContext c) {
            super.connected(c);
            SpikeLog.log("slider-rotary connected state=" + widgetState + " has36=" + hasState(36) + " model="
                    + (getModel() == null ? "null" : getModel().getClass().getName()));
        }
    }

    /** Screen with its own BACK, and the slider-fwd knob fallback. */
    static final class OwnScreen extends ScreenWidgetEVO {
        private final String name;

        OwnScreen(int id, String name) {
            super(id);
            this.name = name;
        }

        public void connected(InitializationContext c) {
            try {
                super.connected(c);
                SpikeLog.log(name + " connected id=" + getID());
            } catch (RuntimeException e) {
                java.io.StringWriter w = new java.io.StringWriter();
                e.printStackTrace(new java.io.PrintWriter(w));
                String t = w.toString();
                SpikeLog.log(name + " connect FAILED: " + (t.length() > 1500 ? t.substring(0, 1500) : t));
                throw e;
            }
        }

        public void keyPressed(KeyEvent e) {
            int code = e.getKeyCode();
            SpikeLog.log(name + "-screen key press code=" + code);
            if (code == SpikeIds.KEY_RETURN) {
                e.consume(true);
                post(back(name + "-back"));
                return;
            }
            super.keyPressed(e);
        }

        public void keyReleased(KeyEvent e) {
            if (e.getKeyCode() == SpikeIds.KEY_RETURN) {
                e.consume(true);
                return;
            }
            super.keyReleased(e);
        }

        public void keyTurned(WheelButtonEvent e) {
            super.keyTurned(e);
            if (!e.isConsumed() && sliderForward && SpikeIds.SLIDER_SCREEN_ID == getID()) {
                RangeModel r = (RangeModel) SpikeBank.instance.spikeModel(SpikeIds.RANGE_MODEL);
                int n = e.getClickCount();
                SpikeLog.log(name + "-screen keyTurned unconsumed, forwarding dir=" + e.getDirection() + " n=" + n);
                if (e.getDirection() == 0) {
                    r.increment(n, 0);
                } else {
                    r.decrement(n, 0);
                }
                e.consume(false);
            } else if (!e.isConsumed()) {
                SpikeLog.log(name + "-screen keyTurned unconsumed dir=" + e.getDirection());
            }
        }
    }

    static Screen slider(SpikeFactory f, int t) {
        AbstractWidgetController ref0 = f.getRefWidget(t, 0, f);
        GlassplateController glass = new GlassplateController();
        glass.setRenderer(new GlassplateRendererHigh(glass));
        glass.setBounds(59, 77, 946, 324);
        glass.setCoordinateSets(new int[] {1, 59, 77, 946, 324, 59, 77, 946, 324});
        SliderRotary rot = new SliderRotary();
        rot.setRenderer(new RotaryBigRendererHigh(rot));
        rot.setModelID(SpikeIds.RANGE_MODEL);
        rot.setBounds(397, 122, 100, 100);
        LabelController max = new LabelController();
        LabelRendererHigh maxR = new LabelRendererHigh(max);
        max.setRenderer(maxR);
        maxR.setFonts(FontFactory.getFonts(2, t, f.framework()));
        max.setTextIds(new int[] {SpikeIds.TEXT_SLIDER_MAX});
        max.setBounds(621, 323, 202, 30);
        max.setColorIndices(new int[] {1, 0, 4, 0});
        LabelController min = new LabelController();
        LabelRendererHigh minR = new LabelRendererHigh(min);
        min.setRenderer(minR);
        minR.setFonts(FontFactory.getFonts(2, t, f.framework()));
        min.setTextIds(new int[] {SpikeIds.TEXT_SLIDER_MIN});
        min.setBounds(226, 323, 202, 30);
        min.setColorIndices(new int[] {1, 0, 4, 0});
        minR.setAlignment(3, 4);
        MirroredContainerController mirror = new MirroredContainerController();
        mirror.setRenderer(new MirrorRendererHigh(mirror));
        mirror.setBounds(0, 0, 1024, 480);
        mirror.add(rot);
        mirror.add(max);
        mirror.add(min);
        ContainerController box = new ContainerController();
        box.setRenderer(new GlassplateContainerRendererHigh(box));
        box.setBounds(0, 0, 0, 0);
        box.add(glass);
        box.add(mirror);
        rotary = rot;
        ScreenWidgetEVO s = shellOwn(f, SpikeIds.SLIDER_SCREEN_ID, "slider", t, ref0, box,
                SpikeIds.TEXT_SLIDER_TITLE, 3, new int[] {SpikeIds.RANGE_MODEL}, new HMIView[][] {{rot}});
        SpikeLog.log("slider built");
        return s;
    }

    // ---- 609502 confirm -----------------------------------------------------------------------------------------

    static Screen confirm(SpikeFactory f, int t) {
        AbstractWidgetController ref0 = f.getRefWidget(t, 0, f);
        ListController list = new ListController();
        list.setModelID(SpikeIds.CONFIRM_LIST_MODEL);
        list.setBounds(0, 0, 0, 0);
        list.setGlassplateInsetsBottom(new int[0]);
        list.setGlassplateInsetsTop(new int[0]);
        list.setIdColumn(2);
        list.setRecordSetColumn(2);
        list.setNoFocusAreasBottom(new int[0]);
        list.setNoFocusAreasTop(new int[0]);
        list.setItemFactory(new SpikePage.Items());
        SpikePage.PageMenu menu = new SpikePage.PageMenu("confirm", back("confirm-back"));
        SpikePage.dressMenu(menu, f, t, 25);
        menu.add(list);
        LabelController hint = new LabelController();
        hint.setRenderer(new MultiLineLabelRendererHigh(hint));
        hint.setTextIds(new int[] {SpikeIds.TEXT_CONFIRM_TEXT});
        hint.setBounds(0, 0, 0, 0);
        hint.setTextOrientationHints(new int[] {6});
        InstructionTextContoller it = new InstructionTextContoller();
        it.setBounds(0, 0, 100, 100);
        it.setColorIndices(new int[] {1, 0, 4, 0});
        it.setOptions(menu);
        it.addHintText(hint);
        ScreenWidgetEVO s = shellOwn(f, SpikeIds.CONFIRM_SCREEN_ID, "confirm", t, ref0, it,
                SpikeIds.TEXT_CONFIRM_TITLE, 1, new int[] {SpikeIds.CONFIRM_LIST_MODEL}, new HMIView[][] {{list}});
        SpikeLog.log("confirm built");
        return s;
    }

    // ---- 609503 speller (stretch) -------------------------------------------------------------------------------

    static Screen speller(SpikeFactory f, int t) {
        AbstractWidgetController ref0 = f.getRefWidget(t, 0, f);
        de.audi.atip.base.IFrameworkAccess fw = f.framework();
        SpellerController sp = SpellerControllerFactory.create();
        SpellerRendererEuropeHigh spR = SpellerRendererHighFactory.create(sp);
        sp.setRenderer(spR);
        sp.setBitmaps(new int[] {421, 84, 85, 86, 87, 88, 89, 424, 425, 90, 91, 92, 93, 94, 95, 96, 97, 98, 99, 187,
            188, 189, 190, 473, 473, 475, 476, 475, 475, 479, 480, 479, 479, 486, 487, 488, 489, 490, 491, 492, 493,
            494, 495, 496, 497, 858, 859, 961, 962});
        spR.setFonts(de.audi.tghu.connectivity.hmi.evohigh.FontFactory.getFonts(5, t, fw));
        sp.setColorIndices(new int[] {2, 0, 4, 0});
        ModelStubController stubA = new ModelStubController();
        stubA.setModelID(5602);
        stubA.setBounds(0, 0, 100, 100);
        ModelStubController stubB = new ModelStubController();
        stubB.setModelID(5606);
        stubB.setBounds(0, 0, 100, 100);
        TouchController tc = TouchControllerFactory.create();
        TouchRendererHigh tcR = TouchRendererHighFactory.create(tc);
        tc.setRenderer(tcR);
        tc.setBitmaps(new int[] {483, 484, 857});
        tcR.setFonts(de.audi.tghu.connectivity.hmi.evohigh.FontFactory.getFonts(4, t, fw));
        tc.setModelID(SpikeIds.SPELLER_MODEL);
        tc.setInfoTextIDs(new int[] {SpikeIds.TEXT_SPELLER_INFO});
        tc.setColorIndices(new int[] {2, 5, 4, 3});
        tc.setCursorOffsetBottom(1);
        tc.setCursorOffsetTop(-1);
        tc.setGlassplateInsetsBottom(17);
        tc.setGlassplateInsetsTop(5);
        tc.setPreferredHeight(60);
        tc.setPrefilledInputField(true);
        tc.setTouchControllerScreenLevel(2);
        tc.setWidgetID(1);
        tc.setOpenSpellerOnOtherScreen(false);
        tc.setSpeller(sp);
        tc.add(stubA, 16);
        tc.add(stubB, 8);
        SpikePage.PageMenu menu = new SpikePage.PageMenu("speller", back("speller-back"));
        SpikePage.dressMenu(menu, f, t, 25);
        menu.add(tc);
        LabelController hint = new LabelController();
        hint.setRenderer(new MultiLineLabelRendererHigh(hint));
        hint.setTextIds(new int[] {SpikeIds.TEXT_SPELLER_HINT});
        InstructionTextContoller it = new InstructionTextContoller();
        it.setBounds(0, 0, 100, 100);
        it.setColorIndices(new int[] {1, 0, 4, 0});
        it.setOptions(menu);
        it.addHintText(hint);
        ScreenWidgetEVO s = shellOwn(f, SpikeIds.SPELLER_SCREEN_ID, "speller", t, ref0, it,
                SpikeIds.TEXT_SPELLER_TITLE, 3, new int[] {5602, 5606, SpikeIds.SPELLER_MODEL},
                new HMIView[][] {{stubA}, {stubB}, {tc}});
        SpikeLog.log("speller built");
        return s;
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    /** SpikePage.shell with an OwnScreen (BACK = page) instead of the page's PageScreen. */
    private static ScreenWidgetEVO shellOwn(SpikeFactory f, int id, String name, int t, AbstractWidgetController ref0,
            AbstractWidgetController body, int title, int smallStage, int[] ids, HMIView[][] views) {
        return SpikePage.shell(new OwnScreen(id, name), f, id, t, ref0, body, title, 0.4f, smallStage, ids, views);
    }

    private static void post(Runnable r) {
        HMIService svc = AbstractModelBank.getHMIservice();
        svc.getEventDispatcher().postEvent(new RunnableEvent(false, r));
    }
}
