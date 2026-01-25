package org.jetbrains.teamcity.agent;

import lombok.Data;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.maven.archiver.MavenArchiveConfiguration;
import org.apache.maven.archiver.MavenArchiver;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.ArtifactUtils;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.resolver.filter.ArtifactFilter;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.artifact.filter.StrictPatternExcludesArtifactFilter;
import org.apache.maven.shared.artifact.filter.StrictPatternIncludesArtifactFilter;
import org.apache.maven.shared.dependency.graph.DependencyNode;
import org.apache.maven.shared.dependency.graph.filter.AndDependencyNodeFilter;
import org.apache.maven.shared.dependency.graph.filter.ArtifactDependencyNodeFilter;
import org.apache.maven.shared.dependency.graph.filter.DependencyNodeFilter;
import org.apache.maven.shared.dependency.graph.internal.ConflictData;
import org.apache.maven.shared.dependency.graph.traversal.CollectingDependencyNodeVisitor;
import org.apache.maven.shared.dependency.graph.traversal.DependencyNodeVisitor;
import org.apache.maven.shared.dependency.graph.traversal.FilteringDependencyNodeVisitor;
import org.apache.maven.shared.dependency.graph.traversal.SerializingDependencyNodeVisitor;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.runtime.RuntimeConstants;
import org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader;
import org.codehaus.plexus.archiver.FileSet;
import org.codehaus.plexus.archiver.jar.JarArchiver;
import org.codehaus.plexus.archiver.manager.ArchiverManager;
import org.codehaus.plexus.archiver.manager.NoSuchArchiverException;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.jetbrains.teamcity.*;
import org.jetbrains.teamcity.data.ResolvedArtifact;
import org.jetbrains.teamcity.velocity.NullTool;

import java.io.*;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.*;
import java.nio.file.FileSystem;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static org.apache.maven.artifact.ArtifactUtils.key;
import static org.jetbrains.teamcity.ServerPluginWorkflow.TEAMCITY_PLUGIN_CLASSIFIER;
import static org.jetbrains.teamcity.agent.AgentPluginWorkflow.TEAMCITY_AGENT_PLUGIN_CLASSIFIER;

@Data
public class WorkflowUtil {
    public static final String TEAMCITY_PLUGIN_XML = "teamcity-plugin.xml";

    private final Log log;
    private final MavenProject project;
    private final Path workDirectory;

    private final ResolveUtil resolve;
    private final String tokens;
    private final ArtifactFactory artifactFactory;

    private final ArchiverManager archiverManager;
    private String outputTimestamp;
    private MavenSession session;

    public WorkflowUtil(Log log, MavenProject project, Path workDirectory, ResolveUtil resolve, String tokens, ArtifactFactory artifactFactory, ArchiverManager archiverManager, String outputTimestamp, MavenSession session) {
        this.log = log;
        this.project = project;
        this.workDirectory = workDirectory;
        this.resolve = resolve;
        this.tokens = tokens;
        this.artifactFactory = artifactFactory;
        this.archiverManager = archiverManager;
        this.outputTimestamp = outputTimestamp;
        this.session = session;
    }
//
//    private Stream<Artifact> getArtifactList(MavenProject it) {
//        try {
//            Set<Artifact> artifacts = it.getArtifacts();
//            return new ArrayList<Artifact>() {{
//                add(it.getArtifact());
//                addAll(artifacts);
//            }}.stream();
//        } catch (Exception e) {
//            log.error("Error while getting list of artifacts from " + it.getName(), e);
//            throw new RuntimeException(e);
//        }
//    }

    public boolean isNeedToBuild(String a) {
        return a != null && !Jdk8Compat.isBlank(a);
    }


    public Path createDir(Path path) {
        try {
            return Files.createDirectories(Files.createDirectories(path));
        } catch (IOException e) {
            getLog().warn("Error while creation " + path, e);
            return path;
        }
    }

    public Pair<List<ResolvedArtifact>,List<Path>>  copyTransitiveDependenciesInto(boolean failOnMissingDependencies, AssemblyContext assemblyContext, List<Artifact> nodes, Path toPath) throws MojoExecutionException {
        List<Path> destinations = new ArrayList<>();
        List<ResolvedArtifact> result = new ArrayList<>();
        for (Artifact node : nodes) {
            Artifact alternativeArtifact = findAlternativeArtifacts(node);
            if (alternativeArtifact == null)
                continue;
            org.eclipse.aether.artifact.Artifact source = resolve.resolve(alternativeArtifact);
            ResolvedArtifact ra = new ResolvedArtifact(source, isReactorProject(node));
            result.add(ra);
            String name = ra.getFileName();
            Path destination = toPath.resolve(name);
            destinations.add(destination);
            assemblyContext.addToLastPathSet(new DependencyPathEntry(node, ra.isReactorProject(), destination.getFileName().toString(), source.getFile().toPath()));
            try {
                internalCopy(failOnMissingDependencies, source.getFile(), destination, ra.isReactorProject());
            } catch (IOException e) {
                getLog().warn("Error while copying " + source + " to " + destination, e);
            }
        }
        return Pair.of(result, destinations);
    }

    public void removeOtherFiles(List<String> ignoreExtraFilesIn, Path toPath, List<Path> destinations) {
        try {
            List<Path> existingFiles = Files.walk(toPath)
                    .filter(it -> !it.equals(toPath))
                    .filter(it -> !destinations.contains(it))
                    .filter(it -> shouldRemove(ignoreExtraFilesIn, toPath, it))
                    .collect(Collectors.toList());
            if (!existingFiles.isEmpty()) {
                getLog().warn("Found extra files in " + toPath + " removing (" + existingFiles + ")");
                existingFiles.forEach(it -> it.toFile().delete());
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void createArchives(List<ResultArchive> attachedArchives, MavenArchiveConfiguration archive) throws MojoExecutionException, NoSuchArchiverException {
        for (ResultArchive a: attachedArchives) {
            createArchive(a, archive);
        }
    }

    private void createArchive(ResultArchive a, MavenArchiveConfiguration archive) throws MojoExecutionException, NoSuchArchiverException {
        MavenArchiver archiver = new MavenArchiver();
        archiver.setArchiver((JarArchiver) archiverManager.getArchiver(a.getType()));
        archiver.setOutputFile(a.getFile());
        archiver.configureReproducibleBuild(outputTimestamp);
        try {
            for (FileSet fs : a.getFileSets()) {
                archiver.getArchiver().addFileSet(fs);
            }
            archiver.createArchive(getSession(), getProject(), archive);
        } catch (Exception e) {
            throw new MojoExecutionException("Error assembling JAR " + a.getFile(), e);
        }

    }


    private boolean shouldRemove(List<String> extraPaths, Path toPath, Path it) {
        if (extraPaths != null) {
            Path relativePath = toPath.relativize(it);
            for (String extra : extraPaths) {
                Path p = Paths.get(extra);
                if ((p.isAbsolute() && it.equals(p)) || (!p.isAbsolute() && isSubpathOf(relativePath, p))) {
                    return false;
                }
            }
        }
        if (it.toFile().isDirectory() || (it.toFile().isFile() && "teamcity-plugin.xml".equalsIgnoreCase(it.toFile().getName())))
            return false;
        return true;
    }

    public boolean isSubpathOf(Path path, Path basedir) {
        return !basedir.relativize(path).startsWith(Jdk8Compat.ofPath(".."));
    }

    private Artifact findAlternativeArtifacts(Artifact a) {
        if ("war".equalsIgnoreCase(a.getType())) {
            if (project.getArtifact().equals(a)) {
                List<Artifact> jarArtifacts = project.getAttachedArtifacts().stream().filter(it -> it.getType().equalsIgnoreCase("jar")).collect(Collectors.toList());
                if (jarArtifacts.size() == 1)
                    return jarArtifacts.get(0);
                else {
                    getLog().warn("Not possible to resolve WAR " + key(a) + " to a classes artifact. The result is [" + jarArtifacts.stream().map(ArtifactUtils::key).collect(Collectors.joining(",")) + "]");
                    return null;// no need to attach war file inside the plugin.
                }
            }
        }
        return a;
    }


    public List<Artifact> getDependencyNodeList(DependencyNode rootNode, String spec, List<String> exclusions) {
        List<DependencyNode> nodes;
        // looking for the nodes specified by user
        if (Arrays.asList("*", ".").contains(spec)) {
            nodes = Collections.singletonList(rootNode);
        } else {
            List<String> patterns = Arrays.asList(spec.split(","));
            nodes = collectNodes(rootNode, new StrictPatternIncludesArtifactFilter(patterns));
        }
        // getting transitive dependencies excluding ones specified in exclusions filter. Not to include teamcity-core by mistake for example.
        StringWriter writer = new StringWriter();
        CollectingDependencyNodeVisitor transitiveCollectingVisitor = new CollectingDependencyNodeVisitor();
        MultipleDependencyNodeVisitor mdnv = new MultipleDependencyNodeVisitor(Arrays.asList(transitiveCollectingVisitor, getSerializingDependencyNodeVisitor(writer)));
        DependencyNodeFilter exclusionFilter = new ArtifactDependencyNodeFilter(new StrictPatternExcludesArtifactFilter(exclusions));
        AndDependencyNodeFilter andDependencyNodeFilter = new AndDependencyNodeFilter(exclusionFilter, it -> isParentClassifierIn(it, TEAMCITY_PLUGIN_CLASSIFIER, TEAMCITY_AGENT_PLUGIN_CLASSIFIER));
        SkipFilteringDependencyNodeVisitor visitor = new SkipFilteringDependencyNodeVisitor(mdnv, andDependencyNodeFilter);
        nodes.forEach(it -> it.accept(visitor));
        getLog().info("Dependencies according to spec " + spec + ":\n" + writer);
        List<DependencyNode> nodes1 = transitiveCollectingVisitor.getNodes();
        // now conflicted dependencies might be in list, need to find them and resolve to the right version
        List<DependencyNode> result = new ArrayList<>();
        for (DependencyNode node : nodes1) {
            ConflictData cd = getPrivateField(node);
            if (cd != null && cd.getWinnerVersion() != null) {
                List<DependencyNode> substitutions = findSubstitutions(rootNode, node, cd.getWinnerVersion());
                CollectingDependencyNodeVisitor collector = new CollectingDependencyNodeVisitor();
                SkipFilteringDependencyNodeVisitor visitor1 = new SkipFilteringDependencyNodeVisitor(collector, exclusionFilter);
                substitutions.forEach(it -> it.accept(visitor1));
                result.addAll(collector.getNodes());
            } else {
                result.add(node);
            }
        }
        return result.stream().map(DependencyNode::getArtifact).distinct().collect(Collectors.toList());
    }

    private boolean isParentClassifierIn(DependencyNode it, String s, String s1) {
        if (it.getParent() != null && (Objects.equals(s, it.getParent().getArtifact().getClassifier()) ||
                Objects.equals(s1, it.getParent().getArtifact().getClassifier())))
            return false;
        return true;
    }

    private List<DependencyNode> findSubstitutions(DependencyNode rootNode, DependencyNode node, String winnerVersion) {
        Artifact a = node.getArtifact();
        StringJoiner sj = new StringJoiner(":");
        sj.add(a.getGroupId());
        sj.add(a.getArtifactId());
        sj.add(a.getType());
        sj.add(winnerVersion);

        StrictPatternIncludesArtifactFilter filter = new StrictPatternIncludesArtifactFilter(Collections.singletonList(sj.toString()));
        AndDependencyNodeFilter nodeFilter = new AndDependencyNodeFilter(new ArtifactDependencyNodeFilter(filter), node1 -> node1 != node);
        CollectingDependencyNodeVisitor collector = new CollectingDependencyNodeVisitor();
        rootNode.accept(new FilteringDependencyNodeVisitor(collector, nodeFilter));
        return collector.getNodes();
    }


    public SerializingDependencyNodeVisitor getSerializingDependencyNodeVisitor(Writer writer) {
        return new SerializingDependencyNodeVisitor(writer, toGraphTokens(tokens));
    }


    private SerializingDependencyNodeVisitor.GraphTokens toGraphTokens(String theTokens) {
        SerializingDependencyNodeVisitor.GraphTokens graphTokens;

        if ("whitespace".equals(theTokens)) {
            getLog().debug("+ Using whitespace tree tokens");

            graphTokens = SerializingDependencyNodeVisitor.WHITESPACE_TOKENS;
        } else if ("extended".equals(theTokens)) {
            getLog().debug("+ Using extended tree tokens");

            graphTokens = SerializingDependencyNodeVisitor.EXTENDED_TOKENS;
        } else {
            graphTokens = SerializingDependencyNodeVisitor.STANDARD_TOKENS;
        }

        return graphTokens;
    }

    private List<DependencyNode> collectNodes(DependencyNode rootNode, ArtifactFilter artifactFilter) {
        CollectingDependencyNodeVisitor collectingVisitor = new CollectingDependencyNodeVisitor();
        DependencyNodeVisitor firstPassVisitor = new FilteringDependencyNodeVisitor(collectingVisitor,
                new ArtifactDependencyNodeFilter(artifactFilter));
        rootNode.accept(firstPassVisitor);
        return collectingVisitor.getNodes();
    }

    private void internalCopy(boolean failOnMissingDependencies, File source, Path destination, boolean isReactorProject) throws IOException {
        try {
            if (!destination.toFile().exists() || isReactorProject || destination.toFile().length() != source.length()) {
                Files.copy(source.toPath(), destination);
            }
        } catch (NoSuchFileException e) {
            if (failOnMissingDependencies)
                getLog().error("Can't find dependency to add to plugin " + source);
            else
                destination.toFile().createNewFile();
            getLog().warn("NoSuchFileException: " + e.getMessage());
        } catch (FileAlreadyExistsException e) {
            Files.copy(source.toPath(), destination, REPLACE_EXISTING);
        } catch (IOException e) {
            getLog().warn(e);
        }
    }


    private ConflictData getPrivateField(DependencyNode node) {
        try {
            Field f = node.getClass().getDeclaredField("data");
            f.setAccessible(true);
            Object o = f.get(node);
            return (ConflictData) o;
        } catch (NoSuchFieldException | IllegalAccessException e) {
            return null;
        }
    }

    private boolean isReactorProject(Artifact a) {
        // reactorProjectList contains also a libraries, in order to distinguish we can check location (should be under project.basedir) somewhere or
        // version should match multi-module project.

        //        session.getAllProjects()
        Optional<Artifact> a1 = session.getProjects().stream().map(MavenProject::getArtifact).filter(it -> compareArtifacts(it, a)).findFirst();
        return a1.isPresent();

//        if (reactorProjectList.contains(a)) {
//            Optional<Artifact> a1 = reactorProjectList.stream().filter(it -> it.equals(a)).findFirst();
//            if (a1.isPresent()) {

//                List<Artifact> strings = new ArrayList<>();
//                Path baseDir = project.getParent().getBasedir().toPath();
//                strings.add(project.getParent().getArtifact());
//                MavenXpp3Reader reader = new MavenXpp3Reader();
//                for (String module: project.getParent().getModules()) {
//                    try {
//                        FileInputStream fis = new FileInputStream(baseDir.resolve(module).resolve("pom.xml").toFile());
//                        Model m = reader.read(fis);
//                        strings.add(artifactFactory.createProjectArtifact(m.getGroupId(), m.getArtifactId(), m.getVersion()));
//                    } catch (IOException | XmlPullParserException e) {
//                        throw new RuntimeException(e);
//                    }
//                }
//                boolean hasCommonRootPath = a1.get().getFile().getAbsolutePath().startsWith(project.getParent().getBasedir().getAbsolutePath());
//                Artifact artifact = a1.get();
//                return artifact.getVersion().equalsIgnoreCase(project.getVersion()) && project.getGroupId().equalsIgnoreCase(artifact.getGroupId());
//            }
//        }
    }

    private boolean compareArtifacts(Artifact it, Artifact a) {
        return Objects.equals(it.getArtifactId(), a.getArtifactId())
                && Objects.equals(it.getGroupId(), a.getGroupId())
                && Objects.equals(it.getVersion(), a.getVersion());
    }

    public Path getJarFile(Path basedir, String resultFinalName, String classifier) {
        if (basedir == null) {
            throw new IllegalArgumentException("basedir is not allowed to be null");
        }
        if (resultFinalName == null) {
            throw new IllegalArgumentException("finalName is not allowed to be null");
        }

        String fileName;
        if (hasClassifier(classifier)) {
            fileName = resultFinalName + "-" + classifier + ".jar";
        } else {
            fileName = resultFinalName + ".jar";
        }

        return basedir.resolve(fileName);
    }

    protected boolean hasClassifier(String classifier) {
        return classifier != null && classifier.trim().length() > 0;
    }

    public Path createDescriptor(String templateName, Path destination, Object parameters) throws IOException {
        File descriptor = destination.toFile();
        try (FileWriter fw = new FileWriter(descriptor)) {
            VelocityContext context = new VelocityContext();
            VelocityEngine ve = new VelocityEngine();
            ve.setProperty(RuntimeConstants.RESOURCE_LOADERS, "classpath");
            ve.setProperty("resource.loader.classpath.class", ClasspathResourceLoader.class.getName());
            context.put("project", project);
            context.put("param", parameters);
            context.put("null", new NullTool());
            Template template = ve.getTemplate(templateName);
            template.merge(context, fw);
        }
        return descriptor.toPath();
    }

    public Path zipFile(Path source, Path baseDir, String zipName) throws MojoFailureException {
        try {
            Path zipPath = Files.createDirectories(baseDir).resolve(zipName);
            if (zipPath.toFile().exists()) {
                boolean deleted = zipPath.toFile().delete();
                if (!deleted) {
                    getLog().warn("Failed to delete " + zipPath);
                }
            }

            URI uri = new URI("jar", zipPath.toUri().toString(), null);
            Optional<Path> largeFiles = Files.walk(source).filter(it -> it.toFile().length() > 50 * 1024 * 1024).findFirst();
            Map<String, Object> env = largeFiles.isPresent() ? Jdk8Compat.ofMap("create", "true", "useTempFile", Boolean.TRUE) : Jdk8Compat.ofMap("create", "true");
            try (FileSystem zipfs = FileSystems.newFileSystem(uri, env)) {
                List<Path> filesInAgentZip = Files.walk(source).collect(Collectors.toList());
                for (Path entry : filesInAgentZip) {
                    Path relativePath = zipfs.getPath(source.relativize(entry).toString());
                    try {
                        if (!Jdk8Compat.isBlank(relativePath.toString()))
                            Files.copy(entry, relativePath);
                    } catch (IOException e) {
                        getLog().warn("Can't zip file " + entry + " to " + relativePath, e);
                    }
                }
            }
            return zipPath;
        } catch (IOException | URISyntaxException e) {
            getLog().warn(e);
            throw new MojoFailureException("Error while building " + zipName, e);
        }
    }

    public Pair<List<ResolvedArtifact>,List<Path>> copyDependenciesInto(AssemblyContext assemblyContext, boolean failOnMissingDependencies, List<Dependency> nodes, Path toPath) throws MojoExecutionException {
        assemblyContext.getPaths().add(new PathSet(toPath));
        List<Path> destinations = new ArrayList<>();
        List<ResolvedArtifact> result = new ArrayList<>();
        for (Dependency node : nodes) {
            Artifact a = artifactFactory.createArtifactWithClassifier(node.getGroupId(), node.getArtifactId(), node.getVersion(), node.getType(), node.getClassifier());
            org.eclipse.aether.artifact.Artifact source = resolve.resolve(a);
            ResolvedArtifact ra = new ResolvedArtifact(source, isReactorProject(a));
            String name = ra.getFileName();
            Path destination = toPath.resolve(name);
            destinations.add(destination);
            try {
                internalCopy(failOnMissingDependencies, source.getFile(), destination, ra.isReactorProject());
                assemblyContext.addToLastPathSet(new ArtifactPathEntry(name, getAssemblyName(a.getArtifactId(), "AGENT", "EXPLODED")));
            } catch (IOException e) {
                getLog().warn("Error while copying " + source + " to " + destination, e);
            }
        }
        return Pair.of(result, destinations);
    }

    public AssemblyContext createAssemblyContext(String prefix, String suffix, Path root) {
        AssemblyContext assemblyContext = new AssemblyContext();
        assemblyContext.setName(getAssemblyName(getProject().getArtifactId(), prefix, suffix));
        assemblyContext.setRoot(root);
        return assemblyContext;
    }

    public String getAssemblyName(String artifactId, String prefix, String suffix) {
        if (suffix != null && !Jdk8Compat.isBlank(suffix))
            suffix = "::" + suffix;
        else
            suffix = "";
        return "TC::" + prefix + "::" + artifactId + suffix;
    }

    public AssemblyContext createAssemblyContext(String prefix, Path root) {
        return createAssemblyContext(prefix, null, root);
    }

    public List<String> lookupFor(Xpp3Dom configuration, String... paths) {
        if (configuration == null)
            return Collections.emptyList();
        Stream<Xpp3Dom> tmpStream = Stream.of(configuration);
        for (String path: paths) {
            tmpStream = tmpStream.flatMap(it -> Arrays.stream(it.getChildren(path)));
        }
        List<String> results = tmpStream.map(Xpp3Dom::getValue).collect(Collectors.toList());
        return results;
    }

    public void processExtras(List<SourceDest> extras, Path destinationRoot, AssemblyContext assemblyContext, List<Path> destinations) {
        for (SourceDest extra : extras) {
            Path source = absOrProject(extra.getSource());
            if (source.toFile().exists()) {
                Path dest = destinationRoot;
                if (extra.hasCustomDest()) {
                    if (extra.hasDestDir()) {
                        dest = createDir(dest.resolve(extra.getDestDir()));
                    }
                }
                assemblyContext.getPaths().add(new PathSet(dest));
                if (source.toFile().isFile()) {
                    assemblyContext.addToLastPathSet(new FilePathEntry(extra.getDestName(), source));
                    Path fullPath = (extra.hasDestName()) ? dest.resolve(extra.getDestName()) : dest.resolve(source.getFileName());
                    destinations.add(fullPath);
                    try {
                        FileUtils.copyFile(source.toFile(), fullPath.toFile());
                    } catch (IOException e) {
                        getLog().warn(source + " can't copy to " + fullPath + " because of " + e.getMessage());
                    }
                } else if (source.toFile().isDirectory()) {
                    assemblyContext.addToLastPathSet(new DirCopyPathEntry(source));
                    destinations.addAll(findPathsInRelativeTo(source, dest));
                    try {
                        FileUtils.copyDirectory(source.toFile(), dest.toFile());
                    } catch (IOException e) {
                        getLog().warn(source + " can't copy to " + dest + " because of " + e.getMessage());
                    }
                } else {
                    getLog().warn(extra.getSource() + " is neither file or folder, skipping");
                }
            } else {
                getLog().warn(extra.getSource() + " not found, skipping");
            }
        }

    }

    public List<Path> findPathsInRelativeTo(Path source, Path dest) {
        try {
            return  Files.walk(source).map(it -> source.relativize(it)).map(it -> dest.resolve(it)).collect(Collectors.toList());
        } catch (IOException e) {
            getLog().warn("Can't process files in " + source + " relative to " + dest, e);
            return Collections.emptyList();
        }
    }

    public Path absOrProject(String path) {
        Path p = Jdk8Compat.ofPath(path);
        if (p.isAbsolute())
            return p;
        return Jdk8Compat.ofPath(project.getBasedir().getPath()).resolve(p).toAbsolutePath();
    }
}