package mibsdk.spike.ui;

import de.audi.atip.activator.AbstractHMIActivator;
import de.audi.atip.hmi.HMIConditionBank;
import de.audi.atip.hmi.view.AbstractScreenFactory;
import de.audi.atip.model.SystemModelBank;
import de.audi.tghu.system.hmi.evohigh.SystemConditionBank;
import de.audi.tghu.system.hmi.evohigh.SystemScreenFactory;

/**
 * Spike C, Boot B: stand-in for stock de.audi.tghu.system.hmi.Activator, named on the HMISystem= line by run.sh
 * (not with --car-only). Same shape as stock; the factory is SpikeSystemFactory, or the stock SystemScreenFactory on
 * any Throwable and always in RUN_MODE=swdl. No static initializer; nothing here throws into the framework.
 */
public class SpikeSystemActivator extends AbstractHMIActivator {
    private SystemScreenFactory instance;
    private SystemConditionBank conditionBank;

    public SpikeSystemActivator() {
        super(0, "System", System.getProperty("variant.skin", "EvoHigh"), new SystemModelBank());
        try {
            boolean seen = false;
            try {
                seen = mibsdk.adapter.hqc.SdkGlobals.configuratorSeen;
            } catch (Throwable t) {
                // adapter missing: report false
            }
            SpikeLog.log("system activator constructed thread=" + Thread.currentThread().getName()
                    + " configuratorSeen=" + seen + " runMode=" + System.getProperty("RUN_MODE"));
        } catch (Throwable t) {
            // stock behaviour
        }
    }

    protected synchronized AbstractScreenFactory getScreenFactory() {
        if (this.instance == null) {
            boolean swdl = false;
            try {
                swdl = "swdl".equals(System.getProperty("RUN_MODE"));
            } catch (Throwable t) {
                swdl = true;
            }
            if (!swdl) {
                try {
                    this.instance = new SpikeSystemFactory(this.getFramework());
                    SpikeLog.log("factory: SpikeSystemFactory");
                } catch (Throwable t) {
                    SpikeLog.log("system factory FAILED, stock: " + t);
                    this.instance = null;
                }
            }
            if (this.instance == null) {
                this.instance = new SystemScreenFactory(this.getFramework());
            }
        }
        return this.instance;
    }

    public HMIConditionBank getConditionBank() {
        if (this.conditionBank == null) {
            this.conditionBank = new SystemConditionBank((SystemScreenFactory) this.getScreenFactory());
        }
        return this.conditionBank;
    }
}
