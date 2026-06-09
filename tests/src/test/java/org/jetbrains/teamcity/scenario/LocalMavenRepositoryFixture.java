package org.jetbrains.teamcity.scenario;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.StringJoiner;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

public class LocalMavenRepositoryFixture {
    private final List<ArtifactSpec> artifacts = new ArrayList<ArtifactSpec>();

    public static LocalMavenRepositoryFixture minimalCommons() {
        return new LocalMavenRepositoryFixture()
                .jar("commons-beanutils:commons-beanutils-core:1.8.3", "commons-logging:commons-logging:1.1.1")
                .jar("commons-logging:commons-logging:1.1.1")
                .jar("commons-codec:commons-codec:1.15")
                .jar("commons-io:commons-io:2.2");
    }

    public LocalMavenRepositoryFixture jar(String gav, String... dependencyGavs) {
        artifacts.add(ArtifactSpec.jar(gav, dependencyGavs));
        return this;
    }

    public Path materialize(Path baseDirectory) throws IOException {
        Path repo = baseDirectory.resolve("local-maven-repo");
        Files.createDirectories(repo);
        int i;
        for (i = 0; i < artifacts.size(); i++) {
            materializeArtifact(repo, artifacts.get(i));
        }
        return repo;
    }

    private void materializeArtifact(Path repo, ArtifactSpec artifact) throws IOException {
        Path artifactDirectory = repo.resolve(artifact.groupId.replace('.', '/'))
                .resolve(artifact.artifactId)
                .resolve(artifact.version);
        Files.createDirectories(artifactDirectory);

        String baseName = artifact.artifactId + "-" + artifact.version;
        writePom(artifactDirectory.resolve(baseName + ".pom"), artifact);
        writeJar(artifactDirectory.resolve(baseName + ".jar"), artifact);
    }

    private void writePom(Path path, ArtifactSpec artifact) throws IOException {
        StringBuilder pom = new StringBuilder();
        pom.append("<project xmlns=\"http://maven.apache.org/POM/4.0.0\"\n");
        pom.append("         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n");
        pom.append("         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd\">\n");
        pom.append("  <modelVersion>4.0.0</modelVersion>\n");
        pom.append("  <groupId>").append(artifact.groupId).append("</groupId>\n");
        pom.append("  <artifactId>").append(artifact.artifactId).append("</artifactId>\n");
        pom.append("  <version>").append(artifact.version).append("</version>\n");
        if (!artifact.dependencies.isEmpty()) {
            pom.append("  <dependencies>\n");
            int i;
            for (i = 0; i < artifact.dependencies.size(); i++) {
                Coordinates dependency = artifact.dependencies.get(i);
                pom.append("    <dependency>\n");
                pom.append("      <groupId>").append(dependency.groupId).append("</groupId>\n");
                pom.append("      <artifactId>").append(dependency.artifactId).append("</artifactId>\n");
                pom.append("      <version>").append(dependency.version).append("</version>\n");
                pom.append("    </dependency>\n");
            }
            pom.append("  </dependencies>\n");
        }
        pom.append("</project>\n");

        Files.write(path, pom.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void writeJar(Path path, ArtifactSpec artifact) throws IOException {
        OutputStream outputStream = Files.newOutputStream(path);
        try {
            JarOutputStream jar = new JarOutputStream(outputStream);
            try {
                JarEntry entry = new JarEntry("fixture.txt");
                jar.putNextEntry(entry);
                jar.write(artifact.toString().getBytes(StandardCharsets.UTF_8));
                jar.closeEntry();
            } finally {
                jar.close();
            }
        } finally {
            outputStream.close();
        }
    }

    private static class ArtifactSpec {
        private final String groupId;
        private final String artifactId;
        private final String version;
        private final List<Coordinates> dependencies;

        private ArtifactSpec(String groupId, String artifactId, String version, List<Coordinates> dependencies) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.version = version;
            this.dependencies = dependencies;
        }

        private static ArtifactSpec jar(String gav, String... dependencyGavs) {
            Coordinates coordinates = Coordinates.parse(gav);
            List<Coordinates> dependencies = new ArrayList<Coordinates>();
            int i;
            for (i = 0; i < dependencyGavs.length; i++) {
                dependencies.add(Coordinates.parse(dependencyGavs[i]));
            }
            return new ArtifactSpec(coordinates.groupId, coordinates.artifactId, coordinates.version, dependencies);
        }

        @Override
        public String toString() {
            StringJoiner joiner = new StringJoiner(":");
            joiner.add(groupId);
            joiner.add(artifactId);
            joiner.add(version);
            return joiner.toString();
        }
    }

    private static class Coordinates {
        private final String groupId;
        private final String artifactId;
        private final String version;

        private Coordinates(String groupId, String artifactId, String version) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.version = version;
        }

        private static Coordinates parse(String gav) {
            List<String> parts = Arrays.asList(gav.split(":"));
            if (parts.size() != 3) {
                throw new IllegalArgumentException("Expected groupId:artifactId:version, got " + gav);
            }
            return new Coordinates(parts.get(0), parts.get(1), parts.get(2));
        }
    }
}
