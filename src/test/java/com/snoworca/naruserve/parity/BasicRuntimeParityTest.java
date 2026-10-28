package com.snoworca.naruserve.parity;

import com.google.gson.*;
import com.snoworca.naruserve.Request;
import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.testing.TestApp;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real current NaruServe replay. Only these implemented cases count as runtime parity evidence. */
public class BasicRuntimeParityTest {
    private static JsonObject event(Request request,String handler,boolean param) {
        JsonObject event=new JsonObject(),params=new JsonObject();
        if(param && request.param("id")!=null) params.addProperty("id",request.param("id"));
        event.addProperty("handler",handler);event.add("params",params);
        event.addProperty("path",request.path());event.addProperty("url",request.rawPath());
        // These fixtures are deliberately unmounted and query-free; mounted views are a later feature adapter.
        event.addProperty("baseUrl","");event.addProperty("originalUrl",request.rawPath());return event;
    }
    private static JsonObject[] capture(String id,String method,String path,BiConsumer<RestApp,JsonArray> setup) throws Exception {
        JsonObject corpus=ParityCorpusTest.resource("express5.json");
        ParityCorpus.validate(corpus,ParityCorpusTest.resource("reference-lock.json"),ParityCorpusTest.generatorHash());
        JsonObject expected=null;
        for(JsonElement candidate:corpus.getAsJsonArray("cases")) if(id.equals(candidate.getAsJsonObject().get("id").getAsString())) expected=candidate.getAsJsonObject();
        assertNotNull("missing reference case "+id,expected);
        TestApp fixture=TestApp.create();JsonArray trace=new JsonArray();
        try {
            JsonObject registration=new JsonObject();
            try {setup.accept(fixture.app(),trace);registration.addProperty("success",true);registration.add("error",JsonNull.INSTANCE);}
            catch(RuntimeException error) {registration.addProperty("success",false);JsonObject e=new JsonObject();e.addProperty("name",error.getClass().getSimpleName());e.addProperty("message",error.getMessage());registration.add("error",e);}
            JsonObject actual=new JsonObject();actual.addProperty("id",id);actual.add("feature",expected.get("feature"));actual.add("registration",registration);
            JsonObject request=new JsonObject();request.addProperty("method",method);request.addProperty("rawPath",path);actual.add("request",request);
            ClientResponse response=fixture.send(method,path);JsonObject observation=new JsonObject(),headers=new JsonObject();
            for(String name:new String[]{"allow","content-type","content-length","location","x-handler"}) headers.addProperty(name,ParityCorpus.canonicalHeader(name,response.header(name)));
            observation.addProperty("status",response.status());observation.add("headers",headers);observation.addProperty("body",response.text());observation.add("trace",trace);
            JsonObject completion=new JsonObject();completion.addProperty("state",response.abnormallyClosed()?"aborted":"finished");completion.add("errors",new JsonArray());observation.add("completion",completion);actual.add("observation",observation);
            return new JsonObject[]{expected,actual};
        } finally {fixture.app().shutdown(0,TimeUnit.SECONDS);}
    }
    private static void replay(String id,String method,String path,BiConsumer<RestApp,JsonArray> setup) throws Exception {
        JsonObject[] pair=capture(id,method,path,setup);ParityCorpus.compare(id,pair[0],pair[1]);
    }
    // OPS-ROUTE-004 AC-2 AC-3 AC-4: the same real adapter observation must pass first, then reject a changed body.
    @Test public void changedRealAdapterObservationFailsWithCaseAndField() throws Exception {
        JsonObject[] pair=capture("literal.get","GET","/a",(app,trace)->app.get("/a",(req,res)->{trace.add(event(req,"literal",false));res.send("literal");}));
        ParityCorpus.compare("literal.get",pair[0],pair[1]);
        pair[1].getAsJsonObject("observation").addProperty("body","unexpected actual body");
        try {ParityCorpus.compare("literal.get",pair[0],pair[1]);fail("changed real response accepted");}
        catch(AssertionError error) {assertTrue(error.getMessage(),error.getMessage().contains("literal.get"));assertTrue(error.getMessage(),error.getMessage().contains("$.observation.body"));}
    }
    // OPS-ROUTE-004 AC-2 AC-3 AC-6: actual runtime execution, not expected-versus-expected comparison.
    @Test public void literalGetMatchesRecordedExpressResponseAndTrace() throws Exception {
        replay("literal.get","GET","/a",(app,trace)->app.get("/a",(req,res)->{trace.add(event(req,"literal",false));res.send("literal");}));
    }
    // OPS-ROUTE-004 AC-2 AC-3 AC-6
    @Test public void literalPostMatchesRecordedExpressStatusBodyHeadersAndTrace() throws Exception {
        replay("literal.post","POST","/a",(app,trace)->app.post("/a",(req,res)->{trace.add(event(req,"posted",false));res.status(201).send("posted");}));
    }
    // OPS-ROUTE-004 AC-2 AC-3 AC-6
    @Test public void basicParameterMatchesRecordedExpressParamsAndScope() throws Exception {
        replay("param.basic","GET","/users/42",(app,trace)->app.get("/users/:id",(req,res)->{trace.add(event(req,"user",true));res.send("id="+req.param("id"));}));
    }
}
