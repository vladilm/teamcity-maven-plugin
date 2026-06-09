package org.jetbrains.teamcity.incremental;

import java.nio.file.Path;

public class OutputState {
    private String type;
    private String classifier;
    private Path path;
    private long lastModified;

    public String identity() {
        return emptyIfNull(classifier) + "|" + emptyIfNull(type) + "|" + (path == null ? "" : path.toString());
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getClassifier() {
        return classifier;
    }

    public void setClassifier(String classifier) {
        this.classifier = classifier;
    }

    public Path getPath() {
        return path;
    }

    public void setPath(Path path) {
        this.path = path;
    }

    public long getLastModified() {
        return lastModified;
    }

    public void setLastModified(long lastModified) {
        this.lastModified = lastModified;
    }

    private static String emptyIfNull(String value) {
        return value == null ? "" : value;
    }
}
