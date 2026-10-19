package com.snoworca.naruserve.tinyhttpd;

import com.snoworca.naruserve.spi.ErrorRenderer;
import com.snoworca.tinyhttpd.http.ErrorHandler;
import com.snoworca.tinyhttpd.http.Request;
import com.snoworca.tinyhttpd.http.Response;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.Nullable;

/**
 * Writes the standard error JSON for errors TinyHttpd raises after parsing (the Host allowlist 421, for one).
 * TinyHttpd's pre-dispatch parse rejections use fixed plain bytes and never reach it.
 */
// @req FR-ENGINE-005
final class JsonErrorHandler implements ErrorHandler {

    private final ErrorRenderer renderer;

    JsonErrorHandler(ErrorRenderer renderer) {
        this.renderer = renderer;
    }

    /**
     * Writes the status, the JSON type and the renderer body, which carries a fresh request id; an empty body gets
     * no content type, since zero bytes are not JSON.
     */
    // @req FR-ENGINE-005
    @Override
    public void render(int status, @Nullable String message, Request request, Response response) throws IOException {
        byte[] body = renderer.render(status).getBytes(StandardCharsets.UTF_8);
        response.setStatus(status);
        if (body.length > 0) {
            response.setContentType("application/json; charset=UTF-8");
        }
        response.setContentLengthLong(body.length);
        response.getOutputStream().write(body);
    }
}
