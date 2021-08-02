
package allocation;

import org.cloudbus.cloudsim.brokers.DatacenterBroker;
import org.cloudbus.cloudsim.cloudlets.Cloudlet;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.CloudSimTags;
import org.cloudbus.cloudsim.datacenters.Datacenter;
import org.cloudbus.cloudsim.vms.Vm;
import org.cloudbus.cloudsim.brokers.DatacenterBrokerSimple;
import vmtypes.DynamicVm;
import vmtypes.SpotInstance;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * This class has been adapted from {@link DatacenterBrokerSimple}
 */

public class DatacenterBrokerDynamic extends allocation.DatacenterBrokerAbstract {
    /**
     * Index of the last VM selected from the {@link #getVmExecList()}
     * to run some Cloudlet.
     */
    private int lastSelectedVmIndex;

    /**
     * Index of the last Datacenter selected to place some VM.
     */
    private int lastSelectedDcIndex;

    /**
     * VMs that weren't able to be created can be resubmitted if the have an assigned waitingTime
     */
    private final List<DynamicVm> resubmittingList = new ArrayList<>();

    /**
     * Creates a new DatacenterBroker.
     *
     * @param simulation the CloudSim instance that represents the simulation the Entity is related to
     */
    public DatacenterBrokerDynamic(final CloudSim simulation) {
        this(simulation, "");
    }

    /**
     * Creates a DatacenterBroker giving a specific name.
     *
     * @param simulation the CloudSim instance that represents the simulation the Entity is related to
     * @param name       the DatacenterBroker name
     */
    public DatacenterBrokerDynamic(final CloudSim simulation, final String name) {
        super(simulation, name);
        this.lastSelectedVmIndex = -1;
        this.lastSelectedDcIndex = -1;
    }

    public List<DynamicVm> getResubmittingList() {
        return resubmittingList;
    }

    @Override
    protected Datacenter defaultDatacenterMapper(final Datacenter lastDatacenter, final Vm vm) {
        if (getDatacenterList().isEmpty()) {
            throw new IllegalStateException("You don't have any Datacenter created.");
        }

        if (lastDatacenter != Datacenter.NULL && lastSelectedDcIndex != -1) {
            return getDatacenterList().get(lastSelectedDcIndex);
        }

        /*If all Datacenter were tried already, return Datacenter.NULL to indicate
         * there isn't a suitable Datacenter to place waiting VMs.*/
        if (lastSelectedDcIndex == getDatacenterList().size() - 1) {
            return Datacenter.NULL;
        }

        return getDatacenterList().get(++lastSelectedDcIndex);
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>It applies a Round-Robin policy to cyclically select
     * the next Vm from the {@link #getVmWaitingList() list of waiting VMs}.</p>
     *
     * @param cloudlet {@inheritDoc}
     * @return {@inheritDoc}
     */
    @Override
    protected Vm defaultVmMapper(final Cloudlet cloudlet) {
        if (cloudlet.isBoundToVm()) {
            return cloudlet.getVm();
        }

        if (getVmExecList().isEmpty()) {
            return Vm.NULL;
        }

        /*If the cloudlet isn't bound to a specific VM or the bound VM was not created,
        cyclically selects the next VM on the list of created VMs.*/
        lastSelectedVmIndex = ++lastSelectedVmIndex % getVmExecList().size();
        return getVmFromCreatedList(lastSelectedVmIndex);
    }

    /**
     * Resets the last selected datacenter and the allocation data from the {@link Vm} instance
     * to enable a new allocation
     * The VMs from the resubmitting List and if possible they will be resubmitted by the Broker, which restarts
     * the allocation of the intances
     */
    public void resubmitVms() {
        List<DynamicVm> vmsToResubmit = new ArrayList<>();
        List<DynamicVm> spotVmsToResubmit = new ArrayList<>();

        resetBrokerAllocation();

        for (DynamicVm vm : resubmittingList) {
            // Reset Last Tried Datacenter to enable new allocation, reset startime
            vm.setLastTriedDatacenter(Datacenter.NULL);
            vm.setFailed(false);
            vm.setCreated(false);
            vm.setStartTime(-1);
            vm.setSubmissionDelay(0);
            vm.setStopTime(-1);

            if (vm.getState() == DynamicVm.State.WAITING) {
                if (getSimulation().clock() < (vm.getInitialRequestTime() + vm.getWaitingTime())) {
                    vmsToResubmit.add(vm);
                } else {
                    vm.setState(DynamicVm.State.FAILURE);
                }
            } else if (vm.getState() == DynamicVm.State.INTERRUPTED && vm instanceof SpotInstance) {
                if (getSimulation().clock() < (vm.getStopTime() + ((SpotInstance) vm).getHibernationTimeLimit())) {
                    spotVmsToResubmit.add(vm);
                } else {
                    vm.setState(DynamicVm.State.TERMINATED);
                }
            }

        }

        vmsToResubmit.addAll(spotVmsToResubmit);
        resubmittingList.clear();

        submitVmList(vmsToResubmit);

        Set<Vm> set = new HashSet<>(getVmExecList());
        getVmExecList().clear();
        getVmExecList().addAll(set);
    }

    public void resubmitSomeVms(int count) {
        List<DynamicVm> vmsToResubmit = new ArrayList<>();
        List<DynamicVm> spotVmsToResubmit = new ArrayList<>();

        resetBrokerAllocation();

        if (resubmittingList.size() < count) {
            count = resubmittingList.size();
        }

        for (int i = 0; i < count; i++) {
            DynamicVm vm = resubmittingList.get(0);

            // Reset Last Tried Datacenter to enable new allocation, reset startime
            vm.setLastTriedDatacenter(Datacenter.NULL);
            vm.setFailed(false);
            vm.setCreated(false);
            vm.setStartTime(-1);
            vm.setSubmissionDelay(0);
            vm.setStopTime(-1);

            if (vm.getState() == DynamicVm.State.WAITING) {
                if (getSimulation().clock() < (vm.getInitialRequestTime() + vm.getWaitingTime())) {
                    vmsToResubmit.add(vm);
                } else {
                    vm.setState(DynamicVm.State.FAILURE);
                }
            } else if (vm.getState() == DynamicVm.State.INTERRUPTED && vm instanceof SpotInstance) {
                if (getSimulation().clock() < (vm.getStopTime() + ((SpotInstance) vm).getHibernationTimeLimit())) {
                    spotVmsToResubmit.add(vm);
                } else {
                    vm.setState(DynamicVm.State.TERMINATED);
                }
            }

            resubmittingList.remove(0);
        }

        vmsToResubmit.addAll(spotVmsToResubmit);

        submitVmList(vmsToResubmit);

        Set<Vm> set = new HashSet<>(getVmExecList());
        getVmExecList().clear();
        getVmExecList().addAll(set);
    }

    /**
     * Resets the last selected Datacenter, so new VM's can be created by the same broker after deallocation
     * of other VMs
     */
    public void resetBrokerAllocation() {
        if (lastSelectedDcIndex == getDatacenterList().size() - 1) {
            this.lastSelectedDcIndex = -1;
        }
    }

    public void requestWaitingCloudlet() {
        requestDatacentersToCreateWaitingCloudlets();
    }

}




