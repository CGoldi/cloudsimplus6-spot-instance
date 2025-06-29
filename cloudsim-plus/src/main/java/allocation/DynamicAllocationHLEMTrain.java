package allocation;

import org.cloudbus.cloudsim.allocationpolicies.VmAllocationPolicy;
import org.cloudbus.cloudsim.allocationpolicies.VmAllocationPolicyAbstract;
import org.cloudbus.cloudsim.allocationpolicies.VmAllocationPolicySimple;
import org.cloudbus.cloudsim.brokers.DatacenterBroker;
import org.cloudbus.cloudsim.cloudlets.Cloudlet;
import org.cloudbus.cloudsim.cloudlets.CloudletExecution;
import org.cloudbus.cloudsim.datacenters.Datacenter;
import org.cloudbus.cloudsim.hosts.Host;
import org.cloudbus.cloudsim.hosts.HostDynamic;
import org.cloudbus.cloudsim.hosts.HostSuitability;
import org.cloudbus.cloudsim.vms.Vm;
import vmtypes.DynamicVm;
import vmtypes.OnDemandInstance;
import vmtypes.SpotInstance;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * This class has been adapted from {@link VmAllocationPolicySimple}
 */
public class DynamicAllocationHLEM extends VmAllocationPolicyAbstract {

    /** @see #getLastHostIndex() */
    private int lastHostIndex;

    private final int threshold = 0;

    private final double resourceCarryingFactor = 0.95;

    /**
     * Instantiates the DynamicAllocation allocation policy
     */
    public DynamicAllocationHLEM() {
        super();
    }

    /**
     * Instantiates a VmAllocationPolicySimple, changing the {@link Function} to select a Host for a Vm
     * in order to define a different policy.
     *
     * @param findHostForVmFunction a {@link Function} to select a Host for a given Vm.
     * @see VmAllocationPolicy#setFindHostForVmFunction(BiFunction)
     */
    public DynamicAllocationHLEM(final BiFunction<VmAllocationPolicy, Vm, Optional<Host>> findHostForVmFunction) {
        super(findHostForVmFunction);
    }

    @Override
    protected Optional<Host> defaultFindHostForVm(final Vm vm) {
        final List<Host> hostList = getHostList();
        Map<Host, Map<String, Object>> suitableHosts = new HashMap<>();
        Map<Host, Map<String, Object>> suitableHostsSpot = new HashMap<>();

        List<String> resourceList = Arrays.asList("Pe", "Ram", "Storage", "Bw");

        Map<String, Map<String, Double>> resourceValues = new HashMap<>();
        resetResourceValues(resourceValues, resourceList);

        Map<String, Map<String, Double>> resourceValuesSpot = new HashMap<>();
        resetResourceValues(resourceValuesSpot, resourceList);

        /* The for loop just defines the maximum number of Hosts to try.
         * When a suitable Host is found, the method returns immediately. */
        final int maxTries = hostList.size();
        for (int i = 0; i < maxTries; i++) {
            final Host host = hostList.get(lastHostIndex);

            /* Step 1: Filter for suitable hosts based on Resource requirements */
            if (host.isSuitableForVm(vm)) {

                /* RsDiff = (Requested Cpu − Host Cpu Utilization) * resourceCarryingFactor */
                double rsDiff = (vm.getNumberOfPes() - host.getBusyPesNumber()) * resourceCarryingFactor;

                Map<String, Object> values = new HashMap<>();
                values.put("rsDiff", rsDiff);
                values.put("Ram", (double) host.getRam().getAvailableResource());
                values.put("Storage", (double) host.getAvailableStorage());
                values.put("Bw", (double) host.getBw().getAvailableResource());
                values.put("Pe", (double) host.getFreePesNumber());

                if (rsDiff > threshold) {
                    suitableHosts.put(host, values);

                    for (String key : resourceValues.keySet()) {
                        if ((double) values.get(key) < resourceValues.get(key).get("min")
                            || resourceValues.get(key).get("min") == 0.0) {
                            resourceValues.get(key).put("min", (double) values.get(key));
                        }
                        if ((double) values.get(key) > resourceValues.get(key).get("max")) {
                            resourceValues.get(key).put("max", (double) values.get(key));
                        }
                        resourceValues.get(key).put(
                            "sum", resourceValues.get(key).get("sum") + (double) values.get(key));
                    }
                }
            }
            else if (host instanceof HostDynamic && (vm instanceof OnDemandInstance || (vm instanceof SpotInstance && ((SpotInstance) vm).getPriority()))) {
                HostDynamic dynamicHost = (HostDynamic) host;
                if (vm.getStorage().getCapacity() <= dynamicHost.getSpotStorageCapacityUsage() &&
                    vm.getRam().getCapacity() <= dynamicHost.getSpotRamCapacityUsage() &&
                    vm.getBw().getCapacity() <= dynamicHost.getSpotBwCapacityUsage() &&
                    vm.getNumberOfPes() <= dynamicHost.getSpotPeCapacityUsage()) {


                    /* RsDiff = (Requested Cpu − Host Cpu Utilization) * resourceCarryingFactor */
                    double rsDiff = (vm.getNumberOfPes() - (dynamicHost.getBusyPesNumber()-dynamicHost.getSpotPeCapacityUsage())) * resourceCarryingFactor;

                    Map<String, Object> valuesSpot = new HashMap<>();
                    valuesSpot.put("rsDiff", rsDiff);
                    valuesSpot.put("Ram", (double) dynamicHost.getRam().getAvailableResource() + dynamicHost.getSpotRamCapacityUsage());
                    valuesSpot.put("Storage", (double) dynamicHost.getAvailableStorage() + dynamicHost.getSpotStorageCapacityUsage());
                    valuesSpot.put("Bw", (double) dynamicHost.getBw().getAvailableResource() + dynamicHost.getSpotBwCapacityUsage());
                    valuesSpot.put("Pe", (double) dynamicHost.getFreePesNumber());

                    suitableHostsSpot.put(dynamicHost, valuesSpot);
                }
            }

            /* If it gets here, the previous Host doesn't have capacity to place the VM.
             * Then, moves to the next Host.*/
            incLastHostIndex();
        }

        if(suitableHosts.size() > 1) {

            SortedMap<Double, Host> sortedHosts = hostEvaluation(resourceValues, suitableHosts);
            return Optional.of(sortedHosts.get(sortedHosts.firstKey()));

        } else if (suitableHosts.size() == 1) {

            return suitableHosts.keySet().stream().findFirst();

        } else if (suitableHostsSpot.size() > 1) {

            SortedMap<Double, Host> sortedHosts = hostEvaluation(resourceValuesSpot, suitableHostsSpot);
            freeCapacity(sortedHosts.get(sortedHosts.firstKey()), vm, getDatacenter());
            return Optional.of(sortedHosts.get(sortedHosts.firstKey()));

        }
        else if (suitableHostsSpot.size() == 1) {

            freeCapacity(suitableHostsSpot.keySet().stream().findFirst().get(), vm, getDatacenter());
            return suitableHostsSpot.keySet().stream().findFirst();

        }

        // return empty if not suitable host is found
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
     * on any of the hosts by calling {@link DynamicAllocationHLEM#spotAllocation(Vm, Datacenter)}.
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

    public SortedMap<Double, Host> hostEvaluation(Map<String, Map<String, Double>> resourceValues,
                               Map<Host, Map<String, Object>> suitableHosts) {

        SortedMap<Double, Host> sortedHosts = new TreeMap<>();

        resourceValues.get("Pe").put("proportionLogSum", 0.0);
        resourceValues.get("Ram").put("proportionLogSum", 0.0);
        resourceValues.get("Storage").put("proportionLogSum", 0.0);
        resourceValues.get("Bw").put("proportionLogSum", 0.0);

        for (Host host : suitableHosts.keySet()) {
            calculateCapacityVariables(host, resourceValues, suitableHosts);
        }

        /* Step 4 - 5 Entropy and factor of variation */
        for (String key : resourceValues.keySet()) {
            double logSum = resourceValues.get(key).get("proportionLogSum");
            double log = Math.log(suitableHosts.size());
            double entropy = -(1 / Math.log(suitableHosts.size())*resourceValues.get(key).get("proportionLogSum"));
            resourceValues.get(key).put("variation", 1 - entropy);
        }

        /* Step 6 Calculate the weights */
        double variationSum = resourceValues.keySet().stream()
            .map(k -> (Number) resourceValues.get(k).get("variation"))
            .mapToDouble(Number::doubleValue)
            .sum();

        for (String key : resourceValues.keySet()) {
            resourceValues.get(key).put("weight", resourceValues.get(key).get("variation") / variationSum);
        }

        /* Step 7 HostSelection */
        for (Host host : suitableHosts.keySet()) {
            double hostSelection = 0.0;
            for (String key : resourceValues.keySet()) {
                hostSelection += (resourceValues.get(key).get("weight") * (double) suitableHosts.get(host).get(key + "AvailableCapacity"));
            }
            suitableHosts.get(host).put("hostSelection", hostSelection);
            sortedHosts.put(hostSelection, host);
        }

        return sortedHosts;
    }

    public void calculateCapacityVariables(Host host, Map<String, Map<String, Double>> resourceValues,
                               Map<Host, Map<String, Object>> suitableHosts) {

        for (String key : resourceValues.keySet()) {

            double current = (double) suitableHosts.get(host).get(key);
            double min = resourceValues.get(key).get("min");
            double max = resourceValues.get(key).get("max");
            double sum = resourceValues.get(key).get("sum");

            double availableCapacity = ((current - min) /
                (max - min));

            suitableHosts.get(host).put(key + "AvailableCapacity",
                availableCapacity);

            double proportion = availableCapacity / sum;

            suitableHosts.get(host).put(key + "Proportions",
                proportion);

            double proportionLog = proportion * Math.log(proportion);
            double MathLog = Math.log(proportion); // For debugging

            // TODO: set to 0 if availableCapacity is 0 to prevent NaN
            if (availableCapacity == 0.0) {
                proportionLog = 0.0;
            }
            resourceValues.get(key).put("proportionLogSum",
                resourceValues.get(key).get("proportionLogSum") + (proportionLog));
        }

    }

    public void resetResourceValues(Map<String, Map<String, Double>> resourceValues, List<String> resourceList) {
        resourceValues.clear();

        for (String resource : resourceList) {
            Map<String, Double> resourceMap = new HashMap<>();
            resourceMap.put("min", 0.0);
            resourceMap.put("max", 0.0);
            resourceMap.put("sum", 0.0);

            resourceValues.put(resource, resourceMap);
        }
    }
}
