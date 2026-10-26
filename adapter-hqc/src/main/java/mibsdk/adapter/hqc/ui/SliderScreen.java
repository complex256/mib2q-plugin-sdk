package mibsdk.adapter.hqc.ui;

import de.audi.atip.hmi.event.KeyEvent;
import de.audi.atip.hmi.view.HMIView;
import de.audi.tghu.car.hmi.evohigh.FontFactory;
import de.esolutions.hmi.widgets.audi.base.InitializationContext;
import de.esolutions.hmi.widgets.audi.base.widgets.AbstractWidgetController;
import de.esolutions.hmi.widgets.audi.evo.ScreenWidgetEVO;
import de.esolutions.hmi.widgets.audi.evo.high.widgets.GlassplateContainerRendererHigh;
import de.esolutions.hmi.widgets.audi.evo.high.widgets.GlassplateRendererHigh;
import de.esolutions.hmi.widgets.audi.evo.high.widgets.LabelRendererHigh;
import de.esolutions.hmi.widgets.audi.evo.high.widgets.MirrorRendererHigh;
import de.esolutions.hmi.widgets.audi.evo.high.widgets.MirroredContainerController;
import de.esolutions.hmi.widgets.audi.evo.high.widgets.RotaryBigRendererHigh;
import de.esolutions.hmi.widgets.audi.evo.widgets.ContainerController;
import de.esolutions.hmi.widgets.audi.evo.widgets.GlassplateController;
import de.esolutions.hmi.widgets.audi.evo.widgets.LabelController;
import de.esolutions.hmi.widgets.audi.evo.widgets.RotaryController;
import mibsdk.adapter.hqc.SdkGlobals;

/**
 * The slider edit screen {@link HqcUiIds#SLIDER_SCREEN_ID} (spike D, D6; the {@code tONESOUNDBASS} recipe of
 * {@code SpikeScreens.slider}): a glassplate, and in a mirrored container the big rotary on the slot's pooled RangeModel
 * with the min and max labels ({@link HqcUiIds#TEXT_SLIDER_MIN}/{@link HqcUiIds#TEXT_SLIDER_MAX}), inside the page's
 * shell titled with the slot's label. The rotary is focused and active at connect (state 36), so the knob reaches the
 * model's RangeListener ({@code RangeModelKeyHandler}); its turns go to {@link PageScreens#sliderTurned}. BACK (key
 * 15, consumed) is {@link PageScreens#subBack}. Built and shown on HMIEvent in the applier's job, like the page; its
 * callbacks catch Throwable.
 */
final class SliderScreen {

    private SliderScreen() {
    }

    /**
     * The screen: BACK (press and release) is the SDK's while the SDK owns it ({@link PageScreens#ownsSub}), else
     * stock's (never a BACK swallowed on a screen nothing handles); it tells its owner when it connects and leaves.
     */
    static final class Screen extends ScreenWidgetEVO {
        private final PageScreens owner;

        Screen(PageScreens owner) {
            super(HqcUiIds.SLIDER_SCREEN_ID);
            this.owner = owner;
        }

        public void connected(InitializationContext c) {
            super.connected(c);
            owner.subConnected(this); // never throws
        }

        public void disconnecting() {
            try {
                super.disconnecting();
            } finally {
                owner.subDisconnecting(this); // never throws
            }
        }

        public void keyPressed(KeyEvent e) {
            try {
                if (e.getKeyCode() == HqcUiIds.KEY_BACK && owner.ownsSub(this)) {
                    e.consume(true);
                    owner.subBack(); // never throws
                    return;
                }
            } catch (Throwable t) {
                // stock handling below
            }
            super.keyPressed(e);
        }

        public void keyReleased(KeyEvent e) {
            try {
                if (e.getKeyCode() == HqcUiIds.KEY_BACK && owner.ownsSub(this)) {
                    e.consume(true);
                    return;
                }
            } catch (Throwable t) {
                // stock handling below
            }
            super.keyReleased(e);
        }
    }

    /** The big rotary; logs once a session if it is not focused and active at connect (spike D saw state 36). */
    static final class Knob extends RotaryController {
        private static boolean logged; // HMIEvent only

        public void connected(InitializationContext c) {
            super.connected(c);
            try {
                if (!hasState(HqcUiIds.SLIDER_KNOB_STATE) && !logged) {
                    logged = true;
                    SdkGlobals.LOG.append("WARN", "mibsdk", "ui slider knob not active at connect (no state "
                            + HqcUiIds.SLIDER_KNOB_STATE + "): turns may not reach the slider");
                }
            } catch (Throwable t) {
                // logging never breaks the HMI
            }
        }
    }

    static Screen build(SdkCarScreenFactory f, int t, int slot, PageLayout layout, PageScreens owner) {
        AbstractWidgetController ref0 = f.getRefWidget(t, 0, f);
        int model = HqcUiIds.TYPED_RANGE_MODEL_0 + slot;
        int[] g = HqcUiIds.SLIDER_GLASS_BOUNDS;
        GlassplateController glass = new GlassplateController();
        glass.setRenderer(new GlassplateRendererHigh(glass));
        glass.setBounds(g[0], g[1], g[2], g[3]);
        glass.setCoordinateSets((int[]) HqcUiIds.SLIDER_GLASS_COORDS.clone());
        Knob rot = new Knob();
        rot.setRenderer(new RotaryBigRendererHigh(rot));
        rot.setModelID(model);
        int[] r = HqcUiIds.SLIDER_ROTARY_BOUNDS;
        rot.setBounds(r[0], r[1], r[2], r[3]);
        LabelController max = label(f, t, HqcUiIds.TEXT_SLIDER_MAX, HqcUiIds.SLIDER_MAX_BOUNDS);
        LabelController min = label(f, t, HqcUiIds.TEXT_SLIDER_MIN, HqcUiIds.SLIDER_MIN_BOUNDS);
        ((LabelRendererHigh) min.getRenderer()).setAlignment(HqcUiIds.SLIDER_MIN_ALIGN_H, HqcUiIds.SLIDER_MIN_ALIGN_V);
        int[] m = HqcUiIds.SLIDER_MIRROR_BOUNDS;
        MirroredContainerController mirror = new MirroredContainerController();
        mirror.setRenderer(new MirrorRendererHigh(mirror));
        mirror.setBounds(m[0], m[1], m[2], m[3]);
        mirror.add(rot);
        mirror.add(max);
        mirror.add(min);
        ContainerController box = new ContainerController();
        box.setRenderer(new GlassplateContainerRendererHigh(box));
        box.setBounds(0, 0, 0, 0);
        box.add(glass);
        box.add(mirror);
        Screen s = new Screen(owner);
        PageWidgets.shell(s, f, t, ref0, box, HqcUiIds.TEXT_INPUT_BASE + HqcUiIds.TEXT_TYPED_STRIDE * slot,
                HqcUiIds.SLIDER_SMALL_STAGE, new int[] {model}, new HMIView[][] {{rot}});
        return s;
    }

    private static LabelController label(SdkCarScreenFactory f, int t, int text, int[] b) {
        LabelController l = new LabelController();
        LabelRendererHigh lr = new LabelRendererHigh(l);
        l.setRenderer(lr);
        lr.setFonts(FontFactory.getFonts(HqcUiIds.SLIDER_LABEL_FONTS, t, f.framework()));
        l.setTextIds(new int[] {text});
        l.setBounds(b[0], b[1], b[2], b[3]);
        l.setColorIndices((int[]) HqcUiIds.SLIDER_LABEL_COLOURS.clone());
        return l;
    }
}
