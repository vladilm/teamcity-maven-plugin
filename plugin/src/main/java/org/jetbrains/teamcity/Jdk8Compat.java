package org.jetbrains.teamcity;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public final class Jdk8Compat {

    @SafeVarargs
    @SuppressWarnings("varargs")
    public static <E> List<E> of(E... elements) {
        switch (elements.length) { // implicit null check of elements
            case 0:
                @SuppressWarnings("unchecked")
                List<E> list = (List<E>) Collections.EMPTY_LIST;
                return list;
            case 1:
                return Collections.singletonList(elements[0]);
            default:
                return Arrays.asList(elements);
        }
    }

    public static boolean isBlank(String s) {
        return s == null || s.length() == 0;
    }

    public static Path ofPath(String first, String... more) {
        return FileSystems.getDefault().getPath(first, more);
    }

    public static <K, V> Map<K,V> ofMap(K create, V aTrue) {
        HashMap<K, V> m = new HashMap<>();
        m.put(create, aTrue);
        return m;
    }

    public static <K, V> Map<K,V> ofMap(K create, V aTrue, K create1, V aTrue1) {
        HashMap<K, V> m = new HashMap<>();
        m.put(create, aTrue);
        m.put(create1, aTrue1);
        return m;
    }

    public static void writeStringToFile(Path path, byte[] arr) throws IOException {
        Files.write(path, arr);
    }
}
