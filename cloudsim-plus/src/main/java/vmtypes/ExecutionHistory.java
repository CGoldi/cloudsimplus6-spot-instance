package vmtypes;

import org.cloudbus.cloudsim.hosts.Host;


/**
 * To calculate the interruption time of Spot Instances a complete history of the start time and stop time of
 * each instance is saved
 */

public class ExecutionHistory {

    /**
     * Start time of the VM execution
     */
    private double startTime;

    /**
     * Stop time of the VM execution
     */
    private double stopTime;

    /**
     * the {@link Host} on which the VM was running
     */
    private Host host;

    public double getStartTime() {
        return startTime;
    }

    public void setStartTime(double startTime) {
        this.startTime = startTime;
    }

    public double getStopTime() {
        return stopTime;
    }

    public void setStopTime(double stopTime) {
        this.stopTime = stopTime;
    }

    public Host getHost() {
        return host;
    }

    public void setHost(Host host) {
        this.host = host;
    }
}
