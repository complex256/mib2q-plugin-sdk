package mibsdk.adapter.hqc.ui;

/**
 * The open slider edit screen's position, in step units: index {@code 0 .. steps} is the value
 * {@code min + index * step}. Knob turns accumulate from the last <b>requested</b> index (not from the plugin's
 * confirmed value), so a fast turn of +1, +1 asks for +1 then +2 even before the plugin's first update lands. A new
 * plugin value ({@link #onModel}) resets the index to it. HMIEvent only; stock-free.
 *
 * <p>F18 (accepted): the HMI limits each turn's {@code n} against the RangeModel's current value, which the applier
 * writes only once the plugin's update arrives. Turning back right after reaching an end can therefore lose a step
 * (the HMI sees no room and sends {@code n = 0} or no event) until the model catches up. Bench N7 rotates -/+ at
 * both ends.
 */
final class SliderSession {

    private final int min;
    private final int step;
    private final int steps;
    private final int slot;
    private int index;
    private int modelValue;

    /** A session for value {@code value} of a slider {@code min .. min + steps * step}; on no typed slot. */
    SliderSession(int min, int step, int steps, int value) {
        this(min, step, steps, value, -1);
    }

    /** As above, for the slider of typed slot {@code slot} (its screen texts are served from that row). */
    SliderSession(int min, int step, int steps, int value, int slot) {
        this.min = min;
        this.step = step < 1 ? 1 : step;
        this.steps = steps < 0 ? 0 : steps;
        this.slot = slot;
        this.modelValue = value;
        this.index = toIndex(value);
    }

    /** The current (last requested, or last confirmed) index, {@code 0 .. steps}. */
    int index() {
        return index;
    }

    /** The typed slot this session edits; -1 for none. */
    int slot() {
        return slot;
    }

    /**
     * A knob turn of {@code n} steps (+ clockwise, - counter-clockwise): moves the index from the last requested one,
     * clamped to {@code 0 .. steps}, and returns the value to request.
     */
    int onTurn(int n) {
        long next = (long) index + n;
        index = next < 0 ? 0 : next > steps ? steps : (int) next;
        return min + index * step;
    }

    /** The plugin's value in a refresh: a new one resets the index to it; the same one changes nothing. */
    void onModel(int value) {
        if (value != modelValue) {
            modelValue = value;
            index = toIndex(value);
        }
    }

    private int toIndex(int value) {
        int i = (value - min) / step;
        return i < 0 ? 0 : i > steps ? steps : i;
    }
}
