package com.snoworca.naruserve.common;

import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Shared attribute storage semantics for requests, applications and WebSocket sessions. */
// @req FR-DX-001
public final class AttributeValues {
    private AttributeValues() { }

    public static void put(Map<String, Object> values, String key, @Nullable Object value) {
        Objects.requireNonNull(key, "key");
        if (value == null) values.remove(key);
        else values.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public static <T> @Nullable T get(Map<String, Object> values, String key, Class<T> type) {
        return (T) wrapper(Objects.requireNonNull(type, "type")).cast(values.get(Objects.requireNonNull(key, "key")));
    }

    public static Class<?> wrapper(Class<?> type) {
        Objects.requireNonNull(type, "type");
        if (!type.isPrimitive()) return type;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == boolean.class) return Boolean.class;
        if (type == double.class) return Double.class;
        if (type == float.class) return Float.class;
        if (type == short.class) return Short.class;
        if (type == byte.class) return Byte.class;
        if (type == char.class) return Character.class;
        return Void.class;
    }
}
