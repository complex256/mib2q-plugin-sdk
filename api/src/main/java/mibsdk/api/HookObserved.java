package mibsdk.api;

/** A value seen at a hook point. For both parking points {@link #value()} is a {@link SoundDirections}. */
public final class HookObserved {

    private final HookPoint point;
    private final Object value;
    private final boolean vetoed;

    public HookObserved(HookPoint p, Object value, boolean vetoed) {
        this.point = p;
        this.value = value;
        this.vetoed = vetoed;
    }

    public HookPoint point() {
        return point;
    }

    public Object value() {
        return value;
    }

    public boolean vetoed() {
        return vetoed;
    }
}
