package com.snoworca.naruserve.tinyhttpd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.AppStartException;
import com.snoworca.naruserve.AppState;
import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.ServerHandle;
import com.snoworca.naruserve.testing.NoLeftoverThreads;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.tinyhttpd.RawTargetSocketTest.Http;
import com.snoworca.tinyhttpd.ServerConfig;
import com.snoworca.tinyhttpd.http.Filter;
import com.snoworca.tinyhttpd.http.filter.HostAllowlistFilter;
import java.io.File;
import java.io.IOException;
import java.net.BindException;
import java.net.ConnectException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;

/** Bind address, Host allowlist and bind outcomes. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-5-5.sds.md. */
public class BindingSocketTest {

    // I-015: no naruserve-* thread outlives the test
    @Rule
    public final NoLeftoverThreads noLeftoverThreads = new NoLeftoverThreads();

    private final List<RestApp> apps = new ArrayList<>();

    @After
    public void shutdown() {
        for (RestApp a : apps) {
            a.shutdown(2, TimeUnit.SECONDS);
        }
    }

    private RestApp app(TinyHttpdAdapter adapter) {
        RestApp a = Http.app(adapter, new RecordingLogger());
        a.get("/h", (req, res) -> res.send("hi"));
        apps.add(a);
        return a;
    }

    private static HostAllowlistFilter allowlist(ServerConfig config) {
        for (Filter f : config.filters) {
            if (f instanceof HostAllowlistFilter) {
                return (HostAllowlistFilter) f;
            }
        }
        throw new AssertionError("no HostAllowlistFilter in " + config.filters);
    }

    private static String hostGet(String host) {
        return "GET /h HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n\r\n";
    }

    // SDS-AC-5; FR-ENGINE-002 AC-1
    @Test(timeout = 20000)
    public void sdsAc5_FR_ENGINE_002_AC1_defaultBindIsTheExplicitIpv4Loopback() throws Exception {
        ServerConfig built = TinyHttpdAdapter.configure(Http.settings().build(), (q, r) -> { }).build().getConfig();
        assertEquals(InetAddress.getByName("127.0.0.1"), built.bindAddress);

        TinyHttpdAdapter adapter = new TinyHttpdAdapter();
        ServerHandle handle = app(adapter).listen(0);
        assertEquals("127.0.0.1", handle.address());
        assertEquals(InetAddress.getByName("127.0.0.1"), adapter.config().listeners.get(0).bindAddress);
        assertEquals(200, Http.status(Http.send(handle.port(), Http.get("/h"))));

        for (File f : AdapterSurfaceTest.mainClassFiles()) {
            assertFalse(f.getName(), AdapterSurfaceTest.classText(f).contains("getLoopbackAddress"));
        }
    }

    // SDS-AC-5; FR-ENGINE-002 AC-2 (T49)
    @Test(timeout = 30000)
    public void sdsAc5_FR_ENGINE_002_AC2_lanAddressIsRefusedByDefaultAndServedAfterAWildcardBind() throws Exception {
        InetAddress lan = null;
        for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!nif.isUp() || nif.isLoopback() || nif.isPointToPoint() || nif.isVirtual()) {
                continue;
            }
            for (InetAddress a : Collections.list(nif.getInetAddresses())) {
                if (a instanceof Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress()) {
                    lan = a;
                }
            }
        }
        Assume.assumeTrue("no non-loopback IPv4 address on this host", lan != null);
        String lanHost = lan.getHostAddress();

        int loopbackPort = app(new TinyHttpdAdapter()).listen(0).port();
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(lan, loopbackPort), 3000);
            fail("the default app must not accept on " + lanHost);
        } catch (ConnectException | SocketTimeoutException expected) {
            // not accepted: only 127.0.0.1 is bound
        }

        RestApp wide = app(new TinyHttpdAdapter());
        wide.server(o -> o.bindAddress("0.0.0.0"));
        ServerHandle handle = wide.listen(0);
        assertEquals("0.0.0.0", handle.address());
        String response = Http.send(lanHost, handle.port(), hostGet(lanHost + ":" + handle.port()));
        assertEquals(200, Http.status(response));
    }

    // SDS-AC-6; FR-ENGINE-002 AC-4
    @Test(timeout = 20000)
    public void sdsAc6_FR_ENGINE_002_AC4_hostAllowlistAcceptsBindAddressAndAllowedNamesOnly() throws Exception {
        RestApp a = app(new TinyHttpdAdapter());
        a.server(o -> o.allowedHosts("myphone.local"));
        int port = a.listen(0).port();

        assertEquals(200, Http.status(Http.send(port, hostGet("127.0.0.1:" + port))));
        assertEquals(200, Http.status(Http.send(port, hostGet("localhost:" + port))));
        assertEquals(200, Http.status(Http.send(port, hostGet("myphone.local"))));
        assertEquals(421, Http.status(Http.send(port, hostGet("evil.example"))));
    }

    // SDS-AC-6; FR-ENGINE-002 AC-4
    @Test
    public void sdsAc6_FR_ENGINE_002_AC4_aHostNameBindAddressJoinsTheConfiguredNames() throws Exception {
        String name;
        try {
            name = InetAddress.getLocalHost().getHostName();
            InetAddress.getByName(name);
        } catch (UnknownHostException e) {
            name = null;
        }
        Assume.assumeTrue("the local host name does not resolve", name != null && !name.equalsIgnoreCase("localhost")
                && !name.matches("[0-9.]+|.*:.*"));
        ServerConfig built = TinyHttpdAdapter.configure(
                Http.settings().bindAddress(name).allowedHosts("myphone.local").build(), (q, r) -> { })
                .build().getConfig();
        HostAllowlistFilter filter = allowlist(built);
        assertTrue(name, filter.isAllowed(name));
        assertTrue(filter.isAllowed("myphone.local"));
        assertFalse(filter.isAllowed("evil.example"));

        HostAllowlistFilter literal = allowlist(TinyHttpdAdapter.configure(
                Http.settings().allowedHosts("myphone.local").build(), (q, r) -> { }).build().getConfig());
        assertTrue(literal.isAllowed("myphone.local"));
        assertFalse(literal.isAllowed("evil.example"));
    }

    // SDS-AC-7; FR-ENGINE-002 AC-6
    @Test(timeout = 30000)
    public void sdsAc7_FR_ENGINE_002_AC6_aTakenPortFailsTheSecondAppAndLeavesTheFirstServing() throws Exception {
        int port = app(new TinyHttpdAdapter()).listen(0).port();
        int acceptors = Http.threads("tinyhttpd-acceptor-" + port).size();
        int watchdogs = Http.threads("tinyhttpd-watchdog").size();
        assertEquals(1, acceptors);

        TinyHttpdAdapter secondAdapter = new TinyHttpdAdapter();
        RestApp second = app(secondAdapter);
        try {
            second.listen(port);
            fail("the second listen must fail");
        } catch (AppStartException e) {
            assertTrue(String.valueOf(e.getCause()), e.getCause() instanceof BindException);
        }
        assertEquals(AppState.TERMINATED, second.state());
        assertEquals(acceptors, Http.threads("tinyhttpd-acceptor-" + port).size());
        assertEquals(watchdogs, Http.threads("tinyhttpd-watchdog").size());
        try {
            secondAdapter.boundPort();
            fail("a failed bind leaves no bound port");
        } catch (IllegalStateException expected) {
            // nothing is bound
        }
        assertEquals(200, Http.status(Http.send(port, Http.get("/h"))));
    }

    // SDS-AC-7; FR-ENGINE-002 AC-5
    @Test(timeout = 20000)
    public void sdsAc7_FR_ENGINE_002_AC5_listenZeroReportsTheEphemeralPort() throws IOException {
        TinyHttpdAdapter adapter = new TinyHttpdAdapter();
        ServerHandle handle = app(adapter).listen(0);
        assertTrue(handle.port() > 0);
        assertEquals(adapter.boundPort(), handle.port());
        assertEquals(200, Http.status(Http.send(handle.port(), Http.get("/h"))));
    }
}
