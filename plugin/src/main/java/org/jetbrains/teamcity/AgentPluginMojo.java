package org.jetbrains.teamcity;

import lombok.Getter;
import lombok.Setter;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.shared.dependency.graph.DependencyNode;
import org.jetbrains.teamcity.agent.AgentPluginWorkflow;
import org.jetbrains.teamcity.agent.WorkflowUtil;

import java.io.File;
import java.io.IOException;
import java.util.List;

@Mojo(
        name = "build-agent",
        defaultPhase = LifecyclePhase.PACKAGE,
        threadSafe = true,
        aggregator = true,
        requiresProject = true,
        requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME,
        requiresDependencyCollection = ResolutionScope.COMPILE_PLUS_RUNTIME
)
@Getter
@Setter
public class AgentPluginMojo extends BaseTeamCityMojo {
    @Parameter(property = "spec")
    private String spec;
    @Parameter(defaultValue = "${project.artifactId}")
    private String pluginName;
    @Parameter(defaultValue = "")
    private String intellijProjectPath;
    @Parameter(defaultValue = "org.jetbrains.teamcity,::zip")
    private List<String> exclusions;
    @Parameter
    private boolean tool; // if this is tool deployment
    @Parameter(defaultValue = "true")
    private boolean failOnMissingDependencies = true;
    @Parameter(defaultValue = "false")
    private boolean createIdeaArtifacts = false;
    @Parameter
    private List<String> ignoreExtraFilesIn;

    @Parameter(defaultValue = "${project.build.outputDirectory}/META-INF/teamcity-agent-plugin.xml")
    private File descriptorPath;

    @Parameter
    private Descriptor descriptor = new Descriptor();
    @Parameter(property = "extras")
    private List<SourceDest> extras;

    private AgentPluginWorkflow agentPluginWorkflow;
    @Parameter( defaultValue = "${teamcity.plugin.version}", readonly = true )
    private String pluginVersion;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        getLog().info("TeamCity Agent Assemble start");
        try {
            WorkflowUtil util = getWorkflowUtil();
            Agent agent = new Agent(spec, pluginName, intellijProjectPath, exclusions, tool, failOnMissingDependencies, ignoreExtraFilesIn, descriptor, getProject().getArtifactId(), extras);
            agent.setDefaultValues(".", getProject(), getProjectBuildOutputDirectory(), pluginVersion);
            DependencyNode rootNode = findRootNode(util);
            agentPluginWorkflow = new AgentPluginWorkflow(rootNode, agent, util, getWorkDirectory().toPath(), isCreateIdeaArtifacts());
            agentPluginWorkflow.execute();
            attachArtifacts(agentPluginWorkflow.getAttachedArtifacts());
        } catch (IOException e) {
            getLog().warn(e);
            throw new MojoFailureException("Error while assembly execution", e);
        }

    }
}
