package mibsdk.platform;

import java.util.ArrayList;
import java.util.List;

/**
 * Navigation stack of SDK page keys. Stock-free; single-threaded (HMIEvent on the unit).
 *
 * <p>Exactly one SDK page is "open" at a time: whenever the visible (top) page changes, the optional
 * {@link Listener} gets {@code closed(previous top)} if any, then {@code shown(new top)} if any.
 */
public final class PageStack {

    public static final int MAX_DEPTH = 8;
    private static final String STOPPED = "mibsdk/stopped";

    /** Receives the paired page events; shared by the fake UI and the HQC adapter. */
    public interface Listener {
        void shown(String pageKey);

        void closed(String pageKey);
    }

    private final List<String> keys = new ArrayList<String>();
    private final Listener listener;
    private Object bottomReturnTo;

    public PageStack() {
        this(null);
    }

    public PageStack(Listener listener) {
        this.listener = listener;
    }

    /** Pushes a page. {@code returnTo} is kept only when the stack was empty (it belongs to the bottom page). */
    public void push(String pageKey, Object returnTo) {
        String old = top();
        if (keys.isEmpty()) {
            bottomReturnTo = returnTo;
        } else if (keys.size() == MAX_DEPTH) {
            keys.remove(1); // drop the oldest above the bottom
        }
        keys.add(pageKey);
        changed(old);
    }

    /** Replaces the whole stack by this single page (a runtime open request). */
    public void open(String pageKey, Object returnTo) {
        String old = top();
        keys.clear();
        bottomReturnTo = returnTo;
        keys.add(pageKey);
        changed(old);
    }

    /** Pops; returns the returnTo of the bottom page when the stack becomes empty, else null. */
    public Object back() {
        if (keys.isEmpty()) {
            return null;
        }
        String old = top();
        keys.remove(keys.size() - 1);
        Object r = null;
        if (keys.isEmpty()) {
            r = bottomReturnTo;
            bottomReturnTo = null;
        }
        changed(old);
        return r;
    }

    /** The visible page key, or null when empty. */
    public String top() {
        return keys.isEmpty() ? null : keys.get(keys.size() - 1);
    }

    /** Replaces vanished pages with "mibsdk/stopped" unless the snapshot carries a page under the same key. */
    public void reconcile(UiSnapshot s) {
        String old = top();
        for (int i = 0; i < keys.size(); i++) {
            String k = keys.get(i);
            if (!STOPPED.equals(k) && !s.pages().containsKey(k)) {
                keys.set(i, STOPPED);
            }
        }
        changed(old);
    }

    private void changed(String old) {
        String now = top();
        if (listener == null || (old == null ? now == null : old.equals(now))) {
            return;
        }
        if (old != null) {
            listener.closed(old);
        }
        if (now != null) {
            listener.shown(now);
        }
    }
}
