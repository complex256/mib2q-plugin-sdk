package mibsdk.api;

/** A registered plugin page. {@link #cancel()} removes it. */
public interface Page extends Subscription {

    String id();

    /** Replaces the page's content; {@code spec.id()} must equal {@link #id()}. */
    void update(PageSpec spec);

    /** Shows it now. */
    void open();
}
