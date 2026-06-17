package org.jetbrains.teamcity.incremental;

import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.project.MavenProject;
import org.jetbrains.teamcity.Agent;
import org.jetbrains.teamcity.Descriptor;
import org.jetbrains.teamcity.Server;
import org.jetbrains.teamcity.SourceDest;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class IncrementalConfigStampBuilder {
    private final MavenProject project;
    private final Agent agent;
    private final Server server;
    private final MojoExecution execution;
    private final String includes;
    private final String excludes;
    private final String ignoreExtraFilesIn;
    private final String incrementalSnapshotExcludes;

    public IncrementalConfigStampBuilder(MavenProject project,
                                         Agent agent,
                                         Server server,
                                         MojoExecution execution,
                                         String includes,
                                         String excludes,
                                         String ignoreExtraFilesIn) {
        this(project, agent, server, execution, includes, excludes, ignoreExtraFilesIn, null);
    }

    public IncrementalConfigStampBuilder(MavenProject project,
                                         Agent agent,
                                         Server server,
                                         MojoExecution execution,
                                         String includes,
                                         String excludes,
                                         String ignoreExtraFilesIn,
                                         String incrementalSnapshotExcludes) {
        this.project = project;
        this.agent = agent;
        this.server = server;
        this.execution = execution;
        this.includes = includes;
        this.excludes = excludes;
        this.ignoreExtraFilesIn = ignoreExtraFilesIn;
        this.incrementalSnapshotExcludes = incrementalSnapshotExcludes;
    }

    public String build() {
        StringBuilder builder = new StringBuilder();
        appendConfigValue(builder, "project.groupId", project.getGroupId());
        appendConfigValue(builder, "project.artifactId", project.getArtifactId());
        appendConfigValue(builder, "project.version", project.getVersion());
        appendConfigValue(builder, "execution.id", execution == null ? null : execution.getExecutionId());
        appendConfigValue(builder, "includes", includes);
        appendConfigValue(builder, "excludes", excludes);
        appendConfigValue(builder, "ignoreExtraFilesIn", ignoreExtraFilesIn);
        appendConfigValue(builder, "incrementalSnapshotExcludes", incrementalSnapshotExcludes);
        appendAgentConfig(builder);
        appendServerConfig(builder);
        return builder.toString();
    }

    private void appendAgentConfig(StringBuilder builder) {
        if (agent == null) {
            appendConfigValue(builder, "agent", "null");
            return;
        }
        appendConfigValue(builder, "agent.spec", agent.getSpec());
        appendConfigValue(builder, "agent.pluginName", agent.getPluginName());
        appendConfigValue(builder, "agent.intellijProjectPath", agent.getIntellijProjectPath());
        appendConfigValue(builder, "agent.tool", Boolean.toString(agent.isTool()));
        appendConfigValue(builder, "agent.failOnMissingDependencies", Boolean.toString(agent.isFailOnMissingDependencies()));
        appendConfigValue(builder, "agent.removeVersionFromJar", Boolean.toString(agent.isRemoveVersionFromJar()));
        appendList(builder, "agent.exclusions", agent.getExclusions());
        appendList(builder, "agent.ignoreExtraFilesIn", agent.getIgnoreExtraFilesIn());
        appendDescriptor(builder, "agent.descriptor", agent.getDescriptor());
        appendExtras(builder, "agent.extras", agent.getExtras());
    }

    private void appendServerConfig(StringBuilder builder) {
        if (server == null) {
            appendConfigValue(builder, "server", "null");
            return;
        }
        appendConfigValue(builder, "server.spec", server.getSpec());
        appendConfigValue(builder, "server.pluginName", server.getPluginName());
        appendConfigValue(builder, "server.intellijProjectPath", server.getIntellijProjectPath());
        appendConfigValue(builder, "server.commonSpec", server.getCommonSpec());
        appendConfigValue(builder, "server.failOnMissingDependencies", Boolean.toString(server.isFailOnMissingDependencies()));
        appendConfigValue(builder, "server.excludeAgent", Boolean.toString(server.isExcludeAgent()));
        appendConfigValue(builder, "server.requireKotlinDsl", Boolean.toString(server.isRequireKotlinDsl()));
        appendConfigValue(builder, "server.removeVersionFromJar", Boolean.toString(server.isRemoveVersionFromJar()));
        appendList(builder, "server.exclusions", server.getExclusions());
        appendList(builder, "server.commonExclusions", server.getCommonExclusions());
        appendList(builder, "server.buildServerResources", server.getBuildServerResources());
        appendList(builder, "server.ignoreExtraFilesIn", server.getIgnoreExtraFilesIn());
        appendList(builder, "server.toolDependencies", server.getToolDependencies());
        appendDescriptor(builder, "server.descriptor", server.getDescriptor());
        appendExtras(builder, "server.extras", server.getExtras());
    }

    private void appendDescriptor(StringBuilder builder, String prefix, Descriptor descriptor) {
        if (descriptor == null) {
            appendConfigValue(builder, prefix, "null");
            return;
        }
        appendConfigValue(builder, prefix + ".doNotGenerate", Boolean.toString(descriptor.isDoNotGenerate()));
        appendConfigValue(builder, prefix + ".failOnMissing", Boolean.toString(descriptor.isFailOnMissing()));
        appendConfigValue(builder, prefix + ".allowRuntimeReload", String.valueOf(descriptor.getAllowRuntimeReload()));
        appendConfigValue(builder, prefix + ".nodeResponsibilitiesAware", String.valueOf(descriptor.getNodeResponsibilitiesAware()));
        appendConfigValue(builder, prefix + ".useSeparateClassloader", String.valueOf(descriptor.getUseSeparateClassloader()));
        appendConfigValue(builder, prefix + ".path", toPathString(descriptor.getPath() == null ? null : descriptor.getPath().toPath()));
        appendConfigValue(builder, prefix + ".pluginVersion", descriptor.getPluginVersion());
        appendList(builder, prefix + ".pluginDependencies", descriptor.getPluginDependencies());
        appendList(builder, prefix + ".toolDependencies", descriptor.getToolDependencies());
        appendMap(builder, prefix + ".parameters", descriptor.getParameters());
    }

    private void appendExtras(StringBuilder builder, String prefix, List<SourceDest> extras) {
        if (extras == null) {
            appendConfigValue(builder, prefix, "null");
            return;
        }
        int i;
        for (i = 0; i < extras.size(); i++) {
            SourceDest extra = extras.get(i);
            appendConfigValue(builder, prefix + "." + i + ".source", extra == null ? null : extra.getSource());
            appendConfigValue(builder, prefix + "." + i + ".destDir", extra == null ? null : extra.getDestDir());
            appendConfigValue(builder, prefix + "." + i + ".destName", extra == null ? null : extra.getDestName());
        }
    }

    private void appendMap(StringBuilder builder, String prefix, Map<String, String> values) {
        if (values == null) {
            appendConfigValue(builder, prefix, "null");
            return;
        }
        List<String> keys = new ArrayList<String>(values.keySet());
        Collections.sort(keys);
        int i;
        for (i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            appendConfigValue(builder, prefix + "." + key, values.get(key));
        }
    }

    private void appendList(StringBuilder builder, String key, List<String> values) {
        if (values == null) {
            appendConfigValue(builder, key, "null");
            return;
        }
        int i;
        for (i = 0; i < values.size(); i++) {
            appendConfigValue(builder, key + "." + i, values.get(i));
        }
    }

    private void appendConfigValue(StringBuilder builder, String key, String value) {
        if (builder.length() > 0) {
            builder.append('|');
        }
        builder.append(key).append('=').append(value == null ? "" : value);
    }

    private String toPathString(Path path) {
        return path == null ? null : path.toString();
    }
}
