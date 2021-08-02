package vmtypes;

import org.cloudbus.cloudsim.resources.Pe;

/**
 * Models on demand virtual machine instances, provides no additional functionality and is
 * only necessary to provide an instantiation of {@link DynamicVm} other than Spot instances
 */
public class OnDemandInstance extends DynamicVm {

    /**
     * Instantiates an OnDemandInstance
     *
     * @param mipsCapacity the mips capacity of each Vm {@link Pe}
     * @param numberOfPes amount of {@link Pe} (CPU cores)
     */
    public OnDemandInstance(double mipsCapacity, long numberOfPes) {
        super(mipsCapacity, numberOfPes);
    }

    public OnDemandInstance(double mipsCapacity, long numberOfPes, boolean listener) {
        super(mipsCapacity, numberOfPes, listener);
    }

    public OnDemandInstance(long id, double mipsCapacity, long numberOfPes, boolean listener) {
        super(id, mipsCapacity, numberOfPes, listener);
    }

    @Override
    public String getType(){
        return "On-Demand";
    }
}
