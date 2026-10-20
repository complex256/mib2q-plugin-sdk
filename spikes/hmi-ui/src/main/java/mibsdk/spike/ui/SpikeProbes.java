package mibsdk.spike.ui;

import de.audi.atip.activator.AbstractHMIActivator;
import de.audi.atip.base.IFrameworkAccess;
import de.audi.atip.hmi.HMIService;
import de.audi.atip.hmi.IHMIServiceApp;
import de.audi.atip.hmi.event.RunnableEvent;
import de.audi.atip.hmi.model.ChoiceModel;
import de.audi.atip.hmi.model.HMIModel;
import de.audi.atip.hmi.model.LabelModel;
import de.audi.atip.hmi.model.AbstractModelBank;
import de.audi.atip.hmi.modelaccess.ChoiceModelApp;
import de.audi.atip.hmi.modelaccess.LabelModelApp;
import java.io.File;

/**
 * Daemon thread "mibsdk-spike-ui": polls command files /tmp/mibsdk-spike-ui.&lt;cmd&gt; every 100 ms (each is deleted
 * when taken). "rerun" runs spike A's probe sequence (S1c, S1d, S3a/b, S3c); spike B adds row1-show/row1-hide,
 * back-reshow/back-event/back-stock, reset-sel-on/off, nolabel-on and hijack (file holds an event id).
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
