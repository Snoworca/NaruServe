package com.snoworca.naruserve;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Callback interfaces of the public API: an interface of {@link ApiJarClasses#PUBLIC_PACKAGES} that is the parameter
 * type of a public method of a type in those packages. The real types come from {@link ApiJarClasses#publicTypes()}.
 */
// @req FR-KT-003
final class CallbackInventory {

    private CallbackInventory() {
    }

    /** Callbacks with exactly one abstract method after dropping Object methods and default methods, sorted by name. */
    // @req FR-KT-003
    static List<Class<?>> inventory(Collection<Class<?>> types) {
        List<Class<?>> result = new ArrayList<>();
        for (Class<?> c : callbacks(types).values()) {
            if (abstractCount(c) == 1) {
                result.add(c);
            }
        }
        return result;
    }

    /**
     * {@code X lacks @FunctionalInterface} per inventory member without it and {@code X is not a SAM} per
     * {@code declared} name whose abstract count is not 1; sorted, empty means pass.
     */
    // @req FR-KT-003
    static List<String> failures(Collection<Class<?>> types, Collection<String> declared) {
        Set<String> failures = new TreeSet<>();
        for (Class<?> c : inventory(types)) {
            if (!c.isAnnotationPresent(FunctionalInterface.class)) {
                failures.add(c.getName() + " lacks @FunctionalInterface");
            }
        }
        Map<String, Class<?>> known = new LinkedHashMap<>();
        for (Class<?> c : types) {
            known.put(c.getName(), c);
        }
        known.putAll(callbacks(types));
        for (String name : declared) {
            Class<?> c = known.get(name);
            if (c == null) {
                failures.add(name + " is not a type of the scanned API");
            } else if (!c.isInterface() || abstractCount(c) != 1) {
                failures.add(name + " is not a SAM");
            }
        }
        return new ArrayList<>(failures);
    }

    /** Every callback interface by binary name, whatever its abstract count. */
    private static Map<String, Class<?>> callbacks(Collection<Class<?>> types) {
        Map<String, Class<?>> callbacks = new TreeMap<>();
        for (Class<?> type : types) {
            if (!inPublicPackage(type)) {
                continue;
            }
            List<Executable> members = new ArrayList<>(Arrays.asList(type.getDeclaredMethods()));
            members.addAll(Arrays.asList(type.getDeclaredConstructors()));
            for (Executable m : members) {
                if (!Modifier.isPublic(m.getModifiers()) || m.isSynthetic()) {
                    continue;
                }
                for (Class<?> p : m.getParameterTypes()) {
                    if (p.isInterface() && !p.isAnnotation() && inPublicPackage(p)) {
                        callbacks.put(p.getName(), p);
                    }
                }
            }
        }
        return callbacks;
    }

    private static boolean inPublicPackage(Class<?> c) {
        String name = c.getName();
        return ApiJarClasses.PUBLIC_PACKAGES.contains(name.substring(0, name.lastIndexOf('.')));
    }

    private static int abstractCount(Class<?> c) {
        int count = 0;
        for (Method m : c.getMethods()) {
            if (Modifier.isAbstract(m.getModifiers()) && !m.isDefault() && !isObjectMethod(m)) {
                count++;
            }
        }
        return count;
    }

    private static boolean isObjectMethod(Method m) {
        try {
            Object.class.getMethod(m.getName(), m.getParameterTypes());
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }
}
