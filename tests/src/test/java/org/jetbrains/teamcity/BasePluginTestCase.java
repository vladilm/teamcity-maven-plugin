package org.jetbrains.teamcity;

import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.repository.ArtifactRepositoryPolicy;
import org.apache.maven.artifact.repository.MavenArtifactRepository;
import org.apache.maven.artifact.repository.layout.DefaultRepositoryLayout;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Model;
import org.apache.maven.plugin.testing.MojoRule;
import org.apache.maven.project.MavenProject;
import org.apache.maven.repository.internal.MavenWorkspaceReader;
import org.assertj.core.api.SoftAssertions;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.internal.impl.SimpleLocalRepositoryManagerFactory;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.LocalRepositoryManager;
import org.eclipse.aether.repository.WorkspaceRepository;
import org.jetbrains.teamcity.agent.AgentPluginWorkflow;
import org.jetbrains.teamcity.agent.ResultArtifact;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jetbrains.teamcity.ServerPluginWorkflow.TEAMCITY_PLUGIN_CLASSIFIER;

public abstract class BasePluginTestCase {
    @Rule
    public MojoRule rule = new MojoRule();
    protected final SoftAssertions sa = new SoftAssertions();
    protected MavenProject project;
    protected MavenSession session;

    @After
    public void after() {
        sa.assertAll();
    }

    @Before
    public void before() {
    }

    protected MavenSession initMavenSession(String projectBase, String ...additionalModules) {
        try {
            MavenProject project = rule.readMavenProject(getTestDir(projectBase));
            List<MavenProject> projects = getMavenProjectList(projectBase, project.getModules());
            List<MavenProject> projects1 = getMavenProjectList(projectBase, Jdk8Compat.of(additionalModules));
            projects.addAll(projects1);
            projects.add(0, project);
            MavenSession session = rule.newMavenSession(project);
            session.setProjects(projects);
            File repoFile = getBuildPreparedLocalRepository();
            ArtifactRepository localRepo = createLocalArtifactRepository(repoFile);
            LocalRepository localRepository = createLocalRepository(repoFile);
            session.getRequest().setOffline(true);
            session.getRequest().setLocalRepository(localRepo);
            session.getRequest().setLocalRepositoryPath(repoFile);
            session.getRequest().setRemoteRepositories(Collections.emptyList());
            session.getRequest().setPluginArtifactRepositories(Collections.emptyList());
            LocalRepositoryManager lrm = rule.getContainer().lookup(SimpleLocalRepositoryManagerFactory.class)
                    .newInstance(session.getRepositorySession(), localRepository);
            DefaultRepositorySystemSession repositorySession = (DefaultRepositorySystemSession) session.getRepositorySession();
            repositorySession.setOffline(true);
            repositorySession.setWorkspaceReader(new MavenWorkspaceReader() {
                @Override
                public Model findModel(Artifact artifact) {
                    Optional<MavenProject> projectOptional = session.getProjects().stream().filter(it -> equalArtifacts(it, artifact)).findFirst();
                    return projectOptional.map(MavenProject::getModel).orElse(null);
                }

                @Override
                public WorkspaceRepository getRepository() {
                    return new WorkspaceRepository();
                }

                @Override
                public File findArtifact(Artifact artifact) {
                    Optional<MavenProject> projectOptional = session.getProjects().stream().filter(it -> equalArtifacts(it, artifact)).findFirst();
                    if (projectOptional.isPresent()) {
                        MavenProject p = projectOptional.get();
                        if ("teamcity-agent-plugin".equalsIgnoreCase(artifact.getClassifier())) {
                            return Path.of(p.getBuild().getDirectory(), p.getArtifactId() + "-" + p.getVersion() + "-" + artifact.getClassifier() + "." + artifact.getExtension()).toFile();
                        } else {
                            return Path.of(p.getBuild().getDirectory(), p.getArtifactId() + "-" + p.getVersion() + "." + artifact.getExtension()).toFile();
                        }
                    } else {
                        return null;
                    }
                }

                @Override
                public List<String> findVersions(Artifact artifact) {
                    List<String> versions = session.getProjects().stream().filter(it -> equalArtifacts(it, artifact))
                            .map(MavenProject::getArtifact)
                            .map(org.apache.maven.artifact.Artifact::getVersion)
                            .collect(Collectors.toList());

                    return versions;
                }
            });
            repositorySession.setLocalRepositoryManager(lrm);
            for (MavenProject p : projects) {
                p.setRemoteArtifactRepositories(Collections.singletonList(localRepo));
                p.setPluginArtifactRepositories(Collections.singletonList(localRepo));
            }
            return session;
        }  catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private File getBuildPreparedLocalRepository() throws URISyntaxException {
        String configured = System.getProperty("teamcity.maven.plugin.test.localRepo");
        if (configured != null && !configured.trim().isEmpty()) {
            return new File(configured);
        }

        Path testClasses = Path.of(BasePluginTestCase.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        return testClasses.getParent().resolve("test-local-repo").toFile();
    }

    private List<MavenProject> getMavenProjectList(String projectBase, List<String> modules) {
        List<MavenProject> projects = modules.stream().map(it -> {
            try {
                return rule.readMavenProject(new File(getTestDir(projectBase), it));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).collect(Collectors.toList());
        return projects;
    }

    private LocalRepository createLocalRepository(File repo) {
        return new LocalRepository(repo);
    }

    public static File getTestDir(String pathToBase) {
        return new File(AssemblePluginMojo.class.getClassLoader().getResource(pathToBase).getFile());
    }

    private ArtifactRepository createLocalArtifactRepository(File localRepo) throws IOException {
        return new MavenArtifactRepository("local",
                localRepo.getCanonicalPath(),
                new DefaultRepositoryLayout(),
                new ArtifactRepositoryPolicy( true, ArtifactRepositoryPolicy.UPDATE_POLICY_ALWAYS, ArtifactRepositoryPolicy.CHECKSUM_POLICY_IGNORE ),
                new ArtifactRepositoryPolicy( true, ArtifactRepositoryPolicy.UPDATE_POLICY_ALWAYS, ArtifactRepositoryPolicy.CHECKSUM_POLICY_IGNORE )

        );
    }

    private static <T> boolean eq(T s1, T s2) {
        return Objects.equals(s1, s2);
    }

    private boolean equalArtifacts(MavenProject it, Artifact artifact) {
        return eq(it.getArtifact().getGroupId(), artifact.getGroupId()) && eq(it.getArtifact().getArtifactId(), artifact.getArtifactId());
    }

    protected void filesAreEqual(Path path, String expected) throws IOException {
        String actual = Files.readString(path);
        Assert.assertEquals(expected, actual);
    }


    protected void appendTestResult(StringJoiner sb, AgentPluginWorkflow apw) throws IOException {
        if (apw.getAgentPath() != null) {
            sb.add("AGENT:");
            try(Stream<Path> stream = Files.walk(apw.getAgentPath())) {
                stream.skip(1).sorted().forEachOrdered(it -> sb.add(apw.getAgentPath().relativize(it).toString()));
            }
        }
    }

    protected void appendTestResult(StringJoiner sb, ServerPluginWorkflow spw) throws IOException {
        sb.add("SERVER:");
        Optional<ResultArtifact> a = spw.getAttachedArtifacts().stream().filter(it -> it.getClassifier().equalsIgnoreCase(TEAMCITY_PLUGIN_CLASSIFIER)).findFirst();
        if (a.isPresent()) {
            try (ZipFile zipFile = new ZipFile(a.get().getFile().toFile())) {
                zipFile.stream()
                        .map(ZipEntry::getName)
                        .sorted()
                        .forEach(sb::add);
            }
        }
    }

    protected String getTestResult(AssemblePluginMojo mojo) throws IOException {
        StringJoiner sb = new StringJoiner("\n");
        appendTestResult(sb, mojo.getAgentPluginWorkflow());
        appendTestResult(sb, mojo.getServerPluginWorkflow());
        return sb.toString();
    }
}
