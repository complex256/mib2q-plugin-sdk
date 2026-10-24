package mibsdk.adapter.hqc.ui;

import mibsdk.platform.UiSnapshot;

/** Draws SDK pages. Both methods run inside an {@link HmiPoster} job, on HMIEvent. */
interface PageRenderer {

    /**
     * The runtime asked to open {@code pageKey} (a new {@code openSeq}), {@code requestAgeMs} ago on the monotonic
     * clock (since the applier first saw the request).
     */
    void open(String pageKey, UiSnapshot s, long requestAgeMs);

    /** Page content changed: redraw what is on screen, if anything. */
    void refresh(UiSnapshot s);

    /**
     * Leaves SDK pages: back to the stock screen the bottom page came from (runtime down). Never throws; with no SDK
     * page on screen it changes no screen.
     */
    void close();

    /** No SDK pages. */
    PageRenderer NONE = new PageRenderer() {
        public void open(String pageKey, UiSnapshot s, long requestAgeMs) {
        }

        public void refresh(UiSnapshot s) {
        }

        public void close() {
        }
    };
}
