package org.jetbrains.teamcity;

import lombok.Getter;
import lombok.Setter;
import org.apache.maven.archiver.MavenArchiveConfiguration;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.resolver.filter.ArtifactFilter;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.shared.artifact.filter.ScopeArtifactFilter;
import org.apache.maven.shared.dependency.graph.DependencyCollectorBuilder;
import org.apache.maven.shared.dependency.graph.DependencyCollectorBuilderException;
import org.apache.maven.shared.dependency.graph.DependencyNode;
import org.apache.maven.shared.dependency.graph.traversal.BuildingDependencyNodeVisitor;
import org.apache.maven.shared.dependency.graph.traversal.DependencyNodeVisitor;
import org.codehaus.plexus.archiver.manager.ArchiverManager;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;
import org.jetbrains.teamcity.agent.ResolveUtil;
import org.jetbrains.teamcity.agent.ResultArtifact;
import org.jetbrains.teamcity.agent.WorkflowUtil;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.util.List;

import static org.apache.maven.artifact.Artifact.SCOPE_RUNTIME;

@Getter
@Setter
public abstract class BaseTeamCityMojo extends AbstractMojo {
    @Parameter(defaultValue = "${repositorySystemSession}")
    private RepositorySystemSession repoSession;
    @Component
    private RepositorySystem repoSystem;
    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true, required = true)
    private List<RemoteRepository> repositories;

    @Parameter(defaultValue = "${project}", required = true, readonly = true)
    private MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    @Component
    private ArtifactFactory artifactFactory;

    @Parameter(defaultValue = "${project.build.outputDirectory}")
    private File projectBuildOutputDirectory;

    @Parameter(defaultValue = "${project.build.directory}/teamcity")
    private File workDirectory;

    @Parameter(property = "tokens", defaultValue = "standard")
    private String tokens;

    @Component(hint = "default")
    private DependencyCollectorBuilder dependencyCollectorBuilder;

    @Component
    private MavenProjectHelper mavenProjectHelper;

    @Parameter( defaultValue = "${project.build.outputTimestamp}" )
    private String outputTimestamp;

    @Component
    private ArchiverManager archiverManager;

    @Parameter
    private MavenArchiveConfiguration archive = new MavenArchiveConfiguration();


    public WorkflowUtil getWorkflowUtil() throws IOException {
        ResolveUtil resolve = new ResolveUtil(getLog(), repoSystem, repositories, repoSession);
        return new WorkflowUtil(getLog(), project, workDirectory.toPath(), resolve, tokens, artifactFactory, archiverManager, outputTimestamp, session);
    }


    protected void attachArtifacts(List<ResultArtifact> artifacts) {
        artifacts.forEach(it -> mavenProjectHelper.attachArtifact(getProject(), it.getType(), it.getClassifier(), it.getFile().toFile()));
    }

    protected DependencyNode findRootNode() throws MojoExecutionException {
        DependencyNode rootNode;
        try {
            ArtifactFilter artifactFilter = createResolvingArtifactFilter(SCOPE_RUNTIME);
            ProjectBuildingRequest buildingRequest = new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());
            buildingRequest.setProject(getProject());

            rootNode = dependencyCollectorBuilder.collectDependencyGraph(buildingRequest, artifactFilter);
        } catch (DependencyCollectorBuilderException exception) {
            throw new MojoExecutionException("Cannot build project dependency graph", exception);
        }

        return rootNode;
    }


    private ArtifactFilter createResolvingArtifactFilter(String scope) {
        ScopeArtifactFilter filter;

        // filter scope
        if (scope != null) {
            getLog().debug("+ Resolving dependency tree for scope '" + scope + "'");
            filter = new ScopeArtifactFilter(scope);
        } else {
            filter = null;
        }

        return filter;
    }
}
