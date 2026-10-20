package com.snoworca.naruserve.testing;

import com.snoworca.naruserve.SseClient;
import com.snoworca.naruserve.spi.ServerSettings;
import java.lang.reflect.Method;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NullMarked;

/** Small helpers the tests of every module repeat: settings, header lists, the SSE end cause and the request id mask. */
@NullMarked
public final class TestSupport {
    private TestSupport() {
    }

    /** Settings with the documented defaults, a recording logger and the fixed renderer {@code {}}; port and address are the caller's. */
    public static ServerSettings.Builder settings() {
        return ServerSettings.builder().logger(new RecordingLogger()).errorRenderer(status -> "{}");
    }

    /** Header name and value pairs as an entry list, in order. */
    public static List<Map.Entry<String, String>> headers(String... pairs) {
        List<Map.Entry<String, String>> out = new ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.add(new SimpleImmutableEntry<>(pairs[i], pairs[i + 1]));
        }
        return out;
    }

    /** The end reason the core recorded for {@code c}, read reflectively since the client class is internal. */
    public static String cause(SseClient c) throws Exception {
        Method m = c.getClass().getDeclaredMethod("cause");
        m.setAccessible(true);
        return (String) m.invoke(c);
    }

    /** {@code body} with the random request id of a standard error body replaced by {@code *}. */
    public static String maskRequestId(String body) {
        return body.replaceAll("\"requestId\":\"[^\"]*\"", "\"requestId\":\"*\"");
    }
}
