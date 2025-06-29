package example;

import allocation.DatacenterBrokerDynamic;
import allocation.DynamicAllocationHLEM;
import com.google.gson.Gson;
import org.cloudbus.cloudsim.hosts.HostDynamic;
import org.cloudbus.cloudsim.brokers.DatacenterBroker;
import org.cloudbus.cloudsim.cloudlets.Cloudlet;
import org.cloudbus.cloudsim.cloudlets.CloudletSimple;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.datacenters.Datacenter;
import org.cloudbus.cloudsim.datacenters.DatacenterSimple;
import org.cloudbus.cloudsim.hosts.Host;
import org.cloudbus.cloudsim.resources.Pe;
import org.cloudbus.cloudsim.resources.PeSimple;
import org.cloudbus.cloudsim.utilizationmodels.UtilizationModel;
import org.cloudbus.cloudsim.utilizationmodels.UtilizationModelFull;
import org.cloudbus.cloudsim.vms.Vm;
import org.cloudsimplus.listeners.EventInfo;
import org.cloudsimplus.listeners.VmHostEventInfo;
import tables.CloudletsTableBuilder;
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
public class TestExample {

    private final CloudSim simulation;
    private final DatacenterBrokerDynamic broker0;
    private final List<DatacenterBroker> brokerList = new ArrayList<>();


    public static void main(String[] args) throws IOException {
        new TestExample();
    }

    private TestExample() throws IOException {

        simulation = new CloudSim(0.5);
        simulation.terminateAt(70);

        final List<Pe> peList = new ArrayList<>(List.of(new PeSimple(1000), new PeSimple(1000)));
        Host host = new HostDynamic(2048, 10000, 1000000, peList);

        final DynamicAllocationHLEM allocationPolicy = new DynamicAllocationHLEM();
        Datacenter datacenter0 = new DatacenterSimple(simulation, List.of(host), allocationPolicy);
        datacenter0.setSchedulingInterval(1);

        //Creates a broker that is a software acting on behalf a cloud customer to manage his/her VMs and Cloudlets
        broker0 = new DatacenterBrokerDynamic(simulation);
        brokerList.add(broker0);

        broker0.setShutdownWhenIdle(false);
        broker0.setVmDestructionDelay(2);

        //Initial Vms and cloudlet creation
        final SpotInstance spotvm = new SpotInstance(1000, 2, true);
        spotvm.setRam(512);
        spotvm.setBw(1000);
        spotvm.setSize(10000);
        spotvm.addOnHostDeallocationListener(this::onHostDeallocationListener);
        spotvm.setInterruptionBehavior(SpotInstance.InterruptionBehavior.HIBERNATE);
        spotvm.setPersistentRequest(true);
        spotvm.setHibernationTimeLimit(300);
        spotvm.setWaitingTime(300);
        spotvm.setMinimumRunningTime(0);

        Cloudlet cloudletSpot = new CloudletSimple(1, 20000, 1);
        cloudletSpot.setFileSize(300);
        cloudletSpot.setOutputSize(300);
        cloudletSpot.setUtilizationModel(new UtilizationModelFull());
        cloudletSpot.setVm(spotvm);

        final OnDemandInstance ondemandvm = new OnDemandInstance(1000, 2, true);
        ondemandvm.setRam(512);
        ondemandvm.setBw(1000);
        ondemandvm.setSize(10000);
        ondemandvm.addOnHostDeallocationListener(this::onHostDeallocationListener);
        ondemandvm.setSubmissionDelay(10);
        ondemandvm.setPersistentRequest(true);
        ondemandvm.setWaitingTime(40);

        Cloudlet cloudletOnDemand = new CloudletSimple(2, 20000, 1);
        cloudletOnDemand.setFileSize(300);
        cloudletOnDemand.setOutputSize(300);
        cloudletOnDemand.setUtilizationModel(new UtilizationModelFull());
        cloudletOnDemand.setVm(ondemandvm);

        broker0.submitVm(spotvm);
        broker0.submitCloudlet(cloudletSpot);

        broker0.submitVm(ondemandvm);
        broker0.submitCloudlet(cloudletOnDemand);

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
}
