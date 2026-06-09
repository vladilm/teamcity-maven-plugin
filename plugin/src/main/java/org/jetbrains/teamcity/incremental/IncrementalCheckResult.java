package org.jetbrains.teamcity.incremental;

public class IncrementalCheckResult {
    private final boolean complete;
    private final boolean upToDate;
    private final String reason;
    private final IncrementalState currentState;

    private IncrementalCheckResult(boolean complete, boolean upToDate, String reason, IncrementalState currentState) {
        this.complete = complete;
        this.upToDate = upToDate;
        this.reason = reason;
        this.currentState = currentState;
    }

    public static IncrementalCheckResult continueCheck() {
        return new IncrementalCheckResult(false, false, null, null);
    }

    public static IncrementalCheckResult upToDate(IncrementalState currentState) {
        return new IncrementalCheckResult(true, true, null, currentState);
    }

    public static IncrementalCheckResult miss(String reason) {
        return new IncrementalCheckResult(true, false, reason, null);
    }

    public boolean isComplete() {
        return complete;
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
