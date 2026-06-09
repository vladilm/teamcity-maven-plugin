package org.jetbrains.teamcity.incremental;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class ReactorInputStateResolver {
    private static final String KIND_IMMUTABLE_ARTIFACT = "IMMUTABLE_ARTIFACT";
    private static final String KIND_EXTERNAL_SNAPSHOT_ARTIFACT = "EXTERNAL_SNAPSHOT_ARTIFACT";
    private static final String KIND_REACTOR_ARTIFACT_STATE = "REACTOR_ARTIFACT_STATE";

    private final MavenProject project;
    private final MavenSession session;
    private final FileSnapshotter fileSnapshotter;
    private final IncrementalStateStore stateStore;

    public ReactorInputStateResolver(MavenProject project,
                                     MavenSession session,
                                     FileSnapshotter fileSnapshotter,
                                     IncrementalStateStore stateStore) {
        this.project = project;
        this.session = session;
        this.fileSnapshotter = fileSnapshotter;
        this.stateStore = stateStore;
    }

    public InputState resolveDependencyInput(Artifact artifact) throws IOException {
        String gavtc = toGavtc(artifact);
        if (!isSnapshot(artifact.getVersion())) {
            InputState state = new InputState();
            state.setKind(KIND_IMMUTABLE_ARTIFACT);
            state.setKey(gavtc);
            state.setDetails(gavtc);
            state.setUsesTimestamp(false);
            return state;
        }

        MavenProject reactorProject = findReactorProject(artifact);
        if (reactorProject != null) {
            return createReactorArtifactState(gavtc, reactorProject);
        }
        return createExternalSnapshotArtifactState(gavtc, artifact.getFile() == null ? null : artifact.getFile().toPath());
    }

    public InputState resolveExtraInput(String key, Path path) throws IOException {
        MavenProject reactorProject = findReactorProjectForPath(path);
        if (reactorProject != null && !isCurrentProject(reactorProject)) {
            return createReactorArtifactState(buildReactorPathKey(key, reactorProject), reactorProject);
        }

        InputState state = new InputState();
        state.setKind("FILE_TREE");
        state.setKey(key);
        populateFileState(state, path);
        state.setUsesTimestamp(true);
        return state;
    }

    private InputState createExternalSnapshotArtifactState(String gavtc, Path artifactPath) throws IOException {
        InputState state = new InputState();
        state.setKind(KIND_EXTERNAL_SNAPSHOT_ARTIFACT);
        state.setKey(gavtc);
        populateFileState(state, artifactPath);
        state.setDetails(gavtc);
        state.setUsesTimestamp(true);
        return state;
    }

    private InputState createReactorArtifactState(String gavtc, MavenProject reactorProject) throws IOException {
        Path externalStateFile = reactorProject.getBuild() == null ? null : Paths.get(reactorProject.getBuild().getDirectory(), "teamcity", ".assemble-state.properties");
        IncrementalState externalState = stateStore.load(externalStateFile);
        if (externalState != null) {
            InputState state = new InputState();
            state.setKind(KIND_REACTOR_ARTIFACT_STATE);
            state.setKey(gavtc);
            state.setPath(externalStateFile);
            state.setExists(true);
            state.setDetails(emptyIfNull(externalState.getConfigStamp()) + "|" + emptyIfNull(externalState.getInputFingerprint()));
            state.setLastModified(externalState.getLatestInputTs());
            state.setUsesTimestamp(true);
            return state;
        }

        List<FileSnapshot> snapshots = new ArrayList<FileSnapshot>();
        snapshots.add(fileSnapshotter.snapshotPath(reactorProject.getFile() == null ? null : reactorProject.getFile().toPath()));
        snapshots.add(fileSnapshotter.snapshotPath(resolveChild(reactorProject.getBasedir().toPath(), "src/main")));
        snapshots.add(fileSnapshotter.snapshotPath(resolveChild(reactorProject.getBasedir().toPath(), "src/main/resources")));
        snapshots.add(fileSnapshotter.snapshotPath(resolveChild(reactorProject.getBasedir().toPath(), "src/main/webapp")));
        if (reactorProject.getBuild() != null) {
            snapshots.add(fileSnapshotter.snapshotPath(resolveChild(Paths.get(reactorProject.getBuild().getDirectory()), "classes")));
            snapshots.add(fileSnapshotter.snapshotPath(resolveChild(Paths.get(reactorProject.getBuild().getDirectory()), "generated-sources")));
            snapshots.add(fileSnapshotter.snapshotPath(resolveChild(Paths.get(reactorProject.getBuild().getDirectory()), "generated-resources")));
        }

        InputState state = new InputState();
        state.setKind(KIND_REACTOR_ARTIFACT_STATE);
        state.setKey(gavtc);
        state.setPath(reactorProject.getBasedir() == null ? null : reactorProject.getBasedir().toPath());
        state.setExists(true);
        state.setUsesTimestamp(true);

        StringBuilder details = new StringBuilder();
        long maxTs = 0L;
        long totalCount = 0L;
        long totalSize = 0L;
        int i;
        for (i = 0; i < snapshots.size(); i++) {
            FileSnapshot snapshot = snapshots.get(i);
            if (details.length() > 0) {
                details.append('|');
            }
            details.append(snapshot.describe());
            if (snapshot.getLastModified() > maxTs) {
                maxTs = snapshot.getLastModified();
            }
            totalCount += snapshot.getCount();
            totalSize += snapshot.getTotalSize();
        }
        state.setDetails(details.toString());
        state.setLastModified(maxTs);
        state.setCount(totalCount);
        state.setTotalSize(totalSize);
        return state;
    }

    private MavenProject findReactorProject(Artifact artifact) {
        if (artifact == null || session == null || session.getProjects() == null) {
            return null;
        }

        int i;
        for (i = 0; i < session.getProjects().size(); i++) {
            MavenProject candidate = session.getProjects().get(i);
            if (candidate == null || candidate.getArtifact() == null) {
                continue;
            }
            if (Objects.equals(candidate.getArtifact().getGroupId(), artifact.getGroupId())
                    && Objects.equals(candidate.getArtifact().getArtifactId(), artifact.getArtifactId())
                    && Objects.equals(candidate.getArtifact().getVersion(), artifact.getVersion())) {
                return candidate;
            }
        }
        return null;
    }

    private MavenProject findReactorProjectForPath(Path path) {
        if (path == null || session == null || session.getProjects() == null) {
            return null;
        }

        Path normalizedPath = path.normalize();
        int i;
        for (i = 0; i < session.getProjects().size(); i++) {
            MavenProject candidate = session.getProjects().get(i);
            if (candidate == null || candidate.getBuild() == null || candidate.getBuild().getDirectory() == null) {
                continue;
            }

            Path buildDirectory = Paths.get(candidate.getBuild().getDirectory()).normalize();
            if (normalizedPath.startsWith(buildDirectory)) {
                return candidate;
            }
        }
        return null;
    }

    private String buildReactorPathKey(String key, MavenProject reactorProject) {
        StringBuilder builder = new StringBuilder();
        builder.append(key).append('|');
        builder.append(emptyIfNull(reactorProject.getGroupId())).append(':');
        builder.append(emptyIfNull(reactorProject.getArtifactId())).append(':');
        builder.append(emptyIfNull(reactorProject.getVersion()));
        return builder.toString();
    }

    private boolean isCurrentProject(MavenProject reactorProject) {
        if (reactorProject == null) {
            return false;
        }
        if (project == reactorProject) {
            return true;
        }
        if (project.getGroupId() != null && project.getArtifactId() != null && project.getVersion() != null &&
                project.getGroupId().equals(reactorProject.getGroupId()) &&
                project.getArtifactId().equals(reactorProject.getArtifactId()) &&
                project.getVersion().equals(reactorProject.getVersion())) {
            return true;
        }
        return false;
    }

    private void populateFileState(InputState state, Path path) throws IOException {
        FileSnapshot snapshot = fileSnapshotter.snapshotPath(path);
        state.setPath(snapshot.getPath());
        state.setExists(snapshot.isExists());
        state.setLastModified(snapshot.getLastModified());
        state.setCount(snapshot.getCount());
        state.setTotalSize(snapshot.getTotalSize());
        state.setDetails(snapshot.describe());
    }

    private String toGavtc(Artifact artifact) {
        StringBuilder builder = new StringBuilder();
        builder.append(emptyIfNull(artifact.getGroupId())).append(':');
        builder.append(emptyIfNull(artifact.getArtifactId())).append(':');
        builder.append(emptyIfNull(artifact.getType())).append(':');
        builder.append(emptyIfNull(artifact.getClassifier())).append(':');
        builder.append(emptyIfNull(artifact.getVersion()));
        return builder.toString();
    }

    private boolean isSnapshot(String version) {
        return version != null && version.contains("SNAPSHOT");
    }

    private Path resolveChild(Path parent, String child) {
        if (parent == null) {
            return null;
        }
        return parent.resolve(child);
    }

    private static String emptyIfNull(String value) {
        return value == null ? "" : value;
    }
}
