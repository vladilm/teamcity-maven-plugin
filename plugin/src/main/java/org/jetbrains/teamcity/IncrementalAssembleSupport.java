package org.jetbrains.teamcity;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.project.MavenProject;
import org.jetbrains.teamcity.agent.ResultArtifact;
import org.jetbrains.teamcity.incremental.FileSnapshotter;
import org.jetbrains.teamcity.incremental.IncrementalAssembleCore;
import org.jetbrains.teamcity.incremental.IncrementalState;
import org.jetbrains.teamcity.incremental.IncrementalStateStore;
import org.jetbrains.teamcity.incremental.MavenIncrementalInputsCollector;
import org.jetbrains.teamcity.incremental.OutputState;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class IncrementalAssembleSupport {
    private final IncrementalAssembleCore core;
    private final IncrementalStateStore stateStore;
    private final MavenIncrementalInputsCollector inputsCollector;

    public IncrementalAssembleSupport(MavenProject project,
                                      MavenSession session,
                                      Path workDirectory,
                                      java.io.File projectBuildOutputDirectory,
                                      Agent agent,
                                      Server server,
                                      MojoExecution execution,
                                      boolean createIdeaArtifacts,
                                      String includes,
                                      String excludes,
                                      String ignoreExtraFilesIn) {
        this.core = new IncrementalAssembleCore();
        this.stateStore = new IncrementalStateStore(workDirectory.resolve(".assemble-state.properties"));
        this.inputsCollector = new MavenIncrementalInputsCollector(
                project,
                session,
                workDirectory,
                projectBuildOutputDirectory,
                agent,
                server,
                execution,
                createIdeaArtifacts,
                includes,
                excludes,
                ignoreExtraFilesIn,
                new FileSnapshotter(),
                new IncrementalStateStore()
        );
    }

    public IncrementalState collectCurrentState() throws IOException {
        return inputsCollector.collectCurrentState();
    }

    public IncrementalState loadState() throws IOException {
        return stateStore.load();
    }

    public boolean isUpToDate(IncrementalState previous, IncrementalState current) {
        return core.isUpToDate(previous, current);
    }

    public String describeDifference(IncrementalState previous, IncrementalState current) {
        return core.describeDifference(previous, current);
    }

    public void saveState(IncrementalState state) throws IOException {
        stateStore.save(state);
    }

    public List<ResultArtifact> restoreAttachedArtifacts(IncrementalState state) {
        List<ResultArtifact> artifacts = new ArrayList<ResultArtifact>();
        if (state == null || state.getOutputs() == null) {
            return artifacts;
        }

        int i;
        for (i = 0; i < state.getOutputs().size(); i++) {
            OutputState output = state.getOutputs().get(i);
            artifacts.add(new ResultArtifact(output.getType(), output.getClassifier(), output.getPath(), null));
        }
        return artifacts;
    }
}
