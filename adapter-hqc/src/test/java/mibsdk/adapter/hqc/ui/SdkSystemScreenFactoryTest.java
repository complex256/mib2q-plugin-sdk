package mibsdk.adapter.hqc.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.audi.atip.base.IFrameworkAccess;
import de.audi.atip.hmi.view.Screen;
import de.esolutions.hmi.widgets.audi.base.AbstractWidget;
import de.esolutions.hmi.widgets.audi.evo.ScreenWidgetEVO;
import de.esolutions.hmi.widgets.audi.evo.widgets.ContainerController;
import de.esolutions.hmi.widgets.audi.evo.widgets.MainWizardController;
import de.esolutions.hmi.widgets.audi.evo.widgets.menu.MenuItemController;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import mibsdk.adapter.hqc.SdkGlobals;
import mibsdk.platform.UiRow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Screen 46 with the SDK's Plugins row. Stock {@code SystemScreenBag2} (which builds screen 46) is one of the classes
 * the host repair cannot load, so these tests build a stock-shaped screen on the host from stock widgets (a
 * MainWizardController with stock MenuItemController rows, as {@code SystemScreenBag2.mAINWIZARD} adds them) and run
 * the factory's SDK step, {@code withPluginsRow}, on it. For the same reason the stock rebuild after a failure
 * ({@code super.createScreen(46, t)}) throws on the host; the step then takes its row back out of the built screen,
 * which is what these tests see.
 */
class SdkSystemScreenFactoryTest {

    private static final int STOCK_ROWS = 3;

    @BeforeEach
    void reset() {
        HostWidgets.init();
        cleanUp();
        SdkGlobals.mainMenu = SdkGlobals.MAIN_MENU_PENDING; // what the activator sets with this factory
    }

    @AfterEach
    void cleanUp() {
        SdkGlobals.uiForceFail = null;
        SdkGlobals.uiDown = false;
        SdkGlobals.uiSlots = DrawerAssignment.EMPTY;
        SdkGlobals.mainMenu = SdkGlobals.MAIN_MENU_OFF;
        SdkGlobals.mainMenuRow = null;
        SdkGlobals.mainMenuWizard = null;
    }

    private static SdkSystemScreenFactory factory() {
        return new SdkSystemScreenFactory(
                (IFrameworkAccess) SdkSystemActivatorTest.deep(SdkSystemScreenFactoryTest.class.getClassLoader(),
                        IFrameworkAccess.class));
    }

    private static MenuItemController stockRow(int widgetId, int label, int[] colors, int internalId) {
        MenuItemController m = new MenuItemController();
        m.setLabelId(label);
        m.setColorIndices(colors);
        m.setGlassplateInsetsBottom(6);
        m.setGlassplateInsetsTop(7);
        m.setInternalID(internalId);
        m.setType(2);
        m.setWidgetID(widgetId);
        return m;
    }

    /** Screen 46 as stock builds it, minus renderers: the wizard (with stock rows) in a ScreenWidgetEVO. */
    private static ScreenWidgetEVO stockScreen46() {
        MainWizardController w = new MainWizardController();
        w.add(stockRow(0, 244, new int[] {16, 0, 4, 0}, 2));
        w.add(stockRow(8, 250, new int[] {17, 0, 4, 0}, 3));
        w.add(stockRow(19, 3200, new int[] {18, 0, 4, 0}, 640)); // the last row (Settings) is the template
        ScreenWidgetEVO s = new ScreenWidgetEVO(46);
        s.add(new ContainerController());
        s.add(w);
        return s;
    }

    private static List<MainWizardController> wizards(AbstractWidget w) {
        List<MainWizardController> out = new ArrayList<MainWizardController>();
        if (w instanceof MainWizardController) {
            out.add((MainWizardController) w);
        }
        List<?> c = w.getChildren();
        for (int i = 0; c != null && i < c.size(); i++) {
            if (c.get(i) instanceof AbstractWidget) {
                out.addAll(wizards((AbstractWidget) c.get(i)));
            }
        }
        return out;
    }

    private static List<MenuItemController> rows(MainWizardController w) {
        List<MenuItemController> out = new ArrayList<MenuItemController>();
        for (Object o : w.getChildren()) {
            out.add((MenuItemController) o);
        }
        return out;
    }

    private static boolean hasSdkRow(MainWizardController w) {
        for (MenuItemController m : rows(w)) {
            if (m.getInternalID() == HqcUiIds.MAIN_MENU_INTERNAL_ID) {
                return true;
            }
        }
        return false;
    }

    @Test
    void appendsOnePluginsRowWithWidget11() {
        ScreenWidgetEVO s = stockScreen46();
        MainWizardController w = wizards(s).get(0);
        Screen out = factory().withPluginsRow(s, 0);

        assertSame(s, out, "the built screen, with the row");
        List<MenuItemController> rows = rows(w);
        assertEquals(STOCK_ROWS + 1, rows.size());
        MenuItemController last = rows.get(rows.size() - 1);
        assertEquals(11, last.getWidgetID());
        assertEquals(90000, last.getLabelId());
        assertEquals(90100, last.getInternalID());
        assertEquals(2, last.getType());
        assertTrue(last.getKeyHandler() instanceof MainMenuKeyHandler);
        assertTrue(last.isVisible());
        MenuItemController settings = rows.get(STOCK_ROWS - 1);
        assertArrayEquals(settings.getColorIndices(), last.getColorIndices(), "colours of the last stock row");
        assertFalse(settings.getColorIndices() == last.getColorIndices(), "a copy");
        assertEquals(settings.getGlassplateInsetsTop(), last.getGlassplateInsetsTop());
        assertEquals(settings.getGlassplateInsetsBottom(), last.getGlassplateInsetsBottom());

        assertEquals(SdkGlobals.MAIN_MENU_ACTIVE, SdkGlobals.mainMenu);
        assertSame(last, SdkGlobals.mainMenuRow);
        assertSame(w, SdkGlobals.mainMenuWizard);
        assertFalse(SdkGlobals.drawerPluginsRowWanted());
    }

    @Test
    void rowStartsHiddenWhileTheUiIsDown() {
        SdkGlobals.uiDown = true;
        ScreenWidgetEVO s = stockScreen46();
        factory().withPluginsRow(s, 0);
        List<MenuItemController> rows = rows(wizards(s).get(0));
        assertFalse(rows.get(rows.size() - 1).isVisible());
        assertEquals(SdkGlobals.MAIN_MENU_ACTIVE, SdkGlobals.mainMenu);
    }

    @Test
    void eachBuildOfScreen46GetsItsOwnRowAndTheRefsFollowTheNewest() {
        SdkSystemScreenFactory f = factory();
        ScreenWidgetEVO first = stockScreen46();
        f.withPluginsRow(first, 0);
        ScreenWidgetEVO second = stockScreen46();
        f.withPluginsRow(second, 0);
        MainWizardController w2 = wizards(second).get(0);
        assertEquals(STOCK_ROWS + 1, rows(w2).size());
        assertSame(w2, SdkGlobals.mainMenuWizard);
        assertSame(rows(w2).get(STOCK_ROWS), SdkGlobals.mainMenuRow);
        assertEquals(SdkGlobals.MAIN_MENU_ACTIVE, SdkGlobals.mainMenu);
    }

    @Test
    void forcedScreenFailureGivesAStockScreenAndFailedState() {
        SdkGlobals.uiForceFail = new String("screen"); // compared with equals, not ==
        SdkSystemScreenFactory f = factory();
        ScreenWidgetEVO s = stockScreen46();
        Screen out = f.withPluginsRow(s, 0);

        List<MainWizardController> ws = wizards((AbstractWidget) out);
        assertEquals(1, ws.size(), "exactly one wizard");
        assertFalse(hasSdkRow(ws.get(0)), "no SDK row");
        assertEquals(STOCK_ROWS, rows(ws.get(0)).size());
        assertEquals(SdkGlobals.MAIN_MENU_FAILED, SdkGlobals.mainMenu);
        assertNull(SdkGlobals.mainMenuRow);
        assertNull(SdkGlobals.mainMenuWizard);
        assertTrue(SdkGlobals.drawerPluginsRowWanted(), "the Car drawer's Plugins row comes back");

        // FAILED is sticky for the boot: later builds of screen 46 stay stock, even with the switch gone.
        SdkGlobals.uiForceFail = null;
        ScreenWidgetEVO again = stockScreen46();
        assertSame(again, f.withPluginsRow(again, 0));
        assertFalse(hasSdkRow(wizards(again).get(0)));
        assertEquals(SdkGlobals.MAIN_MENU_FAILED, SdkGlobals.mainMenu);
        assertNull(SdkGlobals.mainMenuWizard);
    }

    @Test
    void failureAfterAnActiveBuildClearsTheRefs() {
        SdkSystemScreenFactory f = factory();
        f.withPluginsRow(stockScreen46(), 0);
        assertEquals(SdkGlobals.MAIN_MENU_ACTIVE, SdkGlobals.mainMenu);

        ScreenWidgetEVO noWizard = new ScreenWidgetEVO(46); // a screen 46 without a wizard: the SDK step throws
        noWizard.add(new ContainerController());
        assertSame(noWizard, f.withPluginsRow(noWizard, 0), "untouched, and never a throw into the HMI");
        assertEquals(SdkGlobals.MAIN_MENU_FAILED, SdkGlobals.mainMenu);
        assertNull(SdkGlobals.mainMenuRow);
        assertNull(SdkGlobals.mainMenuWizard);
    }

    /** A wizard whose {@code add} runs {@code after} once the child is in (the hook a second build or a throw uses). */
    private static final class HookedWizard extends MainWizardController {
        Runnable after;

        public void add(AbstractWidget child) {
            super.add(child);
            Runnable r = after;
            after = null;
            if (r != null) {
                r.run();
            }
        }
    }

    private static ScreenWidgetEVO screen46With(MainWizardController w) {
        w.add(stockRow(0, 244, new int[] {16, 0, 4, 0}, 2));
        w.add(stockRow(8, 250, new int[] {17, 0, 4, 0}, 3));
        w.add(stockRow(19, 3200, new int[] {18, 0, 4, 0}, 640));
        ScreenWidgetEVO s = new ScreenWidgetEVO(46);
        s.add(new ContainerController());
        s.add(w);
        return s;
    }

    /**
     * Two builds of screen 46 at once: the second fails while the first is between its FAILED check and its publish.
     * FAILED must win (sticky), with no refs and no SDK row; the first build must not overwrite it with ACTIVE.
     */
    @Test
    void aFailureDuringAnotherBuildIsNeverOverwrittenWithActive() {
        final SdkSystemScreenFactory f = factory();
        HookedWizard w = new HookedWizard();
        ScreenWidgetEVO s = screen46With(w);
        w.after = new Runnable() {
            public void run() {
                ScreenWidgetEVO noWizard = new ScreenWidgetEVO(46); // the concurrent build fails
                noWizard.add(new ContainerController());
                f.withPluginsRow(noWizard, 0);
            }
        };
        Screen out = f.withPluginsRow(s, 0);

        assertEquals(SdkGlobals.MAIN_MENU_FAILED, SdkGlobals.mainMenu, "FAILED is sticky, never overwritten");
        assertNull(SdkGlobals.mainMenuRow);
        assertNull(SdkGlobals.mainMenuWizard);
        assertFalse(hasSdkRow(wizards((AbstractWidget) out).get(0)), "no SDK row in what the HMI gets");
    }

    /** {@code w.add} throws after the child went in: the fallback still takes the row back out. */
    @Test
    void aThrowFromAddAfterTheInsertStillRemovesTheRow() {
        HookedWizard w = new HookedWizard();
        ScreenWidgetEVO s = screen46With(w);
        w.after = new Runnable() {
            public void run() {
                throw new IllegalStateException("add threw after inserting");
            }
        };
        Screen out = factory().withPluginsRow(s, 0);

        assertSame(s, out, "the stock rebuild throws on the host: the built screen, row taken out");
        assertFalse(hasSdkRow(w), "the inserted row is removed");
        assertEquals(STOCK_ROWS, rows(w).size());
        assertEquals(SdkGlobals.MAIN_MENU_FAILED, SdkGlobals.mainMenu);
        assertNull(SdkGlobals.mainMenuRow);
        assertNull(SdkGlobals.mainMenuWizard);
    }

    @Test
    void otherScreensAndTextsAreStock() {
        SdkSystemScreenFactory f = factory();
        assertEquals("", f.getText(244), "stock string resources (none on the host)");
        assertEquals("", f.getAltText(244));
        assertEquals("", f.getText(244, 1));
    }

    @Test
    void pluginsLabelComesFromSlot0AndDefaultsToPlugins() {
        SdkSystemScreenFactory f = factory();
        assertEquals("Plugins", f.getText(90000));
        assertEquals("Plugins", f.getAltText(90000));
        assertEquals("Plugins", f.getText(90000, 0));
        assertEquals("Plugins", f.getText(90000, 1));

        SdkGlobals.uiSlots = DrawerSlots.assign(DrawerAssignment.EMPTY,
                Collections.singletonList(new UiRow("mibsdk/plugins", "Plugins (safe mode)", "mibsdk/plugins")),
                HqcUiIds.PLUGIN_ROW_SLOTS, null, null);
        assertEquals("Plugins (safe mode)", f.getText(90000));
        assertEquals("Plugins (safe mode)", f.getAltText(90000));
        assertEquals("Plugins (safe mode)", f.getText(90000, 1));

        SdkGlobals.uiSlots = null; // a broken reference never throws into the HMI
        assertEquals("Plugins", f.getText(90000));
    }

    /** MainMenuKeyHandler: SELECT (key 17) only drops the Plugins row's press into the ring; other keys do nothing. */
    @Test
    void selectOnThePluginsRowQueuesOnePressForMibsdkPlugins() throws InterruptedException {
        while (SdkGlobals.UI.poll(0) != null) {
            // drain
        }
        SdkGlobals.uiSlots = DrawerSlots.assign(DrawerAssignment.EMPTY,
                Collections.singletonList(new UiRow("mibsdk/plugins", "Plugins", "mibsdk/plugins")),
                HqcUiIds.PLUGIN_ROW_SLOTS, null, null);
        long[] now = {1000};
        try {
            UiInputs.forgetForTests();
            UiInputs.clock = () -> now[0];
            MainMenuKeyHandler h = new MainMenuKeyHandler();
            de.audi.atip.hmi.event.KeyEvent other = new de.audi.atip.hmi.event.KeyEvent(null, 0, 15, 0);
            h.keyPressed(null, other);
            assertFalse(other.isConsumed());
            assertNull(SdkGlobals.UI.poll(0));
            assertEquals(Long.MAX_VALUE, UiInputs.sinceLastPress(), "no press recorded");

            de.audi.atip.hmi.event.KeyEvent select = new de.audi.atip.hmi.event.KeyEvent(null, 0, 17, 0);
            h.keyPressed(null, select);
            assertTrue(select.isConsumed());
            mibsdk.platform.UiInput in = SdkGlobals.UI.poll(0);
            assertEquals(mibsdk.platform.UiInput.ROW_SELECTED, in.kind());
            assertEquals("mibsdk/plugins", in.pageKey());
            assertNull(SdkGlobals.UI.poll(0), "one press");
            now[0] += 5;
            assertEquals(5, UiInputs.sinceLastPress(), "the press is recorded");

            h.keyReleased(null, select);
            h.keyPressed(null, null);
            SdkGlobals.uiSlots = null;
            h.keyPressed(null, new de.audi.atip.hmi.event.KeyEvent(null, 0, 17, 0)); // never throws
            assertNull(SdkGlobals.UI.poll(0));
        } finally {
            UiInputs.forgetForTests();
        }
    }
}
