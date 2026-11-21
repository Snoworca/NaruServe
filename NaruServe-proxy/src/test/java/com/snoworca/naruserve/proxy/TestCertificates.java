package com.snoworca.naruserve.proxy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/**
 * Test certificates for {@code trustCertificates} (FR-PROXY-022), made at test time with the JDK's {@code keytool}
 * like the fixtures of {@link ProxyTlsTest}: no key or certificate is checked in. {@link #selfSigned()} is one
 * self-signed certificate for {@code localhost} and {@code 127.0.0.1}, made once per JVM, with the server context that
 * serves it.
 */
final class TestCertificates {

    static final String PASSWORD = "changeit";

    private static String selfSignedPem;
    private static SSLContext selfSignedServer;

    private TestCertificates() {
    }

    /** Runs the JDK's keytool in {@code dir} with a PKCS12 store and the test password. */
    static void keytool(File dir, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(new File(System.getProperty("java.home"), "bin/keytool").getPath());
        command.addAll(Arrays.asList(args));
        command.addAll(Arrays.asList("-storetype", "PKCS12", "-storepass", PASSWORD, "-keypass", PASSWORD));
        Process p = new ProcessBuilder(command).directory(dir).redirectErrorStream(true).start();
        String out = new String(readAll(p.getInputStream()), StandardCharsets.UTF_8);
        if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new AssertionError("keytool " + Arrays.asList(args) + " failed:\n" + out);
        }
    }

    /**
     * Makes a server key pair {@code name}.p12 in {@code dir} whose certificate the CA {@code ca} ({@code ca}.p12 and
     * {@code ca}.pem in {@code dir}) issued with the subject {@code dname} and the extension {@code san}; the store
     * holds the CA certificate under the alias {@code ca} and the key pair under the alias {@code server}.
     */
    static void issued(File dir, String ca, String name, String dname, String san) throws Exception {
        keytool(dir, "-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048", "-dname", dname,
                "-validity", "3", "-keystore", name + ".p12");
        keytool(dir, "-certreq", "-alias", "server", "-keystore", name + ".p12", "-file", name + ".csr");
        keytool(dir, "-gencert", "-rfc", "-alias", ca, "-keystore", ca + ".p12", "-infile", name + ".csr",
                "-outfile", name + ".pem", "-ext", san, "-validity", "3");
        keytool(dir, "-importcert", "-noprompt", "-alias", ca, "-file", ca + ".pem", "-keystore", name + ".p12");
        keytool(dir, "-importcert", "-noprompt", "-alias", "server", "-file", name + ".pem", "-keystore",
                name + ".p12");
    }

    static KeyStore load(File file) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(file)) {
            ks.load(in, PASSWORD.toCharArray());
        }
        return ks;
    }

    static SSLContext serverContext(File keystore) throws Exception {
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(load(keystore), PASSWORD.toCharArray());
        SSLContext c = SSLContext.getInstance("TLS");
        c.init(kmf.getKeyManagers(), null, null);
        return c;
    }

    static String read(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            return new String(readAll(in), StandardCharsets.US_ASCII);
        }
    }

    /** The one certificate of a PEM text, parsed by the JDK. */
    static X509Certificate parse(String pem) throws Exception {
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)));
    }

    /** The PEM text of a self-signed certificate whose subject alternative names are localhost and 127.0.0.1. */
    static synchronized String selfSignedPem() throws Exception {
        if (selfSignedPem == null) {
            File dir = java.nio.file.Files.createTempDirectory("naruserve-proxy-self").toFile();
            try {
                keytool(dir, "-genkeypair", "-alias", "self", "-keyalg", "RSA", "-keysize", "2048", "-dname",
                        "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-validity", "3", "-keystore",
                        "self.p12");
                keytool(dir, "-exportcert", "-rfc", "-alias", "self", "-keystore", "self.p12", "-file", "self.pem");
                selfSignedServer = serverContext(new File(dir, "self.p12"));
                selfSignedPem = read(new File(dir, "self.pem"));
            } finally {
                delete(dir);
            }
        }
        return selfSignedPem;
    }

    static X509Certificate selfSigned() throws Exception {
        return parse(selfSignedPem());
    }

    /** A server context that serves the certificate of {@link #selfSignedPem()}. */
    static synchronized SSLContext selfSignedServer() throws Exception {
        selfSignedPem();
        return selfSignedServer;
    }

    static void delete(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (!f.delete()) {
                    throw new AssertionError("cannot delete " + f);
                }
            }
        }
        if (!dir.delete()) {
            throw new AssertionError("cannot delete " + dir);
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
}
