package org.jetbrains.teamcity.incremental;

import java.nio.file.Path;
import java.util.Objects;

public class InputState {
    private String kind;
    private String key;
    private Path path;
    private boolean exists;
    private long lastModified;
    private long count;
    private long totalSize;
    private String details;
    private boolean usesTimestamp;

    public boolean sameAs(InputState other) {
        if (other == null) {
            return false;
        }
        return Objects.equals(kind, other.kind)
                && Objects.equals(key, other.key)
                && Objects.equals(path, other.path)
                && exists == other.exists
                && lastModified == other.lastModified
                && count == other.count
                && totalSize == other.totalSize
                && Objects.equals(details, other.details)
                && usesTimestamp == other.usesTimestamp;
    }

    public String identity() {
        return emptyIfNull(kind) + "|" + emptyIfNull(key) + "|" + (path == null ? "" : path.toString());
    }

    public String toFingerprint() {
        return identity()
                + "|" + exists
                + "|" + lastModified
                + "|" + count
                + "|" + totalSize
                + "|" + emptyIfNull(details)
                + "|" + usesTimestamp;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
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

    public boolean isUsesTimestamp() {
        return usesTimestamp;
    }

    public void setUsesTimestamp(boolean usesTimestamp) {
        this.usesTimestamp = usesTimestamp;
    }

    private static String emptyIfNull(String value) {
        return value == null ? "" : value;
    }
}
