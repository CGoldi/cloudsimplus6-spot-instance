/*
 * CloudSim Plus: A modern, highly-extensible and easier-to-use Framework for
 * Modeling and Simulation of Cloud Computing Infrastructures and Services.
 * http://cloudsimplus.org
 *
 *     Copyright (C) 2015-2018 Universidade da Beira Interior (UBI, Portugal) and
 *     the Instituto Federal de Educação Ciência e Tecnologia do Tocantins (IFTO, Brazil).
 *
 *     This file is part of CloudSim Plus.
 *
 *     CloudSim Plus is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     CloudSim Plus is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with CloudSim Plus. If not, see <http://www.gnu.org/licenses/>.
 */
package tables;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.cloudbus.cloudsim.brokers.DatacenterBroker;
import org.cloudbus.cloudsim.vms.Vm;
import org.cloudsimplus.builders.tables.Table;
import org.cloudsimplus.builders.tables.TableColumn;
import org.cloudsimplus.builders.tables.TextTable;
import vmtypes.ExecutionHistory;
import vmtypes.SpotInstance;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a table for printing simulation results from a list of Cloudlets.
 * It defines a set of default columns but new ones can be added
 * dynamically using the {@code addColumn()} methods.
 *
 * <p>The basic usage of the class is by calling its constructor,
 * giving a list of Cloudlets to be printed, and then
 * calling the {@link #build()} method.</p>
 *
 * @author Manoel Campos da Silva Filho
 * @since CloudSim Plus 1.0
 */
public class ExecutionTableBuilder extends TableBuilderAbstract<ExecutionHistory> {
    private static final String TIME_FORMAT = "%.0f";
    private static final String SECONDS = "Seconds";
    private static final String CPU_CORES = "CPU cores";

    /**
     * Instantiates a builder to print the list of Cloudlets using the a
     * default {@link TextTable}.
     * To use a different {@link Table}, check the alternative constructors.
     *
     * @param list the list of Cloudlets to print
     */
    public ExecutionTableBuilder(final List<? extends ExecutionHistory> list) {
        super(list);
    }

    /**
     * Instantiates a builder to print the list of Cloudlets using the a
     * given {@link Table}.
     *
     * @param list the list of Cloudlets to print
     * @param table the {@link Table} used to build the table with the Cloudlets data
     */
    public ExecutionTableBuilder(final List<? extends ExecutionHistory> list, final Table table) {
        super(list, table);
    }

    @Override
    protected void createTableColumns() {
        final String ID = "ID";
        addColumnDataFunction(getTable().addColumn("        Host", "Identifier"), e -> e.getHost().getId());
        TableColumn col = getTable().addColumn("StartTime", SECONDS).setFormat(TIME_FORMAT);
        addColumnDataFunction(col, ExecutionHistory::getStartTime);
        col = getTable().addColumn("FinishTime", SECONDS).setFormat(TIME_FORMAT);
        addColumnDataFunction(col, ExecutionHistory::getStopTime);
    }

    public void createJSON(List<SpotInstance> vmList, DatacenterBroker broker) throws IOException {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        Map<Long, Object> executionHistoryJSON = new HashMap<>();

        for (SpotInstance vm : vmList) {

            vm.calculateAverageInterruptionTime();

            if (vm.getExecutionHistory().size() > 1) {

                ArrayList<Map<String, Object>> historyList = new ArrayList<>();

                for (ExecutionHistory history : vm.getExecutionHistory()) {
                    HashMap<String, Object> entry = new HashMap<>();
                    entry.put("Host", history.getHost().getId());
                    entry.put("StartTime", history.getStartTime());
                    entry.put("StopTime", history.getStopTime());

                    historyList.add(entry);
                }
                executionHistoryJSON.put(vm.getId(), historyList);
            }
        }

        Writer writer = Files.newBufferedWriter(Paths.get( "executionhistory_" + broker.getName()+ ".json"));

        gson.toJson(executionHistoryJSON, writer);

        writer.close();
    }

}
