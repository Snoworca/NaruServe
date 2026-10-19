package com.snoworca.naruserve.internal;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.spi.AppFactory;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class AppFactoryLoaderTest {

    private static final String SERVICES = "META-INF/services/" + AppFactory.class.getName();

    /** Fake provider; its app is a constant so the test can check identity. */
    public static final class OneFactory implements AppFactory {
        static final RestApp APP = new DefaultRestApp();

        @Override
        public RestApp create() {
            return APP;
        }
    }

    public static final class OtherFactory implements AppFactory {
        @Override
        public RestApp create() {
            return new DefaultRestApp();
        }
    }

    @Rule
    public final TemporaryFolder temp = new TemporaryFolder();

    /** A loader that sees a services file listing {@code providers} only in a fresh temp dir. */
    private ClassLoader loaderWith(Class<?>... providers) throws IOException {
        File root = temp.newFolder();
        File services = new File(root, SERVICES);
        assertTrue(services.getParentFile().mkdirs());
        StringBuilder sb = new StringBuilder();
        for (Class<?> p : providers) {
            sb.append(p.getName()).append('\n');
        }
        try (OutputStream out = new java.io.FileOutputStream(services)) {
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
        return new URLClassLoader(new URL[] {root.toURI().toURL()}, AppFactoryLoaderTest.class.getClassLoader());
    }

    // SDS-AC-12; FR-APP-001 AC-2
    @Test
    public void sdsAc12_FR_APP_001_AC2_oneProviderReturnsWhatItsCreateReturned() throws Exception {
        assertSame(OneFactory.APP, AppFactoryLoader.create(loaderWith(OneFactory.class)));
    }

    // SDS-AC-13; FR-APP-001 AC-3
    @Test
    public void sdsAc13_FR_APP_001_AC3_noProviderNamesTheModuleAndTheBuilder() throws Exception {
        try {
            AppFactoryLoader.create(loaderWith());
            fail("no provider");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("com.snoworca.naruserve:NaruServe"));
            assertTrue(e.getMessage(), e.getMessage().contains("RestApp.builder()"));
        }
    }

    // SDS-AC-13; FR-APP-001 AC-3
    @Test
    public void sdsAc13_FR_APP_001_AC3_restAppCreateThrowsInTheApiTestRun() {
        try {
            RestApp.create();
            fail("no provider on the NaruServe-api test class path");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("com.snoworca.naruserve:NaruServe"));
            assertTrue(e.getMessage(), e.getMessage().contains("RestApp.builder()"));
        }
    }

    // SDS-AC-13; FR-APP-001 AC-2
    @Test
    public void sdsAc13_FR_APP_001_AC2_restAppCreateIgnoresTheContextClassLoader() throws Exception {
        Thread t = Thread.currentThread();
        ClassLoader saved = t.getContextClassLoader();
        t.setContextClassLoader(loaderWith(OneFactory.class));
        try {
            RestApp.create();
            fail("the context class loader must not be consulted");
        } catch (IllegalStateException expected) {
        } finally {
            t.setContextClassLoader(saved);
        }
    }

    // SDS-AC-14; FR-APP-001 AC-4
    @Test
    public void sdsAc14_FR_APP_001_AC4_twoProvidersAreBothNamed() throws Exception {
        try {
            AppFactoryLoader.create(loaderWith(OneFactory.class, OtherFactory.class));
            fail("two providers");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(OneFactory.class.getName()));
            assertTrue(e.getMessage(), e.getMessage().contains(OtherFactory.class.getName()));
        }
    }

    // SDS-AC-15; FR-APP-001 AC-5
    @Test
    public void sdsAc15_FR_APP_001_AC5_servicesFileExistsOnlyInTheTempDir() throws Exception {
        assertNull(AppFactoryLoaderTest.class.getClassLoader().getResource(SERVICES));
        assertFalse(AppFactory.class.getClassLoader().getResources(SERVICES).hasMoreElements());
        ClassLoader dedicated = loaderWith(OneFactory.class);
        URL found = dedicated.getResource(SERVICES);
        assertTrue(String.valueOf(found), found != null
                && found.toString().startsWith(temp.getRoot().toURI().toURL().toString()));
        // Fake providers are test classes, not testFixtures: the testFixtures loader path holds no AppFactory.
        assertTrue(OneFactory.class.getProtectionDomain().getCodeSource().getLocation().toString()
                .replace('\\', '/').endsWith("/java/test/"));
    }
}
