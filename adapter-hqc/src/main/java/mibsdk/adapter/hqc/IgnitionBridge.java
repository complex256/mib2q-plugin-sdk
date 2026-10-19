package mibsdk.adapter.hqc;

import mibsdk.api.IgnitionChanged;
import org.dsi.ifc.base.DSIListener;
import org.dsi.ifc.powermanagement.ClampSignal;
import org.dsi.ifc.powermanagement.DSIPowerManagement;
import org.dsi.ifc.powermanagement.DSIPowerManagementListener;

/**
 * Ignition (terminal 15) from {@code DSIPowerManagement}: the bridge's listener copies
 * {@code updateClampSignal(cs, flag).isClamp15()} on the stock callback thread and queues it on the
 * {@link EventPump}; the pump thread keeps the state and publishes {@link IgnitionChanged} on change only.
 *
 * <p>The first valid observation only establishes the state: it publishes {@code IgnitionChanged(true)} if the
 * ignition is on and nothing if it is off. So {@code IgnitionChanged(false)}, which makes the runtime restore
 * UNTIL_IGNITION_OFF overrides and record a clean shutdown, is published only for a real on-to-off transition:
 * initial off gives no event; on, off gives {@code true}, {@code false}; a repeated value gives nothing. Updates
 * whose flag is not {@code ATTRVALIDFLAG_VALID} are ignored.
 */
final class IgnitionBridge {

    private static final String CLAMP = "updateClampSignal";
    private static final int UNKNOWN = 0;
    private static final int ON = 1;
    private static final int OFF = 2;

    private final EventPump pump;
    private final int validFlag;
    private final Object listener;
    private final Object lock = new Object(); // attach/detach only, never on a callback
    private boolean closed; // guarded by lock
    private DSIPowerManagement service; // guarded by lock
    private int state = UNKNOWN; // pump thread only

    IgnitionBridge(EventPump pump) {
        this.pump = pump;
        this.validFlag = DsiConstants.get("org.dsi.ifc.base.DSIListener", "ATTRVALIDFLAG_VALID");
        this.listener = new BridgeListener() {
            void onUpdate(String method, Object value, int flag) {
                if (flag == validFlag && value != null && CLAMP.equals(method)) {
                    final boolean on = ((ClampSignal) value).isClamp15();
                    IgnitionBridge.this.pump.post(new Runnable() {
                        public void run() {
                            observe(on);
                        }
                    });
                }
            }
        }.newProxy(DSIPowerManagementListener.class);
    }

    /** Pump thread: the state machine described above. */
    private void observe(boolean on) {
        int next = on ? ON : OFF;
        int prev = state;
        state = next;
        if (prev == next || (prev == UNKNOWN && !on)) {
            return;
        }
        pump.publishFromPump(new IgnitionChanged(on));
    }

    /** Called once the service is available (on the waiter thread). Ignored after {@link #detach}. */
    void attach(Object found) {
        DSIPowerManagement s = (DSIPowerManagement) DsiServiceProxy.unwrap(found);
        synchronized (lock) {
            if (closed || service != null) {
                return;
            }
            s.setNotification((DSIListener) listener);
            service = s;
        }
    }

    /** Unregisters the listener. Never throws. */
    void detach() {
        DSIPowerManagement s;
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
}
