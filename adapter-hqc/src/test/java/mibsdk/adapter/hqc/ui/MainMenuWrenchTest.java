package mibsdk.adapter.hqc.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.audi.atip.base.IFrameworkAccess;
import de.esolutions.hmi.widgets.audi.base.InitializationContext;
import de.esolutions.hmi.widgets.audi.base.Layout;
import de.esolutions.hmi.widgets.audi.evo.high.HMITerminalImplMIB2Std;
import de.esolutions.hmi.widgets.audi.evo.high.LayoutMIB2HighScale;
import de.esolutions.hmi.widgets.audi.evo.widgets.MainWizardController;
import java.util.ArrayList;
import java.util.List;
import mibsdk.adapter.hqc.SdkGlobals;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The wrench on the Plugins row: {@code mapping[11] := {16, 9}} in the layout's main-wizard icon/decorator mapping,
 * read through a connected wizard (spike C, Boot B: the {@code wrench} probe). Only a stock entry is overwritten.
 */
class MainMenuWrenchTest {

    private int[][] stock;
    private int[] stockEntry11;

    @BeforeEach
    void setUp() {
        HostWidgets.init();
        MainMenuWrench.forgetForTests();
        SdkGlobals.mainMenu = SdkGlobals.MAIN_MENU_ACTIVE;
        stock = new LayoutMIB2HighScale().getMainWizardIconDecoratorMapping();
        stockEntry11 = stock[11];
    }

    @AfterEach
    void restore() {
        stock[11] = stockEntry11; // the layout's array is static: shared by every test in this JVM
        MainMenuWrench.forgetForTests();
        SdkGlobals.mainMenu = SdkGlobals.MAIN_MENU_OFF;
    }

    /** A wizard as the framework connects it: its init context's terminal carries {@code layout}. */
    private static MainWizardController connected(Layout layout) {
        IFrameworkAccess fw = (IFrameworkAccess) SdkSystemActivatorTest.deep(MainMenuWrenchTest.class.getClassLoader(),
                IFrameworkAccess.class);
        HMITerminalImplMIB2Std t = new HMITerminalImplMIB2Std(0, null, fw);
        t.setLayout(layout);
        MainWizardController w = new MainWizardController();
        w.setInitContext(new InitializationContext(t));
        return w;
    }

    private static List<String> warnings() {
        List<String> out = new ArrayList<String>();
        for (String l : SdkGlobals.LOG.snapshot()) {
            if (l.contains("wrench")) {
                out.add(l);
            }
        }
        return out;
    }

    @Test
    void setsTheWrenchOnAStockMapping() {
        assertArrayEquals(new int[] {11, 11}, stock[11], "stock entry 11: night vision");
        assertEquals(20, stock.length);
        MainWizardController w = connected(new LayoutMIB2HighScale());
        assertTrue(MainMenuWrench.apply(w));
        assertArrayEquals(new int[] {16, 9}, new LayoutMIB2HighScale().getMainWizardIconDecoratorMapping()[11]);
        assertArrayEquals(new int[] {12, 3}, stock[12], "no other entry changes");
        assertArrayEquals(new int[] {10, 10}, stock[10]);
        assertTrue(MainMenuWrench.apply(w), "already the wrench: fine");
        assertArrayEquals(new int[] {16, 9}, stock[11]);
        assertEquals(SdkGlobals.MAIN_MENU_ACTIVE, SdkGlobals.mainMenu);
    }

    @Test
    void unexpectedMappingIsLeftAlone() {
        int before = warnings().size();
        int[] odd = {3, 3};
        stock[11] = odd;
        MainWizardController w = connected(new LayoutMIB2HighScale());
        assertFalse(MainMenuWrench.apply(w));
        assertFalse(MainMenuWrench.apply(w));
        assertSame(odd, stock[11]);
        assertArrayEquals(new int[] {3, 3}, odd);
        assertEquals(before + 1, warnings().size(), "logged once");
        assertEquals(SdkGlobals.MAIN_MENU_ACTIVE, SdkGlobals.mainMenu);
    }

    @Test
    void shortMappingIsLeftAlone() {
        int[][] shortMap = new int[11][];
        for (int i = 0; i < shortMap.length; i++) {
            shortMap[i] = new int[] {i, i};
        }
        Layout layout = (Layout) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {Layout.class}, (p, m, a) -> m.getName().equals("getMainWizardIconDecoratorMapping")
                        ? shortMap : null);
        assertFalse(MainMenuWrench.apply(connected(layout)));
        assertEquals(11, shortMap.length);
    }

    @Test
    void wizardWithoutALayoutReturnsFalseAndLeavesTheStateAlone() {
        int before = warnings().size();
        assertFalse(MainMenuWrench.apply(new MainWizardController()), "not connected: no init context");
        assertFalse(MainMenuWrench.apply(connected(null)), "a terminal without a layout");
        assertFalse(MainMenuWrench.apply(null));
        assertFalse(MainMenuWrench.apply("not a wizard"));
        assertEquals(SdkGlobals.MAIN_MENU_ACTIVE, SdkGlobals.mainMenu, "the row works without the wrench");
        assertArrayEquals(new int[] {11, 11}, stock[11]);
        assertEquals(before + 1, warnings().size(), "logged once");
    }
}
