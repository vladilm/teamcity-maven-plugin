package org.jetbrains.teamcity.incremental;

import org.apache.maven.artifact.Artifact;

import java.util.Collections;
import java.util.List;

public class DependencyInputs {
    private final InputState treeInput;
    private final List<Artifact> artifacts;

    public DependencyInputs(InputState treeInput, List<Artifact> artifacts) {
        this.treeInput = treeInput;
        this.artifacts = artifacts == null ? Collections.<Artifact>emptyList() : artifacts;
    }

    public InputState getTreeInput() {
        return treeInput;
    }

    public List<Artifact> getArtifacts() {
        return artifacts;
    }
}
