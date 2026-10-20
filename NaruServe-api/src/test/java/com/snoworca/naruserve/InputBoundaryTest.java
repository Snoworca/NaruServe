package com.snoworca.naruserve;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter;
import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientRequest;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import static org.junit.Assert.*;

public class InputBoundaryTest {
    @Test public void integers_FR_DX_003_AC_2_AC_3_AC_4_preserveBoundsSignsDefaultsAndRejectMalformed() {
        // FR-DX-003 AC-2; FR-DX-003 AC-3; FR-DX-003 AC-4
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        RestApp app = com.snoworca.naruserve.testing.TestApp.create(adapter).app();
        app.get("/query", (req,res)->res.send(Integer.toString(req.queryInt("n",17))));
        app.post("/form", (req,res)->res.send(Integer.toString(req.formInt("n",17))));
        app.get("/path/:n", (req,res)->res.send(Integer.toString(req.paramInt("n"))));
        app.listen(0);
        try {
            assertEquals("17",adapter.send(ClientRequest.of("GET","/query")).text());
            assertEquals("17",adapter.send(form("")).text());
            for (String value : new String[]{"2147483647","-2147483648","+1","0","-1"}) {
                String encoded=value.replace("+","%2B");
                String expected=Integer.toString(Integer.parseInt(value));
                assertEquals(expected,adapter.send(ClientRequest.of("GET","/query?n="+encoded)).text());
                assertEquals(expected,adapter.send(form("n="+encoded)).text());
                assertEquals(expected,adapter.send(ClientRequest.of("GET","/path/"+encoded)).text());
            }
            for (String value : new String[]{"2147483648","-2147483649","%201","1%20","1.0",""}) {
                assertEquals(value,400,adapter.send(ClientRequest.of("GET","/query?n="+value)).status());
                assertEquals(value,400,adapter.send(form("n="+value)).status());
                if (!value.isEmpty()) assertEquals(value,400,adapter.send(ClientRequest.of("GET","/path/"+value)).status());
            }
        } finally { app.shutdown(0,TimeUnit.SECONDS); }
    }

    @Test public void requiredHeaderAndFormRejectEmptyAndWhitespace_FR_DX_003_AC_1() {
        // FR-DX-003 AC-1
        InMemoryHttpAdapter adapter = new InMemoryHttpAdapter();
        RestApp app = com.snoworca.naruserve.testing.TestApp.create(adapter).app();
        app.post("/form", (req,res)->res.send(req.requireForm("n")));
        app.get("/header", (req,res)->res.send(req.requireHeader("X-Value")));
        app.listen(0);
        try {
            for(String text:new String[]{""," ","\t"}) {
                assertEquals(400,adapter.send(ClientRequest.of("GET","/header").header("X-Value",text)).status());
                assertEquals(400,adapter.send(form("n="+text.replace(" ","%20").replace("\t","%09"))).status());
            }
            assertEquals(" x ",adapter.send(form("n=%20x%20")).text());
        } finally { app.shutdown(0,TimeUnit.SECONDS); }
    }
    private static ClientRequest form(String body) {
        return ClientRequest.of("POST","/form").header("Content-Type","application/x-www-form-urlencoded")
                .body(body.getBytes(StandardCharsets.UTF_8));
    }
}
