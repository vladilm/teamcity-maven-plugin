package org.jetbrains.teamcity.incremental;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.project.MavenProject;
import org.jetbrains.teamcity.Agent;
import org.jetbrains.teamcity.Server;
import org.jetbrains.teamcity.SourceDest;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MavenIncrementalInputsCollector {
    private static final String KIND_FILE_TREE = "FILE_TREE";

    private final MavenProject project;
    private final Path workDirectory;
    private final java.io.File projectBuildOutputDirectory;
    private final Agent agent;
    private final FileSnapshotter fileSnapshotter;
    private final IncrementalAssembleCore core;
    private final ReactorInputStateResolver reactorStateResolver;
    private final IncrementalConfigStampBuilder configStampBuilder;
    private final Server server;

    public MavenIncrementalInputsCollector(MavenProject project,
                                           MavenSession session,
                                           Path workDirectory,
                                           java.io.File projectBuildOutputDirectory,
                                           Agent agent,
                                           Server server,
                                           MojoExecution execution,
                                           String includes,
                                           String excludes,
                                           String ignoreExtraFilesIn,
                                           FileSnapshotter fileSnapshotter,
                                           IncrementalStateStore stateStore) {
        this(
                project,
                session,
                workDirectory,
                projectBuildOutputDirectory,
                agent,
                server,
                execution,
                includes,
                excludes,
                ignoreExtraFilesIn,
                null,
                fileSnapshotter,
                stateStore
        );
    }

    public MavenIncrementalInputsCollector(MavenProject project,
                                           MavenSession session,
                                           Path workDirectory,
                                           java.io.File projectBuildOutputDirectory,
                                           Agent agent,
                                           Server server,
                                           MojoExecution execution,
                                           String includes,
                                           String excludes,
                                           String ignoreExtraFilesIn,
                                           String incrementalSnapshotExcludes,
                                           FileSnapshotter fileSnapshotter,
                                           IncrementalStateStore stateStore) {
        this.project = project;
        this.workDirectory = workDirectory;
        this.projectBuildOutputDirectory = projectBuildOutputDirectory;
        this.agent = agent;
        this.server = server;
        this.fileSnapshotter = fileSnapshotter;
        this.core = new IncrementalAssembleCore();
        this.reactorStateResolver = new ReactorInputStateResolver(project, session, fileSnapshotter, stateStore);
        this.configStampBuilder = new IncrementalConfigStampBuilder(
                project,
                agent,
                server,
                execution,
                includes,
                excludes,
                ignoreExtraFilesIn,
                incrementalSnapshotExcludes
        );
    }

    public IncrementalState collectCurrentState() throws IOException {
        return collectCurrentState(null);
    }

    public IncrementalState collectCurrentState(DependencyInputs dependencyInputs) throws IOException {
        ListInputSink sink = new ListInputSink();
        collectDependencyInputs(sink, dependencyInputs);
        collectFileInputs(sink);
        return buildState(configStampBuilder.build(), sink.getInputs());
    }

    public IncrementalCheckResult checkCurrentState(IncrementalState previous) throws IOException {
        return checkCurrentState(previous, null);
    }

    public IncrementalCheckResult checkCurrentState(IncrementalState previous, DependencyInputs dependencyInputs) throws IOException {
        IncrementalCheckResult cheapResult = checkCheapState(previous);
        if (cheapResult.isComplete()) {
            return cheapResult;
        }

        CheckingInputSink sink = new CheckingInputSink(previous.getInputs());
        String currentConfigStamp = configStampBuilder.build();
        try {
            collectDependencyInputs(sink, dependencyInputs);
            collectFileInputs(sink);
        } catch (EarlyIncrementalMiss e) {
            return IncrementalCheckResult.miss(e.getMessage());
        }

        String removedInput = sink.findRemovedInput();
        if (removedInput != null) {
            return IncrementalCheckResult.miss("input removed: " + removedInput);
        }

        IncrementalState current = buildState(currentConfigStamp, sink.getInputs());
        if (core.isUpToDate(previous, current)) {
            return IncrementalCheckResult.upToDate(current);
        }
        return IncrementalCheckResult.miss(core.describeDifference(previous, current));
    }

    public IncrementalCheckResult checkCheapState(IncrementalState previous) {
        if (previous == null) {
            return IncrementalCheckResult.miss("no previous state");
        }

        String currentConfigStamp = configStampBuilder.build();
        if (!currentConfigStamp.equals(previous.getConfigStamp())) {
            return IncrementalCheckResult.miss("config stamp changed: previous="
                    + previous.getConfigStamp()
                    + " current="
                    + currentConfigStamp);
        }

        String outputDifference = core.describeOutputDifference(previous);
        if (outputDifference != null) {
            return IncrementalCheckResult.miss(outputDifference);
        }

        return IncrementalCheckResult.continueCheck();
    }

    private void collectFileInputs(InputSink sink) throws IOException {
        addFileTreeInput(sink, "project.pom", project.getFile() == null ? null : project.getFile().toPath());
        addFileTreeInput(sink, "project.output", projectBuildOutputDirectory == null ? null : projectBuildOutputDirectory.toPath());
        addFileTreeInput(sink, "project.artifact", getProjectArtifactPath());

        if (agent != null) {
            addFileTreeInput(sink, "agent.descriptor", getPath(agent.getDescriptor() == null ? null : agent.getDescriptor().getPath()));
            addExtrasInputs(sink, "agent.extras", agent.getExtras());
            if (agent.isTool()) {
                addToolUnpackedInput(sink, "agent.tool-unpacked", workDirectory.resolve("agent-unpacked").resolve(agent.getPluginName()));
            }
        }

        if (server != null) {
            addFileTreeInput(sink, "server.descriptor", getPath(server.getDescriptor() == null ? null : server.getDescriptor().getPath()));
            addFileTreeInput(sink, "server.kotlin-dsl", getPath(server.getKotlinDslDescriptorsPath()));
            addFileTreeInput(sink, "server.ui-schemas", getPath(server.getUiSchemasPath()));
            addStringPathInputs(sink, "server.buildServerResources", server.getBuildServerResources());
            addExtrasInputs(sink, "server.extras", server.getExtras());
        }

    }

    private IncrementalState buildState(String configStamp, List<InputState> inputs) {
        sortInputs(inputs);
        IncrementalState state = new IncrementalState();
        state.setConfigStamp(configStamp);
        state.setInputs(inputs);
        state.setLatestInputTs(core.calculateLatestInputTs(inputs));
        state.setInputFingerprint(core.buildInputFingerprint(inputs));
        state.setOutputs(new ArrayList<OutputState>());
        return state;
    }

    private void collectDependencyInputs(InputSink sink, DependencyInputs dependencyInputs) throws IOException {
        if (dependencyInputs != null) {
            sink.add(dependencyInputs.getTreeInput());
            addDependencyInputs(sink, dependencyInputs.getArtifacts());
        }
    }

    private void addDependencyInputs(InputSink sink, List<Artifact> artifacts) throws IOException {
        Map<String, InputState> uniqueInputs = new LinkedHashMap<String, InputState>();
        for (Artifact artifact : artifacts) {
            if (artifact == null) {
                continue;
            }

            InputState state = reactorStateResolver.resolveDependencyInput(artifact);
            uniqueInputs.put(state.identity(), state);
        }

        for (InputState state : uniqueInputs.values()) {
            sink.add(state);
        }
    }

    private void addStringPathInputs(InputSink sink, String prefix, List<String> values) throws IOException {
        if (values == null) {
            return;
        }
        int i;
        for (i = 0; i < values.size(); i++) {
            String value = values.get(i);
            Path path = toProjectRelativePath(value);
            addFileTreeInput(sink, prefix + "." + i, path);
        }
    }

    private void addExtrasInputs(InputSink sink, String prefix, List<SourceDest> extras) throws IOException {
        if (extras == null) {
            return;
        }
        int i;
        for (i = 0; i < extras.size(); i++) {
            SourceDest extra = extras.get(i);
            if (extra == null) {
                continue;
            }
            addExtraInput(sink, prefix + "." + i, toProjectRelativePath(extra.getSource()));
        }
    }

    private void addExtraInput(InputSink sink, String key, Path path) throws IOException {
        sink.add(reactorStateResolver.resolveExtraInput(key, path));
    }

    private void addFileTreeInput(InputSink sink, String key, Path path) throws IOException {
        InputState state = new InputState();
        state.setKind(KIND_FILE_TREE);
        state.setKey(key);
        populateFileState(state, path);
        state.setUsesTimestamp(true);
        sink.add(state);
    }

    private void addToolUnpackedInput(InputSink sink, String key, Path path) throws IOException {
        InputState state = new InputState();
        state.setKind(KIND_FILE_TREE);
        state.setKey(key);
        populateToolInputState(state, path);
        state.setUsesTimestamp(true);
        sink.add(state);
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

    private void populateToolInputState(InputState state, Path path) throws IOException {
        String projectArtifactFileName = project.getArtifact() == null || project.getArtifact().getFile() == null
                ? null
                : project.getArtifact().getFile().getName();
        FileSnapshot snapshot = fileSnapshotter.snapshotToolInput(path, projectArtifactFileName);
        state.setPath(snapshot.getPath());
        state.setExists(snapshot.isExists());
        state.setLastModified(snapshot.getLastModified());
        state.setCount(snapshot.getCount());
        state.setTotalSize(snapshot.getTotalSize());
        state.setDetails(snapshot.describe());
    }

    private Path getProjectArtifactPath() {
        if (!shouldTrackProjectArtifact()) {
            return null;
        }
        if (project.getArtifact() == null || project.getArtifact().getFile() == null) {
            return null;
        }
        return project.getArtifact().getFile().toPath();
    }

    /*
     * Current module artifact is a useful incremental input for packagings that produce a stable primary artifact
     * which reflects meaningful upstream changes, for example a regular jar built from current module classes.
     *
     * WAR modules are different in this build:
     *  - Maven runs war:war before teamcity:build.
     *  - war:war rewrites target/<artifactId>-<version>.war even on a no-op build.
     *  - The rewritten WAR often keeps exactly the same payload and size, but gets a fresh mtime.
     *  - teamcity:build then sees the current module's project.artifact timestamp as changed and treats the module
     *    as stale, even though none of the inputs that actually affect TeamCity plugin assembly changed.
     *
     * In practice that produced false incremental misses for many server webapp modules. Example from TeamCity:
     *  - ant-runner-server-webapp compiles no Java sources,
     *  - war:war still rewrites ant-runner-server-webapp-<version>.war,
     *  - incremental state changes only because project.artifact points to that rewritten WAR,
     *  - teamcity:build repacks the plugin again instead of reporting "up-to-date, skipping".
     *
     * The same pattern was reproduced in ModuleWarTestCase:
     *  - write module-war-1.1.war,
     *  - run teamcity:build once,
     *  - rewrite the same WAR with the same contents but a different timestamp,
     *  - the second build must still be considered up-to-date.
     *
     * For WAR packaging we therefore ignore project.artifact as an input of the current module and rely on
     * pre-package inputs instead:
     *  - pom.xml
     *  - output/classes
     *  - descriptors
     *  - web resources and extras gathered by other inputs
     *
     * This keeps WAR modules incremental while still allowing other packaging types to use project.artifact.
     */
    private boolean shouldTrackProjectArtifact() {
        String packaging = project.getPackaging();
        if (packaging == null) {
            return true;
        }
        return !"war".equalsIgnoreCase(packaging);
    }

    private Path toProjectRelativePath(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        Path path = Paths.get(value);
        if (path.isAbsolute()) {
            return path;
        }
        if (project.getBasedir() == null) {
            return path;
        }
        return project.getBasedir().toPath().resolve(path).normalize();
    }

    private Path resolveChild(Path parent, String child) {
        if (parent == null) {
            return null;
        }
        return parent.resolve(child);
    }

    private Path getPath(java.io.File file) {
        if (file == null) {
            return null;
        }
        return file.toPath();
    }

    private void sortInputs(List<InputState> inputs) {
        Collections.sort(inputs, new Comparator<InputState>() {
            @Override
            public int compare(InputState first, InputState second) {
                return first.identity().compareTo(second.identity());
            }
        });
    }

    private interface InputSink {
        void add(InputState input) throws IOException;
    }

    private static class ListInputSink implements InputSink {
        private final List<InputState> inputs = new ArrayList<InputState>();

        @Override
        public void add(InputState input) {
            inputs.add(input);
        }

        private List<InputState> getInputs() {
            return inputs;
        }
    }

    private static class CheckingInputSink implements InputSink {
        private final List<InputState> inputs = new ArrayList<InputState>();
        private final Map<String, InputState> previousByIdentity = new LinkedHashMap<String, InputState>();
        private final Set<String> seenIdentities = new HashSet<String>();

        private CheckingInputSink(List<InputState> previousInputs) {
            if (previousInputs == null) {
                return;
            }
            int i;
            for (i = 0; i < previousInputs.size(); i++) {
                InputState input = previousInputs.get(i);
                previousByIdentity.put(input.identity(), input);
            }
        }

        @Override
        public void add(InputState input) {
            inputs.add(input);
            String identity = input.identity();
            InputState previous = previousByIdentity.get(identity);
            if (previous == null) {
                throw new EarlyIncrementalMiss("input added: " + identity + " current=" + input.toFingerprint());
            }
            seenIdentities.add(identity);
            if (!previous.sameAs(input)) {
                throw new EarlyIncrementalMiss(input.describeChangeFrom(previous));
            }
        }

        private String findRemovedInput() {
            for (String identity : previousByIdentity.keySet()) {
                if (!seenIdentities.contains(identity)) {
                    InputState previous = previousByIdentity.get(identity);
                    return identity + " previous=" + (previous == null ? "" : previous.toFingerprint());
                }
            }
            return null;
        }

        private List<InputState> getInputs() {
            return inputs;
        }
    }

    private static class EarlyIncrementalMiss extends RuntimeException {
        private EarlyIncrementalMiss(String message) {
            super(message);
        }
    }
}
