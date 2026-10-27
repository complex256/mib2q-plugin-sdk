/**
 * The plugin API: the only SDK code a plugin compiles against, and the unit-side behaviour it relies on.
 *
 * <h2>Threading: one worker per plugin</h2>
 *
 * <p>Everything a plugin runs executes on that plugin's single SDK worker thread, one task at a time:
 * {@link mibsdk.api.MibPlugin#onEnable(PluginContext)}, page actions and input events, {@link Scheduler} tasks, event
 * listeners, hook observers and vetoes, and {@link Ui#confirm}/{@link Ui#speller} answers. Two of those callbacks
 * therefore never run at the same time, so fields they share need no {@code synchronized}, no {@code volatile} and no
 * locks. The worker is a daemon thread at {@link Thread#NORM_PRIORITY} minus one; plugin code never runs on a stock
 * HMI thread, nor on the runtime's timer or watchdog threads.
 *
 * <p>One worker per plugin also means a slow callback holds up the rest of that plugin, and only that plugin. The SDK
 * logs a callback that takes longer than 5 s as slow, and one that takes longer than 30 s gets the plugin quarantined
 * (see below). Work a plugin starts on a thread of its own is outside this model: the SDK cannot see it, cancel it, or
 * keep it off the heap.
 *
 * <h2>Lifecycle</h2>
 *
 * <ol>
 *   <li>The runtime checks the manifest and the permissions first. A missing permission leaves the plugin
 *       {@code BLOCKED_PERMISSIONS} and {@link MibPlugin#onEnable} is never called.</li>
 *   <li>{@link MibPlugin#onEnable} runs on the worker. Everything registered there (listeners, timers, pages, rows,
 *       status items, hooks, overrides) belongs to this run of the plugin.</li>
 *   <li>On disable, unload, reload or quarantine the SDK cancels every registration, releases every override, and
 *       flushes storage. Only then does {@link MibPlugin#onDisable} run, on the same worker, with its state still
 *       readable from storage. Anything registered during {@code onDisable} is cancelled immediately, and the worker
 *       is shut down when {@code onDisable} returns.</li>
 *   <li>The plugin's handles ({@link Page}, {@link StatusItem}, {@link Subscription}) stay safe to call afterwards, but
 *       they do nothing (see below).</li>
 * </ol>
 *
 * <p><strong>{@code onDisable} also runs after {@code onEnable} threw.</strong> A plugin that fails in the middle of
 * {@code onEnable} is disabled like any other, so guard every field {@code onDisable} touches: the ones assigned after
 * the failure point are still null. The one exception is an {@code onEnable} (or {@code onDisable}) that never
 * returns: once it has run for 30 s the plugin is quarantined as hung and {@code onDisable} is not called, because the
 * worker is still stuck inside the plugin.
 *
 * <h2>Quarantine: what happens when plugin code misbehaves</h2>
 *
 * <p>Plugin callbacks are the only code the SDK cannot vet. The runtime watches each plugin's worker:
 *
 * <ul>
 *   <li>a callback that takes longer than <strong>5 s</strong> ({@code callback.slow.ms}) is logged as slow; the plugin
 *       keeps running;</li>
 *   <li>a callback still running after <strong>30 s</strong> ({@code callback.hung.ms}) is hung: the plugin is
 *       quarantined and its state becomes {@code QUARANTINED};</li>
 *   <li>a callback that throws counts as a failure; the <strong>third</strong> failure within <strong>60 s</strong>
 *       ({@code quarantine.failures}, {@code quarantine.window.ms}) quarantines the plugin.</li>
 * </ul>
 *
 * <p>The thresholds are the defaults in {@code sdk.properties} ({@code RuntimeConfig}); the owner may change them per
 * unit. Quarantine is a disable: the plugin's registrations are cancelled, its overrides released, its UI removed, and
 * {@code onDisable} runs if it can. It stays quarantined until the owner re-enables it or the unit restarts. A
 * quarantined plugin's code is not rerun to test whether it recovered.
 *
 * <h2>Inert handles instead of exceptions</h2>
 *
 * <p>When a feature is missing (the unit does not draw the SDK UI this boot: the wrap-loop latch, {@code ui.enabled}
 * false, an init failure, or no such slot) the {@link Ui} call still succeeds, after checking permissions and
 * arguments like a live one, and returns an <em>inert</em> handle. An inert page, Car row or status item holds its
 * count slot and accepts updates, but never reaches the screen. Timers and event listeners are never inert: they keep
 * running plugin code, which is why a plugin's non-UI work (a parking mute, a poll) keeps working with the UI off. A
 * handle that came back inert stays inert even if the feature appears later, and handles asked for after the plugin is
 * disabled come back cancelled. Exceptions are kept for
 * programming errors: a bad id, an oversized icon, a missing permission, a count above the limit.
 *
 * <h2>UI behaviour that is easy to get wrong</h2>
 *
 * <ul>
 *   <li>A slider shows its position, not its number. Show the value in a nearby item's value column if the number
 *       matters.</li>
 *   <li>Dropdown and slider rows are always drawn after the list rows, whatever order they were added in.</li>
 *   <li>A toggle does not flip by itself. The unit sends you a toggle event and keeps showing what your next
 *       {@link Page#update} says, so update the page in the handler.</li>
 *   <li>A toast needs {@code ui.slot.statusbar}, the same permission as the status item; the name does not suggest
 *       it.</li>
 *   <li>A Car row's new icon shows only the next time the Car screen opens: the screen reads its rows when it
 *       opens.</li>
 * </ul>
 *
 * <h2>Language level</h2>
 *
 * <p>Source may use modern Java (lambdas, generics, enhanced {@code for}, autoboxing, try-with-resources) and the
 * build lowers it for the unit. Two limits follow from that: the {@code java.util.concurrent} types are not on the
 * unit (use {@link Scheduler} and the worker model above), and <strong>records are not supported</strong>. A
 * {@code record} fails in {@code compileJava} with Jabel's message {@code "Must be annotated with @Desugar"}, which
 * says nothing about the car. Use a final class with final fields instead.
 *
 * @since api 1
 */
package mibsdk.api;
