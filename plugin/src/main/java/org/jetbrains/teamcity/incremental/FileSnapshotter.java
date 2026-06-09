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
            snapshot.setCount(1L);
            snapshot.setTotalSize(Files.size(path));
            snapshot.setLastModified(getLastModified(path));
            return snapshot;
        }

        final FileSnapshot result = snapshot;
        Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
            @Override
            public java.nio.file.FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (shouldIgnore(file, ignoredFileNames)) {
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
                result.setCount(result.getCount() + 1L);
                result.setTotalSize(result.getTotalSize() + attrs.size());
                if (attrs.lastModifiedTime().toMillis() > result.getLastModified()) {
                    result.setLastModified(attrs.lastModifiedTime().toMillis());
                }
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
        if (result.getCount() == 0L) {
            result.setLastModified(getLastModified(path));
        }
        return snapshot;
    }

    private boolean shouldIgnore(Path file, List<String> ignoredFileNames) {
        if (file == null || ignoredFileNames == null || ignoredFileNames.isEmpty()) {
            return false;
        }
        Path fileName = file.getFileName();
        if (fileName == null) {
            return false;
        }

        int i;
        for (i = 0; i < ignoredFileNames.size(); i++) {
            if (Objects.equals(fileName.toString(), ignoredFileNames.get(i))) {
                return true;
            }
        }
        return false;
    }

    private long getLastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }
}
