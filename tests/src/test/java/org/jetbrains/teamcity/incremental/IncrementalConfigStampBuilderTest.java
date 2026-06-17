package org.jetbrains.teamcity.incremental;

import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.project.MavenProject;
import org.jetbrains.teamcity.Agent;
import org.jetbrains.teamcity.Descriptor;
import org.jetbrains.teamcity.Server;
import org.jetbrains.teamcity.SourceDest;
import org.junit.Test;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class IncrementalConfigStampBuilderTest {
    @Test
    public void buildsStableConfigStampIncludingSortedMapsAndExtras() {
        MavenProject project = new MavenProject();
        project.setGroupId("jb.int");
        project.setArtifactId("sample-plugin");
        project.setVersion("1.2-SNAPSHOT");

        Agent agent = new Agent();
        agent.setSpec("a:b:*:*");
        agent.setPluginName("agent-plugin");
        agent.setTool(true);
        agent.setFailOnMissingDependencies(false);
        agent.setExclusions(List.of("z", "a"));
        agent.setIgnoreExtraFilesIn(List.of("agent-ignore"));
        agent.setExtras(List.of(extra("agent-source.jar", "lib", "agent.jar")));
        Descriptor agentDescriptor = new Descriptor();
        agentDescriptor.setPath(new File("target/agent.xml"));
        agentDescriptor.setPluginVersion("999");
        agentDescriptor.setPluginDependencies(List.of("dep-b", "dep-a"));
        LinkedHashMap<String, String> agentParameters = new LinkedHashMap<String, String>();
        agentParameters.put("zeta", "last");
        agentParameters.put("alpha", "first");
        agentDescriptor.setParameters(agentParameters);
        agent.setDescriptor(agentDescriptor);

        Server server = new Server();
        server.setSpec("c:d:*:*");
        server.setPluginName("server-plugin");
        server.setCommonSpec("common:*");
        server.setExcludeAgent(false);
        server.setRequireKotlinDsl(true);
        server.setBuildServerResources(List.of("resources/a", "resources/b"));
        server.setCommonExclusions(List.of("common-exclusion"));
        server.setToolDependencies(List.of("tool-a"));
        server.setExtras(List.of(extra("server-source.jar", "server", "server.jar")));
        server.setKotlinDslDescriptorsPath(new File("target/kdsl"));
        server.setUiSchemasPath(new File("target/ui"));

        MojoExecution execution = new MojoExecution(new Plugin(), "build", "build");

        IncrementalConfigStampBuilder builder = new IncrementalConfigStampBuilder(
                project,
                agent,
                server,
                execution,
                "include",
                "exclude",
                "ignore-extra"
        );

        String stamp = builder.build();

        assertThat(stamp).contains("project.groupId=jb.int");
        assertThat(stamp).contains("project.artifactId=sample-plugin");
        assertThat(stamp).contains("execution.id=build");
        assertThat(stamp).contains("includes=include");
        assertThat(stamp).contains("excludes=exclude");
        assertThat(stamp).contains("ignoreExtraFilesIn=ignore-extra");
        assertThat(stamp).contains("agent.spec=a:b:*:*");
        assertThat(stamp).contains("agent.extras.0.source=agent-source.jar");
        assertThat(stamp).contains("server.extras.0.destName=server.jar");
        assertThat(stamp).contains("server.requireKotlinDsl=true");
        assertThat(stamp).contains("agent.descriptor.path=target/agent.xml");
        assertThat(stamp.indexOf("agent.descriptor.parameters.alpha=first"))
                .isLessThan(stamp.indexOf("agent.descriptor.parameters.zeta=last"));
    }

    private static SourceDest extra(String source, String destDir, String destName) {
        SourceDest extra = new SourceDest();
        extra.setSource(source);
        extra.setDestDir(destDir);
        extra.setDestName(destName);
        return extra;
    }
}
