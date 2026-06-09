package org.jetbrains.teamcity.incremental;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class FileSnapshotterTest {
    private final FileSnapshotter snapshotter = new FileSnapshotter();

    @Test
    public void snapshotsSingleFile() throws Exception {
        Path file = Files.createTempFile("snapshotter", ".txt");
        Files.writeString(file, "hello");

        FileSnapshot snapshot = snapshotter.snapshotPath(file);

        assertThat(snapshot.isExists()).isTrue();
        assertThat(snapshot.getPath()).isEqualTo(file);
        assertThat(snapshot.getCount()).isEqualTo(1L);
        assertThat(snapshot.getTotalSize()).isEqualTo(5L);
    }

    @Test
    public void snapshotsDirectoryTree() throws Exception {
        Path dir = Files.createTempDirectory("snapshotter-dir");
        Files.writeString(dir.resolve("a.txt"), "ab");
        Path nested = Files.createDirectories(dir.resolve("nested"));
        Files.writeString(nested.resolve("b.txt"), "cde");

        FileSnapshot snapshot = snapshotter.snapshotPath(dir);

        assertThat(snapshot.isExists()).isTrue();
        assertThat(snapshot.getCount()).isEqualTo(2L);
        assertThat(snapshot.getTotalSize()).isEqualTo(5L);
    }

    @Test
    public void ignoresExplicitMetadataAndChecksumFiles() throws Exception {
        Path dir = Files.createTempDirectory("snapshotter-ignore");
        Files.writeString(dir.resolve("keep.txt"), "ok");
        Files.writeString(dir.resolve("artifact.jar.sha1"), "checksum");
        Files.writeString(dir.resolve("maven-metadata.xml"), "metadata");

        FileSnapshot snapshot = snapshotter.snapshotPath(dir, List.of("artifact.jar.sha1", "maven-metadata.xml"));

        assertThat(snapshot.getCount()).isEqualTo(1L);
        assertThat(snapshot.getTotalSize()).isEqualTo(2L);
    }

    @Test
    public void snapshotsToolInputDirectoryIgnoringGeneratedMetadata() throws Exception {
        Path dir = Files.createTempDirectory("snapshotter-tool");
        Files.writeString(dir.resolve("payload.txt"), "payload");
        Files.writeString(dir.resolve("teamcity-plugin.xml"), "descriptor");
        Files.writeString(dir.resolve("current-artifact.zip"), "binary");

        FileSnapshot snapshot = snapshotter.snapshotToolInput(dir, "current-artifact.zip");

        assertThat(snapshot.getCount()).isEqualTo(1L);
        assertThat(snapshot.getTotalSize()).isEqualTo("payload".length());
    }
}
