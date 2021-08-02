package example;

import allocation.DatacenterBrokerDynamic;
import allocation.DynamicAllocation;
import ch.qos.logback.classic.Level;
import org.cloudbus.cloudsim.brokers.DatacenterBroker;
import org.cloudbus.cloudsim.cloudlets.Cloudlet;
import org.cloudbus.cloudsim.cloudlets.CloudletExecution;
import org.cloudbus.cloudsim.cloudlets.CloudletSimple;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.events.SimEvent;
import org.cloudbus.cloudsim.datacenters.Datacenter;
import org.cloudbus.cloudsim.datacenters.DatacenterSimple;
import org.cloudbus.cloudsim.hosts.Host;
import org.cloudbus.cloudsim.hosts.HostSimple;
import org.cloudbus.cloudsim.resources.Pe;
import org.cloudbus.cloudsim.resources.PeSimple;
import org.cloudbus.cloudsim.util.Conversion;
import org.cloudbus.cloudsim.util.TimeUtil;
import org.cloudbus.cloudsim.utilizationmodels.UtilizationModel;
import org.cloudbus.cloudsim.utilizationmodels.UtilizationModelDynamic;
import org.cloudbus.cloudsim.utilizationmodels.UtilizationModelFull;
import org.cloudbus.cloudsim.vms.Vm;
import org.cloudsimplus.listeners.*;
import tables.CloudletsTableBuilder;
import org.cloudsimplus.builders.tables.TextTableColumn;
import org.cloudsimplus.util.Log;
import tables.DynamicVmTableBuilder;
import tables.ExecutionTableBuilder;
import tables.SpotVmTableBuilder;
import tracereader.google.TaskEventType;
import vmtypes.DynamicVm;
import vmtypes.OnDemandInstance;
import vmtypes.SpotInstance;

import java.io.IOException;
import java.time.LocalTime;
import java.util.*;
import java.util.function.Predicate;


import static org.cloudbus.cloudsim.util.Conversion.megaBytesToBytes;
import static org.cloudbus.cloudsim.util.MathUtil.positive;

/**
 *
 */
public class GoogleClusterTask_combined {
    private static final String TRACE_FILENAME = "workload/google-traces/machine-events-sample-1.csv"; // "S:/GoogleCluster/clusterdata-2011-2/machine_events/part-00000-of-00001_filled_empty_values.csv"; //
    // S:/GoogleCluster/clusterdata-2011-2/machine_events/part-00000-of-00001_filled_empty_values.csv
    private static final String TASK_EVENT_TRACE = "workload/google-traces/task-events-sample-part-00000-of-00500_adjusted.csv"; // "S:/GoogleCluster/clusterdata-2011-2/task_events/shortened/part-00000-of-00500_shortened.csv"; //
    // S:/GoogleCluster/clusterdata-2011-2/task_events/other/part-00000-of-00500_short_new.csv
    private static final String TASK_USAGE_TRACE = "workload/google-traces/task-usage-sample-1.csv";

    private static final int CLOUDLET_LENGTH = -10_000;

    private static final long VM_BW = 10000; //in Megabits/s
    private static final long VM_SIZE_MB = 1000; //in Megabytes

    private final CloudSim simulation;
    private final List<DatacenterBroker> brokers = new ArrayList<>();
    ;
    private Set<Cloudlet> cloudlets;


    // Machine Event variables
    private List<Datacenter> datacenters;
    //TODO: write method to submit to specific host
    //TODO: The initial data of the cluster trace submits and starts the cloudlets both at timestamp 0,
    // updates the clock time after the cloudlet, which results in an exception since the start of the cloudlet
    // execution is now a past event and can be started if the clock is greater than 0
    // ---> bandaid fix was to increase the timestamp for all scheduling events by 1 second

    private static final long HOST_BW = 10000000;
    private static final long HOST_STORAGE = 100000;
    private static final double HOST_MIPS = 1000;
    private static final long VM_RAM = 0;
    private static final long VM_PES = 1;
    private static final int VM_MIPS = 1000;

    // Other Trace variables
    private final List<Vm> vmList = new ArrayList<>();
    HashMap<String, Vm> machine_username_vm = new HashMap<>();
    private final DatacenterBrokerDynamic broker0;

    // Spot Variables
    private final List<Cloudlet> spotCloudletList = new ArrayList<>();
    private final int SpotNumber = 100;
    private final int SPOT_CLOUDLET_LENGTH = 72000;
    private final DatacenterBrokerDynamic spot_broker;
    HashMap<Long, Cloudlet> cloudletHashMap = new HashMap<>();


    private boolean submit = true;
    private double lastSubmitted;

    public static void main(String[] args) throws IOException {
        new GoogleClusterTask_combined();
    }

    private GoogleClusterTask_combined() throws IOException {
        final double startSecs = TimeUtil.currentTimeSecs();
        System.out.printf("Simulation started at %s%n%n", LocalTime.now());
        Log.setLevel(Level.TRACE);

        simulation = new CloudSim();

        broker0 = new DatacenterBrokerDynamic(simulation);
        broker0.setName("TraceFileBroker");
//        broker0.setVmDestructionDelayFunction(this::vmDestruction);
        brokers.add(broker0);

        spot_broker = new DatacenterBrokerDynamic(simulation);
        spot_broker.setName("SpotInstanceBroker");
        spot_broker.setVmDestructionDelayFunction(this::vmDestruction);
        brokers.add(spot_broker);

        // Creates Datacenter and Host from the Trace File
        createDatacenters();

        // Creates Cloudlets and Virtual Machines from the Trace File
        createCloudletsAndBrokersFromTraceFile();

        broker0.submitVmList(vmList);

        createAndSubmitSpotInstances();

        System.out.println("Brokers:");
        brokers.stream().sorted().forEach(b -> System.out.printf("\t%d - %s%n", b.getId(), b.getName()));

        simulation.addOnClockTickListener(this::updateProcessingforSpotVms);
        simulation.addOnClockTickListener(this::submitSpotinstances);

        simulation.start();

        for (DatacenterBroker broker : brokers) {
            printCloudlets(broker);
        }

        saveVms();
        System.out.printf("Simulation finished at %s. Execution time: %.2f seconds%n", LocalTime.now(), TimeUtil.elapsedSeconds(startSecs));

    }

    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    ///////////       Spot Instances       ///////////////////////////////////////////////////////////////////////////
    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////


    private void updateProcessingforSpotVms(EventInfo eventInfo) {

        if(submit) {
            spot_broker.resubmitVms();
            submit = false;
            lastSubmitted = simulation.clock();

            for (Vm vm : spot_broker.getVmExecList()) {
                vm.updateProcessing(simulation.clock(), vm.getHost().getVmScheduler().getAllocatedMips(vm));
            }
        } else if(simulation.clock() - lastSubmitted >= 5) {
            spot_broker.resubmitVms();
            submit = true;
            lastSubmitted = simulation.clock();

            for (Vm vm : spot_broker.getVmExecList()) {
                vm.updateProcessing(simulation.clock(), vm.getHost().getVmScheduler().getAllocatedMips(vm));
            }
        }

        // shutdown spot broker to not get concur
        if(simulation.clock() > 100){
            spot_broker.shutdown();
        }


    }

    /**
     * Listner to dynamically create instances over time
     * @param eventInfo
     */
    private void submitSpotinstances(EventInfo eventInfo) {
        // S
    }

    private void createAndSubmitSpotInstances() {
        final List<DynamicVm> spotList = new ArrayList<>(SpotNumber);
        for (int i = 0; i < SpotNumber; i++) {
            spotList.add(createSpot());
        }

        submitVMandCreateCloudlet(spotList);
    }

    private DynamicVm createSpot() {
        SpotInstance spot = new SpotInstance(VM_MIPS, VM_PES, true);
        spot.setRam(VM_RAM).setBw(VM_BW).setSize(VM_SIZE_MB);
        spot.setInterruptionBehavior(SpotInstance.InterruptionBehavior.HIBERNATE);
        spot.setPersistentRequest(true);
        spot.setHibernationTimeLimit(300);
        spot.setWaitingTime(300);
        spot.setMinimumRunningTime(0);

        return spot;
    }

    private void submitVMandCreateCloudlet(List<DynamicVm> spotList) {
        for (Vm vm : spotList) {
            spot_broker.submitVm(vm);
            createAndSubmitCloudlets(vm);
        }
    }

    private void createAndSubmitCloudlets(Vm vm) {
        UtilizationModel utilizationModel = new UtilizationModelFull();

        Cloudlet cloudlet = new CloudletSimple(vm.getId(), SPOT_CLOUDLET_LENGTH, 1)
                .setFileSize(300).setOutputSize(300).setUtilizationModel(utilizationModel)
                .setVm(vm);

        long jobId = Double.valueOf(simulation.clock()).longValue();
        cloudlet.setJobId(jobId);

        spot_broker.submitCloudlet(cloudlet);
        cloudlets.add(cloudlet);
    }


    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    ///////////       Cloudlet & VM's from Trace         /////////////////////////////////////////////////////////////
    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     *
     */
    private void createCloudletsAndBrokersFromTraceFile() {

        System.out.println("-------------------------------------------------------------------------------------");
        System.out.println(java.time.Clock.systemUTC().instant());
        System.out.println("Starting to read file ");

        final tracereader.google.GoogleTaskEventsTraceReader reader =
                tracereader.google.GoogleTaskEventsTraceReader.getInstance(simulation, TASK_EVENT_TRACE, this::createCloudlet);
        reader.setCloudletHashMap(cloudletHashMap);

        // set default broker for all cloudlets and vms
        reader.setDefaultBroker(broker0);
        cloudlets = reader.process();

        System.out.println("Processing file completed");
        System.out.println("-------------------------------------------------------------------------------------");

        System.out.printf(
                "%d Cloudlets and %d Brokers created from the %s trace file.%n",
                cloudlets.size(), brokers.size(), TASK_EVENT_TRACE);
    }

    private Cloudlet createCloudlet(final tracereader.google.TaskEvent event) {

        final long pesNumber = positive(event.actualCpuCores(VM_PES), VM_PES);

        final double maxRamUsagePercent = positive(event.getResourceRequestForRam(), Conversion.HUNDRED_PERCENT);

        final double sizeInMB = event.getResourceRequestForLocalDiskSpace() * VM_SIZE_MB + 1;
        final long sizeInBytes = (long) Math.ceil(megaBytesToBytes(sizeInMB));
        Cloudlet cloudlet = new CloudletSimple(CLOUDLET_LENGTH, pesNumber)
                .setFileSize(sizeInBytes)
                .setOutputSize(sizeInBytes);

        cloudlet.addOnFinishListener(this::destroyVm);


        // TODO: create VM for each username + machine_id
        //  if a username has no machine_id
        if (event.getMachineId() != -1 && event.getType() == TaskEventType.SUBMIT) {
            if (machine_username_vm.containsKey(event.getUserName() + "_" + event.getMachineId())) {
                Vm vm = machine_username_vm.get(event.getUserName() + "_" + event.getMachineId());
                cloudlet.setVm(vm);
                cloudlet.setExecStartTime(event.getTimestamp());
                vm.getCloudletScheduler().getCloudletExecList().add(new CloudletExecution(cloudlet));

            } else {
                Vm vm = createVm(event.getUniqueTaskId());
                vm.setBroker(broker0);
                vm.setHost(datacenters.get(0).getHostById(event.getMachineId()));

                machine_username_vm.put(event.getUserName() + "_" + event.getMachineId(), vm);
                cloudlet.setVm(vm);
                vm.getCloudletScheduler().getCloudletExecList().add(new CloudletExecution(cloudlet));
                cloudlet.setExecStartTime(event.getTimestamp());
                vm.setSubmissionDelay(event.getTimestamp());
                vmList.add(vm);

            }
            cloudlet.setSubmissionDelay(event.getTimestamp() - cloudlet.getVm().getSubmissionDelay());
        }

        return cloudlet;
    }

    private void destroyVm(CloudletVmEventInfo cloudletVmEventInfo) {
        if(cloudletVmEventInfo.getVm() instanceof DynamicVm) {
            DynamicVm vm = (DynamicVm) cloudletVmEventInfo.getVm();
            vm.getWaitingCloudlets().remove(cloudletVmEventInfo.getCloudlet());

            if(vm.getWaitingCloudlets().size() == 0) {
                broker0.destroyVm(vm);
                cloudletVmEventInfo.getVm().getBroker().destroyVm(cloudletVmEventInfo.getVm());
            }
        }

    }

    /**
     * If a Vm hasn't been allocated yet, the destruction delay is higher than the submission
     * delay after that it lowers
     * TODO: vm destruction delay function
     */
    private Double vmDestruction(Vm vm) {
        if (simulation.clock() > vm.getSubmissionDelay()) {
            return 120.0;
        } else {
            return vm.getSubmissionDelay() + 6000;
        }
    }

    private long getVmSize(final Cloudlet cloudlet) {
        return cloudlet.getVm().getStorage().getCapacity();
    }

    private long getCloudletSizeInMB(final Cloudlet cloudlet) {
        return (long) Conversion.bytesToMegaBytes(cloudlet.getFileSize());
    }

    private Vm createVm(final long id) {
        //Uses a CloudletSchedulerTimeShared by default
        return new OnDemandInstance(VM_MIPS, VM_PES, true).setRam(VM_RAM).setBw(VM_BW).setSize(VM_SIZE_MB);
    }

    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    ///////////       Datacenter & Host         //////////////////////////////////////////////////////////////////////
    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    private void createDatacenters() {
        datacenters = new ArrayList<>(1);

        final tracereader.google.GoogleMachineEventsTraceReader reader =
                tracereader.google.GoogleMachineEventsTraceReader.getInstance(TRACE_FILENAME, this::createHost);
        reader.setMaxRamCapacity(32);
        reader.setMaxCpuCores(10);

        //Creates Datacenters with no hosts.
        for (int i = 0; i < 1; i++) {
            DatacenterSimple dc = new DatacenterSimple(simulation, new DynamicAllocation());
            dc.addOnHostAvailableListener(this::setSimulation);
            dc.setSchedulingInterval(20);
            datacenters.add(dc);
        }

        /*Process the trace file and creates the Hosts that the timestamp is defined as zero inside the file.
         * Then, returns the list of immediately created Hosts (for timestamp 0).
         * The second Datacenter that is given as parameter will be used to add the Hosts with timestamp greater than 0.
         * */
        reader.setDatacenterForLaterHosts(datacenters.get(0));
        final List<Host> hostList = new ArrayList<>(reader.process());

        System.out.println();
        System.out.printf("# Created %d Hosts that were immediately available from the Google trace file%n", hostList.size());
        System.out.printf("# %d Hosts will be available later on (according to the trace timestamp)%n", reader.getNumberOfLaterAvailableHosts());
        System.out.printf("# %d Hosts will be removed later on (according to the trace timestamp)%n%n", reader.getNumberOfHostsForRemoval());

        //Finally, the immediately created Hosts are added to the first Datacenter
        for (Host host : hostList) {
            host.setSimulation(simulation);
        }
        datacenters.get(0).addHostList(hostList);
    }

    private Host createHost(final tracereader.google.MachineEvent event) {
        final Host host = new HostSimple(event.getRam()*5, HOST_BW*5, HOST_STORAGE*5, createPesList(event.getCpuCores()*5));
        host.setId(event.getMachineId());
        return host;
    }

    private List<Pe> createPesList(final int count) {
        final List<Pe> cpuCoresList = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            //Uses a PeProvisionerSimple by default
            cpuCoresList.add(new PeSimple(HOST_MIPS));
        }

        return cpuCoresList;
    }

    /**
     * Set simulation for newly available hosts to get the correct start and stop time for Vms running on that host
     *
     * @param hostEventInfo data from the host available event
     */
    private void setSimulation(HostEventInfo hostEventInfo) {
        hostEventInfo.getHost().setSimulation(simulation);
    }

    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    ///////////       Output         /////////////////////////////////////////////////////////////////////////////////
    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    private void printCloudlets(final DatacenterBroker broker) throws IOException {
        final String username = broker.getName().replace("Broker_", "");
        final List<Cloudlet> list = broker.getCloudletFinishedList();
        list.sort(Comparator.comparingLong(Cloudlet::getId));
        new CloudletsTableBuilder(list)
                .addColumn(0, new TextTableColumn("             Job", "ID"), Cloudlet::getJobId)
                .addColumn(7, new TextTableColumn("VM Size", "MB"), this::getVmSize)
                .addColumn(8, new TextTableColumn("Cloudlet Size", "MB"), this::getCloudletSizeInMB)
                .addColumn(10, new TextTableColumn("Waiting Time", "Seconds").setFormat("%.0f"), Cloudlet::getWaitingTime)
                .setTitle("Simulation results for Broker " + broker.getId() + " representing the username " + username)
                .build();

        new CloudletsTableBuilder(list)
                .addColumn(0, new TextTableColumn("             Job", "ID"), Cloudlet::getJobId)
                .addColumn(7, new TextTableColumn("VM Size", "MB"), this::getVmSize)
                .addColumn(8, new TextTableColumn("Cloudlet Size", "MB"), this::getCloudletSizeInMB)
                .addColumn(10, new TextTableColumn("Waiting Time", "Seconds").setFormat("%.0f"), Cloudlet::getWaitingTime)
                .setTitle("Simulation results for Broker " + broker.getId() + " representing the username " + username)
                .save("cloudlets_"+broker.getName()+".csv");
    }

    /**
     * Save output
     */
    private void saveVms() throws IOException {

        List<DynamicVm> finishedVms = new ArrayList<>(broker0.getVmCreatedList());

        Set<DynamicVm> vmSet = new HashSet<>(finishedVms);
        finishedVms.clear();
        finishedVms.addAll(vmSet);

        new DynamicVmTableBuilder(finishedVms).build();
        new DynamicVmTableBuilder(finishedVms).save("finished_vms.csv");

        List<DynamicVm> spotVms = new ArrayList<>(spot_broker.getVmCreatedList());
        Set<DynamicVm> spotSet = new HashSet<>(spotVms);
        spotVms.clear();
        spotVms.addAll(spotSet);

        List<SpotInstance> finishedSpot = new ArrayList<>();
        for (Vm vm : spot_broker.getVmCreatedList()) {
            if (vm instanceof SpotInstance) {
                ((SpotInstance) vm).calculateAverageInterruptionTime();
                finishedSpot.add((SpotInstance) vm);

                new ExecutionTableBuilder(((SpotInstance) vm).getExecutionHistory()).save("executionhistory/"+vm.getId()+"_history.csv");
            }
        }

        new SpotVmTableBuilder(finishedSpot).build();
        new SpotVmTableBuilder(finishedSpot).save("finished_spot.csv");

    }
}
