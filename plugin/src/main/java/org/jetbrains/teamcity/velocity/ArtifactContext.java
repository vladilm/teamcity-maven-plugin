package org.jetbrains.teamcity.velocity;

import org.apache.maven.project.MavenProject;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.runtime.RuntimeConstants;
import org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader;
import org.jetbrains.teamcity.Jdk8Compat;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;

public class ArtifactContext extends VelocityContext {
    private final String template = "artifact.vm";
    public ArtifactContext(MavenProject project, String artifactName) {
        super(Jdk8Compat.ofMap("artifactName", artifactName, "project", project));
    }

    public void generate(Path destination) throws IOException {
        File descriptor = destination.toFile();
        try (FileWriter fw = new FileWriter(descriptor)) {
            VelocityEngine ve = new VelocityEngine();
            ve.setProperty(RuntimeConstants.RESOURCE_LOADERS, "classpath");
            ve.setProperty("resource.loader.classpath.class", ClasspathResourceLoader.class.getName());
            Template t = ve.getTemplate(template);
            t.merge(this, fw);
        }
    }
}
