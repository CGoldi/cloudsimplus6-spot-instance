package vmtypes;

import org.cloudbus.cloudsim.resources.Pe;
import org.cloudsimplus.listeners.VmHostEventInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Class that Models Spot Virtual Machine instances
 */
public class SpotInstance extends DynamicVm {

    /**
     * The maximum bid price for a spot instance
     */
    private long maxBidPrice;

    /**
     * Specifies the behavior of Spot instances in case of an Interruption
     * <p>
     * {@link InterruptionBehavior}
     */
    private InterruptionBehavior interruptionBehavior = InterruptionBehavior.TERMINATE;

    /**
     * Minimum running time of an Vm before it can get interrupted
     */
    private double minimumRunningTime = 0;

    /**
     * Time the VM stays active before it gets terminated after an interruption
     */
    private double warningTime = 0;

    /**
     * The average time of Interruption of a single Spot instance
     */
    private double averageInterruptionTime = 0;

    /**
     * List of {@link ExecutionHistory} entries, shows the times the spot instance was active
     */
    private final List<ExecutionHistory> executionHistory = new ArrayList<>();

    /**
     * Time limit for the hibernation, defines how long a spot instance remains hibernated before it gets
     * terminated if it gets interrupted
     */
    private double hibernationTimeLimit = 0;

    private boolean priority = false;

    /**
     * Enumeration class for the interruption behavior
     */
    public enum InterruptionBehavior {
        TERMINATE, HIBERNATE
    }

    /**
     * Instantiates a SpotInstance
     *
     * @param mipsCapacity the mips capacity of each Vm {@link Pe}
     * @param numberOfPes  amount of {@link Pe} (CPU cores)
     */
    public SpotInstance(double mipsCapacity, long numberOfPes) {
        super(mipsCapacity, numberOfPes);
    }

    public SpotInstance(double mipsCapacity, long numberOfPes, boolean listener) {
        super(mipsCapacity, numberOfPes, listener);
        addOnHostDeallocationListener(this::updateExecutionHistory);
    }

    public SpotInstance(long id, double mipsCapacity, long numberOfPes, boolean listener) {
        super(id, mipsCapacity, numberOfPes, listener);
        addOnHostDeallocationListener(this::updateExecutionHistory);
    }

    public double getMinimumRunningTime() {
        return minimumRunningTime;
    }

    public void setMinimumRunningTime(double minimumRunningTime) {
        this.minimumRunningTime = minimumRunningTime;
    }

    public InterruptionBehavior getInterruptionBehavior() {
        return interruptionBehavior;
    }

    public void setInterruptionBehavior(InterruptionBehavior interruptionBehavior) {
        this.interruptionBehavior = interruptionBehavior;
    }

    public double getWarningTime() {
        return warningTime;
    }

    public void setWarningTime(double warningTime) {
        this.warningTime = warningTime;
    }

    public long getMaxBidPrice() {
        return maxBidPrice;
    }

    public void setMaxBidPrice(long maxBidPrice) {
        this.maxBidPrice = maxBidPrice;
    }

    public boolean getPriority() {
        return priority;
    }

    public void setPriority(boolean priority) {
        this.priority = priority;
    }

    public double getHibernationTimeLimit() {
        return hibernationTimeLimit;
    }

    public void setHibernationTimeLimit(double hibernationTimeLimit) {
        this.hibernationTimeLimit = hibernationTimeLimit;
    }

    public List<ExecutionHistory> getExecutionHistory() {
        return executionHistory;
    }

    @Override
    public String getType() {
        return "Spot";
    }

    /**
     * Calculates the average interruption time based on the execution history
     */
    public void calculateAverageInterruptionTime() {
        double totalInterruption = 0;
        int interruptionCount = 0;
        if (executionHistory.size() > 1) {
            for (int i = 0; i < executionHistory.size(); i++) {
                if (executionHistory.size() > i + 1) {
                    totalInterruption += executionHistory.get(i + 1).getStartTime() - executionHistory.get(i).getStopTime();
                    interruptionCount++;
                }
            }
        }
        if (interruptionCount != 0) {
            averageInterruptionTime = totalInterruption / interruptionCount;
        }
    }

    public double getAverageInterruptionTime() {
        return averageInterruptionTime;
    }

    /**
     * Deallocation Listener that updates the Execution history of a spot Instance,
     * necessary to calculate the interruption time of the instances if they get restarted
     *
     * @param vmHostEventInfo deallocation Listener event information
     */
    private void updateExecutionHistory(VmHostEventInfo vmHostEventInfo) {

        SpotInstance VmToDestroy = (SpotInstance) vmHostEventInfo.getVm();
        List<ExecutionHistory> history = VmToDestroy.getExecutionHistory();
        ExecutionHistory newEntry = new ExecutionHistory();
        newEntry.setStartTime(VmToDestroy.getStartTime());

        newEntry.setStopTime(VmToDestroy.getBroker().getSimulation().clock());
        newEntry.setHost(VmToDestroy.getHost());
        history.add(newEntry);
    }

}
