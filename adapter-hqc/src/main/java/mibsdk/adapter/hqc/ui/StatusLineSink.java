package mibsdk.adapter.hqc.ui;

/** The one-line status display. Called only on the {@code mibsdk-ui} thread. */
interface StatusLineSink {

    void show(String text);

    void hide();
}
