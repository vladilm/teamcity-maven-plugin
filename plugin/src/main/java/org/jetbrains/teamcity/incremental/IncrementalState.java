package org.jetbrains.teamcity.incremental;

import org.jetbrains.teamcity.agent.ResultArtifact;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class IncrementalState {
    private String configStamp;
    private List<InputState> inputs;
    private List<OutputState> outputs;
    private long latestInputTs;
    private String inputFingerprint;

    public IncrementalState withOutputs(List<ResultArtifact> artifacts) {
        List<OutputState> outputStates = new ArrayList<OutputState>();
        if (artifacts != null) {
            int i;
            for (i = 0; i < artifacts.size(); i++) {
                ResultArtifact artifact = artifacts.get(i);
                OutputState state = new OutputState();
                state.setType(artifact.getType());
                state.setClassifier(artifact.getClassifier());
                state.setPath(artifact.getFile());
                state.setLastModified(readLastModified(artifact.getFile()));
                outputStates.add(state);
            }
        }
        this.outputs = outputStates;
        return this;
    }

    public String getConfigStamp() {
        return configStamp;
    }

    public void setConfigStamp(String configStamp) {
        this.configStamp = configStamp;
    }

    public List<InputState> getInputs() {
        return inputs;
    }

    public void setInputs(List<InputState> inputs) {
        this.inputs = inputs;
    }

    public List<OutputState> getOutputs() {
        return outputs;
    }

    public void setOutputs(List<OutputState> outputs) {
        this.outputs = outputs;
    }

    public long getLatestInputTs() {
        return latestInputTs;
    }

    public void setLatestInputTs(long latestInputTs) {
        this.latestInputTs = latestInputTs;
    }

    public String getInputFingerprint() {
        return inputFingerprint;
    }

    public void setInputFingerprint(String inputFingerprint) {
        this.inputFingerprint = inputFingerprint;
    }

    private static long readLastModified(Path path) {
        if (path == null) {
            return 0L;
        }
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }
}
