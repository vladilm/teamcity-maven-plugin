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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
        IncrementalDependencyInputsCollector dependencyInputsCollector = new IncrementalDependencyInputsCollector();
        InputState previousTree = dependencyInputsCollector.collect(node("root", "1.0-SNAPSHOT", node("direct", "1.0"))).getTreeInput();
        Path output = Files.createTempFile("incremental-tree-output", ".zip");
        IncrementalState previous = previousState(
                project,
                Collections.singletonList(previousTree),
                Collections.singletonList(outputState(output))
        );

        IncrementalCheckResult result = collector.checkCurrentState(
                previous,
                dependencyInputsCollector.collect(node("root", "1.0-SNAPSHOT", node("direct", "1.1")))
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
        assertThat(reactorInput.getDetails()).isEqualTo("reactor-config|51dd99f9fcf8fb79df2854621c8e1599417756bb5c062d76419a387c2221fe79");
        assertThat(reactorInput.getLastModified()).isEqualTo(456L);
    }

    @Test
    public void dependencyInputsComeOnlyFromSuppliedRootNode() throws Exception {
        MavenProject project = simpleProject();
        Set<Artifact> artifacts = new LinkedHashSet<Artifact>();
        artifacts.add(artifact("compile-lib", "1.0", "compile"));
        artifacts.add(artifact("provided-lib", "1.0", "provided"));
        project.setArtifacts(artifacts);

        MavenIncrementalInputsCollector collector = simpleCollector(project, new FileSnapshotter());

        IncrementalState state = collector.collectCurrentState();

        assertThat(state.getInputFingerprint()).doesNotContain("compile-lib");
        assertThat(state.getInputFingerprint()).doesNotContain("provided-lib");

        IncrementalState stateWithRootNode = collector.collectCurrentState(
                new IncrementalDependencyInputsCollector().collect(node("root", "1.0-SNAPSHOT", node("compile-lib", "1.0")))
        );

        assertThat(stateWithRootNode.getInputFingerprint()).contains("compile-lib");
        assertThat(stateWithRootNode.getInputFingerprint()).doesNotContain("provided-lib");
    }

    @Test
    public void dependencyTreeIsTraversedOnceWhenDependencyInputsAreCollected() throws Exception {
        MavenProject project = simpleProject();
        TestNode direct = node("direct", "1.0-SNAPSHOT", node("transitive", "1.0"));
        TestNode root = node("root", "1.0-SNAPSHOT", direct);

        MavenIncrementalInputsCollector collector = simpleCollector(project, new FileSnapshotter());
        DependencyInputs dependencyInputs = new IncrementalDependencyInputsCollector().collect(root);

        collector.collectCurrentState(dependencyInputs);

        assertThat(root.getTraversalCalls()).isEqualTo(1);
        assertThat(direct.getTraversalCalls()).isEqualTo(1);
    }

    @Test
    public void dependencyInputsCanBeReusedBetweenCheckAndStateCollection() throws Exception {
        MavenProject project = simpleProject();
        TestNode direct = node("direct", "1.0-SNAPSHOT", node("transitive", "1.0"));
        TestNode root = node("root", "1.0-SNAPSHOT", direct);
        MavenIncrementalInputsCollector collector = simpleCollector(project, new FileSnapshotter());
        IncrementalDependencyInputsCollector dependencyInputsCollector = new IncrementalDependencyInputsCollector();
        DependencyInputs dependencyInputs = dependencyInputsCollector.collect(root);

        Path output = Files.createTempFile("incremental-tree-output", ".zip");
        InputState previousTree = dependencyInputsCollector.collect(node("root", "1.0-SNAPSHOT", node("direct", "0.9"))).getTreeInput();
        IncrementalState previous = previousState(
                project,
                Collections.singletonList(previousTree),
                Collections.singletonList(outputState(output))
        );

        collector.checkCurrentState(previous, dependencyInputs);
        collector.collectCurrentState(dependencyInputs);

        assertThat(root.getTraversalCalls()).isEqualTo(1);
        assertThat(direct.getTraversalCalls()).isEqualTo(1);
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
        return artifact(artifactId, version, "runtime");
    }

    private static Artifact artifact(String artifactId, String version, String scope) {
        return new DefaultArtifact(
                "org.example",
                artifactId,
                VersionRange.createFromVersion(version),
                scope,
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
        private int traversalCalls;

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
            traversalCalls++;
            return children;
        }

        @Override
        public boolean accept(DependencyNodeVisitor visitor) {
            traversalCalls++;
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

        private int getTraversalCalls() {
            return traversalCalls;
        }
    }
}
