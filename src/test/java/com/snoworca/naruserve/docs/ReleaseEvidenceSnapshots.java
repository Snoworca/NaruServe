package com.snoworca.naruserve.docs;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.*;
import static org.junit.Assert.*;

/** Test-only version boundary. A pinned historical assertion never establishes a current release. */
final class ReleaseEvidenceSnapshots {
    static final String PIN="2498edaa643593474f830259b8cf3c31a9590702";
    static final String HISTORICAL="docs/release/test-results.md", HISTORICAL_SCAN="docs/release/vulnerability-scan.md";
    static final String CURRENT="docs/release/0.10.0-test-results.md", CURRENT_SCAN="docs/release/0.10.0-vulnerability-scan.md";
    private ReleaseEvidenceSnapshots() {}

    private static File repository() { return new File(System.getProperty("naruserve.rootDir")); }
    private static String read(File root,String path,String field)throws IOException {
        File file=new File(root,path);
        String base=root.getCanonicalPath()+File.separator;
        assertTrue(field+" path must stay inside checkout",file.getCanonicalPath().startsWith(base));
        assertTrue(field+" file missing: "+path,file.isFile());
        return new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8);
    }
    private static String git(String field,String... args)throws Exception {
        List<String> command=new ArrayList<>(Arrays.asList("git","--no-optional-locks","-C",repository().getAbsolutePath()));
        command.addAll(Arrays.asList(args));
        Process process=new ProcessBuilder(command).redirectErrorStream(true).start();
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(InputStream input=process.getInputStream()) {byte[] buffer=new byte[4096];int size;while((size=input.read(buffer))!=-1)bytes.write(buffer,0,size);}
        assertEquals(field+" unavailable: "+new String(bytes.toByteArray(),StandardCharsets.UTF_8),0,process.waitFor());
        return new String(bytes.toByteArray(),StandardCharsets.UTF_8);
    }
    private static boolean historical(String path,String commit,boolean scan) {
        String old=scan?HISTORICAL_SCAN:HISTORICAL, current=scan?CURRENT_SCAN:CURRENT;
        assertTrue("record path is not an approved versioned record: "+path,path.equals(old)||path.equals(current));
        if(path.equals(old)) {assertEquals("historical commit",PIN,commit);return true;}
        assertNull("current record cannot use historical commit",commit);return false;
    }
    private static String record(File root,String path,String commit,boolean scan)throws Exception {
        boolean old=historical(path,commit,scan);
        String actual=read(root,path,old?"historical bytes":"current evidence");
        if(old)assertEquals("historical bytes changed: "+path,git("commit","show",PIN+":"+path),actual);
        return actual;
    }
    // OPS-REL-008 AC-2 AC-3; OPS-REL-009 AC-2
    static void method(File root,String recordPath,String commit,String reference)throws Exception {
        record(root,recordPath,commit,false);
        int hash=reference.indexOf('#');
        assertTrue("method reference must be path#method: "+reference,hash>0 && hash==reference.lastIndexOf('#') && hash<reference.length()-1);
        String path=reference.substring(0,hash),name=reference.substring(hash+1);
        assertTrue("source must be a relative Java/Kotlin test path: "+path,
                !path.startsWith("/")&&!path.contains("\\")&&!path.contains(":")&&!Arrays.asList(path.split("/")).contains("..")
                &&(path.endsWith(".java")||path.endsWith(".kt")));
        String source=commit==null?read(root,path,"source"):git("source","show",PIN+":"+path);
        assertTrue("method missing: "+reference,ReleaseResultsDocsTest.declares(source,name));
    }
    // OPS-REL-008 AC-2; OPS-REL-009 AC-1 AC-2
    static void scan(File root,String path,String commit,List<String> runtime)throws Exception {
        String report=record(root,path,commit,true);
        List<String> actual=com.snoworca.naruserve.build.ScanReportTest.dependencySection(report);
        if(commit==null)assertEquals("current scan dependencies differ from current runtime",runtime,actual);
        else assertEquals("historical dependencies",com.snoworca.naruserve.build.ScanReportTest.dependencySection(git("commit","show",PIN+":"+path)),actual);
    }
    private static void successfulRun(String results)throws Exception {
        Matcher run=Pattern.compile("(?m)^- run full-suite: command=(.+?); date=(\\d{4}-\\d{2}-\\d{2}); result=(.+)$").matcher(results);
        assertTrue("current run witness missing",run.find());
        String command=run.group(1),value=run.group(3);
        assertTrue("current run wrapper command",command.startsWith("./gradlew "));
        assertFalse("current run witness duplicated",run.find());
        assertTrue("current run must exit0",Pattern.compile("(?:^|;\\s*)exit=0(?:;|$)").matcher(value).find());
        Matcher commit=Pattern.compile("(?:^|;\\s*)commit=([0-9a-f]{40})(?:;|$)").matcher(value);
        assertTrue("current run commit missing/unusable",commit.find());
        String revision=commit.group(1);
        assertNotEquals("current run cannot reuse historical release commit",PIN,revision);
        git("current run commit","cat-file","-e",revision+"^{commit}");
        Matcher count=Pattern.compile("(?:^|;\\s*)tests=([0-9]+)(?:;|$)").matcher(value);
        assertTrue("current run test count missing",count.find());
        assertTrue("current run needs positive test count",Long.parseLong(count.group(1))>0);
        for(String field:new String[]{"failures","errors","skipped"})
            assertTrue("current run "+field+" must be0",Pattern.compile("(?:^|;\\s*)"+field+"=0(?:;|$)").matcher(value).find());
    }
    // OPS-REL-009 AC-1 AC-2 AC-3: absence is pending only before the authoritative build version changes.
    static boolean current(File root,List<String> runtime)throws Exception {
        Matcher version=Pattern.compile("(?m)^\\s*version\\s*=\\s*'([^']+)'").matcher(read(root,"build.gradle","version"));
        assertTrue("version declaration missing",version.find());String actualVersion=version.group(1);
        assertFalse("version declaration ambiguous",version.find());
        assertTrue("unsupported release version: "+actualVersion,actualVersion.equals("0.9.0")||actualVersion.equals("0.10.0"));
        boolean results=new File(root,CURRENT).isFile(),report=new File(root,CURRENT_SCAN).isFile();
        if(!results&&!report&&actualVersion.equals("0.9.0"))return false;
        assertTrue("current evidence requires result record and scan",results&&report);
        String body=record(root,CURRENT,null,false);int references=0;
        for(String[] row:ReleaseResultsDocsTest.rows(body))if("automated".equals(row[2]))
            for(String ref:row[3].split(", ")){method(root,CURRENT,null,ref);references++;}
        assertTrue("current evidence needs automated method references",references>0);
        successfulRun(body);
        String scan=record(root,CURRENT_SCAN,null,true);
        assertTrue("current scan must be completed",Pattern.compile("(?m)^Status: completed$").matcher(scan).find());
        scan(root,CURRENT_SCAN,null,runtime);
        return true;
    }
}
