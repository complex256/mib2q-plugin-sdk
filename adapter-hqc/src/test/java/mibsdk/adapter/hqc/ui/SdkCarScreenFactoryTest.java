package mibsdk.adapter.hqc.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;

import de.audi.atip.base.IFrameworkAccess;
import de.audi.atip.hmi.view.IPartialPopupController;
import de.audi.atip.hmi.view.HMIView;
import de.audi.tghu.car.hmi.evohigh.CarScreenFactory;
import de.esolutions.hmi.widgets.audi.evo.widgets.DrawerController;
import de.esolutions.hmi.widgets.audi.evo.widgets.ContainerController;
import de.esolutions.hmi.widgets.audi.evo.widgets.PartialPopupController;
import de.esolutions.hmi.widgets.audi.evo.widgets.PartialPopupStub;
import de.esolutions.hmi.widgets.audi.evo.high.widgets.LabelRendererHigh;
import de.esolutions.hmi.widgets.audi.evo.widgets.IconController;
import de.esolutions.hmi.widgets.audi.evo.widgets.LabelController;
import de.esolutions.hmi.widgets.audi.evo.widgets.menu.MenuItemController;
import java.util.ArrayList;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.List;
import mibsdk.adapter.hqc.SdkGlobals;
import mibsdk.platform.UiIcon;
import mibsdk.platform.UiRow;
import mibsdk.platform.UiStatusPart;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

    @BeforeEach
    @AfterEach
    void resetPopup() {
        HostWidgets.init();
        SdkGlobals.statusPopup = 0;
        SdkStatusPopup.current = null;
        SdkStatusPopup.wanted = StatusLayout.EMPTY;
    }

    /**
     * The System fonts the popup takes: cached (as once the System bundle loaded them), or not, so the next lookup
     * goes to the HMI terminal.
     */
    private static void systemFonts(boolean cached) throws Exception {
        Class<?> ff = de.audi.tghu.system.hmi.evohigh.FontFactory.class;
        java.lang.reflect.Field arrays = ff.getDeclaredField("fontArrays");
        arrays.setAccessible(true);
        Object[] a = (Object[]) arrays.get(null);
        java.util.Arrays.fill(a, cached ? new de.esolutions.hmi.widgets.audi.base.eal.IWrappedFont[0] : null);
        java.lang.reflect.Field loader = ff.getDeclaredField("fontLoader");
        loader.setAccessible(true);
        loader.set(null, null);
    }

    /** A factory whose popup build throws: no fonts cached, and the HMI terminal is gone. */
    private static SdkCarScreenFactory failingBuild() throws Exception {
        systemFonts(false);
        IFrameworkAccess gone = (IFrameworkAccess) Proxy.newProxyInstance(
                SdkCarScreenFactoryTest.class.getClassLoader(), new Class<?>[] {IFrameworkAccess.class}, (p, m, a) -> {
                    if (m.getName().equals("getHMIService")) {
                        return Proxy.newProxyInstance(SdkCarScreenFactoryTest.class.getClassLoader(),
                                new Class<?>[] {m.getReturnType()}, (q, n, b) -> {
                                    throw new IllegalStateException("no terminal");
                                });
                    }
                    return m.invoke(fw(), a);
                });
        return new SdkCarScreenFactory(gone);
    }

    private static PartialPopupStub sdkStub(IPartialPopupController[] stubs) {
        PartialPopupStub found = null;
        for (int i = 0; stubs != null && i < stubs.length; i++) {
            if (stubs[i] instanceof PartialPopupStub
                    && ((PartialPopupStub) stubs[i]).getID() == HqcUiIds.POPUP_SDK_STATUS) {
                assertNull(found, "one stub");
                found = (PartialPopupStub) stubs[i];
            }
        }
        return found;
    }

    private static int length(IPartialPopupController[] a) {
        return a == null ? 0 : a.length;
    }

    @Test
    void stubsAddPopup609600InSlot7AndBuildFailureMarksFailed() throws Exception {
        IPartialPopupController[] stock = new CarScreenFactory(fw()).getPartialPopupStubs(0);
        IPartialPopupController[] stubs = failingBuild().getPartialPopupStubs(0);
        assertEquals(length(stock) + 1, stubs.length, "the stock stubs, then the SDK's");
        PartialPopupStub stub = sdkStub(stubs);
        assertSame(stub, stubs[stubs.length - 1]);
        assertEquals(HqcUiIds.POPUP_SDK_SLOT, stub.getSlot());
        assertEquals(7, stub.getSlot());
        assertEquals(HqcUiIds.POPUP_SDK_ZPM_SLOT, stub.getZpmSlot());
        assertEquals(HqcUiIds.POPUP_SDK_PRIORITY, stub.getZpmPriority());
        assertEquals(HqcUiIds.POPUP_SDK_TYPE, stub.getType());
        assertEquals(HqcUiIds.POPUP_SDK_STUB_KIND, stub.getStyle());
        assertEquals(HqcUiIds.STATUS_POPUP_VISIBLE_MODEL, stub.getModelID());
        assertEquals(1, SdkGlobals.statusPopup, "registered");

        assertNull(failingBuild().getPartialPopup(HqcUiIds.POPUP_SDK_STATUS, 0));
        assertEquals(-1, SdkGlobals.statusPopup, "failed");
        assertNull(SdkStatusPopup.current);
    }

    @Test
    void popupBuildsInSlot7AndMarksBuilt() throws Exception {
        systemFonts(true);
        SdkCarScreenFactory f = new SdkCarScreenFactory(fw());
        f.getPartialPopupStubs(0);
        IPartialPopupController p = f.getPartialPopup(HqcUiIds.POPUP_SDK_STATUS, 0);
        assertTrue(p instanceof PartialPopupController, "built: " + SdkGlobals.LOG.snapshot());
        PartialPopupController c = (PartialPopupController) p;
        assertEquals(HqcUiIds.POPUP_SDK_STATUS, c.getID());
        assertEquals(HqcUiIds.POPUP_SDK_SLOT, c.getSlot());
        assertEquals(HqcUiIds.POPUP_SDK_ZPM_SLOT, c.getZpmSlot());
        assertEquals(HqcUiIds.STATUS_POPUP_VISIBLE_MODEL, c.getModelID());
        assertArrayEquals(new int[] {HqcUiIds.STATUS_POPUP_VISIBLE_MODEL, HqcUiIds.STATUS_LABEL_MODEL_0,
            HqcUiIds.STATUS_LABEL_MODEL_0 + 1, HqcUiIds.STATUS_LABEL_MODEL_0 + 2, HqcUiIds.STATUS_LABEL_MODEL_0 + 3,
            HqcUiIds.STATUS_LABEL_MODEL_0 + 4, HqcUiIds.STATUS_LABEL_MODEL_0 + 5, HqcUiIds.STATUS_LABEL_MODEL_0 + 6,
            HqcUiIds.STATUS_FILE_ICON_MODEL_1, HqcUiIds.STATUS_FILE_ICON_MODEL_1 + 1,
            HqcUiIds.STATUS_FILE_ICON_MODEL_1 + 2, HqcUiIds.STATUS_FILE_ICON_MODEL_1 + 3,
            HqcUiIds.STATUS_FILE_ICON_MODEL_1 + 4, HqcUiIds.STATUS_FILE_ICON_MODEL_1 + 5}, c.getViewIDs());
        assertEquals(2, SdkGlobals.statusPopup, "built");
        assertTrue(SdkStatusPopup.current != null);
        boolean stockNull = new CarScreenFactory(fw()).getPartialPopup(600000, 0) == null;
        assertEquals(stockNull, f.getPartialPopup(600000, 0) == null, "other ids: stock");
    }

    @Test
    void failedStaysFailedWhenStubsAreCollectedAgain() throws Exception {
        SdkCarScreenFactory ok = new SdkCarScreenFactory(fw());
        ok.getPartialPopupStubs(0);
        failingBuild().getPartialPopup(HqcUiIds.POPUP_SDK_STATUS, 0);
        assertEquals(-1, SdkGlobals.statusPopup);

        IPartialPopupController[] again = ok.getPartialPopupStubs(0);
        assertNull(sdkStub(again), "no stub once failed");
        assertEquals(length(new CarScreenFactory(fw()).getPartialPopupStubs(0)), length(again));
        assertEquals(-1, SdkGlobals.statusPopup, "a later collection never moves it back to 1");
        systemFonts(true); // a build would succeed now
        assertNull(ok.getPartialPopup(HqcUiIds.POPUP_SDK_STATUS, 0), "nor a later build to 2");
        assertEquals(-1, SdkGlobals.statusPopup);
    }

    @TempDir
    java.io.File iconDir;

    /**
     * Bench R1 (Task 12): with {@code setAlignment(3, …)} (ALIGN_RIGHT in the stock {@code Alignment}) every cell's
     * text ended at the popup's right padding, so the items were drawn on top of each other. The layout places each
     * label from its left edge.
     */
    @Test
    void popupLabelsAreLeftAligned() throws Exception {
        systemFonts(true);
        SdkCarScreenFactory f = new SdkCarScreenFactory(fw());
        f.getPartialPopupStubs(0);
        f.getPartialPopup(HqcUiIds.POPUP_SDK_STATUS, 0);
        LabelController[] labels = (LabelController[]) popupField("labels");
        assertEquals(HqcUiIds.STATUS_CELLS + 1, labels.length);
        Field hAlign = LabelRendererHigh.class.getDeclaredField("hAlign");
        hAlign.setAccessible(true);
        for (int i = 0; i < labels.length; i++) {
            assertEquals(1, hAlign.getInt(labels[i].getRenderer()), "label " + i + ": ALIGN_LEFT");
        }
    }

    /**
     * Bench R1 (Task 12): {@code setBitmaps} on a built popup's id icon only stores the array, so the new icon was
     * drawn at the next screen change, not live. A changed cell's icon is marked dirty; an unchanged one is not.
     */
    @Test
    void iconChangeOnABuiltPopupMarksTheIconDirty() throws Exception {
        systemFonts(true);
        SdkCarScreenFactory f = new SdkCarScreenFactory(fw());
        f.getPartialPopupStubs(0);
        f.getPartialPopup(HqcUiIds.POPUP_SDK_STATUS, 0);
        IconController[] ids = (IconController[]) popupField("idIcons");
        for (IconController i : ids) {
            i.setCompositesDirty(false); // as after the first paint
        }
        List<UiStatusPart> parts = new ArrayList<UiStatusPart>();
        parts.add(new UiStatusPart("TS up", UiIcon.stock("dot"), false));
        StatusLayout l = StatusLayout.of(parts, new IconIds(HqcUiIds.ICON_ID_FIRST, HqcUiIds.ICON_ID_LAST),
                StatusLayoutTest.builtins(iconDir));
        SdkStatusPopup.current.icons(l, StatusLayout.EMPTY);
        assertArrayEquals(new int[] {HqcUiIds.ICON_ID_FIRST}, ids[0].getBitmapIndices());
        assertTrue(ids[0].getRenderer().areCompositesDirty(), "the changed icon is redrawn");
        assertFalse(ids[1].getRenderer().areCompositesDirty(), "an unchanged cell is left alone");
        ids[0].setCompositesDirty(false);
        SdkStatusPopup.current.icons(StatusLayout.EMPTY, l);
        assertTrue(ids[0].getRenderer().areCompositesDirty(), "a removed icon is redrawn too");
    }

    /** A popup rebuilt while a cell wants its own colours starts with them, on both of that cell's icons. */
    @Test
    void rebuiltPopupReappliesOwnColours() throws Exception {
        systemFonts(true);
        List<UiStatusPart> parts = new ArrayList<UiStatusPart>();
        parts.add(new UiStatusPart("TS up", UiIcon.file("hello", HqcUiIds.INSTALL_ROOT
                + "/icons/hello/aaaa000000000000.png", "aaaa000000000000", 20, 20).withOwnColours(true), false));
        parts.add(new UiStatusPart("MQ", UiIcon.stock("dot"), false));
        SdkStatusPopup.wanted = StatusLayout.of(parts, new IconIds(HqcUiIds.ICON_ID_FIRST, HqcUiIds.ICON_ID_LAST),
                StatusLayoutTest.builtins(iconDir));
        SdkCarScreenFactory f = new SdkCarScreenFactory(fw());
        f.getPartialPopupStubs(0);
        assertTrue(f.getPartialPopup(HqcUiIds.POPUP_SDK_STATUS, 0) instanceof PartialPopupController,
                "built: " + SdkGlobals.LOG.snapshot());
        IconController[] ids = (IconController[]) popupField("idIcons");
        IconController[] files = (IconController[]) popupField("fileIcons");
        assertArrayEquals(new int[] {1, 0, 4, 0}, ids[0].getColorIndices());
        assertArrayEquals(new int[] {1, 0, 4, 0}, files[0].getColorIndices());
        for (int i = 1; i < ids.length; i++) {
            assertNull(ids[i].getColorIndices(), "cell " + i + ": tinted");
            assertNull(files[i].getColorIndices(), "cell " + i + ": tinted");
        }
    }

    private static Object popupField(String name) throws Exception {
        Field fd = SdkStatusPopup.class.getDeclaredField(name);
        fd.setAccessible(true);
        return fd.get(SdkStatusPopup.current);
    }
}
