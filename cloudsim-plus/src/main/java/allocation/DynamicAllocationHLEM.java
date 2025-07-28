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
public class DynamicAllocationHLEM extends DynamicAllocation {

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
        Map<Host, Map<String, Object>> suitableHostsNoRsDiff = new HashMap<>();
        Map<Host, Map<String, Object>> suitableHostsSpot = new HashMap<>();

        List<String> resourceList = Arrays.asList("Pe", "Ram", "Storage", "Bw");

        Map<String, Map<String, Double>> resourceValues = new HashMap<>();
        resetResourceValues(resourceValues, resourceList);

        Map<String, Map<String, Double>> resourceValuesNoRsDiff = new HashMap<>();
        resetResourceValues(resourceValuesNoRsDiff, resourceList);

        Map<String, Map<String, Double>> resourceValuesSpot = new HashMap<>();
        resetResourceValues(resourceValuesSpot, resourceList);

        boolean rsdiffnot = false;

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

            LOGGER.info("Suitable hosts found {}", suitableHosts.size());

            SortedMap<Double, Host> sortedHosts = hostEvaluation(resourceValues, suitableHosts);
            return Optional.of(sortedHosts.get(sortedHosts.firstKey()));


        } else if (suitableHosts.size() == 1) {

            LOGGER.info("Suitable hosts found {}", suitableHosts.size());

            return suitableHosts.keySet().stream().findFirst();


        } else if(suitableHostsNoRsDiff.size() > 1) {

            LOGGER.info("NoRsDiff hosts found {}", suitableHostsNoRsDiff.size());

            SortedMap<Double, Host> sortedHosts = hostEvaluation(resourceValuesNoRsDiff, suitableHostsNoRsDiff);

            return Optional.of(sortedHosts.get(sortedHosts.firstKey()));

        } else if (suitableHostsNoRsDiff.size() == 1) {

            LOGGER.info("NoRsDiff hosts found {}", suitableHostsNoRsDiff.size());

            return suitableHostsNoRsDiff.keySet().stream().findFirst();

        }
        else if (suitableHostsSpot.size() > 1) {

            LOGGER.info("HostsSpot found {}", suitableHostsSpot.size());

            SortedMap<Double, Host> sortedHosts = hostEvaluation(resourceValuesSpot, suitableHostsSpot);
            freeCapacity(sortedHosts.get(sortedHosts.firstKey()), vm, getDatacenter());

            return Optional.of(sortedHosts.get(sortedHosts.firstKey()));

        }
        else if (suitableHostsSpot.size() == 1) {

            LOGGER.info("HostsSpot found {}", suitableHostsSpot.size());

            freeCapacity(suitableHostsSpot.keySet().stream().findFirst().get(), vm, getDatacenter());
            return suitableHostsSpot.keySet().stream().findFirst();
        }

        // return empty if not suitable host is found
        return Optional.empty();
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
            if (Double.isNaN(hostSelection)) {
                System.out.println(hostSelection);
                System.out.println("check Nan");
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
}
