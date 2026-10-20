package mibsdk.adapter.hqc.ui;

import de.audi.atip.hmi.model.ChoiceModel;
import de.audi.atip.hmi.model.HMIModel;
import de.audi.atip.hmi.model.listener.DefaultChoiceListener;
import de.audi.atip.model.CarModelBank;
import mibsdk.adapter.hqc.SdkGlobals;

/**
 * CarModelBank grown by {@link HqcUiIds#MODEL_COUNT} models at {@link HqcUiIds#MODEL_BASE} (spike A's technique): a
 * visibility ChoiceModel per drawer slot and the selection ChoiceModel shared by the SDK rows.
 *
 * <p>Built on LSD's startup thread (arrays only: no thread, no I/O). The stock framework creates the models on
 * HMIEvent, through {@link #createModel}, when the drawer is first built; once every row model exists the bank
 * publishes them as {@code SdkGlobals.uiRowModels}. The selection listener only drops the press into
 * {@code SdkGlobals.UI}.
 */
final class SdkCarModelBank extends CarModelBank {

    /** Index of {@link HqcUiIds#MODEL_BASE} in {@code models[]} (ids are module * 100000 + index). */
    private static final int BASE_INDEX = HqcUiIds.MODEL_BASE - HqcUiIds.CAR_MODULE * 100000;

    /** Visibility models by slot; written under the bank's lock (createModel), published through SdkGlobals. */
    private final ChoiceModel[] rowVisibility = new ChoiceModel[HqcUiIds.ROW_SLOTS];

    SdkCarModelBank() {
        int stockLen = this.models.length;
        if (stockLen > BASE_INDEX) {
            throw new IllegalStateException("Car bank has " + stockLen + " models; SDK ids start at " + BASE_INDEX);
        }
        HMIModel[] grown = new HMIModel[BASE_INDEX + HqcUiIds.MODEL_COUNT];
        System.arraycopy(this.models, 0, grown, 0, stockLen);
        int stockIds = this.modelIDs.length;
        int[] ids = new int[stockIds + HqcUiIds.MODEL_COUNT];
        System.arraycopy(this.modelIDs, 0, ids, 0, stockIds);
        for (int i = 0; i < HqcUiIds.MODEL_COUNT; i++) {
            ids[stockIds + i] = HqcUiIds.MODEL_BASE + i;
        }
        this.models = grown;
        this.modelIDs = ids;
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

    /** Stock thread (HMIEvent). SDK indices: create the SDK model; never throws, never runs plugin code. */
    protected synchronized void createModel(int i) {
        if (i < BASE_INDEX || i >= BASE_INDEX + HqcUiIds.MODEL_COUNT) {
            super.createModel(i);
            return;
        }
        if (this.models[i] != null) {
            return;
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
                // The Plugins row is visible from the start, unless the SDK UI was already taken down.
                boolean visible = slot == 0 ? !SdkGlobals.uiDown : slots != null && slots.visibleAt(slot);
                m.setValue(visible ? 0 : 1);
                rowVisibility[slot] = m;
            }
            this.models[i] = m;
            publishRowModelsOnceComplete();
        } catch (Throwable t) {
            // the model stays absent: the row it backs is not shown
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
}
