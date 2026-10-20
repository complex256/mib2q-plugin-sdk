package mibsdk.spike.ui;

import de.audi.atip.activator.AbstractHMIActivator;
import de.audi.atip.hmi.HMIConditionBank;
import de.audi.atip.hmi.view.AbstractScreenFactory;
import de.audi.tghu.car.hmi.evohigh.CarConditionBank;
import de.audi.tghu.car.hmi.evohigh.CarScreenFactory;

/**
 * Stand-in for stock de.audi.tghu.car.hmi.Activator, named on the HMICar= line by run.sh. Same shape; the bank and
 * factory are the spike subclasses, each falling back to the stock object on any Throwable.
 */
public class SpikeCarActivator extends AbstractHMIActivator {
    private CarScreenFactory instance;
    private CarConditionBank conditionBank;

    public SpikeCarActivator() {
        super(6, "Car", System.getProperty("variant.skin", "EvoHigh"), SpikeBank.create());
        try {
            SpikeLog.log("activator constructed on " + Thread.currentThread().getName());
            SpikeProbes.start(this);
        } catch (Throwable t) {
            // stock behaviour
        }
    }

    protected synchronized AbstractScreenFactory getScreenFactory() {
        if (this.instance == null) {
            try {
                this.instance = new SpikeFactory(this.getFramework());
                SpikeLog.log("factory: SpikeFactory");
            } catch (Throwable t) {
                SpikeLog.log("factory FAILED, stock: " + t);
                this.instance = null;
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
}
