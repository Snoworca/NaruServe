package com.snoworca.naruserve.parity;
import com.google.gson.*;
import com.snoworca.naruserve.HttpException;
import com.snoworca.naruserve.routing.RoutingConfigException;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
/** Synthetic normalization controls are separate from the actual runtime replay tests. */
public class LanguageErrorCounterpartsTest {
    static JsonObject reference(String id) throws Exception {
        for(JsonElement e:ParityCorpusTest.resource("express5.json").getAsJsonArray("cases"))
            if(id.equals(e.getAsJsonObject().get("id").getAsString()))return e.getAsJsonObject();
        throw new AssertionError(id);
    }
    static String reason(JsonObject r) {
        return r.getAsJsonObject("registration").getAsJsonObject("error").get("message").getAsString()
                .replace("; visit https://git.new/pathToRegexpError for info","");
    }
    static RuntimeReplay.Observation counterpart(JsonObject r,Throwable error,boolean registration) {
        JsonObject v=r.deepCopy();JsonObject e=registration?v.getAsJsonObject("registration").getAsJsonObject("error"):
                v.getAsJsonObject("observation").getAsJsonObject("completion").getAsJsonArray("errors").get(0).getAsJsonObject();
        e.addProperty("name",error.getClass().getName());e.addProperty("message",error.getMessage());
        return new RuntimeReplay.Observation(v,registration?error:null,registration?Collections.emptyList():Collections.singletonList(error));
    }
    static void rejected(String id,JsonObject r,RuntimeReplay.Observation v,String field) {
        try {LanguageErrorCounterparts.compare(id,r,v);fail("accepted invalid counterpart "+field);}
        catch(AssertionError e){assertTrue(e.getMessage(),e.getMessage().contains(id));assertTrue(e.getMessage(),e.getMessage().contains(field));
            assertTrue("raw reference retained",e.getMessage().contains("raw reference="));assertTrue("raw Java retained",e.getMessage().contains("raw Java="));}
    }
    // OPS-ROUTE-004 AC-7: only the exact informational suffix is optional; reference stays immutable.
    @Test public void registrationCounterpartPreservesOriginalsAndAcceptsExactReason() throws Exception {
        for(String id:new String[]{"registration.unnamed-wildcard","registration.inline-regex"}) {
            JsonObject r=reference(id);String before=r.toString();
            RuntimeReplay.Observation v=counterpart(r,new RoutingConfigException(reason(r)),true);String javaBefore=v.value.toString();
            LanguageErrorCounterparts.compare(id,r,v);assertEquals(before,r.toString());assertEquals(javaBefore,v.value.toString());
        }
    }
    // OPS-ROUTE-004 AC-7
    @Test public void registrationWrongFamilyFails() throws Exception {
        String id="registration.inline-regex";JsonObject r=reference(id);rejected(id,r,counterpart(r,new IllegalArgumentException(reason(r)),true),"registration.error.name");
    }
    // OPS-ROUTE-004 AC-7: index, source and reason are not normalized away.
    @Test public void registrationChangedMeaningAndHelpUrlFail() throws Exception {
        String id="registration.inline-regex";JsonObject r=reference(id);String s=reason(r);
        for(String changed:new String[]{s.replace("index 6","index 7"),s.replace("Unexpected (","Unexpected ["),s.replace("/x/","/y/"),s+"; visit https://other.invalid for info"})
            rejected(id,r,counterpart(r,new RoutingConfigException(changed),true),"registration.error.message");
    }
    // OPS-ROUTE-004 AC-7
    @Test public void exactPlainRuntimeErrorsAreExplicitCounterparts() throws Exception {
        for(String id:new String[]{"error.thrown","async.rejected"}) {
            JsonObject r=reference(id);String msg=id.equals("error.thrown")?"boom":"rejected";
            LanguageErrorCounterparts.compare(id,r,counterpart(r,new RuntimeException(msg),false));
        }
    }
    // OPS-ROUTE-004 AC-7: arbitrary subclasses and changed messages are not plain scenario Errors.
    @Test public void thrownWrongFamilyOrMessageFails() throws Exception {
        String id="error.thrown";JsonObject r=reference(id);
        rejected(id,r,counterpart(r,new IllegalStateException("boom"),false),"completion.errors[0].name");
        rejected(id,r,counterpart(r,new RuntimeException("different"),false),"completion.errors[0].message");
    }
    // OPS-ROUTE-004 AC-7: URI counterpart preserves its raw offending value and HTTP status.
    @Test public void decodeCounterpartAcceptsOnlySpecific400WithExactRawValue() throws Exception {
        String id="raw.malformed-escape";JsonObject r=reference(id);
        LanguageErrorCounterparts.compare(id,r,counterpart(r,new HttpException(400,"Failed to decode param '%ZZ'"),false));
        rejected(id,r,counterpart(r,new HttpException(422,"Failed to decode param '%ZZ'"),false),"completion.errors[0].name");
        rejected(id,r,counterpart(r,new RuntimeException("Failed to decode param '%ZZ'"),false),"completion.errors[0].name");
        rejected(id,r,counterpart(r,new HttpException(400,"Failed to decode param '%XY'"),false),"completion.errors[0].message");
        rejected(id,r,counterpart(r,new HttpException(400,"other bad input"),false),"completion.errors[0].name");
    }
    // OPS-ROUTE-004 AC-2 AC-7: language mapping never excuses a response or trace difference.
    @Test public void responseBodyStatusHeadersAndTraceRemainStrict() throws Exception {
        String id="error.thrown";JsonObject r=reference(id);
        for(String field:new String[]{"body","status","headers","trace"}) {
            RuntimeReplay.Observation v=counterpart(r,new RuntimeException("boom"),false);
            JsonObject o=v.value.getAsJsonObject("observation");
            if(field.equals("status"))o.addProperty(field,501);else if(field.equals("body"))o.addProperty(field,"wrong");
            else if(field.equals("headers"))o.getAsJsonObject(field).addProperty("content-type","text/html");else o.getAsJsonArray(field).remove(0);
            rejected(id,r,v,"observation."+field);
        }
    }
    // OPS-ROUTE-004 AC-7: captured JSON must agree with the actual Throwable, not a forged friendly name.
    @Test public void rawJavaMetadataCannotBeForged() throws Exception {
        String id="error.thrown";JsonObject r=reference(id);RuntimeReplay.Observation v=counterpart(r,new RuntimeException("boom"),false);
        v.value.getAsJsonObject("observation").getAsJsonObject("completion").getAsJsonArray("errors").get(0).getAsJsonObject().addProperty("name","Error");
        rejected(id,r,v,"completion.errors[0].name");
    }
}
