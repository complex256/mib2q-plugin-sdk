package mibsdk.spike.ui;

import de.audi.atip.base.IFrameworkAccess;
import de.audi.atip.hmi.view.IPartialPopupController;
import de.audi.atip.hmi.view.Screen;
import de.audi.tghu.system.hmi.evohigh.SystemScreenFactory;
import de.esolutions.hmi.widgets.audi.base.AbstractWidget;
import de.esolutions.hmi.widgets.audi.base.widgets.AbstractWidgetController;
import de.esolutions.hmi.widgets.audi.evo.widgets.IconController;
import de.esolutions.hmi.widgets.audi.evo.widgets.LabelController;
import de.esolutions.hmi.widgets.audi.evo.widgets.MainWizardController;
import de.esolutions.hmi.widgets.audi.evo.widgets.menu.MenuItemController;
import java.io.File;
import java.util.List;

/**
 * Spike C, Boot B: SystemScreenFactory that appends 4 SDK rows (widgetIDs 10, 11, 12, 14; texts 90000..90003) to the
 * main-menu wizard ring (screen 46), and (only when /tmp/mibsdk-spike-ui.p65icons exists when the popup is built)
 * adds two icons to popup 65. Every override calls super first and never throws: on any Throwable the stock
 * object is returned.
 */
final class SpikeSystemFactory extends SystemScreenFactory {
    /** Live strings for WIZARD_TEXT_BASE + 0..3. Written by the probe thread, read on HMIEvent. */
    static volatile String[] wizardTexts = {"SDK w10", "SDK w11 wrench", "SDK w12", "SDK w14"};
    /** The SDK rows of the last built screen 46 (for wz-hide/wz-show) and its wizard (for wrench). */
    static volatile MenuItemController[] wizardRows;
    static volatile MainWizardController wizard;

    SpikeSystemFactory(IFrameworkAccess fw) {
        super(fw);
    }

    protected Screen createScreen(int id, int terminal) {
        Screen s = super.createScreen(id, terminal);
        if (id != SpikeIds.SYS_SCREEN_MAINWIZARD || s == null) {
            return s;
        }
        try {
            MainWizardController w = findWizard((AbstractWidget) s);
            if (w == null) {
                SpikeLog.log("screen 46: no MainWizardController; stock screen");
                return s;
            }
            List children = w.getChildren();
            MenuItemController ref = null;
            for (int i = 0; i < children.size(); i++) {
                Object o = children.get(i);
                if (!(o instanceof MenuItemController)) {
                    SpikeLog.log("  wizard child " + i + " " + o.getClass().getName());
                    continue;
                }
                MenuItemController m = (MenuItemController) o;
                ref = m;   // the last row (Settings) is the template
                SpikeLog.log("  wizard row " + i + " label=" + m.getLabelId() + " text='" + getText(m.getLabelId())
                        + "' widgetID=" + m.getWidgetID() + " event=" + m.getEvent() + " visible=" + m.isVisible()
                        + " internalID=" + m.getInternalID());
            }
            if (ref == null) {
                SpikeLog.log("screen 46: no wizard rows; stock screen");
                return s;
            }
            int[] colors = ref.getColorIndices();
            MenuItemController[] rows = new MenuItemController[SpikeIds.WIZARD_WIDGET_IDS.length];
            for (int n = 0; n < rows.length; n++) {
                MenuItemController m = new MenuItemController();
                m.setLabelId(SpikeIds.WIZARD_TEXT_BASE + n);
                if (colors != null) {
                    m.setColorIndices((int[]) colors.clone());
                }
                m.setGlassplateInsetsBottom(6);
                m.setGlassplateInsetsTop(7);
                m.setInternalID(SpikeIds.WIZARD_INTERNAL_ID_BASE + n);
                m.setKeyHandler(new SpikeWizardKeyHandler(n));
                m.setType(SpikeIds.DRAWER_ROW_TYPE_ACTION);
                m.setWidgetID(SpikeIds.WIZARD_WIDGET_IDS[n]);
                rows[n] = m;
            }
            for (int n = 0; n < rows.length; n++) {
                w.add(rows[n]);
            }
            wizardRows = rows;
            wizard = w;
            SpikeLog.log("wizard rows appended n=" + rows.length + " children=" + w.getChildren().size()
                    + " thread=" + Thread.currentThread().getName());
        } catch (Throwable t) {
            SpikeLog.log("screen 46 spike part FAILED, rebuilding stock: " + t);
            try {
                return super.createScreen(id, terminal);
            } catch (Throwable t2) {
                return s;
            }
        }
        return s;
    }

    private static MainWizardController findWizard(AbstractWidget w) {
        if (w instanceof MainWizardController) {
            return (MainWizardController) w;
        }
        List c = w.getChildren();
        for (int i = 0; c != null && i < c.size(); i++) {
            Object o = c.get(i);
            if (o instanceof AbstractWidget) {
                MainWizardController r = findWizard((AbstractWidget) o);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    /** S3 (only with the p65icons flag file): popup 65 with a custom (609120) and a stock (230) icon. */
    public IPartialPopupController getPartialPopup(int id, int terminal) {
        IPartialPopupController p = super.getPartialPopup(id, terminal);
        if (id != SpikeIds.POPUP_STATUS || p == null) {
            return p;
        }
        try {
            if (!new File("/tmp/mibsdk-spike-ui.p65icons").exists()) {
                return p;
            }
            AbstractWidgetController pc = (AbstractWidgetController) p;
            List c = pc.getChildren();
            for (int i = 0; i < c.size(); i++) {
                Object o = c.get(i);
                if (o instanceof LabelController) {
                    ((LabelController) o).setBounds(58, 7, 187, 20);
                }
            }
            IconController a = SpikePopup.icon(new int[] {SpikeIds.ICON_P65}, 10);
            IconController b = SpikePopup.icon(new int[] {SpikeIds.SYS_ICON_STATUS}, 34);
            pc.add(a);
            pc.add(b);
            SpikeLog.log("popup 65 built with icons 609120, 230");
        } catch (Throwable t) {
            SpikeLog.log("popup 65 spike part FAILED, rebuilding stock: " + t);
            try {
                return super.getPartialPopup(id, terminal);
            } catch (Throwable t2) {
                return p;
            }
        }
        return p;
    }

    public String getText(int id) {
        int n = id - SpikeIds.WIZARD_TEXT_BASE;
        if (n >= 0 && n < SpikeIds.WIZARD_WIDGET_IDS.length) {
            String[] t = wizardTexts;
            return t[n] == null ? "" : t[n];
        }
        return super.getText(id);
    }

    public String getText(int id, int viewSize) {
        int n = id - SpikeIds.WIZARD_TEXT_BASE;
        if (n >= 0 && n < SpikeIds.WIZARD_WIDGET_IDS.length) {
            return getText(id);
        }
        return super.getText(id, viewSize);
    }

    public String getAltText(int id) {
        int n = id - SpikeIds.WIZARD_TEXT_BASE;
        if (n >= 0 && n < SpikeIds.WIZARD_WIDGET_IDS.length) {
            return getText(id);
        }
        return super.getAltText(id);
    }
}
