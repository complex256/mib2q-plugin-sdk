package mibsdk.api;

/**
 * A value seen at a {@link HookPoint}.
 *
 * <p>For both parking points {@link #value()} is a {@link SoundDirections}.
 *
 * @since api 1
 */
public final class HookObserved {

    private final HookPoint point;
    private final Object value;
    private final boolean vetoed;

    /**
     * @param p the point
     * @param value the value that passed it
     * @param vetoed whether a veto was active
     */
    public HookObserved(HookPoint p, Object value, boolean vetoed) {
        this.point = p;
        this.value = value;
        this.vetoed = vetoed;
    }

    /** The hook point. */
    public HookPoint point() {
        return point;
    }

    /** The value: a {@link SoundDirections} for the parking points. */
    public Object value() {
        return value;
    }

    /** Whether a plugin vetoed the value. */
    public boolean vetoed() {
        return vetoed;
    }
}
