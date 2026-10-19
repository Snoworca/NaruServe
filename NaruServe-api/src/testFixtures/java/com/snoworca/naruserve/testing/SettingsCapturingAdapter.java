package com.snoworca.naruserve.testing;

import com.snoworca.naruserve.spi.ExchangeHandler;
import com.snoworca.naruserve.spi.HttpAdapter;
import com.snoworca.naruserve.spi.ServerSettings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Test adapter that keeps every {@code bind(ServerSettings, ExchangeHandler)} call, the last settings and handler,
 * so exchanges can be driven in process; the host/port bind fails, since listen() must use the settings bind.
 */
// @req IR-ENGINE-002
@NullMarked
public class SettingsCapturingAdapter implements HttpAdapter {

    /** Every settings value bound, in order. */
    public final List<ServerSettings> binds = Collections.synchronizedList(new ArrayList<ServerSettings>());
    public volatile @Nullable ServerSettings settings;
    public volatile @Nullable ExchangeHandler handler;
    public volatile boolean bound;

    @Override
    public void bind(ServerSettings s, ExchangeHandler h) {
        binds.add(s);
        settings = s;
        handler = h;
        bound = true;
    }

    @Override
    public final void bind(String host, int port, ExchangeHandler h) {
        throw new AssertionError("listen must use bind(ServerSettings, ExchangeHandler)");
    }

    @Override
    public int boundPort() {
        return 8080;
    }

    @Override
    public void stop(long timeout, TimeUnit unit) {
    }

    @Override
    public void release() {
    }
}
