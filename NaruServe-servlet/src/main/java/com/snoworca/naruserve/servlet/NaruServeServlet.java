package com.snoworca.naruserve.servlet;

import javax.servlet.http.HttpServlet;

/** The servlet {@link NaruServeInitializer} registers; later parts serve requests through the adapter. */
// @req IR-SVL-001
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
}
