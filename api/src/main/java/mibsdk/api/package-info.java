/**
 * The stable, firmware-neutral API a plugin compiles against.
 *
 * <h2>Getting started</h2>
 *
 * <p>A plugin is one class that extends {@link mibsdk.api.MibPlugin}. The runtime constructs it and calls
 * {@link mibsdk.api.MibPlugin#onEnable(mibsdk.api.PluginContext)} on the plugin's own worker thread. The
 * context is the only door to the SDK:
 *
 * <pre>{@code
 * public class MyPlugin extends MibPlugin {
 *     @Override
 *     protected void onEnable(PluginContext ctx) {
 *         ctx.log().info("hello");
 *     }
 * }
 * }</pre>
 *
 * <p>See the project documentation for the tutorial, guides and reference. This package summary covers
 * the rules that apply to every plugin.
 *
 * <h2>Lifecycle</h2>
 *
 * <p>The runtime reads the manifest and checks permissions before it loads the class. A plugin whose
 * manifest is invalid is {@code BLOCKED_INVALID}; a plugin with an ungranted permission is
 * {@code BLOCKED_PERMISSIONS}. Neither reaches {@code onEnable}.
 *
 * <p>Everything registered in {@code onEnable} belongs to that run: pages, Car rows, status items,
 * timers, listeners, hooks and overrides. When the plugin is disabled, reloaded, quarantined or the HMI
 * shuts down, the SDK cancels all of it, releases overrides, flushes storage, and then calls
 * {@link mibsdk.api.MibPlugin#onDisable()}.
 *
 * <p>{@code onDisable} also runs after an {@code onEnable} that threw. Guard every field it touches: a
 * field assigned after the failure point is still null. A plugin is constructed fresh for each run, so
 * fields do not survive a reload; persistent state belongs in {@link mibsdk.api.Storage}.
 *
 * <h2>One worker per plugin</h2>
 *
 * <p>All plugin code runs on one SDK-owned daemon thread, one task at a time: lifecycle hooks, page
 * actions, scheduler tasks, event listeners and dialog answers. Callbacks never overlap, so shared fields
 * need no {@code synchronized} or {@code volatile}.
 *
 * <p>Plugin code never runs on an HMI thread. A slow callback delays only its own plugin: the SDK logs a
 * callback over 5 s and quarantines one that runs for 30 s.
 *
 * <h2>Failures and quarantine</h2>
 *
 * <p>An exception in a callback is logged and counted. The third failure within 60 s quarantines the
 * plugin. A callback still running after 30 s quarantines it as hung. Quarantine disables the plugin,
 * cancels its registrations and releases its overrides; it stays off until the user re-enables it or the
 * unit restarts.
 *
 * <p>Lifecycle hooks are different: a throwing {@code onEnable} disables the plugin as {@code FAILED}
 * immediately, and a throwing {@code onDisable} is only logged.
 *
 * <h2>Inert handles</h2>
 *
 * <p>When a feature is missing at this boot, UI calls still return handles, but they are inert: updates
 * are accepted and show nothing. Argument and count checks still apply. Use
 * {@link mibsdk.api.PluginContext#has(String)} to tell the difference.
 *
 * <h2>UI behaviour that surprises people</h2>
 *
 * <ul>
 *   <li>A toggle does not flip by itself. You receive the requested state and must update the page.</li>
 *   <li>A slider shows its position, not its number. Show the number in a nearby row's value.</li>
 *   <li>Dropdown and slider rows are drawn after the list rows, whatever order you add them in.</li>
 *   <li>A toast needs {@code ui.slot.statusbar}, the same permission as the status item.</li>
 *   <li>A Car row's new icon shows the next time the Car screen opens.</li>
 * </ul>
 *
 * <h2>Language level</h2>
 *
 * <p>Source may use modern Java syntax (lambdas, generics, switch expressions, text blocks,
 * try-with-resources); the build lowers it to class-file v48. Two limits follow from the car's Java 1.4
 * library: {@code java.util.concurrent} is unavailable (use {@link mibsdk.api.Scheduler}), and
 * <strong>records are not supported</strong>. A {@code record} fails in {@code compileJava} with Jabel's
 * {@code "Must be annotated with @Desugar"}; use a final class.
 *
 * @since api 1
 */
package mibsdk.api;
