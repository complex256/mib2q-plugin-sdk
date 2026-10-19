package mibsdk.adapter.hqc;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import mibsdk.api.HookPoint;
import mibsdk.api.SoundDirections;

/**
 * Which DSI interface methods are hook points, and how their arguments become API values. Immutable after
 * construction, so the stock threads read it without locking.
 */
final class HookBindings {

    static final String PARKING = "org.dsi.ifc.carparkingsystem.DSICarParkingSystem";
    static final String PARKING_LISTENER = "org.dsi.ifc.carparkingsystem.DSICarParkingSystemListener";

    /** Getters of a PDCSoundReproduction class, resolved once per class. */
    private static final class SoundGetters {
        final Class type;
        final Method front;
        final Method rear;
        final Method left;
        final Method right;

        SoundGetters(Class type) throws NoSuchMethodException {
            this.type = type;
            this.front = type.getMethod("isFront", (Class[]) null);
            this.rear = type.getMethod("isRear", (Class[]) null);
            this.left = type.getMethod("isLeft", (Class[]) null);
            this.right = type.getMethod("isRight", (Class[]) null);
        }
    }

    private static final HookBindings DEFAULTS = createDefaults();

    /** Interface name to (method name to HookPoint). */
    private final Map points;
    /** Last class seen by {@link #toApiValue}; a race only costs a second lookup. */
    private volatile SoundGetters sound;

    private HookBindings(Map points) {
        this.points = points;
    }

    /**
     * {@code DSICarParkingSystem.setPDCSoundReproduction} is {@link HookPoint#PARKING_SOUND_SET};
     * {@code DSICarParkingSystemListener.updatePDCSoundReproduction} is {@link HookPoint#PARKING_SOUND_UPDATE}.
     * One shared instance (immutable), so listener wrappers made for it are shared too.
     */
    static HookBindings defaults() {
        return DEFAULTS;
    }

    private static HookBindings createDefaults() {
        Map points = new HashMap();
        bind(points, PARKING, "setPDCSoundReproduction", HookPoint.PARKING_SOUND_SET);
        bind(points, PARKING_LISTENER, "updatePDCSoundReproduction", HookPoint.PARKING_SOUND_UPDATE);
        return new HookBindings(points);
    }

    private static void bind(Map points, String iface, String method, HookPoint p) {
        Map methods = (Map) points.get(iface);
        if (methods == null) {
            methods = new HashMap();
            points.put(iface, methods);
        }
        methods.put(method, p);
    }

    /** The point bound to {@code iface.method} (the interface that declares the method); null if none. */
    HookPoint pointFor(String iface, String method) {
        Map methods = (Map) points.get(iface);
        return methods == null ? null : (HookPoint) methods.get(method);
    }

    /** Whether any of {@code interfaces} declares a bound method. */
    boolean bindsAny(Class[] interfaces) {
        for (int i = 0; i < interfaces.length; i++) {
            if (bindsAnyIn(interfaces[i])) {
                return true;
            }
        }
        return false;
    }

    private boolean bindsAnyIn(Class iface) {
        if (points.containsKey(iface.getName())) {
            return true;
        }
        Class[] supers = iface.getInterfaces();
        for (int i = 0; i < supers.length; i++) {
            if (bindsAnyIn(supers[i])) {
                return true;
            }
        }
        return false;
    }

    /**
     * The API value for a call at {@code p}: for both parking points the first argument, a
     * {@code PDCSoundReproduction}, read through its getters as {@link SoundDirections}. Null if the argument
     * is missing or unreadable. Never throws.
     */
    Object toApiValue(HookPoint p, Object[] args) {
        try {
            if ((p != HookPoint.PARKING_SOUND_SET && p != HookPoint.PARKING_SOUND_UPDATE)
                    || args == null || args.length == 0 || args[0] == null) {
                return null;
            }
            Object v = args[0];
            SoundGetters g = sound;
            if (g == null || g.type != v.getClass()) {
                g = new SoundGetters(v.getClass());
                sound = g;
            }
            return new SoundDirections(is(g.front, v), is(g.rear, v), is(g.left, v), is(g.right, v));
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean is(Method getter, Object target) throws Exception {
        return ((Boolean) getter.invoke(target, (Object[]) null)).booleanValue();
    }
}
