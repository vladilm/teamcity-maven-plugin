package org.jetbrains.teamcity.incremental;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.shared.dependency.graph.DependencyNode;
import org.apache.maven.shared.dependency.graph.traversal.DependencyNodeVisitor;
import org.jetbrains.teamcity.DependencyArtifactSelector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class IncrementalDependencyInputsCollector {
    private static final String KIND_DEPENDENCY_TREE = "DEPENDENCY_TREE";
    private static final String KEY_RUNTIME = "runtime";

    private final DependencyArtifactSelector artifactSelector;

    public IncrementalDependencyInputsCollector() {
        this(new DependencyArtifactSelector());
    }

    IncrementalDependencyInputsCollector(DependencyArtifactSelector artifactSelector) {
        this.artifactSelector = artifactSelector;
    }

    public DependencyInputs collect(DependencyNode rootNode) {
        FingerprintVisitor visitor = new FingerprintVisitor();
        List<Artifact> artifacts = Collections.emptyList();
        if (rootNode != null) {
            artifacts = artifactSelector.getDependencyNodeList(rootNode, "*", Collections.<String>emptyList(), visitor);
        }
        return new DependencyInputs(buildTreeInput(rootNode, visitor), removeRootArtifact(rootNode, artifacts));
    }

    private InputState buildTreeInput(DependencyNode rootNode, FingerprintVisitor visitor) {
        InputState state = new InputState();
        state.setKind(KIND_DEPENDENCY_TREE);
        state.setKey(KEY_RUNTIME);
        state.setExists(rootNode != null);
        state.setDetails(visitor.getFingerprint());
        state.setCount(visitor.getCount());
        state.setUsesTimestamp(false);
        return state;
    }

    private List<Artifact> removeRootArtifact(DependencyNode rootNode, List<Artifact> artifacts) {
        if (rootNode == null || artifacts == null || artifacts.isEmpty()) {
            return Collections.emptyList();
        }

        Artifact rootArtifact = rootNode.getArtifact();
        List<Artifact> result = new ArrayList<Artifact>();
        for (Artifact artifact : artifacts) {
            if (artifact != rootArtifact) {
                result.add(artifact);
            }
        }
        return result;
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

    private class FingerprintVisitor implements DependencyNodeVisitor {
        private final StringBuilder fingerprint = new StringBuilder();
        private final List<Frame> stack = new ArrayList<Frame>();
        private long count;

        @Override
        public boolean visit(DependencyNode node) {
            Frame parent = stack.isEmpty() ? null : stack.get(stack.size() - 1);
            Frame frame = createFrame(node, parent);
            appendFingerprint(node, frame);
            count++;

            if (isReleaseBoundary(node, frame.depth)) {
                fingerprint.append("|release-boundary");
                return false;
            }

            stack.add(frame);
            return true;
        }

        @Override
        public boolean endVisit(DependencyNode node) {
            if (!stack.isEmpty() && stack.get(stack.size() - 1).node == node) {
                stack.remove(stack.size() - 1);
            }
            return true;
        }

        private Frame createFrame(DependencyNode node, Frame parent) {
            if (parent == null) {
                return new Frame(node, "0", 0);
            }

            String path = parent.path + "." + parent.nextChildIndex;
            parent.nextChildIndex++;
            return new Frame(node, path, parent.depth + 1);
        }

        private void appendFingerprint(DependencyNode node, Frame frame) {
            if (fingerprint.length() > 0) {
                fingerprint.append(';');
            }
            fingerprint.append(frame.path)
                    .append('=')
                    .append(toIdentity(node.getArtifact()))
                    .append("|optional=")
                    .append(node.getOptional());
        }

        private String getFingerprint() {
            return fingerprint.toString();
        }

        private long getCount() {
            return count;
        }
    }

    private static class Frame {
        private final DependencyNode node;
        private final String path;
        private final int depth;
        private int nextChildIndex;

        private Frame(DependencyNode node, String path, int depth) {
            this.node = node;
            this.path = path;
            this.depth = depth;
        }
    }
}
