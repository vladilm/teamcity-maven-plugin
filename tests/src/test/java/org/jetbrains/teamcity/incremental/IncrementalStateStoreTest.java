package org.jetbrains.teamcity.incremental;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class IncrementalStateStoreTest {
    @Test
    public void savesAndLoadsStateWithoutChangingInputsOutputsOrConfigStamp() throws Exception {
        Path stateFile = Files.createTempDirectory("incremental-store").resolve(".assemble-state.properties");
        IncrementalStateStore store = new IncrementalStateStore(stateFile);

        IncrementalState state = new IncrementalState();
        state.setConfigStamp("config-stamp");
        state.setLatestInputTs(123L);
        state.setInputFingerprint("fingerprint");
        state.setInputs(List.of(input()));
        state.setOutputs(List.of(output()));

        store.save(state);
        IncrementalState loaded = store.load();

        assertThat(loaded).isNotNull();
        assertThat(loaded.getConfigStamp()).isEqualTo("config-stamp");
        assertThat(loaded.getLatestInputTs()).isEqualTo(123L);
        assertThat(loaded.getInputFingerprint()).isEqualTo("fingerprint");
        assertThat(loaded.getInputs()).hasSize(1);
        assertThat(loaded.getInputs().get(0).toFingerprint()).isEqualTo(state.getInputs().get(0).toFingerprint());
        assertThat(loaded.getOutputs()).hasSize(1);
        assertThat(loaded.getOutputs().get(0).identity()).isEqualTo(state.getOutputs().get(0).identity());
        assertThat(loaded.getOutputs().get(0).getLastModified()).isEqualTo(state.getOutputs().get(0).getLastModified());
    }

    private static InputState input() {
        InputState state = new InputState();
        state.setKind("FILE_TREE");
        state.setKey("project.output");
        state.setPath(Path.of("target/classes"));
        state.setExists(true);
        state.setLastModified(10L);
        state.setCount(2L);
        state.setTotalSize(64L);
        state.setDetails("details");
        state.setUsesTimestamp(true);
        return state;
    }

    private static OutputState output() {
        OutputState state = new OutputState();
        state.setType("zip");
        state.setClassifier("teamcity-plugin");
        state.setPath(Path.of("target/teamcity/plugin.zip"));
        state.setLastModified(20L);
        return state;
    }
}
