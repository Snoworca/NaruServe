package com.snoworca.naruserve.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.snoworca.naruserve.UploadFile;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class FormDataTest {

    private final SpoolRegistry spool = new SpoolRegistry();

    private DefaultUploadFile textPart(String name, String value) {
        byte[] b = value.getBytes(StandardCharsets.UTF_8);
        return new DefaultUploadFile(name, null, null, b.length, b, null, spool);
    }

    private DefaultUploadFile filePart(String name, String filename) {
        return new DefaultUploadFile(name, filename, "text/plain", 1, new byte[] {'x'}, null, spool);
    }

    private static void assertUnmodifiable(List<UploadFile> list) {
        try {
            list.add(null);
            fail("expected an unmodifiable list");
        } catch (UnsupportedOperationException expected) {
            // unmodifiable
        }
    }

    // SDS-AC-4; IR-MULTIPART-002 AC-3: parts() is the same unmodifiable list on every call
    @Test
    public void sdsAc4_IR_MULTIPART_002_AC3_partsIsTheSameUnmodifiableListOnEveryCall() {
        DefaultUploadFile t = textPart("title", "Hello");
        DefaultUploadFile a = filePart("photos", "a.png");
        DefaultUploadFile b = filePart("photos", "b.png");
        FormData d = FormData.multipart(Arrays.asList(t, a, b));
        assertSame(d.parts(), d.parts());
        assertEquals(Arrays.<UploadFile>asList(t, a, b), d.parts());
        assertUnmodifiable(d.parts());
    }

    // SDS-AC-4; IR-MULTIPART-002 AC-3: files(name) is a fresh unmodifiable list per call, in body order
    @Test
    public void sdsAc4_IR_MULTIPART_002_AC3_filesIsAFreshUnmodifiableListPerCall() {
        DefaultUploadFile a = filePart("photos", "a.png");
        DefaultUploadFile t = textPart("photos", "not a file");
        DefaultUploadFile b = filePart("photos", "b.png");
        FormData d = FormData.multipart(Arrays.asList(a, t, b));
        List<UploadFile> first = d.files("photos");
        List<UploadFile> second = d.files("photos");
        assertNotSame(first, second);
        assertEquals(Arrays.<UploadFile>asList(a, b), first);
        assertEquals(first, second);
        assertUnmodifiable(first);
        assertUnmodifiable(d.files("missing"));
        assertTrue(d.files("missing").isEmpty());
        assertSame(a, d.firstFile("photos"));
        assertEquals("not a file", d.firstText("photos"));
    }

    // SDS-AC-4; SDS-AC-3; IR-MULTIPART-002 AC-3: EMPTY and urlencoded data have no parts and no files
    @Test
    public void sdsAc4_IR_MULTIPART_002_AC3_emptyAndUrlencodedHaveNoParts() {
        assertTrue(FormData.EMPTY.parts().isEmpty());
        assertSame(FormData.EMPTY.parts(), FormData.EMPTY.parts());
        assertUnmodifiable(FormData.EMPTY.parts());
        assertNull(FormData.EMPTY.firstText("a"));
        assertNull(FormData.EMPTY.firstFile("a"));
        FormData u = FormData.urlencoded(Collections.singletonMap("a", Arrays.asList("1", "2")));
        assertEquals("1", u.firstText("a"));
        assertNull(u.firstFile("a"));
        assertTrue(u.parts().isEmpty());
        assertUnmodifiable(u.parts());
        assertTrue(u.files("a").isEmpty());
    }
}
