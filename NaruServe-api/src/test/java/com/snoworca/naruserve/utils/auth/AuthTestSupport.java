package com.snoworca.naruserve.utils.auth;

import com.snoworca.naruserve.Request;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.RecordingLogger;
import com.snoworca.naruserve.testing.TestApp;
import com.snoworca.naruserve.utils.auth.internal.Credential;
import com.snoworca.naruserve.utils.auth.internal.CredentialResolver;
import com.snoworca.naruserve.utils.auth.internal.TokenVerifier;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared set-up for the facade tests. Until the token verifiers exist, a test credential stands in for a verified
 * token: header {@code X-Test-Credential: <userId>:<ver>} or, for an API key actor,
 * {@code <userId>:<ver>:<keyId>:<scope>,<scope>}. It plugs into the package-private credential seam of the builder
 * and is not part of the public API.
 */
final class AuthTestSupport {

    static final String SECRET = "k9Qv2Lm7Xr4Tz8Wb1Nc6Ys3Hd5Gf0Ja-Pq";
    /** The file key of PROD tests over the shipped file stores, which PROD requires (FR-IAM-015 AC-3). */
    static final AuthFileKey FILE_KEY = AuthFileKey.of("q6Cz0bT4mW9xN2rE7kJ5vH8sL1pF3dG6aY0uQ4iO2wM=");
    static final String HEADER = "X-Test-Credential";

    private AuthTestSupport() {
    }

    /** Resolves the test header; counts calls. */
    static final class TestCredentials implements CredentialResolver {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public Credential resolve(Request req) {
            calls.incrementAndGet();
            String v = req.header(HEADER);
            if (v == null) {
                return null;
            }
            String[] p = v.split(":", 4);
            if (p.length == 4) {
                Set<String> scopes = new LinkedHashSet<>(Arrays.asList(p[3].split(",")));
                return new Credential(p[0], Long.parseLong(p[1]), p[2], scopes);
            }
            return new Credential(p[0], Long.parseLong(p[1]));
        }
    }

    /**
     * Stands in for the JWT verifier behind the real transport reading (SEC-FLOW-004): {@code t:<userId>:<ver>} is a
     * valid access token, anything else (an "expired" or forged token) is 401 invalid_token, as the JWT verifier
     * answers. Counts calls.
     */
    static final class TestTokens implements TokenVerifier {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public Credential verify(String token) {
            calls.incrementAndGet();
            String[] p = token.split(":", 3);
            if (p.length != 3 || !p[0].equals("t")) {
                throw new AuthRejection(401, "invalid_token", "the token does not verify");
            }
            return new Credential(p[1], Long.parseLong(p[2]));
        }
    }

    /** The test access token of user {@code id} at token version 0. */
    static String token(String id) {
        return "t:" + id + ":0";
    }

    /** A PROD builder that reads credentials as the transport says, over {@link TestTokens}. */
    static Auth.Builder transportBuilder(TestTokens tokens, RecordingLogger logger) {
        return Auth.builder(SECRET).tokenVerifier(tokens).hashIterations(1000).logger(logger);
    }

    /** Decodes a JSON object to an empty map and refuses anything else; encodes maps as {"k":"v"}. */
    static final class TestJsonCodec implements com.snoworca.naruserve.spi.JsonCodec {
        @Override
        @SuppressWarnings("unchecked")
        public <T> T decode(java.io.Reader reader, java.lang.reflect.Type type) throws java.io.IOException {
            StringBuilder b = new StringBuilder();
            for (int c; (c = reader.read()) >= 0; ) {
                b.append((char) c);
            }
            String text = b.toString().trim();
            if (!text.startsWith("{") || !text.endsWith("}")) {
                throw new java.io.IOException("not a JSON object");
            }
            return (T) new java.util.LinkedHashMap<String, Object>();
        }

        @Override
        public void encode(@org.jspecify.annotations.Nullable Object value, java.io.Writer writer)
                throws java.io.IOException {
            writer.write(value instanceof java.util.Map ? "{}" : String.valueOf(value));
        }
    }

    /** A PROD builder over the test credentials with cheap hashing and a recording logger. */
    static Auth.Builder builder(TestCredentials creds, RecordingLogger logger) {
        return Auth.builder(SECRET).credentials(creds).hashIterations(1000).logger(logger);
    }

    static Auth.Builder builder() {
        return builder(new TestCredentials(), new RecordingLogger());
    }

    /** A local (127.0.0.1, plain) test app. */
    static TestApp localApp() {
        return TestApp.create(new InMemoryHttpAdapter());
    }

    /** A non-local plain-text test app. */
    static TestApp remoteApp() {
        return TestApp.create(new InMemoryHttpAdapter("203.0.113.9", false));
    }

    static ClientResponse send(TestApp t, String method, String target, String... headers) {
        return t.send(method, target, headers);
    }

    static ClientResponse post(InMemoryHttpAdapter adapter, String target, String json, String... headers) {
        ClientRequest r = ClientRequest.json("POST", target, json);
        for (int i = 0; i < headers.length; i += 2) {
            r.header(headers[i], headers[i + 1]);
        }
        return adapter.send(r);
    }

    /** The error code of an auth error body. */
    static String code(ClientResponse r) {
        String body = r.text();
        int i = body.indexOf("\"code\":\"");
        if (i < 0) {
            return "<no code in " + body + ">";
        }
        int s = i + 8;
        return body.substring(s, body.indexOf('"', s));
    }
}
