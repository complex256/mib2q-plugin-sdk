package mibsdk.spike.ui;

import de.audi.atip.hmi.model.ChoiceModel;
import de.audi.atip.hmi.model.HMIModel;
import de.audi.atip.hmi.model.LabelModel;
import de.audi.atip.hmi.model.ListCell;
import de.audi.atip.hmi.model.ListListener;
import de.audi.atip.hmi.model.ListModel;
import de.audi.atip.hmi.model.RangeListener;
import de.audi.atip.hmi.model.RangeModel;
import de.audi.atip.hmi.model.SpellerListener;
import de.audi.atip.hmi.model.SpellerModel;
import de.audi.atip.hmi.model.TextListCell;
import de.audi.atip.hmi.model.listener.DefaultChoiceListener;

/**
 * Spike D: the models of the typed rows and of the second-level SDK screens, created by SpikeBank. Every listener
 * logs "spike.ui" and sets the value itself (the stock app-sets-value pattern): the type-8 ChoiceModel toggles, the
 * combo ChoiceModel takes the picked index, the RangeModel clamps v +/- n into its limits.
 */
final class SpikeInputs {
    static final int RANGE_MIN = -6;
    static final int RANGE_MAX = 6;
    static final String[] CONFIRM_ROWS = {"Yes", "No"};

    private SpikeInputs() {
    }

    static HMIModel createModel(int id) {
        if (id == SpikeIds.ROW8_MODEL) {
            final ChoiceModel c = new ChoiceModel(id);
            c.setValue(0);
            c.setChoiceListener(new LoggingChoice("row8") {
                public void itemSelected(int modelId, int value, int unused, int terminal) {
                    int v = c.getValue() == 0 ? 1 : 0;
                    SpikeLog.log("choice " + modelId + " itemSelected value=" + value + " -> toggle " + v + " thread="
                            + Thread.currentThread().getName());
                    c.setValue(v);
                }
            });
            return c;
        }
        if (id == SpikeIds.COMBO_MODEL) {
            final ChoiceModel c = new ChoiceModel(id);
            c.setValue(0);
            c.setChoiceListener(new LoggingChoice("combo") {
                public void itemSelected(int modelId, int value, int unused, int terminal) {
                    SpikeLog.log("choice " + modelId + " value=" + value + " terminal=" + terminal + " thread="
                            + Thread.currentThread().getName());
                    c.setValue(value);
                }
            });
            return c;
        }
        if (id == SpikeIds.RANGE_MODEL) {
            final RangeModel r = new RangeModel(id);
            r.setLimits(RANGE_MIN, RANGE_MAX, 1);
            r.setMedialPosition(0);
            r.setValue(0);
            r.setRangeListener(new RangeListener() {
                public void increment(int modelId, int n, int terminal) {
                    int v = clamp(r.getValue() + n);
                    SpikeLog.log("range " + modelId + " increment n=" + n + " -> " + v + " thread="
                            + Thread.currentThread().getName());
                    r.setValue(v);
                }

                public void decrement(int modelId, int n, int terminal) {
                    int v = clamp(r.getValue() - n);
                    SpikeLog.log("range " + modelId + " decrement n=" + n + " -> " + v + " thread="
                            + Thread.currentThread().getName());
                    r.setValue(v);
                }

                public void keyPressed(int modelId, int key, int terminal) {
                    SpikeLog.log("range " + modelId + " keyPressed key=" + key);
                }

                public void keyReleased(int modelId, int key, int terminal) {
                    SpikeLog.log("range " + modelId + " keyReleased key=" + key);
                }

                public void keyTyped(int modelId, int key, int terminal) {
                    SpikeLog.log("range " + modelId + " keyTyped key=" + key);
                }

                public void keyLongTyped(int modelId, int key, int terminal) {
                    SpikeLog.log("range " + modelId + " keyLongTyped key=" + key);
                }
            });
            return r;
        }
        if (id == SpikeIds.CONFIRM_LABEL_MODEL) {
            LabelModel l = new LabelModel(id);
            l.setText("Confirm text (LabelModel)");
            return l;
        }
        if (id == SpikeIds.CONFIRM_LIST_MODEL) {
            ListModel l = new ListModel(id);
            l.setMaxColumns(3);
            l.setMaxRows(4);
            for (int i = 0; i < CONFIRM_ROWS.length; i++) {
                l.addRow(new ListCell[] {TextListCell.create(CONFIRM_ROWS[i]),
                        de.audi.atip.hmi.model.IntegerListCell.create(0),
                        de.audi.atip.hmi.model.IntegerListCell.create(0)});
            }
            l.setListListener(new ListListener() {
                public void itemSelected(int modelId, int row, int x, int terminal) {
                    try {
                        SpikeLog.log("confirm row=" + row + " ("
                                + (row >= 0 && row < CONFIRM_ROWS.length ? CONFIRM_ROWS[row] : "?") + ") thread="
                                + Thread.currentThread().getName());
                        SpikeScreens.backToPage("confirm-select");
                    } catch (Throwable t) {
                        SpikeLog.log("confirm select FAILED: " + t);
                    }
                }

                public void itemFocused(int modelId, int row, int x, int terminal) {
                    SpikeLog.log("confirm focus row=" + row);
                }

                public void itemReleased(int modelId, int row, int x, int terminal) {
                }
            });
            return l;
        }
        if (id == SpikeIds.SPELLER_MODEL) {
            final SpellerModel s = new SpellerModel(id);
            s.setMaxLength(20);
            s.setText("");
            s.setSpellerListener(new SpellerListener() {
                public void keyPressed(int modelId, int key, int terminal) {
                    SpikeLog.log("speller " + modelId + " keyPressed key=" + key);
                }

                public void keyReleased(int modelId, int key, int terminal) {
                    SpikeLog.log("speller " + modelId + " keyReleased key=" + key);
                }

                public void keyTyped(int modelId, int key, int terminal) {
                    SpikeLog.log("speller " + modelId + " keyTyped key=" + key);
                }

                public void textChanged(int modelId, String text, char c, int terminal) {
                    SpikeLog.log("speller " + modelId + " textChanged text='" + text + "' char=" + (int) c + " thread="
                            + Thread.currentThread().getName());
                }

                public void focusedCharacter(int modelId, char c, int terminal) {
                    SpikeLog.log("speller " + modelId + " focusedCharacter char=" + (int) c);
                }

                public void commandPressed(int modelId, int command, int terminal) {
                    SpikeLog.log("speller " + modelId + " commandPressed command=" + command + " modelText='"
                            + s.getText() + "' thread=" + Thread.currentThread().getName());
                }
            });
            return s;
        }
        return new ChoiceModel(id);
    }

    static int clamp(int v) {
        return v < RANGE_MIN ? RANGE_MIN : (v > RANGE_MAX ? RANGE_MAX : v);
    }

    /** A ChoiceListener that logs the ButtonListener callbacks too (the type-8 key order question, §1.3). */
    abstract static class LoggingChoice extends DefaultChoiceListener {
        private final String what;

        LoggingChoice(String what) {
            this.what = what;
        }

        public void itemFocused(int modelId, int value, int unused, int terminal) {
            SpikeLog.log("choice " + what + " " + modelId + " itemFocused value=" + value);
        }

        public void keyPressed(int modelId, int key, int terminal) {
            SpikeLog.log("choice " + what + " " + modelId + " keyPressed key=" + key);
        }

        public void keyReleased(int modelId, int key, int terminal) {
            SpikeLog.log("choice " + what + " " + modelId + " keyReleased key=" + key);
        }

        public void keyTyped(int modelId, int key, int terminal) {
            SpikeLog.log("choice " + what + " " + modelId + " keyTyped key=" + key);
        }
    }
}
