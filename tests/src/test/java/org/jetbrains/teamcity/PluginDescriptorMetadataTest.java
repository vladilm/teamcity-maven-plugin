package org.jetbrains.teamcity;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

public class PluginDescriptorMetadataTest {
    @Test
    public void generatedPluginDescriptorMarksAllGoalsThreadSafe() throws Exception {
        try (InputStream pluginXml = AssemblePluginMojo.class.getResourceAsStream("/META-INF/maven/plugin.xml")) {
            assertThat(pluginXml).as("generated Maven plugin descriptor").isNotNull();

            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            Document document = factory.newDocumentBuilder().parse(pluginXml);

            NodeList mojos = document.getElementsByTagName("mojo");

            for (int i = 0; i < mojos.getLength(); i++) {
                org.w3c.dom.Element mojo = (org.w3c.dom.Element) mojos.item(i);
                String goal = mojo.getElementsByTagName("goal").item(0).getTextContent();
                String threadSafe = mojo.getElementsByTagName("threadSafe").item(0).getTextContent();
                assertThat(threadSafe)
                        .as("goal %s should be marked thread-safe in plugin.xml", goal)
                        .isEqualTo("true");
            }
        }
    }
}
