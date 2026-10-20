package mibsdk.adapter.hqc;

import de.dreisoft.lsd.IServiceDelegateConfigurator;
import de.dreisoft.lsd.IServiceDelegator;
import java.io.File;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.List;
import mibsdk.platform.HookTable;
import mibsdk.platform.SdkRoots;
import mibsdk.runtime.AdapterSelector;
import mibsdk.runtime.BootGuard;
import mibsdk.runtime.RingLog;
import mibsdk.runtime.RuntimeConfig;
import org.osgi.framework.ServiceRegistration;

/**
 * The work behind {@link ChainingConfigurator}, created by its {@code initConfigurator} (so any failure to load
 * the SDK classes lands in that catch-all).
 *
 * <p>For the allow-listed DSI services ({@code hook.services} in sdk.properties) it registers a
 * {@link DsiServiceProxy} instead of the service, when this firmware is supported, the boot guard is not in
 * safe mode and no wrap loop was detected ({@link BootGuard#wrapLoopDetected}; a wrapping boot is counted with
 * {@link BootGuard#countWrapBoot} at init). A configured {@code chain.configurator} (normally DSITracer's
 * TraceConfigurator, which ChainingConfigurator replaces in bundles.properties) sees every registration, with the
 * wrapped object where there is one, so tracing keeps working. Everything else is passed through untouched, and any failure falls back
 * to what the chain alone (or stock LSD) would do. Never starts, stops or reloads the SDK runtime.
 *
 * <p>The registration path does no I/O and takes no lock except RingLog's append, HookTable's producer lock and
 * {@link RawServices}' put (the raw object of each wrapped service, for the SDK's own bridges).
 * Files are read once, in {@link #init}, which also writes the wrap-boot count to boot.state (in the persistent
 * root, which is writable from boot: no remount, no exec on this stock thread).
 */
final class ConfiguratorCore {

    static final String PROXY_PROPERTY = "mibsdk.proxy";
    /** TraceConfigurator's marker on its own trace-proxy registrations (it skips them; so do we). */
    private static final String TRACE_DELEGATE_PROPERTY = "service.delegate";
    private static final String TRACE_PROXY_CLASS = "de.audi.tghu.dsitrace.AbstractTraceProxy";
    private static final String SOURCE = "mibsdk.hqc";

    private final HookTable hooks = SdkGlobals.HOOKS;
    private final RingLog log = SdkGlobals.LOG;
    private final HookBindings bindings = HookBindings.defaults();

    // Final once init returns; the core is published to stock threads through a volatile field.
    private boolean enabled;
    private String[] hookServices = new String[0];
    private IServiceDelegateConfigurator chain;
    private String chainLabel = "none";

    private ConfiguratorCore() {
    }

    /**
     * Decides whether to wrap this boot and loads the chain. Throws only if the SDK classes themselves cannot be
     * loaded (the caller catches that); everything after is caught here.
     */
    static ConfiguratorCore init(File installDir, File persistDir, File ramDir, File versionInfo) {
        ConfiguratorCore c = new ConfiguratorCore();
        c.configure(new SdkRoots(installDir, persistDir, ramDir), versionInfo);
        return c;
    }

    private void configure(SdkRoots roots, File versionInfo) {
        SdkGlobals.configuratorSeen = true;
        SdkGlobals.uiEnabled = false; // decided below; off until then
        boolean on = false;
        String reason = null;
        boolean ui = false;
        String uiReason = "init-failed";
        try {
            String version = AdapterSelector.readHmiVersion(versionInfo);
            RuntimeConfig cfg = RuntimeConfig.load(roots);
            int threshold = cfg.safeModeAfterUnhealthyBoots();
            boolean safeMode = BootGuard.wouldEnterSafeMode(cfg.bootStateFile(), cfg.safeModeMarker(), threshold);
            boolean wrapLoop = BootGuard.wrapLoopDetected(cfg.bootStateFile(), threshold);
            boolean supported = HqcVersion.SUPPORTED.equals(version);
            on = supported && !safeMode && !wrapLoop;
            reason = !supported ? "unsupported-hmi" : safeMode ? "safe-mode" : wrapLoop ? "wrap-boots" : null;
            // The UI wraps the Car HMI: under the latch it is off as a whole (it may be what crashes the HMI). Safe
            // mode keeps it on, so the Plugins menu can say so.
            ui = supported && !wrapLoop && cfg.uiEnabled();
            uiReason = !supported ? "unsupported-hmi" : wrapLoop ? "wrap-boots" : !cfg.uiEnabled() ? "ui-disabled"
                    : null;
            if (wrapLoop) {
                warn("configurator: wrapping=off reason=wrap-boots (" + threshold + " boots in a row wrapped the DSI"
                        + " services and died before the HMI ran healthy; passing everything through until the boot"
                        + " state is reset: mibsdk-dev safe-mode off)");
            }
            // One count per boot, wrapping or UI or both; cleared by the runtime once the HMI is healthy. A boot that
            // cannot be counted is a loop that could never be caught: fail safe, as when boot.state cannot be judged.
            if ((on || ui) && !BootGuard.countWrapBoot(cfg.bootStateFile(), log)) {
                if (on) {
                    reason = "boot-state-unwritable";
                    warn("configurator: wrap skipped: cannot record wrap boot in "
                            + cfg.bootStateFile().getAbsoluteFile().getParent() + " (passing everything through)");
                }
                if (ui) {
                    uiReason = "count-failed";
                    warn("configurator: ui=off reason=count-failed (stock Car drawer this boot)");
                }
                on = false;
                ui = false;
            }
            List names = cfg.hookServices();
            hookServices = (String[]) names.toArray(new String[names.size()]);
            info("configurator: hmi=" + version + " safeMode=" + safeMode + " wrapLoop=" + wrapLoop
                    + " wrappingEnabled=" + on + " uiEnabled=" + ui + " hookServices=" + join(hookServices));
            // The chain is loaded even when wrapping is off: it is what stock would run in our place.
            loadChain(cfg.chainConfigurator());
        } catch (Throwable t) {
            on = false;
            reason = "init-failed";
            ui = false;
            uiReason = "init-failed";
            warn("configurator init failed, passing everything through: " + t);
        }
        enabled = on;
        SdkGlobals.wrapOffReason = on ? null : reason;
        SdkGlobals.wrappingEnabled = on;
        SdkGlobals.uiOffReason = ui ? null : uiReason;
        SdkGlobals.uiEnabled = ui;
    }

    private void loadChain(String className) {
        if (className == null) {
            info("configurator: chain=none");
            return;
        }
        try {
            if (className.equals(ChainingConfigurator.class.getName())) {
                throw new IllegalArgumentException("refusing to chain to itself");
            }
            Object o = Class.forName(className).newInstance();
            if (!(o instanceof IServiceDelegateConfigurator)) {
                throw new ClassCastException(className + " is not an IServiceDelegateConfigurator");
            }
            IServiceDelegateConfigurator c = (IServiceDelegateConfigurator) o;
            c.initConfigurator();
            chain = c;
            chainLabel = className.substring(className.lastIndexOf('.') + 1);
            info("configurator: chain=" + className);
        } catch (Throwable t) {
            // TraceConfigurator throws IllegalStateException("Trace deactivated.") when tracing is off.
            info("configurator: chain " + className + " dropped: " + t);
        }
    }

    ServiceRegistration getServiceRegistration(IServiceDelegator d, String[] names, Object service,
            Dictionary props) {
        String iface = null;
        Object wrapped = service;
        Dictionary marked = props;
        try {
            iface = enabled ? hookedName(names, service, props) : null;
            if (iface != null) {
                Object w = DsiServiceProxy.wrap(service, DsiServiceProxy.interfacesOf(service.getClass()), hooks,
                        bindings);
                if (w != service) {
                    marked = markedCopy(props);
                    wrapped = w;
                    RawServices.record(iface, service); // the SDK's bridges bypass every proxy layer
                } else {
                    warn("not wrapped " + iface + ": no proxy for " + service.getClass().getName());
                }
            }
        } catch (Throwable t) {
            wrapped = service;
            marked = props;
            warn("not wrapped " + iface + ": " + t);
        }
        if (wrapped == service) {
            return passThrough(d, names, service, props);
        }
        return registerWrapped(iface, d, names, service, props, wrapped, marked);
    }

    /** Exactly what the chain alone, or else stock LSD (null), does with the original object. */
    private ServiceRegistration passThrough(IServiceDelegator d, String[] names, Object service, Dictionary props) {
        IServiceDelegateConfigurator c = chain;
        if (c == null) {
            return null;
        }
        try {
            return c.getServiceRegistration(d, names, service, props);
        } catch (Throwable t) {
            warn("chain failed, LSD registers the original: " + t);
            return null;
        }
    }

    /**
     * The chain registers the wrapped object, or declines (null) and we register it ourselves: returning null
     * would make LSD register the original. If the chain throws it is not asked again (it may already have
     * registered {@code delegated.<name>}); the original is registered directly instead.
     */
    private ServiceRegistration registerWrapped(String iface, IServiceDelegator d, String[] names, Object service,
            Dictionary props, Object wrapped, Dictionary marked) {
        IServiceDelegateConfigurator c = chain;
        if (c != null) {
            ServiceRegistration r;
            try {
                r = c.getServiceRegistration(d, names, wrapped, marked);
            } catch (Throwable t) {
                warn("wrap of " + iface + " abandoned, chain failed: " + t);
                return registerOriginal(d, names, service, props);
            }
            if (r != null) {
                info("wrapped " + iface + " chain=" + chainLabel);
                return r;
            }
        }
        try {
            ServiceRegistration r = d.createServiceRegistration(names, wrapped, marked);
            info("wrapped " + iface + " chain=" + chainLabel);
            return r;
        } catch (Throwable t) {
            warn("wrap of " + iface + " abandoned, registration failed: " + t);
            return null;
        }
    }

    private ServiceRegistration registerOriginal(IServiceDelegator d, String[] names, Object service,
            Dictionary props) {
        try {
            return d.createServiceRegistration(names, service, props);
        } catch (Throwable t) {
            return null; // LSD registers the original itself
        }
    }

    /** The first of {@code names} that is a hook service, if this registration is one to wrap; else null. */
    private String hookedName(String[] names, Object service, Dictionary props) {
        if (names == null || service == null || isTraceProxy(service)) {
            return null;
        }
        if (props != null && (props.get(PROXY_PROPERTY) != null || props.get(TRACE_DELEGATE_PROPERTY) != null)) {
            return null;
        }
        String[] hooked = hookServices;
        for (int i = 0; i < names.length; i++) {
            for (int j = 0; j < hooked.length; j++) {
                if (hooked[j].equals(names[i])) {
                    return names[i];
                }
            }
        }
        return null;
    }

    /** By name: DSITracer may be absent from the class path, and then this must not fail to link. */
    private static boolean isTraceProxy(Object service) {
        for (Class k = service.getClass(); k != null; k = k.getSuperclass()) {
            if (TRACE_PROXY_CLASS.equals(k.getName())) {
                return true;
            }
        }
        return false;
    }

    /** A copy of {@code props} (which stays untouched) plus {@code mibsdk.proxy=true}. */
    private static Dictionary markedCopy(Dictionary props) {
        Hashtable copy = new Hashtable();
        if (props != null) {
            for (Enumeration keys = props.keys(); keys.hasMoreElements();) {
                Object k = keys.nextElement();
                Object v = props.get(k);
                if (k != null && v != null) {
                    copy.put(k, v);
                }
            }
        }
        copy.put(PROXY_PROPERTY, "true");
        return copy;
    }

    /** {@code [a, b]}: J9's unmodifiable list has no useful toString (it prints the class and hash). */
    static String join(String[] items) {
        StringBuffer b = new StringBuffer("[");
        for (int i = 0; i < items.length; i++) {
            if (i > 0) {
                b.append(", ");
            }
            b.append(items[i]);
        }
        return b.append(']').toString();
    }

    private void info(String msg) {
        try {
            log.append("INFO", SOURCE, msg);
        } catch (Throwable ignored) {
            // logging never breaks a registration
        }
    }

    private void warn(String msg) {
        try {
            log.append("WARN", SOURCE, msg);
        } catch (Throwable ignored) {
            // logging never breaks a registration
        }
    }
}
