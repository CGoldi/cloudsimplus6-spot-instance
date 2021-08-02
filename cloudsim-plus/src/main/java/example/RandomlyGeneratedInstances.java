package example;

import allocation.DatacenterBrokerDynamic;
import allocation.DynamicAllocation;
import org.cloudbus.cloudsim.brokers.DatacenterBroker;
import org.cloudbus.cloudsim.cloudlets.Cloudlet;
import org.cloudbus.cloudsim.cloudlets.CloudletSimple;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.datacenters.Datacenter;
import org.cloudbus.cloudsim.datacenters.DatacenterSimple;
import org.cloudbus.cloudsim.distributions.ContinuousDistribution;
import org.cloudbus.cloudsim.distributions.UniformDistr;
import org.cloudbus.cloudsim.hosts.Host;
import org.cloudbus.cloudsim.hosts.HostSimple;
import org.cloudbus.cloudsim.resources.Pe;
import org.cloudbus.cloudsim.resources.PeSimple;
import org.cloudbus.cloudsim.utilizationmodels.UtilizationModel;
import org.cloudbus.cloudsim.utilizationmodels.UtilizationModelFull;
import org.cloudbus.cloudsim.vms.Vm;
import org.cloudsimplus.builders.tables.CloudletsTableBuilder;
import org.cloudsimplus.listeners.EventInfo;
import org.cloudsimplus.listeners.VmHostEventInfo;
import tables.DynamicVmTableBuilder;
import vmtypes.DynamicVm;
import vmtypes.OnDemandInstance;
import vmtypes.SpotInstance;

import java.util.ArrayList;
import java.util.List;

/**
 * RandomlyGeneratedInstances Example
 * Generates random {@link DynamicVm} instances over time, shows the automatic deallocation of spot instances
 * <p>
 * This example is adapted from the existing CloudSimPlus Examples and might contain some code snippets from their
 * example files
 */
public class RandomlyGeneratedInstances {

    // configuration of the hosts
    private static final int HOSTS = 2;
    private static final int HOST_PES = 8;
    private static final int HOST_RAM = 2048;  //in Megabytes
    private static final int HOST_BW = 10000;  //in Megabit/s
    private static final int HOST_STORAGE = 1000000;  //in Megabytes

    // configuration of VMs and Cloudlets
    private static final int VMS = 3;
    private static final int VM_PES = 4;
    private static final int CLOUDLET_LENGTH = 20000;

    // configure simulation running time
    private double TERMINATION_TIME = 250;

    private final CloudSim simulation;
    private final DatacenterBrokerDynamic broker0;
    private final List<Cloudlet> cloudletList = new ArrayList<>();
    private final ContinuousDistribution random;
    private final List<DatacenterBroker> brokerList = new ArrayList<>();


    public static void main(String[] args) {
        new RandomlyGeneratedInstances();
    }

    private RandomlyGeneratedInstances() {

        random = new UniformDistr();

        simulation = new CloudSim(0.5);
        simulation.terminateAt(TERMINATION_TIME);
        Datacenter datacenter0 = createDatacenter();
        datacenter0.setSchedulingInterval(1);

        //Creates a broker that is a software acting on behalf a cloud customer to manage his/her VMs and Cloudlets
        broker0 = new DatacenterBrokerDynamic(simulation);
        broker0.setVmDestructionDelay(2);
        brokerList.add(broker0);

        //Initial Vms and cloudlet creation
        List<DynamicVm> vmList = createSpotVms();
        vmList.addAll(createOnDemand());
        submitVMandCreateCloudlet(vmList);

        //Dynamic Creation of Vms
        simulation.addOnClockTickListener(this::createRandomVmAndCloudlet);

        simulation.start();

        new CloudletsTableBuilder(broker0.getCloudletFinishedList()).build();
        new CloudletsTableBuilder(cloudletList).build();
        new DynamicVmTableBuilder(broker0.getVmCreatedList()).build();
    }

    /**
     * Listener function, that randomly creates new {@link DynamicVm}
     * and {@link Cloudlet} instances and submits them to the {@link DatacenterBroker}.
     *
     * @param eventInfo contains information about the event type
     */
    private void createRandomVmAndCloudlet(EventInfo eventInfo) {

        for (DatacenterBroker broker : brokerList) {
            for (Vm vm : broker.getVmExecList()) {
                vm.updateProcessing(simulation.clock(), vm.getHost().getVmScheduler().getAllocatedMips(vm));
            }

            // request to check for waiting cloudlets and resets the broker allocation
            if (broker instanceof DatacenterBrokerDynamic) {
                ((DatacenterBrokerDynamic) broker).resetBrokerAllocation();
                ((DatacenterBrokerDynamic) broker).requestWaitingCloudlet();
            }
        }

        if (random.sample() <= 0.1) {
            DynamicVm vm;
            // random choice between spot and on demand instance
            if (random.sample() <= 0.5) {
                vm = new OnDemandInstance(1000, VM_PES, true);
            } else {
                vm = new SpotInstance(1000, VM_PES, true);
            }

            vm.setRam(512).setBw(1000).setSize(10000).addOnHostAllocationListener(this::createCloudletWhenAllocated);
            broker0.submitVm(vm);
        }
    }

    /**
     * Creates a {@link Cloudlet} for the vm if it was allocated
     */
    private void createCloudletWhenAllocated(VmHostEventInfo vmHostEventInfo) {
        UtilizationModel utilizationModel = new UtilizationModelFull();

        int cloudletId = cloudletList.size();
        Cloudlet cloudlet = new CloudletSimple(cloudletId, CLOUDLET_LENGTH, 1)
                .setFileSize(300)
                .setOutputSize(300)
                .setUtilizationModel(utilizationModel)
                .setVm(vmHostEventInfo.getVm());

        vmHostEventInfo.getVm().getBroker().submitCloudlet(cloudlet);
        cloudletList.add(cloudlet);
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

        // Assign the DynamicAllocation policy to enable the behavior of spot instances
        final DynamicAllocation allocationPolicy = new DynamicAllocation();
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
     * Creates a list of {@link OnDemandInstance} Instances.
     */
    private List<DynamicVm> createSpotVms() {
        final List<DynamicVm> list = new ArrayList<>(VMS);
        for (int i = 0; i < VMS; i++) {
            final SpotInstance vm = new SpotInstance(1000, VM_PES, true);
            vm.setRam(512).setBw(1000).setSize(10000);
            list.add(vm);
        }
        return list;
    }

    /**
     * Creates a list {@link SpotInstance} Instances.
     */
    private List<DynamicVm> createOnDemand() {
        final List<DynamicVm> list = new ArrayList<>(VMS);
        for (int i = 0; i < VMS; i++) {
            final OnDemandInstance vm = new OnDemandInstance(1000, VM_PES, true);
            vm.setRam(512).setBw(1000).setSize(10000).setSubmissionDelay(10);
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
