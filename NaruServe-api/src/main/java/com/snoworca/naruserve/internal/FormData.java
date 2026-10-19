package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.HttpException;
import com.snoworca.naruserve.UploadFile;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** One parsed form or multipart body: text fields in body order and every part in body order. */
// @req FR-MULTIPART-003
final class FormData {

    static final FormData EMPTY = new FormData(Collections.<String, List<String>>emptyMap(),
            Collections.<DefaultUploadFile>emptyList());

    private final Map<String, List<String>> text;
    private final List<UploadFile> parts;
    private final List<DefaultUploadFile> uploads;

    private FormData(Map<String, List<String>> text, List<DefaultUploadFile> uploads) {
        this.text = text;
        this.uploads = uploads;
        this.parts = Collections.<UploadFile>unmodifiableList(new ArrayList<UploadFile>(uploads));
    }

    /** The decoded pairs of an urlencoded body; no parts. */
    // @req FR-MULTIPART-003
    static FormData urlencoded(Map<String, List<String>> fields) {
        return new FormData(fields, Collections.<DefaultUploadFile>emptyList());
    }

    /**
     * Every part of a multipart body; text parts (no filename) decode as strict UTF-8, else HttpException(400). A
     * failure releases every part.
     */
    // @req FR-MULTIPART-003
    static FormData multipart(List<DefaultUploadFile> parts) {
        boolean ok = false;
        try {
            Map<String, List<String>> text = new LinkedHashMap<>();
            for (DefaultUploadFile p : parts) {
                if (p.filename() == null) {
                    List<String> values = text.get(p.name());
                    if (values == null) {
                        values = new ArrayList<>(1);
                        text.put(p.name(), values);
                    }
                    values.add(utf8(p));
                }
            }
            FormData d = new FormData(text, parts);
            ok = true;
            return d;
        } finally {
            if (!ok) {
                for (DefaultUploadFile p : parts) {
                    p.release();
                }
            }
        }
    }

    private static String utf8(DefaultUploadFile p) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = p.inputStream()) {
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) >= 0) {
                out.write(b, 0, n);
            }
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(out.toByteArray()))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new HttpException(400, "multipart text field is not UTF-8", e);
        } catch (IOException e) {
            throw new HttpException(400, null, e); // text parts are in memory; kept for the checked signature
        }
    }

    // @req FR-MULTIPART-003
    @Nullable String firstText(String name) {
        List<String> values = text.get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    // @req FR-MULTIPART-003
    @Nullable UploadFile firstFile(String name) {
        for (DefaultUploadFile p : uploads) {
            if (p.filename() != null && p.name().equals(name)) {
                return p;
            }
        }
        return null;
    }

    /** A new unmodifiable list on each call. */
    // @req FR-MULTIPART-003
    List<UploadFile> files(String name) {
        List<UploadFile> out = new ArrayList<>();
        for (DefaultUploadFile p : uploads) {
            if (p.filename() != null && p.name().equals(name)) {
                out.add(p);
            }
        }
        return Collections.unmodifiableList(out);
    }

    /** The same unmodifiable list on every call. */
    List<UploadFile> parts() {
        return parts;
    }
}
