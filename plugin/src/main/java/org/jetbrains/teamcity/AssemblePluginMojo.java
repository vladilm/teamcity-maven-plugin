package org.jetbrains.teamcity;

import lombok.Getter;
import lombok.Setter;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.lifecycle.LifeCyclePluginAnalyzer;
import org.apache.maven.lifecycle.LifecycleExecutor;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.PluginManager;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugins.annotations.*;
import org.apache.maven.project.MavenProjectHelper;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilder;
import org.apache.maven.shared.dependency.graph.DependencyNode;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.eclipse.aether.repository.RemoteRepository;
import org.jetbrains.teamcity.agent.AgentPluginWorkflow;
import org.jetbrains.teamcity.agent.ResultArtifact;
import org.jetbrains.teamcity.agent.WorkflowUtil;
import org.jetbrains.teamcity.incremental.IncrementalCheckResult;
import org.jetbrains.teamcity.incremental.IncrementalState;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Mojo(
        name = "build",
        defaultPhase = LifecyclePhase.PACKAGE,
        threadSafe = true,
        aggregator = false,
        requiresProject = true,
        requiresDependencyResolution = ResolutionScope.TEST,
        requiresDependencyCollection = ResolutionScope.TEST
)
public class AssemblePluginMojo extends BaseTeamCityMojo {
    @Parameter(defaultValue = "${project.remoteProjectRepositories}")
    private List<RemoteRepository> projectRepos;

    @Parameter(defaultValue = "${mojoExecution}", readonly = true, required = true)
    private MojoExecution mojoExecution;

    @Parameter(property = "ignoreExtraFilesIn")
    private String ignoreExtraFilesIn;

    @Component(hint = "default")
    private DependencyGraphBuilder dependencyGraphBuilder;

    @Component
    private MavenProjectHelper projectHelper;

    @Parameter(property = "includes")
    private String includes;

    @Parameter(property = "excludes")
    private String excludes;

    @Parameter(defaultValue = "${localRepository}", readonly = true, required = true)
    private ArtifactRepository local;

    @Component
    private LifecycleExecutor lifecycleExecutor;
    @Parameter( defaultValue = "${mojoExecution}", readonly = true )
    private MojoExecution execution;
    @Parameter( defaultValue = "${teamcity.plugin.version}", readonly = true )
    private String pluginVersion;
    @Parameter( defaultValue = "${plugin}", readonly = true )
    private PluginDescriptor pluginDescriptor;
    @Component
    private PluginManager pluginManager;


    @Component
    private LifeCyclePluginAnalyzer lifeCyclePluginAnalyzer;

    /**
     * TeamCity Agent configuration parameters.
     */
    @Parameter(property = "agent")
    @Getter
    @Setter
    private Agent agent;

    /**
     * TeamCity Server configuration parameters.
     */
    @Parameter(property = "server")
    @Getter
    @Setter
    private Server server;
    @Parameter(defaultValue = "false")
    private boolean createIdeaArtifacts = false;

    @Getter
    private AgentPluginWorkflow agentPluginWorkflow;
    @Getter
    private ServerPluginWorkflow serverPluginWorkflow;
    @Parameter(property = "teamcity.assemble.incremental", defaultValue = "false")
    private boolean incremental;
    @Parameter(property = "teamcity.assemble.incremental.excludes")
    private String incrementalSnapshotExcludes;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        setDefaultconfigurationValues(pluginVersion);
        if (!agent.isNeedToBuild() && !server.isNeedToBuild())
            return;
        try {
            IncrementalAssembleSupport incrementalSupport = new IncrementalAssembleSupport(
                    getProject(),
                    getSession(),
                    getWorkDirectory().toPath(),
                    getProjectBuildOutputDirectory(),
                    agent,
                    server,
                    execution,
                    createIdeaArtifacts,
                    includes,
                    excludes,
                    ignoreExtraFilesIn,
                    incrementalSnapshotExcludes
            );
            final WorkflowUtil util = getWorkflowUtil();
            final DependencyNode rootNode = findRootNode();
            getLog().debug("Dependency Tree: " + util.serializeDependencyTree(rootNode));

            if (incremental) {
                IncrementalState previousState = incrementalSupport.loadState();
                IncrementalCheckResult checkResult = incrementalSupport.checkCheapState(previousState);
                if (!checkResult.isComplete()) {
                    checkResult = incrementalSupport.checkCurrentState(previousState, rootNode);
                }
                if (checkResult.isUpToDate()) {
                    getLog().info("TeamCity Assemble is up-to-date, skipping");
                    attachArtifacts(incrementalSupport.restoreAttachedArtifacts(previousState));
                    return;
                }
                getLog().info("TeamCity Assemble incremental miss: " + checkResult.getReason());
            } else {
                getLog().info("TeamCity Assemble start");
            }

            agentPluginWorkflow = new AgentPluginWorkflow(rootNode, agent, util, getWorkDirectory().toPath(), createIdeaArtifacts);
            agentPluginWorkflow.execute();
            attachArtifacts(agentPluginWorkflow.getAttachedArtifacts());

            serverPluginWorkflow = new ServerPluginWorkflow(rootNode, server, util, getProject(), getWorkDirectory().toPath(), createIdeaArtifacts);
            serverPluginWorkflow.getAgentAttachedRuntimeArtifacts().addAll(agentPluginWorkflow.getAttachedArtifacts());
            serverPluginWorkflow.setAgentSpec(agent.getSpec());
            findPluginConfiguration().ifPresent(plugin -> serverPluginWorkflow.getPluginDependencies().addAll(plugin.getDependencies()));
            serverPluginWorkflow.execute();
            attachArtifacts(serverPluginWorkflow.getAttachedArtifacts());

            List<ResultArtifact> attachedArtifacts = new ArrayList<ResultArtifact>();
            attachedArtifacts.addAll(agentPluginWorkflow.getAttachedArtifacts());
            attachedArtifacts.addAll(serverPluginWorkflow.getAttachedArtifacts());
            IncrementalState currentState = incrementalSupport.collectCurrentState(rootNode);
            incrementalSupport.saveState(currentState.withOutputs(attachedArtifacts));

        } catch (IOException e) {
            getLog().warn(e);
            throw new MojoFailureException("Error while assembly execution", e);
        }
    }

    private void setDefaultconfigurationValues(String pluginVersion) {
        PluginExecution pluginExecution = findPluginExecution();
        if (pluginExecution != null) {
            Xpp3Dom configuration = (Xpp3Dom) pluginExecution.getConfiguration();
            for (Xpp3Dom node:configuration.getChildren()) {
                if ("agent".equalsIgnoreCase(node.getName())) {
                    agent.setDefaultValues(".", getProject(), getProjectBuildOutputDirectory(), pluginVersion);
                }
                if ("server".equalsIgnoreCase(node.getName())) {
                    server.setDefaultValues(".", getProject(), getProjectBuildOutputDirectory(), pluginVersion);
                }
            }
        }
    }

    private PluginExecution findPluginExecution() {
        Optional<Plugin> plugin = findPluginConfiguration();
        if (plugin.isPresent()) {
            List<PluginExecution> executions = plugin.get().getExecutions();
            if (executions.size() == 1) {
                return executions.get(0);
            }
            for (PluginExecution e : executions) {
                if (Objects.equals(execution.getExecutionId(), e.getId())) {
                    return e;
                }
            }
        }
        return null;
    }

    private Optional<Plugin> findPluginConfiguration() {
        if (pluginDescriptor.getPlugin() != null) {
            return Optional.of(pluginDescriptor.getPlugin());
        }
        Optional<Plugin> p = getProject().getBuild().getPlugins().stream().filter(it -> match(it, this.pluginDescriptor)).findFirst();
        return p;
    }

    private boolean match(Plugin it, PluginDescriptor pluginDescriptor) {
        Artifact pluginArtifact = pluginDescriptor.getPluginArtifact();
        return Objects.equals(pluginArtifact.getGroupId(), it.getGroupId()) && Objects.equals(pluginArtifact.getArtifactId(), it.getArtifactId());
    }

    public List<Artifact> getAttachedArtifact() {
        return getProject().getAttachedArtifacts();
    }

    public String getServerPluginName() {
        return server.getPluginName();
    }


    public String getCustomAgentPluginName() {
        return getProject().getArtifactId().equalsIgnoreCase(getAgentPluginName()) ? null : getAgentPluginName();
    }

    public String getAgentPluginName() {
        if (this.agent.getPluginName() == null) {
            return server.getPluginName();
        }
        return agent.getPluginName();
    }

    public void setFailOnMissingDependencies(boolean b) {
        agent.setFailOnMissingDependencies(b);
        server.setFailOnMissingDependencies(b);
    }
}
