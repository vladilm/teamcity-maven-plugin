package org.jetbrains.teamcity.incremental;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

class IncrementalAssemblySourcesFixture {
    private final IncrementalAssembleCore core = new IncrementalAssembleCore();
    private final Path root;
    private final List<Source> sources = new ArrayList<Source>();
    private final List<String> snapshotExcludes = new ArrayList<String>();
    private Path output;

    private IncrementalAssemblySourcesFixture(Path root) {
        this.root = root;
    }

    static IncrementalAssemblySourcesFixture in(Path root) {
        return new IncrementalAssemblySourcesFixture(root);
    }

    IncrementalAssemblySourcesFixture excludeFromSnapshots(String... patterns) {
        int i;
        for (i = 0; i < patterns.length; i++) {
            snapshotExcludes.add(patterns[i]);
        }
        return this;
    }

    IncrementalAssemblySourcesFixture moduleTarget(Path path) {
        sources.add(Source.fileTree("module.target", path));
        return this;
    }

    IncrementalAssemblySourcesFixture filesystem(String key, Path path) {
        sources.add(Source.fileTree("filesystem." + key, path));
        return this;
    }

    IncrementalAssemblySourcesFixture releaseDependency(String gavtc) {
        sources.add(Source.releaseDependency(gavtc));
        return this;
    }

    IncrementalAssemblySourcesFixture snapshotDependency(String gavtc, Path path) {
        sources.add(Source.snapshotDependency(gavtc, path));
        return this;
    }

    IncrementalAssemblySourcesFixture output(Path output) {
        this.output = output;
        return this;
    }

    IncrementalState state() throws IOException {
        List<InputState> inputs = new ArrayList<InputState>();
        FileSnapshotter snapshotter = new FileSnapshotter(snapshotExcludes);
        int i;
        for (i = 0; i < sources.size(); i++) {
            inputs.add(sources.get(i).toInput(snapshotter));
        }
        Collections.sort(inputs, new Comparator<InputState>() {
            @Override
            public int compare(InputState first, InputState second) {
                return first.identity().compareTo(second.identity());
            }
        });

        IncrementalState state = new IncrementalState();
        state.setConfigStamp("fixture|snapshotExcludes=" + snapshotExcludes);
        state.setInputs(inputs);
        state.setLatestInputTs(core.calculateLatestInputTs(inputs));
        state.setInputFingerprint(core.buildInputFingerprint(inputs));
        state.setOutputs(new ArrayList<OutputState>());
        if (output != null) {
            state.setOutputs(Collections.singletonList(outputState(output)));
        }
        return state;
    }

    Path file(String relativePath, String content) throws IOException {
        Path path = root.resolve(relativePath);
        Files.createDirectories(path.getParent());
        Files.write(path, content.getBytes("UTF-8"));
        return path;
    }

    boolean isUpToDate(IncrementalState previous, IncrementalState current) {
        return core.isUpToDate(previous, current);
    }

    String describeDifference(IncrementalState previous, IncrementalState current) {
        return core.describeDifference(previous, current);
    }

    private static OutputState outputState(Path path) throws IOException {
        OutputState output = new OutputState();
        output.setType("zip");
        output.setClassifier("fixture-output");
        output.setPath(path);
        output.setLastModified(Files.getLastModifiedTime(path).toMillis());
        return output;
    }

    private static InputState fileTreeInput(String key, FileSnapshot snapshot) {
        InputState state = new InputState();
        state.setKind("FILE_TREE");
        state.setKey(key);
        copySnapshot(state, snapshot);
        state.setUsesTimestamp(true);
        return state;
    }

    private static InputState snapshotDependencyInput(String gavtc, FileSnapshot snapshot) {
        InputState state = new InputState();
        state.setKind("EXTERNAL_SNAPSHOT_ARTIFACT");
        state.setKey(gavtc);
        copySnapshot(state, snapshot);
        state.setDetails(gavtc + "|" + snapshot.describe());
        state.setUsesTimestamp(true);
        return state;
    }

    private static void copySnapshot(InputState state, FileSnapshot snapshot) {
        state.setPath(snapshot.getPath());
        state.setExists(snapshot.isExists());
        state.setLastModified(snapshot.getLastModified());
        state.setCount(snapshot.getCount());
        state.setTotalSize(snapshot.getTotalSize());
        state.setDetails(snapshot.describe());
    }

    private static class Source {
        private final String kind;
        private final String key;
        private final Path path;

        private Source(String kind, String key, Path path) {
            this.kind = kind;
            this.key = key;
            this.path = path;
        }

        static Source fileTree(String key, Path path) {
            return new Source("FILE_TREE", key, path);
        }

        static Source releaseDependency(String gavtc) {
            return new Source("IMMUTABLE_ARTIFACT", gavtc, null);
        }

        static Source snapshotDependency(String gavtc, Path path) {
            return new Source("EXTERNAL_SNAPSHOT_ARTIFACT", gavtc, path);
        }

        InputState toInput(FileSnapshotter snapshotter) throws IOException {
            if ("IMMUTABLE_ARTIFACT".equals(kind)) {
                InputState state = new InputState();
                state.setKind(kind);
                state.setKey(key);
                state.setDetails(key);
                state.setUsesTimestamp(false);
                return state;
            }
            if ("EXTERNAL_SNAPSHOT_ARTIFACT".equals(kind)) {
                return snapshotDependencyInput(key, snapshotter.snapshotPath(path));
            }
            return fileTreeInput(key, snapshotter.snapshotPath(path));
        }
    }
}
