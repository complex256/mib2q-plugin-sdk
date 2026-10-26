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
    static IFrameworkAccess fw() {
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
        SdkGlobals.toastPopup = 0;
        SdkToastPopup.current = null;
        SdkToastPopup.wanted = SdkToastPopup.Part.NONE;
        SdkToastPopup.up = false;
    }

    /**
     * The System fonts the popup takes: cached (as once the System bundle loaded them), or not, so the next lookup
     * goes to the HMI terminal.
     */
    static void systemFonts(boolean cached) throws Exception {
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
    static SdkCarScreenFactory failingBuild() throws Exception {
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
        return stub(stubs, HqcUiIds.POPUP_SDK_STATUS);
    }

    private static PartialPopupStub stub(IPartialPopupController[] stubs, int id) {
        PartialPopupStub found = null;
        for (int i = 0; stubs != null && i < stubs.length; i++) {
            if (stubs[i] instanceof PartialPopupStub
                    && ((PartialPopupStub) stubs[i]).getID() == id) {
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
        assertEquals(length(stock) + 2, stubs.length, "the stock stubs, then the SDK's (status, toast)");
        PartialPopupStub stub = sdkStub(stubs);
        assertSame(stub, stubs[stubs.length - 2]);
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
        assertTrue(stub(again, HqcUiIds.POPUP_SDK_TOAST) != null, "the toast keeps its own stub");
        assertEquals(length(new CarScreenFactory(fw()).getPartialPopupStubs(0)) + 1, length(again));
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

    /** The bank's model for SDK id {@code id}, created on demand (getModel's index maths is byte-swapped on the host). */
    private static Object model(SdkCarModelBank bank, int id) throws Exception {
        int i = id - HqcUiIds.CAR_MODULE * 100000;
        bank.createModel(i);
        Field f = de.audi.atip.hmi.model.AbstractModelBank.class.getDeclaredField("models");
        f.setAccessible(true);
        return ((Object[]) f.get(bank))[i];
    }

    /** F4, F22: each SDK index gets its own kind of model; the reserved ones none, and none is a stray locator. */
    @Test
    void bankModelKindsByIndex() throws Exception {
        // the models' log channel, as the activator sets it (a failed model class initializer stays failed)
        de.audi.atip.hmi.model.AbstractModelBank.initLogging(name -> de.audi.atip.log.NullLogChannel.getInstance());
        boolean was = SdkGlobals.uiEnabled;
        try {
            SdkGlobals.uiEnabled = true;
            SdkCarModelBank bank = new SdkCarModelBank();
            int base = HqcUiIds.MODEL_BASE;
            assertEquals(42, HqcUiIds.MODEL_COUNT);
            int popup = 0;
            for (int off = 12; off <= 25; off++) {
                Object m = model(bank, base + off);
                assertTrue(m instanceof de.audi.atip.hmi.model.ChoiceModel
                        || m instanceof de.audi.atip.hmi.model.LabelModel
                        || m instanceof de.audi.atip.hmi.model.ResourceLocatorModel, "+" + off + ": " + m);
                popup++;
            }
            assertEquals(14, popup);
            assertTrue(model(bank, base + 20) instanceof de.audi.atip.hmi.model.ResourceLocatorModel);
            for (int s = 0; s < 4; s++) {
                assertSame(de.audi.atip.hmi.model.ChoiceModel.class, model(bank, base + 26 + s).getClass(),
                        "+" + (26 + s));
                assertSame(de.audi.atip.hmi.model.RangeModel.class, model(bank, base + 30 + s).getClass(),
                        "+" + (30 + s));
            }
            Object list = model(bank, base + 35);
            assertSame(de.audi.atip.hmi.model.ListModel.class, list.getClass());
            assertEquals(6, ((de.audi.atip.hmi.model.ListModel) list).getMaxColumns());
            assertSame(de.audi.atip.hmi.model.SpellerModel.class, model(bank, base + 36).getClass());
            assertSame(de.audi.atip.hmi.model.ChoiceModel.class, model(bank, base + 37).getClass());
            assertSame(de.audi.atip.hmi.model.LabelModel.class, model(bank, base + 38).getClass());
            assertSame(de.audi.atip.hmi.model.ResourceLocatorModel.class, model(bank, base + 39).getClass());
            for (int off : new int[] {10, 34, 40, 41}) {
                assertNull(model(bank, base + off), "+" + off + " is reserved");
            }
            int[] all = bank.getAllModelIds();
            List<Integer> sdk = new ArrayList<Integer>();
            for (int id : all) {
                if (id >= base && id < base + HqcUiIds.MODEL_COUNT) {
                    sdk.add(id - base);
                }
            }
            assertEquals(38, sdk.size(), "42 ids, 4 reserved: " + sdk);
            assertFalse(sdk.contains(10) || sdk.contains(34) || sdk.contains(40) || sdk.contains(41));
            assertEquals(0, (int) sdk.get(0));
            assertEquals(39, (int) sdk.get(sdk.size() - 1));
        } finally {
            SdkGlobals.uiEnabled = was;
        }
    }

    /** F19: getAltText serves the input texts as getText does (one helper), and the page title as before. */
    @Test
    void altTextServesInputTexts() throws Exception {
        PageScreens.forgetForTests();
        try {
            mibsdk.platform.UiTable table = new mibsdk.platform.UiTable(8);
            PageScreensNavigationTest.FakeScreens hmi = new PageScreensNavigationTest.FakeScreens();
            mibsdk.platform.PageStack stack = new mibsdk.platform.PageStack(new PageEvents(table));
            HmiPoster poster = new HmiPoster(new HmiPosterTest.HeldSink(), null, () -> 0L,
                    HmiPoster.PENDING_DEADLINE_MS);
            PageScreens pages = new PageScreens(hmi, stack, table, poster, new mibsdk.runtime.RingLog(10));
            UiInputs.clock = () -> 0L;
            UiInputs.userPressed();
            mibsdk.platform.UiPage p = new mibsdk.platform.UiPage("a/p", "Alpha", java.util.Arrays.asList(
                    new mibsdk.platform.UiEntry(mibsdk.platform.UiEntry.DROPDOWN, "mode", "Mode", null, false, null)
                            .withOptions(new String[] {"Off", "On"})));
            java.util.Map<String, mibsdk.platform.UiPage> m = new java.util.HashMap<String, mibsdk.platform.UiPage>();
            m.put("a/p", p);
            mibsdk.platform.UiSnapshot s = new mibsdk.platform.UiSnapshot(1, null, "", m, 1, "a/p");
            table.publish(s);
            pages.open("a/p", s, 0);

            SdkCarScreenFactory f = new SdkCarScreenFactory(fw());
            assertEquals("Mode", f.getAltText(609040));
            assertEquals("On", f.getAltText(609042));
            assertEquals("Mode", f.getText(609040));
            assertEquals("", f.getAltText(609077), "ours, nothing to serve: never the stock text");
            assertEquals("Alpha", f.getAltText(HqcUiIds.TEXT_PAGE_TITLE));
        } finally {
            PageScreens.forgetForTests();
        }
    }

    // ---- the toast popup 609601 (Task 7) ----

    @Test
    void toastStubUsesFreeSlot6LeftOfStatus() throws Exception {
        IPartialPopupController[] stock = new CarScreenFactory(fw()).getPartialPopupStubs(0);
        IPartialPopupController[] stubs = new SdkCarScreenFactory(fw()).getPartialPopupStubs(0);
        assertEquals(length(stock) + 2, stubs.length);
        PartialPopupStub status = sdkStub(stubs);
        PartialPopupStub toast = stub(stubs, HqcUiIds.POPUP_SDK_TOAST);
        assertSame(status, stubs[stubs.length - 2]);
        assertSame(toast, stubs[stubs.length - 1], "placed after the status stub");
        assertEquals(609601, toast.getID());
        assertEquals(6, toast.getSlot());
        assertEquals(7, status.getSlot(), "the status stub stays in slot 7");
        assertEquals(12, toast.getZpmSlot());
        assertEquals(250, toast.getZpmPriority());
        assertEquals(7, toast.getType());
        assertEquals(HqcUiIds.POPUP_SDK_STUB_KIND, toast.getStyle());
        assertEquals(HqcUiIds.TOAST_VISIBLE_MODEL, toast.getModelID());
        for (int i = 0; i < length(stock); i++) {
            if (stock[i] instanceof PartialPopupStub) {
                assertTrue(((PartialPopupStub) stock[i]).getSlot() != 6, "no stock Car stub in slot 6");
            }
        }
        assertEquals(1, SdkGlobals.toastPopup, "registered");
        assertEquals(1, SdkGlobals.statusPopup, "registered");

        systemFonts(true);
        SdkCarScreenFactory f = new SdkCarScreenFactory(fw());
        IPartialPopupController p = f.getPartialPopup(HqcUiIds.POPUP_SDK_TOAST, 0);
        assertTrue(p instanceof PartialPopupController, "built: " + SdkGlobals.LOG.snapshot());
        PartialPopupController c = (PartialPopupController) p;
        assertEquals(609601, c.getID());
        assertEquals(6, c.getSlot());
        assertEquals(12, c.getZpmSlot());
        assertEquals(259, c.getX());
        assertEquals(0, c.getY());
        assertEquals(255, c.getWidth());
        assertEquals(24, c.getHeight());
        assertEquals(HqcUiIds.POPUP_SDK_X, c.getX() + c.getWidth(), "ends where the status popup starts");
        assertEquals(2, SdkGlobals.toastPopup, "built");
        assertEquals(1, SdkGlobals.statusPopup, "the status popup's state is its own");
        assertTrue(SdkToastPopup.current != null);
    }

    @Test
    void toastStubWithoutStatusPopup() throws Exception {
        SdkGlobals.statusPopup = -1; // the status line fell back to popup 65
        IPartialPopupController[] stock = new CarScreenFactory(fw()).getPartialPopupStubs(0);
        IPartialPopupController[] stubs = new SdkCarScreenFactory(fw()).getPartialPopupStubs(0);
        assertEquals(length(stock) + 1, stubs.length);
        assertNull(sdkStub(stubs), "no status stub");
        assertSame(stub(stubs, HqcUiIds.POPUP_SDK_TOAST), stubs[stubs.length - 1],
                "the toast stub is still registered");
        assertEquals(1, SdkGlobals.toastPopup);
        assertEquals(-1, SdkGlobals.statusPopup);
    }

    @Test
    void toastFailureIsStickyAndLeavesStatus() throws Exception {
        SdkCarScreenFactory ok = new SdkCarScreenFactory(fw());
        ok.getPartialPopupStubs(0);
        assertEquals(1, SdkGlobals.toastPopup);
        assertNull(failingBuild().getPartialPopup(HqcUiIds.POPUP_SDK_TOAST, 0), "a forced build failure");
        assertEquals(-1, SdkGlobals.toastPopup);
        assertNull(SdkToastPopup.current);
        assertEquals(1, SdkGlobals.statusPopup, "status untouched");
        assertTrue(SdkGlobals.LOG.snapshot().toString().contains("ui toast popup 609601 failed reason=build"),
                "one log line: " + SdkGlobals.LOG.snapshot());

        IPartialPopupController[] again = ok.getPartialPopupStubs(0);
        assertNull(stub(again, HqcUiIds.POPUP_SDK_TOAST), "no toast stub on the next call");
        assertTrue(sdkStub(again) != null, "the status popup still registered");
        assertEquals(-1, SdkGlobals.toastPopup, "sticky");
        systemFonts(true); // a build would succeed now
        assertNull(ok.getPartialPopup(HqcUiIds.POPUP_SDK_TOAST, 0), "nor a later build");
        assertEquals(-1, SdkGlobals.toastPopup);
        assertTrue(ok.getPartialPopup(HqcUiIds.POPUP_SDK_STATUS, 0) instanceof PartialPopupController);
        assertEquals(2, SdkGlobals.statusPopup);
    }

    @Test
    void toastNeverTouchesStatusVisibility() throws Exception {
        de.audi.atip.hmi.model.AbstractModelBank.initLogging(name -> de.audi.atip.log.NullLogChannel.getInstance());
        boolean was = SdkGlobals.uiEnabled;
        try {
            SdkGlobals.uiEnabled = true;
            SdkCarModelBank bank = new SdkCarModelBank();
            de.audi.atip.hmi.model.ChoiceModel statusVisible =
                    (de.audi.atip.hmi.model.ChoiceModel) model(bank, HqcUiIds.STATUS_POPUP_VISIBLE_MODEL);
            de.audi.atip.hmi.model.ChoiceModel toastVisible =
                    (de.audi.atip.hmi.model.ChoiceModel) model(bank, HqcUiIds.TOAST_VISIBLE_MODEL);
            for (int status : new int[] {0, 1}) {
                statusVisible.setValue(status);
                SdkToastPopup.Models m = SdkCarModelBank.toastModels();
                m.label("Saved");
                m.fileIcon(null);
                m.visible(true);
                assertEquals(1, toastVisible.getValue());
                assertEquals(status, statusVisible.getValue(), "a show leaves the status visibility");
                m.visible(false);
                assertEquals(0, toastVisible.getValue());
                assertEquals(status, statusVisible.getValue(), "a hide leaves the status visibility");
            }
        } finally {
            SdkGlobals.uiEnabled = was;
        }
        // The built toast maps none of the status popup's models.
        systemFonts(true);
        PartialPopupController c = (PartialPopupController) new SdkCarScreenFactory(fw())
                .getPartialPopup(HqcUiIds.POPUP_SDK_TOAST, 0);
        assertEquals(HqcUiIds.TOAST_VISIBLE_MODEL, c.getModelID());
        assertArrayEquals(new int[] {HqcUiIds.TOAST_VISIBLE_MODEL, HqcUiIds.TOAST_LABEL_MODEL,
            HqcUiIds.TOAST_FILE_ICON_MODEL}, c.getViewIDs());
    }

    /**
     * Fix round 1 (ruling): a slot-6 toast cannot displace the slot-7 status popup (stock links slot 4 to 1/2 only),
     * and routine stock passes (REM entry, block modes) hide 609600 with its model at 1. Such a hide during a toast is only
     * logged, once per boot: toasts stay on, and the toast is not hidden from inside the manager's hide.
     */
    @Test
    void statusHiddenDuringToastLogsOnceAndKeepsToasts() throws Exception {
        systemFonts(true);
        SdkCarScreenFactory f = new SdkCarScreenFactory(fw());
        f.getPartialPopupStubs(0);
        IPartialPopupController p = f.getPartialPopup(HqcUiIds.POPUP_SDK_STATUS, 0);
        assertTrue(p instanceof StatusPopupController, "609600 is built on StatusPopupController");
        StatusPopupController c = (StatusPopupController) p;
        List<Integer> hidden = new ArrayList<Integer>();
        Field pm = de.esolutions.hmi.widgets.audi.evo.widgets.AbstractPartialPopupController.class
                .getDeclaredField("popupManager");
        pm.setAccessible(true);
        pm.set(c, Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {pm.getType()}, (q, m, a) -> {
            if (m.getName().equals("hidePopup")) {
                hidden.add((Integer) a[0]);
            }
            return m.getReturnType() == int.class ? Integer.valueOf(1) : null;
        }));
        de.audi.atip.hmi.model.AbstractModelBank.initLogging(name -> de.audi.atip.log.NullLogChannel.getInstance());
        de.audi.atip.hmi.model.ChoiceModel visible = new de.audi.atip.hmi.model.ChoiceModel(
                HqcUiIds.STATUS_POPUP_VISIBLE_MODEL);
        c.setModel(visible);
        StatusPopupController.warned = false;
        int before = warnings();

        // The status line hidden by its own model (0), or with no toast up: nothing logged.
        visible.setValue(0);
        SdkToastPopup.up = true;
        hideOnHost(c);
        visible.setValue(1);
        SdkToastPopup.up = false;
        hideOnHost(c);
        assertEquals(before, warnings());

        // A stock hide of 609600 while its model says shown, during a toast: one WARN line, twice over.
        SdkToastPopup.up = true;
        hideOnHost(c);
        hideOnHost(c);
        assertEquals(before + 1, warnings(), "once per boot: " + SdkGlobals.LOG.snapshot());
        assertEquals(1, SdkGlobals.toastPopup, "toasts stay on (never disabled)");
        assertEquals(new ArrayList<Integer>(), hidden, "the toast is not hidden from inside the manager's hide");
        assertEquals(2, SdkGlobals.statusPopup);
        assertTrue(stub(f.getPartialPopupStubs(0), HqcUiIds.POPUP_SDK_TOAST) != null, "the toast keeps its stub");
    }

    private static int warnings() {
        int n = 0;
        for (Object l : SdkGlobals.LOG.snapshot()) {
            n += String.valueOf(l).contains("status popup hidden while a toast is up") ? 1 : 0;
        }
        return n;
    }

    /** Stock hide needs the HMI terminal, which the host lacks: the SDK part runs before it. */
    private static void hideOnHost(StatusPopupController c) {
        try {
            c.hide(2);
        } catch (RuntimeException noTerminalOnTheHost) {
            // stock PartialPopupController.hide
        }
    }
}
