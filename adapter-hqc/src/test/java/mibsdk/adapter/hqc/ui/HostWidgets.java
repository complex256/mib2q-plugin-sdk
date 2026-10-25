package mibsdk.adapter.hqc.ui;

import de.audi.atip.base.IFrameworkAccess;
import de.esolutions.hmi.widgets.audi.base.AbstractWidget;
import java.lang.reflect.Proxy;

/**
 * Stock widgets on the host: IWidgetLogChannel's static initializer asks {@code AbstractWidget.framework} for its log
 * channels, and a failed class initializer stays failed for the JVM. Every test that builds a label, icon or popup
 * controller calls {@link #init} first.
 */
final class HostWidgets {

    private HostWidgets() {
    }

    static synchronized void init() {
        if (AbstractWidget.framework != null) {
            return;
        }
        AbstractWidget.framework = (IFrameworkAccess) Proxy.newProxyInstance(HostWidgets.class.getClassLoader(),
                new Class<?>[] {IFrameworkAccess.class}, (p, m, a) -> {
                    Class<?> r = m.getReturnType();
                    if (r == de.audi.atip.log.LogChannel.class) {
                        return de.audi.atip.log.NullLogChannel.getInstance();
                    } else if (m.getName().equals("hashCode")) {
                        return System.identityHashCode(p);
                    } else if (m.getName().equals("equals")) {
                        return p == a[0];
                    } else if (r == boolean.class) {
                        return false;
                    } else if (r == int.class) {
                        return 0;
                    } else if (r == long.class) {
                        return 0L;
                    }
                    return null;
                });
    }
}
