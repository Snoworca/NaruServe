package com.snoworca.naruserve.tinyhttpd;

import com.snoworca.naruserve.HttpException;
import com.snoworca.tinyhttpd.http.BodyTooLargeException;
import com.snoworca.tinyhttpd.http.MalformedBodyException;
import com.snoworca.tinyhttpd.http.TruncatedBodyException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;

/** The TinyHttpd request body; its client-caused failures surface as {@link HttpException}. */
// @req FR-ENGINE-005
final class TinyHttpdBodyStream extends FilterInputStream {

    TinyHttpdBodyStream(InputStream in) {
        super(in);
    }

    /**
     * {@link HttpException} 413, 408 or 400 for a size overrun, a read timeout or a truncated or malformed body, the
     * original kept as cause; any other exception, such as the response-side ContentLengthExceededException, is
     * rethrown as is.
     */
    // @req FR-ENGINE-005
    static HttpException map(IOException e) throws IOException {
        int status;
        if (e instanceof BodyTooLargeException) {
            status = 413;
        } else if (e instanceof SocketTimeoutException) {
            status = 408;
        } else if (e instanceof TruncatedBodyException || e instanceof MalformedBodyException) {
            status = 400;
        } else {
            throw e;
        }
        return new HttpException(status, null, e);
    }

    @Override
    public int read() throws IOException {
        try {
            return super.read();
        } catch (IOException e) {
            throw map(e);
        }
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        try {
            return super.read(b, off, len);
        } catch (IOException e) {
            throw map(e);
        }
    }

    @Override
    public long skip(long n) throws IOException {
        try {
            return super.skip(n);
        } catch (IOException e) {
            throw map(e);
        }
    }
}
