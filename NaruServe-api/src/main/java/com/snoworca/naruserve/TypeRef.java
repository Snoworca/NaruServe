package com.snoworca.naruserve;

import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.Arrays;

/**
 * Captures a generic type for {@link Request#body(TypeRef)}: {@code new TypeRef<List<User>>() {}}. Minified Android
 * builds keep the captured signature through the consumer rules in {@code META-INF/proguard/naruserve-api.pro}.
 * A Kotlin {@code object : TypeRef<List<String>>() {}} captures the same {@code List<String>} as Java: the covariant
 * wildcards Kotlin writes for declaration-site variance ({@code List<? extends String>}) are dropped.
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
        this.type = isKotlin(getClass()) ? dropCovariantWildcards(arg) : arg;
    }

    /** The captured type argument, such as {@code List<User>} or {@code String.class}. */
    public final Type getType() {
        return type;
    }

    // shortcut: detected by the kotlin.Metadata annotation, which an R8 build may strip; the type then keeps
    // Kotlin's wildcards (codecs such as Gson still read it the same). Upgrade if equality matters there.
    private static boolean isKotlin(Class<?> c) {
        for (Annotation a : c.getDeclaredAnnotations()) {
            if (a.annotationType().getName().equals("kotlin.Metadata")) {
                return true;
            }
        }
        return false;
    }

    // shortcut: walks ParameterizedType arguments only, so Array<List<String>> keeps its wildcard; extend to
    // GenericArrayType if a Kotlin caller needs it.
    private static Type dropCovariantWildcards(Type t) {
        if (t instanceof WildcardType) {
            WildcardType w = (WildcardType) t;
            Type[] upper = w.getUpperBounds();
            if (w.getLowerBounds().length == 0 && upper.length == 1) {
                return dropCovariantWildcards(upper[0]);
            }
            return t;
        }
        if (!(t instanceof ParameterizedType)) {
            return t;
        }
        ParameterizedType p = (ParameterizedType) t;
        Type[] args = p.getActualTypeArguments().clone();
        boolean changed = false;
        for (int i = 0; i < args.length; i++) {
            Type a = dropCovariantWildcards(args[i]);
            changed |= a != args[i];
            args[i] = a;
        }
        return changed ? new Parameterized(p.getOwnerType(), p.getRawType(), args) : t;
    }

    /** A ParameterizedType whose equals and hashCode follow the JDK's, so equality holds both ways. */
    private static final class Parameterized implements ParameterizedType {
        private final Type owner;
        private final Type raw;
        private final Type[] args;

        Parameterized(Type owner, Type raw, Type[] args) {
            this.owner = owner;
            this.raw = raw;
            this.args = args;
        }

        @Override
        public Type[] getActualTypeArguments() {
            return args.clone();
        }

        @Override
        public Type getRawType() {
            return raw;
        }

        @Override
        public Type getOwnerType() {
            return owner;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof ParameterizedType)) {
                return false;
            }
            ParameterizedType p = (ParameterizedType) o;
            return (owner == null ? p.getOwnerType() == null : owner.equals(p.getOwnerType()))
                    && raw.equals(p.getRawType()) && Arrays.equals(args, p.getActualTypeArguments());
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(args) ^ (owner == null ? 0 : owner.hashCode()) ^ raw.hashCode();
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(name(raw)).append('<');
            for (int i = 0; i < args.length; i++) {
                sb.append(i == 0 ? "" : ", ").append(name(args[i]));
            }
            return sb.append('>').toString();
        }

        // Type.getTypeName() is API 26+.
        private static String name(Type t) {
            return t instanceof Class ? ((Class<?>) t).getName() : t.toString();
        }
    }
}
