package example;

import allocation.*;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.apache.commons.math3.distribution.BetaDistribution;
import org.apache.commons.math3.distribution.ExponentialDistribution;
import org.cloudbus.cloudsim.allocationpolicies.VmAllocationPolicy;
import org.cloudbus.cloudsim.brokers.DatacenterBroker;
import org.cloudbus.cloudsim.cloudlets.Cloudlet;
import org.cloudbus.cloudsim.cloudlets.CloudletSimple;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.CloudSimTags;
import org.cloudbus.cloudsim.core.events.CloudSimEvent;
import org.cloudbus.cloudsim.datacenters.Datacenter;
import org.cloudbus.cloudsim.datacenters.DatacenterSimple;
import org.cloudbus.cloudsim.hosts.Host;
import org.cloudbus.cloudsim.hosts.HostDynamic;
import org.cloudbus.cloudsim.resources.Pe;
import org.cloudbus.cloudsim.resources.PeSimple;
import org.cloudbus.cloudsim.utilizationmodels.UtilizationModel;
import org.cloudbus.cloudsim.utilizationmodels.UtilizationModelFull;
import org.cloudbus.cloudsim.vms.Vm;
import org.cloudsimplus.listeners.EventInfo;
import org.cloudsimplus.listeners.VmHostEventInfo;
import tables.DynamicVmTableBuilder;
import tables.ExecutionTableBuilder;
import vmtypes.DynamicVm;
import vmtypes.ExecutionHistory;
import vmtypes.OnDemandInstance;
import vmtypes.SpotInstance;

import java.io.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * RestartingInterruptedSpot Example
 * Creates some {@link SpotInstance} that get interrupted by, {@link OnDemandInstance}. The spot instances will
 * automatically resume at a later time
 * Show the dynamic resubmission of {@link DynamicVm} amd the calculate of the
 * {@link SpotInstance#getAverageInterruptionTime()}
 *
 * This example shows how VMs that failed to be created or were hibernated can be resubmitted
 */
public class compareAlgorithm {

    // Vm and Cloudlet configuration
    private static final int SIMULATIONTIME = 25000;
    private final CloudSim simulation;
    private final DatacenterBrokerDynamic broker0;
    private final Datacenter datacenter0;
    private final double lastUpdate = 0;
    private final List<Cloudlet> cloudletList0 = new ArrayList<>();

    private final List<DynamicVm> vmList0 = new ArrayList<>();

    private final ArrayList<Map<String, Object>> spotListExport = new ArrayList<>();
    private final ArrayList<Map<String, Object>> onDemandExport = new ArrayList<>();

    private List<Host> hostList0;

    private final List<DatacenterBrokerDynamic> brokerList0 = new ArrayList<>();

    private final List<Map<String, Integer>> vmProfiles = new ArrayList<>();
    public static void main(String[] args) throws IOException {
        new compareAlgorithm();
    }

    private compareAlgorithm() throws IOException {

        createVmProfiles();
        createHostLists();

        ////////////////////////////////////////
        ////////////////////////////////////////

        simulation = new CloudSim(0.1);

        final DynamicAllocation allocationDynamic = new DynamicAllocation();
        datacenter0 = createDatacenter(simulation, hostList0, allocationDynamic);
        datacenter0.setSchedulingInterval(1);

        //Creates a broker that is a software acting on behalf a cloud customer to manage his/her VMs and Cloudlets
        broker0 = new DatacenterBrokerDynamic(simulation);
        brokerList0.add(broker0);

        createVms();
        CreateCloudlet();

        broker0.setShutdownWhenIdle(false);
        broker0.setVmDestructionDelay(10);


        ////////////////////////////////////////
        ////////////////////////////////////////


        simulation.addOnClockTickListener(this::updateProcessingforVms0);
        simulation.addOnSimulationStartListener(this::updateProcessingEvents);
        // simulation.start();

        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream("spotInstances.dat"))) {
            oos.writeObject(spotListExport);
        }

        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream("onDemandInstances.dat"))) {
            oos.writeObject(onDemandExport);
        }

        createOutput(brokerList0, vmList0, broker0);

    }

    /**
     * Clocktick Listener
     * Update the processing for all executed virtual machine instances to get the correct running time
     */
    private void updateProcessingforVms0(EventInfo eventInfo) {
        // manually update processing because it doesn't work if vms are only resumed
        for (DatacenterBroker broker : brokerList0) {
            for (Vm vm : new ArrayList<Vm>(broker.getVmExecList())) {
                vm.updateProcessing(simulation.clock(), vm.getHost().getVmScheduler().getAllocatedMips(vm));
                datacenter0.schedule(1, CloudSimTags.VM_UPDATE_CLOUDLET_PROCESSING);
            }
        }
    }

    /**
     * Deallocation Listener
     * When other virtual machines get deallocated, the broker will try to resubmit VMS that are interrupted
     * or failed to be created
     *
     * @param vmHostEventInfo deallocation event listener information
     */
    private void onHostDeallocationListener0(VmHostEventInfo vmHostEventInfo) {
        if (simulation.clock() > 10) {
            ((DatacenterBrokerDynamic) broker0).resubmitVms();
        }
    }

    /**
     * Creates a datacenter with the specified and initiates host creation and
     * the allocation policy gets assigned to the Datacenter
     *
     * @return a {@link Datacenter} instance
     */
    private Datacenter createDatacenter(CloudSim simulation, List<Host> hostList, VmAllocationPolicy allocationPolicy) {

        // Assign the DynamicAllocation policy to enable the behavior of spot instances
        return new DatacenterSimple(simulation, hostList, allocationPolicy);
    }

    private void createHostLists() {

        int smallHosts = ThreadLocalRandom.current().nextInt(20, 51);
        int mediumHosts = ThreadLocalRandom.current().nextInt(20, 51);
        int largeHosts = ThreadLocalRandom.current().nextInt(15, 41);
        int xlHosts = ThreadLocalRandom.current().nextInt(10, 31);

        hostList0 = new ArrayList<>(smallHosts + mediumHosts + largeHosts + xlHosts);

        for (int i = 0; i < smallHosts; i++) {
            Host host = createHost(8, 16384, 5000, 200000);
            hostList0.add(host);
        }
        for (int i = 0; i < mediumHosts; i++) {
            Host host = createHost(16, 32768, 10000, 400000);
            hostList0.add(host);
        }
        for (int i = 0; i < largeHosts; i++) {
            Host host = createHost(32, 65536, 20000, 800000);
            hostList0.add(host);
        }
        for (int i = 0; i < xlHosts; i++) {
            Host host = createHost(64, 65536 * 2, 20000 * 2, 800000 * 2);
            hostList0.add(host);
        }
    }

    /**
     * Creates a host with the specified parameters
     *
     * @return a {@link Host} instance
     */
    private Host createHost(int pes, int ram, int bw, int storage) {
        final List<Pe> peList = new ArrayList<>(pes);
        //List of Host's CPUs (Processing Elements, PEs)
        for (int i = 0; i < pes; i++) {
            peList.add(new PeSimple(2000));
        }
        return new HostDynamic(ram, bw, storage, peList);
    }

    private List<DynamicVm> createVms() {

        int totalVms = 900; // ThreadLocalRandom.current().nextInt(2000, 2500);

        final List<DynamicVm> list = new ArrayList<>(totalVms);

        double initialSpot = totalVms * 0.3 * 0.7;
        double initialOnDemand= totalVms * 0.3 * 0.3;

        for (int i = 0; i < initialSpot; i++) {
            createSpotVm(false, 0);
        }
        for (int i = 0; i < initialOnDemand; i++) {
            createOnDemandVm(false, 0);
        }

        double delayedSpot = totalVms * 0.75 * 0.3;
        double delayedOnDemand = totalVms * 0.75 * 0.7;

        for (int i = 0; i < delayedSpot; i++) {
            createSpotVm(true, 0);
        }
        for (int i = 0; i < delayedOnDemand; i++) {
            createOnDemandVm(true, 0);
        }

        double endSpot = totalVms * 0.2 * 0.3;
        double endOnDemand = totalVms * 0.2 * 0.7;

        for (int i = 0; i < endSpot; i++) {
            createSpotVm(true, 500);
        }
        for (int i = 0; i < endOnDemand; i++) {
            createOnDemandVm(true, 500);
        }

        return list;
    }

    /**
     * Creates a list of {@link SpotInstance}. Persistent requests are activated
     */
    private void createSpotVm(boolean delay, double addedDelay) {

        double delayTime = 0;

        if (delay) {
            BetaDistribution beta = new BetaDistribution(2.0, 5.0); // skewed toward lower end
            double normalized = beta.sample();  // between 0 and 1
            delayTime = 1 + (1000 - 1) * normalized;
        }

        double delaySaved = delayTime + addedDelay;
        int chosenProfile = ThreadLocalRandom.current().nextInt(0, vmProfiles.size());
        int mips = ThreadLocalRandom.current().nextInt(500000, 1000000);

        Map<String, Object> savedVM = new HashMap<>();
        savedVM.put("delay", delaySaved);
        savedVM.put("profile", chosenProfile);
        savedVM.put("mips", mips);

        spotListExport.add(savedVM);

        Map<String, Integer> profile = vmProfiles.get(chosenProfile);

        final SpotInstance vm = new SpotInstance(1000, profile.get("pes"), true);
        vm.setRam(profile.get("ram")).setBw(profile.get("bw")).setSize(profile.get("storage"));
        vm.addOnHostDeallocationListener(this::onHostDeallocationListener0);
        vm.setSubmissionDelay(delayTime + addedDelay);
        vm.setInterruptionBehavior(SpotInstance.InterruptionBehavior.HIBERNATE);
        vm.setPersistentRequest(true);
        vm.setHibernationTimeLimit(20000);
        vm.setWaitingTime(20000);
        vm.setMinimumRunningTime(0);

        vmList0.add(vm);
    }

    private void createOnDemandVm(boolean delay, double addedDelay) {

        double delayTime = 0;

        if (delay) {
            double lambda = 1.0 / 1000.0;
            ExponentialDistribution interArrivalDist = new ExponentialDistribution(1 / lambda);
            delayTime = interArrivalDist.sample();
        }

        double delaySaved = delayTime + addedDelay;
        int chosenProfile = ThreadLocalRandom.current().nextInt(0, vmProfiles.size());
        int mips = ThreadLocalRandom.current().nextInt(500000, 1000000);

        Map<String, Object> savedVM = new HashMap<>();
        savedVM.put("delay", delaySaved);
        savedVM.put("profile", chosenProfile);
        savedVM.put("mips", mips);

        onDemandExport.add(savedVM);

        Map<String, Integer> profile = vmProfiles.get(chosenProfile);

        final OnDemandInstance vm = new OnDemandInstance(1000, profile.get("pes"), true);
        vm.setRam(profile.get("ram")).setBw(profile.get("bw")).setSize(profile.get("storage"));
        vm.addOnHostDeallocationListener(this::onHostDeallocationListener0);
        vm.setSubmissionDelay(delayTime + addedDelay);
        vm.setPersistentRequest(true);
        vm.setWaitingTime(20000);

        vmList0.add(vm);
    }

    /**
     * Submits the previously created vmList and submits it to the broker
     * calls createAndSubmitCloudlets to initiate cloudlet creation
     *
     */
    private void submitVMandCreateCloudlet() {
        for (DynamicVm dynamicVm : vmList0) {
            int mips = ThreadLocalRandom.current().nextInt(500000, 1000000);

            // VMLIST 0
            broker0.submitVm(dynamicVm);
            createAndSubmitCloudlets(broker0, dynamicVm, mips, cloudletList0);
        }
    }

    private void CreateCloudlet() {
        for (DynamicVm dynamicVm : vmList0) {
            int mips = ThreadLocalRandom.current().nextInt(500000, 1000000);

            // VMLIST 0
            //broker0.submitVm(dynamicVm);
            createAndSubmitCloudlets(broker0, dynamicVm, mips, cloudletList0);
        }
    }

    /**
     * Creates a {@link Cloudlet} and submits it to the broker
     *
     * @param broker {@link DatacenterBroker} that is used to submit the cloudlets
     * @param vm     {@link DynamicVm} for which the cloudlet will be created
     */
    private void createAndSubmitCloudlets(DatacenterBrokerDynamic broker, Vm vm, int mips, List<Cloudlet> cloudletList) {

        int cloudlets = ThreadLocalRandom.current().nextInt(1, 4);
        LinkedHashSet<Cloudlet> cloudletSubmit = new LinkedHashSet<>();

        for (int i = 0; i < 1; i++) {

            int cloudletId = cloudletList0.size();
            UtilizationModel utilizationModel = new UtilizationModelFull();

            Cloudlet cloudlet = new CloudletSimple(cloudletId, mips, vm.getNumberOfPes())
                .setFileSize(300).setOutputSize(300).setUtilizationModel(utilizationModel)
                .setVm(vm);

            cloudletSubmit.add(cloudlet);
            cloudletList0.add(cloudlet);
        }
        broker0.getVmCloudletHashMap().put(vm, cloudletSubmit);
    }

    private void createVmProfiles() {

        vmProfiles.add(Map.of(
            "pes", 1,
            "ram", 1024,
            "bw", 100,
            "storage", 10000
        ));

        vmProfiles.add(Map.of(
            "pes", 2,
            "ram", 1024,
            "bw", 100,
            "storage", 10000
        ));

        vmProfiles.add(Map.of(
            "pes", 1,
            "ram", 2048,
            "bw", 200,
            "storage", 20000
        ));

        vmProfiles.add(Map.of(
            "pes", 2,
            "ram", 2048,
            "bw", 200,
            "storage", 20000
        ));

        vmProfiles.add(Map.of(
            "pes", 4,
            "ram", 2048,
            "bw", 200,
            "storage", 20000
        ));

        vmProfiles.add(Map.of(
            "pes", 4,
            "ram", 4096,
            "bw", 500,
            "storage", 50000
        ));

        vmProfiles.add(Map.of(
            "pes", 6,
            "ram", 4096,
            "bw", 500,
            "storage", 50000
        ));

        vmProfiles.add(Map.of(
            "pes", 6,
            "ram", 8192,
            "bw", 1000,
            "storage", 80000
        ));

        vmProfiles.add(Map.of(
            "pes", 8,
            "ram", 8192,
            "bw", 1000,
            "storage", 80000
        ));

        vmProfiles.add(Map.of(
            "pes", 10,
            "ram", 8192,
            "bw", 1000,
            "storage", 80000
        ));
    }

    private void updateProcessingEvents(EventInfo eventInfo) {
        int i;
        for(i = 0; i < SIMULATIONTIME; i++) {
            if (i % 5 == 0) {
                CloudSimEvent evt = new CloudSimEvent((double) i, datacenter0, CloudSimTags.VM_UPDATE_CLOUDLET_PROCESSING);
                simulation.send(evt);
            }
        }
    }

    private void createOutput(List<DatacenterBrokerDynamic> brokerList, List<DynamicVm> vmList, DatacenterBrokerDynamic brokerOutput) throws IOException {
        /// Create OUTPUT
        List<DynamicVm> finishedVms = new ArrayList<>();

        for (DatacenterBroker broker : brokerList) {
            finishedVms.addAll(broker.getVmCreatedList());
        }
        Set<DynamicVm> VmSet = new HashSet<>(finishedVms);
        finishedVms.clear();
        finishedVms.addAll(VmSet);

        new DynamicVmTableBuilder(finishedVms).build();

        List<SpotInstance> finishedSpot = new ArrayList<>();
        for (DynamicVm vm : finishedVms) {
            if (vm instanceof SpotInstance) {
                ((SpotInstance) vm).calculateAverageInterruptionTime();
                finishedSpot.add((SpotInstance) vm);
            }
        }

        // new SpotVmTableBuilder(finishedSpot).build();
//        new SpotVmTableBuilder(finishedSpot).save("test.csv");

        Gson gson = new Gson();
        Map<Long, Object> executionHistoryJSON = new HashMap<>();

        for (Vm vm : vmList) {
            if (vm instanceof SpotInstance) {
                ((SpotInstance) vm).calculateAverageInterruptionTime();
                finishedSpot.add((SpotInstance) vm);

                if (((SpotInstance) vm).getExecutionHistory().size() > 1) {



                    ArrayList<Map<String, Object>> historyList = new ArrayList<>();

                    for (ExecutionHistory history : ((SpotInstance) vm).getExecutionHistory()) {

                        HashMap<String, Object> entry = new HashMap<>();
                        entry.put("Host", history.getHost().getId());
                        entry.put("StartTime", history.getStartTime());
                        entry.put("StopTime", history.getStopTime());

                        historyList.add(entry);

                    }

                    executionHistoryJSON.put(vm.getId(), historyList);

                }
            }
        }

        new ExecutionTableBuilder(finishedSpot.get(0).getExecutionHistory()).createJSON(finishedSpot, finishedSpot.get(0).getBroker());
        System.out.println(gson.toJson(executionHistoryJSON));
        System.out.println(brokerOutput.getResubmittingList());
    }
}
