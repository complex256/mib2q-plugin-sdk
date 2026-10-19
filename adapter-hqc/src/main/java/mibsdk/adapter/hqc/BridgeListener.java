package mibsdk.adapter.hqc;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Invocation handler of a bridge's DSI listener ({@link java.lang.reflect.Proxy} over one public stock listener
 * interface, so the bridge compiles against nothing but the methods it uses). Runs on stock DSI threads: it never
 * throws, and {@link #onUpdate} must only copy values and hand off to an {@link EventPump}. Object methods are
 * identity-based. Every DSI listener method returns {@code void}, so null is always a valid answer.
 */
abstract class BridgeListener implements InvocationHandler, DsiServiceProxy.SdkOwnedListener {

    /** A proxy for {@code iface} (a public stock listener interface) with this handler. */
    final Object newProxy(Class iface) {
        return Proxy.newProxyInstance(iface.getClassLoader(), new Class[] {iface}, this);
    }

    public final Object invoke(Object proxy, Method m, Object[] args) {
        try {
            String name = m.getName();
            if (m.getDeclaringClass() == Object.class) {
                if ("equals".equals(name)) {
                    return args != null && args[0] == proxy ? Boolean.TRUE : Boolean.FALSE;
                }
                if ("hashCode".equals(name)) {
                    return new Integer(System.identityHashCode(proxy));
                }
                return getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(proxy));
            }
            if (args != null && args.length == 2 && args[1] instanceof Integer) {
                onUpdate(name, args[0], ((Integer) args[1]).intValue());
            }
        } catch (Throwable ignored) {
            // a stock callback thread must never see an SDK failure
        }
        return null;
    }

    /** {@code updateXxx(value, validFlag)}; every other call is ignored. Never blocks. */
    abstract void onUpdate(String method, Object value, int validFlag);
}
