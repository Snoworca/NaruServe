package com.snoworca.naruserve.servlet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.spi.JsonCodec;
import com.snoworca.naruserve.testing.StubJsonCodec;
import java.lang.reflect.Field;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.jspecify.annotations.Nullable;
import org.junit.Test;

/** GsonJsonCodec is found by reflection on the web application's class loader and preset before configure. */
public class ServletCodecTest {

    static final String GSON = "com.snoworca.naruserve.gson.GsonJsonCodec";

    /** Builds without touching the codec. */
    public static final class Plain implements ServletConfigurer {
        @Override
        public RestApp configure(RestApp.Builder builder) {
            return builder.build();
        }
    }

    /** Replaces the preset codec. */
    public static final class Replacing implements ServletConfigurer {
        static final StubJsonCodec CODEC = new StubJsonCodec();

        @Override
        public RestApp configure(RestApp.Builder builder) {
            return builder.jsonCodec(CODEC).build();
        }
    }

    /** A loader that cannot see the NaruServe-gson package, as in a web application without that jar. */
    static final class WithoutGson extends ClassLoader {
        WithoutGson() {
            super(ServletCodecTest.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("com.snoworca.naruserve.gson.")) {
                throw new ClassNotFoundException(name);
            }
            return super.loadClass(name, resolve);
        }
    }

    private static ServletContext context(@Nullable ClassLoader loader) {
        ServletContextHandler handler = new ServletContextHandler();
        handler.setClassLoader(loader != null ? loader : ServletCodecTest.class.getClassLoader());
        return handler.getServletContext();
    }

    private static @Nullable Object builderCodec(RestApp.Builder b) throws Exception {
        Field f = RestApp.Builder.class.getDeclaredField("codec");
        f.setAccessible(true);
        return f.get(b);
    }

    private static @Nullable JsonCodec appCodec(RestApp app) throws Exception {
        return (JsonCodec) app.getClass().getMethod("jsonCodec").invoke(app);
    }

    // SDS-AC-5; FR-SVL-001 AC-5
    @Test
    public void sdsAc5_FR_SVL_001_AC5_gsonOnTheClassPathIsPreset() throws Exception {
        RestApp.Builder b = RestApp.builder();
        assertTrue(AppStarter.presetCodec(context(null), b));
        Object codec = builderCodec(b);
        assertEquals(GSON, codec == null ? null : codec.getClass().getName());
        RestApp app = AppStarter.create(context(null), Plain.class.getName());
        JsonCodec used = appCodec(app);
        assertEquals(GSON, used == null ? null : used.getClass().getName());
    }

    // SDS-AC-5; FR-SVL-001 AC-5
    @Test
    public void sdsAc5_FR_SVL_001_AC5_configurerMayReplaceThePresetCodec() throws Exception {
        RestApp app = AppStarter.create(context(null), Replacing.class.getName());
        assertSame(Replacing.CODEC, appCodec(app));
    }

    // SDS-AC-5; FR-SVL-001 AC-5
    @Test
    public void sdsAc5_FR_SVL_001_AC5_noCodecFailsStartupNamingGsonAndJsonCodec() throws Exception {
        ServletContext ctx = context(new WithoutGson());
        assertFalse(AppStarter.presetCodec(ctx, RestApp.builder()));
        try {
            AppStarter.create(ctx, Plain.class.getName());
            fail("startup without a codec");
        } catch (ServletException e) {
            String message = String.valueOf(e.getMessage());
            assertTrue(message, message.contains(GSON));
            assertTrue(message, message.contains("jsonCodec"));
        }
    }
}
