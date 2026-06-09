package org.jetbrains.teamcity.incremental;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.artifact.versioning.VersionRange;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Exclusion;
import org.apache.maven.model.Model;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.dependency.graph.DependencyNode;
import org.apache.maven.shared.dependency.graph.traversal.DependencyNodeVisitor;
import org.jetbrains.teamcity.AssemblePluginMojo;
import org.jetbrains.teamcity.BasePluginTestCase;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class MavenIncrementalInputsCollectorTest extends BasePluginTestCase {
    private final IncrementalAssembleCore core = new IncrementalAssembleCore();

    @Test
    public void outputMissStopsBeforeFileSnapshots() throws Exception {
        MavenProject project = simpleProject();
        FailingFileSnapshotter snapshotter = new FailingFileSnapshotter();
        MavenIncrementalInputsCollector collector = simpleCollector(project, snapshotter);
        IncrementalState previous = previousState(project, Collections.<InputState>emptyList(), Collections.<OutputState>emptyList());

        IncrementalCheckResult result = collector.checkCheapState(previous);

        assertThat(result.isComplete()).isTrue();
        assertThat(result.isUpToDate()).isFalse();
        assertThat(result.getReason()).isEqualTo("no previous outputs");
        assertThat(snapshotter.getCalls()).isEqualTo(0);
    }

    @Test
    public void dependencyTreeMissStopsBeforeFileSnapshots() throws Exception {
        MavenProject project = simpleProject();
        FailingFileSnapshotter snapshotter = new FailingFileSnapshotter();
        MavenIncrementalInputsCollector collector = simpleCollector(project, snapshotter);
        DependencyTreeInputBuilder treeInputBuilder = new DependencyTreeInputBuilder();
        InputState previousTree = treeInputBuilder.buildTreeInput(node("root", "1.0-SNAPSHOT", node("direct", "1.0")));
        Path output = Files.createTempFile("incremental-tree-output", ".zip");
        IncrementalState previous = previousState(
                project,
                Collections.singletonList(previousTree),
                Collections.singletonList(outputState(output))
        );

        IncrementalCheckResult result = collector.checkCurrentState(
                previous,
                node("root", "1.0-SNAPSHOT", node("direct", "1.1"))
        );

        assertThat(result.isComplete()).isTrue();
        assertThat(result.isUpToDate()).isFalse();
        assertThat(result.getReason()).contains("DEPENDENCY_TREE|runtime").contains("direct").contains("1.0").contains("1.1");
        assertThat(snapshotter.getCalls()).isEqualTo(0);
    }

    @Test
    public void warPackagingDoesNotTrackCurrentProjectArtifact() throws Exception {
        MavenSession session = initMavenSession("unit/module-war", "module-agent");
        MavenProject project = session.getCurrentProject();
        Path warArtifact = Paths.get(project.getBuild().getDirectory(), "module-war-1.1.war");
        Files.createDirectories(warArtifact.getParent());
        Files.writeString(warArtifact, "same-war-content");
        project.getArtifact().setFile(warArtifact.toFile());

        MojoExecution execution = rule.newMojoExecution("build");
        AssemblePluginMojo mojo = (AssemblePluginMojo) rule.lookupConfiguredMojo(session, execution);
        MavenIncrementalInputsCollector collector = new MavenIncrementalInputsCollector(
                project,
                session,
                Paths.get(project.getBuild().getDirectory(), "teamcity"),
                Paths.get(project.getBuild().getOutputDirectory()).toFile(),
                mojo.getAgent(),
                mojo.getServer(),
                execution,
                false,
                null,
                null,
                null,
                new FileSnapshotter(),
                new IncrementalStateStore()
        );

        IncrementalState state = collector.collectCurrentState();
        InputState projectArtifact = findInput(state, "project.artifact");

        assertThat(projectArtifact).isNotNull();
        assertThat(projectArtifact.getKind()).isEqualTo("FILE_TREE");
        assertThat(projectArtifact.getPath()).isNull();
        assertThat(projectArtifact.isExists()).isFalse();
    }

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

    private MavenIncrementalInputsCollector simpleCollector(MavenProject project, FileSnapshotter snapshotter) throws Exception {
        Path workDirectory = Files.createTempDirectory("incremental-collector");
        return new MavenIncrementalInputsCollector(
                project,
                null,
                workDirectory,
                workDirectory.resolve("classes").toFile(),
                null,
                null,
                null,
                false,
                null,
                null,
                null,
                snapshotter,
                new IncrementalStateStore()
        );
    }

    private IncrementalState previousState(MavenProject project, List<InputState> inputs, List<OutputState> outputs) {
        IncrementalState state = new IncrementalState();
        state.setConfigStamp(new IncrementalConfigStampBuilder(
                project,
                null,
                null,
                null,
                false,
                null,
                null,
                null,
                null
        ).build());
        state.setInputs(inputs);
        state.setLatestInputTs(core.calculateLatestInputTs(inputs));
        state.setInputFingerprint(core.buildInputFingerprint(inputs));
        state.setOutputs(outputs);
        return state;
    }

    private OutputState outputState(Path path) throws Exception {
        OutputState state = new OutputState();
        state.setType("zip");
        state.setClassifier("teamcity-plugin");
        state.setPath(path);
        state.setLastModified(Files.getLastModifiedTime(path).toMillis());
        return state;
    }

    private MavenProject simpleProject() {
        Model model = new Model();
        model.setGroupId("org.example");
        model.setArtifactId("app");
        model.setVersion("1.0-SNAPSHOT");
        return new MavenProject(model);
    }

    private static TestNode node(String artifactId, String version, TestNode... children) {
        return new TestNode(artifact(artifactId, version), children);
    }

    private static Artifact artifact(String artifactId, String version) {
        return new DefaultArtifact(
                "org.example",
                artifactId,
                VersionRange.createFromVersion(version),
                "runtime",
                "jar",
                null,
                new DefaultArtifactHandler("jar")
        );
    }

    private static class FailingFileSnapshotter extends FileSnapshotter {
        private int calls;

        @Override
        public FileSnapshot snapshotPath(Path path) throws IOException {
            calls++;
            throw new AssertionError("File snapshot should not be calculated before the first incremental miss");
        }

        @Override
        public FileSnapshot snapshotToolInput(Path path, String projectArtifactFileName) throws IOException {
            calls++;
            throw new AssertionError("Tool snapshot should not be calculated before the first incremental miss");
        }

        private int getCalls() {
            return calls;
        }
    }

    private static class TestNode implements DependencyNode {
        private final Artifact artifact;
        private final List<DependencyNode> children;
        private DependencyNode parent;

        private TestNode(Artifact artifact, TestNode... children) {
            this.artifact = artifact;
            this.children = Collections.unmodifiableList(Arrays.<DependencyNode>asList(children));
            int i;
            for (i = 0; i < children.length; i++) {
                children[i].parent = this;
            }
        }

        @Override
        public Artifact getArtifact() {
            return artifact;
        }

        @Override
        public List<DependencyNode> getChildren() {
            return children;
        }

        @Override
        public boolean accept(DependencyNodeVisitor visitor) {
            if (!visitor.visit(this)) {
                return false;
            }
            int i;
            for (i = 0; i < children.size(); i++) {
                children.get(i).accept(visitor);
            }
            return visitor.endVisit(this);
        }

        @Override
        public DependencyNode getParent() {
            return parent;
        }

        @Override
        public String getPremanagedVersion() {
            return null;
        }

        @Override
        public String getPremanagedScope() {
            return null;
        }

        @Override
        public String getVersionConstraint() {
            return null;
        }

        @Override
        public String toNodeString() {
            return artifact.toString();
        }

        @Override
        public Boolean getOptional() {
            return Boolean.FALSE;
        }

        @Override
        public List<Exclusion> getExclusions() {
            return Collections.emptyList();
        }
    }
}
