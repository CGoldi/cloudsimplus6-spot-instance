package allocation;

import org.cloudbus.cloudsim.allocationpolicies.*;
import org.cloudbus.cloudsim.brokers.DatacenterBroker;
import org.cloudbus.cloudsim.cloudlets.Cloudlet;
import org.cloudbus.cloudsim.cloudlets.CloudletExecution;
import org.cloudbus.cloudsim.datacenters.Datacenter;
import org.cloudbus.cloudsim.hosts.Host;
import org.cloudbus.cloudsim.hosts.HostSuitability;
import org.cloudbus.cloudsim.vms.Vm;
import vmtypes.DynamicVm;
import vmtypes.SpotInstance;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * This class has been adapted from {@link VmAllocationPolicySimple}
 */
public class DynamicAllocation extends VmAllocationPolicyAbstract {

    /** @see #getLastHostIndex() */
    private int lastHostIndex;

    /**
     * Instantiates the DynamicAllocation allocation policy
     */
    public DynamicAllocation() {
        super();
    }

    /**
     * Instantiates a VmAllocationPolicySimple, changing the {@link Function} to select a Host for a Vm
     * in order to define a different policy.
     *
     * @param findHostForVmFunction a {@link Function} to select a Host for a given Vm.
     * @see VmAllocationPolicy#setFindHostForVmFunction(java.util.function.BiFunction)
     */
    public DynamicAllocation(final BiFunction<VmAllocationPolicy, Vm, Optional<Host>> findHostForVmFunction) {
        super(findHostForVmFunction);
    }

    @Override
    protected Optional<Host> defaultFindHostForVm(final Vm vm) {
        final List<Host> hostList = getHostList();
        /* The for loop just defines the maximum number of Hosts to try.
         * When a suitable Host is found, the method returns immediately. */
        final int maxTries = hostList.size();
        for (int i = 0; i < maxTries; i++) {
            final Host host = hostList.get(lastHostIndex);
            if (host.isSuitableForVm(vm)) {
                return Optional.of(host);
            }

            /* If it gets here, the previous Host doesn't have capacity to place the VM.
             * Then, moves to the next Host.*/
            incLastHostIndex();
        }

        return Optional.empty();
    }

    /**
     * Gets the index of the last host where a VM was placed.
     */
    protected int getLastHostIndex() {
        return lastHostIndex;
    }

    /**
     * Increment the index to move to the next Host.
     * If the end of the Host list is reached, starts from the beginning. */
    protected void incLastHostIndex() {
        lastHostIndex = ++lastHostIndex % getHostList().size();
    }

    /**
     * First it checks if a suitable {@link Host} is available and allocates the VM instance if it is.
     * If no suitable host is found, it checks if it is possible to free capacity by destroying Spot instances
     * on any of the hosts by calling {@link DynamicAllocation#spotAllocation(Vm, Datacenter)}.
     * If after the trying the spot allocation, allocating the vm to a host still failed, it will be
     * added to a resubmitting list if {@link DynamicVm#persistentRequest} is true.
     *
     * @param vm virtual machine instance {@link Vm}
     * @return boolean value that determines if the was able to be allocated
     */
    @Override
    public HostSuitability allocateHostForVm(final Vm vm) {
        if (getHostList().isEmpty()) {
            LOGGER.error(
                    "{}: {}: {} could not be allocated because there isn't any Host for Datacenter {}",
                    vm.getSimulation().clockStr(), getClass().getSimpleName(), vm, getDatacenter().getId());
            return new HostSuitability("Datacenter has no host.");
        }

        if (vm.isCreated()) {
            return new HostSuitability("Vm already created.");
        }

        if (vm.getHost() != null && vm.getBroker() instanceof DatacenterBrokerDynamic) {
            final Host newHost = spotAllocationSpecificHost(vm, getDatacenter(), vm.getHost());
            if (newHost != null) {
                return allocateHostForVm(vm, newHost);
            }
        }

        final Optional<Host> optional = findHostForVm(vm);
        if (optional.isPresent()) {
            return allocateHostForVm(vm, optional.get());
        }



        // Checks if any spot instances can be destroyed to make space for other instances
        if (vm.getBroker() instanceof DatacenterBrokerDynamic) {
            final Host newHost = spotAllocation(vm, getDatacenter());
            if (newHost != null) {
                return allocateHostForVm(vm, newHost);
            }
        }

        LOGGER.warn("{}: {}: No suitable host found for {} in {}", vm.getSimulation().clockStr(), getClass().getSimpleName(), vm, getDatacenter());

        if (vm instanceof DynamicVm) {
            // Sets the initial instance request time for Dynamic Vms
            ((DynamicVm) vm).setInitialRequestTime(vm.getBroker().getSimulation().clock());

            // Add vm to resubmitting list if it is a persistent request
            if (vm.getBroker() instanceof DatacenterBrokerDynamic && ((DynamicVm) vm).isPersistentRequest()) {
                ((DatacenterBrokerDynamic) vm.getBroker()).getResubmittingList().add((DynamicVm) vm);
                vm.getBroker().getVmWaitingList().remove(vm);

                for (Cloudlet cloudlet : vm.getBroker().getCloudletWaitingList()) {
                    if (cloudlet.getVm() == vm) {
                        ((DynamicVm) vm).getFailedCloudlets().add(cloudlet);
                    }
                }
            }
        }
        return new HostSuitability("No suitable Host found.");
    }

    /**
     * Tries to find a host that has enough capacity for the new VM instance
     * if {@link SpotInstance} get deallocated.
     *
     * @param vm         the {@link Vm} instance that has to be allocated
     * @param datacenter the {@link Datacenter} which will be checked for allocation
     * @return the {@link Host} if it can be allocated or Null otherwise
     */
    public Host spotAllocation(final Vm vm, Datacenter datacenter) {

        if (!(vm instanceof SpotInstance) || ((SpotInstance) vm).getPriority()) {

            for (Host host : datacenter.getHostList()) {

                if (!checkSpotCapacityUsage(host, vm)) {
                    continue;
                }

                freeCapacity(host, vm, datacenter);

                if (host.isSuitableForVm(vm)) {
                    if(vm instanceof SpotInstance) {
                        LOGGER.warn("Making Space for Priority");
                    }
                    return host;
                }
            }
        }

        return null;
    }

    /**
     * Frees the capacity if a suitable host is found.
     *
     * @param host the {@link Host} on which the vm will be allocated
     * @param vm the {@link Vm} instance that has to be allocated
     * @param datacenter the {@link Datacenter} which will be checked for allocation
     */
    public void freeCapacity(Host host, Vm vm, Datacenter datacenter){
        // Spot instances get removed until the host is suitable for the
        // vm or if no more spot instances are available
        int i = 0;

        while (!host.isSuitableForVm(vm) && i < host.getVmList().size()) {
            if(i==0) {
                LOGGER.warn("Checking for Spot Destruction");
            }

            boolean priority = false;

            if (vm instanceof SpotInstance) {
                priority = ((SpotInstance) vm).getPriority();
            }

            Vm VmToDestroy = host.getVmList().get(i);
            if (VmToDestroy instanceof SpotInstance) {
                if (!priority || !(((SpotInstance) VmToDestroy).getPriority())) {

                    DatacenterBroker broker = VmToDestroy.getBroker();
                    if (((SpotInstance) VmToDestroy).getMinimumRunningTime() <
                        broker.getSimulation().clock() - vm.getStartTime()) {

                        broker.LOGGER.info(
                            "{}: {}: Destroying {} on {}, free capacity for On-demand instances",
                            broker.getSimulation().clockStr(), datacenter.getClass().getSimpleName(), VmToDestroy,
                            VmToDestroy.getHost());

                        terminationBehavior((SpotInstance) VmToDestroy);

                    }
                }
            }
            i++;
        }
    }

    /**
     * Checks the total amount of resources that are used by Spot instances on a specific Host
     * to avoid terminating instances that wouldn't provide enough space for the new vm
     *
     * @param host the {@link Host} that gets checked for capacity
     * @param vm   the {@link Vm} instance that has to be allocated
     */
    public boolean checkSpotCapacityUsage(Host host, Vm vm) {

        long spotStorageCapacity = 0;
        long spotRamCapacity = 0;
        long spotBwCapacity = 0;
        long spotPeCapacity = 0;

        for (Vm v : host.getVmList()) {
            if (v instanceof SpotInstance) {
                spotStorageCapacity += v.getStorage().getCapacity();
                spotRamCapacity += v.getRam().getCapacity();
                spotBwCapacity += v.getBw().getCapacity();
                spotPeCapacity += v.getNumberOfPes();
            }

            if (vm.getStorage().getCapacity() <= spotStorageCapacity && vm.getRam().getCapacity() <= spotRamCapacity
                    && vm.getBw().getCapacity() <= spotBwCapacity && vm.getNumberOfPes() <= spotPeCapacity) {
                return true;
            }
        }
        return false;
    }


    /**
     * Depending on the {@link SpotInstance#getInterruptionBehavior()} the Spot instance will either be interrupted or
     * terminated. If the behavior is set to HIBERNATE, the Spot instance will be INTERRUPTED and all running
     * {@link Cloudlet} will be paused. If the behavior is set to TERMINATE, the Spot instance will be TERMINATED and
     * the instance including the cloudlets will be destroyed.
     *
     * @param VmToDestroy the {@link SpotInstance} that needs to be deallocated from the host
     */
    public void terminationBehavior(SpotInstance VmToDestroy) {

        DatacenterBroker broker = VmToDestroy.getBroker();
        List<CloudletExecution> execCloudlets = VmToDestroy.getCloudletScheduler().getCloudletExecList();
        List<Cloudlet> cloudletList = new ArrayList<>();

        if (VmToDestroy.getInterruptionBehavior() == SpotInstance.InterruptionBehavior.HIBERNATE) {
            // Pause Execute, Two for loops to avoid concurrent modification error
            for (CloudletExecution cloudlet : execCloudlets) {
                cloudletList.add(cloudlet.getCloudlet());
            }
            for (Cloudlet cloudlet : cloudletList) {
                VmToDestroy.getCloudletScheduler().cloudletPause(cloudlet);

                broker.LOGGER.info(
                        "{}: {}: Pause Cloudlet {} on {} / executed {} mips",
                        broker.getSimulation().clockStr(), getDatacenter().getClass().getSimpleName(), cloudlet,
                        VmToDestroy, cloudlet.getFinishedLengthSoFar());
            }

            VmToDestroy.setPausedCloudlets(cloudletList);

            // VM gets added to resubmitting List
            if (VmToDestroy.getBroker() instanceof DatacenterBrokerDynamic) {
                ((DatacenterBrokerDynamic) VmToDestroy.getBroker()).getResubmittingList().add(VmToDestroy);
            }

            VmToDestroy.getHost().destroyVm(VmToDestroy);
            VmToDestroy.setState(DynamicVm.State.INTERRUPTED);

        } else {
            VmToDestroy.getBroker().destroyVm(VmToDestroy);
            VmToDestroy.setState(DynamicVm.State.TERMINATED);
        }
    }


    /**
     * Frees capacity on a specific host to enable allocation. Used for the Google Machine Trace Events
     *
     * @param vm         the {@link Vm} instance that has to be allocated
     * @param datacenter the {@link Datacenter} which will be checked for allocation
     * @return the {@link Host} if it can be allocated or Null otherwise
     */
    public Host spotAllocationSpecificHost(final Vm vm, Datacenter datacenter, Host host) {

        if (!(vm instanceof SpotInstance)) {

            freeCapacity(host, vm, datacenter);

            if (host.isSuitableForVm(vm)) {
                return host;
            }
        }
        return null;
    }
}
