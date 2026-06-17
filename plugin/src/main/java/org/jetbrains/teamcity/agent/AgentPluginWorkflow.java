package org.jetbrains.teamcity.agent;

import lombok.Data;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.shared.dependency.graph.DependencyNode;
import org.jetbrains.teamcity.Agent;
import org.jetbrains.teamcity.data.ResolvedArtifact;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static org.jetbrains.teamcity.agent.WorkflowUtil.TEAMCITY_PLUGIN_XML;

@Data
public class AgentPluginWorkflow {
    public static final String TEAMCITY_AGENT_PLUGIN_CLASSIFIER = "teamcity-agent-plugin";
    public static final String TEAMCITY_TOOL_CLASSIFIER = "teamcity-tool";
    private final DependencyNode rootNode;
    private final Agent parameters;
    private final WorkflowUtil util;

    private final List<AssemblyContext> assemblyContexts = new ArrayList<>();

    private final List<ResultArtifact> attachedArtifacts = new ArrayList<>();

    private Path agentPath;
    private Path pluginDescriptorPath;

    public List<ResultArtifact> execute() throws MojoExecutionException {
        if (parameters.isNeedToBuild()) {
            AssemblyContext assemblyContext = buildAgentPlugin(rootNode);
            assemblyContexts.add(assemblyContext);
        }
        return attachedArtifacts;
    }

    public AssemblyContext buildAgentPlugin(DependencyNode rootNode) throws MojoExecutionException {
        Path agentUnpacked = util.getWorkDirectory().resolve("agent-unpacked");
        agentPath  = util.createDir(agentUnpacked.resolve(parameters.getPluginName()));
        AssemblyContext assemblyContext = util.createAssemblyContext("AGENT", "EXPLODED", agentPath);

        AssemblyContext ideaAssemblyContext = util.createAssemblyContext("AGENT", "4IDEA", agentUnpacked);
        ideaAssemblyContext.getPaths().add(new PathSet(agentPath).with(new ArtifactPathEntry(null, assemblyContext.getName())));
        assemblyContexts.add(ideaAssemblyContext.cloneWithRoot());


        /**
         * pluginRoot/
         * |-agent/
         * | |-plugin_name.zip
         * |   |-plugin_name/
         * |     |-dependencies.jar
         * |-server
         * |-teamcity-plugin.xml
         */
        Path agentLibPath = util.createDir(parameters.isTool() ?  agentPath : agentPath.resolve("lib"));
        assemblyContext.getPaths().add(new PathSet(agentLibPath));
        List<Artifact> nodes = util.getDependencyNodeList(rootNode, parameters.getSpec(), parameters.getExclusions());
        Pair<List<ResolvedArtifact>, List<Path>> artifacts = util.copyTransitiveDependenciesInto(parameters.isFailOnMissingDependencies(), parameters.isRemoveVersionFromJar(), assemblyContext, nodes, agentLibPath);
        List<Path> destinations = new ArrayList<>(artifacts.getRight());

        if (!nodes.isEmpty()) {

            File targetDescriptorPath = parameters.getDescriptor().getPath();
            if (!targetDescriptorPath.exists() && !parameters.getDescriptor().isDoNotGenerate()) {
                try {
                    Path generatedPath = util.getWorkDirectory().resolve("teamcity-agent-plugin-generated.xml");
                    util.createDescriptor("teamcity-agent-plugin.vm", generatedPath, parameters);
                    targetDescriptorPath = generatedPath.toFile();
                } catch (IOException e) {
                    util.getLog().warn("Error while generating agent descriptor: " + agentPath, e);
                }
            }

            if (targetDescriptorPath.exists()) {
                try {
                    pluginDescriptorPath = agentPath.resolve(TEAMCITY_PLUGIN_XML);
                    destinations.add(pluginDescriptorPath);
                    Files.copy(targetDescriptorPath.toPath(), pluginDescriptorPath, REPLACE_EXISTING);
                } catch (IOException e) {
                    throw new MojoExecutionException(String.format("Can't copy %s.", targetDescriptorPath), e);
                }
            } else if (parameters.getDescriptor().isFailOnMissing()) {
                throw new MojoExecutionException(String.format("`agent.pluginDescriptorPath` must point to teamcity plugin descriptor (%s).", targetDescriptorPath));
            }
            assemblyContext.getPaths().add(new PathSet(agentPath).with(new FilePathEntry(TEAMCITY_PLUGIN_XML, targetDescriptorPath.toPath()))); // add it anyway if it exists or not

            if (parameters.hasExtras()) {
                util.processExtras(parameters.getExtras(), parameters.isRemoveVersionFromJar(), agentPath, assemblyContext, destinations);
            }

            Path agentPluginPath = util.getWorkDirectory().resolve("agent");
            try {
                String zipName = parameters.getPluginName() + ".zip";
                AssemblyContext zipAssemblyContext = util.createAssemblyContext("AGENT", agentPluginPath);
                zipAssemblyContext.getPaths().add(new PathSet(agentPluginPath).with(new ArtifactPathEntry(zipName, assemblyContext.getName())));
                assemblyContexts.add(zipAssemblyContext.cloneWithRoot(agentPluginPath));

                Path agentPart = util.zipFile(agentPath, Files.createDirectories(agentPluginPath), zipName);
                attachedArtifacts.add(new ResultArtifact("zip", parameters.isTool() ?  TEAMCITY_TOOL_CLASSIFIER :TEAMCITY_AGENT_PLUGIN_CLASSIFIER, agentPart, zipAssemblyContext));
            } catch (IOException | MojoFailureException e) {
                util.getLog().warn("Error while packing agent part to: " + agentPluginPath, e);
            }
        }

        util.removeOtherFiles(parameters.getIgnoreExtraFilesIn(), agentPath, destinations);
        return assemblyContext.cloneWithRoot();
    }
}
