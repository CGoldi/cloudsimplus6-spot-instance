
package tables;

import org.cloudbus.cloudsim.core.AbstractMachine;
import org.cloudbus.cloudsim.core.Identifiable;
import org.cloudsimplus.builders.tables.Table;
import vmtypes.DynamicVm;

import java.util.List;

/**
 * Table builder for {@link DynamicVm} instances, displays the output of the simulation
 * <p>
 * Adjusted for DynamicVms from {@link org.cloudsimplus.builders.tables.CloudletsTableBuilder}
 */
public class DynamicVmTableBuilder extends tables.TableBuilderAbstract<DynamicVm> {
    private static final String TIME_FORMAT = "%.0f";
    private static final String SECONDS = "Seconds";
    private static final String CPU_CORES = "CPU cores";


    public DynamicVmTableBuilder(final List<? extends DynamicVm> list) {
        super(list);
    }

    public DynamicVmTableBuilder(final List<? extends DynamicVm> list, final Table table) {
        super(list, table);
    }

    @Override
    protected void createTableColumns() {

        final String ID = "ID";

        addColumnDataFunction(getTable().addColumn("Broker", ID), vm -> vm.getBroker().getId());
        addColumnDataFunction(getTable().addColumn("    Virtual Machine (VM)", "Identifier"), Identifiable::getId);
        addColumnDataFunction(getTable().addColumn("DC", ID), vm -> vm.getHost().getDatacenter().getId());
        addColumnDataFunction(getTable().addColumn("        Host", "Identifier"), vm -> vm.getHost().getId());
        addColumnDataFunction(getTable().addColumn("Host PEs ", CPU_CORES), vm -> vm.getHost().getWorkingPesNumber());
        addColumnDataFunction(getTable().addColumn("VM PEs   ", CPU_CORES), AbstractMachine::getNumberOfPes);
        addColumnDataFunction(getTable().addColumn("Start Time", SECONDS).setFormat(TIME_FORMAT), DynamicVm::getStartTime);
        addColumnDataFunction(getTable().addColumn("Stop Time", SECONDS).setFormat(TIME_FORMAT), DynamicVm::getStopTime);
        addColumnDataFunction(getTable().addColumn("Delay   ", SECONDS).setFormat(TIME_FORMAT), DynamicVm::getSubmissionDelay);
        addColumnDataFunction(getTable().addColumn("  Type      ", ""), DynamicVm::getType);
        addColumnDataFunction(getTable().addColumn("  State      ", ""), DynamicVm::getState);
    }
}
