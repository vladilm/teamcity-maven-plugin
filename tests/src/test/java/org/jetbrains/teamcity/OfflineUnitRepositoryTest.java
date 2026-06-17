package org.jetbrains.teamcity;

import org.apache.maven.execution.MavenSession;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

public class OfflineUnitRepositoryTest extends BasePluginTestCase {
    @Test
    public void unitTestsUseBuildPreparedOfflineLocalRepository() {
        MavenSession session = initMavenSession("agent/simple");
        Path localRepository = session.getRequest().getLocalRepositoryPath().toPath();

        assertThat(session.getRequest().isOffline()).isTrue();
        assertThat(((DefaultRepositorySystemSession) session.getRepositorySession()).isOffline()).isTrue();
        assertThat(session.getRequest().getRemoteRepositories()).isEmpty();
        assertThat(session.getRequest().getPluginArtifactRepositories()).isEmpty();
        assertThat(localRepository.getFileName().toString()).isEqualTo("test-local-repo");
        assertThat(Files.exists(localRepository.resolve("commons-beanutils/commons-beanutils-core/1.8.3/commons-beanutils-core-1.8.3.pom"))).isTrue();
        assertThat(Files.exists(localRepository.resolve("commons-beanutils/commons-beanutils-core/1.8.3/commons-beanutils-core-1.8.3.jar"))).isTrue();
    }
}
