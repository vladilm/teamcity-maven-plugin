package org.jetbrains.teamcity.agent;

import java.nio.file.Path;
import java.util.List;

/**
 * dependency or artifact or file
 */
public interface PathEntry {
    String getName();
    List<Path> resolve();

    default boolean isReactorProject() {
        return false;
    }

    PathEntry cloneWithRoot(Path base);
}
