package mibsdk.adapter.hqc.ui;

import de.audi.atip.hmi.model.ChoiceModel;
import de.audi.atip.hmi.model.HMIModel;
import de.audi.atip.hmi.model.LabelModel;
import de.audi.atip.hmi.model.ListListener;
import de.audi.atip.hmi.model.ListModel;
import de.audi.atip.hmi.model.ResourceLocatorModel;
import de.audi.atip.hmi.model.listener.DefaultChoiceListener;
import de.audi.atip.model.CarModelBank;
import mibsdk.adapter.hqc.SdkGlobals;

/**
 * CarModelBank grown at {@link HqcUiIds#MODEL_BASE} (spike A's technique): a visibility ChoiceModel per drawer slot,
 * the selection ChoiceModel shared by the SDK rows, the SDK page's ListModel ({@link HqcUiIds#PAGE_LIST_MODEL}), and
 * the status popup's models (its visibility ChoiceModel, 7 LabelModels and 6 ResourceLocatorModels, from
 * {@link HqcUiIds#STATUS_POPUP_VISIBLE_MODEL}).
 *
 * <p>Built on LSD's startup thread (arrays only: no thread, no I/O). The stock framework creates the models on
 * HMIEvent, through {@link #createModel}, when the drawer is first built; once every row model exists the bank
 * publishes them as {@code SdkGlobals.uiRowModels}. The selection listener only drops the press into
 * {@code SdkGlobals.UI}; the page list's listener only hands the row to {@link PageScreens#listSelected}.
 */
final class SdkCarModelBank extends CarModelBank {

    /** Index of {@link HqcUiIds#MODEL_BASE} in {@code models[]} (ids are module * 100000 + index). */
    private static final int BASE_INDEX = HqcUiIds.MODEL_BASE - HqcUiIds.CAR_MODULE * 100000;
    /** Index of the page ListModel; the reserved ids between the row models and it have no model. */
    private static final int PAGE_LIST_INDEX = BASE_INDEX + HqcUiIds.PAGE_LIST_MODEL - HqcUiIds.MODEL_BASE;
    /** Index of the status popup's first model; it and the rest of the SDK range are the popup's. */
    private static final int POPUP_INDEX = BASE_INDEX + HqcUiIds.STATUS_POPUP_VISIBLE_MODEL - HqcUiIds.MODEL_BASE;
    private static final int POPUP_MODELS = HqcUiIds.MODEL_COUNT - (POPUP_INDEX - BASE_INDEX);
    /** The SDK model ids the bank serves: the row models, then the page list, then the status popup's. */
    private static final int[] SDK_IDS = sdkIds();

    /** The bank SDK pages write their rows to; null until built. */
    private static volatile SdkCarModelBank current;

    /** A page holds at most 24 entries (PageSpec); the stopped notice has one. */
    private static final int PAGE_MAX_ROWS = 24;

    /** Visibility models by slot; written under the bank's lock (createModel), published through SdkGlobals. */
    private final ChoiceModel[] rowVisibility = new ChoiceModel[HqcUiIds.ROW_SLOTS];
    /** The status popup's models, by handle; created once with them ({@link #statusModels}). */
    private StatusModels statusModels; // guarded by this

    SdkCarModelBank() {
        int stockLen = this.models.length;
        if (stockLen > BASE_INDEX) {
            throw new IllegalStateException("Car bank has " + stockLen + " models; SDK ids start at " + BASE_INDEX);
        }
        HMIModel[] grown = new HMIModel[BASE_INDEX + HqcUiIds.MODEL_COUNT];
        System.arraycopy(this.models, 0, grown, 0, stockLen);
        int stockIds = this.modelIDs.length;
        int[] ids = new int[stockIds + SDK_IDS.length];
        System.arraycopy(this.modelIDs, 0, ids, 0, stockIds);
        System.arraycopy(SDK_IDS, 0, ids, stockIds, SDK_IDS.length);
        this.models = grown;
        this.modelIDs = ids;
        current = this;
    }

    private static int[] sdkIds() {
        int[] ids = new int[HqcUiIds.ROW_MODEL_COUNT + 1 + POPUP_MODELS];
        for (int i = 0; i < HqcUiIds.ROW_MODEL_COUNT; i++) {
            ids[i] = HqcUiIds.MODEL_BASE + i;
        }
        ids[HqcUiIds.ROW_MODEL_COUNT] = HqcUiIds.PAGE_LIST_MODEL;
        for (int i = 0; i < POPUP_MODELS; i++) {
            ids[HqcUiIds.ROW_MODEL_COUNT + 1 + i] = HqcUiIds.STATUS_POPUP_VISIBLE_MODEL + i;
        }
        return ids;
    }

    /** The SDK page's ListModel (created on first use), or null before the bank exists. HMIEvent. */
    static ListModel pageList() {
        SdkCarModelBank b = current;
        if (b == null) {
            return null;
        }
        b.createModel(PAGE_LIST_INDEX);
        return (ListModel) b.models[PAGE_LIST_INDEX];
    }

    /**
     * The status popup's models, created on first use, or null before the bank exists. Called on {@code mibsdk-ui}
     * (spike C's probes created and wrote the popup's models from an SDK thread the same way, through the bank) and
     * possibly on HMIEvent; the bank's lock covers the creation. Never throws.
     */
    static SdkPopupStatusLine.Models statusModels() {
        SdkCarModelBank b = current;
        return b == null ? null : b.popupModels();
    }

    private synchronized SdkPopupStatusLine.Models popupModels() {
        if (statusModels == null) {
            try {
                for (int i = 0; i < POPUP_MODELS; i++) {
                    createModel(POPUP_INDEX + i);
                }
                ChoiceModel visible = (ChoiceModel) this.models[POPUP_INDEX];
                LabelModel[] labels = new LabelModel[HqcUiIds.STATUS_CELLS + 1];
                ResourceLocatorModel[] files = new ResourceLocatorModel[HqcUiIds.STATUS_CELLS];
                for (int i = 0; i < labels.length; i++) {
                    labels[i] = (LabelModel) this.models[index(HqcUiIds.STATUS_LABEL_MODEL_0 + i)];
                }
                for (int i = 0; i < files.length; i++) {
                    files[i] = (ResourceLocatorModel) this.models[index(HqcUiIds.STATUS_FILE_ICON_MODEL_1 + i)];
                }
                if (visible != null && !contains(labels, null) && !contains(files, null)) {
                    statusModels = new StatusModels(visible, labels, files);
                }
            } catch (Throwable t) {
                // no handle yet: SdkPopupStatusLine's show fails (logged once by the applier) and asks again next time
            }
        }
        return statusModels;
    }

    private static int index(int id) {
        return BASE_INDEX + id - HqcUiIds.MODEL_BASE;
    }

    private static boolean contains(Object[] a, Object x) {
        for (int i = 0; i < a.length; i++) {
            if (a[i] == x) {
                return true;
            }
        }
        return false;
    }

    /**
     * The grown {@code modelIDs} (stock ids, then the SDK's), as CarModelBank answers. Pinned here because
     * AbstractModelBank's own version sizes its array by the stock model count while walking the grown
     * {@code models[]}: reaching it would overflow on a stock thread once SDK models exist. {@code getModels()} (final)
     * sizes by this.
     */
    public int[] getAllModelIds() {
        return this.modelIDs;
    }

    /**
     * Stock thread (HMIEvent; and {@code mibsdk-ui} once, for the status popup's models). SDK indices: create the SDK
     * model; never throws, never runs plugin code.
     */
    protected synchronized void createModel(int i) {
        if (i < BASE_INDEX || i >= BASE_INDEX + HqcUiIds.MODEL_COUNT) {
            super.createModel(i);
            return;
        }
        if (this.models[i] != null) {
            return;
        }
        if (i == PAGE_LIST_INDEX) {
            createPageList();
            return;
        }
        if (i >= POPUP_INDEX) {
            createPopupModel(i);
            return;
        }
        if (i >= BASE_INDEX + HqcUiIds.ROW_MODEL_COUNT) {
            return; // reserved, no model
        }
        try {
            int id = HqcUiIds.MODEL_BASE + (i - BASE_INDEX);
            ChoiceModel m = new ChoiceModel(id);
            if (id == HqcUiIds.ROW_SELECTION_MODEL) {
                m.setValue(-1);
                m.setChoiceListener(new RowPressListener());
            } else {
                int slot = id - HqcUiIds.ROW_VISIBILITY_MODEL_0;
                DrawerAssignment slots = SdkGlobals.uiSlots;
                // The Plugins row is visible from the start, unless the SDK UI was already taken down or the MENU
                // ring has its own Plugins row (ACTIVE).
                boolean visible = slot == 0 ? !SdkGlobals.uiDown && SdkGlobals.drawerPluginsRowWanted()
                        : slots != null && slots.visibleAt(slot);
                m.setValue(visible ? 0 : 1);
                rowVisibility[slot] = m;
            }
            this.models[i] = m;
            publishRowModelsOnceComplete();
        } catch (Throwable t) {
            // the model stays absent: the row it backs is not shown
        }
    }

    /**
     * {@link HqcUiIds#PAGE_COLUMNS} columns: label, value, row kind (the list's record-set column), enabled, value
     * text (F14); rows are written by PageScreens. Never throws.
     */
    private void createPageList() {
        try {
            ListModel l = new ListModel(HqcUiIds.PAGE_LIST_MODEL);
            l.setMaxColumns(HqcUiIds.PAGE_COLUMNS);
            l.setMaxRows(PAGE_MAX_ROWS);
            l.setListListener(new PageListListener());
            this.models[PAGE_LIST_INDEX] = l;
        } catch (Throwable t) {
            // no list model: pages cannot be drawn (PageScreens logs it)
        }
    }

    /**
     * The status popup's models: visibility (0 hidden until the first show), the labels (empty), the file-icon
     * locators (none). Never throws.
     */
    private void createPopupModel(int i) {
        try {
            int id = HqcUiIds.MODEL_BASE + (i - BASE_INDEX);
            HMIModel m;
            if (id == HqcUiIds.STATUS_POPUP_VISIBLE_MODEL) {
                ChoiceModel c = new ChoiceModel(id);
                c.setValue(0);
                m = c;
            } else if (id < HqcUiIds.STATUS_FILE_ICON_MODEL_1) {
                LabelModel l = new LabelModel(id);
                l.setText("");
                m = l;
            } else {
                m = new ResourceLocatorModel(id);
            }
            this.models[i] = m;
        } catch (Throwable t) {
            // the model stays absent: the popup cannot be written (statusModels() stays null)
        }
    }

    private void publishRowModelsOnceComplete() {
        if (SdkGlobals.uiRowModels != null) {
            return;
        }
        for (int s = 0; s < rowVisibility.length; s++) {
            if (rowVisibility[s] == null) {
                return;
            }
        }
        SdkGlobals.uiRowModels = new VisibilityModels((ChoiceModel[]) rowVisibility.clone());
    }

    /** The {@code mibsdk-ui} thread's handle on the visibility models (0 visible, 1 hidden). */
    private static final class VisibilityModels implements RowModels {
        private final ChoiceModel[] rows;

        VisibilityModels(ChoiceModel[] rows) {
            this.rows = rows;
        }

        public void setVisible(int slot, boolean visible) {
            if (slot >= 0 && slot < rows.length) {
                rows[slot].setValue(visible ? 0 : 1);
            }
        }
    }

    /**
     * The {@code mibsdk-ui} thread's handle on the status popup's models. A locator is set with
     * {@code setResourceLocator(-1, path, 0)} and cleared with {@code (-1, "", 0)}. Status 0, as spike C's
     * {@code HMIResourceLocator(path)}: for status 1 IconRendererHigh draws the icon's bitmap 0 and never loads the URI.
     */
    private static final class StatusModels implements SdkPopupStatusLine.Models {
        private final ChoiceModel visible;
        private final LabelModel[] labels;
        private final ResourceLocatorModel[] files;

        StatusModels(ChoiceModel visible, LabelModel[] labels, ResourceLocatorModel[] files) {
            this.visible = visible;
            this.labels = labels;
            this.files = files;
        }

        public void label(int i, String text) {
            if (i >= 0 && i < labels.length) {
                labels[i].setText(text == null ? "" : text);
            }
        }

        public void fileIcon(int i, String path) {
            if (i >= 0 && i < files.length) {
                if (path == null || path.length() == 0) {
                    files[i].setResourceLocator(-1, "", 0);
                } else {
                    files[i].setResourceLocator(-1, path, 0);
                }
            }
        }

        public void visible(boolean on) {
            visible.setValue(on ? 1 : 0);
        }
    }

    /**
     * SDK row pressed (HMIEvent): {@code value} is the row's widgetID, i.e. its slot. No selection reset: a re-press
     * fires again (spike B).
     */
    private static final class RowPressListener extends DefaultChoiceListener {
        public void itemSelected(int modelId, int value, int unused, int terminal) {
            try {
                UiInputs.rowPressed(SdkGlobals.UI, SdkGlobals.uiSlots, value);
            } catch (Throwable ignored) {
                // never propagate into the HMI
            }
        }
    }

    /** A page list row was selected (HMIEvent): handed to PageScreens, which only sends input or asks for a job. */
    private static final class PageListListener implements ListListener {
        public void itemSelected(int modelId, int row, int x, int terminal) {
            try {
                PageScreens.listSelected(row);
            } catch (Throwable ignored) {
                // never propagate into the HMI
            }
        }

        public void itemFocused(int modelId, int row, int x, int terminal) {
        }

        public void itemReleased(int modelId, int row, int x, int terminal) {
        }
    }
}
