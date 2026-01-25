package org.jetbrains.teamcity;

import javax.annotation.Nullable;
import java.nio.file.FileSystems;
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

    public static boolean isNotEmpty(@Nullable String s) {
        return s != null && !s.isEmpty();
    }

    public static Path ofPath(String first, String... more) {
        return FileSystems.getDefault().getPath(first, more);
    }

    public static <K, V> Map<K,V> ofMap(K k1, V v1) {
        HashMap<K, V> m = new HashMap<>();
        m.put(k1, v1);
        return m;
    }

    public static <K, V> Map<K,V> ofMap(K k1, V v1, K k2, V v2) {
        HashMap<K, V> m = new HashMap<>();
        m.put(k1, v1);
        m.put(k2, v2);
        return m;
    }
}
