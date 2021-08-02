package vmtypes;

import org.cloudbus.cloudsim.cloudlets.Cloudlet;
import org.cloudbus.cloudsim.resources.Pe;
import org.cloudbus.cloudsim.schedulers.cloudlet.CloudletScheduler;
import org.cloudbus.cloudsim.vms.Vm;
import org.cloudbus.cloudsim.vms.VmSimple;
import org.cloudsimplus.listeners.VmDatacenterEventInfo;
import org.cloudsimplus.listeners.VmHostEventInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Abstract class for Dynamic Virtual Machine instances extends the functionality of {@link VmSimple}
 */
public abstract class DynamicVm extends VmSimple {

    private State state = State.WAITING;

    /**
     * Determines if a request will be persistent or not, by default creation requests are not persistent
     */
    public boolean persistentRequest = false;

    /**
     * The time to wait until a request will be canceled, by default requests will be immediately canceled
     * if the creation fails
     */
    private double waitingTime = 0;

    /**
     * The time at which the Instance was requested
     */
    private double initialRequestTime;

    /**
     * List of paused {@link Cloudlet} instances assigned to this VM instance
     */
    private List<Cloudlet> pausedCloudlets = new ArrayList<>();

    /**
     * List of failed {@link Cloudlet} instances assigned to this VM instance
     */
    private List<Cloudlet> failedCloudlets = new ArrayList<>();

    /**
     * List of failed {@link Cloudlet} cloudlets that haven't been submitted yet but will
     * be bound to the virtual machine
     */
    private List<Cloudlet> waitingCloudlets = new ArrayList<>();

    /**
     * Enumeration class that defines the state of the DynamicVM instance
     */
    public enum State {
        WAITING, TERMINATED, ACTIVE, INTERRUPTED, FINISHED, FAILURE
    }

    /**
     * Instantiates a DynamicVM instance without active Listeners
     *
     * @param mipsCapacity the mips capacity of each Vm {@link Pe}
     * @param numberOfPes  amount of {@link Pe} (CPU cores)
     */

    public DynamicVm(double mipsCapacity, long numberOfPes) {
        super(mipsCapacity, numberOfPes);
    }

    /**
     * Instantiates a DynamicVM instance with active Listeners that enable the dynamic behavior of the instances
     *
     * @param mipsCapacity the mips capacity of each Vm {@link Pe}
     * @param numberOfPes  amount of {@link Pe} (CPU cores)
     * @param listener     boolean that defines if listeners are active
     */

    public DynamicVm(double mipsCapacity, long numberOfPes, boolean listener) {
        super(mipsCapacity, numberOfPes);
        if (listener) {
            addOnHostDeallocationListener(this::deallocatingBehavior);
            addOnHostAllocationListener(this::allocationBehavior);
            addOnCreationFailureListener(this::creationFailedBehavior);
        }
    }

    public DynamicVm(double mipsCapacity, long numberOfPes, CloudletScheduler cloudletScheduler, boolean listener) {
        super(mipsCapacity, numberOfPes, cloudletScheduler);
        if (listener) {
            addOnHostDeallocationListener(this::deallocatingBehavior);
            addOnHostAllocationListener(this::allocationBehavior);
            addOnCreationFailureListener(this::creationFailedBehavior);
        }
    }

    public DynamicVm(long id, double mipsCapacity, long numberOfPes, boolean listener) {
        super(id, mipsCapacity, numberOfPes);
        if (listener) {
            addOnHostDeallocationListener(this::deallocatingBehavior);
            addOnHostAllocationListener(this::allocationBehavior);
            addOnCreationFailureListener(this::creationFailedBehavior);
        }
    }

    public DynamicVm(Vm sourceVm) {
        super(sourceVm);
    }

    public String getType() {
        return "";
    }

    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    public double getWaitingTime() {
        return waitingTime;
    }

    public void setWaitingTime(double waitingTime) {
        this.waitingTime = waitingTime;
    }

    public boolean isPersistentRequest() {
        return persistentRequest;
    }

    public void setPersistentRequest(boolean persistentRequest) {
        this.persistentRequest = persistentRequest;
    }

    public double getInitialRequestTime() {
        return initialRequestTime;
    }

    public void setInitialRequestTime(double initialRequestTime) {
        this.initialRequestTime = initialRequestTime;
    }

    public List<Cloudlet> getPausedCloudlets() {
        return pausedCloudlets;
    }

    public void setPausedCloudlets(List<Cloudlet> pausedCloudlets) {
        this.pausedCloudlets = pausedCloudlets;
    }

    public List<Cloudlet> getFailedCloudlets() {
        return failedCloudlets;
    }

    public void setFailedCloudlets(List<Cloudlet> failedCloudlets) {
        this.failedCloudlets = failedCloudlets;
    }

    public List<Cloudlet> getWaitingCloudlets() {
        return waitingCloudlets;
    }

    /**
     * Allocation Listener {@link org.cloudsimplus.listeners.EventListener} for DynamicVM instances,
     * When an DynimcVm instaces gets allocated to a host the {@link State} of the instance will change to ACTIVE
     * and all paused {@link Cloudlet} will be resumed and removed from the {@link DynamicVm#pausedCloudlets} list
     * Will display a Log entry for each resumed cloudlet, including the time, broker, cloudlet ID and VM ID.
     *
     * @param vmHostEventInfo information about the allocation event
     */
    private void allocationBehavior(VmHostEventInfo vmHostEventInfo) {
        setState(State.ACTIVE);

        // Resumes the paused cloudlets
        for (Cloudlet cloudlet : pausedCloudlets) {

            getCloudletScheduler().cloudletResume(cloudlet);
            updateProcessing(getHost().getVmScheduler().getAllocatedMips(this));

            LOGGER.info("{}: {}: {} resumed on {}", getSimulation().clockStr(), getBroker(), cloudlet, this);
        }

        if(waitingCloudlets.size() != 0) {
            for (Cloudlet cloudlet : waitingCloudlets) {
                getBroker().submitCloudlet(cloudlet);
            }
        }

//        waitingCloudlets.clear();
        pausedCloudlets.removeAll(getPausedCloudlets());
    }

    /**
     * Deallocation Listener {@link org.cloudsimplus.listeners.EventListener} for DynamicVM instances,
     * When an DynamicVm instance gets deallocated and it didn't get INTERRUPTED or TERMINATED the {@link State}
     * gets set to FINISHED
     *
     * @param vmHostEventInfo information about the deallocation event
     */
    private void deallocatingBehavior(VmHostEventInfo vmHostEventInfo) {
        if (getState() != State.INTERRUPTED || getState() != State.TERMINATED) {
            setState(State.FINISHED);
        }
    }

    /**
     * Creation Failure Listener {@link org.cloudsimplus.listeners.EventListener} for DynamicVM instances,
     * When the allocation of an DynamicVM instance fails, the {@link State} gets set to WAITING if it has a
     * waitingTime bigger then 0, otherwise it gets set to FAILURE
     *
     * @param vmDatacenterEventInfo information about the creation failure event
     */
    private void creationFailedBehavior(VmDatacenterEventInfo vmDatacenterEventInfo) {
        if (waitingTime > 0) {
            setState(State.WAITING);
        } else {
            setState(State.FAILURE);
        }
    }

}
