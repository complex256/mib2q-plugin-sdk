package mibsdk.adapter.hqc.ui;

import de.audi.atip.activator.AbstractHMIActivator;
import de.audi.atip.hmi.HMIConditionBank;
import de.audi.atip.hmi.view.AbstractScreenFactory;
import de.audi.atip.model.SystemModelBank;
import de.audi.tghu.system.hmi.evohigh.SystemConditionBank;
import de.audi.tghu.system.hmi.evohigh.SystemScreenFactory;
import mibsdk.adapter.hqc.SdkGlobals;

/**
 * The SDK's {@code HMISystem=} activator: stock {@code de.audi.tghu.system.hmi.Activator}'s shape, with
 * {@link SdkSystemScreenFactory} (the MENU ring gets a Plugins row) when the takeover may run, else exactly the stock
 * {@code SystemScreenFactory}: in {@code RUN_MODE=swdl} (or if reading it throws), with the SDK UI off, under the
 * forced failure {@code factory}, and on any Throwable while building the SDK factory. Sets
 * {@link SdkGlobals#mainMenu} to PENDING only with the SDK factory.
 *
 * <p>This runs inside the stock System module: a failure here would blank the whole HMI, firmware-update screens
 * included. So there is no static initializer and no static field other than compile-time constants, and every use of
 * an SDK class (other than {@link HqcUiIds} constants, which javac inlines) sits inside a catch of Throwable. Built by
 * LSD on a stock thread: no I/O, no thread; it logs one STATUS line through the in-memory log.
 */
public class SdkSystemActivator extends AbstractHMIActivator {

    private static final String SOURCE = "mibsdk";

    private SystemScreenFactory instance;
    private SystemConditionBank conditionBank;

    public SdkSystemActivator() {
        this(newBank());
    }

    private SdkSystemActivator(SystemModelBank bank) {
        super(HqcUiIds.SYSTEM_MODULE, "System", System.getProperty("variant.skin", "EvoHigh"), bank);
    }

    /**
     * The SDK bank when the UI is on and it builds; else the stock bank. The bar's file icons are System locators, so
     * the stock bank leaves them unavailable (the bar still draws text and built-in icons). Logs the choice once.
     * Never throws.
     */
    private static SystemModelBank newBank() {
        String reason = null;
        SystemModelBank bank = null;
        try {
            if (SdkGlobals.uiEnabled) {
                bank = new SdkSystemModelBank();
            } else {
                reason = SdkGlobals.uiOffReason == null ? "no-configurator" : SdkGlobals.uiOffReason;
            }
        } catch (Throwable t) {
            bank = null;
            reason = "bank-failed";
        }
        status(bank != null ? "ui system bank=sdk"
                : "ui system bank=stock reason=" + (reason == null ? "unknown" : reason));
        return bank != null ? bank : new SystemModelBank();
    }

    /** Chosen once: the SDK factory if the takeover may run and it builds; else the stock factory. */
    protected synchronized AbstractScreenFactory getScreenFactory() {
        if (this.instance == null) {
            String reason = "factory-failed";
            try {
                boolean swdl;
                try {
                    swdl = "swdl".equals(System.getProperty("RUN_MODE"));
                } catch (Throwable t) {
                    swdl = true;
                }
                if (swdl) {
                    reason = "swdl";
                } else if (!SdkGlobals.uiEnabled) {
                    reason = "ui-off";
                } else {
                    if ("factory".equals(SdkGlobals.uiForceFail)) {
                        reason = "forced";
                        throw new IllegalStateException("forced failure: factory");
                    }
                    SdkSystemScreenFactory f = new SdkSystemScreenFactory(this.getFramework());
                    SdkGlobals.mainMenu = SdkGlobals.MAIN_MENU_PENDING;
                    this.instance = f;
                    status("ui system=sdk");
                }
            } catch (Throwable t) {
                this.instance = null;
            }
            if (this.instance == null) {
                status("ui system=stock reason=" + reason);
                this.instance = new SystemScreenFactory(this.getFramework());
            }
        }
        return this.instance;
    }

    /** Tests: runs the lazy choice and returns the factory. */
    AbstractScreenFactory factoryForTests() {
        return getScreenFactory();
    }

    public HMIConditionBank getConditionBank() {
        if (this.conditionBank == null) {
            this.conditionBank = new SystemConditionBank((SystemScreenFactory) this.getScreenFactory());
        }
        return this.conditionBank;
    }

    private static void status(String msg) {
        try {
            SdkGlobals.LOG.append("STATUS", SOURCE, msg);
        } catch (Throwable ignored) {
            // logging never breaks the System HMI
        }
    }
}
