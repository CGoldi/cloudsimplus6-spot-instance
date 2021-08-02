package example;

import allocation.DatacenterBrokerDynamic;
import allocation.DynamicAllocation;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.cloudbus.cloudsim.brokers.DatacenterBroker;
import org.cloudbus.cloudsim.cloudlets.Cloudlet;
import org.cloudbus.cloudsim.cloudlets.CloudletSimple;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.datacenters.Datacenter;
import org.cloudbus.cloudsim.datacenters.DatacenterSimple;
import org.cloudbus.cloudsim.hosts.Host;
import org.cloudbus.cloudsim.hosts.HostSimple;
import org.cloudbus.cloudsim.resources.Pe;
import org.cloudbus.cloudsim.resources.PeSimple;
import org.cloudbus.cloudsim.utilizationmodels.UtilizationModel;
import org.cloudbus.cloudsim.utilizationmodels.UtilizationModelFull;
import org.cloudbus.cloudsim.vms.Vm;
import org.cloudsimplus.listeners.EventInfo;
import org.cloudsimplus.listeners.VmHostEventInfo;
import tables.DynamicVmTableBuilder;
import tables.ExecutionTableBuilder;
import tables.SpotVmTableBuilder;
import vmtypes.DynamicVm;
import vmtypes.ExecutionHistory;
import vmtypes.OnDemandInstance;
import vmtypes.SpotInstance;

import java.io.IOException;
import java.util.*;

/**
 * RestartingInterruptedSpot Example
 * Creates some {@link SpotInstance} that get interrupted by, {@link OnDemandInstance}. The spot instances will
 * automatically resume at a later time
 * Show the dynamic resubmission of {@link DynamicVm} amd the calculate of the
 * {@link SpotInstance#getAverageInterruptionTime()}
 *
 * This example shows how VMs that failed to be created or were hibernated can be resubmitted
 */
public class RestartingInterruptedSpot {

    // Host configuration
    private static final int HOSTS = 2;
    private static final int HOST_PES = 8;
    private static final int HOST_RAM = 2048;  //in Megabytes
    private static final int HOST_BW = 10000;  //in Megabit/s
    private static final int HOST_STORAGE = 1000000;  //in Megabytes

    // Vm and Cloudlet configuration
    private static final int VMS = 5;  // number of on demand vms created
    private static final int VMS_SPOT = 3;  // number of spot vms created
    private static final int VM_PES = 4;
    private static final int CLOUDLET_LENGTH = 20000;

    private final CloudSim simulation;
    private final DatacenterBrokerDynamic broker0;
    private final List<Cloudlet> cloudletList = new ArrayList<>();
    private final List<DatacenterBroker> brokerList = new ArrayList<>();


    public static void main(String[] args) throws IOException {
        new RestartingInterruptedSpot();
    }

    private RestartingInterruptedSpot() throws IOException {

        simulation = new CloudSim(0.5);
        simulation.terminateAt(70);
        Datacenter datacenter0 = createDatacenter();
        datacenter0.setSchedulingInterval(1);

        //Creates a broker that is a software acting on behalf a cloud customer to manage his/her VMs and Cloudlets
        broker0 = new DatacenterBrokerDynamic(simulation);
        brokerList.add(broker0);

        broker0.setShutdownWhenIdle(false);
        broker0.setVmDestructionDelay(1);

        //Initial Vms and cloudlet creation
        List<DynamicVm> vmList = createSpotVms();
        vmList.addAll(createOnDemand());
        submitVMandCreateCloudlet(vmList);

        simulation.addOnClockTickListener(this::updateProcessingforVms);

        simulation.start();

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

        new SpotVmTableBuilder(finishedSpot).build();
//        new SpotVmTableBuilder(finishedSpot).save("test.csv");


        Gson gson = new Gson();
        Map<Long, Object> executionHistoryJSON = new HashMap<>();

        for (Vm vm : broker0.getVmCreatedList()) {
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

    }

    /**
     * Clocktick Listener
     * Update the processing for all executed virtual machine instances to get the correct running time
     */
    private void updateProcessingforVms(EventInfo eventInfo) {
        // manually update processing because it doesn't work if vms are only resumed
        for (DatacenterBroker broker : brokerList) {
            for (Vm vm : broker.getVmExecList()) {
                vm.updateProcessing(simulation.clock(), vm.getHost().getVmScheduler().getAllocatedMips(vm));
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
    private void onHostDeallocationListener(VmHostEventInfo vmHostEventInfo) {
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
    private Datacenter createDatacenter() {
        final List<Host> hostList = new ArrayList<>(HOSTS);
        for (int i = 0; i < HOSTS; i++) {
            Host host = createHost();
            hostList.add(host);
        }

        final DynamicAllocation allocationPolicy = new DynamicAllocation();

        //Uses a VmAllocationPolicySimple by default to allocate VMs
        return new DatacenterSimple(simulation, hostList, allocationPolicy);
    }

    /**
     * Creates a host with the specified parameters
     *
     * @return a {@link Host} instance
     */
    private Host createHost() {
        final List<Pe> peList = new ArrayList<>(HOST_PES);
        //List of Host's CPUs (Processing Elements, PEs)
        for (int i = 0; i < HOST_PES; i++) {
            peList.add(new PeSimple(1000));
        }

        return new HostSimple(HOST_RAM, HOST_BW, HOST_STORAGE, peList);
    }

    /**
     * Creates a list of {@link SpotInstance}. Persistent requests are activated
     */
    private List<DynamicVm> createSpotVms() {
        final List<DynamicVm> list = new ArrayList<>(VMS_SPOT);
        for (int i = 0; i < VMS_SPOT; i++) {

            final SpotInstance vm = new SpotInstance(1000, VM_PES, true);
            vm.setRam(512).setBw(1000).setSize(10000).addOnHostDeallocationListener(this::onHostDeallocationListener);
            vm.setInterruptionBehavior(SpotInstance.InterruptionBehavior.HIBERNATE);
            vm.setPersistentRequest(true);
            vm.setHibernationTimeLimit(300);
            vm.setWaitingTime(300);
            vm.setMinimumRunningTime(0);
            list.add(vm);
        }

        return list;
    }

    /**
     * Creates a list of {@link OnDemandInstance}. Persistent requests are activated
     */
    private List<DynamicVm> createOnDemand() {
        final List<DynamicVm> list = new ArrayList<>(VMS);
        for (int i = 0; i < VMS; i++) {

            final OnDemandInstance vm = new OnDemandInstance(1000, VM_PES, true);
            vm.setRam(512).setBw(1000).setSize(10000).addOnHostDeallocationListener(this::onHostDeallocationListener)
                    .setSubmissionDelay(10);
            vm.setPersistentRequest(true);
            vm.setWaitingTime(40);
            list.add(vm);
        }

        return list;
    }

    /**
     * Submits the previously created vmList and submits it to the broker
     * calls createAndSubmitCloudlets to initiate cloudlet creation
     *
     * @param vmList a List of Dynamic Vms that have been created
     */
    private void submitVMandCreateCloudlet(List<DynamicVm> vmList) {
        for (Vm vm : vmList) {
            broker0.submitVm(vm);
            createAndSubmitCloudlets(broker0, vm);
        }
    }

    /**
     * Creates a {@link Cloudlet} and submits it to the broker
     *
     * @param broker {@link DatacenterBroker} that is used to submit the cloudlets
     * @param vm     {@link DynamicVm} for which the cloudlet will be created
     */
    private void createAndSubmitCloudlets(DatacenterBroker broker, Vm vm) {
        int cloudletId = cloudletList.size();

        UtilizationModel utilizationModel = new UtilizationModelFull();

        Cloudlet cloudlet = new CloudletSimple(cloudletId, CLOUDLET_LENGTH, 1)
                .setFileSize(300).setOutputSize(300).setUtilizationModel(utilizationModel)
                .setVm(vm);

        broker.submitCloudlet(cloudlet);
        cloudletList.add(cloudlet);
    }
}
