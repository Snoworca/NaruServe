package com.snoworca.naruserve.servlet;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.ServletContainerInitializer;

import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.spi.HttpAdapter;
import org.jspecify.annotations.NullMarked;
import org.junit.Test;

/** Reflection over the public types of the module. */
public class ServletSurfaceTest {

    // SDS-AC-1; IR-SVL-001 AC-1
    @Test
    public void sdsAc1_IR_SVL_001_AC1_servletAdapterIsPublicFinalHttpAdapterWithNoArgConstructor() throws Exception {
        Class<ServletAdapter> c = ServletAdapter.class;
        assertTrue(Modifier.isPublic(c.getModifiers()));
        assertTrue(Modifier.isFinal(c.getModifiers()));
        assertTrue(HttpAdapter.class.isAssignableFrom(c));
        assertTrue(Modifier.isPublic(c.getConstructor().getModifiers()));
        assertTrue(c.getConstructor().newInstance() instanceof HttpAdapter);
    }

    // SDS-AC-1; IR-SVL-001 AC-1
    @Test
    public void sdsAc1_IR_SVL_001_AC1_servletConfigurerIsFunctionalWithOneThrowingConfigure() throws Exception {
        Class<ServletConfigurer> c = ServletConfigurer.class;
        assertTrue(c.isInterface());
        assertTrue(Modifier.isPublic(c.getModifiers()));
        assertNotNull(c.getAnnotation(FunctionalInterface.class));
        List<Method> abstracts = new ArrayList<>();
        for (Method m : c.getMethods()) {
            if (Modifier.isAbstract(m.getModifiers())) {
                abstracts.add(m);
            }
        }
        assertEquals(abstracts.toString(), 1, abstracts.size());
        Method configure = abstracts.get(0);
        assertEquals("configure", configure.getName());
        assertEquals(RestApp.class, configure.getReturnType());
        assertArrayEquals(new Class<?>[] {RestApp.Builder.class}, configure.getParameterTypes());
        assertArrayEquals(new Class<?>[] {Exception.class}, configure.getExceptionTypes());
    }

    // SDS-AC-1; IR-SVL-001 AC-1
    @Test
    public void sdsAc1_IR_SVL_001_AC1_initializerIsPublicContainerInitializerAndPackageIsNullMarked() throws Exception {
        Class<NaruServeInitializer> c = NaruServeInitializer.class;
        assertTrue(Modifier.isPublic(c.getModifiers()));
        assertFalse(c.isInterface());
        assertTrue(ServletContainerInitializer.class.isAssignableFrom(c));
        assertTrue(Modifier.isPublic(c.getConstructor().getModifiers()));
        Package p = Class.forName("com.snoworca.naruserve.servlet.package-info").getPackage();
        assertNotNull(p.getAnnotation(NullMarked.class));
    }
}
