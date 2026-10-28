package com.snoworca.naruserve.parity;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import org.junit.Test;
import static org.junit.Assert.*;

/** Comparator contract tests; synthetic values below are test controls, not reference observations. */
public class ParityCorpusTest {
    private static JsonObject object(String json) { return JsonParser.parseString(json).getAsJsonObject(); }
    private static JsonObject observation() {
        return object("{\"registration\":{\"success\":true,\"error\":null},\"request\":{\"method\":\"GET\",\"rawPath\":\"/users/42\"},\"observation\":{\"status\":200,\"headers\":{\"allow\":null,\"content-type\":\"text/plain; charset=utf-8\"},\"body\":\"id=42\",\"trace\":[{\"handler\":\"user\",\"params\":{\"id\":\"42\",\"parts\":[\"a\",\"b\"]},\"path\":\"/users/42\",\"baseUrl\":\"\"}],\"completion\":{\"state\":\"finished\",\"errors\":[]}}}");
    }
    private static void mismatch(JsonObject expected, JsonObject actual, String field) {
        try { ParityCorpus.compare("contract-case", expected, actual); fail("comparison accepted " + field); }
        catch (AssertionError error) { assertTrue(error.getMessage(), error.getMessage().contains("contract-case")); assertTrue(error.getMessage(), error.getMessage().contains(field)); }
    }
    // OPS-ROUTE-004 AC-2 AC-4
    @Test public void comparatorReportsStatusHeaderBodyAndEveryChangedField() {
        JsonObject e=observation(), a=e.deepCopy(), o=a.getAsJsonObject("observation");
        o.addProperty("status",201); o.getAsJsonObject("headers").addProperty("allow","GET"); o.addProperty("body","changed");
        try { ParityCorpus.compare("multi-case",e,a); fail("accepted changes"); }
        catch (AssertionError error) { for(String field:new String[]{"multi-case","$.observation.status","$.observation.headers.allow","$.observation.body"}) assertTrue(error.getMessage(),error.getMessage().contains(field)); }
    }
    // OPS-ROUTE-004 AC-2 AC-4
    @Test public void unchangedValuesPassAndObjectMemberOrderDoesNotMatter() {
        ParityCorpus.compare("same",object("{\"a\":1,\"b\":2}"),object("{\"b\":2,\"a\":1}"));
    }
    // OPS-ROUTE-004 AC-2 AC-4
    @Test public void parameterValuesAbsenceNullAndListShapeAreDistinct() {
        JsonObject e=observation(), a=e.deepCopy();
        JsonObject params=a.getAsJsonObject("observation").getAsJsonArray("trace").get(0).getAsJsonObject().getAsJsonObject("params");
        params.addProperty("id","43"); mismatch(e,a,"$.observation.trace[0].params.id");
        a=e.deepCopy(); a.getAsJsonObject("observation").getAsJsonArray("trace").get(0).getAsJsonObject().getAsJsonObject("params").add("optional",JsonNull.INSTANCE); mismatch(e,a,"$.observation.trace[0].params.optional");
        a=e.deepCopy(); a.getAsJsonObject("observation").getAsJsonArray("trace").get(0).getAsJsonObject().getAsJsonObject("params").remove("id"); mismatch(e,a,"$.observation.trace[0].params.id");
        a=e.deepCopy(); a.getAsJsonObject("observation").getAsJsonArray("trace").get(0).getAsJsonObject().getAsJsonObject("params").addProperty("parts","a/b"); mismatch(e,a,"$.observation.trace[0].params.parts");
    }
    // OPS-ROUTE-004 AC-2 AC-4
    @Test public void traceOrderScopeAndCompletionErrorsAreCompared() {
        JsonObject e=observation(), a=e.deepCopy(); a.getAsJsonObject("observation").getAsJsonArray("trace").get(0).getAsJsonObject().addProperty("baseUrl","/api"); mismatch(e,a,"$.observation.trace[0].baseUrl");
        a=e.deepCopy(); a.getAsJsonObject("observation").getAsJsonArray("trace").get(0).getAsJsonObject().addProperty("path","/other"); mismatch(e,a,"$.observation.trace[0].path");
        a=e.deepCopy(); a.getAsJsonObject("observation").getAsJsonArray("trace").add(object("{\"handler\":\"unexpected\"}")); mismatch(e,a,"$.observation.trace.length");
        a=e.deepCopy(); a.getAsJsonObject("observation").getAsJsonObject("completion").addProperty("state","aborted"); mismatch(e,a,"$.observation.completion.state");
        a=e.deepCopy(); a.getAsJsonObject("observation").getAsJsonObject("completion").getAsJsonArray("errors").add("failure"); mismatch(e,a,"$.observation.completion.errors.length");
        mismatch(object("{\"trace\":[\"first\",\"second\"]}"),object("{\"trace\":[\"second\",\"first\"]}"),"$.trace[0]");
    }
    // OPS-ROUTE-004 AC-2 AC-4
    @Test public void registrationAndRawRequestChangesFail() {
        JsonObject e=observation(), a=e.deepCopy(); a.getAsJsonObject("registration").addProperty("success",false); mismatch(e,a,"$.registration.success");
        a=e.deepCopy(); a.getAsJsonObject("request").addProperty("rawPath","/users/%34%32"); mismatch(e,a,"$.request.rawPath");
        a=e.deepCopy(); a.getAsJsonObject("request").addProperty("method","POST"); mismatch(e,a,"$.request.method");
    }
    static JsonObject resource(String name) throws IOException {
        try(InputStream stream=ParityCorpusTest.class.getResourceAsStream("/parity/"+name)) {
            assertNotNull("missing committed resource "+name,stream);
            try(Reader reader=new InputStreamReader(stream,StandardCharsets.UTF_8)) { return JsonParser.parseReader(reader).getAsJsonObject(); }
        }
    }
    static String generatorHash() throws Exception {
        Path root=Paths.get(System.getProperty("naruserve.rootDir",".")); byte[] bytes=Files.readAllBytes(root.resolve("tools/parity/generate-express-corpus.cjs"));
        StringBuilder hex=new StringBuilder(); for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes)) hex.append(String.format("%02x",b&255)); return hex.toString();
    }
    // OPS-ROUTE-004 AC-1 AC-3 AC-5
    @Test public void committedCorpusHasPinnedProvenanceAndJavaPatternBoundary() throws Exception {
        JsonObject corpus=resource("express5.json"); ParityCorpus.validate(corpus,resource("reference-lock.json"),generatorHash());
        assertTrue(corpus.getAsJsonArray("cases").size()>=20);
        for(JsonElement entry:corpus.getAsJsonArray("cases")) { JsonObject c=entry.getAsJsonObject(); assertTrue(c.has("registration")); assertTrue(c.has("request")); assertTrue(c.has("observation")); }
    }
    // OPS-ROUTE-004 AC-1 AC-4 AC-5
    @Test public void staleReferenceVersionHashGeneratorAndBoundaryFail() throws Exception {
        JsonObject original=resource("express5.json"), lock=resource("reference-lock.json");
        JsonObject changed=original.deepCopy(); changed.getAsJsonObject("reference").addProperty("express","5.0.0"); invalid(changed,lock,"reference");
        changed=original.deepCopy(); changed.getAsJsonObject("reference").getAsJsonObject("packages").getAsJsonObject("express").addProperty("packageSha256",repeat('0',64)); invalid(changed,lock,"reference");
        changed=original.deepCopy(); changed.addProperty("generatorSha256",repeat('0',64)); invalid(changed,lock,"generatorSha256");
        changed=original.deepCopy(); changed.getAsJsonObject("boundary").addProperty("regexEngine","ECMAScript"); invalid(changed,lock,"boundary.regexEngine");
        changed=original.deepCopy(); changed.getAsJsonObject("boundary").addProperty("ecmascriptRegExpEquivalent",true); invalid(changed,lock,"boundary.ecmascriptRegExpEquivalent");
        changed=original.deepCopy(); changed.addProperty("schemaVersion",2); invalid(changed,lock,"schemaVersion");
    }
    private static String repeat(char c,int n) { char[] a=new char[n];java.util.Arrays.fill(a,c);return new String(a); }
    private static void invalid(JsonObject corpus,JsonObject lock,String field) throws Exception {
        try { ParityCorpus.validate(corpus,lock,generatorHash());fail("stale metadata accepted"); }
        catch(AssertionError error) {assertTrue(error.getMessage(),error.getMessage().contains(field));}
    }
}
