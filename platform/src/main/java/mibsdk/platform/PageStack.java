package mibsdk.platform;

import java.util.ArrayList;
import java.util.List;

/** Navigation stack of SDK page keys. Stock-free; single-threaded (HMIEvent on the unit). */
public final class PageStack {

    public static final int MAX_DEPTH = 8;
    private static final String STOPPED = "mibsdk/stopped";

    private final List<String> keys = new ArrayList<String>();
    private Object bottomReturnTo;

    /** Pushes a page. {@code returnTo} is kept only when the stack was empty (it belongs to the bottom page). */
    public void push(String pageKey, Object returnTo) {
        if (keys.isEmpty()) {
            bottomReturnTo = returnTo;
        } else if (keys.size() == MAX_DEPTH) {
            keys.remove(1); // drop the oldest above the bottom
        }
        keys.add(pageKey);
    }

    /** Pops; returns the returnTo of the bottom page when the stack becomes empty, else null. */
    public Object back() {
        if (keys.isEmpty()) {
            return null;
        }
        keys.remove(keys.size() - 1);
        if (keys.isEmpty()) {
            Object r = bottomReturnTo;
            bottomReturnTo = null;
            return r;
        }
        return null;
    }

    /** The visible page key, or null when empty. */
    public String top() {
        return keys.isEmpty() ? null : keys.get(keys.size() - 1);
    }

    /** Replaces vanished pages with "mibsdk/stopped" unless the snapshot carries a page under the same key. */
    public void reconcile(UiSnapshot s) {
        for (int i = 0; i < keys.size(); i++) {
            String k = keys.get(i);
            if (!STOPPED.equals(k) && !s.pages().containsKey(k)) {
                keys.set(i, STOPPED);
            }
        }
    }
}
