package org.jetbrains.teamcity.incremental;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class IncrementalAssembleCore {
    public boolean isUpToDate(IncrementalState previous, IncrementalState current) {
        if (previous == null || current == null) {
            return false;
        }
        if (!Objects.equals(previous.getConfigStamp(), current.getConfigStamp())) {
            return false;
        }
        if (!Objects.equals(previous.getInputFingerprint(), current.getInputFingerprint())) {
            return false;
        }
        if (!sameInputs(previous.getInputs(), current.getInputs())) {
            return false;
        }
        if (previous.getOutputs() == null || previous.getOutputs().isEmpty()) {
            return false;
        }

        return describeOutputDifference(previous) == null;
    }

    public String describeOutputDifference(IncrementalState previous) {
        if (previous == null) {
            return "no previous state";
        }
        if (previous.getOutputs() == null || previous.getOutputs().isEmpty()) {
            return "no previous outputs";
        }
        int i;
        for (i = 0; i < previous.getOutputs().size(); i++) {
            OutputState output = previous.getOutputs().get(i);
            if (output.getPath() == null || !Files.exists(output.getPath())) {
                return "missing output " + output.getPath();
            }
            long outputTs = getLastModified(output.getPath());
            if (outputTs != output.getLastModified()) {
                return "output changed: " + output.getClassifier()
                        + " saved=" + output.getLastModified()
                        + " current=" + outputTs
                        + " path=" + output.getPath();
            }
        }
        return null;
    }

    public String describeDifference(IncrementalState previous, IncrementalState current) {
        if (previous == null) {
            return "no previous state";
        }
        if (current == null) {
            return "no current state";
        }
        if (!Objects.equals(previous.getConfigStamp(), current.getConfigStamp())) {
            return "config stamp changed";
        }
        if (!Objects.equals(previous.getInputFingerprint(), current.getInputFingerprint())) {
            return describeInputDifference(previous.getInputs(), current.getInputs());
        }
        if (!sameInputs(previous.getInputs(), current.getInputs())) {
            return describeInputDifference(previous.getInputs(), current.getInputs());
        }
        String outputDifference = describeOutputDifference(previous);
        if (outputDifference != null) {
            return outputDifference;
        }
        return "savedOutputTs=" + collectOutputTimestamps(previous);
    }

    public String buildInputFingerprint(List<InputState> inputs) {
        StringBuilder builder = new StringBuilder();
        if (inputs == null) {
            return "";
        }
        int i;
        for (i = 0; i < inputs.size(); i++) {
            if (builder.length() > 0) {
                builder.append('|');
            }
            builder.append(inputs.get(i).toFingerprint());
        }
        return builder.toString();
    }

    public long calculateLatestInputTs(List<InputState> inputs) {
        long maxTs = 0L;
        if (inputs == null) {
            return maxTs;
        }
        int i;
        for (i = 0; i < inputs.size(); i++) {
            InputState input = inputs.get(i);
            if (!input.isUsesTimestamp()) {
                continue;
            }
            if (input.getLastModified() > maxTs) {
                maxTs = input.getLastModified();
            }
        }
        return maxTs;
    }

    private boolean sameInputs(List<InputState> first, List<InputState> second) {
        List<InputState> safeFirst = first == null ? Collections.<InputState>emptyList() : first;
        List<InputState> safeSecond = second == null ? Collections.<InputState>emptyList() : second;
        if (safeFirst.size() != safeSecond.size()) {
            return false;
        }

        int i;
        for (i = 0; i < safeFirst.size(); i++) {
            if (!safeFirst.get(i).sameAs(safeSecond.get(i))) {
                return false;
            }
        }
        return true;
    }

    private String describeInputDifference(List<InputState> first, List<InputState> second) {
        List<InputState> safeFirst = first == null ? Collections.<InputState>emptyList() : first;
        List<InputState> safeSecond = second == null ? Collections.<InputState>emptyList() : second;
        if (safeFirst.size() != safeSecond.size()) {
            return "input count changed: " + safeFirst.size() + " -> " + safeSecond.size();
        }

        int i;
        for (i = 0; i < safeFirst.size(); i++) {
            InputState previous = safeFirst.get(i);
            InputState current = safeSecond.get(i);
            if (!previous.sameAs(current)) {
                return current.describeChangeFrom(previous);
            }
        }
        return "input fingerprint changed";
    }

    private String collectOutputTimestamps(IncrementalState state) {
        if (state == null || state.getOutputs() == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        int i;
        for (i = 0; i < state.getOutputs().size(); i++) {
            OutputState output = state.getOutputs().get(i);
            if (output.getPath() == null || !Files.exists(output.getPath())) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append('|');
            }
            builder.append(output.getClassifier()).append('=').append(getLastModified(output.getPath()));
        }
        return builder.toString();
    }

    private long getLastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }
}
