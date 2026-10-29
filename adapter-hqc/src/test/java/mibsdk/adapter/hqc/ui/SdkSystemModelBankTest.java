package mibsdk.adapter.hqc.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.audi.atip.hmi.model.AbstractModelBank;
import de.audi.atip.hmi.model.ResourceLocatorModel;
import de.audi.atip.model.SystemModelBank;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

/** The System bank grown for the built-in bar's file icons: ids, model kinds, locators. */
class SdkSystemModelBankTest {

    static {
        // the models' log channel, as the activator sets it (a failed model class initializer stays failed)
        AbstractModelBank.initLogging(name -> de.audi.atip.log.NullLogChannel.getInstance());
    }

    @Test
    void growsTheStockBankAtTheSdkSystemIds() throws Exception {
        SdkSystemModelBank bank = new SdkSystemModelBank();
        assertEquals(HqcUiIds.SYSTEM_MODEL_BASE + HqcUiIds.STATUS_BAR_ICON_MODELS, models(bank).length);
        assertEquals(stockIdCount() + HqcUiIds.STATUS_BAR_ICON_MODELS, bank.getAllModelIds().length);
        int[] ids = bank.getAllModelIds();
        for (int i = 0; i < HqcUiIds.STATUS_BAR_ICON_MODELS; i++) {
            assertTrue(contains(ids, HqcUiIds.STATUS_BAR_ICON_MODEL_0 + i), "id " + i);
        }
    }

    @Test
    void statusIconsMakesOneLocatorPerBarRow() throws Exception {
        SdkSystemModelBank bank = new SdkSystemModelBank();
        SdkSystemModelBank.Models icons = SdkSystemModelBank.statusIcons();
        assertNotNull(icons);
        for (int i = 0; i < HqcUiIds.STATUS_BAR_ICON_MODELS; i++) {
            Object m = models(bank)[HqcUiIds.STATUS_BAR_ICON_MODEL_0 + i];
            assertTrue(m instanceof ResourceLocatorModel, "row " + i + ": " + m);
        }

        ResourceLocatorModel first = (ResourceLocatorModel) bank.getModel(HqcUiIds.STATUS_BAR_ICON_MODEL_0);
        icons.fileIcon(0, "/tmp/a.png");
        assertEquals("/tmp/a.png", first.getResourceLocator().getResourceURI());
        icons.fileIcon(0, null);
        assertEquals("", first.getResourceLocator().getResourceURI(), "cleared");
        icons.fileIcon(HqcUiIds.STATUS_BAR_ICON_MODELS, "/tmp/x.png"); // out of range: ignored, never throws
    }

    /** The stock bank keeps its 5626 models; only the SDK subclass grows the arrays. */
    @Test
    void stockSystemBankIsUnchanged() throws Exception {
        SystemModelBank stock = new SystemModelBank();
        assertEquals(5626, models(stock).length);
    }

    private static Object[] models(AbstractModelBank bank) throws Exception {
        Field f = AbstractModelBank.class.getDeclaredField("models");
        f.setAccessible(true);
        return (Object[]) f.get(bank);
    }

    private static int stockIdCount() throws Exception {
        Field f = AbstractModelBank.class.getDeclaredField("modelIDs");
        f.setAccessible(true);
        return ((int[]) f.get(new SystemModelBank())).length;
    }

    private static boolean contains(int[] a, int x) {
        for (int i = 0; i < a.length; i++) {
            if (a[i] == x) {
                return true;
            }
        }
        return false;
    }
}
