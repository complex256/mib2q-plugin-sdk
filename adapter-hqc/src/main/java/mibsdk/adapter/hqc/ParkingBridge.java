package mibsdk.adapter.hqc;

import mibsdk.api.ParkingSoundChanged;
import mibsdk.api.SoundDirections;
import mibsdk.api.TrailerHitchedChanged;
import mibsdk.platform.ParkingIo;
import org.dsi.ifc.base.DSIListener;
import org.dsi.ifc.carparkingsystem.DSICarParkingSystem;
import org.dsi.ifc.carparkingsystem.DSICarParkingSystemListener;
import org.dsi.ifc.carparkingsystem.PDCSoundReproduction;

/**
 * Parking sound directions and trailer state from {@code DSICarParkingSystem}.
 *
 * <p>The bridge's own listener ({@link java.lang.reflect.Proxy} over {@code DSICarParkingSystemListener}) is
 * registered with {@code setNotification}. On the stock callback thread a valid
 * {@code updatePDCSoundReproduction} only caches the value (a volatile write) and queues a
 * {@code ParkingSoundChanged} on the {@link EventPump}; a valid {@code updatePDCTrailerHitched} queues a
 * {@code TrailerHitchedChanged}. Updates whose flag is not {@code ATTRVALIDFLAG_VALID} (read at run time through
 * {@link DsiConstants}) and every other callback are ignored.
 *
 * <p>The bridge never talks to what the OSGi registry holds when the SDK wrapped the service: that is our
 * {@link DsiServiceProxy}, and with DSITracer active (as on the bench) a stock trace proxy over ours, which hides
 * its target and replaces listeners with its own facades. It uses the raw service {@link ConfiguratorCore} recorded
 * in {@link RawServices} when it wrapped it, so its listener is registered unwrapped and untraced (no duplicate
 * {@code PARKING_SOUND_UPDATE} observations) and its writes are neither hook observations nor vetoable (a plugin's
 * veto can never block an SDK override restore). Only if no raw service was recorded does it use the registry
 * object (minus our proxy, if it is one); then {@link #apply} runs as an SDK call
 * ({@link DsiServiceProxy#beginSdkCall}), which our proxy neither observes nor vetoes even below another wrapper.
 */
final class ParkingBridge implements ParkingIo {

    private static final String SOUND = "updatePDCSoundReproduction";
    private static final String TRAILER = "updatePDCTrailerHitched";

    private final EventPump pump;
    private final int validFlag;
    private final Object listener;
    private final Object lock = new Object(); // attach/detach only, never on a callback
    private boolean closed; // guarded by lock
    private volatile DSICarParkingSystem service;
    private volatile SoundDirections current;

    ParkingBridge(EventPump pump) {
        this.pump = pump;
        this.validFlag = DsiConstants.get("org.dsi.ifc.base.DSIListener", "ATTRVALIDFLAG_VALID");
        this.listener = new BridgeListener() {
            void onUpdate(String method, Object value, int flag) {
                if (flag != validFlag || value == null) {
                    return;
                }
                if (SOUND.equals(method)) {
                    onSound((PDCSoundReproduction) value);
                } else if (TRAILER.equals(method)) {
                    ParkingBridge.this.pump.publish(new TrailerHitchedChanged(((Boolean) value).booleanValue()));
                }
            }
        }.newProxy(DSICarParkingSystemListener.class);
    }

    private void onSound(PDCSoundReproduction r) {
        SoundDirections v = new SoundDirections(r.isFront(), r.isRear(), r.isLeft(), r.isRight());
        current = v;
        pump.publish(new ParkingSoundChanged(v));
    }

    /** Called once the service is available (on the waiter thread). Ignored after {@link #detach}. */
    void attach(Object found) {
        DSICarParkingSystem s = (DSICarParkingSystem) RawServices.resolve(DSICarParkingSystem.class.getName(), found);
        synchronized (lock) {
            if (closed || service != null) {
                return;
            }
            s.setNotification((DSIListener) listener);
            service = s;
        }
    }

    /** Unregisters the listener; later {@link #apply} calls fail. Never throws. */
    void detach() {
        DSICarParkingSystem s;
        synchronized (lock) {
            closed = true;
            s = service;
            service = null;
        }
        if (s != null) {
            try {
                s.clearNotification((DSIListener) listener);
            } catch (Throwable ignored) {
                // the service may be gone already
            }
        }
    }

    /** The bridge's DSI listener (tests). */
    Object listener() {
        return listener;
    }

    /** The last valid value from the car or the last successful {@link #apply}; null until either. */
    public SoundDirections current() {
        return current;
    }

    /** Writes {@code value}; {@link #current()} returns it at once. Throws if the service is not there yet. */
    public void apply(SoundDirections value) throws Exception {
        if (value == null) {
            throw new IllegalArgumentException("value");
        }
        DSICarParkingSystem s = service;
        if (s == null) {
            throw new IllegalStateException("DSICarParkingSystem is not available");
        }
        // DSI constructor order: front, rear, right, left.
        PDCSoundReproduction r = new PDCSoundReproduction(value.front(), value.rear(), value.right(), value.left());
        DsiServiceProxy.beginSdkCall();
        try {
            s.setPDCSoundReproduction(r);
        } finally {
            DsiServiceProxy.endSdkCall();
        }
        current = value;
    }
}
