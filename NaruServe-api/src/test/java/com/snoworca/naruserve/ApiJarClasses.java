package com.snoworca.naruserve;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Classes of the main jar named by {@code naruserve.apiJar}, never the test class path, which also holds classes of
 * {@code com.snoworca.naruserve} such as {@link NullabilityFixture}.
 */
// @req FR-KT-001
// @req FR-KT-002
final class ApiJarClasses {

    /** The public API packages; {@code internal} is outside the method-level checks. */
    static final List<String> PUBLIC_PACKAGES = Collections.unmodifiableList(Arrays.asList(
            "com.snoworca.naruserve", "com.snoworca.naruserve.spi", "com.snoworca.naruserve.routing"));

    private static final String PREFIX = "com.snoworca.naruserve.";

    private static ClassLoader loader;

    private ApiJarClasses() {
    }

    private static File jar() {
        String path = System.getProperty("naruserve.apiJar");
        if (path == null) {
            throw new IllegalStateException("system property naruserve.apiJar is not set");
        }
        return new File(path);
    }

    /** Jar-only for com.snoworca.naruserve.*; org.jspecify.* and the JDK come from the parent. */
    // @req FR-KT-001
    static synchronized ClassLoader loader() {
        if (loader == null) {
            URL url;
            try {
                url = jar().toURI().toURL();
            } catch (MalformedURLException e) {
                throw new IllegalStateException(e);
            }
            loader = new URLClassLoader(new URL[] {url}, ApiJarClasses.class.getClassLoader()) {
                @Override
                protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                    if (!name.startsWith(PREFIX)) {
                        return super.loadClass(name, resolve);
                    }
                    synchronized (getClassLoadingLock(name)) {
                        Class<?> c = findLoadedClass(name);
                        if (c == null) {
                            // No fallback: a class missing from the jar must not be read from the test class path.
                            c = findClass(name);
                        }
                        if (resolve) {
                            resolveClass(c);
                        }
                        return c;
                    }
                }
            };
        }
        return loader;
    }

    /** Every public class, interface and enum of {@link #PUBLIC_PACKAGES} in the jar, nested types included. */
    // @req FR-KT-001
    // @req FR-KT-002
    static List<Class<?>> publicTypes() {
        List<Class<?>> types = new ArrayList<>();
        try (JarFile jar = new JarFile(jar())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                String entry = entries.nextElement().getName();
                if (!entry.endsWith(".class") || entry.endsWith("package-info.class")) {
                    continue;
                }
                String name = entry.substring(0, entry.length() - ".class".length()).replace('/', '.');
                String pkg = name.substring(0, name.lastIndexOf('.'));
                if (!PUBLIC_PACKAGES.contains(pkg)) {
                    continue;
                }
                Class<?> type = Class.forName(name, false, loader());
                if (Modifier.isPublic(type.getModifiers())) {
                    types.add(type);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
        types.sort((a, b) -> a.getName().compareTo(b.getName()));
        return types;
    }

    /** The package-info class of {@code pkg} in the jar, or null when the package has none. */
    // @req FR-KT-001
    static Class<?> packageInfo(String pkg) {
        return packageInfo(loader(), pkg);
    }

    /** The package-info class of {@code pkg} in {@code from}, or null when the package has none. */
    // @req FR-KT-001
    static Class<?> packageInfo(ClassLoader from, String pkg) {
        try {
            return Class.forName(pkg + ".package-info", false, from);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }
}
