package mibsdk.platform;

import java.io.File;

/**
 * Where the SDK keeps its files, supplied by the platform side (the unit's adapter module, or a test fake) so the
 * runtime never names an on-unit path itself. Three roots, by who may write them while the HMI runs:
 * <ul>
 * <li>{@link #install()}: read-only at runtime, written only by the installer ({@code sdk.properties},
 * {@code grants.properties}, the safe-mode marker, {@code plugins/<id>.jar} and the reload markers);</li>
 * <li>{@link #persist()}: writable and persistent ({@code boot.state}, {@code ledger.properties},
 * {@code data/});</li>
 * <li>{@link #ram()}: writable, lost at power-off ({@code cache/} jar snapshots).</li>
 * </ul>
 * The roots may coincide (a test may use one directory for all three).
 */
public final class SdkRoots {

    private final File install;
    private final File persist;
    private final File ram;

    public SdkRoots(File install, File persist, File ram) {
        this.install = install;
        this.persist = persist;
        this.ram = ram;
    }

    /** Read-only at runtime: written by the installer only. */
    public File install() {
        return install;
    }

    /** Writable, survives power-off. */
    public File persist() {
        return persist;
    }

    /** Writable, volatile. */
    public File ram() {
        return ram;
    }

    public String toString() {
        return "install=" + install + " persist=" + persist + " ram=" + ram;
    }
}
