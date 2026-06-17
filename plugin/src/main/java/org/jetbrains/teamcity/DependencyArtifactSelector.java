package org.jetbrains.teamcity;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.resolver.filter.ArtifactFilter;
import org.apache.maven.shared.artifact.filter.StrictPatternExcludesArtifactFilter;
import org.apache.maven.shared.artifact.filter.StrictPatternIncludesArtifactFilter;
import org.apache.maven.shared.dependency.graph.DependencyNode;
import org.apache.maven.shared.dependency.graph.filter.AndDependencyNodeFilter;
import org.apache.maven.shared.dependency.graph.filter.ArtifactDependencyNodeFilter;
import org.apache.maven.shared.dependency.graph.filter.DependencyNodeFilter;
import org.apache.maven.shared.dependency.graph.internal.ConflictData;
import org.apache.maven.shared.dependency.graph.traversal.CollectingDependencyNodeVisitor;
import org.apache.maven.shared.dependency.graph.traversal.DependencyNodeVisitor;
import org.apache.maven.shared.dependency.graph.traversal.FilteringDependencyNodeVisitor;
import org.jetbrains.teamcity.agent.AgentPluginWorkflow;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.stream.Collectors;

import static org.jetbrains.teamcity.ServerPluginWorkflow.TEAMCITY_PLUGIN_CLASSIFIER;

public class DependencyArtifactSelector {
    public List<Artifact> getDependencyNodeList(DependencyNode rootNode, String spec, List<String> exclusions) {
        return getDependencyNodeList(rootNode, spec, exclusions, null);
    }

    public List<Artifact> getDependencyNodeList(DependencyNode rootNode, String spec, List<String> exclusions, DependencyNodeVisitor reportingVisitor) {
        List<DependencyNode> nodes;
        if (Arrays.asList("*", ".").contains(spec)) {
            nodes = Collections.singletonList(rootNode);
        } else {
            List<String> patterns = Arrays.asList(spec.split(","));
            nodes = collectNodes(rootNode, new StrictPatternIncludesArtifactFilter(patterns));
        }

        CollectingDependencyNodeVisitor transitiveCollectingVisitor = new CollectingDependencyNodeVisitor();
        DependencyNodeVisitor collectingVisitor = transitiveCollectingVisitor;
        if (reportingVisitor != null) {
            collectingVisitor = new MultipleDependencyNodeVisitor(Arrays.asList(transitiveCollectingVisitor, reportingVisitor));
        }
        DependencyNodeFilter exclusionFilter = new ArtifactDependencyNodeFilter(new StrictPatternExcludesArtifactFilter(exclusions));
        AndDependencyNodeFilter andDependencyNodeFilter = new AndDependencyNodeFilter(
                exclusionFilter,
                it -> isParentClassifierIn(it, TEAMCITY_PLUGIN_CLASSIFIER, AgentPluginWorkflow.TEAMCITY_AGENT_PLUGIN_CLASSIFIER)
        );
        SkipFilteringDependencyNodeVisitor visitor = new SkipFilteringDependencyNodeVisitor(collectingVisitor, andDependencyNodeFilter);
        nodes.forEach(it -> it.accept(visitor));

        List<DependencyNode> collectedNodes = transitiveCollectingVisitor.getNodes();
        List<DependencyNode> result = new ArrayList<DependencyNode>();
        for (DependencyNode node : collectedNodes) {
            ConflictData conflictData = getPrivateField(node);
            if (conflictData != null && conflictData.getWinnerVersion() != null) {
                List<DependencyNode> substitutions = findSubstitutions(rootNode, node, conflictData.getWinnerVersion());
                CollectingDependencyNodeVisitor collector = new CollectingDependencyNodeVisitor();
                SkipFilteringDependencyNodeVisitor substitutionVisitor = new SkipFilteringDependencyNodeVisitor(collector, exclusionFilter);
                substitutions.forEach(it -> it.accept(substitutionVisitor));
                result.addAll(collector.getNodes());
            } else {
                result.add(node);
            }
        }
        return result.stream().map(DependencyNode::getArtifact).distinct().collect(Collectors.toList());
    }

    private boolean isParentClassifierIn(DependencyNode it, String serverClassifier, String agentClassifier) {
        if (it.getParent() != null && (Objects.equals(serverClassifier, it.getParent().getArtifact().getClassifier()) ||
                Objects.equals(agentClassifier, it.getParent().getArtifact().getClassifier()))) {
            return false;
        }
        return true;
    }

    private List<DependencyNode> findSubstitutions(DependencyNode rootNode, DependencyNode node, String winnerVersion) {
        Artifact artifact = node.getArtifact();
        StringJoiner pattern = new StringJoiner(":");
        pattern.add(artifact.getGroupId());
        pattern.add(artifact.getArtifactId());
        pattern.add(artifact.getType());
        pattern.add(winnerVersion);

        StrictPatternIncludesArtifactFilter filter = new StrictPatternIncludesArtifactFilter(Collections.singletonList(pattern.toString()));
        AndDependencyNodeFilter nodeFilter = new AndDependencyNodeFilter(new ArtifactDependencyNodeFilter(filter), node1 -> node1 != node);
        CollectingDependencyNodeVisitor collector = new CollectingDependencyNodeVisitor();
        rootNode.accept(new FilteringDependencyNodeVisitor(collector, nodeFilter));
        return collector.getNodes();
    }

    private List<DependencyNode> collectNodes(DependencyNode rootNode, ArtifactFilter artifactFilter) {
        CollectingDependencyNodeVisitor collectingVisitor = new CollectingDependencyNodeVisitor();
        DependencyNodeVisitor firstPassVisitor = new FilteringDependencyNodeVisitor(collectingVisitor,
                new ArtifactDependencyNodeFilter(artifactFilter));
        rootNode.accept(firstPassVisitor);
        return collectingVisitor.getNodes();
    }

    private ConflictData getPrivateField(DependencyNode node) {
        try {
            Field field = node.getClass().getDeclaredField("data");
            field.setAccessible(true);
            Object value = field.get(node);
            return (ConflictData) value;
        } catch (NoSuchFieldException | IllegalAccessException e) {
            return null;
        }
    }
}
