package com.snoworca.naruserve.routing;

import org.junit.Test;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** New API invoked reflectively so the test-first commit executes RED without a production stub. */
public class HttpPathPatternTest {
    static Object compile(String s, boolean end, boolean sensitive, boolean trailing) throws Exception {
        try { return Class.forName("com.snoworca.naruserve.routing.HttpPathPattern")
                .getMethod("compile", String.class, boolean.class, boolean.class, boolean.class)
                .invoke(null, s, end, sensitive, trailing); }
        catch (InvocationTargetException e) { throw (Exception) e.getCause(); }
    }
    static Object match(Object p, String input) throws Exception {
        return p.getClass().getMethod("match", String.class).invoke(p, input);
    }
    @SuppressWarnings("unchecked") static Map<String,List<String>> captures(Object m) throws Exception {
        return (Map<String,List<String>>) m.getClass().getMethod("captures").invoke(m);
    }
    static String path(Object m) throws Exception {
        return (String) m.getClass().getMethod("matchedPath").invoke(m);
    }
    static String decode(String s) { return new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8); }

    @Test public void pinnedRawCorpus() throws Exception { // FR-ROUTE-018 AC-1 AC-3 AC-4
        InputStream stream=getClass().getResourceAsStream("http-path-pattern-v8.4.2.tsv");
        assertNotNull(stream);
        int count=0;
        try (BufferedReader reader=new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            for(String line; (line=reader.readLine())!=null;) {
                String[] c=line.split("\t",-1);
                String context="vector "+(++count)+" pattern="+decode(c[0])+" input="+decode(c[4]);
                Object actual=match(compile(decode(c[0]),c[1].equals("1"),c[2].equals("1"),c[3].equals("1")),decode(c[4]));
                if(c[5].equals("0")) { assertNull(context,actual); continue; }
                assertNotNull(context,actual);
                assertEquals(context,decode(c[6]),path(actual));
                Map<String,List<String>> expected=new LinkedHashMap<>();
                if(!c[7].isEmpty()) for(String entry:c[7].split(";")) {
                    String[] kv=entry.split(":",-1); List<String> values=new ArrayList<>();
                    for(String value:kv[1].split(",",-1)) values.add(decode(value));
                    expected.put(decode(kv[0]),values);
                }
                assertEquals(context,expected,captures(actual));
            }
        }
        assertEquals(257,count);
    }
    @Test public void grammarErrorsHaveSourcePositions() throws Exception { // FR-ROUTE-018 AC-2
        for(String s:Arrays.asList("/:x:y","/*a*b","/:x{*y}","/:\"\"","/:\"unterminated","/:","/*","/x\\","/{a","/a}","/a(b)","/a[b]","/a+","/a?","/a!")) {
            try { compile(s,true,false,true); fail("accepted "+s); }
            catch(RoutingConfigException e) { assertTrue(e.getMessage(),e.getMessage().contains("index")); assertTrue(e.getMessage(),e.getMessage().contains(s)); }
        }
    }
    @Test public void unicodeAndDuplicateNames() throws Exception { // FR-ROUTE-018 AC-1 AC-2
        Object m=match(compile("/:한글/:𐐀/:a/:a",true,false,true),"/one/two/old/new");
        assertEquals(Arrays.asList("one"),captures(m).get("한글"));
        assertEquals(Arrays.asList("two"),captures(m).get("𐐀"));
        assertEquals(Arrays.asList("new"),captures(m).get("a"));
        assertEquals(Arrays.asList("old"),captures(match(compile("/:a{/:a}",true,false,true),"/old")).get("a"));
        // Unicode 16.0 ID_Start added after both Java8 and Java17 Unicode tables.
        assertNotNull(match(compile("/:\uA7CB",true,true,false),"/v"));
    }
    @Test public void caseFoldingUsesPinnedJavascriptNonUnicodeRules() throws Exception { // FR-ROUTE-018 AC-3
        assertNotNull(match(compile("/éΣ",true,false,false),"/Éς"));
        assertNull(match(compile("/k",true,false,false),"/K"));
        assertNull(match(compile("/s",true,false,false),"/ſ"));
        assertNull(match(compile("/𐐀",true,false,false),"/𐐨"));
        assertNull(match(compile("/é",true,true,false),"/É"));
    }
    @Test public void rawWildcardsAndImmutableResults() throws Exception { // FR-ROUTE-018 AC-1 AC-4 AC-6
        Object p=compile("/files/*parts",true,true,true);
        Object m=match(p,"/files/a%2Fb//%ZZ/");
        assertEquals(Arrays.asList("a%2Fb","","%ZZ",""),captures(m).get("parts"));
        try { captures(m).put("new",Collections.singletonList("v")); fail(); } catch(UnsupportedOperationException expected) { }
        try { captures(m).get("parts").add("v"); fail(); } catch(UnsupportedOperationException expected) { }
        assertNull(match(compile("/%61",true,true,false),"/a"));
    }
    @Test public void prefixBoundariesAndNoNormalization() throws Exception { // FR-ROUTE-018 AC-3
        Object p=compile("/Ab",false,false,true);
        assertEquals("/aB",path(match(p,"/aB//x")));
        assertNull(match(p,"/abc"));
        assertNull(match(compile("/a",true,true,false),"/a/"));
        assertNull(match(compile("/a",true,true,true),"/a//"));
        assertNull(match(compile("/a/b",true,true,true),"/a//b"));
    }
    @Test public void optionalExpansionBound() throws Exception { // FR-ROUTE-018 AC-5
        StringBuilder s=new StringBuilder("/x");
        for(int i=0;i<8;i++)s.append("{a}");
        assertNotNull(match(compile(s.toString(),true,true,false),"/x"));
        s.append("{a}");
        try { compile(s.toString(),true,true,false); fail(); }
        catch(RoutingConfigException e) { assertTrue(e.getMessage(),e.getMessage().contains("combinations")); }
    }
    @Test public void compiledPatternCanBeShared() throws Exception { // FR-ROUTE-018 AC-6
        final Object p=compile("/u/:id{/*rest}",true,false,true);
        ExecutorService pool=Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> tasks=new ArrayList<>();
            for(int i=0;i<500;i++) { final String id="u"+i;
                tasks.add(pool.submit(()->{ try { assertEquals(Arrays.asList(id),captures(match(p,"/u/"+id+"/a/b")).get("id")); }
                    catch(Exception e) { throw new AssertionError(e); } })); }
            for(Future<?> f:tasks)f.get(10,TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
    }
    @Test public void adversarialMatcherHasProcessDeadline() throws Exception { // FR-ROUTE-018 AC-5
        String cp=System.getProperty("java.class.path")+File.pathSeparator+
            getClass().getProtectionDomain().getCodeSource().getLocation().getPath()+File.pathSeparator+
            Class.forName("com.snoworca.naruserve.routing.HttpPathPattern").getProtectionDomain().getCodeSource().getLocation().getPath();
        File output=File.createTempFile("http-pattern-probe-",".log");
        Process child=new ProcessBuilder(new File(System.getProperty("java.home"),"bin/java").getPath(),"-cp",cp,Probe.class.getName())
                .redirectErrorStream(true).redirectOutput(output).start();
        boolean done=child.waitFor(20,TimeUnit.SECONDS);
        if(!done)child.destroyForcibly();
        assertTrue("matcher exceeded deadline; log "+output,done);
        assertEquals("probe failed; log "+output,0,child.exitValue());
    }
    public static class Probe {
        public static void main(String[] args) throws Exception {
            char[] chars=new char[100000]; Arrays.fill(chars,'a'); String longValue=new String(chars);
            for(String pattern:Arrays.asList("/:a-:b", "/*a-:b", "/:a-*b", "/*a-*b", "/*a/x/*b/y/*c", "/:a.:b.:c")) {
                Object p=compile(pattern,true,false,true);
                match(p,"/"+longValue+"!");
                match(p,"/"+longValue+"-"+longValue+"!");
            }
            System.out.println("adversarial probe passed");
        }
    }
}
