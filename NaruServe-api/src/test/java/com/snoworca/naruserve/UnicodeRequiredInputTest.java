package com.snoworca.naruserve;

import static org.junit.Assert.assertEquals;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class UnicodeRequiredInputTest {
    // FR-DX-003 AC-1: all Unicode whitespace or space characters alone are blank for query/form helpers.
    @Test public void unicodeBlankQueryAndFormFieldsAnswer400() throws Exception {
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        RestApp app = RestApp.builder().httpAdapter(adapter).jsonCodec(new com.snoworca.naruserve.testing.StubJsonCodec()).build();
        app.get("/query", (req, res) -> res.send(req.requireQuery("name")));
        app.post("/form", (req, res) -> res.send(req.requireForm("name")));
        app.listen(0);
        try {
            for (String blank : new String[] {"\u2003", "\u3000", "\u00A0", " \t\u2003\u3000\u00A0 "}) {
                String encoded = URLEncoder.encode(blank, "UTF-8");
                assertEquals(400, adapter.send(ClientRequest.of("GET", "/query?name=" + encoded)).status());
                assertEquals(400, adapter.send(ClientRequest.of("POST", "/form")
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .body(("name=" + encoded).getBytes(StandardCharsets.UTF_8))).status());
            }
        } finally { app.shutdown(5, TimeUnit.SECONDS); }
    }

    // FR-DX-003 AC-1: NBSP is valid header octet text but remains blank for required-value validation.
    @Test public void unicodeBlankHeaderAnswers400() {
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        RestApp app = RestApp.builder().httpAdapter(adapter).jsonCodec(new com.snoworca.naruserve.testing.StubJsonCodec()).build();
        app.get("/header", (req, res) -> res.send(req.requireHeader("X-Name")));
        app.listen(0);
        try {
            assertEquals(400, adapter.send(ClientRequest.of("GET", "/header").header("X-Name", " \t\u00A0 ")).status());
        } finally { app.shutdown(5, TimeUnit.SECONDS); }
    }

    // FR-DX-003 AC-1: validation preserves original nonblank values; zero-width space is not a Unicode space.
    @Test public void nonblankValuesAreNotTrimmedOrBroadenedToAllInvisibleCharacters() throws Exception {
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        RestApp app = RestApp.builder().httpAdapter(adapter).jsonCodec(new com.snoworca.naruserve.testing.StubJsonCodec()).build();
        app.get("/query", (req, res) -> res.send(req.requireQuery("name")));
        app.listen(0);
        try {
            for (String original : new String[] {" \u2003Ada\u3000 ", "\u200B", "\uD801\uDC00"}) {
                assertEquals(original, adapter.send(ClientRequest.of("GET", "/query?name="
                        + URLEncoder.encode(original, "UTF-8"))).text());
            }
        } finally { app.shutdown(5, TimeUnit.SECONDS); }
    }
}
