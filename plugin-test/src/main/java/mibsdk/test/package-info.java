/**
 * The host-side test harness for plugins.
 *
 * <p>{@link mibsdk.test.PluginTestHarness} installs a built plugin jar into a temporary "unit", starts the
 * real SDK runtime on the fake car and waits until the plugin is enabled. Drive the plugin through the
 * fake screen and the fake car; move the scheduler with {@code advance}; assert on what the UI shows and
 * what the plugin stored.
 *
 * <p>This package is host-only. It is not part of the runtime and never ships to the unit.
 */
package mibsdk.test;
