package org.jetbrains.teamcity.incremental;

import java.nio.file.Path;

public class FileSnapshot {
    private Path path;
    private boolean exists;
    private long lastModified;
    private long count;
    private long totalSize;

    public String describe() {
        return (path == null ? "" : path.toString())
                + "|" + exists
                + "|" + lastModified
                + "|" + count
                + "|" + totalSize;
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
}
