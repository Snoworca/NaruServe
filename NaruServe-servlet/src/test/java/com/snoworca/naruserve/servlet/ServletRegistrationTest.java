package com.snoworca.naruserve.servlet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletContext;
import javax.servlet.ServletRegistration;

import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.webapp.WebAppContext;
import org.junit.Rule;
import org.junit.Test;

/** Discovery through the services file, registration read back from Jetty, and the init-parameters. */
public class ServletRegistrationTest {

    @Rule
    public final ContainerRule container = new ContainerRule();

    private static Map<String, String> params(String... keyValues) {
        Map<String, String> m = new HashMap<>(ServletConfigurerTest.configurer(ServletConfigurerTest.Good.class));
        for (int i = 0; i < keyValues.length; i += 2) {
            m.put(keyValues[i], keyValues[i + 1]);
        }
        return m;
    }

    private static List<ServletHolder> naruserveHolders(WebAppContext ctx) {
        List<ServletHolder> found = new ArrayList<>();
        for (ServletHolder h : ctx.getServletHandler().getServlets()) {
            if (h.getName().equals("naruserve")) {
                found.add(h);
            }
        }
        return found;
    }

    /** Registered servlets other than Jetty's own (its ServletHandler adds a default 404 servlet). */
    private static int applicationServlets(WebAppContext ctx) {
        int n = 0;
        for (ServletHolder h : ctx.getServletHandler().getServlets()) {
            if (!String.valueOf(h.getClassName()).startsWith("org.eclipse.jetty.")) {
                n++;
            }
        }
        return n;
    }

    private int status(String path) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + container.port() + path)
                .openConnection();
        try {
            return c.getResponseCode();
        } finally {
            c.disconnect();
        }
    }

    // SDS-AC-3; IR-SVL-001 AC-3
    @Test
    public void sdsAc3_IR_SVL_001_AC3_configurerParameterRegistersOneAsyncServletAtSlashStar() throws Exception {
        WebAppContext ctx = container.webApp(params());
        List<ServletHolder> holders = naruserveHolders(ctx);
        assertEquals(1, holders.size());
        assertEquals("no other servlet is registered", 1, applicationServlets(ctx));
        ServletHolder holder = holders.get(0);
        assertTrue(holder.isAsyncSupported());
        assertNull(((ServletHolder.Registration) holder.getRegistration()).getMultipartConfig());
        ServletRegistration reg = ctx.getServletContext().getServletRegistration("naruserve");
        assertNotNull(reg);
        assertEquals(Arrays.asList("/*"), new ArrayList<>(reg.getMappings()));
        assertEquals(NaruServeServlet.class.getName(), reg.getClassName());
    }

    // SDS-AC-3; IR-SVL-001 AC-3
    @Test
    public void sdsAc3_IR_SVL_001_AC3_withoutConfigurerNothingIsRegisteredAndRequestsGet404() throws Exception {
        WebAppContext ctx = container.webApp(new HashMap<String, String>());
        assertTrue(naruserveHolders(ctx).isEmpty());
        assertEquals("nothing is registered", 0, applicationServlets(ctx));
        assertNull(ctx.getServletContext().getServletRegistration("naruserve"));
        assertEquals(404, status("/"));
        assertEquals(404, status("/api/x"));
    }

    // SDS-AC-4; IR-SVL-001 AC-4
    @Test
    public void sdsAc4_IR_SVL_001_AC4_acceptedUrlPatternsMapTheServlet() throws Exception {
        for (String pattern : new String[] {"/*", "/", "/api/*"}) {
            WebAppContext ctx = container.webApp(params("naruserve.urlPattern", pattern));
            ServletRegistration reg = ctx.getServletContext().getServletRegistration("naruserve");
            assertEquals(pattern, Arrays.asList(pattern), new ArrayList<>(reg.getMappings()));
        }
        assertEquals("/api", InitParams.prefix("/api/*"));
        assertEquals("", InitParams.prefix("/*"));
        assertEquals("", InitParams.prefix("/"));
    }

    // SDS-AC-4; IR-SVL-001 AC-4
    @Test
    public void sdsAc4_IR_SVL_001_AC4_otherUrlPatternsFailStartupNamingParameterAndValue() {
        for (String pattern : new String[] {"api/*", "*.json", ""}) {
            String message = String.valueOf(ServletConfigurerTest.startupFailure(container,
                    params("naruserve.urlPattern", pattern)).getMessage());
            assertTrue(message, message.contains("naruserve.urlPattern"));
            assertTrue(message, message.contains("'" + pattern + "'"));
        }
    }

    // SDS-AC-5; IR-SVL-001 AC-5
    @Test
    public void sdsAc5_IR_SVL_001_AC5_shutdownTimeoutInRangeOrDefaultIsReturned() throws Exception {
        String[][] cases = {{"1", "1"}, {"300", "300"}, {null, "10"}};
        for (String[] c : cases) {
            WebAppContext ctx = c[0] == null ? container.webApp(params())
                    : container.webApp(params("naruserve.shutdownTimeoutSeconds", c[0]));
            ServletContext sc = ctx.getServletContext();
            assertEquals(Integer.parseInt(c[1]), InitParams.shutdownSeconds(sc));
        }
    }

    // SDS-AC-5; IR-SVL-001 AC-5
    @Test
    public void sdsAc5_IR_SVL_001_AC5_otherShutdownTimeoutsFailStartupNamingParameterAndValue() {
        for (String value : new String[] {"0", "301", "ten", ""}) {
            String message = String.valueOf(ServletConfigurerTest.startupFailure(container,
                    params("naruserve.shutdownTimeoutSeconds", value)).getMessage());
            assertTrue(message, message.contains("naruserve.shutdownTimeoutSeconds"));
            assertTrue(message, message.contains("'" + value + "'"));
        }
    }
}
