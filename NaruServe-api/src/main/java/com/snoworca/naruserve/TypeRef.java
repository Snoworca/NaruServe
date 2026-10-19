package com.snoworca.naruserve;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;

/**
 * Captures a generic type for {@link Request#body(TypeRef)}: {@code new TypeRef<List<User>>() {}}. Minified Android
 * builds keep the captured signature through the consumer rules in {@code META-INF/proguard/naruserve-api.pro}.
 */
// @req IR-JSON-001
public abstract class TypeRef<T> {

    private final Type type;

    /** Reads the type argument of the anonymous subclass; {@link IllegalStateException} for a raw or variable one. */
    // @req IR-JSON-001
    protected TypeRef() {
        Type superclass = getClass().getGenericSuperclass();
        Type arg = superclass instanceof ParameterizedType
                ? ((ParameterizedType) superclass).getActualTypeArguments()[0] : null;
        if (arg == null || arg instanceof TypeVariable) {
            throw new IllegalStateException("TypeRef needs a concrete type argument");
        }
        this.type = arg;
    }

    /** The captured type argument, such as {@code List<User>} or {@code String.class}. */
    public final Type getType() {
        return type;
    }
}
