package com.snoworca.naruserve.docs;

import static com.snoworca.naruserve.docs.WaveFiveDocsTest.doc;
import static com.snoworca.naruserve.docs.WaveFiveDocsTest.row;
import static com.snoworca.naruserve.docs.WaveSevenDocsTest.section;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import org.junit.Test;

/**
 * A response written by {@code res.relay} never receives {@code app.defaultCache} (FR-HTTP-036 AC-5), so every docs
 * place that describes the default cache policy states that exception, in Korean and in English.
 */
// @req FR-HTTP-036 AC-5
public final class RelayDefaultCacheDocsTest {

    private static void assertRelayException(String where, String text) {
        assertTrue(where + " must state that res.relay never receives app.defaultCache:\n" + text,
                text.contains("`res.relay`") && text.contains("defaultCache"));
    }

    // FR-HTTP-036 AC-5: static.md "서버 기본 정책" and en/static.md "Default server policy"
    @Test
    public void FR_HTTP_036_AC5_staticServerPolicyStatesRelayException() throws IOException {
        assertRelayException("static.md 서버 기본 정책", section(doc("static.md"), "### 서버 기본 정책"));
        assertRelayException("en/static.md Default server policy",
                section(doc("en/static.md"), "### Default server policy"));
    }

    // FR-HTTP-036 AC-5: the Cache-Control rows of defaults.md and en/defaults.md
    @Test
    public void FR_HTTP_036_AC5_defaultsCacheControlRowStatesRelayException() throws IOException {
        assertRelayException("defaults.md Cache-Control row",
                String.join(" | ", row(doc("defaults.md"), "`Cache-Control`")));
        assertRelayException("en/defaults.md Cache-Control row",
                String.join(" | ", row(doc("en/defaults.md"), "`Cache-Control`")));
    }

    // FR-HTTP-036 AC-5: response.md "기본 캐시 정책" and en/response.md "Default cache policy"
    @Test
    public void FR_HTTP_036_AC5_responseDefaultCacheStatesRelayException() throws IOException {
        assertRelayException("response.md 기본 캐시 정책", section(doc("response.md"), "### 기본 캐시 정책"));
        assertRelayException("en/response.md Default cache policy",
                section(doc("en/response.md"), "### Default cache policy"));
    }
}
