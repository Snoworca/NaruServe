package com.snoworca.naruserve.proxy;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * The proxy's built-in certificate host name matcher (SEC-PROXY-005 AC-10): {@code dNSName} subject alternative
 * names compared case-insensitively, {@code *} only as the whole leftmost label matching exactly one non-empty label,
 * IP literal targets only against {@code iPAddress} entries, and no subject common name fallback. Self-signed
 * certificates are made with the JDK's {@code keytool} once per class, as {@link ProxyTlsTest} does; the wildcard
 * forms keytool refuses ({@code a*.example.test}, {@code a.*.test}) are made by patching the name bytes of a
 * same-length keytool certificate, which leaves the signature invalid but the certificate parseable, and the matcher
 * does not check signatures.
 */
public class CertificateHostnamesTest {

    private static File dir;
    private static X509Certificate lower;
    private static X509Certificate upper;
    private static X509Certificate wildcard;
    private static X509Certificate partialWildcard;
    private static X509Certificate innerWildcard;
    private static X509Certificate tldWildcard;
    private static X509Certificate ipv4;
    private static X509Certificate ipv6;
    private static X509Certificate dnsSpelledIp;
    private static X509Certificate commonNameOnly;
    private static X509Certificate commonNameAndOtherSan;

    @BeforeClass
    public static void makeCertificates() throws Exception {
        dir = java.nio.file.Files.createTempDirectory("naruserve-proxy-hostnames").toFile();
        lower = certificate("lower", "CN=nothing.example", "SAN=dns:localhost");
        upper = certificate("upper", "CN=nothing.example", "SAN=dns:LOCALHOST");
        wildcard = certificate("wildcard", "CN=nothing.example", "SAN=dns:*.example.test");
        partialWildcard = patch(certificate("partial", "CN=nothing.example", "SAN=dns:xq.example.test"),
                "xq.example.test", "a*.example.test");
        innerWildcard = patch(certificate("inner", "CN=nothing.example", "SAN=dns:a.q.test"), "a.q.test",
                "a.*.test");
        tldWildcard = certificate("tld", "CN=nothing.example", "SAN=dns:*.test");
        ipv4 = certificate("ipv4", "CN=nothing.example", "SAN=ip:127.0.0.1");
        ipv6 = certificate("ipv6", "CN=nothing.example", "SAN=ip:::1");
        dnsSpelledIp = certificate("dnsip", "CN=nothing.example", "SAN=dns:127.0.0.1");
        commonNameOnly = certificate("cn", "CN=localhost", null);
        commonNameAndOtherSan = certificate("cnsan", "CN=localhost", "SAN=dns:other.example");
    }

    @AfterClass
    public static void deleteCertificates() {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                assertTrue(f.delete());
            }
        }
        assertTrue(dir.delete());
    }

    // ---- AC-10: accepted ----

    // SEC-PROXY-005 AC-10: localhost matches a dNSName localhost, and the comparison ignores ASCII case
    @Test
    public void sec_PROXY_005_AC10_localhostMatchesItsDnsNameInAnyCase() {
        assertTrue(CertificateHostnames.matches("localhost", lower));
        assertTrue(CertificateHostnames.matches("LocalHost", lower));
        assertTrue(CertificateHostnames.matches("localhost", upper));
        assertTrue(CertificateHostnames.matches("localhost.", lower));
        assertFalse(CertificateHostnames.matches("other.example", lower));
        assertFalse(CertificateHostnames.matches("localhostx", lower));
    }

    // SEC-PROXY-005 AC-10: *.example.test matches exactly one non-empty leftmost label
    @Test
    public void sec_PROXY_005_AC10_aLeftmostWildcardMatchesOneLabel() {
        assertTrue(CertificateHostnames.matches("a.example.test", wildcard));
        assertTrue(CertificateHostnames.matches("Backend-1.EXAMPLE.test", wildcard));
        assertFalse(CertificateHostnames.matches("a.b.example.test", wildcard));
        assertFalse(CertificateHostnames.matches("example.test", wildcard));
        assertFalse(CertificateHostnames.matches(".example.test", wildcard));
        assertFalse(CertificateHostnames.matches("a.example.test.other", wildcard));
    }

    // SEC-PROXY-005 AC-10: 127.0.0.1 matches an iPAddress 127.0.0.1, and ::1 an iPAddress ::1 in any spelling
    @Test
    public void sec_PROXY_005_AC10_anIpLiteralMatchesItsIpAddressEntry() {
        assertTrue(CertificateHostnames.matches("127.0.0.1", ipv4));
        assertFalse(CertificateHostnames.matches("127.0.0.2", ipv4));
        assertFalse(CertificateHostnames.matches("localhost", ipv4));
        assertTrue(CertificateHostnames.matches("::1", ipv6));
        assertTrue(CertificateHostnames.matches("0:0:0:0:0:0:0:1", ipv6));
        assertFalse(CertificateHostnames.matches("::2", ipv6));
        assertFalse(CertificateHostnames.matches("127.0.0.1", ipv6));
    }

    // ---- AC-10: rejected ----

    // SEC-PROXY-005 AC-10: a wildcard that is not the whole leftmost label matches nothing
    @Test
    public void sec_PROXY_005_AC10_aWildcardOutsideTheWholeLeftmostLabelMatchesNothing() {
        assertFalse(CertificateHostnames.matches("a.example.test", partialWildcard));
        assertFalse(CertificateHostnames.matches("ab.example.test", partialWildcard));
        assertFalse(CertificateHostnames.matches("a*.example.test", partialWildcard));
        assertFalse(CertificateHostnames.matches("a.b.test", innerWildcard));
        assertFalse(CertificateHostnames.matches("a.*.test", innerWildcard));
        assertFalse(CertificateHostnames.matches("a.example.test", tldWildcard));
    }

    // SEC-PROXY-005 AC-10: an IP literal target is compared only with iPAddress entries, never with a dNSName that
    // spells the address
    @Test
    public void sec_PROXY_005_AC10_anIpLiteralDoesNotMatchADnsNameSpellingIt() {
        assertFalse(CertificateHostnames.matches("127.0.0.1", dnsSpelledIp));
    }

    // SEC-PROXY-005 AC-10: the subject common name is never a fallback, with or without other SAN entries
    @Test
    public void sec_PROXY_005_AC10_theSubjectCommonNameIsNotAFallback() {
        assertFalse(CertificateHostnames.matches("localhost", commonNameOnly));
        assertFalse(CertificateHostnames.matches("localhost", commonNameAndOtherSan));
        assertTrue(CertificateHostnames.matches("other.example", commonNameAndOtherSan));
    }

    // SEC-PROXY-005 AC-10: malformed or empty host names match nothing
    @Test
    public void sec_PROXY_005_AC10_malformedHostsMatchNothing() {
        assertFalse(CertificateHostnames.matches("", lower));
        assertFalse(CertificateHostnames.matches(".", lower));
        assertFalse(CertificateHostnames.matches("*.example.test", wildcard));
        assertFalse(CertificateHostnames.matches("a..example.test", wildcard));
    }

    // ---- AC-10: the HostnameVerifier ----

    // SEC-PROXY-005 AC-10: as a HostnameVerifier the matcher checks the session's peer leaf certificate, and fails
    // closed when the session has no verified peer
    @Test
    public void sec_PROXY_005_AC10_theVerifierChecksTheSessionsLeafCertificate() {
        CertificateHostnames v = new CertificateHostnames();
        assertTrue(v.verify("localhost", session(lower, ipv4)));
        assertFalse(v.verify("127.0.0.1", session(lower, ipv4)));
        assertFalse(v.verify("localhost", session()));
        assertFalse(v.verify("localhost", session((X509Certificate[]) null)));
        assertFalse(v.verify("localhost", null));
    }

    // ---- certificates ----

    /** A self-signed certificate with {@code dname} and, unless {@code null}, the {@code -ext} value {@code san}. */
    private static X509Certificate certificate(String name, String dname, String san) throws Exception {
        List<String> args = new ArrayList<>(Arrays.asList("-genkeypair", "-alias", "c", "-keyalg", "RSA", "-keysize",
                "2048", "-dname", dname, "-validity", "3", "-keystore", name + ".p12"));
        if (san != null) {
            args.add("-ext");
            args.add(san);
        }
        keytool(args);
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(new File(dir, name + ".p12"))) {
            ks.load(in, "changeit".toCharArray());
        }
        return (X509Certificate) ks.getCertificate("c");
    }

    /** {@code cert} re-parsed with the one occurrence of the ASCII bytes {@code from} replaced by {@code to}. */
    private static X509Certificate patch(X509Certificate cert, String from, String to) throws Exception {
        byte[] der = cert.getEncoded();
        byte[] a = from.getBytes(StandardCharsets.US_ASCII);
        byte[] b = to.getBytes(StandardCharsets.US_ASCII);
        int at = -1;
        for (int i = 0; i + a.length <= der.length; i++) {
            if (Arrays.equals(Arrays.copyOfRange(der, i, i + a.length), a)) {
                if (at >= 0) {
                    throw new AssertionError(from + " occurs more than once");
                }
                at = i;
            }
        }
        if (at < 0) {
            throw new AssertionError(from + " not found");
        }
        System.arraycopy(b, 0, der, at, b.length);
        Certificate patched = CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(der));
        return (X509Certificate) patched;
    }

    private static void keytool(List<String> args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(new File(System.getProperty("java.home"), "bin/keytool").getPath());
        command.addAll(args);
        command.addAll(Arrays.asList("-storetype", "PKCS12", "-storepass", "changeit", "-keypass", "changeit"));
        Process p = new ProcessBuilder(command).directory(dir).redirectErrorStream(true).start();
        String out = new String(readAll(p.getInputStream()), StandardCharsets.UTF_8);
        if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new AssertionError("keytool " + args + " failed:\n" + out);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        for (int n; (n = in.read(buf)) != -1; ) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /**
     * An {@link SSLSession} whose {@code getPeerCertificates} answers {@code chain}, or throws
     * {@link SSLPeerUnverifiedException} when {@code chain} is {@code null}; every other method answers a default.
     */
    private static SSLSession session(final X509Certificate... chain) {
        InvocationHandler h = (proxy, method, args) -> {
            if (method.getName().equals("getPeerCertificates")) {
                if (chain == null) {
                    throw new SSLPeerUnverifiedException("no peer");
                }
                return chain.clone();
            }
            Class<?> r = method.getReturnType();
            if (r == boolean.class) {
                return false;
            }
            if (r == int.class) {
                return 0;
            }
            if (r == long.class) {
                return 0L;
            }
            return null;
        };
        return (SSLSession) java.lang.reflect.Proxy.newProxyInstance(SSLSession.class.getClassLoader(),
                new Class<?>[] {SSLSession.class}, h);
    }
}
