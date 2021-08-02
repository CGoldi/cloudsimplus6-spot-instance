package example;

import allocation.DatacenterBrokerDynamic;
import allocation.DynamicAllocation;
import ch.qos.logback.classic.Level;
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
import org.cloudbus.cloudsim.util.Conversion;
import org.cloudbus.cloudsim.util.TimeUtil;
import org.cloudbus.cloudsim.utilizationmodels.UtilizationModelDynamic;
import org.cloudbus.cloudsim.utilizationmodels.UtilizationModelFull;
import org.cloudbus.cloudsim.vms.Vm;
import org.cloudsimplus.builders.tables.CloudletsTableBuilder;
import org.cloudsimplus.builders.tables.TextTableColumn;
import org.cloudsimplus.listeners.EventInfo;
import org.cloudsimplus.util.Log;
import tables.DynamicVmTableBuilder;
import tables.SpotVmTableBuilder;
import tracereader.google.GoogleTaskEventsTraceReader;
import tracereader.google.TaskEventType;
import vmtypes.DynamicVm;
import vmtypes.OnDemandInstance;
import vmtypes.SpotInstance;

import java.io.IOException;
import java.time.LocalTime;
import java.util.*;

import static org.cloudbus.cloudsim.util.Conversion.megaBytesToBytes;
import static org.cloudbus.cloudsim.util.MathUtil.positive;

/**
 *
 */
public class GoogleClusterTask {
    private static final String TRACE_FILENAME = "workload/google-traces/machine-events-sample-1.csv"; //
    // S:/GoogleCluster/clusterdata-2011-2/machine_events/part-00000-of-00001_filled_empty_values.csv
    private static final String TASK_EVENT_TRACE = "workload/google-traces/task-events-sample-1.csv"; //
    // S:/GoogleCluster/clusterdata-2011-2/task_events/other/part-00000-of-00500_short_new.csv
    private static final String TASK_USAGE_TRACE = "workload/google-traces/task-usage-sample-1.csv";

    private static final int  CLOUDLET_LENGTH = -10_000;

    private static final long VM_BW = 100; //in Megabits/s
    private static final long VM_SIZE_MB = 1000; //in Megabytes

    private final CloudSim simulation;
    private final List<DatacenterBroker> brokers = new ArrayList<>();;
    private Set<Cloudlet> cloudlets;


    // Machine Event variables
    private List<Datacenter> datacenters;
    //TODO: write method to submit to specific host
    //TODO: The initial data of the cluster trace submits and starts the cloudlets both at timestamp 0,
    // updates the clock time after the cloudlet, which results in an exception since the start of the cloudlet
    // execution is now a past event and can be started if the clock is greater than 0
    // ---> bandaid fix was to increase the timestamp for all scheduling events by 1 second
    private static final int DATACENTERS_NUMBER = 1;

    private static final long HOST_BW = 10000;
    private static final long HOST_STORAGE = 100000;
    private static final double HOST_MIPS = 1000;
    private static final long VM_RAM = 0;
    private static final long VM_PES = 1;
    private static final int  VM_MIPS = 100;

    // Other variables
    private final LinkedHashMap<Vm, Double> delayedVms = new LinkedHashMap<>();
    private final List<Vm> initialVms = new ArrayList<>();
    private final DatacenterBrokerDynamic broker0;

    public static void main(String[] args) throws IOException {
        new GoogleClusterTask();
    }

    private GoogleClusterTask() throws IOException {
        final double startSecs = TimeUtil.currentTimeSecs();
        System.out.printf("Simulation started at %s%n%n", LocalTime.now());
        Log.setLevel(Level.TRACE);

        simulation = new CloudSim();
        broker0 = new DatacenterBrokerDynamic(simulation);
        brokers.add(broker0);

        createDatacenters();

        /////
        createCloudletsAndBrokersFromTraceFile();

        broker0.submitVmList(initialVms);

        // readTaskUsageTraceFile();

        System.out.println("Brokers:");
        brokers.stream().sorted().forEach(b -> System.out.printf("\t%d - %s%n", b.getId(), b.getName()));

        simulation.terminateAt(100);

        simulation.addOnClockTickListener(this::submitDelayedVms);

        simulation.start();

        brokers.stream().sorted().forEach(this::printCloudlets);
        saveVms();
        System.out.printf("Simulation finished at %s. Execution time: %.2f seconds%n", LocalTime.now(), TimeUtil.elapsedSeconds(startSecs));

    }

    private void submitDelayedVms(EventInfo eventInfo) {

        broker0.resetBrokerAllocation();
        if(simulation.clock() > 0.5) {
            Iterator<Map.Entry<Vm, Double>> it = delayedVms.entrySet().iterator();

            while (it.hasNext()) {
                Map.Entry entry = it.next();
                if ((Double) entry.getValue() <= simulation.clock() + 1) {
                    broker0.submitVm((Vm) entry.getKey());
                    it.remove();
                } else {
                    break;
                }
            }
        }

//        delayedVms.entrySet().removeIf(e -> e.getValue() >= simulation.clock());
    }


    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     *
     */
    private void createCloudletsAndBrokersFromTraceFile() {
        final GoogleTaskEventsTraceReader reader =
                GoogleTaskEventsTraceReader.getInstance(simulation, TASK_EVENT_TRACE, this::createCloudlet);

        // set default broker for all cloudlets and vms
        reader.setDefaultBroker(broker0);

        /*The created Cloudlets are automatically submitted to their respective brokers,
        so you don't have to submit them manually.*/
        cloudlets = reader.process();

        System.out.printf(
            "%d Cloudlets and %d Brokers created from the %s trace file.%n",
            cloudlets.size(), brokers.size(), TASK_EVENT_TRACE);
    }

    private Cloudlet createCloudlet(final tracereader.google.TaskEvent event) {

        final long pesNumber = positive(event.actualCpuCores(VM_PES), VM_PES);

        final double maxRamUsagePercent = positive(event.getResourceRequestForRam(), Conversion.HUNDRED_PERCENT);
        final UtilizationModelDynamic utilizationRam = new UtilizationModelDynamic(0, maxRamUsagePercent);

        final double sizeInMB    = event.getResourceRequestForLocalDiskSpace() * VM_SIZE_MB + 1;
        final long   sizeInBytes = (long) Math.ceil(megaBytesToBytes(sizeInMB));
        Cloudlet cloudlet = new CloudletSimple(CLOUDLET_LENGTH, pesNumber)
            .setFileSize(sizeInBytes)
            .setOutputSize(sizeInBytes)
            .setUtilizationModelBw(new UtilizationModelFull())
            .setUtilizationModelCpu(new UtilizationModelFull())
            .setUtilizationModelRam(utilizationRam);

        // TODO: create VM for each cloudlet
        //  if a username has no machine_id
        if(event.getMachineId()!=-1 && event.getType()==TaskEventType.SUBMIT) {

            Vm vm = createVm(event.getUniqueTaskId());
            System.out.println("Create Vm  " + event.getUniqueTaskId());
            vm.setId(event.getUniqueTaskId());
            vm.setBroker(broker0);
            vm.setHost(datacenters.get(0).getHostById(event.getMachineId()));

            cloudlet.setVm(vm);

            if (event.getTimestamp() >= 1) {
                cloudlet.setSubmissionDelay(1);
                delayedVms.put(vm, event.getTimestamp());
            } else {
                initialVms.add(vm);
            }
        }
        return cloudlet;
    }


    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////


    private void readTaskUsageTraceFile() {
        final String fileName = TASK_USAGE_TRACE;
        final tracereader.google.GoogleTaskUsageTraceReader reader =
                tracereader.google.GoogleTaskUsageTraceReader.getInstance(brokers, fileName);
        final Set<Cloudlet> processedCloudlets = reader.process();
        System.out.printf("%d Cloudlets processed from the %s trace file.%n", processedCloudlets.size(), fileName);
        System.out.println();
    }

    private long getVmSize(final Cloudlet cloudlet) {
        return cloudlet.getVm().getStorage().getCapacity();
    }

    private long getCloudletSizeInMB(final Cloudlet cloudlet) {
        return (long)Conversion.bytesToMegaBytes(cloudlet.getFileSize());
    }

    private List<Pe> createPesList(final int count) {
        final List<Pe> cpuCoresList = new ArrayList<>(count);
        for(int i = 0; i < count; i++){
            //Uses a PeProvisionerSimple by default
            cpuCoresList.add(new PeSimple(HOST_MIPS));
        }

        return cpuCoresList;
    }

    private Vm createVm(final long id) {
        //Uses a CloudletSchedulerTimeShared by default
        return new OnDemandInstance(VM_MIPS, VM_PES, true).setRam(VM_RAM).setBw(VM_BW).setSize(VM_SIZE_MB);
    }

    private void printCloudlets(final DatacenterBroker broker) {
        final String username = broker.getName().replace("Broker_", "");
        final List<Cloudlet> list = broker.getCloudletFinishedList();
        list.sort(Comparator.comparingLong(Cloudlet::getId));
        new CloudletsTableBuilder(list)
            .addColumn(0, new TextTableColumn("Job", "ID"), Cloudlet::getJobId)
            .addColumn(7, new TextTableColumn("VM Size", "MB"), this::getVmSize)
            .addColumn(8, new TextTableColumn("Cloudlet Size", "MB"), this::getCloudletSizeInMB)
            .addColumn(10, new TextTableColumn("Waiting Time", "Seconds").setFormat("%.0f"), Cloudlet::getWaitingTime)
            .setTitle("Simulation results for Broker " + broker.getId() + " representing the username " + username)
            .build();
    }


    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    private void createDatacenters() {
        datacenters = new ArrayList<>(DATACENTERS_NUMBER);

        final tracereader.google.GoogleMachineEventsTraceReader reader = tracereader.google.GoogleMachineEventsTraceReader.getInstance(TRACE_FILENAME, this::createHost);
        reader.setMaxRamCapacity(32);
        reader.setMaxCpuCores(10);

        //Creates Datacenters with no hosts.
        for(int i = 0; i < DATACENTERS_NUMBER; i++){
            datacenters.add(new DatacenterSimple(simulation, new DynamicAllocation()));
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
        datacenters.get(0).addHostList(hostList);
    }

    private Host createHost(final tracereader.google.MachineEvent event) {
        final Host host = new HostSimple(event.getRam()*10, HOST_BW, HOST_STORAGE, createPesList(event.getCpuCores()*10));
        host.setId(event.getMachineId());
        return host;
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Save output
     */
    private void saveVms() throws IOException {
        List<DynamicVm> finishedVms = new ArrayList<>();

        for (DatacenterBroker broker : brokers) {
            finishedVms.addAll(broker.getVmCreatedList());
        }
        Set<DynamicVm> VmSet = new HashSet<>(finishedVms);
        finishedVms.clear();
        finishedVms.addAll(VmSet);

        new DynamicVmTableBuilder(finishedVms).build();
//        new DynamicVmTableBuilder(finishedVms).save("finished_vms.csv");

        List<SpotInstance> finishedSpot = new ArrayList<>();
        for (DynamicVm vm : finishedVms) {
            if (vm instanceof SpotInstance) {
                ((SpotInstance) vm).calculateAverageInterruptionTime();
                finishedSpot.add((SpotInstance) vm);
            }
        }

        new SpotVmTableBuilder(finishedSpot).build();
//        new SpotVmTableBuilder(finishedSpot).save("finished_spot.csv");
    }

}
