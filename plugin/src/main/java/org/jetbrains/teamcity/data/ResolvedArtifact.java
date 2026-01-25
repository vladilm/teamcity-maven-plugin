package org.jetbrains.teamcity.data;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.eclipse.aether.artifact.Artifact;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class ResolvedArtifact {
    private  static final Logger LOG = LoggerFactory.getLogger(ResolvedArtifact.class);

    private final org.eclipse.aether.artifact.Artifact source;
    private final boolean reactorProject;

    public ResolvedArtifact(Artifact source, boolean isReactorProject) {
        this.source = source;
        this.reactorProject = isReactorProject;
    }

    public boolean isReactorProject() {
        return reactorProject;
    }

    public String getFileName() {
        String name = source.getFile().getName();
        if (source.getClassifier() != null && Objects.equals("teamcity-agent-plugin", source.getClassifier())) {
            name = source.getArtifactId() + "." + source.getExtension();
            try {
                if (source.getFile().exists()) {
                    ZipFile file = new ZipFile(source.getFile());
                    ZipEntry pluginDescriptorEntry = file.getEntry("teamcity-plugin.xml");
                    if (pluginDescriptorEntry != null) {
                        InputStream inputStream = file.getInputStream(pluginDescriptorEntry);
                        BufferedReader isr = new BufferedReader(new InputStreamReader(inputStream));
                        Map<String, String> map = isr.lines().flatMap(ResolvedArtifact::lookupPluginName).collect(Collectors.toMap(it -> it.getKey(), it -> it.getValue()));
                        if (map.get("AGENT_PLUGIN_NAME") != null) {
                            name = map.get("AGENT_PLUGIN_NAME") + "." + source.getExtension();
                        }
                    }
                }
            } catch (IOException e) {
                LOG.warn("Error while fetching agent plugin name of " +  source.getFile(), e);
            }
        }
        return name;
    }

    public static Stream<Map.Entry<String, String>> lookupPluginName(String line) {
        Pattern pat = Pattern.compile("@@([\\s\\-\\w]+)=([\\s\\w\\-]+)@@");
        Matcher matcher = pat.matcher(line);
        if (matcher.find()) {
            return Stream.of(new ImmutablePair<>(matcher.group(1), matcher.group(2)));
        }
        return Stream.empty();
    }

}