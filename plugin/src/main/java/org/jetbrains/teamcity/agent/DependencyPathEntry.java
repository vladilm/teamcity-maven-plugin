package org.jetbrains.teamcity.agent;

import lombok.Data;
import org.apache.maven.artifact.Artifact;
import org.jetbrains.teamcity.Jdk8Compat;

import java.nio.file.Path;
import java.util.List;

@Data
public class DependencyPathEntry implements PathEntry {
    private final Artifact artifact;
    private final boolean isReactorProject;

    private final String name;
    private final Path resolved;

    @Override
    public List<Path> resolve() {
        return Jdk8Compat.of(resolved);
    }

    @Override
    public PathEntry cloneWithRoot(Path base) {
        return new DependencyPathEntry(artifact, isReactorProject, name, resolved);
    }
}
