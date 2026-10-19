package mibsdk.adapter.hqc;

import de.dreisoft.lsd.IServiceDelegateConfigurator;
import de.dreisoft.lsd.IServiceDelegator;
import java.io.File;
import java.util.Dictionary;
import org.osgi.framework.ServiceRegistration;

/**
 * LSD's service delegate configurator ({@code de.audi.tghu.serviceDelegateConfigurator} in bundles.properties).
 * LSD calls {@link #getServiceRegistration} for every OSGi service registration, on stock threads, from boot on:
 * a non-null result is the registration, null makes LSD register the original object itself. The work is done by
 * {@link ConfiguratorCore}.
 *
 * <p>This class is a shell whose fields, signatures and constructors use only JCL and stock types, so loading and
 * constructing it cannot fail. Stock LSD catches only {@code Exception} around
 * {@code Class.forName(name).newInstance()} and {@code initConfigurator()}, and its callers catch nothing: an
 * {@code Error} would escape into the bundle-registry boot path. With a partial install (runtime, api or
 * Retrotranslator runtime jar missing, or a class J9 rejects) the {@code NoClassDefFoundError} or
 * {@code ExceptionInInitializerError} is raised inside {@link #initConfigurator}'s catch-all instead, and this
 * configurator stays a pure pass-through (no wrapping, no chain: LSD registers everything itself).
 */
public final class ChainingConfigurator implements IServiceDelegateConfigurator {

    private final File baseDir;
    private final File versionInfo;
    /** The {@link ConfiguratorCore}, typed Object so that nothing about this class needs SDK classes. */
    private volatile Object core;

    /** Used by LSD ({@code Class.forName(name).newInstance()}): the unit's paths (inlined constants). */
    public ChainingConfigurator() {
        this(new File(SdkFiles.BASE), new File(SdkFiles.VERSION_INFO));
    }

    /** Tests. Stores the paths only. */
    ChainingConfigurator(File baseDir, File versionInfo) {
        this.baseDir = baseDir;
        this.versionInfo = versionInfo;
    }

    /** Never throws; the stock interface declares no exception, so neither does this. */
    public void initConfigurator() {
        core = null;
        try {
            core = ConfiguratorCore.init(baseDir, versionInfo);
        } catch (Throwable t) {
            try {
                // The SDK log may be the thing that failed to load; stderr is the only channel left (init only).
                System.err.println("mibsdk: configurator disabled, passing everything through: " + t);
            } catch (Throwable ignored) {
                // nothing left to do
            }
        }
    }

    public ServiceRegistration getServiceRegistration(IServiceDelegator d, String[] names, Object service,
            Dictionary props) {
        Object c = core;
        if (c == null) {
            return null;
        }
        try {
            return ((ConfiguratorCore) c).getServiceRegistration(d, names, service, props);
        } catch (Throwable t) {
            return null; // the core catches everything itself; this is the last line
        }
    }
}
