package mibsdk.api;

/**
 * A registered plugin page. {@link #cancel()} removes it.
 *
 * <p>Obtained from {@link Ui#page(PageSpec, Listener)}. The page is also listed under the plugin's entry
 * in the Plugins menu.
 *
 */
public interface Page extends Subscription {

    /**
     * The page id from its {@link PageSpec}.
     *
     * @since api 2
     */
    String id();

    /**
     * Replaces the page's content. {@code spec.id()} must equal {@link #id()}.
     *
     * @param spec the new content
     * @since api 2
     */
    void update(PageSpec spec);

    /**
     * Shows the page now.
     *
     * @since api 2
     */
    void open();
}
