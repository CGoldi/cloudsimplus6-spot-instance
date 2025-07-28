package allocation;

import org.cloudbus.cloudsim.allocationpolicies.VmAllocationPolicy;
import org.cloudbus.cloudsim.allocationpolicies.VmAllocationPolicySimple;
import org.cloudbus.cloudsim.brokers.DatacenterBroker;
import org.cloudbus.cloudsim.cloudlets.Cloudlet;
import org.cloudbus.cloudsim.core.AbstractMachine;
import org.cloudbus.cloudsim.datacenters.Datacenter;
import org.cloudbus.cloudsim.hosts.Host;
import org.cloudbus.cloudsim.hosts.HostDynamic;
import org.cloudbus.cloudsim.hosts.HostSuitability;
import org.cloudbus.cloudsim.vms.Vm;
import org.cloudbus.cloudsim.vms.VmSimple;
import vmtypes.DynamicVm;
import vmtypes.OnDemandInstance;
import vmtypes.SpotInstance;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * This class has been adapted from {@link VmAllocationPolicySimple}
 */
public class DynamicAllocationHLEMAdjusted extends DynamicAllocation {

    /** @see #getLastHostIndex() */
    private int lastHostIndex;

    private final int threshold = 0;

    private final double resourceCarryingFactor = 0.95;

    private final double spotImpact = 1.2;

    /**
     * Instantiates the DynamicAllocation allocation policy
     */
    public DynamicAllocationHLEMAdjusted() {
        super();
    }

    /**
     * Instantiates a VmAllocationPolicySimple, changing the {@link Function} to select a Host for a Vm
     * in order to define a different policy.
     *
     * @param findHostForVmFunction a {@link Function} to select a Host for a given Vm.
     * @see VmAllocationPolicy#setFindHostForVmFunction(BiFunction)
     */
    public DynamicAllocationHLEMAdjusted(final BiFunction<VmAllocationPolicy, Vm, Optional<Host>> findHostForVmFunction) {
        super(findHostForVmFunction);
    }

    @Override
    protected Optional<Host> defaultFindHostForVm(final Vm vm) {
        final List<Host> hostList = getHostList();
        Map<Host, Map<String, Object>> suitableHosts = new HashMap<>();
        Map<Host, Map<String, Object>> suitableHostsNoRsDiff = new HashMap<>();
        Map<Host, Map<String, Object>> suitableHostsSpot = new HashMap<>();

        List<String> resourceList = Arrays.asList("Pe", "Ram", "Storage", "Bw");

        Map<String, Map<String, Double>> resourceValues = new HashMap<>();
        resetResourceValues(resourceValues, resourceList);

        Map<String, Map<String, Double>> resourceValuesNoRsDiff = new HashMap<>();
        resetResourceValues(resourceValuesNoRsDiff, resourceList);

        Map<String, Map<String, Double>> resourceValuesSpot = new HashMap<>();
        resetResourceValues(resourceValuesSpot, resourceList);

        for (final Host host : hostList) {
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
                        resourceValues.get(key).put("count", resourceValues.get(key).get("count") + 1);
                    }
                } else {
                    /* RsDiff = (Requested Cpu − Host Cpu Utilization) * resourceCarryingFactor */
                    suitableHostsNoRsDiff.put(host, values);

                    for (String key : resourceValuesNoRsDiff.keySet()) {
                        if ((double) values.get(key) < resourceValuesNoRsDiff.get(key).get("min")
                            || resourceValuesNoRsDiff.get(key).get("min") == 0.0) {
                            resourceValuesNoRsDiff.get(key).put("min", (double) values.get(key));
                        }
                        if ((double) values.get(key) > resourceValuesNoRsDiff.get(key).get("max")) {
                            resourceValuesNoRsDiff.get(key).put("max", (double) values.get(key));
                        }
                        resourceValuesNoRsDiff.get(key).put(
                            "sum", resourceValuesNoRsDiff.get(key).get("sum") + (double) values.get(key));
                        resourceValuesNoRsDiff.get(key).put("count", resourceValuesNoRsDiff.get(key).get("count") + 1);
                    }
                }
            } else if (host instanceof HostDynamic && (vm instanceof OnDemandInstance || (vm instanceof SpotInstance && ((SpotInstance) vm).getPriority()))) {
                HostDynamic dynamicHost = (HostDynamic) host;
                if (vm.getStorage().getCapacity() <= dynamicHost.getSpotStorageCapacityUsage() &&
                    vm.getRam().getCapacity() <= dynamicHost.getSpotRamCapacityUsage() &&
                    vm.getBw().getCapacity() <= dynamicHost.getSpotBwCapacityUsage() &&
                    vm.getNumberOfPes() <= dynamicHost.getSpotPeCapacityUsage()) {


                    /* RsDiff = (Requested Cpu − Host Cpu Utilization) * resourceCarryingFactor */
                    double rsDiff = (vm.getNumberOfPes() - (dynamicHost.getBusyPesNumber() - dynamicHost.getSpotPeCapacityUsage())) * resourceCarryingFactor;

                    Map<String, Object> valuesSpot = new HashMap<>();
                    valuesSpot.put("rsDiff", rsDiff);
                    valuesSpot.put("Ram", (double) dynamicHost.getRam().getAvailableResource() + dynamicHost.getSpotRamCapacityUsage());
                    valuesSpot.put("Storage", (double) dynamicHost.getAvailableStorage() + dynamicHost.getSpotStorageCapacityUsage());
                    valuesSpot.put("Bw", (double) dynamicHost.getBw().getAvailableResource() + dynamicHost.getSpotBwCapacityUsage());
                    valuesSpot.put("Pe", (double) dynamicHost.getFreePesNumber() + dynamicHost.getSpotPeCapacityUsage());

                    suitableHostsSpot.put(dynamicHost, valuesSpot);

                    for (String key : resourceValuesSpot.keySet()) {
                        if ((double) valuesSpot.get(key) < resourceValuesSpot.get(key).get("min")
                            || resourceValuesSpot.get(key).get("min") == 0.0) {
                            resourceValuesSpot.get(key).put("min", (double) valuesSpot.get(key));
                        }
                        if ((double) valuesSpot.get(key) > resourceValuesSpot.get(key).get("max")) {
                            resourceValuesSpot.get(key).put("max", (double) valuesSpot.get(key));
                        }
                        resourceValuesSpot.get(key).put(
                            "sum", resourceValuesSpot.get(key).get("sum") + (double) valuesSpot.get(key));
                        resourceValuesSpot.get(key).put("count", resourceValuesSpot.get(key).get("count") + 1);
                    }
                }
            }
        }


        ////////////////////////////////////////////////////
        ////////////////////////////////////////////////////

        if(suitableHosts.size() > 1) {

            SortedMap<Double, Host> sortedHosts = hostEvaluation(resourceValues, suitableHosts, (DynamicVm) vm);
            return Optional.of(sortedHosts.get(sortedHosts.firstKey()));

        } else if (suitableHosts.size() == 1) {

            return suitableHosts.keySet().stream().findFirst();


        } else if(suitableHostsNoRsDiff.size() > 1) {

            SortedMap<Double, Host> sortedHosts = hostEvaluation(resourceValuesNoRsDiff, suitableHostsNoRsDiff, (DynamicVm) vm);

            return Optional.of(sortedHosts.get(sortedHosts.firstKey()));

        } else if (suitableHostsNoRsDiff.size() == 1) {

            return suitableHostsNoRsDiff.keySet().stream().findFirst();

        }
        else if (suitableHostsSpot.size() > 1) {

            SortedMap<Double, Host> sortedHosts = hostEvaluation(resourceValuesSpot, suitableHostsSpot, (DynamicVm) vm);
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

    public SortedMap<Double, Host> hostEvaluation(Map<String, Map<String, Double>> resourceValues,
                                                  Map<Host, Map<String, Object>> suitableHosts, DynamicVm vm) {

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
            System.out.println(key + ": " + resourceValues.get(key).get("weight"));
        }

        /* Step 7 HostSelection */
        for (Host host : suitableHosts.keySet()) {
            double hostSelection = 0.0;
            double spotLoad = 0.0;
            for (String key : resourceValues.keySet()) {
                hostSelection += (resourceValues.get(key).get("weight") * (double) suitableHosts.get(host).get(key + "AvailableCapacity"));
                spotLoad += (resourceValues.get(key).get("weight") * resourceValues.get(key).get("spotLoad"));
            }
            if (Double.isNaN(hostSelection)) {
                System.out.println(hostSelection);
                System.out.println("check Nan");
            }
            if (vm instanceof SpotInstance) {
                suitableHosts.get(host).put("hostSelection", hostSelection * (1 + spotImpact * spotLoad));
            } else {
                suitableHosts.get(host).put("hostSelection", hostSelection);
            }
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

            if (availableCapacity == 0.0 || Double.isNaN(availableCapacity)) {
                availableCapacity = 0.5;
            }

            suitableHosts.get(host).put(key + "AvailableCapacity",
                availableCapacity);

            double proportion = availableCapacity / sum;

            suitableHosts.get(host).put(key + "Proportions",
                proportion);

            double proportionLog = proportion * Math.log(proportion);
            double MathLog = Math.log(proportion); // For debugging

            HostDynamic dynamicHost = (HostDynamic) host;

            switch (key) {
                case "Pe":
                    if (dynamicHost.getSpotPeCapacityUsage() > 0) {
                        resourceValues.get(key).put("spotLoad", (double) dynamicHost.getSpotPeCapacityUsage() / (host.getBusyPesNumber() + host.getFreePesNumber()));
                    } else {
                        resourceValues.get(key).put("spotLoad", 0.0);
                    }
                    break;
                case "Ram":
                    if (dynamicHost.getSpotRamCapacityUsage() > 0) {
                        resourceValues.get(key).put("spotLoad", (double) dynamicHost.getSpotRamCapacityUsage() / host.getRam().getCapacity());
                    } else {
                        resourceValues.get(key).put("spotLoad", 0.0);
                    }
                    break;
                case "Storage":
                    if (dynamicHost.getSpotStorageCapacityUsage() > 0) {
                        resourceValues.get(key).put("spotLoad", (double) dynamicHost.getSpotStorageCapacityUsage() /  host.getStorage().getCapacity());
                    } else {
                        resourceValues.get(key).put("spotLoad", 0.0);
                    }
                    break;
                case "Bw":
                    if (dynamicHost.getSpotBwCapacityUsage() > 0) {
                        resourceValues.get(key).put("spotLoad", (double) dynamicHost.getSpotBwCapacityUsage() / host.getBw().getCapacity());
                    } else {
                        resourceValues.get(key).put("spotLoad", 0.0);
                    }
                    break;
            }

            // set to 0 if availableCapacity is 0 to prevent NaN
            if (Double.isNaN(proportionLog)) {
                System.out.println(proportion);
                System.out.println(Math.log(proportion));
                System.out.println(proportionLog);
                System.out.println("NaN prop");
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
            resourceMap.put("count", 0.0);

            resourceValues.put(resource, resourceMap);
        }
    }

    @Override
    public void freeCapacity(Host host, Vm vm, Datacenter datacenter){
        // Spot instances get removed until the host is suitable for the
        // vm or if no more spot instances are available
        int i = 0;

        List<DynamicVm> sortedVMs = new ArrayList<>(host.getVmList());
        sortedVMs.sort(Comparator.comparingLong(AbstractMachine::getNumberOfPes).reversed());

        while (!host.isSuitableForVm(vm) && i < sortedVMs.size()) {
            if(i==0) {
                LOGGER.warn("Checking for Spot Destruction");
            }

            boolean priority = false;

            if (vm instanceof SpotInstance) {
                priority = ((SpotInstance) vm).getPriority();
            }

            Vm VmToDestroy = sortedVMs.get(i);
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
/*
        if (vm.getHost() != null && vm.getBroker() instanceof DatacenterBrokerDynamic) {
            final Host newHost = spotAllocationSpecificHost(vm, getDatacenter(), vm.getHost());
            if (newHost != null) {
                return allocateHostForVm(vm, newHost);
            }
        }
*/
        final Optional<Host> optional = defaultFindHostForVm(vm);
        if (optional.isPresent()) {
            return allocateHostForVm(vm, optional.get());
        }

        LOGGER.warn("{}: {}: Checking for Spot {} in {}", vm.getSimulation().clockStr(), getClass().getSimpleName(), vm, getDatacenter());

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
                ((DatacenterBrokerDynamic) vm.getBroker()).getResubmittingList().sort(Comparator.comparingDouble(VmSimple::getStartTime));

                for (Cloudlet cloudlet : vm.getBroker().getCloudletWaitingList()) {
                    if (cloudlet.getVm() == vm) {
                        ((DynamicVm) vm).getFailedCloudlets().add(cloudlet);
                    }
                }
            }
        }
        return new HostSuitability("No suitable Host found.");
    }
}
