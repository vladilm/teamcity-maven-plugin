package org.jetbrains.teamcity.incremental;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class IncrementalAssembleCoreTest {
    private final IncrementalAssembleCore core = new IncrementalAssembleCore();

    @Test
    public void identicalStatesAreUpToDate() throws Exception {
        Path output = Files.createTempFile("incremental-core", ".zip");
        long outputTs = Files.getLastModifiedTime(output).toMillis();
        IncrementalState previous = state(
                "stamp",
                input("FILE_TREE", "project.pom", Path.of("pom.xml"), true, 10L, 1L, 100L, "pom.xml|true|10|1|100", true)
        );
        previous.setOutputs(List.of(output("zip", "teamcity-plugin", output, outputTs)));

        IncrementalState current = state(
                "stamp",
                input("FILE_TREE", "project.pom", Path.of("pom.xml"), true, 10L, 1L, 100L, "pom.xml|true|10|1|100", true)
        );

        assertThat(core.isUpToDate(previous, current)).isTrue();
    }

    @Test
    public void missingPreviousStateIsAMiss() {
        IncrementalState current = state(
                "stamp",
                input("FILE_TREE", "project.pom", Path.of("pom.xml"), true, 10L, 1L, 100L, "pom.xml|true|10|1|100", true)
        );

        assertThat(core.isUpToDate(null, current)).isFalse();
        assertThat(core.describeDifference(null, current)).isEqualTo("no previous state");
    }

    @Test
    public void changedInputProducesExpectedDifferenceString() {
        IncrementalState previous = state(
                "stamp",
                input("FILE_TREE", "project.output", Path.of("target/classes"), true, 10L, 1L, 100L, "before", true)
        );
        previous.setOutputs(List.of(output("zip", "teamcity-plugin", Path.of("plugin.zip"), 20L)));

        IncrementalState current = state(
                "stamp",
                input("FILE_TREE", "project.output", Path.of("target/classes"), true, 11L, 1L, 100L, "after", true)
        );

        assertThat(core.isUpToDate(previous, current)).isFalse();
        assertThat(core.describeDifference(previous, current))
                .isEqualTo("input changed: FILE_TREE|project.output|target/classes changed fields: lastModified 10 -> 11, details before -> after");
    }

    @Test
    public void changedInputDifferenceShowsConcreteSourceFields() {
        IncrementalState previous = state(
                "stamp",
                input("FILE_TREE", "project.output", Path.of("target/classes"), true, 10L, 1L, 100L, "payload.txt:100:10", true)
        );
        previous.setOutputs(List.of(output("zip", "teamcity-plugin", Path.of("plugin.zip"), 20L)));

        IncrementalState current = state(
                "stamp",
                input("FILE_TREE", "project.output", Path.of("target/classes"), true, 20L, 2L, 150L, "payload.txt:100:10,added.txt:50:20", true)
        );

        assertThat(core.describeDifference(previous, current))
                .isEqualTo("input changed: FILE_TREE|project.output|target/classes changed fields: lastModified 10 -> 20, count 1 -> 2, totalSize 100 -> 150, details payload.txt:100:10 -> payload.txt:100:10,added.txt:50:20");
    }

    @Test
    public void changedOutputTimestampProducesExpectedDifferenceString() throws Exception {
        Path output = Files.createTempFile("incremental-core-output", ".zip");
        Files.setLastModifiedTime(output, FileTime.fromMillis(2000L));
        IncrementalState previous = state(
                "stamp",
                input("FILE_TREE", "project.pom", Path.of("pom.xml"), true, 10L, 1L, 100L, "pom.xml|true|10|1|100", true)
        );
        previous.setOutputs(List.of(output("zip", "teamcity-plugin", output, 1000L)));

        IncrementalState current = state(
                "stamp",
                input("FILE_TREE", "project.pom", Path.of("pom.xml"), true, 10L, 1L, 100L, "pom.xml|true|10|1|100", true)
        );

        assertThat(core.isUpToDate(previous, current)).isFalse();
        assertThat(core.describeDifference(previous, current))
                .isEqualTo("output changed: teamcity-plugin saved=1000 current=2000 path=" + output);
    }

    private static IncrementalState state(String configStamp, InputState... inputs) {
        IncrementalState state = new IncrementalState();
        state.setConfigStamp(configStamp);
        state.setInputs(List.of(inputs));
        state.setLatestInputTs(11L);
        state.setInputFingerprint(new IncrementalAssembleCore().buildInputFingerprint(List.of(inputs)));
        return state;
    }

    private static InputState input(String kind, String key, Path path, boolean exists, long lastModified, long count, long totalSize, String details, boolean usesTimestamp) {
        InputState state = new InputState();
        state.setKind(kind);
        state.setKey(key);
        state.setPath(path);
        state.setExists(exists);
        state.setLastModified(lastModified);
        state.setCount(count);
        state.setTotalSize(totalSize);
        state.setDetails(details);
        state.setUsesTimestamp(usesTimestamp);
        return state;
    }

    private static OutputState output(String type, String classifier, Path path, long lastModified) {
        OutputState state = new OutputState();
        state.setType(type);
        state.setClassifier(classifier);
        state.setPath(path);
        state.setLastModified(lastModified);
        return state;
    }
}
