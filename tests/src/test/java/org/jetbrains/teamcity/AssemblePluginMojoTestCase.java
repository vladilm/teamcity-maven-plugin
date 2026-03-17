package org.jetbrains.teamcity;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecution;
import org.junit.Assert;
import org.junit.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

public class AssemblePluginMojoTestCase extends BasePluginTestCase {

    @Test
    public void testMakeAgentArtifact() throws Exception {
        MavenSession session = initMavenSession("agent/simple");
        MojoExecution execution = rule.newMojoExecution("build-agent");
        AgentPluginMojo mojo = (AgentPluginMojo) rule.lookupConfiguredMojo(session, execution);
        mojo.execute();
        StringJoiner sb = new StringJoiner("\n");
        appendTestResult(sb, mojo.getAgentPluginWorkflow());
        Assert.assertEquals("AGENT:\n" +
                "lib\n" +
                "lib/commons-beanutils-core-1.8.3.jar\n" +
                "lib/commons-logging-1.1.1.jar\n" +
                "teamcity-plugin.xml", sb.toString());
        filesAreEqual(mojo.getAgentPluginWorkflow().getPluginDescriptorPath(), """
                <?xml version="1.0" encoding="UTF-8"?>
                <teamcity-agent-plugin xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                                       xsi:noNamespaceSchemaLocation="urn:schemas-jetbrains-com:teamcity-agent-plugin-v1-xml">
                    <!-- @@AGENT_PLUGIN_NAME=simple2@@ -->
                        
                        <plugin-deployment
                        />
                        <dependencies>
                                <plugin name="java-dowser"/>
                                <tool name="ant"/>
                        </dependencies>
                </teamcity-agent-plugin>""");
        // language=XML
        this.assertIdeaArtifacts(mojo.getAgentPluginWorkflow(), """
                <component name="ArtifactManager">
                    <artifact name="TC::AGENT::simple::4IDEA">
                        <output-path>$PROJECT_DIR$/target/teamcity/agent-unpacked</output-path>
                        <root id="root">
                            <element id="directory" name="simple2">
                                <element artifact-name="TC::AGENT::simple::EXPLODED" id="artifact"/>
                            </element>
                        </root>
                    </artifact>
                </component>                
                """, """
                <component name="ArtifactManager">
                    <artifact name="TC::AGENT::simple">
                        <output-path>$PROJECT_DIR$/target/teamcity/agent</output-path>
                        <root id="root">
                            <element id="archive" name="simple2.zip">
                                <element artifact-name="TC::AGENT::simple::EXPLODED" id="artifact"/>
                            </element>
                        </root>
                    </artifact>
                </component>                
                """, """
                <component name="ArtifactManager">
                    <artifact name="TC::AGENT::simple::EXPLODED">
                        <output-path>$PROJECT_DIR$/target/teamcity/agent-unpacked/simple2</output-path>
                        <root id="root">
                            <element id="directory" name="lib">
                                <element id="library" level="project" name="Maven: commons-beanutils:commons-beanutils-core:1.8.3"/>
                                <element id="library" level="project" name="Maven: commons-logging:commons-logging:1.1.1"/>
                            </element>
                            <element id="file-copy" output-file-name="teamcity-plugin.xml" path="$PROJECT_DIR$/target/teamcity/teamcity-agent-plugin-generated.xml"/>
                        </root>
                    </artifact>
                </component>
                """);
    }

    @Test
    public void testMakeAgentArtifactWithoutVersionInJarNames() throws Exception {
        MavenSession session = initMavenSession("unit/module-war/module-agent");
        MojoExecution execution = rule.newMojoExecution("build");
        AssemblePluginMojo mojo = (AssemblePluginMojo) rule.lookupConfiguredMojo(session, execution);
        mojo.setFailOnMissingDependencies(false);
        mojo.getAgent().setRemoveVersionFromJar(true);
        mojo.execute();

        StringJoiner sb = new StringJoiner("\n");
        appendTestResult(sb, mojo.getAgentPluginWorkflow());
        Assert.assertEquals("AGENT:\n" +
                "lib\n" +
                "lib/module-agent.jar\n" +
                "teamcity-plugin.xml", sb.toString());
    }

    @Test
    public void testMakeSimpleArtifact() throws Exception {
        MavenSession session = initMavenSession("unit/project-to-test");
        MojoExecution execution = rule.newMojoExecution("build");
        AssemblePluginMojo mojo = (AssemblePluginMojo) rule.lookupConfiguredMojo(session, execution);
        mojo.setFailOnMissingDependencies(false);
        mojo.getServer().setIgnoreExtraFilesIn(List.of("bundles"));
        mojo.execute();
        String sb = getTestResult(mojo);
        assertThat(sb).asString().isEqualToIgnoringNewLines("AGENT:\n" +
                "lib\n" +
                "lib/commons-beanutils-core-1.8.3.jar\n" +
                "lib/commons-logging-1.1.1.jar\n" +
                "teamcity-plugin.xml\n" +
                "SERVER:\n" +
                "agent/\n" +
                "agent/project-to-test.zip\n" +
                "bundles/\n" +
                "bundles/1\n" +
                "kotlin-dsl/\n" +
                "kotlin-dsl/test\n" +
                "server/\n" +
                "server/commons-codec-1.15.jar\n" +
                "server/project-to-test-1.1-SNAPSHOT.jar\n" +
                "teamcity-plugin.xml" +
                "ui-schemas/\n" +
                "ui-schemas/test\n");
        // language=XML
        filesAreEqual(mojo.getAgentPluginWorkflow().getPluginDescriptorPath(), """
                <?xml version="1.0" encoding="UTF-8"?>
                <teamcity-agent-plugin xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                                       xsi:noNamespaceSchemaLocation="urn:schemas-jetbrains-com:teamcity-agent-plugin-v1-xml">
                        
                        <plugin-deployment
                        />
                        <dependencies>
                                <plugin name="java-dowser"/>
                                <tool name="ant"/>
                        </dependencies>
                </teamcity-agent-plugin>""");

        filesAreEqual(mojo.getServerPluginWorkflow().getPluginDescriptorPath(),
                // language=XML
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <teamcity-plugin xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                                 xsi:noNamespaceSchemaLocation="urn:schemas-jetbrains-com:teamcity-plugin-v1-xml">
                    <info>
                        <name>project-to-test</name>
                        <display-name>Test</display-name>
                        <version>1.1-SNAPSHOT</version>
                    </info>
                    <deployment
                    />
                </teamcity-plugin>
                """);


        assertIdeaArtifacts(mojo.getAgentPluginWorkflow(),
                // language=XML
                """
                <component name="ArtifactManager">
                    <artifact name="TC::AGENT::project-to-test::4IDEA">
                        <output-path>$PROJECT_DIR$/target/teamcity/agent-unpacked</output-path>
                        <root id="root">
                            <element id="directory" name="project-to-test">
                                <element artifact-name="TC::AGENT::project-to-test::EXPLODED" id="artifact"/>
                            </element>
                        </root>
                    </artifact>
                </component>
                """,
                // language=XML
                """
                <component name="ArtifactManager">
                    <artifact name="TC::AGENT::project-to-test">
                        <output-path>$PROJECT_DIR$/target/teamcity/agent</output-path>
                        <root id="root">
                            <element id="archive" name="project-to-test.zip">
                                <element artifact-name="TC::AGENT::project-to-test::EXPLODED" id="artifact"/>
                            </element>
                        </root>
                    </artifact>
                </component>
                """,
                // language=XML
                """
                <component name="ArtifactManager">
                    <artifact name="TC::AGENT::project-to-test::EXPLODED">
                        <output-path>$PROJECT_DIR$/target/teamcity/agent-unpacked/project-to-test</output-path>
                        <root id="root">
                            <element id="directory" name="lib">
                                <element id="library" level="project" name="Maven: commons-beanutils:commons-beanutils-core:1.8.3"/>
                                <element id="library" level="project" name="Maven: commons-logging:commons-logging:1.1.1"/>
                            </element>
                            <element id="file-copy" output-file-name="teamcity-plugin.xml" path="$PROJECT_DIR$/target/teamcity/teamcity-agent-plugin-generated.xml"/>
                        </root>
                    </artifact>
                </component>
                """);
        assertIdeaArtifacts(mojo.getServerPluginWorkflow(),
                // language=XML
                """
                <component name="ArtifactManager">
                    <artifact name="TC::SERVER::project-to-test::EXPLODED">
                        <output-path>$PROJECT_DIR$/target/teamcity/plugin/project-to-test</output-path>
                        <root id="root">
                            <element id="file-copy" output-file-name="teamcity-plugin.xml" path="$PROJECT_DIR$/target/teamcity/teamcity-plugin-generated.xml"/>
                            <element id="directory" name="server">
                                <element id="archive" name="project-to-test-1.1-SNAPSHOT.jar">
                                    <element id="module-output" name="project-to-test"/>
                                </element>
                                <element id="library" level="project" name="Maven: commons-codec:commons-codec:1.15"/>
                            </element>
                            <element id="directory" name="kotlin-dsl">
                                <element id="dir-copy" path="$PROJECT_DIR$/target/classes/kotlin-dsl"/>
                            </element>
                            <element id="directory" name="ui-schemas">
                                <element id="dir-copy" path="$PROJECT_DIR$/target/classes/ui-schemas"/>
                            </element>
                            <element id="directory" name="agent">
                                <element artifact-name="TC::AGENT::project-to-test" id="artifact"/>
                            </element>
                        </root>
                    </artifact>
                </component>
                """,
                // language=XML
                """
                <component name="ArtifactManager">
                    <artifact name="TC::SERVER::project-to-test::4IDEA">
                        <output-path>$PROJECT_DIR$/target/teamcity/plugin</output-path>
                        <root id="root">
                            <element id="directory" name="project-to-test">
                                <element artifact-name="TC::SERVER::project-to-test::EXPLODED" id="artifact"/>
                            </element>
                        </root>
                    </artifact>
                </component>
                """,
                // language=XML
                """
                <component name="ArtifactManager">
                    <artifact name="TC::SERVER::project-to-test">
                        <output-path>$PROJECT_DIR$/target/teamcity/dist</output-path>
                        <root id="root">
                            <element id="archive" name="project-to-test.zip">
                                <element artifact-name="TC::SERVER::project-to-test::EXPLODED" id="artifact"/>
                            </element>
                        </root>
                    </artifact>
                </component>
                """,
                // language=XML
                """
                <component name="ArtifactManager">
                    <artifact name="TC::SERVER-PACKED::project-to-test">
                        <output-path>$PROJECT_DIR$/target/teamcity/dist</output-path>
                        <root id="root">
                            <element id="archive" name="project-to-test-packed.zip">
                                <element artifact-name="TC::SERVER::project-to-test::4IDEA" id="artifact"/>
                            </element>
                        </root>
                    </artifact>
                </component>
                """);
    }

    @Test
    public void testMakeSimpleServerArtifactWithoutVersionInJarNames() throws Exception {
        MavenSession session = initMavenSession("unit/server-jar-simple");
        MojoExecution execution = rule.newMojoExecution("build");
        AssemblePluginMojo mojo = (AssemblePluginMojo) rule.lookupConfiguredMojo(session, execution);
        mojo.setFailOnMissingDependencies(false);
        mojo.getServer().setRemoveVersionFromJar(true);
        mojo.execute();

        String sb = getTestResult(mojo);
        assertThat(sb).asString().isEqualToIgnoringNewLines("SERVER:\n" +
                "agent/\n" +
                "server/\n" +
                "server/server-jar-simple.jar\n" +
                "teamcity-plugin.xml");
    }

    @Test
    public void testDependencyToPlugin() throws Exception {
        MavenSession session = initMavenSession("unit/dependency-to-plugin", "moduleA");
        MojoExecution execution = rule.newMojoExecution("build");
        AssemblePluginMojo mojo = (AssemblePluginMojo) rule.lookupConfiguredMojo(session, execution);
        mojo.setFailOnMissingDependencies(false);
        mojo.execute();
        String sb = getTestResult(mojo);
        Assert.assertEquals("SERVER:\n" +
                "agent/\n" +
                "agent/moduleA.zip\n" +
                "server/\n" +
                "server/commons-beanutils-core-1.8.3.jar\n" +
                "server/commons-codec-1.15.jar\n" +
                "server/commons-logging-1.1.1.jar\n" +
                "server/dependency-to-plugin-1.1-SNAPSHOT.jar\n" +
                "teamcity-plugin.xml", sb);

    }

    @Test
    public void testMakeMultiModuleArtifact() throws Exception {
        MavenSession session = initMavenSession("unit/multi-module-to-test");
        MojoExecution execution = rule.newMojoExecution("build");
        AssemblePluginMojo mojo = (AssemblePluginMojo) rule.lookupConfiguredMojo(session, execution);
        mojo.setFailOnMissingDependencies(false);
        mojo.execute();
        String sb = getTestResult(mojo);
        sa.assertThat(sb).isEqualToIgnoringCase("""
                AGENT:
                lib
                lib/commons-beanutils-core-1.8.3.jar
                lib/commons-logging-1.1.1.jar
                lib/moduleA-1.1-SNAPSHOT.jar
                lib/moduleB-1.1-SNAPSHOT.jar
                teamcity-plugin.xml
                SERVER:
                agent/
                agent/multi-module-to-test.zip
                server/
                server/moduleB-1.1-SNAPSHOT.jar
                teamcity-plugin.xml""");
        // language=XML
        assertIdeaArtifacts(mojo.getAgentPluginWorkflow(),
                """
                <component name="ArtifactManager">
                    <artifact name="TC::AGENT::multi-module-to-test::4IDEA">
                        <output-path>$PROJECT_DIR$/target/teamcity/agent-unpacked</output-path>
                        <root id="root">
                            <element id="directory" name="multi-module-to-test">
                                <element artifact-name="TC::AGENT::multi-module-to-test::EXPLODED" id="artifact"/>
                            </element>
                        </root>
                    </artifact>
                </component>
                """,
                """
                <component name="ArtifactManager">
                    <artifact name="TC::AGENT::multi-module-to-test">
                        <output-path>$PROJECT_DIR$/target/teamcity/agent</output-path>
                        <root id="root">
                            <element id="archive" name="multi-module-to-test.zip">
                                <element artifact-name="TC::AGENT::multi-module-to-test::EXPLODED" id="artifact"/>
                            </element>
                        </root>
                    </artifact>
                </component>
                """,
                """
                <component name="ArtifactManager">
                    <artifact name="TC::AGENT::multi-module-to-test::EXPLODED">
                        <output-path>$PROJECT_DIR$/target/teamcity/agent-unpacked/multi-module-to-test</output-path>
                        <root id="root">
                            <element id="directory" name="lib">
                                <element id="archive" name="moduleA-1.1-SNAPSHOT.jar">
                                    <element id="module-output" name="moduleA"/>
                                </element>
                                <element id="archive" name="moduleB-1.1-SNAPSHOT.jar">
                                    <element id="module-output" name="moduleB"/>
                                </element>
                                <element id="library" level="project" name="Maven: commons-beanutils:commons-beanutils-core:1.8.3"/>
                                <element id="library" level="project" name="Maven: commons-logging:commons-logging:1.1.1"/>
                            </element>
                            <element id="file-copy" output-file-name="teamcity-plugin.xml" path="$PROJECT_DIR$/target/teamcity/teamcity-agent-plugin-generated.xml"/>
                        </root>
                    </artifact>
                </component>
                """);
        assertIdeaArtifacts(mojo.getServerPluginWorkflow(),
                // language=XML
                """
                <component name="ArtifactManager">
                    <artifact name="TC::SERVER::multi-module-to-test::EXPLODED">
                        <output-path>$PROJECT_DIR$/target/teamcity/plugin/multi-module-to-test</output-path>
                        <root id="root">
                            <element id="file-copy" output-file-name="teamcity-plugin.xml" path="$PROJECT_DIR$/target/teamcity/teamcity-plugin-generated.xml"/>
                            <element id="directory" name="server">
                                <element id="archive" name="moduleB-1.1-SNAPSHOT.jar">
                                    <element id="module-output" name="moduleB"/>
                                </element>
                            </element>
                            <element id="directory" name="agent">
                                <element artifact-name="TC::AGENT::multi-module-to-test" id="artifact"/>
                            </element>
                        </root>
                    </artifact>
                </component>
                """,
                // language=XML
                """
                <component name="ArtifactManager">
                    <artifact name="TC::SERVER::multi-module-to-test::4IDEA">
                        <output-path>$PROJECT_DIR$/target/teamcity/plugin</output-path>
                        <root id="root">
                            <element id="directory" name="multi-module-to-test">
                                <element artifact-name="TC::SERVER::multi-module-to-test::EXPLODED" id="artifact"/>
                            </element>
                        </root>
                    </artifact>
                </component>
                """,
                // language=XML
                """
                <component name="ArtifactManager">
                    <artifact name="TC::SERVER::multi-module-to-test">
                        <output-path>$PROJECT_DIR$/target/teamcity/dist</output-path>
                        <root id="root">
                            <element id="archive" name="multi-module-to-test.zip">
                                <element artifact-name="TC::SERVER::multi-module-to-test::EXPLODED" id="artifact"/>
                            </element>
                        </root>
                    </artifact>
                </component>
                """,
                // language=XML
                """
                <component name="ArtifactManager">
                    <artifact name="TC::SERVER-PACKED::multi-module-to-test">
                        <output-path>$PROJECT_DIR$/target/teamcity/dist</output-path>
                        <root id="root">
                            <element id="archive" name="multi-module-to-test-packed.zip">
                                <element artifact-name="TC::SERVER::multi-module-to-test::4IDEA" id="artifact"/>
                            </element>
                        </root>
                    </artifact>
                </component>
                """
        );
    }


}
