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
 * <p>The service from the OSGi registry may be the SDK's {@link DsiServiceProxy}; the bridge uses the service
 * behind it, so its listener is not wrapped (no duplicate hook observations) and its writes are not hook calls (a
 * plugin's veto cannot block an SDK override). If something else wraps the proxy (DSITracer's trace proxy when a
 * trace chain is active) the bridge cannot see through it: the listener is still never wrapped
 * ({@link DsiServiceProxy.SdkOwnedListener}), but {@link #apply} then goes through the hook point.
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
        DSICarParkingSystem s = (DSICarParkingSystem) DsiServiceProxy.unwrap(found);
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
        s.setPDCSoundReproduction(new PDCSoundReproduction(value.front(), value.rear(), value.right(), value.left()));
        current = value;
    }
}
