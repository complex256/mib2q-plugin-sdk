package mibsdk.api;

/**
 * A plugin's log. Obtain it from {@link PluginContext#log()}.
 *
 * <p>Lines go to an in-memory ring buffer, which the runtime mirrors to {@code /tmp/mibsdk.log} on the unit.
 * Keep messages short: the buffer and the unit's disk are small. Never log the VIN, IMSI, location or other
 * personal data.
 *
 * @since api 1
 */
public interface Log {

    /**
     * Records an informational line.
     *
     * @param m the message
     */
    void info(String m);

    /**
     * Records a warning. Use it for a condition the plugin recovered from.
     *
     * @param m the message
     */
    void warn(String m);

    /**
     * Records an error with its exception.
     *
     * @param m the message
     * @param t the cause, or null
     */
    void error(String m, Throwable t);
}
