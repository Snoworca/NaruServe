package com.snoworca.naruserve;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import com.snoworca.naruserve.testing.TestApp;
import org.junit.Test;
import static org.junit.Assert.*;

/** FR-DX-003 AC-1..4: input helpers distinguish missing, malformed and valid values. */
public class InputConvenienceTest {
    @Test public void queryNumbersDefaultOnlyWhenMissingAndRejectMalformedOrOverflow() {
        InMemoryHttpAdapter a = new InMemoryHttpAdapter(); TestApp t = TestApp.create(a);
        t.app().get("/page", (req,res) -> res.send(Integer.toString(req.queryInt("page", 1))));
        t.start();
        assertEquals("1", a.send(ClientRequest.of("GET", "/page")).text());
        assertEquals("-2", a.send(ClientRequest.of("GET", "/page?page=-2")).text());
        for (String value : new String[]{"", "abc", "2147483648", "1.5", "%20"})
            assertEquals(value,400,a.send(ClientRequest.of("GET", "/page?page="+value)).status());
    }
    @Test public void requiredTextRejectsBlankAndReturnsOriginalValue() {
        InMemoryHttpAdapter a = new InMemoryHttpAdapter(); TestApp t = TestApp.create(a);
        t.app().get("/required", (req,res) -> res.send(req.requireQuery("name")+req.requireHeader("X-Token")));
        t.start();
        assertEquals(400,a.send(ClientRequest.of("GET", "/required")).status());
        assertEquals(400,a.send(ClientRequest.of("GET", "/required?name=%20")).status());
        assertEquals(400,a.send(ClientRequest.of("GET", "/required?name=kim")).status());
        assertEquals("kimt",a.send(ClientRequest.of("GET", "/required?name=kim").header("X-Token","t")).text());
    }
    @Test public void pathAndFormIntegerUseBadRequestInsteadOfServerError() {
        InMemoryHttpAdapter a = new InMemoryHttpAdapter(); TestApp t = TestApp.create(a);
        t.app().post("/items/:id", (req,res) -> res.send(req.paramInt("id")+":"+req.formInt("count",2)+":"+req.requireForm("name")));
        t.start();
        assertEquals("12:2:kim",a.send(ClientRequest.of("POST","/items/12").header("Content-Type","application/x-www-form-urlencoded").body("name=kim".getBytes(java.nio.charset.StandardCharsets.UTF_8))).text());
        assertEquals(400,a.send(ClientRequest.of("POST","/items/abc").header("Content-Type","application/x-www-form-urlencoded").body("name=kim".getBytes(java.nio.charset.StandardCharsets.UTF_8))).status());
        assertEquals(400,a.send(ClientRequest.of("POST","/items/1").header("Content-Type","application/x-www-form-urlencoded").body("name=kim&count=abc".getBytes(java.nio.charset.StandardCharsets.UTF_8))).status());
    }
    @Test public void missingRequiredFileIsBadRequest() {
        InMemoryHttpAdapter a = new InMemoryHttpAdapter(); TestApp t = TestApp.create(a);
        t.app().post("/upload",(req,res)->res.send(req.requireFile("file").filename()));t.start();
        assertEquals(400,a.send(ClientRequest.of("POST","/upload")).status());
    }
    @Test public void requiredFilesRejectEmptyAndAcceptActualUploadedBytes() {
        InMemoryHttpAdapter a = new InMemoryHttpAdapter(); TestApp t = TestApp.create(a);
        t.app().post("/upload",(req,res)->res.send(Long.toString(req.requireFile("file").size())));t.start();
        for (String payload : new String[]{"", "hello"}) {
            String multipart="--demo\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.txt\"\r\nContent-Type: text/plain\r\n\r\n"+payload+"\r\n--demo--\r\n";
            InMemoryHttpAdapter.ClientResponse response=a.send(ClientRequest.of("POST","/upload")
                    .header("Content-Type","multipart/form-data; boundary=demo")
                    .body(multipart.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            assertEquals(payload.isEmpty()?400:200,response.status());
            if (!payload.isEmpty()) assertEquals("5",response.text());
        }
    }

}
