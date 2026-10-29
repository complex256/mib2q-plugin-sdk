package mibsdk.api;

/**
 * A trailer was hitched or unhitched.
 *
 * <p>Delivered asynchronously, after the fact, on the plugin's worker thread.
 *
 * @since api 1
 */
public final class TrailerHitchedChanged {

    private final boolean hitched;

    /**
     * @param hitched true when a trailer is hitched
     */
    public TrailerHitchedChanged(boolean hitched) {
        this.hitched = hitched;
    }

    /** Whether a trailer is hitched. */
    public boolean isHitched() {
        return hitched;
    }
}
