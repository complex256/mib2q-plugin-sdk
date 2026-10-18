package mibsdk.runtime;

import java.util.Set;
import mibsdk.api.OverrideConflictException;
import mibsdk.api.OverrideScope;
import mibsdk.api.Parking;
import mibsdk.api.PermissionDeniedException;
import mibsdk.api.Permissions;
import mibsdk.api.SoundDirections;
import mibsdk.api.Subscription;
import mibsdk.api.VehicleOverride;
import mibsdk.platform.ParkingIo;

/**
 * One plugin's {@link Parking}. Reading needs {@code vehicle.read} (checked by {@code ctx.vehicle()});
 * overriding also needs {@code vehicle.parking.write}. Every override goes through the
 * {@link OverrideLedger} under {@link #KEY}, which records the prior value and applies on its own thread;
 * the handle is tracked in the plugin's {@link Registrations}, so disable/quarantine releases it.
 * Objects handed to the plugin never lock themselves.
 */
final class ParkingImpl implements Parking {

    static final String KEY = "parking.soundDirections";

    private final String pluginId;
    private final ParkingIo io;
    private final OverrideLedger ledger;
    private final Registrations registrations;
    private final Set<String> granted;
    private final OverrideLedger.Applier applier;

    ParkingImpl(String pluginId, ParkingIo io, OverrideLedger ledger, Registrations registrations,
            Set<String> granted) {
        this.pluginId = pluginId;
        this.io = io;
        this.ledger = ledger;
        this.registrations = registrations;
        this.granted = granted;
        this.applier = applier(io);
    }

    /** Applies encoded parking values through {@code io}. */
    static OverrideLedger.Applier applier(final ParkingIo io) {
        return new OverrideLedger.Applier() {
            public void apply(String key, String encodedValue) throws Exception {
                if (!KEY.equals(key)) {
                    throw new IllegalArgumentException("not a parking key: " + key);
                }
                io.apply(decode(encodedValue));
            }
        };
    }

    @Override
    public SoundDirections soundDirections() {
        return io.current();
    }

    @Override
    public VehicleOverride overrideSoundDirections(SoundDirections value, OverrideScope scope)
            throws OverrideConflictException {
        if (!granted.contains(Permissions.VEHICLE_PARKING_WRITE)) {
            throw new PermissionDeniedException(Permissions.VEHICLE_PARKING_WRITE);
        }
        if (value == null || scope == null) {
            throw new IllegalArgumentException("value and scope are required");
        }
        SoundDirections prior = io.current();
        if (prior == null) {
            throw new IllegalStateException("the current parking sound directions are not known yet");
        }
        final VehicleOverride inner = ledger.acquire(KEY, pluginId, scope, encode(prior), encode(value), applier);
        final Subscription releaser = new Subscription() {
            public void cancel() {
                ledger.releaseByRuntime(inner);
            }
        };
        registrations.add(releaser); // closed registrations (plugin stopping) release at once
        return new VehicleOverride() {
            public boolean isActive() {
                return inner.isActive();
            }

            public void release() {
                inner.release();
                registrations.remove(releaser);
            }
        };
    }

    /** {@code F<0|1>R<0|1>L<0|1>X<0|1>}: front, rear, left, right (X). */
    static String encode(SoundDirections v) {
        StringBuffer b = new StringBuffer(8);
        b.append('F').append(v.front() ? '1' : '0');
        b.append('R').append(v.rear() ? '1' : '0');
        b.append('L').append(v.left() ? '1' : '0');
        b.append('X').append(v.right() ? '1' : '0');
        return b.toString();
    }

    /** Inverse of {@link #encode}; strict. */
    static SoundDirections decode(String s) {
        if (s == null || s.length() != 8 || s.charAt(0) != 'F' || s.charAt(2) != 'R' || s.charAt(4) != 'L'
                || s.charAt(6) != 'X') {
            throw new IllegalArgumentException("not an encoded SoundDirections: " + s);
        }
        return new SoundDirections(bit(s, 1), bit(s, 3), bit(s, 5), bit(s, 7));
    }

    private static boolean bit(String s, int i) {
        char c = s.charAt(i);
        if (c != '0' && c != '1') {
            throw new IllegalArgumentException("not an encoded SoundDirections: " + s);
        }
        return c == '1';
    }
}
