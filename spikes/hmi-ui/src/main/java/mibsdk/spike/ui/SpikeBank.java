package mibsdk.spike.ui;

import de.audi.atip.hmi.model.ChoiceModel;
import de.audi.atip.hmi.model.HMIModel;
import de.audi.atip.hmi.model.LabelModel;
import de.audi.atip.hmi.model.listener.DefaultChoiceListener;
import de.audi.atip.model.CarModelBank;

/**
 * CarModelBank plus MODEL_COUNT spike models at MODEL_BASE.. (the Tone bank-growing technique): 9 row-visibility
 * ChoiceModels, one shared selection ChoiceModel, one LabelModel for row 0's %1 replacement.
 */
final class SpikeBank extends CarModelBank {
    static volatile SpikeBank instance;

    private final int base;

    private SpikeBank() {
        int stockLen = this.models.length;
        int stockIds = this.modelIDs.length;
        base = SpikeIds.MODEL_BASE % 100000;
        int needed = base + SpikeIds.MODEL_COUNT;
        HMIModel[] grown = new HMIModel[Math.max(needed, stockLen)];
        System.arraycopy(this.models, 0, grown, 0, stockLen);
        this.models = grown;
        int[] ids = new int[stockIds + SpikeIds.MODEL_COUNT];
        System.arraycopy(this.modelIDs, 0, ids, 0, stockIds);
        for (int i = 0; i < SpikeIds.MODEL_COUNT; i++) {
            ids[stockIds + i] = SpikeIds.MODEL_BASE + i;
        }
        this.modelIDs = ids;
        SpikeLog.log("bank stock models.length=" + stockLen + " modelIDs.length=" + stockIds
                + " -> first free id " + (SpikeIds.CAR_MODULE * 100000 + stockLen)
                + " (MODEL_BASE=" + SpikeIds.MODEL_BASE + ")");
    }

    static CarModelBank create() {
        try {
            SpikeBank b = new SpikeBank();
            instance = b;
            return b;
        } catch (Throwable t) {
            SpikeLog.log("bank FAILED, stock bank: " + t);
            return new CarModelBank();
        }
    }

    protected synchronized void createModel(int i) {
        if (i < base || i >= base + SpikeIds.MODEL_COUNT) {
            super.createModel(i);
            return;
        }
        if (this.models[i] != null) {
            return;
        }
        int id = SpikeIds.MODEL_BASE + (i - base);
        HMIModel m;
        if (id == SpikeIds.PAGE_LIST_MODEL) {
            m = SpikePage.createListModel(id);
        } else if (id == SpikeIds.PAGE_LABEL_MODEL) {
            LabelModel l = new LabelModel(id);
            l.setText("Info: LABEL row on LabelModel " + id);
            m = l;
        } else if (id == SpikeIds.LABEL_MODEL) {
            LabelModel l = new LabelModel(id);
            l.setText("m-A");
            m = l;
        } else if (id == SpikeIds.SELECTION_MODEL) {
            ChoiceModel c = new ChoiceModel(id);
            c.setValue(-1);
            c.setChoiceListener(new DefaultChoiceListener() {
                public void itemSelected(int modelId, int value, int unused, int terminal) {
                    try {
                        SpikeLog.log("press slot=" + value + " thread=" + Thread.currentThread().getName()
                                + " model=" + modelId + " terminal=" + terminal + " modelValue="
                                + ((ChoiceModel) getModel(modelId)).getValue());
                        if (value == 0) {
                            SpikePage.postShowDirect();
                        } else if (value == 1) {
                            SpikePage.hijack();
                        }
                        if (SpikeProbes.resetSelection) {
                            ((ChoiceModel) getModel(modelId)).setValue(-1);
                            SpikeLog.log("press selection model reset to -1");
                        }
                    } catch (Throwable t) {
                        SpikeLog.log("press handling FAILED: " + t);
                    }
                }
            });
            m = c;
        } else {
            ChoiceModel c = new ChoiceModel(id);
            c.setValue(id == SpikeIds.MODEL_BASE || id == SpikeIds.MODEL_BASE + 2 ? 0 : 1);   // rows 0, 2 visible
            m = c;
        }
        this.models[i] = m;
        SpikeLog.log("bank created model " + id + " on " + Thread.currentThread().getName());
    }

    /** The spike model with this id (created on demand); null if absent. */
    HMIModel spikeModel(int id) {
        return getModel(id);
    }
}
