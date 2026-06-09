package org.jetbrains.teamcity.incremental;

public class IncrementalCheckResult {
    private final boolean upToDate;
    private final String reason;
    private final IncrementalState currentState;

    private IncrementalCheckResult(boolean upToDate, String reason, IncrementalState currentState) {
        this.upToDate = upToDate;
        this.reason = reason;
        this.currentState = currentState;
    }

    public static IncrementalCheckResult upToDate(IncrementalState currentState) {
        return new IncrementalCheckResult(true, null, currentState);
    }

    public static IncrementalCheckResult miss(String reason) {
        return new IncrementalCheckResult(false, reason, null);
    }

    public boolean isUpToDate() {
        return upToDate;
    }

    public String getReason() {
        return reason;
    }

    public IncrementalState getCurrentState() {
        return currentState;
    }
}
