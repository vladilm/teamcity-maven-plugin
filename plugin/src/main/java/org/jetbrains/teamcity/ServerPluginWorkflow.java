package org.jetbrains.teamcity;

import lombok.Data;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.maven.archiver.MavenArchiveConfiguration;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.dependency.graph.DependencyNode;
import org.codehaus.plexus.archiver.manager.NoSuchArchiverException;
import org.codehaus.plexus.archiver.util.DefaultFileSet;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.jetbrains.teamcity.agent.*;
import org.jetbrains.teamcity.data.ResolvedArtifact;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static org.jetbrains.teamcity.agent.AgentPluginWorkflow.TEAMCITY_AGENT_PLUGIN_CLASSIFIER;
import static org.jetbrains.teamcity.agent.AgentPluginWorkflow.TEAMCITY_TOOL_CLASSIFIER;
import static org.jetbrains.teamcity.agent.WorkflowUtil.TEAMCITY_PLUGIN_XML;

@Data
public class ServerPluginWorkflow implements ArtifactListProvider {
    public static final String TEAMCITY_PLUGIN_CLASSIFIER = "teamcity-plugin";
    public static final String TEAMCITY_PLUGIN_CLASSIFIER_PACKED = "teamcity-plugin-packed";
    public static final String AGENT_SUBDIR = "agent";
    public static final String BUNDLED_SUBDIR = "bundled";

    private final DependencyNode rootNode;
    private final Server parameters;
    private final WorkflowUtil util;

    private final MavenProject project;

    private final Path workDirectory;

    private final boolean createIdeaArtifacts;

    private final List<AssemblyContext> assemblyContexts = new ArrayList<>();

    private final List<ResultArtifact> attachedArtifacts = new ArrayList<>();
    private final List<ResultArchive> attachedArchives = new ArrayList<>();

    private final List<ResultArtifact> agentAttachedRuntimeArtifacts = new ArrayList<>();
    private final List<Dependency> pluginDependencies = new ArrayList<>();

    private Path pluginDescriptorPath;

    private final List<Path> ideaArtifactList = new ArrayList<>();

    private List<String> webappPaths = new ArrayList<>();

    private String agentSpec;

    public void execute() throws MojoExecutionException, IOException, MojoFailureException {
        if (parameters.isNeedToBuild()) {
            {
                Optional<Plugin> plugin = getProject().getBuildPlugins().stream().filter(it -> it.getArtifactId().equalsIgnoreCase("maven-war-plugin")).findFirst();
                if (plugin.isPresent()) {
                    Plugin actualPlugin = plugin.get();
                    List<String> customWebappPaths = util.lookupFor((Xpp3Dom) actualPlugin.getConfiguration(), "webResources", "resource", "directory");
                    if (customWebappPaths.isEmpty()) {
                        webappPaths = Jdk8Compat.of(project.getBasedir() + "/src/main/webapp");
                    } else {
                        webappPaths = customWebappPaths;
                    }
                }
            }

            Path pluginPath = util.getWorkDirectory().resolve("plugin");
            Path serverPluginRoot = util.createDir(pluginPath.resolve(parameters.getPluginName()));
            AssemblyContext assemblyContext = buildServerPlugin(serverPluginRoot, rootNode);

            assemblyContexts.add(assemblyContext.cloneWithRoot());
            AssemblyContext ideaAssemblyContext = util.createAssemblyContext("SERVER", "4IDEA", serverPluginRoot.getParent());
            ideaAssemblyContext.getPaths().add(new PathSet(serverPluginRoot).with(new ArtifactPathEntry(null, assemblyContext.getName())));
            assemblyContexts.add(ideaAssemblyContext.cloneWithRoot());


            Path dist = util.getWorkDirectory().resolve("dist");
            String zipName = parameters.getPluginName() + ".zip";
            Path plugin = util.zipFile(serverPluginRoot, dist, zipName);

            String zipPackedName = parameters.getPluginName() + "-packed.zip";
            Path pluginPacked = util.zipFile(pluginPath, dist, zipPackedName);

            AssemblyContext zipAssemblyContext = util.createAssemblyContext("SERVER", dist);
            zipAssemblyContext.getPaths().add(new PathSet(dist).with(new ArtifactPathEntry(zipName, assemblyContext.getName())));
            AssemblyContext zipPackedAssemblyContext = util.createAssemblyContext("SERVER-PACKED", dist);
            zipPackedAssemblyContext.getPaths().add(new PathSet(dist).with(new ArtifactPathEntry(zipPackedName, ideaAssemblyContext.getName())));

            assemblyContexts.add(zipAssemblyContext.cloneWithRoot(dist));
            assemblyContexts.add(zipPackedAssemblyContext.cloneWithRoot(dist));
            attachedArtifacts.add(new ResultArtifact("zip", TEAMCITY_PLUGIN_CLASSIFIER, plugin, zipAssemblyContext));
            attachedArtifacts.add(new ResultArtifact("zip", TEAMCITY_PLUGIN_CLASSIFIER_PACKED, pluginPacked, zipPackedAssemblyContext));
        }

        if (isApplicable()) {
            ideaArtifactList.addAll(new ArtifactBuilder(util.getLog(), util).build(getAssemblyContexts(), parameters.getIntellijProjectPath()));
        }
    }

    public boolean isApplicable() {
        return createIdeaArtifacts;
    }

    private AssemblyContext buildServerPlugin(Path serverPluginRoot, DependencyNode rootNode) throws MojoExecutionException, IOException {
        AssemblyContext assemblyContext = util.createAssemblyContext("SERVER", "EXPLODED", serverPluginRoot);
        prepareDescriptor(assemblyContext, serverPluginRoot);

        Path serverPath = util.createDir(serverPluginRoot.resolve("server"));
        List<String> exclusions = new ArrayList<>(parameters.getExclusions());
        if (agentSpec != null && parameters.isExcludeAgent()) {
            exclusions.add(agentSpec);
        }
        List<Artifact> nodes = util.getDependencyNodeList(rootNode, parameters.getSpec(), exclusions);
        Map<Boolean, List<Artifact>> dependencies = nodes.stream().collect(Collectors.partitioningBy(it -> "teamcity-agent-plugin".equalsIgnoreCase(it.getClassifier())));
        assemblyContext.getPaths().add(new PathSet(serverPath));
        Pair<List<ResolvedArtifact>, List<Path>> copyResults = util.copyTransitiveDependenciesInto(parameters.isFailOnMissingDependencies(), parameters.isRemoveVersionFromJar(), assemblyContext, dependencies.get(Boolean.FALSE), serverPath);
        List<Path> createdDestinations = new ArrayList<>(copyResults.getRight());
        if (!getBuildServerResources().isEmpty()) {
            String classifier = "teamcity-plugin-resources";
            Path resourcesJar = util.getJarFile(serverPath, util.getProject().getArtifactId(), classifier);
            assemblyContext.getPaths().add(new PathSet(resourcesJar.getParent()));
            CompressedPathEntry compressedBuildServerResources = new CompressedPathEntry(resourcesJar.toFile().getName(), "buildServerResources");
            assemblyContext.addToLastPathSet(compressedBuildServerResources);

            File resourcesFile = resourcesJar.toFile();
            List<org.codehaus.plexus.archiver.FileSet> fileSets = new ArrayList<>();
            for (String buildServerResource : getBuildServerResources()) {
                Path path = Jdk8Compat.ofPath(buildServerResource);
                if (!path.isAbsolute()) {
                    path = project.getBasedir().toPath().resolve(path);
                }
                org.codehaus.plexus.archiver.FileSet fs = new DefaultFileSet(path.toFile()).prefixed("buildServerResources/");
                compressedBuildServerResources.getPathsIncluded().add(path);
                fileSets.add(fs);
            }

            attachedArchives.add(new ResultArchive("jar", fileSets, resourcesFile));
            attachedArtifacts.add(new ResultArtifact("jar", classifier, resourcesJar, null));
            createdDestinations.add(resourcesJar);
        }

        List<Artifact> agentPluginDependencies = dependencies.get(Boolean.TRUE);
        List<Path> explicitAgentDestinations = assembleExplicitAgentDependencies(serverPluginRoot, assemblyContext, agentPluginDependencies);
        createdDestinations.addAll(explicitAgentDestinations);
        List<Path> explicitBundledDestinations = assembleBundledDependencies(serverPluginRoot, assemblyContext, agentPluginDependencies);
        createdDestinations.addAll(explicitBundledDestinations);
        List<Path> kotlinDestinations = assembleKotlinDsl(assemblyContext, serverPluginRoot);
        createdDestinations.addAll(kotlinDestinations);
        List<Path> uiSchemaDestinations = assembleUiSchemas(assemblyContext, serverPluginRoot);
        createdDestinations.addAll(uiSchemaDestinations);

        if (parameters.isNeedToBuildCommon()) {
            assemblyContext.getPaths().add(new PathSet(serverPluginRoot.resolve("common")));
            Path commonPath = util.createDir(serverPluginRoot.resolve("common"));
            List<Artifact> commonNodes = util.getDependencyNodeList(rootNode, parameters.getCommonSpec(), parameters.getCommonExclusions());
            Pair<List<ResolvedArtifact>, List<Path>> copyResults1 = util.copyTransitiveDependenciesInto(parameters.isFailOnMissingDependencies(), parameters.isRemoveVersionFromJar(), assemblyContext, commonNodes, commonPath);
            createdDestinations.addAll(copyResults1.getRight());
        }
        if (parameters.hasExtras()) {
            util.processExtras(parameters.getExtras(), parameters.isRemoveVersionFromJar(), serverPluginRoot, assemblyContext, createdDestinations);
        }

        Path agentPluginRoot = util.createDir(serverPluginRoot.resolve(AGENT_SUBDIR));
        for (ResultArtifact ra : agentAttachedRuntimeArtifacts) {
            assemblyContext.getPaths().add(new PathSet(agentPluginRoot).with(new ArtifactPathEntry(null, ra.getArtifactContext().getName())));
            Path destination = agentPluginRoot.resolve(ra.getFile().getFileName());
            Files.copy(ra.getFile(), destination, REPLACE_EXISTING);
            createdDestinations.add(destination);
        }

        try {
            util.createArchives(getAttachedArchives(), new MavenArchiveConfiguration());
        } catch (NoSuchArchiverException e) {
            util.getLog().warn("Archiver not found", e);
        }

        util.removeOtherFiles(parameters.getIgnoreExtraFilesIn(), serverPluginRoot, createdDestinations);
        return assemblyContext;
    }

    private List<String> getBuildServerResources() {
        if (!parameters.getBuildServerResources().isEmpty()) {
            return parameters.getBuildServerResources();
        } else {
            if (!webappPaths.isEmpty()) {
                return webappPaths.stream().map(it -> util.absOrProject(it).resolve("plugins").resolve(parameters.getPluginName())).filter(it -> it.toFile().exists()).map(Path::toString).collect(Collectors.toList());
            }
        }
        return Collections.emptyList();
    }

    private void prepareDescriptor(AssemblyContext assemblyContext, Path serverPluginRoot) throws MojoExecutionException {
        File targetDescriptorPath = parameters.getDescriptor().getPath();
        if (!targetDescriptorPath.exists() && !parameters.getDescriptor().isDoNotGenerate()) {
            Path generatedPath = util.getWorkDirectory().resolve("teamcity-plugin-generated.xml");
            try {
                util.createDescriptor("teamcity-server-plugin.vm", generatedPath, parameters);
                targetDescriptorPath = generatedPath.toFile();
            } catch (IOException e) {
                util.getLog().warn("Error while generating server descriptor: " + generatedPath, e);
            }
        }

        if (targetDescriptorPath.exists()) {
            try {
                pluginDescriptorPath = serverPluginRoot.resolve(TEAMCITY_PLUGIN_XML);
                Files.copy(targetDescriptorPath.toPath(), pluginDescriptorPath, REPLACE_EXISTING);
            } catch (IOException e) {
                throw new MojoExecutionException(String.format("Can't copy %s.", targetDescriptorPath), e);
            }
        } else if (parameters.getDescriptor().isFailOnMissing()) {
            throw new MojoExecutionException(String.format("`pluginDescriptorPath` must point to teamcity plugin descriptor (%s).", parameters.getDescriptor().getPath()));
        }
        assemblyContext.getPaths().add(new PathSet(serverPluginRoot).with(new FilePathEntry(TEAMCITY_PLUGIN_XML, targetDescriptorPath.toPath()))); // add it anyway if it exists or not
    }


    private List<Path> assembleExplicitAgentDependencies(Path serverPluginRoot, AssemblyContext assemblyContext, List<Artifact> agentPluginDependencies) throws MojoExecutionException {
        List<Path> explicitDestinations = new ArrayList<>();
        if (agentPluginDependencies != null && !agentPluginDependencies.isEmpty()) {
            Path agentPath = util.createDir(serverPluginRoot.resolve(AGENT_SUBDIR));
            Pair<List<ResolvedArtifact>, List<Path>> copyResults = util.copyTransitiveDependenciesInto(parameters.isFailOnMissingDependencies(), parameters.isRemoveVersionFromJar(), assemblyContext, agentPluginDependencies, agentPath);
            explicitDestinations.addAll(copyResults.getRight());
        }

        List<Dependency> agentDependencies = pluginDependencies.stream().filter(it -> TEAMCITY_AGENT_PLUGIN_CLASSIFIER.equalsIgnoreCase(it.getClassifier())).collect(Collectors.toList());
        if (!agentDependencies.isEmpty()) {
            Path agentPath = util.createDir(serverPluginRoot.resolve(AGENT_SUBDIR));
            Pair<List<ResolvedArtifact>, List<Path>> copyResults = util.copyDependenciesInto(assemblyContext, parameters.isFailOnMissingDependencies(), parameters.isRemoveVersionFromJar(), agentDependencies, agentPath);
            explicitDestinations.addAll(copyResults.getRight());
        }
        return explicitDestinations;
    }

    private List<Path> assembleBundledDependencies(Path serverPluginRoot, AssemblyContext assemblyContext, List<Artifact> agentPluginDependencies) throws MojoExecutionException {
        List<Path> explicitDestinations = new ArrayList<>();

        List<Dependency> bundledDependencies = pluginDependencies.stream().filter(it -> TEAMCITY_TOOL_CLASSIFIER.equalsIgnoreCase(it.getClassifier())).collect(Collectors.toList());
        if (!bundledDependencies.isEmpty()) {
            Path bundledPath = util.createDir(serverPluginRoot.resolve(BUNDLED_SUBDIR));
            Pair<List<ResolvedArtifact>, List<Path>> copyResults = util.copyDependenciesInto(assemblyContext, parameters.isFailOnMissingDependencies(), parameters.isRemoveVersionFromJar(), bundledDependencies, bundledPath);
            explicitDestinations.addAll(copyResults.getRight());
        }
        return explicitDestinations;
    }

    private List<Path> assembleKotlinDsl(AssemblyContext assemblyContext, Path serverPluginRoot) throws MojoExecutionException {
        List<Path> destinations = new ArrayList<>();
        if (parameters.getKotlinDslDescriptorsPath().exists()) {
            Path kotlinDslPath = util.createDir(serverPluginRoot.resolve("kotlin-dsl"));
            assemblyContext.getPaths().add(new PathSet(kotlinDslPath).with(new DirCopyPathEntry(parameters.getKotlinDslDescriptorsPath().toPath())));
            try {
                try (Stream<Path> stream = Files.walk(parameters.getKotlinDslDescriptorsPath().toPath())) {
                    stream.forEach(it -> copyTo(it, kotlinDslPath, destinations));
                }
            } catch (IOException e) {
                util.getLog().warn("Can't copy " + parameters.getKotlinDslDescriptorsPath() + " to " + kotlinDslPath);
            }
        } else if (parameters.isRequireKotlinDsl()) {
            String content = "`requireKotlinDsl` set to true but sources not found in " + parameters.getKotlinDslDescriptorsPath().getPath();
            util.getLog().error(content);
            throw new MojoExecutionException(content);
        }
        return destinations;
    }

    private void copyTo(Path it, Path targetDir, List<Path> destinations) {
        try {
            if (Files.isRegularFile(it)) {
                Path target = targetDir.resolve(it.getFileName());
                destinations.add(target);
                Files.copy(it, target, REPLACE_EXISTING);
            }
        } catch (IOException e) {
            util.getLog().warn("Can't copy " + it + " to " + targetDir, e);
        }
    }

    private List<Path> assembleUiSchemas(AssemblyContext assemblyContext, Path serverPluginRoot) {
        List<Path> destinations = new ArrayList<>();
        if (!parameters.getUiSchemasPath().isDirectory()) {
            return destinations;
        }
        Path uiSchemasPath = util.createDir(serverPluginRoot.resolve("ui-schemas"));
        assemblyContext.getPaths().add(new PathSet(uiSchemasPath).with(new DirCopyPathEntry(parameters.getUiSchemasPath().toPath())));
        try {
            try (Stream<Path> stream = Files.walk(parameters.getUiSchemasPath().toPath())) {
                stream.forEach(it -> copyTo(it, uiSchemasPath, destinations));
            }
        } catch (IOException e) {
            util.getLog().warn("Can't copy " + parameters.getUiSchemasPath() + " to " + uiSchemasPath);
        }
        return destinations;
    }
}
