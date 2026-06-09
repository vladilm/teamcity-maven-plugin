package org.jetbrains.teamcity.incremental;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class FileSnapshotter {
    private final List<String> ignoredPathPatterns;

    public FileSnapshotter() {
        this(Collections.<String>emptyList());
    }

    public FileSnapshotter(String ignoredPathPatterns) {
        this(parsePatterns(ignoredPathPatterns));
    }

    public FileSnapshotter(List<String> ignoredPathPatterns) {
        this.ignoredPathPatterns = normalizePatterns(ignoredPathPatterns);
    }

    public FileSnapshot snapshotPath(Path path) throws IOException {
        return snapshotPath(path, Collections.<String>emptyList());
    }

    public FileSnapshot snapshotToolInput(Path path, String projectArtifactFileName) throws IOException {
        List<String> ignoredFileNames = new ArrayList<String>();
        ignoredFileNames.add("teamcity-plugin.xml");
        if (projectArtifactFileName != null) {
            ignoredFileNames.add(projectArtifactFileName);
        }
        return snapshotPath(path, ignoredFileNames);
    }

    public FileSnapshot snapshotPath(Path path, final List<String> ignoredFileNames) throws IOException {
        FileSnapshot snapshot = new FileSnapshot();
        snapshot.setPath(path);
        if (path == null || !Files.exists(path)) {
            snapshot.setExists(false);
            return snapshot;
        }

        snapshot.setExists(true);
        if (Files.isRegularFile(path)) {
            if (shouldIgnoreRootFile(path)) {
                snapshot.setExists(false);
                return snapshot;
            }
            snapshot.setCount(1L);
            snapshot.setTotalSize(Files.size(path));
            snapshot.setLastModified(getLastModified(path));
            return snapshot;
        }

        final FileSnapshot result = snapshot;
        final List<String> details = new ArrayList<String>();
        Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
            @Override
            public java.nio.file.FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!path.equals(dir) && shouldIgnore(path, dir, ignoredFileNames)) {
                    return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                }
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (shouldIgnore(path, file, ignoredFileNames)) {
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
                result.setCount(result.getCount() + 1L);
                result.setTotalSize(result.getTotalSize() + attrs.size());
                details.add(toDetail(path.relativize(file), attrs));
                if (attrs.lastModifiedTime().toMillis() > result.getLastModified()) {
                    result.setLastModified(attrs.lastModifiedTime().toMillis());
                }
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
        Collections.sort(details);
        snapshot.setDetails(join(details));
        if (result.getCount() == 0L) {
            result.setLastModified(getLastModified(path));
        }
        return snapshot;
    }

    private boolean shouldIgnoreRootFile(Path path) {
        if (ignoredPathPatterns.isEmpty()) {
            return false;
        }
        Path fileName = path.getFileName();
        return fileName != null && matchesAnyPattern(fileName.toString());
    }

    private String toDetail(Path relativePath, BasicFileAttributes attrs) {
        return normalize(relativePath)
                + ":" + attrs.size()
                + ":" + attrs.lastModifiedTime().toMillis();
    }

    private String normalize(Path path) {
        return path.toString().replace('\\', '/');
    }

    private String join(List<String> values) {
        StringBuilder builder = new StringBuilder();
        int i;
        for (i = 0; i < values.size(); i++) {
            if (builder.length() > 0) {
                builder.append(',');
            }
            builder.append(values.get(i));
        }
        return builder.toString();
    }

    private boolean shouldIgnore(Path root, Path file, List<String> ignoredFileNames) {
        Path fileName = file.getFileName();
        if (fileName != null && ignoredFileNames != null) {
            int i;
            for (i = 0; i < ignoredFileNames.size(); i++) {
                if (Objects.equals(fileName.toString(), ignoredFileNames.get(i))) {
                    return true;
                }
            }
        }

        int i;
        for (i = 0; i < ignoredPathPatterns.size(); i++) {
            if (matchesPattern(root, file, ignoredPathPatterns.get(i))) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesPattern(Path root, Path file, String pattern) {
        String relative = normalize(root.relativize(file));
        String fileName = file.getFileName() == null ? "" : file.getFileName().toString();
        return Objects.equals(relative, pattern)
                || relative.startsWith(pattern + "/")
                || Objects.equals(fileName, pattern)
                || matchesSimpleGlob(relative, pattern)
                || matchesSimpleGlob(fileName, pattern);
    }

    private boolean matchesAnyPattern(String value) {
        int i;
        for (i = 0; i < ignoredPathPatterns.size(); i++) {
            String pattern = ignoredPathPatterns.get(i);
            if (Objects.equals(value, pattern) || matchesSimpleGlob(value, pattern)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesSimpleGlob(String value, String pattern) {
        if (pattern.indexOf('*') < 0) {
            return false;
        }
        StringBuilder regex = new StringBuilder();
        int i;
        for (i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '*') {
                regex.append(".*");
            } else if ("\\.[]{}()+-^$?|".indexOf(c) >= 0) {
                regex.append('\\').append(c);
            } else {
                regex.append(c);
            }
        }
        return value.matches(regex.toString());
    }

    private long getLastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static List<String> parsePatterns(String value) {
        if (value == null || value.trim().isEmpty()) {
            return Collections.emptyList();
        }
        List<String> patterns = new ArrayList<String>();
        String[] parts = value.split(",");
        int i;
        for (i = 0; i < parts.length; i++) {
            patterns.add(parts[i]);
        }
        return patterns;
    }

    private static List<String> normalizePatterns(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<String>();
        int i;
        for (i = 0; i < values.size(); i++) {
            String value = values.get(i);
            if (value == null) {
                continue;
            }
            String normalized = value.trim().replace('\\', '/');
            while (normalized.startsWith("/")) {
                normalized = normalized.substring(1);
            }
            while (normalized.endsWith("/")) {
                normalized = normalized.substring(0, normalized.length() - 1);
            }
            if (!normalized.isEmpty()) {
                result.add(normalized);
            }
        }
        return result;
    }
}
