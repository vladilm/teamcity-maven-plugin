package org.jetbrains.teamcity.data;

import lombok.Getter;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.eclipse.aether.artifact.Artifact;
import org.jetbrains.teamcity.Jdk8Compat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.List;
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
    @Getter
    private final boolean reactorProject;

    public ResolvedArtifact(Artifact source, boolean isReactorProject) {
        this.source = source;
        this.reactorProject = isReactorProject;
    }

    private static final String AgentPluginNameKey = "AGENT_PLUGIN_NAME";

    public String getFileName() {
        File sourceFile = source.getFile();
        String name = sourceFile.getName();
        if (Objects.equals("teamcity-agent-plugin", source.getClassifier())) {
            name = source.getArtifactId() + "." + source.getExtension();
            try {
                if (sourceFile.exists()) {
                    try (ZipFile file = new ZipFile(sourceFile)) {
                        ZipEntry pluginDescriptorEntry = file.getEntry("teamcity-plugin.xml");
                        if (pluginDescriptorEntry == null) {
                            return name;
                        }
                        InputStream inputStream = file.getInputStream(pluginDescriptorEntry);
                        BufferedReader isr = new BufferedReader(new InputStreamReader(inputStream));
                        List<Map.Entry<String, String>> entries = isr.lines()
                                .flatMap(ResolvedArtifact::lookupPluginName)
                                .filter(e -> AgentPluginNameKey.equals(e.getKey()))
                                .collect(Collectors.toList());
                        if (entries.size() > 1) {
                            String values = entries.stream().map(Map.Entry::getValue)
                                    .collect(Collectors.joining(","));
                            throw new IllegalStateException(String.format(
                                    "Duplicate key %s, values are: %s", AgentPluginNameKey, values));
                        }
                        String agentPluginName = entries.size() == 1 ? entries.get(0).getValue() : null;
                        if (Jdk8Compat.isNotEmpty(agentPluginName)) {
                            name = agentPluginName + "." + source.getExtension();
                        }
                    }
                }
            } catch (IOException e) {
                LOG.warn("Error while fetching agent plugin name of {}", sourceFile, e);
            }
        }
        return name;
    }

    private static final Pattern AssignPattern = Pattern.compile("@@([\\s\\-\\w]+)=([\\s\\w\\-]+)@@");

    public static Stream<Map.Entry<String, String>> lookupPluginName(String line) {
        Matcher matcher = AssignPattern.matcher(line);
        if (matcher.find()) {
            return Stream.of(new ImmutablePair<>(matcher.group(1), matcher.group(2)));
        }
        return Stream.empty();
    }

}