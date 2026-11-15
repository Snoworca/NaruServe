package com.snoworca.naruserve.gson;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.BodyTooLargeException;
import com.snoworca.naruserve.HttpException;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.TestApp;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** The JSON body limit through the default Gson codec, which wraps the stream's IOException. */
public class GsonBodyLimitTest {

    // IR-HTTP-011 AC-4: a chunked JSON body over jsonLimit, decoded by GsonJsonCodec, is 413 PAYLOAD_TOO_LARGE and
    // the overrun is the public BodyTooLargeException in the cause chain
    @Test
    public void IR_HTTP_011_AC4_chunkedOverrunThroughGsonIs413PayloadTooLarge() {
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        TestApp t = TestApp.create(adapter);
        t.app().jsonCodec(new GsonJsonCodec()).jsonLimit(10);
        t.app().post("/b", (req, res) -> res.send(String.valueOf(req.body(String.class))));
        List<Throwable> seen = new ArrayList<>();
        t.app().onError((error, req, res) -> seen.add(error));
        t.start();
        byte[] eleven = "\"123456789\"".getBytes(StandardCharsets.UTF_8);
        assertEquals(11, eleven.length);
        ClientResponse r = adapter.send(ClientRequest.of("POST", "/b").header("Content-Type", "application/json")
                .body(eleven));
        assertEquals(413, r.status());
        assertEquals(1, seen.size());
        HttpException e = (HttpException) seen.get(0);
        assertEquals(413, e.status());
        assertEquals("PAYLOAD_TOO_LARGE", e.code());
        boolean found = false;
        int depth = 0;
        for (Throwable c = e; c != null && depth++ < 64; c = c.getCause()) {
            found |= c instanceof BodyTooLargeException;
        }
        assertTrue(String.valueOf(e.getCause()), found);

        byte[] ten = "\"12345678\"".getBytes(StandardCharsets.UTF_8);
        ClientResponse ok = adapter.send(ClientRequest.of("POST", "/b").header("Content-Type", "application/json")
                .body(ten));
        assertEquals(200, ok.status());
        assertEquals("12345678", ok.text());
    }
}
