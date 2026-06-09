package org.jetbrains.teamcity.scenario;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.repository.ArtifactRepositoryPolicy;
import org.apache.maven.artifact.repository.MavenArtifactRepository;
import org.apache.maven.artifact.repository.layout.DefaultRepositoryLayout;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Model;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.testing.MojoRule;
import org.apache.maven.project.MavenProject;
import org.apache.maven.repository.internal.MavenWorkspaceReader;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.internal.impl.SimpleLocalRepositoryManagerFactory;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.LocalRepositoryManager;
import org.eclipse.aether.repository.WorkspaceRepository;
import org.jetbrains.teamcity.AssemblePluginMojo;
import org.jetbrains.teamcity.ServerPluginWorkflow;
import org.jetbrains.teamcity.agent.ResultArtifact;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

public class TeamCityMojoScenario {
    private final MojoRule rule;
    private final List<String> reactorModules = new ArrayList<String>();
    private final List<ScenarioSetup> setups = new ArrayList<ScenarioSetup>();
    private final List<MojoCustomizer> customizers = new ArrayList<MojoCustomizer>();

    private String pomResource;
    private String goal = "build";
    private boolean incremental;
    private String expectedLayout;
    private LocalMavenRepositoryFixture repositoryFixture = new LocalMavenRepositoryFixture();

    private Path scenarioDirectory;
    private Path fixtureDirectory;
    private Path localRepositoryDirectory;

    private TeamCityMojoScenario(MojoRule rule) {
        this.rule = rule;
    }

    public static TeamCityMojoScenario given(MojoRule rule) {
        return new TeamCityMojoScenario(rule);
    }

    public TeamCityMojoScenario pom(String pomResource) {
        this.pomResource = pomResource;
        return this;
    }

    public TeamCityMojoScenario goal(String goal) {
        this.goal = goal;
        return this;
    }

    public TeamCityMojoScenario reactorModule(String module) {
        reactorModules.add(module);
        return this;
    }

    public TeamCityMojoScenario offlineRepository(LocalMavenRepositoryFixture repositoryFixture) {
        this.repositoryFixture = repositoryFixture;
        return this;
    }

    public TeamCityMojoScenario beforeExecute(ScenarioSetup setup) {
        setups.add(setup);
        return this;
    }

    public TeamCityMojoScenario configureMojo(MojoCustomizer customizer) {
        customizers.add(customizer);
        return this;
    }

    public TeamCityMojoScenario incremental() {
        this.incremental = true;
        return this;
    }

    public TeamCityMojoScenario expectLayout(String expectedLayout) {
        this.expectedLayout = expectedLayout;
        return this;
    }

    public BuildResult executeAndVerify() throws Exception {
        BuildResult result = execute();
        result.assertLayoutEquals(expectedLayout);
        return result;
    }

    public BuildResult execute() throws Exception {
        prepareScenarioDirectory();

        MavenProject project = rule.readMavenProject(fixtureDirectory.toFile());
        List<MavenProject> projects = new ArrayList<MavenProject>();
        projects.add(project);
        projects.addAll(readModules(project.getModules()));
        projects.addAll(readModules(reactorModules));

        MavenSession session = rule.newMavenSession(project);
        session.setProjects(projects);
        session.setAllProjects(projects);
        session.setCurrentProject(project);
        configureLocalRepository(session, projects);
        materializeProjectArtifacts(projects);

        ScenarioContext context = new ScenarioContext(project, session);
        int i;
        for (i = 0; i < setups.size(); i++) {
            setups.get(i).apply(context);
        }

        MojoExecution execution = rule.newMojoExecution(goal);
        AssemblePluginMojo mojo = (AssemblePluginMojo) rule.lookupConfiguredMojo(session, execution);
        if (incremental) {
            setIncremental(mojo);
        }
        for (i = 0; i < customizers.size(); i++) {
            customizers.get(i).apply(mojo);
        }

        mojo.execute();
        return new BuildResult(mojo);
    }

    private void prepareScenarioDirectory() throws Exception {
        if (scenarioDirectory != null) {
            return;
        }
        if (pomResource == null || !pomResource.endsWith("/pom.xml")) {
            throw new IllegalStateException("Use an explicit test pom resource, for example unit/project-to-test/pom.xml");
        }

        URL pomUrl = TeamCityMojoScenario.class.getClassLoader().getResource(pomResource);
        if (pomUrl == null) {
            throw new IllegalStateException("Test pom resource not found: " + pomResource);
        }

        Path sourcePom = Path.of(pomUrl.toURI());
        Path sourceDirectory = sourcePom.getParent();
        scenarioDirectory = Files.createTempDirectory("teamcity-mojo-scenario");
        fixtureDirectory = scenarioDirectory.resolve(sourceDirectory.getFileName().toString());
        copyDirectory(sourceDirectory, fixtureDirectory);
        localRepositoryDirectory = repositoryFixture.materialize(scenarioDirectory);
    }

    private void copyDirectory(Path source, Path destination) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (!dir.equals(source) && isRuntimeFixtureDirectory(dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(destination.resolve(source.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, destination.resolve(source.relativize(file).toString()), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private boolean isRuntimeFixtureDirectory(Path dir) {
        Path fileName = dir.getFileName();
        if (fileName == null) {
            return false;
        }
        return "target".equals(fileName.toString()) || "repo".equals(fileName.toString());
    }

    private List<MavenProject> readModules(List<String> modules) throws Exception {
        List<MavenProject> projects = new ArrayList<MavenProject>();
        if (modules == null) {
            return projects;
        }

        int i;
        for (i = 0; i < modules.size(); i++) {
            String module = modules.get(i);
            if (module == null || module.trim().isEmpty()) {
                continue;
            }
            projects.add(rule.readMavenProject(fixtureDirectory.resolve(module).toFile()));
        }
        return projects;
    }

    private void configureLocalRepository(MavenSession session, List<MavenProject> projects) throws Exception {
        ArtifactRepository localRepository = createLocalArtifactRepository(localRepositoryDirectory.toFile());
        session.getRequest().setOffline(true);
        session.getRequest().setLocalRepository(localRepository);
        session.getRequest().setLocalRepositoryPath(localRepositoryDirectory.toFile());
        session.getRequest().setRemoteRepositories(Collections.<ArtifactRepository>emptyList());
        session.getRequest().setPluginArtifactRepositories(Collections.<ArtifactRepository>emptyList());

        LocalRepositoryManager localRepositoryManager = rule.getContainer()
                .lookup(SimpleLocalRepositoryManagerFactory.class)
                .newInstance(session.getRepositorySession(), new LocalRepository(localRepositoryDirectory.toFile()));
        DefaultRepositorySystemSession repositorySession = (DefaultRepositorySystemSession) session.getRepositorySession();
        repositorySession.setLocalRepositoryManager(localRepositoryManager);
        repositorySession.setOffline(true);
        repositorySession.setWorkspaceReader(new ScenarioWorkspaceReader(session));

        int i;
        for (i = 0; i < projects.size(); i++) {
            MavenProject project = projects.get(i);
            project.setRemoteArtifactRepositories(Collections.singletonList(localRepository));
            project.setPluginArtifactRepositories(Collections.singletonList(localRepository));
        }
    }

    private ArtifactRepository createLocalArtifactRepository(File localRepo) throws IOException {
        return new MavenArtifactRepository(
                "scenario-local",
                localRepo.getCanonicalPath(),
                new DefaultRepositoryLayout(),
                new ArtifactRepositoryPolicy(true, ArtifactRepositoryPolicy.UPDATE_POLICY_NEVER, ArtifactRepositoryPolicy.CHECKSUM_POLICY_IGNORE),
                new ArtifactRepositoryPolicy(true, ArtifactRepositoryPolicy.UPDATE_POLICY_NEVER, ArtifactRepositoryPolicy.CHECKSUM_POLICY_IGNORE)
        );
    }

    private void materializeProjectArtifacts(List<MavenProject> projects) throws IOException {
        int i;
        for (i = 0; i < projects.size(); i++) {
            MavenProject project = projects.get(i);
            if (project.getArtifact() == null || "pom".equals(project.getPackaging())) {
                continue;
            }

            File file = project.getArtifact().getFile();
            if (file == null) {
                file = defaultArtifactPath(project).toFile();
                project.getArtifact().setFile(file);
            }
            if (!file.exists()) {
                writeJarLikeFile(file.toPath(), project.getGroupId() + ":" + project.getArtifactId() + ":" + project.getVersion());
            }
        }
    }

    private Path defaultArtifactPath(MavenProject project) {
        String extension = project.getArtifact().getType();
        if (extension == null || extension.trim().isEmpty()) {
            extension = project.getPackaging() == null ? "jar" : project.getPackaging();
        }
        String finalName = project.getBuild() == null ? null : project.getBuild().getFinalName();
        if (finalName == null || finalName.trim().isEmpty()) {
            finalName = project.getArtifactId() + "-" + project.getVersion();
        }
        return Path.of(project.getBuild().getDirectory()).resolve(finalName + "." + extension);
    }

    private void writeJarLikeFile(Path path, String marker) throws IOException {
        Files.createDirectories(path.getParent());
        OutputStream outputStream = Files.newOutputStream(path);
        try {
            JarOutputStream jar = new JarOutputStream(outputStream);
            try {
                JarEntry entry = new JarEntry("fixture.txt");
                jar.putNextEntry(entry);
                jar.write(marker.getBytes(StandardCharsets.UTF_8));
                jar.closeEntry();
            } finally {
                jar.close();
            }
        } finally {
            outputStream.close();
        }
    }

    private void setIncremental(AssemblePluginMojo mojo) throws Exception {
        java.lang.reflect.Field field = AssemblePluginMojo.class.getDeclaredField("incremental");
        field.setAccessible(true);
        field.setBoolean(mojo, true);
    }

    public interface ScenarioSetup {
        void apply(ScenarioContext context) throws Exception;
    }

    public interface MojoCustomizer {
        void apply(AssemblePluginMojo mojo) throws Exception;
    }

    public class ScenarioContext {
        private final MavenProject project;
        private final MavenSession session;

        private ScenarioContext(MavenProject project, MavenSession session) {
            this.project = project;
            this.session = session;
        }

        public Path projectDirectory() {
            return project.getBasedir().toPath();
        }

        public Path buildDirectory() {
            return Path.of(project.getBuild().getDirectory());
        }

        public Path outputDirectory() {
            return Path.of(project.getBuild().getOutputDirectory());
        }

        public MavenProject project() {
            return project;
        }

        public MavenSession session() {
            return session;
        }

        public Path writeProjectFile(String relativePath, String content) throws IOException {
            Path path = projectDirectory().resolve(relativePath);
            Files.createDirectories(path.getParent());
            Files.write(path, content.getBytes(StandardCharsets.UTF_8));
            return path;
        }
    }

    public static class BuildResult {
        private final AssemblePluginMojo mojo;

        private BuildResult(AssemblePluginMojo mojo) {
            this.mojo = mojo;
        }

        public AssemblePluginMojo mojo() {
            return mojo;
        }

        public void assertLayoutEquals(String expectedLayout) throws IOException {
            assertThat(layout()).isEqualToIgnoringNewLines(expectedLayout);
        }

        public void assertSkippedAndArtifactsUnchanged(BuildResult previous) throws IOException {
            assertThat(mojo.getAgentPluginWorkflow()).isNull();
            assertThat(mojo.getServerPluginWorkflow()).isNull();
            assertThat(mojo.getAttachedArtifact()).hasSize(previous.outputTimestamps().size());

            Map<String, Long> expectedTimestamps = previous.outputTimestamps();
            int i;
            for (i = 0; i < mojo.getAttachedArtifact().size(); i++) {
                Artifact artifact = mojo.getAttachedArtifact().get(i);
                Long expectedTimestamp = expectedTimestamps.get(artifact.getClassifier());
                assertThat(expectedTimestamp).isNotNull();
                assertThat(Files.getLastModifiedTime(artifact.getFile().toPath()).toMillis()).isEqualTo(expectedTimestamp.longValue());
            }
        }

        public Map<String, Long> outputTimestamps() throws IOException {
            Map<String, Long> timestamps = new LinkedHashMap<String, Long>();
            collectTimestamps(timestamps, mojo.getAgentPluginWorkflow() == null ? null : mojo.getAgentPluginWorkflow().getAttachedArtifacts());
            collectTimestamps(timestamps, mojo.getServerPluginWorkflow() == null ? null : mojo.getServerPluginWorkflow().getAttachedArtifacts());
            return timestamps;
        }

        public String layout() throws IOException {
            StringJoiner layout = new StringJoiner("\n");
            appendAgentLayout(layout);
            appendServerLayout(layout);
            return layout.toString();
        }

        private void appendAgentLayout(StringJoiner layout) throws IOException {
            if (mojo.getAgentPluginWorkflow() == null || mojo.getAgentPluginWorkflow().getAgentPath() == null) {
                return;
            }
            Path agentPath = mojo.getAgentPluginWorkflow().getAgentPath();
            layout.add("AGENT:");
            try (Stream<Path> stream = Files.walk(agentPath)) {
                stream.skip(1)
                        .map(agentPath::relativize)
                        .map(Path::toString)
                        .sorted()
                        .forEach(layout::add);
            }
        }

        private void appendServerLayout(StringJoiner layout) throws IOException {
            if (mojo.getServerPluginWorkflow() == null) {
                return;
            }
            Optional<ResultArtifact> plugin = mojo.getServerPluginWorkflow().getAttachedArtifacts().stream()
                    .filter(it -> ServerPluginWorkflow.TEAMCITY_PLUGIN_CLASSIFIER.equalsIgnoreCase(it.getClassifier()))
                    .findFirst();
            if (!plugin.isPresent()) {
                return;
            }

            layout.add("SERVER:");
            try (ZipFile zipFile = new ZipFile(plugin.get().getFile().toFile())) {
                zipFile.stream()
                        .map(ZipEntry::getName)
                        .sorted()
                        .forEach(layout::add);
            }
        }

        private void collectTimestamps(Map<String, Long> timestamps, List<ResultArtifact> artifacts) throws IOException {
            if (artifacts == null) {
                return;
            }
            int i;
            for (i = 0; i < artifacts.size(); i++) {
                ResultArtifact artifact = artifacts.get(i);
                timestamps.put(artifact.getClassifier(), Files.getLastModifiedTime(artifact.getFile()).toMillis());
            }
        }
    }

    private static class ScenarioWorkspaceReader implements MavenWorkspaceReader {
        private final MavenSession session;

        private ScenarioWorkspaceReader(MavenSession session) {
            this.session = session;
        }

        @Override
        public Model findModel(org.eclipse.aether.artifact.Artifact artifact) {
            Optional<MavenProject> project = findProject(artifact);
            return project.map(MavenProject::getModel).orElse(null);
        }

        @Override
        public WorkspaceRepository getRepository() {
            return new WorkspaceRepository();
        }

        @Override
        public File findArtifact(org.eclipse.aether.artifact.Artifact artifact) {
            Optional<MavenProject> project = findProject(artifact);
            if (!project.isPresent()) {
                return null;
            }

            MavenProject mavenProject = project.get();
            if (mavenProject.getArtifact() != null && mavenProject.getArtifact().getFile() != null) {
                return mavenProject.getArtifact().getFile();
            }
            return null;
        }

        @Override
        public List<String> findVersions(org.eclipse.aether.artifact.Artifact artifact) {
            return session.getProjects().stream()
                    .filter(project -> equalArtifacts(project, artifact))
                    .map(MavenProject::getVersion)
                    .collect(Collectors.toList());
        }

        private Optional<MavenProject> findProject(org.eclipse.aether.artifact.Artifact artifact) {
            return session.getProjects().stream()
                    .filter(project -> equalArtifacts(project, artifact))
                    .findFirst();
        }

        private boolean equalArtifacts(MavenProject project, org.eclipse.aether.artifact.Artifact artifact) {
            return project != null
                    && Objects.equals(project.getGroupId(), artifact.getGroupId())
                    && Objects.equals(project.getArtifactId(), artifact.getArtifactId())
                    && Objects.equals(project.getVersion(), artifact.getVersion());
        }
    }
}
