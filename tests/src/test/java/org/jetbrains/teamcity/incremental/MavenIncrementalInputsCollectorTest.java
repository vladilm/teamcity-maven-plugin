package org.jetbrains.teamcity.incremental;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.project.MavenProject;
import org.jetbrains.teamcity.AssemblePluginMojo;
import org.jetbrains.teamcity.BasePluginTestCase;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class MavenIncrementalInputsCollectorTest extends BasePluginTestCase {
    @Test
    public void prefersSavedReactorStateForReactorExtraInputs() throws Exception {
        MavenSession session = initMavenSession("unit/reactor-extra", "producer");
        MojoExecution execution = rule.newMojoExecution("build");
        AssemblePluginMojo mojo = (AssemblePluginMojo) rule.lookupConfiguredMojo(session, execution);

        MavenProject producer = findProject(session, "producer");
        Path reactorStateFile = Paths.get(producer.getBuild().getDirectory(), "teamcity", ".assemble-state.properties");
        Files.createDirectories(reactorStateFile.getParent());

        IncrementalState reactorState = new IncrementalState();
        reactorState.setConfigStamp("reactor-config");
        reactorState.setInputFingerprint("reactor-inputs");
        reactorState.setLatestInputTs(456L);
        reactorState.setInputs(List.of());
        reactorState.setOutputs(List.of());
        new IncrementalStateStore(reactorStateFile).save(reactorState);

        MavenIncrementalInputsCollector collector = new MavenIncrementalInputsCollector(
                session.getCurrentProject(),
                session,
                Paths.get(session.getCurrentProject().getBuild().getDirectory(), "teamcity"),
                Paths.get(session.getCurrentProject().getBuild().getOutputDirectory()).toFile(),
                mojo.getAgent(),
                mojo.getServer(),
                execution,
                false,
                null,
                null,
                null,
                new FileSnapshotter(),
                new IncrementalStateStore(reactorStateFile)
        );

        IncrementalState state = collector.collectCurrentState();
        InputState reactorInput = findInput(state, "agent.extras.0|jb.int:producer:1.1-SNAPSHOT");

        assertThat(reactorInput).isNotNull();
        assertThat(reactorInput.getKind()).isEqualTo("REACTOR_ARTIFACT_STATE");
        assertThat(reactorInput.getPath()).isEqualTo(reactorStateFile);
        assertThat(reactorInput.getDetails()).isEqualTo("reactor-config|reactor-inputs");
        assertThat(reactorInput.getLastModified()).isEqualTo(456L);
    }

    private InputState findInput(IncrementalState state, String keyPrefix) {
        if (state == null || state.getInputs() == null) {
            return null;
        }

        int i;
        for (i = 0; i < state.getInputs().size(); i++) {
            InputState input = state.getInputs().get(i);
            if (input.getKey() != null && input.getKey().startsWith(keyPrefix)) {
                return input;
            }
        }
        return null;
    }

    private MavenProject findProject(MavenSession session, String artifactId) {
        int i;
        for (i = 0; i < session.getProjects().size(); i++) {
            MavenProject project = session.getProjects().get(i);
            if (artifactId.equals(project.getArtifactId())) {
                return project;
            }
        }
        return null;
    }
}
