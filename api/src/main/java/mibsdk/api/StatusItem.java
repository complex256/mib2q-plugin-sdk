package mibsdk.api;

/** A plugin's status-line item. {@link #cancel()} removes it. */
public interface StatusItem extends Subscription {

    /** Longer text is truncated by the runtime, never rejected. */
    int MAX_TEXT = 12;

    /** {@code null} or {@code ""} hides the item. */
    void set(String text);
}
