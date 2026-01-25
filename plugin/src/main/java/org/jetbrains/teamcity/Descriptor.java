package org.jetbrains.teamcity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Descriptor {
    @Parameter(defaultValue = "false")
    private boolean doNotGenerate;
    @Parameter(defaultValue = "false")
    private boolean failOnMissing;
    @Parameter(defaultValue = "")
    private Boolean allowRuntimeReload;
    @Parameter(defaultValue = "")
    private Boolean nodeResponsibilitiesAware;
    @Parameter(defaultValue = "")
    private Boolean useSeparateClassloader;
    @Parameter
    private List<String> pluginDependencies = new ArrayList<>();
    @Parameter
    private List<String> toolDependencies = new ArrayList<>();
    @Parameter(defaultValue = "${project.build.outputDirectory}/META-INF/teamcity-agent-plugin.xml")
    private File path;
    private boolean isInUnitTest = false;
    @Parameter
    private String pluginVersion;
    @Parameter
    private Map<String, String> parameters = new HashMap<>();

    public void adjustDefaults(File projectBuildOutputDirectory, String fileName, MavenProject project, String pluginVersion) {
        if (path == null) {
            path = projectBuildOutputDirectory.toPath().resolve("META-INF").resolve(fileName).toFile();
        }
        if (this.pluginVersion == null) {
            this.pluginVersion = pluginVersion;
        }
        if (this.pluginVersion == null) {
            this.pluginVersion = project.getProperties().getProperty("teamcity.plugin.version");
        }
        if (this.pluginVersion == null) {
            this.pluginVersion = project.getVersion();
        }
    }
}
