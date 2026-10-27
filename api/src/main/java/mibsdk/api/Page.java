package mibsdk.api;

/** A registered plugin page. {@link #cancel()} removes it. */
public interface Page extends Subscription {

    /**
     * @since api 2
     */
    String id();

    /**
     * Replaces the page's content; {@code spec.id()} must equal {@link #id()}.
     *
     * @since api 2
     */
    void update(PageSpec spec);

    /**
     * Shows it now.
     *
     * @since api 2
     */
    void open();
}
