package com.snoworca.naruserve.bootstrap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.ConsoleLogger;
import com.snoworca.naruserve.RestApp;
import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** The platform default logger. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-5-7.sds.md. */
public class DefaultLoggerTest {

    private static final String FAKE_LOG = "package android.util;\n"
            + "public final class Log {\n"
            + "  public static int v(String t, String m) { return 0; }\n"
            + "  public static int d(String t, String m) { return 0; }\n"
            + "  public static int i(String t, String m) { return 0; }\n"
            + "  public static int w(String t, String m) { return 0; }\n"
            + "  public static int e(String t, String m) { return 0; }\n"
            + "  public static int e(String t, String m, Throwable x) { return 0; }\n"
            + "}\n";

    @Rule
    public final TemporaryFolder temp = new TemporaryFolder();

    // SDS-AC-6; FR-ENGINE-009 AC-3
    @Test
    public void sdsAc6_FR_ENGINE_009_AC3_withoutAndroidLogTheAppLoggerIsConsole() throws Exception {
        try {
            Class.forName("android.util.Log", false, DefaultAppFactory.class.getClassLoader());
            fail("android.util.Log is on the JVM test class path");
        } catch (ClassNotFoundException expected) {
            // the JVM case
        }
        assertSame(ConsoleLogger.class, RestApp.create().logger().getClass());
        assertSame(ConsoleLogger.class, DefaultAppFactory.defaultLogger(DefaultAppFactory.class.getClassLoader())
                .getClass());
    }

    // SDS-AC-6; FR-ENGINE-009 AC-3
    @Test
    public void sdsAc6_FR_ENGINE_009_AC3_throughALoaderWithAFakeAndroidLogTheAppLoggerIsLogcat() throws Exception {
        File src = temp.newFolder("src");
        File out = temp.newFolder("classes");
        File logSource = new File(src, "Log.java");
        Files.write(logSource.toPath(), FAKE_LOG.getBytes(StandardCharsets.UTF_8));
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        assertEquals(0, javac.run(null, null, null, "-d", out.getPath(), logSource.getPath()));

        // The test class path minus this module's test classes, plus the fake Log; parent null keeps the
        // application loader (and its classes) out.
        File testClasses = new File(DefaultLoggerTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<URL> urls = new ArrayList<>();
        urls.add(out.toURI().toURL());
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            File f = new File(entry);
            if (!f.getAbsoluteFile().equals(testClasses.getAbsoluteFile())) {
                urls.add(f.toURI().toURL());
            }
        }
        try (URLClassLoader dedicated = new URLClassLoader(urls.toArray(new URL[0]), null)) {
            Class<?> factory = dedicated.loadClass(DefaultAppFactory.class.getName());
            assertTrue(factory != DefaultAppFactory.class);
            Object app = factory.getMethod("create").invoke(factory.getConstructor().newInstance());
            Object logger = dedicated.loadClass(RestApp.class.getName()).getMethod("logger").invoke(app);
            assertEquals("com.snoworca.naruserve.logcat.LogcatLogger", logger.getClass().getName());
        }
    }
}
