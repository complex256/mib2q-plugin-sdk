package mibsdk.adapter.hqc.ui;

import de.audi.atip.activator.AbstractHMIActivator;
import de.audi.atip.hmi.HMIConditionBank;
import de.audi.atip.hmi.view.AbstractScreenFactory;
import de.audi.atip.model.CarModelBank;
import de.audi.tghu.car.hmi.evohigh.CarConditionBank;
import de.audi.tghu.car.hmi.evohigh.CarScreenFactory;
import mibsdk.adapter.hqc.SdkGlobals;

/**
 * The SDK's {@code HMICar=} activator: stock {@code de.audi.tghu.car.hmi.Activator}'s shape, with the SDK bank and
 * factory when {@link SdkGlobals#uiEnabled}, else (or on any Throwable) exactly the stock bank and factory.
 *
 * <p>Constructed by LSD on its startup dispatcher (a stock thread): the constructor only builds the bank and appends
 * one STATUS line to the in-memory log. It creates no thread and does no I/O; every SDK thread starts in
 * {@code HqcPlatform.start()}.
 */
public class SdkCarActivator extends AbstractHMIActivator {

    private static final String SOURCE = "mibsdk";

    private final boolean sdk;
    private CarScreenFactory instance;
    private CarConditionBank conditionBank;

    public SdkCarActivator() {
        this(newBank());
    }

    private SdkCarActivator(CarModelBank bank) {
        super(HqcUiIds.CAR_MODULE, "Car", System.getProperty("variant.skin", "EvoHigh"), bank);
        this.sdk = bank instanceof SdkCarModelBank;
    }

    /** The SDK bank when the UI is on and it builds; else the stock bank. Logs the choice once. Never throws. */
    private static CarModelBank newBank() {
        String reason = null;
        CarModelBank bank = null;
        try {
            if (SdkGlobals.uiEnabled) {
                bank = new SdkCarModelBank();
            } else {
                // null: no configurator of ours ran in this class loader (SdkGlobals.configuratorSeen is false)
                reason = SdkGlobals.uiOffReason == null ? "no-configurator" : SdkGlobals.uiOffReason;
            }
        } catch (Throwable t) {
            bank = null;
            reason = "bank-failed";
        }
        status(bank != null ? "ui car=sdk" : "ui car=stock reason=" + (reason == null ? "unknown" : reason));
        return bank != null ? bank : new CarModelBank();
    }

    /** The SDK factory over the SDK bank; the stock factory otherwise, or if the SDK one cannot be built. */
    protected synchronized AbstractScreenFactory getScreenFactory() {
        if (this.instance == null) {
            if (sdk) {
                try {
                    this.instance = new SdkCarScreenFactory(this.getFramework());
                } catch (Throwable t) {
                    this.instance = null;
                    status("ui car factory=stock reason=factory-failed");
                }
            }
            if (this.instance == null) {
                this.instance = new CarScreenFactory(this.getFramework());
            }
        }
        return this.instance;
    }

    public HMIConditionBank getConditionBank() {
        if (this.conditionBank == null) {
            this.conditionBank = new CarConditionBank((CarScreenFactory) this.getScreenFactory());
        }
        return this.conditionBank;
    }

    private static void status(String msg) {
        try {
            SdkGlobals.LOG.append("STATUS", SOURCE, msg);
        } catch (Throwable ignored) {
            // logging never breaks the Car HMI
        }
    }
}
