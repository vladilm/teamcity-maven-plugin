package org.jetbrains.teamcity.incremental;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.shared.dependency.graph.DependencyNode;

import java.util.ArrayList;
import java.util.List;

public class DependencyTreeInputBuilder {
    public static final String KIND_DEPENDENCY_TREE = "DEPENDENCY_TREE";
    public static final String KEY_RUNTIME = "runtime";

    public InputState buildTreeInput(DependencyNode rootNode) {
        InputState state = new InputState();
        state.setKind(KIND_DEPENDENCY_TREE);
        state.setKey(KEY_RUNTIME);
        state.setExists(rootNode != null);
        state.setDetails(buildFingerprint(rootNode));
        state.setCount(countNodes(rootNode));
        state.setUsesTimestamp(false);
        return state;
    }

    public List<Artifact> collectDependencyArtifacts(DependencyNode rootNode) {
        List<Artifact> artifacts = new ArrayList<Artifact>();
        collectDependencyArtifacts(rootNode, 0, artifacts);
        return artifacts;
    }

    private String buildFingerprint(DependencyNode rootNode) {
        if (rootNode == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        appendFingerprint(rootNode, "0", 0, builder);
        return builder.toString();
    }

    private void appendFingerprint(DependencyNode node, String path, int depth, StringBuilder builder) {
        if (node == null) {
            return;
        }
        if (builder.length() > 0) {
            builder.append(';');
        }
        builder.append(path)
                .append('=')
                .append(toIdentity(node.getArtifact()))
                .append("|optional=")
                .append(node.getOptional());

        if (isReleaseBoundary(node, depth)) {
            builder.append("|release-boundary");
            return;
        }

        List<DependencyNode> children = node.getChildren();
        if (children == null) {
            return;
        }
        int i;
        for (i = 0; i < children.size(); i++) {
            appendFingerprint(children.get(i), path + "." + i, depth + 1, builder);
        }
    }

    private long countNodes(DependencyNode rootNode) {
        if (rootNode == null) {
            return 0L;
        }
        CountingVisitor visitor = new CountingVisitor();
        countNodes(rootNode, 0, visitor);
        return visitor.count;
    }

    private void countNodes(DependencyNode node, int depth, CountingVisitor visitor) {
        if (node == null) {
            return;
        }
        visitor.count++;
        if (isReleaseBoundary(node, depth)) {
            return;
        }
        List<DependencyNode> children = node.getChildren();
        if (children == null) {
            return;
        }
        int i;
        for (i = 0; i < children.size(); i++) {
            countNodes(children.get(i), depth + 1, visitor);
        }
    }

    private void collectDependencyArtifacts(DependencyNode node, int depth, List<Artifact> artifacts) {
        if (node == null) {
            return;
        }
        Artifact artifact = node.getArtifact();
        if (depth > 0 && artifact != null) {
            artifacts.add(artifact);
        }
        if (isReleaseBoundary(node, depth)) {
            return;
        }
        List<DependencyNode> children = node.getChildren();
        if (children == null) {
            return;
        }
        int i;
        for (i = 0; i < children.size(); i++) {
            collectDependencyArtifacts(children.get(i), depth + 1, artifacts);
        }
    }

    private boolean isReleaseBoundary(DependencyNode node, int depth) {
        if (depth == 0 || node == null || node.getArtifact() == null) {
            return false;
        }
        return !isSnapshot(node.getArtifact().getVersion());
    }

    private boolean isSnapshot(String version) {
        return version != null && version.contains("SNAPSHOT");
    }

    private String toIdentity(Artifact artifact) {
        if (artifact == null) {
            return "";
        }
        return emptyIfNull(artifact.getGroupId())
                + ":" + emptyIfNull(artifact.getArtifactId())
                + ":" + emptyIfNull(artifact.getType())
                + ":" + emptyIfNull(artifact.getClassifier())
                + ":" + emptyIfNull(artifact.getVersion())
                + ":scope=" + emptyIfNull(artifact.getScope());
    }

    private String emptyIfNull(String value) {
        return value == null ? "" : value;
    }

    private static class CountingVisitor {
        private long count;
    }
}
