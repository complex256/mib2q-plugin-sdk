package mibsdk.spike.ui;

import de.audi.atip.activator.AbstractHMIActivator;
import de.audi.atip.base.IFrameworkAccess;
import de.audi.atip.hmi.HMIService;
import de.audi.atip.hmi.IHMIServiceApp;
import de.audi.atip.hmi.event.RunnableEvent;
import de.audi.atip.hmi.model.ChoiceModel;
import de.audi.atip.hmi.model.HMIModel;
import de.audi.atip.hmi.model.HMIResourceLocator;
import de.audi.atip.hmi.model.IntegerListCell;
import de.audi.atip.hmi.model.LabelModel;
import de.audi.atip.hmi.model.ListModel;
import de.audi.atip.hmi.model.RangeModel;
import de.audi.atip.hmi.model.SpellerModel;
import de.audi.atip.hmi.model.ResourceLocatorModel;
import de.audi.atip.hmi.model.AbstractModelBank;
import de.audi.atip.hmi.modelaccess.ChoiceModelApp;
import de.audi.atip.hmi.modelaccess.LabelModelApp;
import de.esolutions.hmi.widgets.audi.evo.widgets.MainWizardController;
import de.esolutions.hmi.widgets.audi.evo.widgets.PartialPopupController;
import de.esolutions.hmi.widgets.audi.evo.widgets.PartialPopupStub;
import de.esolutions.hmi.widgets.audi.evo.widgets.menu.MenuItemController;
import java.io.File;

/**
 * Daemon thread "mibsdk-spike-ui": polls command files /tmp/mibsdk-spike-ui.&lt;cmd&gt; every 100 ms (each is deleted
 * when taken). "rerun" runs spike A's probe sequence (S1c, S1d, S3a/b, S3c); spike B adds row1-show/row1-hide,
 * back-reshow/back-event/back-stock, reset-sel-on/off, nolabel-on and hijack (file holds an event id).
 * Spike C adds pop-on/pop-off, pop-file, pop-slot (file holds a slot number), icon-next, glyphs1/glyphs2/glyphs3/glyph-off, logo-on/logo-off, and for the
 * main menu (Boot B) wrench, wz-hide/wz-show and wz-label. Spike D adds page, cb-set/cb-clear, prog-next, kind-swap,
 * rs-off/rs-on, typed-off/typed-on, label-on, slider, confirm, speller, slider-fwd, slider-focus, range-set (file
 * holds the value), toast and dump.
 */
final class SpikeProbes implements Runnable {
    private static final String RULER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private final AbstractHMIActivator activator;

    private SpikeProbes(AbstractHMIActivator activator) {
        this.activator = activator;
    }

    static void start(AbstractHMIActivator activator) {
        Thread t = new Thread(new SpikeProbes(activator), "mibsdk-spike-ui");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        t.start();
    }

    public void run() {
        int round = 0;
        while (true) {
            sleep(100);
            try {
                if (take("rerun")) {
                    SpikeLog.log("probe rerun requested");
                    sequence(round++);
                }
                if (take("row1-show")) {
                    row1(0);
                }
                if (take("row1-hide")) {
                    row1(1);
                }
                if (take("back-reshow")) {
                    mode(0);
                }
                if (take("back-event")) {
                    mode(1);
                }
                if (take("back-stock")) {
                    mode(2);
                }
                if (take("reset-sel-on")) {
                    resetSelection = true;
                    SpikeLog.log("cmd resetSelection=true");
                }
                if (take("reset-sel-off")) {
                    resetSelection = false;
                    SpikeLog.log("cmd resetSelection=false");
                }
                if (take("nolabel-on")) {
                    SpikePage.noLabelRow = true;
                    SpikeLog.log("cmd noLabelRow=true");
                }
                spikeC();
                spikeD();
                File h = cmd("hijack");
                if (h.exists()) {
                    int ev = readInt(h);
                    h.delete();
                    if (ev > 0) {
                        SpikePage.hijackEvent = ev;
                    }
                    SpikeLog.log("cmd hijackEvent=" + SpikePage.hijackEvent);
                }
            } catch (Throwable t) {
                SpikeLog.log("probe command FAILED: " + t);
            }
        }
    }

    // ---- Spike C ------------------------------------------------------------------------------------------------

    /** S4 glyph rulers (font coverage of label 416). */
    static final String GLYPHS1 = "\u2190\u2191\u2192\u2193\u2022\u25AA\u25BA\u25C0\u25C6\u25CA\u221E\u221A\u2300";
    static final String GLYPHS2 = "\u25CF\u25CB\u2605\u25B2\u25A0\u25A1\u25CE\u203B\u266A\u260E\u2713\u26A0";
    /** S4 re-read: the glyphs that were too small to tell apart in glyphs1, spaced out, plus a known-missing pair. */
    static final String GLYPHS3 = "\u25AA \u25BA \u25C0 \u2300 \u25CA \u2713 \u26A0";
    /** S4: the glyph text held in label 416 (re-asserted every second, the SDK status writer may share it). */
    private static volatile String heldGlyphs;
    /**
     * Slot of popup 609600 (stock popup 65's is 5); changed by "pop-slot" (file holds the slot number). Spike D: 7,
     * the SDK's slot, so the toast and the SDK status line (popup 65 under the spike activator) show side by side.
     */
    static volatile int popupSlot = 7;
    private long lastGlyphWrite;

    private void spikeC() {
        if (take("pop-on")) {
            popupVisible(1);
        }
        if (take("pop-off")) {
            popupVisible(0);
        }
        if (take("pop-file")) {
            ResourceLocatorModel r = (ResourceLocatorModel) spike(SpikeIds.POPUP_FILE_MODEL);
            if (r != null) {
                String path = SpikeIds.ICON_ABS_DIR + "p20.png";
                r.setResourceLocator(new HMIResourceLocator(path));
                SpikeLog.log("cmd pop-file 602752 := " + path);
            }
        }
        File ps = cmd("pop-slot");
        if (ps.exists()) {
            final int slot = readInt(ps);
            ps.delete();
            if (slot >= 0 && slot < 14) {
                popupVisible(0);
                onHmi("pop-slot", new Runnable() {
                    public void run() {
                        popupSlot = slot;
                        PartialPopupStub st = SpikeFactory.popupStub;
                        int old = st == null ? -1 : st.getSlot();
                        if (st != null) {
                            st.setSlot(slot);
                        }
                        PartialPopupController b = SpikePopup.built;
                        if (b != null) {
                            b.setSlot(slot);
                        }
                        SpikeLog.log("cmd pop-slot stub " + old + " -> " + slot + " built=" + (b != null)
                                + " (pop-on to show)");
                    }
                });
            } else {
                SpikeLog.log("cmd pop-slot ignored: " + slot);
            }
        }
        if (take("icon-next")) {
            onHmi("icon-next", new Runnable() {
                public void run() {
                    MenuItemController r = SpikeFactory.row0;
                    if (r != null) {
                        r.setBitmaps(new int[] {SpikeIds.ICON_NEXT, SpikeIds.ICON_NEXT_REFLEX});
                    }
                    SpikeLog.log("cmd icon-next row0=" + (r != null) + " bitmaps := {609102, 609103}");
                }
            });
        }
        if (take("glyphs1")) {
            glyphs(GLYPHS1);
        }
        if (take("glyphs2")) {
            glyphs(GLYPHS2);
        }
        if (take("glyphs3")) {
            glyphs(GLYPHS3);
        }
        if (take("glyph-off")) {
            heldGlyphs = null;
            status("", 0);
            SpikeLog.log("cmd glyph-off");
        }
        String g = heldGlyphs;
        if (g != null && System.currentTimeMillis() - lastGlyphWrite >= 1000) {
            lastGlyphWrite = System.currentTimeMillis();
            status(g, 1);
        }
        if (take("logo-on")) {
            logo(SpikeIds.ICON_ABS_DIR + "p30.png", 1);
        }
        if (take("logo-off")) {
            logo("", 0);
        }
        // Boot B (main menu) commands.
        if (take("wrench")) {
            onHmi("wrench", new Runnable() {
                public void run() {
                    MainWizardController w = SpikeSystemFactory.wizard;
                    if (w == null) {
                        SpikeLog.log("cmd wrench: no wizard yet");
                        return;
                    }
                    int[][] map = w.getInitContext().getLayout().getMainWizardIconDecoratorMapping();
                    int k = SpikeIds.WRENCH_WIDGET_ID;
                    int[] old = map[k];
                    map[k] = new int[] {16, 9};
                    SpikeLog.log("cmd wrench mapping[" + k + "] " + (old == null ? "null" : old[0] + "," + old[1])
                            + " -> 16,9 (length " + map.length + ")");
                }
            });
        }
        if (take("wz-hide")) {
            wizardVisible(false);
        }
        if (take("wz-show")) {
            wizardVisible(true);
        }
        if (take("wz-label")) {
            String[] t = (String[]) SpikeSystemFactory.wizardTexts.clone();
            t[0] = "SDK w10 relabelled";
            SpikeSystemFactory.wizardTexts = t;
            SpikeLog.log("cmd wz-label text[90000]='" + t[0] + "'");
        }
    }

    // ---- Spike D ------------------------------------------------------------------------------------------------

    /** Toast: when to hide popup 609600 again (0 = no toast pending). */
    private long toastHideAt;

    private void spikeD() {
        if (take("page")) {
            SpikePage.postShowPage(true, "cmd");
        }
        if (take("cb-set")) {
            ListModel l = SpikePage.pageList;
            if (l != null) {
                l.setCell(SpikePage.ROW_CHECKBOX_1, SpikePage.COL_VALUE, IntegerListCell.create(1));
            }
            SpikeLog.log("cmd cb-set row=" + SpikePage.ROW_CHECKBOX_1 + " value=1 model=" + (l != null) + " thread="
                    + Thread.currentThread().getName());
        }
        if (take("cb-clear")) {
            ListModel l = SpikePage.pageList;
            if (l != null) {
                l.setCell(SpikePage.ROW_CHECKBOX_1, SpikePage.COL_VALUE, IntegerListCell.create(0));
            }
            SpikeLog.log("cmd cb-clear row=" + SpikePage.ROW_CHECKBOX_1 + " value=0");
        }
        if (take("prog-next")) {
            SpikePage.progressNext("cmd");
        }
        if (take("kind-swap")) {
            ListModel l = SpikePage.pageList;
            if (l != null) {
                int k = SpikePage.intCell(l, 1, SpikePage.COL_KIND);
                int nk = k == SpikePage.KIND_ACTION ? SpikePage.KIND_CHECKBOX : SpikePage.KIND_ACTION;
                l.setCell(1, SpikePage.COL_KIND, IntegerListCell.create(nk));
                SpikeLog.log("cmd kind-swap row=1 kind " + k + " -> " + nk);
            }
        }
        if (take("rs-off")) {
            SpikePage.recordSet = false;
            SpikeLog.log("cmd recordSet=false (next page build)");
        }
        if (take("rs-on")) {
            SpikePage.recordSet = true;
            SpikeLog.log("cmd recordSet=true (next page build)");
        }
        if (take("typed-off")) {
            SpikePage.typedRows = false;
            SpikeLog.log("cmd typedRows=false (next page build)");
        }
        if (take("typed-on")) {
            SpikePage.typedRows = true;
            SpikeLog.log("cmd typedRows=true (next page build)");
        }
        if (take("label-on")) {
            SpikePage.noLabelRow = false;
            SpikeLog.log("cmd noLabelRow=false (next page build)");
        }
        if (take("slider")) {
            SpikeScreens.postShow(SpikeIds.SLIDER_SCREEN_ID, "cmd");
        }
        if (take("confirm")) {
            SpikeScreens.postShow(SpikeIds.CONFIRM_SCREEN_ID, "cmd");
        }
        if (take("speller")) {
            SpikeScreens.postShow(SpikeIds.SPELLER_SCREEN_ID, "cmd");
        }
        if (take("slider-fwd")) {
            SpikeScreens.sliderForward = true;
            SpikeLog.log("cmd sliderForward=true");
        }
        if (take("slider-focus")) {
            onHmi("slider-focus", new Runnable() {
                public void run() {
                    SpikeScreens.SliderRotary r = SpikeScreens.rotary;
                    if (r != null) {
                        r.setActive(true);
                        r.setFocused(true);
                    }
                    SpikeLog.log("cmd slider-focus rotary=" + (r != null) + " has36="
                            + (r != null && r.hasState(36)));
                }
            });
        }
        File rs = cmd("range-set");
        if (rs.exists()) {
            int v = readInt(rs);
            rs.delete();
            RangeModel r = (RangeModel) spike(SpikeIds.RANGE_MODEL);
            if (r != null) {
                r.setValue(SpikeInputs.clamp(v));
            }
            SpikeLog.log("cmd range-set " + v + " model=" + (r != null));
        }
        if (take("toast")) {
            LabelModel l = (LabelModel) spike(SpikeIds.POPUP_LABEL_MODEL);
            if (l != null) {
                l.setText("Saved");
            }
            popupVisible(1);
            toastHideAt = System.currentTimeMillis() + 3000;
            SpikeLog.log("cmd toast 'Saved' shown, hide in 3000 ms (slot " + popupSlot + ")");
        }
        if (toastHideAt != 0 && System.currentTimeMillis() >= toastHideAt) {
            toastHideAt = 0;
            popupVisible(0);
            SpikeLog.log("toast hidden by timer");
        }
        if (take("dump")) {
            dump();
        }
    }

    private static void dump() {
        StringBuffer b = new StringBuffer("dump");
        ListModel l = SpikePage.pageList;
        if (l != null) {
            for (int r = 0; r < SpikePage.ROW_TEXT.length; r++) {
                b.append(" r").append(r).append('=').append(SpikePage.intCell(l, r, SpikePage.COL_VALUE)).append('/')
                        .append(SpikePage.intCell(l, r, SpikePage.COL_KIND));
            }
        }
        ChoiceModel r8 = (ChoiceModel) spike(SpikeIds.ROW8_MODEL);
        ChoiceModel cb = (ChoiceModel) spike(SpikeIds.COMBO_MODEL);
        RangeModel rg = (RangeModel) spike(SpikeIds.RANGE_MODEL);
        SpellerModel sp = (SpellerModel) spike(SpikeIds.SPELLER_MODEL);
        b.append(" row8=").append(r8 == null ? -99 : r8.getValue());
        b.append(" combo=").append(cb == null ? -99 : cb.getValue());
        b.append(" range=").append(rg == null ? -99 : rg.getValue());
        b.append(" speller='").append(sp == null ? "?" : sp.getText()).append('\'');
        SpikeLog.log(b.toString());
    }

    private static HMIModel spike(int id) {
        SpikeBank bank = SpikeBank.instance;
        return bank == null ? null : bank.spikeModel(id);
    }

    private static void popupVisible(int v) {
        ChoiceModel c = (ChoiceModel) spike(SpikeIds.POPUP_VISIBLE_MODEL);
        if (c != null) {
            c.setValue(v);
        }
        SpikeLog.log("cmd popup 609600 visibility 602751 := " + v + " model=" + (c != null));
    }

    private void glyphs(String g) {
        heldGlyphs = g;
        lastGlyphWrite = System.currentTimeMillis();
        status(g, 1);
        SpikeLog.log("cmd glyphs len=" + g.length());
    }

    private IHMIServiceApp cachedHmi;

    private IHMIServiceApp hmiC() {
        if (cachedHmi == null) {
            cachedHmi = hmi(System.currentTimeMillis());
        }
        return cachedHmi;
    }

    private void status(String text, int visible) {
        IHMIServiceApp hmi = hmiC();
        hmi.getLabelModel(SpikeIds.STATUS_LABEL_MODEL).setText(text);
        hmi.getChoiceModel(SpikeIds.STATUS_VISIBLE_CHOICE).setValue(visible);
    }

    private void logo(String path, int st) {
        IHMIServiceApp hmi = hmiC();
        hmi.getResourceLocatorModel(SpikeIds.SYS_PROVIDER_LOGO_MODEL).setResourceLocator(-1, path, st);
        SpikeLog.log("cmd logo model 3838 := status " + st + " '" + path + "'");
    }

    private static void wizardVisible(final boolean v) {
        onHmi("wz-visible", new Runnable() {
            public void run() {
                MenuItemController[] rows = SpikeSystemFactory.wizardRows;
                if (rows != null) {
                    rows[0].setVisible(v);
                }
                SpikeLog.log("cmd wz-" + (v ? "show" : "hide") + " rows=" + (rows != null));
            }
        });
    }

    /** Widget work goes to HMIEvent; a throwing runnable only logs. */
    private static void onHmi(final String what, final Runnable r) {
        HMIService svc = AbstractModelBank.getHMIservice();
        svc.getEventDispatcher().postEvent(new RunnableEvent(false, new Runnable() {
            public void run() {
                try {
                    r.run();
                } catch (Throwable t) {
                    SpikeLog.log("cmd " + what + " FAILED on HMIEvent: " + t);
                }
            }
        }));
    }

    /** Spike B: set by the "reset-sel-on" command; the drawer press listener then resets the selection model to -1. */
    static volatile boolean resetSelection;

    private static File cmd(String name) {
        return new File("/tmp/mibsdk-spike-ui." + name);
    }

    private static boolean take(String name) {
        File f = cmd(name);
        if (!f.exists()) {
            return false;
        }
        f.delete();
        return true;
    }

    private static void mode(int m) {
        SpikePage.backMode = m;
        SpikeLog.log("cmd backMode=" + m);
    }

    private static int readInt(File f) {
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            try {
                byte[] b = new byte[16];
                int n = in.read(b);
                return n <= 0 ? -1 : Integer.parseInt(new String(b, 0, n).trim());
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            return -1;
        }
    }

    /** S1c timing: flip row 1's visibility model now (the drawer can stay open). */
    private static void row1(int v) {
        SpikeBank bank = SpikeBank.instance;
        if (bank == null) {
            return;
        }
        ChoiceModel row1 = (ChoiceModel) bank.spikeModel(SpikeIds.MODEL_BASE + 1);
        row1.setValue(v);
        SpikeLog.log("cmd S1c row1 visibility := " + v);
    }

    private void sequence(int round) {
        long t0 = System.currentTimeMillis();
        SpikeBank bank = SpikeBank.instance;
        log(t0, "start round=" + round + " bank=" + (bank != null));
        if (bank == null) {
            return;
        }
        // S1c: row 1 visible.
        ChoiceModel row1 = (ChoiceModel) bank.spikeModel(SpikeIds.MODEL_BASE + 1);
        row1.setValue(round % 2 == 0 ? 0 : 1);
        log(t0, "S1c row1 visibility model " + row1.getID() + " := " + row1.getValue());

        at(t0, 10000);
        // S1d: row 0's factory text, and its %1 LabelModel.
        String[] t = (String[]) SpikeFactory.texts.clone();
        t[0] = "SDK row 0 B" + round + " [%1]";
        SpikeFactory.texts = t;
        LabelModel lbl = (LabelModel) bank.spikeModel(SpikeIds.LABEL_MODEL);
        lbl.setText("m-B" + round);
        log(t0, "S1d text[0]='" + t[0] + "' label model " + lbl.getID() + "='" + lbl.getText() + "'");

        IHMIServiceApp hmi = hmi(t0);
        int[] lens = {10, 15, 20, 25, 30};
        for (int i = 0; i < lens.length; i++) {
            at(t0, 20000 * (i + 1));
            status(t0, hmi, RULER.substring(0, lens[i]), 1);
        }
        at(t0, 120000);
        // S3c: one throwing RunnableEvent on HMIEvent.
        try {
            HMIService svc = AbstractModelBank.getHMIservice();
            svc.getEventDispatcher().postEvent(new RunnableEvent(false, new Runnable() {
                public void run() {
                    SpikeLog.log("S3c runnable on " + Thread.currentThread().getName() + ", throwing");
                    throw new IllegalStateException("spike S3c deliberate throw");
                }
            }));
            log(t0, "S3c posted throwing RunnableEvent");
        } catch (Throwable e) {
            log(t0, "S3c post FAILED: " + e);
        }
        at(t0, 140000);
        status(t0, hmi, "", 0);
        log(t0, "done round=" + round);
    }

    /** The system-model accessor: IFrameworkAccess.getHmiServiceApp(), else AbstractModelBank's HMIService. */
    private IHMIServiceApp hmi(long t0) {
        try {
            IFrameworkAccess fw = activator.getFramework();
            IHMIServiceApp a = fw == null ? null : fw.getHmiServiceApp();
            if (a != null) {
                log(t0, "hmi accessor: IFrameworkAccess.getHmiServiceApp() " + a.getClass().getName());
                return a;
            }
        } catch (Throwable e) {
            log(t0, "getHmiServiceApp FAILED: " + e);
        }
        HMIService s = AbstractModelBank.getHMIservice();
        log(t0, "hmi accessor: AbstractModelBank.getHMIservice() " + (s == null ? "null" : s.getClass().getName()));
        return s;
    }

    private void status(long t0, IHMIServiceApp hmi, String text, int visible) {
        try {
            LabelModelApp l = hmi.getLabelModel(SpikeIds.STATUS_LABEL_MODEL);
            ChoiceModelApp c = hmi.getChoiceModel(SpikeIds.STATUS_VISIBLE_CHOICE);
            l.setText(text);
            c.setValue(visible);
            log(t0, "S3 status len=" + text.length() + " text='" + text + "' choice1=" + visible
                    + " label=" + l.getClass().getName());
        } catch (Throwable e) {
            log(t0, "S3 status FAILED: " + e);
        }
    }

    private static void at(long t0, long offset) {
        long wait = t0 + offset - System.currentTimeMillis();
        if (wait > 0) {
            sleep(wait);
        }
    }

    private static void log(long t0, String msg) {
        SpikeLog.log("probe T+" + (System.currentTimeMillis() - t0) / 1000 + " " + msg);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            // keep going
        }
    }
}
