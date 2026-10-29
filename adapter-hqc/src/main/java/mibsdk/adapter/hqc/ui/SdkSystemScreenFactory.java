package mibsdk.adapter.hqc.ui;

import de.audi.atip.base.IFrameworkAccess;
import de.audi.atip.hmi.view.Screen;
import de.audi.tghu.system.hmi.evohigh.SystemScreenFactory;
import de.esolutions.hmi.widgets.audi.base.AbstractWidget;
import de.esolutions.hmi.widgets.audi.evo.widgets.MainWizardController;
import de.esolutions.hmi.widgets.audi.evo.widgets.menu.MenuItemController;
import java.util.List;
import mibsdk.adapter.hqc.SdkGlobals;

/**
 * Stock {@code SystemScreenFactory} plus one row in the main-menu wizard (screen
 * {@link HqcUiIds#SYS_SCREEN_MAINWIZARD}, the MENU ring): Plugins, after the stock rows (spike C, Boot B C1/C2). The
 * row's icon (the wrench) is not set here: {@link MainMenuWrench} sets it from the HMI job on the connected wizard.
 *
 * <p>Every override calls super first. On any Throwable while adding the row, or under the forced failure
 * {@code screen}, screen 46 is rebuilt stock and {@link SdkGlobals#mainMenu} becomes FAILED, which is sticky for the
 * boot. Same rules as {@link SdkSystemActivator}: no static initializer, no static field other than compile-time
 * constants, every SDK class use inside a catch of Throwable. Runs on HMIEvent: no I/O, no thread, no plugin code.
 */
final class SdkSystemScreenFactory extends SystemScreenFactory {

    private static final String SOURCE = "mibsdk";
    private static final String DEFAULT_LABEL = "Plugins";

    SdkSystemScreenFactory(IFrameworkAccess fw) {
        super(fw);
    }

    /**
     * SDK icon ids ({@link HqcUiIds#ICON_ID_FIRST}..{@link HqcUiIds#ICON_ID_LAST}) with a known path: that path, as
     * the Car factory serves it (the ImageRoot is the shared {@code lsd/images}). Anything else: stock. This is what
     * makes the built-in bar's plugin PNGs global: the bar is a System screen, so its image ids resolve here, not only
     * in the Car factory.
     */
    public String getImagePath(int id, int terminal) {
        try {
            if (id >= HqcUiIds.ICON_ID_FIRST && id <= HqcUiIds.ICON_ID_LAST) {
                String p = SdkGlobals.ICON_IDS.relPathFor(id);
                if (p != null) {
                    return p;
                }
            }
        } catch (Throwable t) {
            // stock path
        }
        return super.getImagePath(id, terminal);
    }

    protected Screen createScreen(int id, int terminal) {
        Screen s = super.createScreen(id, terminal);
        if (id != HqcUiIds.SYS_SCREEN_MAINWIZARD || s == null) {
            return s;
        }
        try {
            return withPluginsRow(s, terminal);
        } catch (Throwable t) {
            return s; // withPluginsRow never throws; this only keeps that true
        }
    }

    /**
     * Screen 46 ({@code s}, just built by stock) with the Plugins row appended to its wizard; or, on any failure, a
     * stock screen 46 and the state FAILED. Already FAILED (also when another build of screen 46 failed while this one
     * ran): {@code s} without the SDK row, state untouched. Never throws.
     */
    Screen withPluginsRow(Screen s, int terminal) {
        MainWizardController w = null;
        MenuItemController row = null;
        try {
            if (SdkGlobals.mainMenu == SdkGlobals.MAIN_MENU_FAILED) {
                return s;
            }
            w = findWizard((AbstractWidget) s);
            if (w == null) {
                throw new IllegalStateException("screen 46 has no MainWizardController");
            }
            row = newRow(w);
            w.add(row); // row is recorded first: if add throws after inserting it, the fallback still removes it
            if ("screen".equals(SdkGlobals.uiForceFail)) {
                throw new IllegalStateException("forced failure: screen");
            }
            if (publish(row, w)) {
                return s;
            }
            return stockScreen(s, w, row, terminal); // another build failed meanwhile: FAILED stays, no row
        } catch (Throwable t) {
            failed(t);
            return stockScreen(s, w, row, terminal);
        }
    }

    /**
     * The Plugins row for {@code w}, styled as its last stock row (colour indices, glass-plate insets); not added yet.
     * Throws if {@code w} has no stock row. Called only inside {@link #withPluginsRow}'s catch of Throwable (and by
     * tests).
     */
    MenuItemController newRow(MainWizardController w) {
        MenuItemController ref = null;
        List children = w.getChildren();
        for (int i = 0; children != null && i < children.size(); i++) {
            Object o = children.get(i);
            if (o instanceof MenuItemController) {
                ref = (MenuItemController) o; // the last row (Settings) is the template
            }
        }
        if (ref == null) {
            throw new IllegalStateException("screen 46: no stock wizard row");
        }
        MenuItemController m = new MenuItemController();
        m.setType(HqcUiIds.DRAWER_ROW_TYPE_ACTION);
        m.setLabelId(HqcUiIds.SYS_TEXT_PLUGINS);
        int[] colors = ref.getColorIndices();
        if (colors != null) {
            m.setColorIndices((int[]) colors.clone());
        }
        m.setGlassplateInsetsTop(ref.getGlassplateInsetsTop());
        m.setGlassplateInsetsBottom(ref.getGlassplateInsetsBottom());
        m.setWidgetID(HqcUiIds.MAIN_MENU_WIDGET_ID);
        m.setInternalID(HqcUiIds.MAIN_MENU_INTERNAL_ID);
        m.setKeyHandler(new MainMenuKeyHandler());
        m.setVisible(!SdkGlobals.uiDown);
        return m;
    }

    /**
     * Re-checks FAILED and publishes the refs and ACTIVE as one critical section with {@link #failed}'s writes, so two
     * builds of screen 46 at once can neither overwrite FAILED with ACTIVE nor leave FAILED with refs set. False if
     * FAILED: nothing written. Field writes only, no I/O; called inside {@link #withPluginsRow}'s catch of Throwable.
     */
    private static boolean publish(MenuItemController row, MainWizardController w) {
        synchronized (SdkSystemScreenFactory.class) {
            if (SdkGlobals.mainMenu == SdkGlobals.MAIN_MENU_FAILED) {
                return false;
            }
            SdkGlobals.mainMenuRow = row;
            SdkGlobals.mainMenuWizard = w;
            SdkGlobals.mainMenu = SdkGlobals.MAIN_MENU_ACTIVE;
            return true;
        }
    }

    /**
     * FAILED for the boot: the refs first, so nobody sees FAILED with a row still published; the writes in the same
     * critical section as {@link #publish}, the log line outside it. Never throws.
     */
    private static void failed(Throwable cause) {
        try {
            synchronized (SdkSystemScreenFactory.class) {
                SdkGlobals.mainMenuRow = null;
                SdkGlobals.mainMenuWizard = null;
                SdkGlobals.mainMenu = SdkGlobals.MAIN_MENU_FAILED;
            }
            SdkGlobals.LOG.append("ERROR", SOURCE, "ui main menu failed, screen 46 stock for this boot: " + cause);
        } catch (Throwable ignored) {
            // the stock screen below is all that matters
        }
    }

    /**
     * A fresh stock screen 46. If even that throws, {@code built} with the SDK row (if one was added) taken out again:
     * never a throw into the HMI for the SDK's sake.
     */
    private Screen stockScreen(Screen built, MainWizardController w, MenuItemController row, int terminal) {
        try {
            return super.createScreen(HqcUiIds.SYS_SCREEN_MAINWIZARD, terminal);
        } catch (Throwable t) {
            try {
                if (w != null && row != null) {
                    w.remove(row);
                }
            } catch (Throwable ignored) {
                // best effort: the row's key handler never throws either
            }
            return built;
        }
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

    /** The Plugins row's label: the Car Plugins row's (drawer slot 0), else "Plugins". Never throws. */
    private static String pluginsLabel() {
        try {
            DrawerAssignment a = SdkGlobals.uiSlots;
            String l = a == null ? null : a.labelAt(0);
            return l == null || l.length() == 0 ? DEFAULT_LABEL : l;
        } catch (Throwable t) {
            return DEFAULT_LABEL;
        }
    }

    public String getText(int id) {
        try {
            if (id == HqcUiIds.SYS_TEXT_PLUGINS) {
                return pluginsLabel();
            }
        } catch (Throwable t) {
            // stock text below
        }
        return super.getText(id);
    }

    public String getText(int id, int viewSize) {
        try {
            if (id == HqcUiIds.SYS_TEXT_PLUGINS) {
                return pluginsLabel();
            }
        } catch (Throwable t) {
            // stock text below
        }
        return super.getText(id, viewSize);
    }

    public String getAltText(int id) {
        try {
            if (id == HqcUiIds.SYS_TEXT_PLUGINS) {
                return pluginsLabel();
            }
        } catch (Throwable t) {
            // stock text below
        }
        return super.getAltText(id);
    }
}
