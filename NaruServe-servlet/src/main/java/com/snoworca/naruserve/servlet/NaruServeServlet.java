package com.snoworca.naruserve.servlet;

import com.snoworca.naruserve.spi.ErrorRenderer;
import com.snoworca.naruserve.spi.ExchangeHandler;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * The servlet {@link NaruServeInitializer} registers. Every method token goes to the core through a
 * {@link ServletExchange} whose path is {@link ServletPath#remainder} of the raw request URI.
 */
// @req IR-SVL-001
// @req FR-SVL-002
// @req FR-SVL-003
final class NaruServeServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    /** The registered servlet name. */
    static final String NAME = "naruserve";

    final transient ServletAdapter adapter;
    /** The mapping prefix ({@code /api} for {@code /api/*}), empty for {@code /*} and {@code /}. */
    final String prefix;

    // @req IR-SVL-001
    NaruServeServlet(ServletAdapter adapter, String prefix) {
        this.adapter = adapter;
        this.prefix = prefix;
    }

    /**
     * Overrides without super, so HttpServlet refuses no method token; unbound, stopping or released is 503 with
     * {@code Connection: close}, a prefix miss 404. Every admitted request is counted in and out of the adapter, so
     * its stop can wait for it. Once the handler returned, the exchange's afterHandler runs, so this request thread
     * is released while an SSE stream stays open.
     */
    // @req FR-SVL-002
    // @req FR-SVL-005
    // @req FR-SVL-006
    @Override
    protected void service(HttpServletRequest req, HttpServletResponse res) throws IOException {
        ExchangeHandler handler = adapter.enter();
        if (handler == null) {
            reject(res, 503, true);
            return;
        }
        try {
            String path = ServletPath.remainder(req.getRequestURI(), req.getContextPath(), prefix);
            if (path == null) {
                reject(res, 404, false);
                return;
            }
            ServletExchange ex = new ServletExchange(req, res, path, adapter);
            try {
                handler.handle(ex);
            } finally {
                ex.afterHandler(); // starts a registered event-stream drain on the adapter's pool
            }
        } finally {
            adapter.leave();
        }
    }

    /** The adapter's own answer: fixed header values and the standard body, never sendError, nothing logged. */
    // @req FR-SVL-002
    void reject(HttpServletResponse res, int status, boolean close) throws IOException {
        byte[] body = adapter.errorRenderer().render(status).getBytes(StandardCharsets.UTF_8);
        res.setStatus(status);
        res.setHeader("Content-Type", ErrorRenderer.JSON_TYPE);
        if (close) {
            res.setHeader("Connection", "close");
        }
        res.setHeader("Content-Length", Integer.toString(body.length));
        res.getOutputStream().write(body);
    }
}
