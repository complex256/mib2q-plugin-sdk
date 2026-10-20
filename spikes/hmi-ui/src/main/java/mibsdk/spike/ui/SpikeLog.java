package mibsdk.spike.ui;

import mibsdk.adapter.hqc.SdkGlobals;

/** Every spike line goes to the SDK ring log (written to /tmp/mibsdk.log by the runtime), tagged "spike.ui". */
final class SpikeLog {
    private SpikeLog() {
    }

    static void log(String msg) {
        try {
            SdkGlobals.LOG.append("INFO", "spike.ui", msg);
        } catch (Throwable t) {
            // never into a stock thread
        }
    }
}
