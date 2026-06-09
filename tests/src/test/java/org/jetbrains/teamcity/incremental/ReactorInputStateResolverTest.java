package org.jetbrains.teamcity.incremental;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;
import org.jetbrains.teamcity.BasePluginTestCase;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class ReactorInputStateResolverTest extends BasePluginTestCase {
    @Test
    public void prefersSavedReactorStateForReactorExtraInputs() throws Exception {
        MavenSession session = initMavenSession("unit/reactor-extra", "producer");
        MavenProject currentProject = session.getCurrentProject();
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

        ReactorInputStateResolver resolver = new ReactorInputStateResolver(
                currentProject,
                session,
                new FileSnapshotter(),
                new IncrementalStateStore()
        );

        Path extraSource = currentProject.getBasedir().toPath().resolve("producer/target/producer-1.1-SNAPSHOT-jar-with-dependencies.jar").normalize();
        InputState reactorInput = resolver.resolveExtraInput("agent.extras.0", extraSource);

        assertThat(reactorInput).isNotNull();
        assertThat(reactorInput.getKind()).isEqualTo("REACTOR_ARTIFACT_STATE");
        assertThat(reactorInput.getKey()).isEqualTo("agent.extras.0|jb.int:producer:1.1-SNAPSHOT");
        assertThat(reactorInput.getPath()).isEqualTo(reactorStateFile);
        assertThat(reactorInput.getDetails()).isEqualTo("reactor-config|reactor-inputs");
        assertThat(reactorInput.getLastModified()).isEqualTo(456L);
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
