package mibsdk.adapter.hqc.ui;

/**
 * The SDK drawer rows' visibility models, published in {@code SdkGlobals.uiRowModels} once the Car bank created them
 * (on HMIEvent, when the drawer is first built). Called only on the {@code mibsdk-ui} thread. Rows created before the
 * first call took their visibility from {@code SdkGlobals.uiSlots} at creation; the caller re-applies every slot when
 * it first sees the models, so an assignment swapped in between is not lost.
 */
public interface RowModels {

    /** Shows or hides the SDK row in {@code slot} (0..8); out-of-range slots are ignored. */
    void setVisible(int slot, boolean visible);
}
