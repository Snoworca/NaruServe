package com.snoworca.naruserve.tinyhttpd;

import static org.junit.Assert.*;
import com.snoworca.naruserve.tinyhttpd.RawTargetSocketTest.Http;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.*;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/** Adapter SPI observation only: these200 responses do not claim framework path acceptance. */
@RunWith(Parameterized.class)
public class RawPolicySocketTest {
    @Parameterized.Parameters(name="{index}: {0} query={1}") public static Collection<Object[]> targets() {
        List<Object[]> rows=new ArrayList<>();
        for(String path:Arrays.asList("/x/a%2Fb","/x/%00","/x/%2e","/x/%2e%2e","/x/%ZZ","/x/a//b",
                "/x/%","/x/%0","/x/%C0%AF","/x/%ED%A0%80","/x/%252F","/x/%2f%2F"))
            for(String query:new String[]{null,"","Q=%2F&x=a+b&bad=%ZZ&x=1??tail"}) rows.add(new Object[]{path,query});
        return rows;
    }
    private final String path,query;
    private final TinyHttpdAdapter adapter=new TinyHttpdAdapter();
    public RawPolicySocketTest(String path,String query){this.path=path;this.query=query;}
    @After public void stop(){Http.stopQuietly(adapter);}
    // FR-ENGINE-012 AC-1 AC-2: RAW bridge must never invoke the rejecting/normalizing Request.getPath accessor.
    @Test(timeout=15000) public void rawPathAndQueryReachSpiWithoutDecodingOrSlashMerging() throws Exception {
        AtomicReference<List<String>> seen=new AtomicReference<>();
        adapter.bind(Http.settings().build(),Http.ok(ex->seen.set(Arrays.asList(ex.rawPath(),ex.rawQuery()))));
        String response=Http.send(adapter.boundPort(),Http.get(path+(query==null?"":"?"+query)));
        assertEquals(response,200,Http.status(response));assertEquals("ok",Http.body(response));
        assertEquals(Arrays.asList(path,query),seen.get());
    }
}
