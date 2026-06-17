package org.jetbrains.teamcity;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.project.MavenProject;
import org.jetbrains.teamcity.agent.ResultArtifact;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Field;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

public class AssemblePluginMojoTestCase extends BasePluginTestCase {

    @Test
    public void testMakeAgentArtifact() throws Exception {
        MavenSession session = initMavenSession("agent/simple");
        MojoExecution execution = rule.newMojoExecution("build");
        AssemblePluginMojo mojo = (AssemblePluginMojo) rule.lookupConfiguredMojo(session, execution);
        mojo.execute();
        StringJoiner sb = new StringJoiner("\n");
        appendTestResult(sb, mojo.getAgentPluginWorkflow());
        Assert.assertEquals("AGENT:\n" +
                "lib\n" +
                "lib/commons-beanutils-core-1.8.3.jar\n" +
                "lib/commons-logging-1.1.1.jar\n" +
                "teamcity-plugin.xml", sb.toString());
        filesAreEqual(mojo.getAgentPluginWorkflow().getPluginDescriptorPath(), """
                <?xml version="1.0" encoding="UTF-8"?>
                <teamcity-agent-plugin xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                                       xsi:noNamespaceSchemaLocation="urn:schemas-jetbrains-com:teamcity-agent-plugin-v1-xml">
                    <!-- @@AGENT_PLUGIN_NAME=simple2@@ -->
                        
                        <plugin-deployment
                        />
                        <dependencies>
                                <plugin name="java-dowser"/>
                                <tool name="ant"/>
                        </dependencies>
                </teamcity-agent-plugin>""");
    }

    @Test
    public void testMakeAgentArtifactWithoutVersionInJarNames() throws Exception {
        MavenSession session = initMavenSession("unit/module-war/module-agent");
        MojoExecution execution = rule.newMojoExecution("build");
        AssemblePluginMojo mojo = (AssemblePluginMojo) rule.lookupConfiguredMojo(session, execution);
        mojo.setFailOnMissingDependencies(false);
        mojo.getAgent().setRemoveVersionFromJar(true);
        mojo.execute();

        StringJoiner sb = new StringJoiner("\n");
        appendTestResult(sb, mojo.getAgentPluginWorkflow());
        Assert.assertEquals("AGENT:\n" +
                "lib\n" +
                "lib/module-agent.jar\n" +
                "teamcity-plugin.xml", sb.toString());
    }

    @Test
    public void testIncrementalAssembleSkipsRepackingAndReattachesArtifacts() throws Exception {
        MavenSession session = initMavenSession("unit/project-to-test");
        deleteRecursively(Paths.get(session.getCurrentProject().getBuild().getDirectory(), "teamcity"));
        MojoExecution execution = rule.newMojoExecution("build");
        AssemblePluginMojo firstMojo = (AssemblePluginMojo) rule.lookupConfiguredMojo(session, execution);
        firstMojo.setFailOnMissingDependencies(false);
        enableIncrementalIfSupported(firstMojo);
        firstMojo.execute();

        List<org.jetbrains.teamcity.agent.ResultArtifact> firstArtifacts = new ArrayList<>(firstMojo.getAgentPluginWorkflow().getAttachedArtifacts());
        firstArtifacts.addAll(firstMojo.getServerPluginWorkflow().getAttachedArtifacts());
        assertThat(firstArtifacts).isNotEmpty();

        Map<String, Long> timestamps = new LinkedHashMap<>();
        for (org.jetbrains.teamcity.agent.ResultArtifact artifact : firstArtifacts) {
            timestamps.put(artifact.getClassifier(), Files.getLastModifiedTime(artifact.getFile()).toMillis());
        }

        Thread.sleep(1100L);

        MavenSession secondSession = initMavenSession("unit/project-to-test");
        MojoExecution secondExecution = rule.newMojoExecution("build");
        AssemblePluginMojo secondMojo = (AssemblePluginMojo) rule.lookupConfiguredMojo(secondSession, secondExecution);
        secondMojo.setFailOnMissingDependencies(false);
        enableIncrementalIfSupported(secondMojo);
        secondMojo.execute();

        assertThat(secondMojo.getAgentPluginWorkflow()).isNull();
        assertThat(secondMojo.getServerPluginWorkflow()).isNull();
        assertThat(secondSession.getCurrentProject().getProperties().getProperty("maven.install.skip")).isEqualTo("true");
        assertThat(secondMojo.getAttachedArtifact()).hasSize(firstArtifacts.size());

        for (org.apache.maven.artifact.Artifact artifact : secondMojo.getAttachedArtifact()) {
            Long previousTimestamp = timestamps.get(artifact.getClassifier());
            assertThat(previousTimestamp).isNotNull();
            assertThat(Files.getLastModifiedTime(artifact.getFile().toPath()).toMillis()).isEqualTo(previousTimestamp);
        }
    }

    @Test
    public void testIncrementalAssembleSkipsWhenExtraSourceBelongsToReactorProject() throws Exception {
        MavenSession session = initMavenSession("unit/reactor-extra", "producer");
        deleteRecursively(Paths.get(session.getCurrentProject().getBuild().getDirectory(), "teamcity"));

        MavenProject producer = findProject(session, "producer");
        Path extraArtifact = Paths.get(producer.getBuild().getDirectory(), "producer-1.1-SNAPSHOT-jar-with-dependencies.jar");
        Files.createDirectories(extraArtifact.getParent());
        Files.writeString(extraArtifact, "same-reactor-extra");

        MojoExecution execution = rule.newMojoExecution("build");
        AssemblePluginMojo firstMojo = (AssemblePluginMojo) rule.lookupConfiguredMojo(session, execution);
        firstMojo.setFailOnMissingDependencies(false);
        enableIncrementalIfSupported(firstMojo);
        firstMojo.execute();

        List<org.jetbrains.teamcity.agent.ResultArtifact> firstArtifacts = new ArrayList<>(firstMojo.getAgentPluginWorkflow().getAttachedArtifacts());
        assertThat(firstArtifacts).isNotEmpty();

        Map<String, Long> timestamps = new LinkedHashMap<>();
        for (org.jetbrains.teamcity.agent.ResultArtifact artifact : firstArtifacts) {
            timestamps.put(artifact.getClassifier(), Files.getLastModifiedTime(artifact.getFile()).toMillis());
        }

        Thread.sleep(1100L);
        Files.writeString(extraArtifact, "same-reactor-extra");

        MavenSession secondSession = initMavenSession("unit/reactor-extra", "producer");
        MojoExecution secondExecution = rule.newMojoExecution("build");
        AssemblePluginMojo secondMojo = (AssemblePluginMojo) rule.lookupConfiguredMojo(secondSession, secondExecution);
        secondMojo.setFailOnMissingDependencies(false);
        enableIncrementalIfSupported(secondMojo);
        secondMojo.execute();

        assertThat(secondMojo.getAgentPluginWorkflow()).isNull();
        assertThat(secondMojo.getServerPluginWorkflow()).isNull();
        assertThat(secondMojo.getAttachedArtifact()).hasSize(firstArtifacts.size());

        for (org.apache.maven.artifact.Artifact artifact : secondMojo.getAttachedArtifact()) {
            Long previousTimestamp = timestamps.get(artifact.getClassifier());
            assertThat(previousTimestamp).isNotNull();
            assertThat(Files.getLastModifiedTime(artifact.getFile().toPath()).toMillis()).isEqualTo(previousTimestamp);
        }
    }

    @Test
    public void testIncrementalAssembleSkipsWhenExtraSourceBelongsToCurrentProject() throws Exception {
        MavenSession session = initMavenSession("unit/self-extra");
        deleteRecursively(Paths.get(session.getCurrentProject().getBuild().getDirectory(), "teamcity"));

        Path extraDir = Paths.get(session.getCurrentProject().getBuild().getDirectory(), "self-dist");
        Files.createDirectories(extraDir);
        Files.writeString(extraDir.resolve("payload.txt"), "same-self-extra");

        MojoExecution execution = rule.newMojoExecution("build");
        AssemblePluginMojo firstMojo = (AssemblePluginMojo) rule.lookupConfiguredMojo(session, execution);
        firstMojo.setFailOnMissingDependencies(false);
        enableIncrementalIfSupported(firstMojo);
        firstMojo.execute();

        List<ResultArtifact> firstArtifacts = new ArrayList<ResultArtifact>(firstMojo.getAgentPluginWorkflow().getAttachedArtifacts());
        assertThat(firstArtifacts).isNotEmpty();

        Map<String, Long> timestamps = new LinkedHashMap<String, Long>();
        for (ResultArtifact artifact : firstArtifacts) {
            timestamps.put(artifact.getClassifier(), Files.getLastModifiedTime(artifact.getFile()).toMillis());
        }

        MavenSession secondSession = initMavenSession("unit/self-extra");
        MojoExecution secondExecution = rule.newMojoExecution("build");
        AssemblePluginMojo secondMojo = (AssemblePluginMojo) rule.lookupConfiguredMojo(secondSession, secondExecution);
        secondMojo.setFailOnMissingDependencies(false);
        enableIncrementalIfSupported(secondMojo);
        secondMojo.execute();

        assertThat(secondMojo.getAgentPluginWorkflow()).isNull();
        assertThat(secondMojo.getServerPluginWorkflow()).isNull();
        assertThat(secondMojo.getAttachedArtifact()).hasSize(firstArtifacts.size());

        for (org.apache.maven.artifact.Artifact artifact : secondMojo.getAttachedArtifact()) {
            Long previousTimestamp = timestamps.get(artifact.getClassifier());
            assertThat(previousTimestamp).isNotNull();
            assertThat(Files.getLastModifiedTime(artifact.getFile().toPath()).toMillis()).isEqualTo(previousTimestamp);
        }
    }

    private void enableIncrementalIfSupported(AssemblePluginMojo mojo) throws Exception {
        try {
            Field field = AssemblePluginMojo.class.getDeclaredField("incremental");
            field.setAccessible(true);
            field.setBoolean(mojo, true);
        } catch (NoSuchFieldException ignored) {
        }
    }

    private void deleteRecursively(Path path) throws Exception {
        if (!Files.exists(path)) {
            return;
        }

        Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws java.io.IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, java.io.IOException exc) throws java.io.IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private MavenProject findProject(MavenSession session, String artifactId) {
        for (MavenProject project : session.getProjects()) {
            if (project != null && artifactId.equals(project.getArtifactId())) {
                return project;
            }
        }
        throw new AssertionError("Project not found: " + artifactId);
    }

    @Test
    public void testMakeSimpleArtifact() throws Exception {
        MavenSession session = initMavenSession("unit/project-to-test");
        Path ignoredBundle = Paths.get(session.getCurrentProject().getBuild().getDirectory(), "teamcity", "plugin", "project-to-test", "bundles", "1");
        Files.createDirectories(ignoredBundle.getParent());
        Files.writeString(ignoredBundle, "");
        MojoExecution execution = rule.newMojoExecution("build");
        AssemblePluginMojo mojo = (AssemblePluginMojo) rule.lookupConfiguredMojo(session, execution);
        mojo.setFailOnMissingDependencies(false);
        mojo.getServer().setIgnoreExtraFilesIn(List.of("bundles"));
        mojo.execute();
        String sb = getTestResult(mojo);
        assertThat(sb).asString().isEqualToIgnoringNewLines("AGENT:\n" +
                "lib\n" +
                "lib/commons-beanutils-core-1.8.3.jar\n" +
                "lib/commons-logging-1.1.1.jar\n" +
                "teamcity-plugin.xml\n" +
                "SERVER:\n" +
                "agent/\n" +
                "agent/project-to-test.zip\n" +
                "bundles/\n" +
                "bundles/1\n" +
                "kotlin-dsl/\n" +
                "kotlin-dsl/test\n" +
                "server/\n" +
                "server/commons-codec-1.15.jar\n" +
                "server/project-to-test-1.1-SNAPSHOT.jar\n" +
                "teamcity-plugin.xml" +
                "ui-schemas/\n" +
                "ui-schemas/test\n");
        // language=XML
        filesAreEqual(mojo.getAgentPluginWorkflow().getPluginDescriptorPath(), """
                <?xml version="1.0" encoding="UTF-8"?>
                <teamcity-agent-plugin xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                                       xsi:noNamespaceSchemaLocation="urn:schemas-jetbrains-com:teamcity-agent-plugin-v1-xml">
                        
                        <plugin-deployment
                        />
                        <dependencies>
                                <plugin name="java-dowser"/>
                                <tool name="ant"/>
                        </dependencies>
                </teamcity-agent-plugin>""");

        filesAreEqual(mojo.getServerPluginWorkflow().getPluginDescriptorPath(),
                // language=XML
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <teamcity-plugin xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                                 xsi:noNamespaceSchemaLocation="urn:schemas-jetbrains-com:teamcity-plugin-v1-xml">
                    <info>
                        <name>project-to-test</name>
                        <display-name>Test</display-name>
                        <version>1.1-SNAPSHOT</version>
                    </info>
                    <deployment
                    />
                </teamcity-plugin>
                """);
    }

    @Test
    public void testMakeSimpleServerArtifactWithoutVersionInJarNames() throws Exception {
        MavenSession session = initMavenSession("unit/server-jar-simple");
        MojoExecution execution = rule.newMojoExecution("build");
        AssemblePluginMojo mojo = (AssemblePluginMojo) rule.lookupConfiguredMojo(session, execution);
        mojo.setFailOnMissingDependencies(false);
        mojo.getServer().setRemoveVersionFromJar(true);
        mojo.execute();

        String sb = getTestResult(mojo);
        assertThat(sb).asString().isEqualToIgnoringNewLines("SERVER:\n" +
                "agent/\n" +
                "server/\n" +
                "server/server-jar-simple.jar\n" +
                "teamcity-plugin.xml");
    }

    @Test
    public void testDependencyToPlugin() throws Exception {
        MavenSession session = initMavenSession("unit/dependency-to-plugin", "moduleA");
        MojoExecution execution = rule.newMojoExecution("build");
        AssemblePluginMojo mojo = (AssemblePluginMojo) rule.lookupConfiguredMojo(session, execution);
        mojo.setFailOnMissingDependencies(false);
        mojo.execute();
        String sb = getTestResult(mojo);
        Assert.assertEquals("SERVER:\n" +
                "agent/\n" +
                "agent/moduleA.zip\n" +
                "server/\n" +
                "server/commons-beanutils-core-1.8.3.jar\n" +
                "server/commons-codec-1.15.jar\n" +
                "server/commons-logging-1.1.1.jar\n" +
                "server/dependency-to-plugin-1.1-SNAPSHOT.jar\n" +
                "teamcity-plugin.xml", sb);

    }

    @Test
    public void testMakeMultiModuleArtifact() throws Exception {
        MavenSession session = initMavenSession("unit/multi-module-to-test");
        MojoExecution execution = rule.newMojoExecution("build");
        AssemblePluginMojo mojo = (AssemblePluginMojo) rule.lookupConfiguredMojo(session, execution);
        mojo.setFailOnMissingDependencies(false);
        mojo.execute();
        String sb = getTestResult(mojo);
        sa.assertThat(sb).isEqualToIgnoringCase("""
                AGENT:
                lib
                lib/commons-beanutils-core-1.8.3.jar
                lib/commons-logging-1.1.1.jar
                lib/moduleA-1.1-SNAPSHOT.jar
                lib/moduleB-1.1-SNAPSHOT.jar
                teamcity-plugin.xml
                SERVER:
                agent/
                agent/multi-module-to-test.zip
                server/
                server/moduleB-1.1-SNAPSHOT.jar
                teamcity-plugin.xml""");
    }


}
