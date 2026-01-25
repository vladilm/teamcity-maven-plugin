package org.jetbrains.teamcity;

import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.testing.ArtifactStubFactory;
import org.apache.maven.plugin.testing.stubs.MavenProjectStub;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class BaseMavenModuleStub extends MavenProjectStub {
    private static final ArtifactStubFactory artifactFactory = new ArtifactStubFactory();
    private List<Dependency> dependencies = new ArrayList<>();

    public BaseMavenModuleStub(String groupId, String artifactId, String version) {
        try {
            setArtifact(artifactFactory.createArtifact(groupId, artifactId, version));
            setGroupId(groupId);
            setArtifactId(artifactId);
            setVersion(version);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    protected void addDependency(String dependencySpec) {
        String[] spec = dependencySpec.split(":");
        Dependency mavenDependency = new Dependency();
        mavenDependency.setGroupId( spec[0] );
        mavenDependency.setArtifactId( spec[1] );
        mavenDependency.setVersion( spec[2] );
        if (spec.length > 3 && !spec[3].isBlank()) {
            mavenDependency.setClassifier( spec[3] );
        }
        if (spec.length > 4 && !spec[4].isBlank()) {
            mavenDependency.setType( spec[4] );
        }
        if (spec.length > 5 && !spec[5].isBlank()) {
            mavenDependency.setScope( spec[5] );
        }
        if (spec.length > 6 && !spec[6].isBlank()) {
            mavenDependency.setOptional( spec[6] );
        }
        dependencies.add(mavenDependency);
    }

    public void setDependencies( List<Dependency> list ) {
        this.dependencies = list;
    }

    public List<Dependency> getDependencies() {
        return dependencies;
    }
}
