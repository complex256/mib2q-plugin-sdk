package mibsdk.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Merges the status items every plugin registered into the one list the SDK renders.
 *
 * <p>The user's global order wins. Items the user has not placed yet follow, sorted by the plugin's priority
 * hint (higher first) and then by registration order, so a plugin that registers several items can keep them
 * together. Plugins never see or change another plugin's items and never control pagination.
 */
public final class StatusBarItems {

    /** One registered item: a stable {@code plugin/item} key, a priority hint and its registration sequence. */
    public static final class Item {
        private final String key;
        private final int priority;
        private final int sequence;

        public Item(String key, int priority, int sequence) {
            if (key == null || key.trim().length() == 0 || key.indexOf('/') <= 0) {
                throw new IllegalArgumentException("key must be plugin/item: " + key);
            }
            this.key = key.trim();
            this.priority = priority;
            this.sequence = sequence;
        }

        public String key() {
            return key;
        }

        public int priority() {
            return priority;
        }

        public int sequence() {
            return sequence;
        }

        @Override
        public String toString() {
            return key + "(" + priority + "," + sequence + ")";
        }
    }

    private StatusBarItems() {
    }

    /** The render order: user-placed items first in the user's order, then the rest by priority and sequence. */
    public static List<Item> order(List<Item> items, StatusBarConfig config) {
        List<Item> out = new ArrayList<Item>(items);
        final StatusBarConfig c = config == null ? StatusBarConfig.defaults() : config;
        Collections.sort(out, new Comparator<Item>() {
            public int compare(Item a, Item b) {
                int ra = c.rank(a.key);
                int rb = c.rank(b.key);
                if (ra != rb) {
                    return ra < rb ? -1 : 1;
                }
                if (a.priority != b.priority) {
                    return a.priority > b.priority ? -1 : 1;
                }
                return a.sequence < b.sequence ? -1 : a.sequence > b.sequence ? 1 : 0;
            }
        });
        return out;
    }
}
