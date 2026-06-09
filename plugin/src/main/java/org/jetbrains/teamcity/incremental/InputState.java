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

    public String describeChangeFrom(InputState previous) {
        if (previous == null) {
            return "input added: " + identity() + " current=" + toFingerprint();
        }

        StringBuilder builder = new StringBuilder();
        builder.append("input changed: ");
        String previousIdentity = previous.identity();
        String currentIdentity = identity();
        if (Objects.equals(previousIdentity, currentIdentity)) {
            builder.append(currentIdentity);
        } else {
            builder.append(previousIdentity).append(" -> ").append(currentIdentity);
        }

        StringBuilder fields = new StringBuilder();
        appendFieldChange(fields, "kind", previous.kind, kind);
        appendFieldChange(fields, "key", previous.key, key);
        appendFieldChange(fields, "path", previous.path, path);
        appendFieldChange(fields, "exists", Boolean.valueOf(previous.exists), Boolean.valueOf(exists));
        appendFieldChange(fields, "lastModified", Long.valueOf(previous.lastModified), Long.valueOf(lastModified));
        appendFieldChange(fields, "count", Long.valueOf(previous.count), Long.valueOf(count));
        appendFieldChange(fields, "totalSize", Long.valueOf(previous.totalSize), Long.valueOf(totalSize));
        appendFieldChange(fields, "details", previous.details, details);
        appendFieldChange(fields, "usesTimestamp", Boolean.valueOf(previous.usesTimestamp), Boolean.valueOf(usesTimestamp));

        if (fields.length() > 0) {
            builder.append(" changed fields: ").append(fields);
        } else {
            builder.append(" previous=").append(previous.toFingerprint())
                    .append(" current=").append(toFingerprint());
        }
        return builder.toString();
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

    private static void appendFieldChange(StringBuilder builder, String field, Object previous, Object current) {
        if (Objects.equals(previous, current)) {
            return;
        }
        if (builder.length() > 0) {
            builder.append(", ");
        }
        builder.append(field)
                .append(' ')
                .append(formatValue(previous))
                .append(" -> ")
                .append(formatValue(current));
    }

    private static String formatValue(Object value) {
        if (value == null) {
            return "<null>";
        }
        String text = String.valueOf(value);
        if (text.length() <= 800) {
            return text;
        }
        return text.substring(0, 800) + "...";
    }
}
