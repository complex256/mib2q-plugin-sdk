package mibsdk.adapter.hqc.ui;

import de.audi.atip.hmi.model.HMIModel;
import de.audi.atip.hmi.model.ResourceLocatorModel;
import de.audi.atip.model.SystemModelBank;

/**
 * SystemModelBank grown at {@link HqcUiIds#SYSTEM_MODEL_BASE}, mirroring {@link SdkCarModelBank}: the built-in status
 * bar is System UI, so a file-backed status icon needs a {@code ResourceLocatorModel} from the System module, not the
 * Car factory's image ids. That makes a plugin's PNG path global (Car, Media, System and EarlyApps), unlike the
 * Car-only ids the spike borrowed.
 *
 * <p>For module 0 a model's id is its index ({@code AbstractModelBank.getModelIndex}: {@code id % 100000}), so the SDK
 * ids are {@link HqcUiIds#STATUS_BAR_ICON_MODEL_0} .. {@code +STATUS_BAR_ICON_MODELS - 1}, one per bar row. Built on
 * LSD's startup thread (arrays only: no thread, no I/O). The models are created on first use through
 * {@link #statusIcons()}, which the bar calls on HMIEvent; the bank's lock covers the creation. Never throws.
 */
final class SdkSystemModelBank extends SystemModelBank {

    /** {@link HqcUiIds#SYSTEM_MODEL_BASE} is both the first SDK index and (module 0) the first SDK id. */
    private static final int BASE_INDEX = HqcUiIds.SYSTEM_MODEL_BASE;
    private static final int COUNT = HqcUiIds.STATUS_BAR_ICON_MODELS;

    /** The bank the bar asks for its file-icon locators; null until built. */
    private static volatile SdkSystemModelBank current;

    /** The bar's locators, by handle; created once with them. */
    private Models handle; // guarded by this

    SdkSystemModelBank() {
        int stockLen = this.models.length;
        if (stockLen > BASE_INDEX) {
            throw new IllegalStateException("System bank has " + stockLen + " models; SDK ids start at " + BASE_INDEX);
        }
        HMIModel[] grown = new HMIModel[BASE_INDEX + COUNT];
        System.arraycopy(this.models, 0, grown, 0, stockLen);
        int stockIds = this.modelIDs.length;
        int[] ids = new int[stockIds + COUNT];
        System.arraycopy(this.modelIDs, 0, ids, 0, stockIds);
        for (int i = 0; i < COUNT; i++) {
            ids[stockIds + i] = BASE_INDEX + i;
        }
        this.models = grown;
        this.modelIDs = ids;
        current = this;
    }

    /**
     * The bar's file-icon models, created on first use, or null before the bank exists (or if they cannot be made).
     * Called on HMIEvent (the bar's update) and on {@code mibsdk-ui} (its first show may race the attach); the bank's
     * lock covers the creation. Never throws.
     */
    static Models statusIcons() {
        SdkSystemModelBank b = current;
        return b == null ? null : b.icons();
    }

    private synchronized Models icons() {
        if (handle == null) {
            try {
                ResourceLocatorModel[] m = new ResourceLocatorModel[COUNT];
                for (int i = 0; i < COUNT; i++) {
                    createModel(BASE_INDEX + i);
                    m[i] = (ResourceLocatorModel) this.models[BASE_INDEX + i];
                }
                for (int i = 0; i < COUNT; i++) {
                    if (m[i] == null) {
                        return null;
                    }
                }
                handle = new Models(m);
            } catch (Throwable t) {
                // no handle yet: the bar draws the item without its file icon and asks again on its next update
            }
        }
        return handle;
    }

    /**
     * The grown {@code modelIDs} (stock ids, then the SDK's), as SystemModelBank answers. Pinned here because
     * AbstractModelBank's own version sizes its array by the stock model count while walking the grown
     * {@code models[]}: reaching it would overflow on a stock thread once SDK models exist. {@code getModels()} (final)
     * sizes by this.
     */
    public int[] getAllModelIds() {
        return this.modelIDs;
    }

    /** Stock thread (HMIEvent) or {@code mibsdk-ui}: create the SDK locator; never throws. */
    protected synchronized void createModel(int i) {
        if (i < BASE_INDEX || i >= BASE_INDEX + COUNT) {
            super.createModel(i);
            return;
        }
        if (this.models[i] != null) {
            return;
        }
        try {
            this.models[i] = new ResourceLocatorModel(BASE_INDEX + (i - BASE_INDEX));
        } catch (Throwable t) {
            // the model stays absent: the bar's file icon does not draw
        }
    }

    /**
     * The bar's handle on the file-icon locators. A locator is set with {@code setResourceLocator(-1, path, 0)} and
     * cleared with {@code (-1, "", 0)}. Status 0, as spike C's {@code HMIResourceLocator(path)}: for status 1
     * IconRendererHigh draws the icon's bitmap 0 and never loads the URI.
     */
    static final class Models {
        private final ResourceLocatorModel[] files;

        Models(ResourceLocatorModel[] files) {
            this.files = files;
        }

        /** Row {@code slot}'s file icon; null or empty clears it. */
        void fileIcon(int slot, String path) {
            if (slot >= 0 && slot < files.length) {
                files[slot].setResourceLocator(-1, path == null ? "" : path, 0);
            }
        }
    }
}
