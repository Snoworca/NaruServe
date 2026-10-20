package com.snoworca.naruserve;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedArrayType;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.AnnotatedWildcardType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.TypeVariable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Reusable Kotlin contract checks; each returns sorted failure messages, empty means pass. */
// @req FR-KT-001
// @req FR-KT-002
final class KotlinContractChecks {

    /** Kotlin hard keywords that are legal Java identifiers; soft keywords such as get stay allowed. */
    private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList(
            "as", "fun", "in", "is", "object", "typealias", "typeof", "val", "var", "when"));

    private static final String NULL_MARKED = "org.jspecify.annotations.NullMarked";
    private static final String NULLABLE = "org.jspecify.annotations.Nullable";

    private KotlinContractChecks() {
    }

    /** One {@code package <name> lacks @NullMarked} per package whose package-info lacks it or is missing. */
    // @req FR-KT-001
    static List<String> nullMarkedFailures(ClassLoader loader, Collection<String> packages) {
        Set<String> failures = new TreeSet<>();
        for (String pkg : packages) {
            Class<?> info;
            try {
                info = Class.forName(pkg + ".package-info", false, loader);
            } catch (ClassNotFoundException e) {
                info = null;
            }
            if (info == null || !has(info.getAnnotations(), NULL_MARKED)) {
                failures.add("package " + pkg + " lacks @NullMarked");
            }
        }
        return new ArrayList<>(failures);
    }

    /** {@code Type.method} per value-returning public overload whose {@code @Nullable} return disagrees with the row. */
    // @req FR-KT-001
    static List<String> accessorFailures(Collection<Class<?>> types, Map<String, Boolean> table) {
        Set<String> failures = new TreeSet<>();
        for (Class<?> type : types) {
            for (Method m : type.getMethods()) {
                Boolean nullable = table.get(m.getName());
                if (nullable == null || m.isBridge() || m.isSynthetic() || m.getReturnType() == void.class) {
                    continue;
                }
                if (nullable != has(m.getAnnotatedReturnType().getAnnotations(), NULLABLE)) {
                    failures.add(type.getSimpleName() + "." + m.getName());
                }
            }
        }
        return new ArrayList<>(failures);
    }

    /** {@code Type.member} (or {@code Type}) per NonNull on a type, member, parameter or type use. */
    // @req FR-KT-001
    static List<String> nonNullFailures(Collection<Class<?>> types) {
        Set<String> failures = new TreeSet<>();
        for (Class<?> type : types) {
            String name = type.getSimpleName();
            if (nonNull(type) || nonNull(type.getAnnotatedSuperclass())
                    || nonNull(type.getAnnotatedInterfaces()) || nonNull(type.getTypeParameters())) {
                failures.add(name);
            }
            for (Field f : type.getDeclaredFields()) {
                if (nonNull(f) || nonNull(f.getAnnotatedType())) {
                    failures.add(name + "." + f.getName());
                }
            }
            for (Method m : type.getDeclaredMethods()) {
                if (nonNull(m) || nonNull(m.getAnnotatedReturnType())) {
                    failures.add(name + "." + m.getName());
                }
                if (nonNullSignature(m)) {
                    failures.add(name + "." + m.getName());
                }
            }
            for (Constructor<?> c : type.getDeclaredConstructors()) {
                if (nonNull(c) || nonNullSignature(c)) {
                    failures.add(name + ".<init>");
                }
            }
        }
        return new ArrayList<>(failures);
    }

    /** {@code Type.method} per public or protected declared method named with one of {@link #KEYWORDS}. */
    // @req FR-KT-002
    static List<String> keywordFailures(Collection<Class<?>> types) {
        Set<String> failures = new TreeSet<>();
        for (Class<?> type : types) {
            for (Method m : type.getDeclaredMethods()) {
                int mod = m.getModifiers();
                if ((Modifier.isPublic(mod) || Modifier.isProtected(mod)) && KEYWORDS.contains(m.getName())) {
                    failures.add(type.getSimpleName() + "." + m.getName());
                }
            }
        }
        return new ArrayList<>(failures);
    }

    private static boolean nonNullSignature(Executable e) {
        for (Parameter p : e.getParameters()) {
            if (nonNull(p) || nonNull(p.getAnnotatedType())) {
                return true;
            }
        }
        return nonNull(e.getAnnotatedExceptionTypes()) || nonNull(e.getTypeParameters());
    }

    private static boolean nonNull(TypeVariable<?>[] variables) {
        for (TypeVariable<?> v : variables) {
            if (nonNull(v) || nonNull(v.getAnnotatedBounds())) {
                return true;
            }
        }
        return false;
    }

    private static boolean nonNull(AnnotatedType[] types) {
        for (AnnotatedType t : types) {
            if (nonNull(t)) {
                return true;
            }
        }
        return false;
    }

    /** Walks generic arguments, array components and wildcard bounds; a type-variable use stops there. */
    private static boolean nonNull(AnnotatedType type) {
        if (type == null) {
            return false;
        }
        if (nonNull((AnnotatedElement) type)) {
            return true;
        }
        if (type instanceof AnnotatedParameterizedType) {
            return nonNull(((AnnotatedParameterizedType) type).getAnnotatedActualTypeArguments());
        }
        if (type instanceof AnnotatedArrayType) {
            return nonNull(((AnnotatedArrayType) type).getAnnotatedGenericComponentType());
        }
        if (type instanceof AnnotatedWildcardType) {
            AnnotatedWildcardType w = (AnnotatedWildcardType) type;
            return nonNull(w.getAnnotatedUpperBounds()) || nonNull(w.getAnnotatedLowerBounds());
        }
        return false;
    }

    private static boolean nonNull(AnnotatedElement element) {
        for (Annotation a : element.getAnnotations()) {
            if (a.annotationType().getSimpleName().equals("NonNull")) {
                return true;
            }
        }
        return false;
    }

    private static boolean has(Annotation[] annotations, String type) {
        for (Annotation a : annotations) {
            if (a.annotationType().getName().equals(type)) {
                return true;
            }
        }
        return false;
    }
}
