package org.jetbrains.teamcity;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecution;
import org.junit.Test;

import java.lang.reflect.Field;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

import static org.assertj.core.api.Assertions.assertThat;

public class ModuleWarTestCase extends BasePluginTestCase {
    @Test
    public void testServerArtifact() throws Exception {
        MavenSession session = initMavenSession("unit/module-war", "module-agent");
        MojoExecution execution = rule.newMojoExecution("build");
        AssemblePluginMojo mojo = (AssemblePluginMojo) rule.lookupConfiguredMojo(session, execution);
        mojo.setFailOnMissingDependencies(false);
        mojo.execute();
        StringJoiner sb = new StringJoiner("\n");
        appendTestResult(sb, mojo.getServerPluginWorkflow());
        assertThat(sb.toString()).isEqualTo("""
                SERVER:
                1
                agent/
                agent/module-agent.zip
                server/
                server/lib/
                server/lib/3
                server/module-war-teamcity-plugin-resources.jar
                teamcity-plugin.xml""");
    }
        @Test
    public void testAgentArtifact() throws Exception {
        MavenSession session = initMavenSession("unit/module-war/module-agent");
        MojoExecution execution = rule.newMojoExecution("build");
        AssemblePluginMojo mojo = (AssemblePluginMojo) rule.lookupConfiguredMojo(session, execution);
        mojo.setFailOnMissingDependencies(false);
        mojo.execute();
        StringJoiner sb = new StringJoiner("\n");
        appendTestResult(sb, mojo.getAgentPluginWorkflow());
        assertThat("""
                AGENT:
                lib
                lib/module-agent-1.1.jar
                teamcity-plugin.xml""").isEqualTo(sb.toString());

        filesAreEqual(mojo.getAgentPluginWorkflow().getPluginDescriptorPath(), """
                <?xml version="1.0" encoding="UTF-8"?>
                <teamcity-agent-plugin xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                                       xsi:noNamespaceSchemaLocation="urn:schemas-jetbrains-com:teamcity-agent-plugin-v1-xml">
                                
                        <plugin-deployment
                        />
                </teamcity-agent-plugin>""");
        Descriptor descriptor = mojo.getAgent().getDescriptor();
        descriptor.setUseSeparateClassloader(true);
        descriptor.setToolDependencies(List.of("plugin1", "plugin2"));
        descriptor.setPluginDependencies(List.of("plugin1", "plugin2"));
        mojo.execute();
        filesAreEqual(mojo.getAgentPluginWorkflow().getPluginDescriptorPath(), """
                <?xml version="1.0" encoding="UTF-8"?>
                <teamcity-agent-plugin xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                                       xsi:noNamespaceSchemaLocation="urn:schemas-jetbrains-com:teamcity-agent-plugin-v1-xml">
                
                        <plugin-deployment 
                                        use-separate-classloader="true"
                        />
                        <dependencies>
                                <plugin name="plugin1"/>
                                <plugin name="plugin2"/>
                                <tool name="plugin1"/>
                                <tool name="plugin2"/>
                        </dependencies>
                </teamcity-agent-plugin>""");
    }

    @Test
    public void testIncrementalAssembleSkipsWhenWarArtifactTimestampChanges() throws Exception {
        MavenSession session = initMavenSession("unit/module-war", "module-agent");
        Path buildDirectory = Path.of(session.getCurrentProject().getBuild().getDirectory());
        deleteRecursively(buildDirectory.resolve("teamcity"));
        Path warArtifact = buildDirectory.resolve("module-war-1.1.war");
        Files.createDirectories(buildDirectory);
        Files.writeString(warArtifact, "same-war-content");
        session.getCurrentProject().getArtifact().setFile(warArtifact.toFile());

        MojoExecution execution = rule.newMojoExecution("build");
        AssemblePluginMojo firstMojo = (AssemblePluginMojo) rule.lookupConfiguredMojo(session, execution);
        firstMojo.setFailOnMissingDependencies(false);
        enableIncrementalIfSupported(firstMojo);
        firstMojo.execute();

        Map<String, Long> timestamps = new LinkedHashMap<>();
        for (org.jetbrains.teamcity.agent.ResultArtifact artifact : firstMojo.getServerPluginWorkflow().getAttachedArtifacts()) {
            timestamps.put(artifact.getClassifier(), Files.getLastModifiedTime(artifact.getFile()).toMillis());
        }

        Thread.sleep(1100L);
        Files.writeString(warArtifact, "same-war-content");

        MavenSession secondSession = initMavenSession("unit/module-war", "module-agent");
        secondSession.getCurrentProject().getArtifact().setFile(warArtifact.toFile());
        MojoExecution secondExecution = rule.newMojoExecution("build");
        AssemblePluginMojo secondMojo = (AssemblePluginMojo) rule.lookupConfiguredMojo(secondSession, secondExecution);
        secondMojo.setFailOnMissingDependencies(false);
        enableIncrementalIfSupported(secondMojo);
        secondMojo.execute();

        assertThat(secondMojo.getServerPluginWorkflow()).isNull();
        assertThat(secondMojo.getAttachedArtifact()).hasSize(firstMojo.getServerPluginWorkflow().getAttachedArtifacts().size());

        for (org.apache.maven.artifact.Artifact artifact : secondMojo.getAttachedArtifact()) {
            Long previousTimestamp = timestamps.get(artifact.getClassifier());
            assertThat(previousTimestamp).isNotNull();
            long currentTimestamp = Files.getLastModifiedTime(artifact.getFile().toPath()).toMillis();
            assertThat(currentTimestamp).isEqualTo(previousTimestamp.longValue());
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

}
