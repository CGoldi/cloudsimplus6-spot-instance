/*
 * Title: CloudSim Toolkit Description: CloudSim (Cloud Simulation) Toolkit for Modeling and
 * Simulation of Clouds Licence: GPL - http://www.gnu.org/copyleft/gpl.html
 *
 * Copyright (c) 2009-2012, The University of Melbourne, Australia
 */
package org.cloudbus.cloudsim.hosts;

import org.cloudbus.cloudsim.provisioners.ResourceProvisioner;
import org.cloudbus.cloudsim.resources.*;
import org.cloudbus.cloudsim.vms.*;
import vmtypes.SpotInstance;

import java.util.*;

import static java.util.Objects.requireNonNull;

public class HostDynamic extends HostSimple implements Host {

    /**
     * additional variables for HostDynamic
     */
    private final List<Vm> vmListSpot = new ArrayList<>();

    private long spotStorageCapacityUsage = 0;
    private long spotRamCapacityUsage = 0;
    private long spotBwCapacityUsage = 0;
    private long spotPeCapacityUsage = 0;

    public HostDynamic(List<Pe> peList) {
        super(peList);
    }

    public HostDynamic(List<Pe> peList, boolean activate) {
        super(peList, activate);
    }

    public HostDynamic(ResourceProvisioner ramProvisioner, ResourceProvisioner bwProvisioner, long storage, List<Pe> peList) {
        super(ramProvisioner, bwProvisioner, storage, peList);
    }

    public HostDynamic(long ram, long bw, long storage, List<Pe> peList) {
        super(ram, bw, storage, peList);
    }

    public HostDynamic(long ram, long bw, long storage, List<Pe> peList, boolean activate) {
        super(ram, bw, storage, peList, activate);
    }


    @Override
    public HostSuitability createVm(final Vm vm) {


        final HostSuitability suitability = createVmInternal(vm);
        if (suitability.fully()) {
            addVmToCreatedList(vm);
            vm.setHost(this);
            vm.setCreated(true);
            vm.notifyOnHostAllocationListeners();
            vm.setStartTime(getSimulation().clock());
        }

        return suitability;
    }



    private HostSuitability createVmInternal(final Vm vm) {
        if (vm instanceof VmGroup) {
            return new HostSuitability("Just internal VMs inside a VmGroup can be created, not the VmGroup itself.");
        }

        final HostSuitability suitability = this.allocateResourcesForVm(vm, false);
        if (suitability.fully()) {
            this.addVmToList(vm);

            if (vm instanceof SpotInstance) {
                vmListSpot.add(vm);
            }
        }

        return suitability;
    }

    private HostSuitability allocateResourcesForVm(final Vm vm, final boolean inMigration){
        final HostSuitability suitability = getSuitabilityFor(vm);
        if(!suitability.fully()) {
            return suitability;
        }

        vm.setInMigration(inMigration);
        allocateResourcesForVm(vm);

        return suitability;
    }

    private void allocateResourcesForVm(Vm vm) {
        this.getRamProvisioner().allocateResourceForVm(vm, vm.getCurrentRequestedRam());
        this.getBwProvisioner().allocateResourceForVm(vm, vm.getCurrentRequestedBw());
        Storage storage = (Storage) this.getStorage();
        storage.allocateResource(vm.getStorage());
        this.getVmScheduler().allocatePesForVm(vm, vm.getCurrentRequestedMips());

        if (vm instanceof SpotInstance) {
            spotStorageCapacityUsage += vm.getStorage().getCapacity();
            spotRamCapacityUsage += vm.getRam().getCapacity();
            spotBwCapacityUsage += vm.getBw().getCapacity();
            spotPeCapacityUsage += vm.getNumberOfPes();
        }
    }

    /**
     * Deallocate all resources that a VM was using.
     *
     * @param vm the VM
     */
    protected void deallocateResourcesOfVm(final Vm vm) {
        vm.setCreated(false);
        this.getRamProvisioner().deallocateResourceForVm(vm);
        this.getBwProvisioner().deallocateResourceForVm(vm);
        this.getVmScheduler().deallocatePesFromVm(vm);
        Storage storage = (Storage) this.getStorage();
        storage.deallocateResource(vm.getStorage());

        if (vm instanceof SpotInstance) {
            spotStorageCapacityUsage -= vm.getStorage().getCapacity();
            spotRamCapacityUsage -= vm.getRam().getCapacity();
            spotBwCapacityUsage -= vm.getBw().getCapacity();
            spotPeCapacityUsage -= vm.getNumberOfPes();
        }
    }

    @Override
    public void destroyVm(final Vm vm) {
        if(!vm.isCreated()){
            return;
        }

        vmListSpot.remove(vm);
        super.destroyVm(vm);
    }

    @Override
    public void destroyAllVms() {

        vmListSpot.clear();
        super.destroyAllVms();
    }

    public long getSpotStorageCapacityUsage() {
        return spotStorageCapacityUsage;
    }

    public long getSpotRamCapacityUsage() {
        return spotRamCapacityUsage;
    }

    public long getSpotBwCapacityUsage() {
        return spotBwCapacityUsage;
    }

    public long getSpotPeCapacityUsage() {
        return spotPeCapacityUsage;
    }

}
