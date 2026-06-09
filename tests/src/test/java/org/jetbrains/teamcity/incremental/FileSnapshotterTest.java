package org.jetbrains.teamcity.incremental;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
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
    public void singleFileSnapshotChangesWhenSizeChanges() throws Exception {
        Path file = Files.createTempFile("snapshotter-size", ".txt");
        Files.writeString(file, "before");
        Files.setLastModifiedTime(file, FileTime.fromMillis(1000L));

        FileSnapshot before = snapshotter.snapshotPath(file);

        Files.writeString(file, "after with different size");
        Files.setLastModifiedTime(file, FileTime.fromMillis(1000L));

        FileSnapshot after = snapshotter.snapshotPath(file);

        assertThat(after.getLastModified()).isEqualTo(before.getLastModified());
        assertThat(after.getTotalSize()).isNotEqualTo(before.getTotalSize());
        assertThat(after.describe()).isNotEqualTo(before.describe());
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
    public void directorySnapshotIncludesRelativeFileNames() throws Exception {
        Path dir = Files.createTempDirectory("snapshotter-rename");
        Path first = dir.resolve("a.txt");
        Files.writeString(first, "same");
        Files.setLastModifiedTime(first, FileTime.fromMillis(1000L));

        FileSnapshot before = snapshotter.snapshotPath(dir);

        Files.delete(first);
        Path second = dir.resolve("b.txt");
        Files.writeString(second, "same");
        Files.setLastModifiedTime(second, FileTime.fromMillis(1000L));

        FileSnapshot after = snapshotter.snapshotPath(dir);

        assertThat(after.getCount()).isEqualTo(before.getCount());
        assertThat(after.getTotalSize()).isEqualTo(before.getTotalSize());
        assertThat(after.getLastModified()).isEqualTo(before.getLastModified());
        assertThat(after.describe()).isNotEqualTo(before.describe());
        assertThat(before.describe()).contains("a.txt");
        assertThat(after.describe()).contains("b.txt");
    }

    @Test
    public void directorySnapshotChangesWhenFileMtimeChanges() throws Exception {
        Path dir = Files.createTempDirectory("snapshotter-dir-mtime");
        Path file = dir.resolve("payload.txt");
        Files.writeString(file, "same");
        Files.setLastModifiedTime(file, FileTime.fromMillis(1000L));

        FileSnapshot before = snapshotter.snapshotPath(dir);

        Files.setLastModifiedTime(file, FileTime.fromMillis(2000L));

        FileSnapshot after = snapshotter.snapshotPath(dir);

        assertThat(after.getCount()).isEqualTo(before.getCount());
        assertThat(after.getTotalSize()).isEqualTo(before.getTotalSize());
        assertThat(after.getLastModified()).isNotEqualTo(before.getLastModified());
        assertThat(after.describe()).isNotEqualTo(before.describe());
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
    public void ignoredFilesDoNotChangeDirectoryFingerprint() throws Exception {
        Path dir = Files.createTempDirectory("snapshotter-ignore-fingerprint");
        Path keep = dir.resolve("keep.txt");
        Files.writeString(keep, "ok");
        Files.setLastModifiedTime(keep, FileTime.fromMillis(1000L));
        Path ignored = dir.resolve("artifact.jar.sha1");
        Files.writeString(ignored, "checksum");
        Files.setLastModifiedTime(ignored, FileTime.fromMillis(2000L));

        FileSnapshot before = snapshotter.snapshotPath(dir, List.of("artifact.jar.sha1"));

        Files.writeString(ignored, "new checksum with different size");
        Files.setLastModifiedTime(ignored, FileTime.fromMillis(3000L));

        FileSnapshot after = snapshotter.snapshotPath(dir, List.of("artifact.jar.sha1"));

        assertThat(after.describe()).isEqualTo(before.describe());
        assertThat(after.describe()).doesNotContain("artifact.jar.sha1");
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
