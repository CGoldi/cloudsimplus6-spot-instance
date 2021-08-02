
package tables;

import org.cloudbus.cloudsim.core.AbstractMachine;
import org.cloudbus.cloudsim.core.Identifiable;
import org.cloudsimplus.builders.tables.Table;
import vmtypes.SpotInstance;

import java.util.List;

/**
 * Table Builder for {@link SpotInstance}, displays the output of the simulation
 * <p>
 * Adjusted for SpotInstances from {@link org.cloudsimplus.builders.tables.CloudletsTableBuilder}
 */
public class SpotVmTableBuilder extends tables.TableBuilderAbstract<SpotInstance> {
    private static final String TIME_FORMAT = "%.0f";
    private static final String SECONDS = "Seconds";
    private static final String CPU_CORES = "CPU cores";

    public SpotVmTableBuilder(final List<? extends SpotInstance> list) {
        super(list);
    }

    public SpotVmTableBuilder(final List<? extends SpotInstance> list, final Table table) {
        super(list, table);
    }

    @Override
    protected void createTableColumns() {

        addColumnDataFunction(getTable().addColumn("Broker", "ID"), vm -> vm.getBroker().getId());
        addColumnDataFunction(getTable().addColumn("    Virtual Machine (VM)", "Identifier"), Identifiable::getId);
        addColumnDataFunction(getTable().addColumn("DC", "ID"), vm -> vm.getHost().getDatacenter().getId());
        addColumnDataFunction(getTable().addColumn("        Host", "Identifier"), vm -> vm.getHost().getId());
        addColumnDataFunction(getTable().addColumn("Host PEs ", CPU_CORES),
                vm -> vm.getHost().getWorkingPesNumber());
        addColumnDataFunction(getTable().addColumn("VM PEs   ", CPU_CORES), AbstractMachine::getNumberOfPes);
        addColumnDataFunction(getTable().addColumn("Start Time", SECONDS).setFormat(TIME_FORMAT),
                vm -> vm.getExecutionHistory().get(0).getStartTime());
        addColumnDataFunction(getTable().addColumn("Stop Time", SECONDS).setFormat(TIME_FORMAT),
                vm -> vm.getExecutionHistory().get(vm.getExecutionHistory().size() - 1).getStopTime());
        addColumnDataFunction(getTable().addColumn("  State      ", ""), SpotInstance::getState);
        addColumnDataFunction(getTable().addColumn("Average Interruption", SECONDS).setFormat(TIME_FORMAT),
                SpotInstance::getAverageInterruptionTime);
    }
}
