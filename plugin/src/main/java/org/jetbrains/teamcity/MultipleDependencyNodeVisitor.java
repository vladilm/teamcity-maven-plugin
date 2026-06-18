package org.jetbrains.teamcity;

import org.apache.maven.shared.dependency.graph.DependencyNode;
import org.apache.maven.shared.dependency.graph.traversal.DependencyNodeVisitor;

import java.util.List;

public class MultipleDependencyNodeVisitor implements DependencyNodeVisitor {
    private final List<DependencyNodeVisitor> visitors;

    public MultipleDependencyNodeVisitor(List<DependencyNodeVisitor> visitors) {
        this.visitors = visitors;
    }

    @Override
    public boolean visit(DependencyNode node) {
        boolean result = true;
        for (DependencyNodeVisitor visitor : visitors) {
            result = visitor.visit(node) && result;
        }
        return result;
    }

    @Override
    public boolean endVisit(DependencyNode node) {
        boolean result = true;
        for (DependencyNodeVisitor visitor : visitors) {
            result = visitor.endVisit(node) && result;
        }
        return result;
    }
}
