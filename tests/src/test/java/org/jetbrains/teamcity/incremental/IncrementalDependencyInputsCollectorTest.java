package org.jetbrains.teamcity.incremental;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.artifact.versioning.VersionRange;
import org.apache.maven.model.Exclusion;
import org.apache.maven.shared.dependency.graph.DependencyNode;
import org.apache.maven.shared.dependency.graph.traversal.DependencyNodeVisitor;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class IncrementalDependencyInputsCollectorTest {
    private final IncrementalDependencyInputsCollector collector = new IncrementalDependencyInputsCollector();

    @Test
    public void releaseNodeStopsTraversalAtReleaseBoundary() {
        TestNode first = node("root", "1.0-SNAPSHOT",
                node("release-lib", "1.0",
                        node("old-transitive", "1.0")));
        TestNode second = node("root", "1.0-SNAPSHOT",
                node("release-lib", "1.0",
                        node("new-transitive", "2.0")));

        DependencyInputs firstInput = collector.collect(first);
        DependencyInputs secondInput = collector.collect(second);

        assertThat(secondInput.getTreeInput().getDetails()).isEqualTo(firstInput.getTreeInput().getDetails());
        assertThat(secondInput.getTreeInput().getCount()).isEqualTo(2L);
        assertThat(secondInput.getArtifacts())
                .extracting(Artifact::getArtifactId)
                .containsExactly("release-lib");
    }

    @Test
    public void releaseVersionChangeChangesTreeFingerprint() {
        TestNode first = node("root", "1.0-SNAPSHOT", node("release-lib", "1.0"));
        TestNode second = node("root", "1.0-SNAPSHOT", node("release-lib", "1.1"));

        assertThat(collector.collect(second).getTreeInput().getDetails())
                .isNotEqualTo(collector.collect(first).getTreeInput().getDetails());
    }

    @Test
    public void snapshotNodeTraversesTransitiveDependencies() {
        TestNode first = node("root", "1.0-SNAPSHOT",
                node("snapshot-lib", "1.0-SNAPSHOT",
                        node("old-transitive", "1.0")));
        TestNode second = node("root", "1.0-SNAPSHOT",
                node("snapshot-lib", "1.0-SNAPSHOT",
                        node("new-transitive", "1.0")));

        DependencyInputs firstInput = collector.collect(first);
        DependencyInputs secondInput = collector.collect(second);

        assertThat(secondInput.getTreeInput().getDetails()).isNotEqualTo(firstInput.getTreeInput().getDetails());
        assertThat(secondInput.getTreeInput().getCount()).isEqualTo(3L);
        assertThat(secondInput.getArtifacts())
                .extracting(Artifact::getArtifactId)
                .containsExactly("snapshot-lib", "new-transitive");
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
