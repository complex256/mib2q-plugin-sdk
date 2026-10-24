package mibsdk.adapter.hqc.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.audi.atip.hmi.model.ChoiceModel;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import mibsdk.adapter.hqc.SdkGlobals;
import mibsdk.platform.UiInput;
import mibsdk.platform.UiRow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The {@code HMICar=} activator on the host, over the repaired stock classes (the adapter's test JVM skips bytecode
 * verification: jxe2jar code has no stack maps). Static SdkGlobals: sequential. The host copies' id arithmetic uses
 * jxe2jar's byte-swapped constants, so models are created by index ({@code createModel}) rather than by id.
 */
class SdkCarActivatorTest {

    private static final int BASE_INDEX = HqcUiIds.MODEL_BASE - HqcUiIds.CAR_MODULE * 100000;

    @BeforeAll
    static void stockLogging() {
        de.audi.atip.hmi.model.AbstractModelBank.initLogging(name -> de.audi.atip.log.NullLogChannel.getInstance());
    }

    /** Creates (as the framework does on HMIEvent) and returns the SDK model with this id. */
    private static ChoiceModel model(SdkCarModelBank bank, int id) throws Exception {
        bank.createModel(BASE_INDEX + id - HqcUiIds.MODEL_BASE);
        Field f = de.audi.atip.hmi.model.AbstractModelBank.class.getDeclaredField("models");
        f.setAccessible(true);
        return (ChoiceModel) ((Object[]) f.get(bank))[BASE_INDEX + id - HqcUiIds.MODEL_BASE];
    }

    @BeforeEach
    @AfterEach
    void reset() throws InterruptedException {
        SdkGlobals.uiEnabled = false;
        SdkGlobals.uiOffReason = null;
        SdkGlobals.uiSlots = DrawerAssignment.EMPTY;
        SdkGlobals.uiRowModels = null;
        SdkGlobals.uiDown = false;
        while (SdkGlobals.UI.poll(0) != null) {
            // drain
        }
    }

    private static Object bankOf(SdkCarActivator a) throws Exception {
        Field f = de.audi.atip.activator.AbstractHMIActivator.class.getDeclaredField("modelBank");
        f.setAccessible(true);
        return f.get(a);
    }

    private static Set<Thread> liveThreads() {
        return new HashSet<Thread>(Thread.getAllStackTraces().keySet());
    }

    private static String lastLog() {
        List<String> l = SdkGlobals.LOG.snapshot();
        return l.get(l.size() - 1);
    }

    /** Carried requirement 1: the LSD-thread constructor starts nothing (every SDK thread starts in start()). */
    @Test
    void constructorCreatesNoThread() throws Exception {
        for (boolean on : new boolean[] {false, true}) {
            SdkGlobals.uiEnabled = on;
            Set<Thread> before = liveThreads();
            new SdkCarActivator();
            Set<Thread> after = liveThreads();
            after.removeAll(before);
            assertEquals(new HashSet<Thread>(), after, "uiEnabled=" + on);
        }
    }

    @Test
    void stockBankAndOneStatusLineWhenUiIsOff() throws Exception {
        SdkGlobals.uiOffReason = "wrap-boots";
        SdkCarActivator a = new SdkCarActivator();
        assertSame(de.audi.atip.model.CarModelBank.class, bankOf(a).getClass());
        assertTrue(lastLog().contains("ui car=stock reason=wrap-boots"), lastLog());

        SdkGlobals.uiOffReason = null; // no configurator of ours ran
        new SdkCarActivator();
        assertTrue(lastLog().contains("ui car=stock reason=no-configurator"), lastLog());
    }

    @Test
    void sdkBankGrowsRowModelsAndPublishesThemOnceAllExist() throws Exception {
        SdkGlobals.uiEnabled = true;
        SdkCarActivator a = new SdkCarActivator();
        assertTrue(lastLog().contains("ui car=sdk"), lastLog());
        SdkCarModelBank bank = (SdkCarModelBank) bankOf(a);

        List<UiRow> rows = new ArrayList<UiRow>();
        rows.add(new UiRow("mibsdk.plugins", "Plugins", "mibsdk.plugins"));
        rows.add(new UiRow("hello.row", "Hello", "hello.page"));
        SdkGlobals.uiSlots = DrawerSlots.assign(DrawerAssignment.EMPTY, rows, HqcUiIds.PLUGIN_ROW_SLOTS);

        for (int slot = 0; slot < HqcUiIds.ROW_SLOTS; slot++) {
            assertNull(SdkGlobals.uiRowModels, "published only once every row model exists");
            ChoiceModel m = model(bank, HqcUiIds.ROW_VISIBILITY_MODEL_0 + slot);
            assertEquals(HqcUiIds.ROW_VISIBILITY_MODEL_0 + slot, m.getID());
            assertEquals(slot <= 1 ? 0 : 1, m.getValue(), "slot " + slot + ": 0 visible, 1 hidden");
        }
        RowModels models = SdkGlobals.uiRowModels;
        assertNotNull(models);
        models.setVisible(5, true);
        assertEquals(0, model(bank, HqcUiIds.ROW_VISIBILITY_MODEL_0 + 5).getValue());
        models.setVisible(1, false);
        assertEquals(1, model(bank, HqcUiIds.ROW_VISIBILITY_MODEL_0 + 1).getValue());
        models.setVisible(9, true); // out of range: ignored
        models.setVisible(-1, true);
        // Creating a model again keeps the first (the framework calls createModel on every lookup).
        assertSame(model(bank, HqcUiIds.ROW_VISIBILITY_MODEL_0 + 5), model(bank, HqcUiIds.ROW_VISIBILITY_MODEL_0 + 5));
        assertSame(models, SdkGlobals.uiRowModels, "published once");
    }

    /** Carried requirement 5: the press only drops ROW_SELECTED into the ring, every time, and never throws. */
    @Test
    void rowPressOnlyQueuesInput() throws Exception {
        SdkGlobals.uiEnabled = true;
        SdkCarModelBank bank = (SdkCarModelBank) bankOf(new SdkCarActivator());
        List<UiRow> rows = new ArrayList<UiRow>();
        rows.add(new UiRow("mibsdk.plugins", "Plugins", "mibsdk.plugins"));
        rows.add(new UiRow("hello.row", "Hello", "hello.page"));
        SdkGlobals.uiSlots = DrawerSlots.assign(DrawerAssignment.EMPTY, rows, HqcUiIds.PLUGIN_ROW_SLOTS);

        ChoiceModel sel = model(bank, HqcUiIds.ROW_SELECTION_MODEL);
        assertEquals(-1, sel.getValue());
        Field f = de.audi.atip.hmi.model.ButtonModel.class.getDeclaredField("buttonListener");
        f.setAccessible(true);
        de.audi.atip.hmi.model.ChoiceListener l =
                (de.audi.atip.hmi.model.ChoiceListener) f.get(sel);
        l.itemSelected(HqcUiIds.ROW_SELECTION_MODEL, 1, 0, 0);
        l.itemSelected(HqcUiIds.ROW_SELECTION_MODEL, 1, 0, 0);
        l.itemSelected(HqcUiIds.ROW_SELECTION_MODEL, 7, 0, 0); // empty slot
        l.itemSelected(HqcUiIds.ROW_SELECTION_MODEL, 99, 0, 0);
        SdkGlobals.uiSlots = null; // even a broken reference never throws into the HMI
        l.itemSelected(HqcUiIds.ROW_SELECTION_MODEL, 1, 0, 0);
        for (int i = 0; i < 2; i++) {
            UiInput in = SdkGlobals.UI.poll(0);
            assertEquals(UiInput.ROW_SELECTED, in.kind());
            assertEquals("hello.row", in.pageKey());
        }
        assertNull(SdkGlobals.UI.poll(0));
        assertEquals(-1, sel.getValue(), "no selection reset");
    }

    /**
     * Carried requirement 3 (fix round 1, Minor 1): the stock answer (CarModelBank returns its {@code modelIDs}, which
     * the SDK bank grew) already covers the SDK ids once every SDK model exists, so AbstractModelBank's version sized by
     * the stock model count is never reached. SdkCarModelBank overrides getAllModelIds() only as an explicit pin of that
     * answer; this test passes with or without the pin. getModels() is final and sizes by getAllModelIds().
     */
    @Test
    void stockModelIdAnswerCoversTheSdkIds() throws Exception {
        SdkGlobals.uiEnabled = true;
        SdkCarModelBank bank = (SdkCarModelBank) bankOf(new SdkCarActivator());
        int[] before = bank.getAllModelIds();
        for (int i = 0; i < HqcUiIds.ROW_MODEL_COUNT; i++) {
            model(bank, HqcUiIds.MODEL_BASE + i);
        }
        assertNotNull(SdkCarModelBank.pageList());
        int[] ids = bank.getAllModelIds();
        assertEquals(before.length, ids.length);
        int sdk = HqcUiIds.ROW_MODEL_COUNT + 1;
        for (int i = 0; i < HqcUiIds.ROW_MODEL_COUNT; i++) {
            assertEquals(HqcUiIds.MODEL_BASE + i, ids[ids.length - sdk + i]);
        }
        assertEquals(HqcUiIds.PAGE_LIST_MODEL, ids[ids.length - 1], "then the page list");
        assertEquals(1147 + sdk, ids.length, "CarModelBank's 1147 stock ids, then the SDK's");
    }

    /** Task 11: the page ListModel (3 columns) exists on demand; the reserved id between it and the rows has none. */
    @Test
    void bankGrowsThePageListModel() throws Exception {
        SdkGlobals.uiEnabled = true;
        SdkCarModelBank bank = (SdkCarModelBank) bankOf(new SdkCarActivator());
        de.audi.atip.hmi.model.ListModel list = SdkCarModelBank.pageList();
        assertNotNull(list);
        assertSame(list, SdkCarModelBank.pageList(), "created once");
        assertEquals(3, list.getMaxColumns());
        int reserved = BASE_INDEX + HqcUiIds.ROW_MODEL_COUNT;
        bank.createModel(reserved);
        Field f = de.audi.atip.hmi.model.AbstractModelBank.class.getDeclaredField("models");
        f.setAccessible(true);
        assertNull(((Object[]) f.get(bank))[reserved]);
    }

    /** Fix round 1, Minor 3: after the SDK UI was taken down, a drawer built from scratch starts without the Plugins row. */
    @Test
    void pluginsRowStartsHiddenOnceTheUiIsDown() throws Exception {
        SdkGlobals.uiEnabled = true;
        SdkCarModelBank up = (SdkCarModelBank) bankOf(new SdkCarActivator());
        assertEquals(0, model(up, HqcUiIds.ROW_VISIBILITY_MODEL_0).getValue(), "visible by default");
        SdkGlobals.uiDown = true;
        SdkCarModelBank down = (SdkCarModelBank) bankOf(new SdkCarActivator());
        assertEquals(1, model(down, HqcUiIds.ROW_VISIBILITY_MODEL_0).getValue(), "hidden once torn down");
    }
}
