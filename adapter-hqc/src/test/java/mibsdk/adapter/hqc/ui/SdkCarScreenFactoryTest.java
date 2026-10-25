package mibsdk.adapter.hqc.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;

import de.audi.atip.base.IFrameworkAccess;
import de.audi.atip.hmi.view.HMIView;
import de.audi.tghu.car.hmi.evohigh.CarScreenFactory;
import de.esolutions.hmi.widgets.audi.evo.widgets.DrawerController;
import de.esolutions.hmi.widgets.audi.evo.widgets.ContainerController;
import de.esolutions.hmi.widgets.audi.evo.widgets.menu.MenuItemController;
import java.util.ArrayList;
import java.lang.reflect.Proxy;
import java.util.List;
import mibsdk.adapter.hqc.SdkGlobals;
import mibsdk.platform.UiRow;
import org.junit.jupiter.api.Test;

/** Carried requirement 3: the SDK rows go in whole or not at all; the drawer is never left half-built. */
class SdkCarScreenFactoryTest {

    /** A drawer whose view mapping fails. */
    static final class FailingDrawer extends DrawerController {
        int setViewsCalls;

        FailingDrawer() {
            super(HqcUiIds.DRAWER_ID);
        }

        public void setViews(int[] ids, HMIView[][] views) {
            setViewsCalls++;
            throw new IllegalStateException("setViews failed");
        }
    }

    private static MenuItemController[] rows() {
        MenuItemController[] r = new MenuItemController[HqcUiIds.ROW_SLOTS];
        for (int i = 0; i < r.length; i++) {
            r[i] = new MenuItemController();
        }
        return r;
    }

    @SuppressWarnings("unchecked") // stock getChildren() is a raw List
    private static List<Object> children(ContainerController m) {
        return m.getChildren() == null ? new ArrayList<Object>() : new ArrayList<Object>(m.getChildren());
    }

    @Test
    void failedViewMappingRemovesTheRowsAgain() {
        FailingDrawer d = new FailingDrawer();
        ContainerController menu = new ContainerController();
        MenuItemController stock = new MenuItemController();
        menu.add(stock);
        List<Object> before = children(menu);

        SdkCarScreenFactory.install(d, menu, rows(), new int[] {1}, new HMIView[][] {new HMIView[0]});
        assertEquals(1, d.setViewsCalls, "children were added first, then the views mapped");
        assertEquals(before, children(menu), "rolled back: only the stock row remains");
        assertSame(stock, children(menu).get(0));
    }

    @Test
    void successAddsEveryRowThenMapsTheViews() {
        List<Integer> childCountAtSetViews = new ArrayList<Integer>();
        ContainerController menu = new ContainerController();
        DrawerController d = new DrawerController(HqcUiIds.DRAWER_ID) {
            public void setViews(int[] ids, HMIView[][] views) {
                childCountAtSetViews.add(menu.getChildren().size());
            }
        };
        SdkCarScreenFactory.install(d, menu, rows(), new int[] {1}, new HMIView[][] {new HMIView[0]});
        assertEquals(java.util.Collections.singletonList(HqcUiIds.ROW_SLOTS), childCountAtSetViews);
        assertEquals(HqcUiIds.ROW_SLOTS, children(menu).size());
    }

    /** A framework whose every call answers a default: a fake for interfaces, "" for strings, 0 / false. */
    private static IFrameworkAccess fw() {
        return (IFrameworkAccess) deep(IFrameworkAccess.class);
    }

    private static Object deep(Class<?> iface) {
        return Proxy.newProxyInstance(SdkCarScreenFactoryTest.class.getClassLoader(), new Class<?>[] {iface},
                (p, m, a) -> {
                    Class<?> r = m.getReturnType();
                    switch (m.getName()) {
                        case "hashCode":
                            return System.identityHashCode(p);
                        case "equals":
                            return p == a[0];
                        default:
                            break;
                    }
                    if (r == de.audi.atip.i18n.Language.class) {
                        return new de.audi.atip.i18n.Language(0, "en", "en", "English", 0, java.util.Locale.ENGLISH);
                    } else if (r == String.class) {
                        return "";
                    } else if (r.isInterface()) {
                        return deep(r);
                    } else if (r == boolean.class) {
                        return false;
                    } else if (r == int.class) {
                        return 0;
                    } else if (r == long.class) {
                        return 0L;
                    }
                    return null;
                });
    }

    /** A fresh id from the session table, for a test-unique owner. */
    private static int sessionId(String owner) {
        String rel = "../mibsdk/icons/" + owner + "/0123456789abcdef.png";
        return SdkGlobals.ICON_IDS.idFor(owner, "0123456789abcdef", HqcUiIds.INSTALL_ROOT + rel.substring(9), rel);
    }

    @Test
    void getImagePathServesSdkIdsAndDefersTheRest() {
        int id = sessionId("serve-test");
        SdkCarScreenFactory f = new SdkCarScreenFactory(fw());
        CarScreenFactory stock = new CarScreenFactory(fw());
        assertEquals("../mibsdk/icons/serve-test/0123456789abcdef.png", f.getImagePath(id, 0));
        assertEquals(stock.getImagePath(600023, 0), f.getImagePath(600023, 0), "stock id");
        assertEquals(stock.getImagePath(HqcUiIds.ICON_ID_LAST, 0), f.getImagePath(HqcUiIds.ICON_ID_LAST, 0),
                "SDK range, but no id given: stock");
        assertEquals(stock.getImagePath(HqcUiIds.ICON_ID_LAST + 1, 0), f.getImagePath(HqcUiIds.ICON_ID_LAST + 1, 0));
    }

    private static int lines(String needle) {
        int n = 0;
        for (String l : SdkGlobals.LOG.snapshot()) {
            if (l.contains(needle)) {
                n++;
            }
        }
        return n;
    }

    @Test
    void firstServeOfEachIdIsLoggedOnce() {
        int id = sessionId("log-test");
        SdkCarScreenFactory f = new SdkCarScreenFactory(fw());
        String line = "getImagePath " + id + " -> ../mibsdk/icons/log-test/0123456789abcdef.png";
        f.getImagePath(id, 0);
        f.getImagePath(id, 0);
        new SdkCarScreenFactory(fw()).getImagePath(id, 1);
        f.getImagePath(600023, 0);
        assertEquals(1, lines(line), "once a session: " + SdkGlobals.LOG.snapshot());
        assertTrue(lines("getImagePath 600023") == 0, "stock ids are not logged");
    }

    /** A drawer built after the icons were assigned shows them at once, and HmiWidgets gets its rows. */
    @Test
    void drawerRowsTakeTheirIconsWhenBuilt() {
        List<UiRow> carRows = new ArrayList<UiRow>();
        carRows.add(new UiRow("mibsdk.plugins", "Plugins", "mibsdk.plugins"));
        carRows.add(new UiRow("a", "A", "pa", mibsdk.platform.UiIcon.file("build-test",
                HqcUiIds.INSTALL_ROOT + "/icons/build-test/0123456789abcdef.png", "0123456789abcdef", 72, 72)));
        DrawerAssignment before = SdkGlobals.uiSlots;
        try {
            SdkGlobals.uiSlots = DrawerSlots.assign(DrawerAssignment.EMPTY, carRows, HqcUiIds.PLUGIN_ROW_SLOTS,
                    SdkGlobals.ICON_IDS, BuiltinIcons.NONE);
            int[] icon = SdkGlobals.uiSlots.iconAt(1);
            DrawerController d = new DrawerController(HqcUiIds.DRAWER_ID);
            d.setDrawerMain(new ContainerController());
            d.setViews(new int[0], new HMIView[0][]);
            SdkCarScreenFactory.sdkRows = null;
            new SdkCarScreenFactory(fw()).addRows(d);
            MenuItemController[] rows = SdkCarScreenFactory.sdkRows;
            assertEquals(HqcUiIds.ROW_SLOTS, rows.length);
            assertArrayEquals(icon, rows[1].getBitmapIndices());
            assertNull(rows[0].getBitmapIndices(), "no icon: no bitmaps");
        } finally {
            SdkGlobals.uiSlots = before;
            SdkCarScreenFactory.sdkRows = null;
        }
    }
}
