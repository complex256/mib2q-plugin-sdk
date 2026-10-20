package mibsdk.adapter.hqc.ui;

import mibsdk.platform.UiSnapshot;

/** Draws SDK pages. Both methods run inside an {@link HmiPoster} job, on HMIEvent. */
interface PageRenderer {

    /** The runtime asked to open {@code pageKey} (a new {@code openSeq}). */
    void open(String pageKey, UiSnapshot s);

    /** Page content changed: redraw what is on screen, if anything. */
    void refresh(UiSnapshot s);

    /** Leaves SDK pages: back to the stock screen the bottom page came from (runtime down). */
    void close();

    /** No SDK pages (until Task 11/12). */
    PageRenderer NONE = new PageRenderer() {
        public void open(String pageKey, UiSnapshot s) {
        }

        public void refresh(UiSnapshot s) {
        }

        public void close() {
        }
    };
}
