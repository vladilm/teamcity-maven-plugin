package org.jetbrains.teamcity.incremental;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.assertj.core.api.Assertions.assertThat;

public class IncrementalAssemblySourcesFixtureTest {
    @Test
    public void snapshotDependencySizeChangeIsAMiss() throws Exception {
        Path root = Files.createTempDirectory("incremental-sources-snapshot");
        Path output = write(root.resolve("target/teamcity/plugin.zip"), "output");
        Path dependency = write(root.resolve("repo/lib-1.0-SNAPSHOT.jar"), "before");
        Files.setLastModifiedTime(dependency, FileTime.fromMillis(1000L));

        IncrementalAssemblySourcesFixture fixture = IncrementalAssemblySourcesFixture.in(root)
                .snapshotDependency("org.example:lib:jar::1.0-SNAPSHOT", dependency)
                .output(output);
        IncrementalState previous = fixture.state();

        write(dependency, "after with different size");
        Files.setLastModifiedTime(dependency, FileTime.fromMillis(1000L));

        IncrementalState current = fixture.state();

        assertThat(fixture.isUpToDate(previous, current)).isFalse();
        assertThat(fixture.describeDifference(previous, current)).contains("org.example:lib:jar::1.0-SNAPSHOT");
    }

    @Test
    public void unchangedSnapshotDependencyIsUpToDate() throws Exception {
        Path root = Files.createTempDirectory("incremental-sources-snapshot-noop");
        Path output = write(root.resolve("target/teamcity/plugin.zip"), "output");
        Path dependency = write(root.resolve("repo/lib-1.0-SNAPSHOT.jar"), "abcd");

        IncrementalAssemblySourcesFixture fixture = IncrementalAssemblySourcesFixture.in(root)
                .snapshotDependency("org.example:lib:jar::1.0-SNAPSHOT", dependency)
                .output(output);

        assertThat(fixture.isUpToDate(fixture.state(), fixture.state())).isTrue();
    }

    @Test
    public void releaseDependencyVersionChangeIsAMiss() throws Exception {
        Path root = Files.createTempDirectory("incremental-sources-release");
        Path output = write(root.resolve("target/teamcity/plugin.zip"), "output");
        IncrementalState previous = IncrementalAssemblySourcesFixture.in(root)
                .releaseDependency("org.example:lib:jar::1.0")
                .output(output)
                .state();
        IncrementalState current = IncrementalAssemblySourcesFixture.in(root)
                .releaseDependency("org.example:lib:jar::1.1")
                .output(output)
                .state();

        assertThat(new IncrementalAssembleCore().isUpToDate(previous, current)).isFalse();
        assertThat(new IncrementalAssembleCore().describeDifference(previous, current)).contains("org.example:lib:jar::1.0");
    }

    @Test
    public void sameReleaseDependencyIsImmutableEvenIfLocalFileChanges() throws Exception {
        Path root = Files.createTempDirectory("incremental-sources-release-noop");
        Path output = write(root.resolve("target/teamcity/plugin.zip"), "output");
        Path releaseFile = write(root.resolve("repo/lib-1.0.jar"), "before");

        IncrementalAssemblySourcesFixture fixture = IncrementalAssemblySourcesFixture.in(root)
                .releaseDependency("org.example:lib:jar::1.0")
                .output(output);
        IncrementalState previous = fixture.state();

        write(releaseFile, "after");

        assertThat(fixture.isUpToDate(previous, fixture.state())).isTrue();
    }

    @Test
    public void addedTransitiveDependencyIsAMiss() throws Exception {
        Path root = Files.createTempDirectory("incremental-sources-transitive");
        Path output = write(root.resolve("target/teamcity/plugin.zip"), "output");
        IncrementalState previous = IncrementalAssemblySourcesFixture.in(root)
                .releaseDependency("org.example:direct:jar::1.0")
                .output(output)
                .state();
        IncrementalState current = IncrementalAssemblySourcesFixture.in(root)
                .releaseDependency("org.example:direct:jar::1.0")
                .releaseDependency("org.example:transitive:jar::1.0")
                .output(output)
                .state();

        assertThat(new IncrementalAssembleCore().isUpToDate(previous, current)).isFalse();
        assertThat(new IncrementalAssembleCore().describeDifference(previous, current))
                .isEqualTo("input count changed: 1 -> 2");
    }

    @Test
    public void unchangedTransitiveDependencyGraphIsUpToDate() throws Exception {
        Path root = Files.createTempDirectory("incremental-sources-transitive-noop");
        Path output = write(root.resolve("target/teamcity/plugin.zip"), "output");
        IncrementalAssemblySourcesFixture fixture = IncrementalAssemblySourcesFixture.in(root)
                .releaseDependency("org.example:direct:jar::1.0")
                .releaseDependency("org.example:transitive:jar::1.0")
                .output(output);

        assertThat(fixture.isUpToDate(fixture.state(), fixture.state())).isTrue();
    }

    @Test
    public void moduleTargetMtimeChangeIsAMiss() throws Exception {
        Path root = Files.createTempDirectory("incremental-sources-target");
        Path output = write(root.resolve("target/teamcity/plugin.zip"), "output");
        Path targetFile = write(root.resolve("target/classes/payload.txt"), "same");
        Files.setLastModifiedTime(targetFile, FileTime.fromMillis(1000L));

        IncrementalAssemblySourcesFixture fixture = IncrementalAssemblySourcesFixture.in(root)
                .moduleTarget(root.resolve("target/classes"))
                .output(output);
        IncrementalState previous = fixture.state();

        Files.setLastModifiedTime(targetFile, FileTime.fromMillis(2000L));

        assertThat(fixture.isUpToDate(previous, fixture.state())).isFalse();
    }

    @Test
    public void filesystemContentChangeIsAMiss() throws Exception {
        Path root = Files.createTempDirectory("incremental-sources-filesystem");
        Path output = write(root.resolve("target/teamcity/plugin.zip"), "output");
        Path file = write(root.resolve("external/payload.txt"), "before");

        IncrementalAssemblySourcesFixture fixture = IncrementalAssemblySourcesFixture.in(root)
                .filesystem("external", root.resolve("external"))
                .output(output);
        IncrementalState previous = fixture.state();

        write(file, "after");

        assertThat(fixture.isUpToDate(previous, fixture.state())).isFalse();
    }

    @Test
    public void excludedFilesystemPathDoesNotChangeState() throws Exception {
        Path root = Files.createTempDirectory("incremental-sources-filesystem-excluded");
        Path output = write(root.resolve("target/teamcity/plugin.zip"), "output");
        Path included = write(root.resolve("target/classes/included.txt"), "same");
        Files.setLastModifiedTime(included, FileTime.fromMillis(1000L));
        Path excluded = write(root.resolve("target/classes/generated/volatile.txt"), "before");

        IncrementalAssemblySourcesFixture fixture = IncrementalAssemblySourcesFixture.in(root)
                .excludeFromSnapshots("generated")
                .moduleTarget(root.resolve("target/classes"))
                .output(output);
        IncrementalState previous = fixture.state();

        write(excluded, "after with different size");

        assertThat(fixture.isUpToDate(previous, fixture.state())).isTrue();
    }

    private static Path write(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.write(path, content.getBytes("UTF-8"));
        return path;
    }
}
