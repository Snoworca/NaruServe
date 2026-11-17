package com.snoworca.naruserve.proxy;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Locale;
import org.junit.Test;

/**
 * The request-target character and path segment checks of the proxy (SEC-PROXY-001), on raw strings: the path check
 * {@link PathChecker#isSafePath} that {@code Proxy.to} also applies to its base path (design D-072) and the request
 * target check {@link PathChecker#isSafeTarget} the middleware applies to the raw {@code originalUrl()}.
 */
public class PathCheckerTest {

    /** The characters RFC 3986 calls unreserved: ALPHA, DIGIT, '-', '.', '_' and '~'. */
    private static final String UNRESERVED =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~";

    private static void rejected(String rawTarget) {
        assertFalse("expected a rejection: " + printable(rawTarget), PathChecker.isSafeTarget(rawTarget));
    }

    private static void accepted(String rawTarget) {
        assertTrue("expected a pass: " + printable(rawTarget), PathChecker.isSafeTarget(rawTarget));
    }

    private static String printable(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x21 || c > 0x7E) {
                b.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    private static String hex(int b) {
        return String.format(Locale.ROOT, "%02X", b);
    }

    // ---- AC-1 ----

    // SEC-PROXY-001 AC-1: '.' and '..' segments, raw or percent-encoded in either case, and with ';', are rejected
    @Test
    public void sec_PROXY_001_AC1_dotSegmentsAreRejected() {
        rejected("/api/orders/../other/x");
        rejected("/api/orders/%2e%2e/other/x");
        rejected("/api/orders/%2E%2E/other/x");
        rejected("/api/orders/..;/other/x");
        rejected("/api/orders/.;x/other/x");
        rejected("/api/orders/./x");
    }

    // SEC-PROXY-001 AC-1: a dot segment at the end of the path or right after the root is rejected too
    @Test
    public void sec_PROXY_001_AC1_dotSegmentsAtTheEdgesAreRejected() {
        rejected("/api/orders/..");
        rejected("/api/orders/.");
        rejected("/..");
        rejected("/.");
        rejected("/api/orders/../");
        rejected("/api/orders/%2e");
        rejected("/api/orders/.%2E/x");
    }

    // SEC-PROXY-001 AC-1: segments that only contain dots among other characters are ordinary segments
    @Test
    public void sec_PROXY_001_AC1_dotsInsideALongerSegmentPass() {
        accepted("/api/orders/...");
        accepted("/api/orders/a.b/x");
        accepted("/api/orders/.hidden");
        accepted("/api/orders/x..");
    }

    // ---- AC-2 ----

    // SEC-PROXY-001 AC-2: an encoded slash, backslash or NUL in either case and a raw backslash are rejected
    @Test
    public void sec_PROXY_001_AC2_encodedSlashBackslashNulAndRawBackslashAreRejected() {
        rejected("/api/orders/a%2fb");
        rejected("/api/orders/a%2Fb");
        rejected("/api/orders/a%5cb");
        rejected("/api/orders/a%5Cb");
        rejected("/api/orders/a%00b");
        rejected("/api/orders/a\\b");
    }

    // ---- AC-3 ----

    // SEC-PROXY-001 AC-3: percent-encoded unreserved characters and ';' in a segment are rejected
    @Test
    public void sec_PROXY_001_AC3_encodedUnreservedAndSemicolonAreRejected() {
        rejected("/api/orders/%70rivate/x");
        rejected("/api/orders/%7Ex");
        rejected("/api/orders/%41");
        rejected("/api/orders/private;x/y");
    }

    // SEC-PROXY-001 AC-3: every unreserved character is rejected percent-encoded in upper and lower case hex
    @Test
    public void sec_PROXY_001_AC3_everyEncodedUnreservedCharacterIsRejectedInBothCases() {
        for (int i = 0; i < UNRESERVED.length(); i++) {
            String upper = hex(UNRESERVED.charAt(i));
            rejected("/api/orders/a%" + upper + "b");
            rejected("/api/orders/a%" + upper.toLowerCase(Locale.ROOT) + "b");
        }
    }

    // SEC-PROXY-001 AC-3: every other percent-encoded byte passes in the path, except the encoded slash, backslash and
    // NUL of AC-2
    @Test
    public void sec_PROXY_001_AC3_otherEncodedBytesPass() {
        for (int b = 0; b < 256; b++) {
            if ((b < 128 && UNRESERVED.indexOf(b) >= 0) || b == 0x2F || b == 0x5C || b == 0x00) {
                continue;
            }
            accepted("/api/orders/a%" + hex(b) + "b");
            accepted("/api/orders/a%" + hex(b).toLowerCase(Locale.ROOT) + "b");
        }
    }

    // ---- AC-4 ----

    // SEC-PROXY-001 AC-4: a path that holds a rejected encoding again once decoded is rejected
    @Test
    public void sec_PROXY_001_AC4_doubleEncodingsAreRejected() {
        rejected("/api/orders/%252e%252e/other/x");
        rejected("/api/orders/a%252fb");
        rejected("/api/orders/%2570rivate");
        rejected("/api/orders/a%252Fb");
        rejected("/api/orders/a%255cb");
        rejected("/api/orders/a%2500b");
        rejected("/api/orders/%252E");
        rejected("/api/orders/a%2541");
    }

    // SEC-PROXY-001 AC-4: an encoded '%' whose decoded form is not a rejected encoding passes
    @Test
    public void sec_PROXY_001_AC4_encodedPercentOfAnAllowedEncodingPasses() {
        accepted("/api/orders/a%2520b");
        accepted("/api/orders/100%25");
        accepted("/api/orders/%25zz");
        accepted("/api/orders/%25ED");
    }

    // ---- AC-5 ----

    // SEC-PROXY-001 AC-5: empty segments are rejected, except one trailing '/'
    @Test
    public void sec_PROXY_001_AC5_emptySegmentsAreRejectedExceptOneTrailingSlash() {
        rejected("/api/orders//private/x");
        rejected("/api/orders/private//x");
        rejected("/api/orders/x//");
        rejected("//");
        accepted("/api/orders/x/");
        accepted("/api/orders/x");
        accepted("/");
    }

    // ---- AC-6 ----

    // SEC-PROXY-001 AC-6: controls, space, 0x7F and non-ASCII characters are rejected in the path
    @Test
    public void sec_PROXY_001_AC6_controlSpaceDelAndNonAsciiAreRejectedInThePath() {
        for (char c = 0; c <= 0x20; c++) {
            rejected("/api/orders/a" + c + "b");
        }
        rejected("/api/orders/a\u007Fb");
        rejected("/api/orders/a\u0080b");
        rejected("/api/orders/aÿb");
        rejected("/api/orders/한");
    }

    // SEC-PROXY-001 AC-6: the same characters are rejected in the query
    @Test
    public void sec_PROXY_001_AC6_controlSpaceDelAndNonAsciiAreRejectedInTheQuery() {
        for (char c = 0; c <= 0x20; c++) {
            rejected("/api/orders/x?q=a" + c + "b");
        }
        rejected("/api/orders/x?q=\u007F");
        rejected("/api/orders/x?q=\u0080");
        rejected("/api/orders/x?q=ÿ");
        rejected("/api/orders/x?q=한");
    }

    // SEC-PROXY-001 AC-6: '#' and a '%' not followed by two hexadecimal digits are rejected in the path and the query
    @Test
    public void sec_PROXY_001_AC6_hashAndBadPercentAreRejected() {
        rejected("/api/orders/x#frag");
        rejected("/api/orders/x?q=1#frag");
        rejected("/api/orders/x?q=%zz");
        rejected("/api/orders/x?q=%");
        rejected("/api/orders/x?q=%4");
        rejected("/api/orders/x?q=%4g");
        rejected("/api/orders/x%");
        rejected("/api/orders/x%4");
        rejected("/api/orders/x%g1/y");
        // a non-ASCII digit after '%' is not a hexadecimal digit
        rejected("/api/orders/x%\u06611");
        rejected("/api/orders/x?q=%\uFF11\uFF11");
    }

    // SEC-PROXY-001 AC-6: '@', ':', '!', '$' and well-formed encodings pass in the path and the query
    @Test
    public void sec_PROXY_001_AC6_allowedCharactersPass() {
        accepted("/api/orders/x?x=1&y=%ED%95%9C");
        accepted("/api/orders/a@b:c!d$e");
        accepted("/api/orders/x?a=@&b=:&c=!&d=$");
        accepted("/api/orders/%ED%95%9C");
        accepted("/api/orders/x?");
        accepted("/api/orders/x?a='()*+,=&b=[]");
    }

    // SEC-PROXY-001 AC-6: the query is checked for characters only, not for segment rules or encodings
    @Test
    public void sec_PROXY_001_AC6_theQueryIsNotSegmentChecked() {
        accepted("/api/orders/x?path=../a//b;c");
        accepted("/api/orders/x?a=%2f%2e%2e%41%00");
        accepted("/api/orders/x?a=%252e");
        accepted("/api/orders/x?a?b");
    }

    // ---- AC-7 ----

    // SEC-PROXY-001 AC-7: '@' in the path passes while '//' and an encoded '//' are rejected
    @Test
    public void sec_PROXY_001_AC7_authorityLookingPaths() {
        accepted("/api/orders/@evil.example/x");
        rejected("/api/orders//evil.example/x");
        rejected("/api/orders/%2F%2Fevil.example");
        rejected("//evil.example/x");
    }

    // ---- AC-8 ----

    // SEC-PROXY-001 AC-8: a raw target that is not origin-form never passes, so a scheme and host cannot ride in it
    @Test
    public void sec_PROXY_001_AC8_aTargetThatIsNotOriginFormIsRejected() {
        rejected("http://evil.example/api/orders/x");
        rejected("http://h/api/orders/%2e%2e/x");
        rejected("evil.example/x");
        rejected("*");
        rejected("?q=1");
    }

    // ---- D-072: the same checks on a base path ----

    // SEC-PROXY-001 AC-1: the path check Proxy.to reuses for its base path rejects the same segments (design D-072)
    @Test
    public void sec_PROXY_001_AC1_basePathCheckRejectsDotSegments() {
        assertFalse(PathChecker.isSafePath("/v1/.."));
        assertFalse(PathChecker.isSafePath("/v1/%2e%2e"));
        assertFalse(PathChecker.isSafePath("/v1/./x"));
    }

    // SEC-PROXY-001 AC-2: the base path check rejects an encoded slash and a raw backslash (design D-072)
    @Test
    public void sec_PROXY_001_AC2_basePathCheckRejectsEncodedSlashAndBackslash() {
        assertFalse(PathChecker.isSafePath("/v1/a%2fb"));
        assertFalse(PathChecker.isSafePath("/v1/a\\b"));
    }

    // SEC-PROXY-001 AC-5: the base path check rejects empty segments and passes the empty base path (design D-072)
    @Test
    public void sec_PROXY_001_AC5_basePathCheckOnEmptySegmentsAndNoBasePath() {
        assertFalse(PathChecker.isSafePath("/v1//x"));
        assertTrue(PathChecker.isSafePath(""));
        assertTrue(PathChecker.isSafePath("/v1"));
        assertTrue(PathChecker.isSafePath("/v1/a@b"));
    }

    // SEC-PROXY-001 AC-6: the base path check rejects a space, a non-ASCII character and a bad '%' (design D-072)
    @Test
    public void sec_PROXY_001_AC6_basePathCheckRejectsBadCharacters() {
        assertFalse(PathChecker.isSafePath("/v 1"));
        assertFalse(PathChecker.isSafePath("/vé1"));
        assertFalse(PathChecker.isSafePath("/v1%"));
        assertFalse(PathChecker.isSafePath("v1"));
    }
}
