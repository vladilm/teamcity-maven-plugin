package org.jetbrains.teamcity.scenario;

import org.apache.maven.plugin.testing.MojoRule;
import org.junit.Rule;
import org.junit.Test;

import java.util.List;

public class TeamCityMojoScenarioConceptTest {
    @Rule
    public MojoRule rule = new MojoRule();

    @Test
    public void assemblePluginFromExplicitPomAndOfflineRepository() throws Exception {
        TeamCityMojoScenario.given(rule)
                .pom("unit/project-to-test/pom.xml")
                .offlineRepository(LocalMavenRepositoryFixture.minimalCommons())
                .beforeExecute(context -> {
                    context.writeProjectFile("target/classes/kotlin-dsl/test", "dsl");
                    context.writeProjectFile("target/classes/ui-schemas/test", "schema");
                    context.writeProjectFile("target/teamcity/plugin/project-to-test/bundles/1", "");
                })
                .configureMojo(mojo -> {
                    mojo.setFailOnMissingDependencies(false);
                    mojo.getServer().setIgnoreExtraFilesIn(List.of("bundles"));
                })
                .expectLayout("""
                        AGENT:
                        lib
                        lib/commons-beanutils-core-1.8.3.jar
                        lib/commons-logging-1.1.1.jar
                        teamcity-plugin.xml
                        SERVER:
                        agent/
                        agent/project-to-test.zip
                        bundles/
                        bundles/1
                        kotlin-dsl/
                        kotlin-dsl/test
                        server/
                        server/commons-codec-1.15.jar
                        server/project-to-test-1.1-SNAPSHOT.jar
                        teamcity-plugin.xml
                        ui-schemas/
                        ui-schemas/test
                        """)
                .executeAndVerify();
    }
}
