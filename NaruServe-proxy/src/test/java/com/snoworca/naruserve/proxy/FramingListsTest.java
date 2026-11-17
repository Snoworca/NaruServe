package com.snoworca.naruserve.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.proxy.FramingLists.TransferCoding;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 * The Content-Length and Transfer-Encoding list parser shared by the proxy's framing checks (SEC-PROXY-003, design
 * D-054 and D-073). Each argument is the list of field-line values of one header name, in received order.
 */
public class FramingListsTest {

    private static List<String> lines(String... values) {
        return Arrays.asList(values);
    }

    private static long length(String... values) {
        return FramingLists.contentLength(lines(values));
    }

    private static TransferCoding coding(String... values) {
        return FramingLists.transferEncoding(lines(values));
    }

    // ---- Content-Length ----

    // SEC-PROXY-003 AC-5: one plain value is that number
    @Test
    public void sec_PROXY_003_AC5_singleContentLengthIsItsNumber() {
        assertEquals(5, length("5"));
        assertEquals(0, length("0"));
    }

    // SEC-PROXY-003 AC-5: a comma list of equal elements is that number
    @Test
    public void sec_PROXY_003_AC5_equalElementsInOneLineAreOneNumber() {
        assertEquals(5, length("5, 5"));
        assertEquals(5, length("5,5,5"));
    }

    // SEC-PROXY-003 AC-5: optional whitespace (spaces and tabs) around an element is trimmed
    @Test
    public void sec_PROXY_003_AC5_owsAroundElementsIsTrimmed() {
        assertEquals(5, length("  5 "));
        assertEquals(5, length("\t5\t,  5"));
    }

    // SEC-PROXY-003 AC-2: only spaces and tabs are OWS; another control character around an element is not digits
    @Test
    public void sec_PROXY_003_AC2_otherWhitespaceAroundAnElementIsInvalid() {
        assertEquals(FramingLists.INVALID_LENGTH, length("\u000b5"));
        assertEquals(FramingLists.INVALID_LENGTH, length("5\u000b"));
        assertEquals(FramingLists.INVALID_LENGTH, length("\u00005"));
        assertEquals(FramingLists.INVALID_LENGTH, length("5\r"));
    }

    // SEC-PROXY-003 AC-5: the invalid marker is negative, so it never collides with a valid length up to Long.MAX_VALUE
    @Test
    public void sec_PROXY_003_AC5_invalidMarkerIsNotAValidLength() {
        assertTrue(FramingLists.INVALID_LENGTH < 0);
    }

    // SEC-PROXY-003 AC-5: elements are compared numerically, so 5 and 05 on two lines are the same length
    @Test
    public void sec_PROXY_003_AC5_numericallyEqualLinesAreOneNumber() {
        assertEquals(5, length("5", "05"));
        assertEquals(5, length("0000000000000000000000005"));
    }

    // SEC-PROXY-003 AC-5: the largest long is a valid length
    @Test
    public void sec_PROXY_003_AC5_longMaxIsAValidLength() {
        assertEquals(Long.MAX_VALUE, length(String.valueOf(Long.MAX_VALUE)));
    }

    // SEC-PROXY-003 AC-2: differing elements, on one line or on two, are invalid
    @Test
    public void sec_PROXY_003_AC2_differingElementsAreInvalid() {
        assertEquals(FramingLists.INVALID_LENGTH, length("5", "6"));
        assertEquals(FramingLists.INVALID_LENGTH, length("5, 6"));
        assertEquals(FramingLists.INVALID_LENGTH, length("5, 5", "6"));
    }

    // SEC-PROXY-003 AC-2: an element that is not only ASCII digits is invalid
    @Test
    public void sec_PROXY_003_AC2_nonDigitElementsAreInvalid() {
        assertEquals(FramingLists.INVALID_LENGTH, length("abc"));
        assertEquals(FramingLists.INVALID_LENGTH, length("-1"));
        assertEquals(FramingLists.INVALID_LENGTH, length("+5"));
        assertEquals(FramingLists.INVALID_LENGTH, length("5 5"));
        assertEquals(FramingLists.INVALID_LENGTH, length("0x5"));
        assertEquals(FramingLists.INVALID_LENGTH, length("٥")); // ARABIC-INDIC DIGIT FIVE is not ASCII
    }

    // SEC-PROXY-003 AC-2: a number beyond a long is invalid
    @Test
    public void sec_PROXY_003_AC2_numberBeyondALongIsInvalid() {
        assertEquals(FramingLists.INVALID_LENGTH, length("99999999999999999999"));
        assertEquals(FramingLists.INVALID_LENGTH, length("9223372036854775808"));
    }

    // SEC-PROXY-003 AC-2: an empty value or an empty element is not digits, so invalid
    @Test
    public void sec_PROXY_003_AC2_emptyValueOrElementIsInvalid() {
        assertEquals(FramingLists.INVALID_LENGTH, length(""));
        assertEquals(FramingLists.INVALID_LENGTH, length("  "));
        assertEquals(FramingLists.INVALID_LENGTH, length("5,"));
        assertEquals(FramingLists.INVALID_LENGTH, length("5", ""));
    }

    // SEC-PROXY-003 AC-2: no field line at all has no length to agree on, so it is invalid
    @Test
    public void sec_PROXY_003_AC2_noLinesIsInvalid() {
        assertEquals(FramingLists.INVALID_LENGTH, FramingLists.contentLength(Collections.<String>emptyList()));
    }

    // ---- Transfer-Encoding ----

    // SEC-PROXY-003 AC-5: chunked alone, in any case, is chunked framing
    @Test
    public void sec_PROXY_003_AC5_chunkedInAnyCaseIsChunked() {
        assertEquals(TransferCoding.CHUNKED, coding("chunked"));
        assertEquals(TransferCoding.CHUNKED, coding("CHUNKED"));
        assertEquals(TransferCoding.CHUNKED, coding("Chunked"));
    }

    // SEC-PROXY-003 AC-5: empty elements and OWS are ignored
    @Test
    public void sec_PROXY_003_AC5_emptyElementsAndOwsAreIgnored() {
        assertEquals(TransferCoding.CHUNKED, coding("chunked,"));
        assertEquals(TransferCoding.CHUNKED, coding(" , chunked ,\t"));
        assertEquals(TransferCoding.CHUNKED, coding("", "chunked"));
    }

    // SEC-PROXY-003 AC-3: a last coding other than chunked is invalid
    @Test
    public void sec_PROXY_003_AC3_lastCodingNotChunkedIsInvalid() {
        assertEquals(TransferCoding.INVALID, coding("gzip"));
        assertEquals(TransferCoding.INVALID, coding("chunked, gzip"));
        assertEquals(TransferCoding.INVALID, coding("chunked", "gzip"));
    }

    // SEC-PROXY-003 AC-3: only spaces and tabs are OWS, so chunked with another control character is not chunked
    @Test
    public void sec_PROXY_003_AC3_chunkedWithOtherWhitespaceIsNotChunked() {
        assertEquals(TransferCoding.INVALID, coding("chunked\u000b"));
        assertEquals(TransferCoding.INVALID, coding("\u000bchunked"));
    }

    // SEC-PROXY-003 AC-3: chunked more than once, on one line or across lines, is invalid
    @Test
    public void sec_PROXY_003_AC3_chunkedTwiceIsInvalid() {
        assertEquals(TransferCoding.INVALID, coding("chunked, chunked"));
        assertEquals(TransferCoding.INVALID, coding("chunked", "chunked"));
        assertEquals(TransferCoding.INVALID, coding("chunked", "CHUNKED"));
        assertEquals(TransferCoding.INVALID, coding("chunked, gzip, chunked"));
    }

    // SEC-PROXY-003 AC-4: another coding before a single final chunked is unsupported (501)
    @Test
    public void sec_PROXY_003_AC4_codingBeforeChunkedIsUnsupported() {
        assertEquals(TransferCoding.UNSUPPORTED, coding("gzip, chunked"));
        assertEquals(TransferCoding.UNSUPPORTED, coding("gzip", "chunked"));
        assertEquals(TransferCoding.UNSUPPORTED, coding("gzip, deflate, chunked"));
    }

    // SEC-PROXY-003 AC-6: a coding with parameters is a coding other than chunked
    @Test
    public void sec_PROXY_003_AC6_codingWithParametersIsNotChunked() {
        assertEquals(TransferCoding.INVALID, coding("chunked;x=1"));
        assertEquals(TransferCoding.INVALID, coding("chunked ; x=1"));
        assertEquals(TransferCoding.UNSUPPORTED, coding("gzip;q=1, chunked"));
        assertEquals(TransferCoding.UNSUPPORTED, coding("chunked;x=1, chunked"));
    }

    // SEC-PROXY-003 AC-6: an empty overall value counts as a coding other than chunked, so invalid
    @Test
    public void sec_PROXY_003_AC6_emptyOverallValueIsInvalid() {
        assertEquals(TransferCoding.INVALID, coding(""));
        assertEquals(TransferCoding.INVALID, coding(" , ,"));
        assertEquals(TransferCoding.INVALID, coding("", ""));
    }

    // SEC-PROXY-003 AC-6: no field line at all is an empty overall value, so invalid
    @Test
    public void sec_PROXY_003_AC6_noLinesIsInvalid() {
        assertEquals(TransferCoding.INVALID, FramingLists.transferEncoding(Collections.<String>emptyList()));
    }
}
