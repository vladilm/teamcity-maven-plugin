package org.jetbrains.teamcity.incremental;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;

public class IncrementalStateStore {
    private static final String STATE_VERSION = "1";

    private final Path stateFile;

    public IncrementalStateStore() {
        this.stateFile = null;
    }

    public IncrementalStateStore(Path stateFile) {
        this.stateFile = stateFile;
    }

    public IncrementalState load() throws IOException {
        return load(stateFile);
    }

    public IncrementalState load(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return null;
        }

        Properties properties = loadProperties(path);
        if (!STATE_VERSION.equals(properties.getProperty("version"))) {
            return null;
        }

        IncrementalState state = new IncrementalState();
        state.setConfigStamp(properties.getProperty("configStamp", ""));
        state.setLatestInputTs(parseLong(properties.getProperty("latestInputTs")));
        state.setInputFingerprint(properties.getProperty("inputFingerprint", ""));
        state.setInputs(loadInputs(properties));
        state.setOutputs(loadOutputs(properties));
        return state;
    }

    public void save(IncrementalState state) throws IOException {
        save(stateFile, state);
    }

    public void save(Path path, IncrementalState state) throws IOException {
        if (state == null) {
            return;
        }
        if (path == null) {
            throw new IllegalStateException("State file is not configured");
        }
        Files.createDirectories(path.getParent());

        Properties properties = new Properties();
        properties.setProperty("version", STATE_VERSION);
        properties.setProperty("configStamp", nullToEmpty(state.getConfigStamp()));
        properties.setProperty("latestInputTs", Long.toString(state.getLatestInputTs()));
        properties.setProperty("inputFingerprint", nullToEmpty(state.getInputFingerprint()));
        storeInputs(properties, state.getInputs());
        storeOutputs(properties, state.getOutputs());

        OutputStream outputStream = null;
        try {
            outputStream = Files.newOutputStream(path);
            properties.store(outputStream, "TeamCity assemble incremental state");
        } finally {
            if (outputStream != null) {
                outputStream.close();
            }
        }
    }

    private List<InputState> loadInputs(Properties properties) {
        List<InputState> inputs = new ArrayList<InputState>();
        int count = parseInt(properties.getProperty("input.count"));
        int i;
        for (i = 0; i < count; i++) {
            InputState input = new InputState();
            String prefix = "input." + i + ".";
            input.setKind(properties.getProperty(prefix + "kind", ""));
            input.setKey(properties.getProperty(prefix + "key", ""));
            input.setPath(toPath(properties.getProperty(prefix + "path")));
            input.setExists(Boolean.parseBoolean(properties.getProperty(prefix + "exists", "false")));
            input.setLastModified(parseLong(properties.getProperty(prefix + "lastModified")));
            input.setCount(parseLong(properties.getProperty(prefix + "count")));
            input.setTotalSize(parseLong(properties.getProperty(prefix + "totalSize")));
            input.setDetails(properties.getProperty(prefix + "details", ""));
            input.setUsesTimestamp(Boolean.parseBoolean(properties.getProperty(prefix + "usesTimestamp", "false")));
            inputs.add(input);
        }
        return inputs;
    }

    private List<OutputState> loadOutputs(Properties properties) {
        List<OutputState> outputs = new ArrayList<OutputState>();
        int count = parseInt(properties.getProperty("output.count"));
        int i;
        for (i = 0; i < count; i++) {
            String prefix = "output." + i + ".";
            OutputState output = new OutputState();
            output.setType(properties.getProperty(prefix + "type", ""));
            output.setClassifier(properties.getProperty(prefix + "classifier", ""));
            output.setPath(toPath(properties.getProperty(prefix + "path")));
            output.setLastModified(parseLong(properties.getProperty(prefix + "lastModified")));
            outputs.add(output);
        }
        sortOutputs(outputs);
        return outputs;
    }

    private void storeInputs(Properties properties, List<InputState> inputs) {
        List<InputState> safeInputs = inputs == null ? new ArrayList<InputState>() : inputs;
        properties.setProperty("input.count", Integer.toString(safeInputs.size()));
        int i;
        for (i = 0; i < safeInputs.size(); i++) {
            InputState input = safeInputs.get(i);
            String prefix = "input." + i + ".";
            properties.setProperty(prefix + "kind", nullToEmpty(input.getKind()));
            properties.setProperty(prefix + "key", nullToEmpty(input.getKey()));
            properties.setProperty(prefix + "path", input.getPath() == null ? "" : input.getPath().toString());
            properties.setProperty(prefix + "exists", Boolean.toString(input.isExists()));
            properties.setProperty(prefix + "lastModified", Long.toString(input.getLastModified()));
            properties.setProperty(prefix + "count", Long.toString(input.getCount()));
            properties.setProperty(prefix + "totalSize", Long.toString(input.getTotalSize()));
            properties.setProperty(prefix + "details", nullToEmpty(input.getDetails()));
            properties.setProperty(prefix + "usesTimestamp", Boolean.toString(input.isUsesTimestamp()));
        }
    }

    private void storeOutputs(Properties properties, List<OutputState> outputs) {
        List<OutputState> safeOutputs = outputs == null ? new ArrayList<OutputState>() : new ArrayList<OutputState>(outputs);
        sortOutputs(safeOutputs);
        properties.setProperty("output.count", Integer.toString(safeOutputs.size()));
        int i;
        for (i = 0; i < safeOutputs.size(); i++) {
            OutputState output = safeOutputs.get(i);
            String prefix = "output." + i + ".";
            properties.setProperty(prefix + "type", nullToEmpty(output.getType()));
            properties.setProperty(prefix + "classifier", nullToEmpty(output.getClassifier()));
            properties.setProperty(prefix + "path", output.getPath() == null ? "" : output.getPath().toString());
            properties.setProperty(prefix + "lastModified", Long.toString(output.getLastModified()));
        }
    }

    private Properties loadProperties(Path path) throws IOException {
        Properties properties = new Properties();
        InputStream inputStream = null;
        try {
            inputStream = Files.newInputStream(path);
            properties.load(inputStream);
        } finally {
            if (inputStream != null) {
                inputStream.close();
            }
        }
        return properties;
    }

    private void sortOutputs(List<OutputState> outputs) {
        Collections.sort(outputs, new Comparator<OutputState>() {
            @Override
            public int compare(OutputState first, OutputState second) {
                return first.identity().compareTo(second.identity());
            }
        });
    }

    private Path toPath(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        return Paths.get(value);
    }

    private int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            return 0;
        }
    }

    private long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (Exception e) {
            return 0L;
        }
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
