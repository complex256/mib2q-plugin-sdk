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
 * LSD's service delegate configurator ({@code de.audi.tghu.serviceDelegateConfigurator} in bundles.properties).
 * LSD calls {@link #getServiceRegistration} for every OSGi service registration, on stock threads, from boot on:
 * a non-null result is the registration, null makes LSD register the original object itself.
 *
 * <p>For the allow-listed DSI services ({@code hook.services} in sdk.properties) it registers a
 * {@link DsiServiceProxy} instead, when this firmware is supported and the boot guard is not in safe mode. A
 * configured {@code chain.configurator} (normally DSITracer's TraceConfigurator, which this class replaces in
 * bundles.properties) then sees every registration, with the wrapped object where there is one, so tracing keeps
 * working. Everything else is passed through untouched, and any failure falls back to what the chain alone (or
 * stock LSD) would do. This class never starts, stops or reloads the SDK runtime.
 *
 * <p>The registration path does no I/O and takes no lock except RingLog's append and HookTable's producer lock.
 * Files are read once, in {@link #initConfigurator}.
 */
public final class ChainingConfigurator implements IServiceDelegateConfigurator {

    static final String PROXY_PROPERTY = "mibsdk.proxy";
    /** TraceConfigurator's marker on its own trace-proxy registrations (it skips them; so do we). */
    private static final String TRACE_DELEGATE_PROPERTY = "service.delegate";
    private static final String TRACE_PROXY_CLASS = "de.audi.tghu.dsitrace.AbstractTraceProxy";
    private static final String SOURCE = "mibsdk.hqc";
    private static final String[] NONE = new String[0];

    private final File baseDir;
    private final File versionInfo;
    private final HookTable hooks = SdkGlobals.HOOKS;
    private final RingLog log = SdkGlobals.LOG;
    private final HookBindings bindings = HookBindings.defaults();

    // Written once by initConfigurator, before LSD's first registration; volatile for the stock threads.
    private volatile boolean enabled;
    private volatile String[] hookServices = NONE;
    private volatile IServiceDelegateConfigurator chain;
    private volatile String chainLabel = "none";

    /** Used by LSD ({@code Class.forName(name).newInstance()}): the unit's paths. */
    public ChainingConfigurator() {
        this(new File(SdkFiles.BASE), new File(SdkFiles.VERSION_INFO));
    }

    /** Tests. */
    ChainingConfigurator(File baseDir, File versionInfo) {
        this.baseDir = baseDir;
        this.versionInfo = versionInfo;
    }

    /**
     * Decides whether to wrap this boot and loads the chain. Never throws (LSD would drop us, and with us the
     * chain). The stock interface declares no exception, so neither does this.
     */
    public void initConfigurator() {
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
        chain = null;
        chainLabel = "none";
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

    public ServiceRegistration getServiceRegistration(IServiceDelegator d, String[] names, Object service,
            Dictionary props) {
        IServiceDelegateConfigurator c = chain;
        try {
            String iface = enabled ? hookedName(names, service, props) : null;
            Object wrapped = service;
            Dictionary marked = props;
            if (iface != null) {
                Object w = DsiServiceProxy.wrap(service, DsiServiceProxy.interfacesOf(service.getClass()), hooks,
                        bindings);
                if (w != service) {
                    wrapped = w;
                    marked = markedCopy(props);
                    info("wrapped " + iface + " chain=" + chainLabel);
                } else {
                    warn("not wrapped " + iface + ": no proxy for " + service.getClass().getName());
                }
            }
            if (c != null) {
                ServiceRegistration r = c.getServiceRegistration(d, names, wrapped, marked);
                if (r != null || wrapped == service) {
                    return r;
                }
            } else if (wrapped == service) {
                return null;
            }
            // Ours to register: null would make LSD register the original.
            return d.createServiceRegistration(names, wrapped, marked);
        } catch (Throwable t) {
            try {
                warn("registration failed, passing through: " + t);
            } catch (Throwable ignored) {
                // still pass through
            }
            return fallback(c, d, names, service, props);
        }
    }

    /** What the chain alone, or else stock LSD, would do with the original object. */
    private static ServiceRegistration fallback(IServiceDelegateConfigurator c, IServiceDelegator d,
            String[] names, Object service, Dictionary props) {
        try {
            return c == null ? null : c.getServiceRegistration(d, names, service, props);
        } catch (Throwable t) {
            return null;
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
        log.append("INFO", SOURCE, msg);
    }

    private void warn(String msg) {
        log.append("WARN", SOURCE, msg);
    }
}
