package com.snoworca.naruserve.utils.auth;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * Operator values from a {@link Properties} file, applied with {@code Auth.builder(...).config(AuthConfig.load(f))}.
 * Like {@code dotenv} in Express, it carries values only: stores, sinks and other objects are set in code, and no
 * key names a class. A secret is never written in the file itself: {@code naru.auth.secret} is a reference,
 * {@code env:NAME} (the environment variable) or {@code file:/path} (the file's content, surrounding whitespace
 * removed). An unknown key, a bad value or an unresolved reference is reported by {@code build()} together with the
 * builder's own violations. Keys:
 *
 * <table>
 *   <caption>AuthConfig keys</caption>
 *   <tr><td>{@code naru.auth.secret}</td><td>{@code env:NAME} or {@code file:/path}</td></tr>
 *   <tr><td>{@code naru.auth.mode}</td><td>{@code dev} or {@code prod}</td></tr>
 *   <tr><td>{@code naru.auth.profile}</td><td>{@code baseline}, {@code standard} or {@code strict}</td></tr>
 *   <tr><td>{@code naru.auth.deployment}</td><td>{@code single-instance} or {@code multi-instance}</td></tr>
 *   <tr><td>{@code naru.auth.transport}</td><td>{@code header}, {@code cookie} or {@code both}</td></tr>
 *   <tr><td>{@code naru.auth.mount-path}</td><td>a path such as {@code /auth}</td></tr>
 *   <tr><td>{@code naru.auth.permit}</td><td>comma-separated exact GET/HEAD paths</td></tr>
 *   <tr><td>{@code naru.auth.permit-prefix}</td><td>comma-separated public prefixes</td></tr>
 *   <tr><td>{@code naru.auth.protect-only}</td><td>comma-separated protected prefixes</td></tr>
 *   <tr><td>{@code naru.auth.stateless-access}</td><td>{@code true} or {@code false}</td></tr>
 *   <tr><td>{@code naru.auth.api-key-scheme}</td><td>the API key scheme name</td></tr>
 * </table>
 */
// @req FR-AUTH-002
public final class AuthConfig {

    /** Every key the loader knows, in the order of the class description. */
    public static final List<String> KEYS = Collections.unmodifiableList(Arrays.asList(
            "naru.auth.secret", "naru.auth.mode", "naru.auth.profile", "naru.auth.deployment", "naru.auth.transport",
            "naru.auth.mount-path", "naru.auth.permit", "naru.auth.permit-prefix", "naru.auth.protect-only",
            "naru.auth.stateless-access", "naru.auth.api-key-scheme"));

    private final Properties values;
    private final List<String> unknownKeys = new ArrayList<>();
    private final String source;

    private AuthConfig(Properties values, String source) {
        this.values = values;
        this.source = source;
        for (String key : new TreeSet<>(values.stringPropertyNames())) {
            if (!KEYS.contains(key)) {
                unknownKeys.add(source + ": unknown key " + key + " (known keys: " + KEYS + ")");
            }
        }
    }

    /** The values of the Properties file {@code file}, read as UTF-8. */
    // @req FR-AUTH-002
    public static AuthConfig load(File file) throws IOException {
        Properties p = new Properties();
        InputStream in = new FileInputStream(file);
        try {
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } finally {
            in.close();
        }
        return new AuthConfig(p, file.getPath());
    }

    /** The values of {@code properties} (copied). */
    // @req FR-AUTH-002
    public static AuthConfig from(Properties properties) {
        Properties p = new Properties();
        for (String k : properties.stringPropertyNames()) {
            p.setProperty(k, properties.getProperty(k));
        }
        return new AuthConfig(p, "AuthConfig");
    }

    private @Nullable String get(String key) {
        String v = values.getProperty(key);
        return v == null ? null : v.trim();
    }

    private <E extends Enum<E>> @Nullable E parse(String key, String value, Class<E> type, List<String> violations) {
        String constant = value.toUpperCase(Locale.ROOT).replace('-', '_');
        for (E e : type.getEnumConstants()) {
            if (e.name().equals(constant)) {
                return e;
            }
        }
        violations.add(source + ": " + key + " has an unknown value " + value);
        return null;
    }

    private static List<String> list(String value) {
        List<String> out = new ArrayList<>();
        for (String part : value.split(",")) {
            if (!part.trim().isEmpty()) {
                out.add(part.trim());
            }
        }
        return out;
    }

    /** Calls the builder setters of every present key; returns this application's violations, a new list each call. */
    List<String> applyTo(Auth.Builder b) {
        List<String> violations = new ArrayList<>(unknownKeys);
        String secret = get("naru.auth.secret");
        if (secret != null) {
            if (secret.startsWith("env:") && secret.length() > 4) {
                b.keys(KeySources.fromEnv(secret.substring(4)));
            } else if (secret.startsWith("file:") && secret.length() > 5) {
                String path = secret.substring(5);
                try {
                    b.keys(KeySources.of(readTrimmed(new File(path))));
                } catch (IOException e) {
                    violations.add(source + ": naru.auth.secret file " + path + " cannot be read");
                }
            } else {
                violations.add(source + ": naru.auth.secret must be a reference, env:NAME or file:/path; a secret "
                        + "is never written in the file");
            }
        }
        String mode = get("naru.auth.mode");
        if (mode != null) {
            AuthMode m = parse("naru.auth.mode", mode, AuthMode.class, violations);
            if (m != null) {
                b.mode(m);
            }
        }
        String profile = get("naru.auth.profile");
        if (profile != null) {
            AuthProfile p = parse("naru.auth.profile", profile, AuthProfile.class, violations);
            if (p != null) {
                b.profile(p);
            }
        }
        String deployment = get("naru.auth.deployment");
        if (deployment != null) {
            Deployment d = parse("naru.auth.deployment", deployment, Deployment.class, violations);
            if (d != null) {
                b.deployment(d);
            }
        }
        String transport = get("naru.auth.transport");
        if (transport != null) {
            TokenTransport t = parse("naru.auth.transport", transport, TokenTransport.class, violations);
            if (t != null) {
                b.transport(t);
            }
        }
        String mount = get("naru.auth.mount-path");
        if (mount != null) {
            b.mountPath(mount);
        }
        String permit = get("naru.auth.permit");
        if (permit != null) {
            for (String p : list(permit)) {
                b.permit(p);
            }
        }
        String prefixes = get("naru.auth.permit-prefix");
        if (prefixes != null) {
            for (String p : list(prefixes)) {
                b.permitPrefix(p);
            }
        }
        String protect = get("naru.auth.protect-only");
        if (protect != null) {
            b.protectOnly(list(protect).toArray(new String[0]));
        }
        String stateless = get("naru.auth.stateless-access");
        if (stateless != null) {
            if (stateless.equalsIgnoreCase("true") || stateless.equalsIgnoreCase("false")) {
                b.statelessAccess(Boolean.parseBoolean(stateless.toLowerCase(Locale.ROOT)));
            } else {
                violations.add(source + ": naru.auth.stateless-access has an unknown value " + stateless);
            }
        }
        String scheme = get("naru.auth.api-key-scheme");
        if (scheme != null) {
            b.apiKeyScheme(scheme);
        }
        return violations;
    }

    private static String readTrimmed(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8).trim();
        } finally {
            in.close();
        }
    }

    @Override
    public String toString() {
        return "AuthConfig{" + source + ", keys=" + new TreeSet<>(values.stringPropertyNames()) + "}";
    }
}
