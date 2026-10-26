package mibsdk.adapter.hqc.ui;

import de.audi.atip.hmi.model.ChoiceModel;
import de.audi.atip.hmi.model.HMIModel;
import de.audi.atip.hmi.model.LabelModel;
import de.audi.atip.hmi.model.ListListener;
import de.audi.atip.hmi.model.ListModel;
import de.audi.atip.hmi.model.RangeListener;
import de.audi.atip.hmi.model.RangeModel;
import de.audi.atip.hmi.model.ResourceLocatorModel;
import de.audi.atip.hmi.model.SpellerListener;
import de.audi.atip.hmi.model.SpellerModel;
import de.audi.atip.hmi.model.listener.DefaultChoiceListener;
import de.audi.atip.model.CarModelBank;
import mibsdk.adapter.hqc.SdkGlobals;

/**
 * CarModelBank grown at {@link HqcUiIds#MODEL_BASE} (spike A's technique): a visibility ChoiceModel per drawer slot,
 * the selection ChoiceModel shared by the SDK rows, the SDK page's ListModel ({@link HqcUiIds#PAGE_LIST_MODEL}), and
 * the status popup's models (its visibility ChoiceModel, 7 LabelModels and 6 ResourceLocatorModels, from
 * {@link HqcUiIds#STATUS_POPUP_VISIBLE_MODEL}), the typed rows' pooled models (4 ChoiceModels from
 * {@link HqcUiIds#TYPED_CHOICE_MODEL_0}, 4 RangeModels from {@link HqcUiIds#TYPED_RANGE_MODEL_0}), the confirm
 * ListModel, the SpellerModel and the toast's ChoiceModel, LabelModel and ResourceLocatorModel. {@code +10},
 * {@code +34}, {@code +40} and {@code +41} are reserved with no model.
 *
 * <p>Built on LSD's startup thread (arrays only: no thread, no I/O). The stock framework creates the models on
 * HMIEvent, through {@link #createModel}, when the drawer is first built; once every row model exists the bank
 * publishes them as {@code SdkGlobals.uiRowModels}. The selection listener only drops the press into
 * {@code SdkGlobals.UI}; the page list's listener only hands the row to {@link PageScreens#listSelected}; the typed
 * ChoiceModels' listener hands the pick to {@link PageScreens#typedChosen} and the RangeModels' the knob turn to
 * {@link PageScreens#sliderTurned}; the confirm list's listener the row to {@link PageScreens#confirmSelected}, the
 * SpellerModel's its text and band commands to {@link PageScreens#spellerTextChanged} and
 * {@link PageScreens#spellerCommand}. The typed models are written only on {@code mibsdk-ui} ({@link #typedModels}).
 */
final class SdkCarModelBank extends CarModelBank {

    /** Index of {@link HqcUiIds#MODEL_BASE} in {@code models[]} (ids are module * 100000 + index). */
    private static final int BASE_INDEX = HqcUiIds.MODEL_BASE - HqcUiIds.CAR_MODULE * 100000;
    /** Index of the page ListModel; the reserved ids between the row models and it have no model. */
    private static final int PAGE_LIST_INDEX = BASE_INDEX + HqcUiIds.PAGE_LIST_MODEL - HqcUiIds.MODEL_BASE;
    /** Index of the status popup's first model; it and the next {@link #POPUP_MODELS} are the popup's (+12..+25). */
    private static final int POPUP_INDEX = BASE_INDEX + HqcUiIds.STATUS_POPUP_VISIBLE_MODEL - HqcUiIds.MODEL_BASE;
    private static final int POPUP_MODELS = HqcUiIds.STATUS_FILE_ICON_MODEL_1 + HqcUiIds.STATUS_CELLS
            - HqcUiIds.STATUS_POPUP_VISIBLE_MODEL;
    /** Indices of the typed rows' first ChoiceModel and first RangeModel. */
    private static final int TYPED_CHOICE_INDEX = index(HqcUiIds.TYPED_CHOICE_MODEL_0);
    private static final int TYPED_RANGE_INDEX = index(HqcUiIds.TYPED_RANGE_MODEL_0);
    /** The SDK model ids the bank serves, ascending: every id of the SDK range but the reserved ones. */
    private static final int[] SDK_IDS = sdkIds();

    /** The bank SDK pages write their rows to; null until built. */
    private static volatile SdkCarModelBank current;

    /** A page holds at most 24 entries (PageSpec); the stopped notice has one. */
    private static final int PAGE_MAX_ROWS = 24;

    /** Visibility models by slot; written under the bank's lock (createModel), published through SdkGlobals. */
    private final ChoiceModel[] rowVisibility = new ChoiceModel[HqcUiIds.ROW_SLOTS];
    /** The status popup's models, by handle; created once with them ({@link #statusModels}). */
    private StatusModels statusModels; // guarded by this
    /** The typed rows' pooled models, by handle; created once with them ({@link #typedModels}). */
    private TypedHandle typedModels; // guarded by this
    /** The toast popup's models, by handle; created once with them ({@link #toastModels}). */
    private ToastHandle toastModels; // guarded by this

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
        int n = 0;
        for (int i = 0; i < HqcUiIds.MODEL_COUNT; i++) {
            n += reserved(HqcUiIds.MODEL_BASE + i) ? 0 : 1;
        }
        int[] ids = new int[n];
        int k = 0;
        for (int i = 0; i < HqcUiIds.MODEL_COUNT; i++) {
            if (!reserved(HqcUiIds.MODEL_BASE + i)) {
                ids[k++] = HqcUiIds.MODEL_BASE + i;
            }
        }
        return ids;
    }

    /** The SDK ids with no model: {@code +10}, {@code +34}, {@code +40} and {@code +41} (F22). */
    static boolean reserved(int id) {
        int off = id - HqcUiIds.MODEL_BASE;
        return off == HqcUiIds.ROW_MODEL_COUNT || id == HqcUiIds.CONFIRM_TEXT_RESERVED
                || off >= HqcUiIds.TOAST_FILE_ICON_MODEL + 1 - HqcUiIds.MODEL_BASE;
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

    /** The confirm screen's Yes/No ListModel (created on first use), or null before the bank exists. HMIEvent. */
    static ListModel confirmList() {
        return (ListModel) model(HqcUiIds.CONFIRM_LIST_MODEL);
    }

    /** The speller screen's SpellerModel (created on first use), or null before the bank exists. HMIEvent. */
    static SpellerModel speller() {
        return (SpellerModel) model(HqcUiIds.SPELLER_MODEL);
    }

    private static HMIModel model(int id) {
        SdkCarModelBank b = current;
        if (b == null) {
            return null;
        }
        b.createModel(index(id));
        return b.models[index(id)];
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

    /**
     * The toast popup's models (visibility, label, file icon: +37..+39), created on first use, or null before the
     * bank exists. Called on {@code mibsdk-ui} only (Global Constraints, F15). Never throws.
     */
    static SdkToastPopup.Models toastModels() {
        SdkCarModelBank b = current;
        return b == null ? null : b.toastHandle();
    }

    private synchronized SdkToastPopup.Models toastHandle() {
        if (toastModels == null) {
            try {
                createModel(index(HqcUiIds.TOAST_VISIBLE_MODEL));
                createModel(index(HqcUiIds.TOAST_LABEL_MODEL));
                createModel(index(HqcUiIds.TOAST_FILE_ICON_MODEL));
                Object v = this.models[index(HqcUiIds.TOAST_VISIBLE_MODEL)];
                Object l = this.models[index(HqcUiIds.TOAST_LABEL_MODEL)];
                Object f = this.models[index(HqcUiIds.TOAST_FILE_ICON_MODEL)];
                if (v instanceof ChoiceModel && l instanceof LabelModel && f instanceof ResourceLocatorModel) {
                    toastModels = new ToastHandle((ChoiceModel) v, (LabelModel) l, (ResourceLocatorModel) f);
                }
            } catch (Throwable t) {
                // no handle yet: the applier asks again for the next toast
            }
        }
        return toastModels;
    }

    private static int index(int id) {
        return BASE_INDEX + id - HqcUiIds.MODEL_BASE;
    }

    /**
     * The typed rows' pooled models, created on first use, or null before the bank exists. Called on
     * {@code mibsdk-ui} only (the applier writes them; Global Constraints, F15). Never throws.
     */
    static UiApplier.TypedModels typedModels() {
        SdkCarModelBank b = current;
        return b == null ? null : b.typedHandle();
    }

    private synchronized UiApplier.TypedModels typedHandle() {
        if (typedModels == null) {
            try {
                ChoiceModel[] choices = new ChoiceModel[HqcUiIds.TYPED_ROWS];
                RangeModel[] ranges = new RangeModel[HqcUiIds.TYPED_ROWS];
                for (int s = 0; s < HqcUiIds.TYPED_ROWS; s++) {
                    createModel(TYPED_CHOICE_INDEX + s);
                    createModel(TYPED_RANGE_INDEX + s);
                    choices[s] = (ChoiceModel) this.models[TYPED_CHOICE_INDEX + s];
                    ranges[s] = (RangeModel) this.models[TYPED_RANGE_INDEX + s];
                }
                if (!contains(choices, null) && !contains(ranges, null)) {
                    typedModels = new TypedHandle(choices, ranges);
                }
            } catch (Throwable t) {
                // no handle yet: the applier asks again on its next pass
            }
        }
        return typedModels;
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
        if (i >= POPUP_INDEX && i < POPUP_INDEX + POPUP_MODELS) {
            createPopupModel(i);
            return;
        }
        if (i >= BASE_INDEX + HqcUiIds.ROW_MODEL_COUNT) {
            createInputModel(i); // the typed rows', the dialogs' and the toast's; none for a reserved id
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
     * text (F14), row id (the list's id column, bench B); rows are written by PageScreens. Never throws.
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

    /**
     * The typed rows' pooled models (a ChoiceModel with its pick listener, a RangeModel in index space with its knob
     * listener; written on {@code mibsdk-ui}), the confirm ListModel (the page list's columns), the SpellerModel
     * and the toast's models (hidden, empty, no icon). A reserved id gets none. Never throws.
     */
    private void createInputModel(int i) {
        try {
            int id = HqcUiIds.MODEL_BASE + (i - BASE_INDEX);
            HMIModel m;
            if (i >= TYPED_CHOICE_INDEX && i < TYPED_CHOICE_INDEX + HqcUiIds.TYPED_ROWS) {
                ChoiceModel c = new ChoiceModel(id);
                c.setValue(0);
                c.setChoiceListener(new TypedChoiceListener(i - TYPED_CHOICE_INDEX));
                m = c;
            } else if (i >= TYPED_RANGE_INDEX && i < TYPED_RANGE_INDEX + HqcUiIds.TYPED_ROWS) {
                RangeModel r = new RangeModel(id);
                r.setLimits(0, 1, 1); // a one-step range until the applier writes the shown slider's
                r.setValue(0);
                r.setRangeListener(new SliderRangeListener(i - TYPED_RANGE_INDEX));
                m = r;
            } else if (id == HqcUiIds.CONFIRM_LIST_MODEL) {
                ListModel l = new ListModel(id);
                l.setMaxColumns(HqcUiIds.PAGE_COLUMNS);
                l.setMaxRows(2);
                l.setListListener(new ConfirmListListener());
                m = l;
            } else if (id == HqcUiIds.SPELLER_MODEL) {
                SpellerModel sp = new SpellerModel(id);
                sp.setSpellerListener(new DialogSpellerListener());
                m = sp;
            } else if (id == HqcUiIds.TOAST_VISIBLE_MODEL) {
                ChoiceModel c = new ChoiceModel(id);
                c.setValue(0);
                m = c;
            } else if (id == HqcUiIds.TOAST_LABEL_MODEL) {
                LabelModel l = new LabelModel(id);
                l.setText("");
                m = l;
            } else if (id == HqcUiIds.TOAST_FILE_ICON_MODEL) {
                m = new ResourceLocatorModel(id);
            } else {
                return; // reserved, no model
            }
            this.models[i] = m;
        } catch (Throwable t) {
            // the model stays absent: the widget it backs is not drawn
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
     * The {@code mibsdk-ui} thread's handle on the toast popup's models; a locator as {@link StatusModels} sets it.
     * It holds none of the status popup's models.
     */
    private static final class ToastHandle implements SdkToastPopup.Models {
        private final ChoiceModel visible;
        private final LabelModel label;
        private final ResourceLocatorModel file;

        ToastHandle(ChoiceModel visible, LabelModel label, ResourceLocatorModel file) {
            this.visible = visible;
            this.label = label;
            this.file = file;
        }

        public void label(String text) {
            label.setText(text == null ? "" : text);
        }

        public void fileIcon(String path) {
            file.setResourceLocator(-1, path == null ? "" : path, 0);
        }

        public void visible(boolean on) {
            visible.setValue(on ? 1 : 0);
        }
    }

    /**
     * The {@code mibsdk-ui} thread's handle on the typed rows' pooled models: a dropdown's selected index; a slider in
     * index space ({@code setLimits(0, steps, 1)}, its medial position, the index), F13.
     */
    private static final class TypedHandle implements UiApplier.TypedModels {
        private final ChoiceModel[] choices;
        private final RangeModel[] ranges;

        TypedHandle(ChoiceModel[] choices, RangeModel[] ranges) {
            this.choices = choices;
            this.ranges = ranges;
        }

        public void choice(int slot, int index) {
            if (slot >= 0 && slot < choices.length) {
                choices[slot].setValue(index);
            }
        }

        public void range(int slot, int steps, int medial, int index) {
            if (slot >= 0 && slot < ranges.length) {
                RangeModel r = ranges[slot];
                r.setMedialPosition(medial);
                r.setLimits(0, steps, 1);
                r.setValue(index);
            }
        }
    }

    /** A dropdown option picked (HMIEvent): its index to PageScreens, which only sends input. Focus is ignored. */
    private static final class TypedChoiceListener extends DefaultChoiceListener {
        private final int slot;

        TypedChoiceListener(int slot) {
            this.slot = slot;
        }

        public void itemSelected(int modelId, int value, int unused, int terminal) {
            try {
                PageScreens.typedChosen(slot, value);
            } catch (Throwable ignored) {
                // never propagate into the HMI
            }
        }

        public void itemFocused(int modelId, int value, int unused, int terminal) {
        }
    }

    /**
     * A knob turn on the slider screen (HMIEvent; {@code n} already limited by the HMI against the model's value):
     * +n / -n to PageScreens, which only sends input. The other keys are ignored.
     */
    private static final class SliderRangeListener implements RangeListener {
        private final int slot;

        SliderRangeListener(int slot) {
            this.slot = slot;
        }

        public void increment(int modelId, int n, int terminal) {
            try {
                PageScreens.sliderTurned(slot, n);
            } catch (Throwable ignored) {
                // never propagate into the HMI
            }
        }

        public void decrement(int modelId, int n, int terminal) {
            try {
                PageScreens.sliderTurned(slot, -n);
            } catch (Throwable ignored) {
                // never propagate into the HMI
            }
        }

        public void keyPressed(int modelId, int key, int terminal) {
        }

        public void keyReleased(int modelId, int key, int terminal) {
        }

        public void keyTyped(int modelId, int key, int terminal) {
        }

        public void keyLongTyped(int modelId, int key, int terminal) {
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

    /** A confirm row was selected (HMIEvent): handed to PageScreens, which only sends input or asks for a job. */
    private static final class ConfirmListListener implements ListListener {
        public void itemSelected(int modelId, int row, int x, int terminal) {
            try {
                PageScreens.confirmSelected(row);
            } catch (Throwable ignored) {
                // never propagate into the HMI
            }
        }

        public void itemFocused(int modelId, int row, int x, int terminal) {
        }

        public void itemReleased(int modelId, int row, int x, int terminal) {
        }
    }

    /** The speller's text and band commands (HMIEvent): handed to PageScreens' open speller session. */
    private static final class DialogSpellerListener implements SpellerListener {
        public void keyPressed(int modelId, int key, int terminal) {
        }

        public void keyReleased(int modelId, int key, int terminal) {
        }

        public void keyTyped(int modelId, int key, int terminal) {
        }

        public void textChanged(int modelId, String text, char c, int terminal) {
            try {
                PageScreens.spellerTextChanged(text);
            } catch (Throwable ignored) {
                // never propagate into the HMI
            }
        }

        public void focusedCharacter(int modelId, char c, int terminal) {
        }

        public void commandPressed(int modelId, int command, int terminal) {
            try {
                PageScreens.spellerCommand(command);
            } catch (Throwable ignored) {
                // never propagate into the HMI
            }
        }
    }
}
