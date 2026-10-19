package mibsdk.adapter.hqc;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import mibsdk.api.HookPoint;
import mibsdk.platform.HookTable;
import org.dsi.ifc.base.DSIListener;

/**
 * Forwarding proxy for a DSI service, or for an HMI listener registered with one. Runs on stock threads: the
 * SDK part of a call never throws and never blocks (HookTable's producer lock is an array store; the listener
 * map lock is held only for a map lookup or store, and only in set/clearNotification). Whatever the SDK side
 * does, the call itself is forwarded to the target unchanged, unless a hook point is vetoed and the method
 * returns {@code void}. Nothing here needs the runtime: with nobody polling, {@link HookTable} drops the oldest
 * observations.
 *
 * <p>Listener wrappers are identity-stable: the same listener object always maps to the same wrapper while that
 * wrapper is alive, so a {@code clearNotification} removes exactly what {@code setNotification} added. Retention:
 * the map holds both the listener (key) and its wrapper (value) weakly, compared by identity. A registered wrapper
 * is held strongly by the stock provider (its {@code ListenerMap}), and the wrapper holds its listener, so the
 * entry lives exactly as long as some service still has the wrapper; afterwards both can be collected. (A
 * {@code WeakHashMap} would compare listeners by {@code equals} and, with the wrapper as a strong value, would
 * keep every listener forever.)
 */
final class DsiServiceProxy implements InvocationHandler {

    private static final String SET_NOTIFICATION = "setNotification";
    private static final String CLEAR_NOTIFICATION = "clearNotification";

    /** Identity key held weakly. */
    private static final class IdentityKey extends WeakReference {
        private final int hash;

        IdentityKey(Object referent, ReferenceQueue queue) {
            super(referent, queue);
            this.hash = System.identityHashCode(referent);
        }

        public int hashCode() {
            return hash;
        }

        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof IdentityKey)) {
                return false;
            }
            Object mine = get();
            return mine != null && mine == ((IdentityKey) other).get();
        }
    }

    private static final Object LISTENERS_LOCK = new Object();
    /** IdentityKey(listener) to WeakReference(wrapper); guarded by LISTENERS_LOCK. */
    private static final HashMap LISTENERS = new HashMap();
    private static final ReferenceQueue STALE = new ReferenceQueue();

    private final Object target;
    private final HookTable hooks;
    private final HookBindings bindings;

    private DsiServiceProxy(Object target, HookTable hooks, HookBindings bindings) {
        this.target = target;
        this.hooks = hooks;
        this.bindings = bindings;
    }

    /**
     * A proxy implementing {@code interfaces} that forwards to {@code service}, or {@code service} itself on any
     * failure (no interfaces, a non-interface, interfaces not visible from the service's loader, ...).
     */
    static Object wrap(Object service, Class[] interfaces, HookTable hooks, HookBindings bindings) {
        try {
            if (service == null || interfaces == null || interfaces.length == 0 || hooks == null
                    || bindings == null) {
                return service;
            }
            return Proxy.newProxyInstance(service.getClass().getClassLoader(), interfaces,
                    new DsiServiceProxy(service, hooks, bindings));
        } catch (Throwable t) {
            return service;
        }
    }

    /**
     * The wrapper for an HMI listener: the existing one for this listener object, else a new one. The listener
     * itself if none of its interfaces has a bound method, if it is already one of ours, or on any failure.
     */
    static Object wrapListener(Object listener, HookTable hooks, HookBindings bindings) {
        try {
            if (listener == null || hooks == null || bindings == null || isOurs(listener)) {
                return listener;
            }
            Object existing = existingWrapper(listener, hooks, bindings);
            if (existing != null) {
                return existing;
            }
            Class[] interfaces = interfacesOf(listener.getClass());
            if (!bindings.bindsAny(interfaces)) {
                return listener;
            }
            Object w = wrap(listener, interfaces, hooks, bindings);
            return w == listener ? listener : remember(listener, w, hooks, bindings);
        } catch (Throwable t) {
            return listener;
        }
    }

    /** The live wrapper of {@code listener} for these hooks/bindings; null if there is none. */
    static Object existingWrapper(Object listener, HookTable hooks, HookBindings bindings) {
        IdentityKey probe = new IdentityKey(listener, null);
        synchronized (LISTENERS_LOCK) {
            expungeStale();
            WeakReference ref = (WeakReference) LISTENERS.get(probe);
            Object w = ref == null ? null : ref.get();
            return w != null && sameTable(w, hooks, bindings) ? w : null;
        }
    }

    /** Stores {@code wrapper} unless another thread got there first; returns the winner. */
    private static Object remember(Object listener, Object wrapper, HookTable hooks, HookBindings bindings) {
        synchronized (LISTENERS_LOCK) {
            expungeStale();
            IdentityKey key = new IdentityKey(listener, STALE);
            WeakReference ref = (WeakReference) LISTENERS.get(key);
            Object w = ref == null ? null : ref.get();
            if (w != null && sameTable(w, hooks, bindings)) {
                return w;
            }
            LISTENERS.remove(key); // the stale entry's key object would otherwise be kept by put
            LISTENERS.put(key, new WeakReference(wrapper));
            return wrapper;
        }
    }

    /** Caller holds LISTENERS_LOCK. */
    private static void expungeStale() {
        for (Object k = STALE.poll(); k != null; k = STALE.poll()) {
            LISTENERS.remove(k);
        }
    }

    private static boolean sameTable(Object wrapper, HookTable hooks, HookBindings bindings) {
        DsiServiceProxy h = (DsiServiceProxy) Proxy.getInvocationHandler(wrapper);
        return h.hooks == hooks && h.bindings == bindings;
    }

    private static boolean isOurs(Object o) {
        return Proxy.isProxyClass(o.getClass()) && Proxy.getInvocationHandler(o) instanceof DsiServiceProxy;
    }

    /** Every interface declared by {@code c} or a superclass, each once, subclass first. */
    static Class[] interfacesOf(Class c) {
        List out = new ArrayList();
        for (Class k = c; k != null; k = k.getSuperclass()) {
            Class[] direct = k.getInterfaces();
            for (int i = 0; i < direct.length; i++) {
                if (!out.contains(direct[i])) {
                    out.add(direct[i]);
                }
            }
        }
        return (Class[]) out.toArray(new Class[out.size()]);
    }

    public Object invoke(Object proxy, Method m, Object[] args) throws Throwable {
        Class declaring = m.getDeclaringClass();
        if (declaring == Object.class) {
            return objectMethod(proxy, m.getName(), args);
        }
        Object[] forward = args;
        boolean vetoed = false;
        try {
            String name = m.getName();
            if (args != null && (SET_NOTIFICATION.equals(name) || CLEAR_NOTIFICATION.equals(name))) {
                forward = listenerArgs(args, SET_NOTIFICATION.equals(name));
            }
            HookPoint p = bindings.pointFor(declaring.getName(), name);
            if (p != null) {
                vetoed = hooks.isVetoed(p);
                Object value = bindings.toApiValue(p, args);
                if (value != null) {
                    hooks.observed(p, value, vetoed);
                }
            }
        } catch (Throwable t) {
            // the SDK side failed: forward as if we were not here
            forward = args;
            vetoed = false;
        }
        if (vetoed && m.getReturnType() == Void.TYPE) {
            return null;
        }
        try {
            return m.invoke(target, forward);
        } catch (InvocationTargetException e) {
            throw e.getTargetException();
        }
    }

    /**
     * {@code args} with each DSIListener replaced by its wrapper: created on set, only looked up on clear (a
     * listener that was registered unwrapped is cleared unwrapped). A copy if anything changed.
     */
    private Object[] listenerArgs(Object[] args, boolean create) {
        Object[] out = args;
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            if (a instanceof DSIListener) {
                Object w = create ? wrapListener(a, hooks, bindings) : existingWrapper(a, hooks, bindings);
                if (w != null && w != a) {
                    if (out == args) {
                        out = (Object[]) args.clone();
                    }
                    out[i] = w;
                }
            }
        }
        return out;
    }

    /**
     * equals/hashCode/toString of the target. Two of our proxies are equal when their targets are; a proxy never
     * equals its raw target (that would not be symmetric).
     */
    private Object objectMethod(Object proxy, String name, Object[] args) {
        if ("equals".equals(name)) {
            Object other = args == null ? null : args[0];
            if (other == proxy) {
                return Boolean.TRUE;
            }
            if (other == null || !isOurs(other)) {
                return Boolean.FALSE;
            }
            Object otherTarget = ((DsiServiceProxy) Proxy.getInvocationHandler(other)).target;
            return target == otherTarget || target.equals(otherTarget) ? Boolean.TRUE : Boolean.FALSE;
        }
        if ("hashCode".equals(name)) {
            return new Integer(target.hashCode());
        }
        return target.toString();
    }
}
