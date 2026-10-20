package com.snoworca.naruserve.servlet;

import java.util.regex.Pattern;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import org.jspecify.annotations.Nullable;

/** The context init-parameters of the initializer; each failure is a ServletException naming parameter and value. */
// @req IR-SVL-001
final class InitParams {

    static final String CONFIGURER = "naruserve.configurer";
    static final String URL_PATTERN = "naruserve.urlPattern";
    static final String SHUTDOWN_TIMEOUT = "naruserve.shutdownTimeoutSeconds";

    private static final Pattern PREFIX_PATTERN = Pattern.compile("(/[^/*]+)+/\\*");

    private InitParams() {
    }

    /** {@code /*} when absent; only {@code /*}, {@code /} and {@code /prefix/*} pass. */
    // @req IR-SVL-001
    static String urlPattern(ServletContext ctx) throws ServletException {
        String v = ctx.getInitParameter(URL_PATTERN);
        if (v == null) {
            return "/*";
        }
        if (v.equals("/*") || v.equals("/") || PREFIX_PATTERN.matcher(v).matches()) {
            return v;
        }
        throw invalid(URL_PATTERN, v, "use /*, / or /prefix/*");
    }

    /** {@code 10} when absent; otherwise the exact text as an integer from 1 to 300. */
    // @req IR-SVL-001
    static int shutdownSeconds(ServletContext ctx) throws ServletException {
        String v = ctx.getInitParameter(SHUTDOWN_TIMEOUT);
        if (v == null) {
            return 10;
        }
        int seconds;
        try {
            seconds = Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw invalid(SHUTDOWN_TIMEOUT, v, "an integer from 1 to 300 is required");
        }
        if (seconds < 1 || seconds > 300) {
            throw invalid(SHUTDOWN_TIMEOUT, v, "an integer from 1 to 300 is required");
        }
        return seconds;
    }

    /** {@code /api/*} gives {@code /api}; {@code /*} and {@code /} give the empty string. */
    // @req IR-SVL-001
    static String prefix(String pattern) {
        return pattern.endsWith("/*") ? pattern.substring(0, pattern.length() - 2) : "";
    }

    private static ServletException invalid(String name, @Nullable String value, String hint) {
        return new ServletException(name + ": invalid value '" + value + "' (" + hint + ")");
    }
}
