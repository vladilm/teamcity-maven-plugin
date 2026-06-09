package org.jetbrains.teamcity.incremental;

import java.nio.file.Path;

public class FileSnapshot {
    private Path path;
    private boolean exists;
    private long lastModified;
    private long count;
    private long totalSize;
    private String details;

    public String describe() {
        String summary = (path == null ? "" : path.toString())
                + "|" + exists
                + "|" + lastModified
                + "|" + count
                + "|" + totalSize;
        if (details == null || details.isEmpty()) {
            return summary;
        }
        return summary + "|" + details;
    }

    public Path getPath() {
        return path;
    }

    public void setPath(Path path) {
        this.path = path;
    }

    public boolean isExists() {
        return exists;
    }

    public void setExists(boolean exists) {
        this.exists = exists;
    }

    public long getLastModified() {
        return lastModified;
    }

    public void setLastModified(long lastModified) {
        this.lastModified = lastModified;
    }

    public long getCount() {
        return count;
    }

    public void setCount(long count) {
        this.count = count;
    }

    public long getTotalSize() {
        return totalSize;
    }

    public void setTotalSize(long totalSize) {
        this.totalSize = totalSize;
    }

    public String getDetails() {
        return details;
    }

    public void setDetails(String details) {
        this.details = details;
    }
}
