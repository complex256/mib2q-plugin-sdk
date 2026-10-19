package mibsdk.adapter.hqc;

import de.dreisoft.lsd.IServiceDelegateConfigurator;
import de.dreisoft.lsd.IServiceDelegator;
import java.io.File;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.List;
import mibsdk.platform.HookTable;
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
 * {@link DsiServiceProxy} instead of the service, when this firmware is supported and the boot guard is not in
 * safe mode. A configured {@code chain.configurator} (normally DSITracer's TraceConfigurator, which
 * ChainingConfigurator replaces in bundles.properties) sees every registration, with the wrapped object where
 * there is one, so tracing keeps working. Everything else is passed through untouched, and any failure falls back
 * to what the chain alone (or stock LSD) would do. Never starts, stops or reloads the SDK runtime.
 *
 * <p>The registration path does no I/O and takes no lock except RingLog's append, HookTable's producer lock and
 * {@link RawServices}' put (the raw object of each wrapped service, for the SDK's own bridges).
 * Files are read once, in {@link #init}.
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
    static ConfiguratorCore init(File baseDir, File versionInfo) {
        ConfiguratorCore c = new ConfiguratorCore();
        c.configure(baseDir, versionInfo);
        return c;
    }

    private void configure(File baseDir, File versionInfo) {
        boolean on = false;
        try {
            String version = AdapterSelector.readHmiVersion(versionInfo);
            RuntimeConfig cfg = RuntimeConfig.load(baseDir);
            boolean safeMode = BootGuard.wouldEnterSafeMode(cfg.bootStateFile(), cfg.safeModeMarker(),
                    cfg.safeModeAfterUnhealthyBoots());
            on = HqcVersion.SUPPORTED.equals(version) && !safeMode;
            List names = cfg.hookServices();
            hookServices = (String[]) names.toArray(new String[names.size()]);
            info("configurator: hmi=" + version + " safeMode=" + safeMode + " wrappingEnabled=" + on
                    + " hookServices=" + names);
            // The chain is loaded even when wrapping is off: it is what stock would run in our place.
            loadChain(cfg.chainConfigurator());
        } catch (Throwable t) {
            on = false;
            warn("configurator init failed, passing everything through: " + t);
        }
        enabled = on;
        SdkGlobals.wrappingEnabled = on;
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
