package com.snoworca.naruserve.docs;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

/** Explicit historical evidence is never substituted for current-release verification. */
public class ReleaseEvidenceSnapshotsTest {
    private static final String PIN="2498edaa643593474f830259b8cf3c31a9590702";
    private static final String OLD="docs/release/test-results.md", SCAN="docs/release/vulnerability-scan.md";
    private static final String CURRENT="docs/release/0.10.0-test-results.md", CURRENT_SCAN="docs/release/0.10.0-vulnerability-scan.md";
    private static final String PATH="NaruServe-api/src/test/java/com/snoworca/naruserve/HttpAcceptanceTest.java";
    private static final String METHOD="sdsAc15_T02_FR_HTTP_022_AC7_anyExceptDeleteAnswersDelete405";
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private interface Action {void run() throws Exception;}
    private static Object call(String name,Class<?>[] types,Object... args)throws Exception {
        Class<?> type=Class.forName("com.snoworca.naruserve.docs.ReleaseEvidenceSnapshots");
        try{return type.getDeclaredMethod(name,types).invoke(null,args);}catch(InvocationTargetException e){
            Throwable cause=e.getCause();if(cause instanceof Exception)throw (Exception)cause;throw (Error)cause;
        }
    }
    private static void method(File root,String record,String pin,String ref)throws Exception {
        call("method",new Class<?>[]{File.class,String.class,String.class,String.class},root,record,pin,ref);
    }
    private static void scan(File root,String path,String pin,List<String> graph)throws Exception {
        call("scan",new Class<?>[]{File.class,String.class,String.class,List.class},root,path,pin,graph);
    }
    private static void rejected(String field,Action action)throws Exception {
        boolean rejected=false;
        try{action.run();}catch(AssertionError expected){
            assertTrue("diagnostic must identify "+field+": "+expected.getMessage(),String.valueOf(expected.getMessage()).contains(field));
            rejected=true;
        }
        assertTrue("accepted invalid "+field,rejected);
    }
    private static File root(){return new File(System.getProperty("naruserve.rootDir"));}
    private static void write(File root,String path,String text)throws IOException {
        File f=new File(root,path);f.getParentFile().mkdirs();Files.write(f.toPath(),text.getBytes(StandardCharsets.UTF_8));
    }
    private File fixture(String version)throws Exception {
        File root=temporary.newFolder();write(root,"build.gradle","allprojects {\n version = '"+version+"'\n}\n");return root;
    }
    private static String report(String coordinate){return "# Vulnerability scan\n\nStatus: completed\n\n## Findings\n\n| dependency | identifier | CVSS | severity |\n|---|---|---|---|\n\n## Dependencies\n\n"+coordinate+"\n";}

    private static String currentResults() {
        return "# Current\n\n| T01 | - | automated | "+PATH+"#currentOnly |\n"
                + "\n## Recorded runs\n"
                + "- run full-suite: command=./gradlew -PtinyHttpdFromMaven=true build; date=2026-10-29; "
                + "result=exit=0; commit=06b9ed5e53caefb46a43a567ab37a50f0b051842; tests=1; failures=0; errors=0; skipped=0\n";
    }

    @Test public void all67ActualHistoricalReferencesResolveAtExactPin()throws Exception { // OPS-REL-008 AC-2; OPS-REL-009 AC-2
        int count=0;
        for(String[] row:ReleaseResultsDocsTest.rows(ReadmeDocsTest.read(OLD)))if("automated".equals(row[2]))
            for(String ref:row[3].split(", ")){method(root(),OLD,PIN,ref);count++;}
        assertEquals(67,count);
    }
    @Test public void historicalMissingMethodAndSourceAreRejectedWithoutAliases()throws Exception { // OPS-REL-008 AC-2
        method(root(),OLD,PIN,PATH+"#"+METHOD);
        rejected("method",()->method(root(),OLD,PIN,PATH+"#bogus_"+METHOD));
        rejected("source",()->method(root(),OLD,PIN,"NaruServe-api/src/test/java/Missing.java#"+METHOD));
    }
    @Test public void unknownCommitWrongExistingCommitAndCrossVersionPinAreRejected()throws Exception { // OPS-REL-009 AC-2
        rejected("commit",()->method(root(),OLD,"not-a-commit",PATH+"#"+METHOD));
        rejected("commit",()->method(root(),OLD,"0000000000000000000000000000000000000000",PATH+"#"+METHOD));
        rejected("commit",()->method(root(),OLD,"06b9ed5",PATH+"#"+METHOD));
        File local=fixture("0.10.0");write(local,CURRENT,"# Current\n");
        rejected("commit",()->method(local,CURRENT,PIN,PATH+"#"+METHOD));
        rejected("record",()->method(root(),"docs/release/arbitrary.md",PIN,PATH+"#"+METHOD));
    }
    @Test public void modifiedHistoricalRecordAndScanBytesAreRejected()throws Exception { // OPS-REL-008 AC-2; OPS-REL-009 AC-2
        File local=temporary.newFolder();
        write(local,OLD,ReadmeDocsTest.read(OLD)+"\nmodified\n");
        rejected("historical bytes",()->method(local,OLD,PIN,PATH+"#"+METHOD));
        write(local,SCAN,ReadmeDocsTest.read(SCAN)+"\nmodified\n");
        rejected("historical bytes",()->scan(local,SCAN,PIN,Collections.emptyList()));
    }
    @Test public void currentSourceMustDeclareActualMethodAndCannotFallbackToHistoricalSource()throws Exception { // OPS-REL-008 AC-3; OPS-REL-009 AC-2
        File local=fixture("0.10.0");write(local,CURRENT,"# Current\n");write(local,PATH,"class Example { void currentOnly() {} }\n");
        method(local,CURRENT,null,PATH+"#currentOnly");
        rejected("method",()->method(local,CURRENT,null,PATH+"#"+METHOD));
        rejected("source",()->method(local,CURRENT,null,"missing.java#currentOnly"));
        rejected("source",()->method(local,CURRENT,null,"../outside.java#currentOnly"));
    }
    @Test public void historicalScanIsPinnedWhileCurrentScanRequiresExactCurrentGraph()throws Exception { // OPS-REL-008 AC-2; OPS-REL-009 AC-1 AC-2
        scan(root(),SCAN,PIN,Arrays.asList("com.snoworca:tinyhttpd-core:3.1.1"));
        File local=fixture("0.10.0");String current="com.snoworca:tinyhttpd-core:3.1.1";
        write(local,CURRENT_SCAN,report(current));scan(local,CURRENT_SCAN,null,Arrays.asList(current));
        rejected("dependencies",()->scan(local,CURRENT_SCAN,null,Arrays.asList("com.snoworca:tinyhttpd-core:3.1.0")));
        rejected("commit",()->scan(local,CURRENT_SCAN,PIN,Arrays.asList(current)));
        write(local,CURRENT_SCAN,report("com.snoworca:tinyhttpd-core:3.1.0"));
        rejected("dependencies",()->scan(local,CURRENT_SCAN,null,Arrays.asList(current)));
    }
    @Test public void missingCurrentEvidenceIsPendingOnlyBeforeVersionBumpAndMandatoryAt010()throws Exception { // OPS-REL-009 AC-1 AC-2 AC-3
        File before=fixture("0.9.0");
        assertEquals(false,call("current",new Class<?>[]{File.class,List.class},before,Collections.emptyList()));
        File release=fixture("0.10.0");
        rejected("current evidence",()->call("current",new Class<?>[]{File.class,List.class},release,Collections.emptyList()));
        write(release,CURRENT,"# Current\n");
        rejected("current evidence",()->call("current",new Class<?>[]{File.class,List.class},release,Collections.emptyList()));
        write(before,CURRENT_SCAN,report("g:a:1"));
        rejected("current evidence",()->call("current",new Class<?>[]{File.class,List.class},before,Collections.emptyList()));
    }
    @Test public void currentGateValidatesLiveMethodGraphAndCompletedScanWithoutHistoricalFallback()throws Exception { // OPS-REL-009 AC-1 AC-2 AC-3
        File local=fixture("0.10.0");write(local,PATH,"class Example { void currentOnly() {} }\n");
        write(local,CURRENT,currentResults());write(local,CURRENT_SCAN,report("g:a:1"));
        assertEquals(true,call("current",new Class<?>[]{File.class,List.class},local,Arrays.asList("g:a:1")));
        write(local,CURRENT_SCAN,report("g:a:1").replace("Status: completed","Status: pending\nReason: NO_NETWORK"));
        rejected("completed",()->call("current",new Class<?>[]{File.class,List.class},local,Arrays.asList("g:a:1")));
        write(local,CURRENT_SCAN,report("g:a:1"));write(local,PATH,"class Example {}\n");
        rejected("method",()->call("current",new Class<?>[]{File.class,List.class},local,Arrays.asList("g:a:1")));
    }
    @Test public void currentResultsCannotBeEmptyHeadingOnlyOrHaveNoAutomatedReference()throws Exception { // OPS-REL-009 AC-2; OPS-REL-008 AC-3
        File local=fixture("0.10.0");write(local,PATH,"class Example { void currentOnly() {} }\n");write(local,CURRENT_SCAN,report("g:a:1"));
        for(String empty:new String[]{"", "# Current\n", currentResults().replace("| T01 | - | automated | "+PATH+"#currentOnly |\n", "")}) {
            write(local,CURRENT,empty);
            rejected("current evidence",()->call("current",new Class<?>[]{File.class,List.class},local,Arrays.asList("g:a:1")));
        }
    }
    @Test public void currentRunWitnessMustDeclareUsableCommitSuccessfulExitAndPositiveTestCount()throws Exception { // OPS-REL-009 AC-2
        File local=fixture("0.10.0");write(local,PATH,"class Example { void currentOnly() {} }\n");write(local,CURRENT_SCAN,report("g:a:1"));
        String valid=currentResults();write(local,CURRENT,valid);
        assertEquals(true,call("current",new Class<?>[]{File.class,List.class},local,Arrays.asList("g:a:1")));
        for(String invalid:new String[]{
                valid.substring(0,valid.indexOf("\n## Recorded runs")),
                valid.replace("commit=06b9ed5e53caefb46a43a567ab37a50f0b051842; ", ""),
                valid.replace("06b9ed5e53caefb46a43a567ab37a50f0b051842", "not-a-commit"),
                valid.replace("06b9ed5e53caefb46a43a567ab37a50f0b051842", "0000000000000000000000000000000000000000"),
                valid.replace("exit=0", "exit=1"),
                valid.replace("tests=1", "tests=0")}) {
            write(local,CURRENT,invalid);
            rejected("current run",()->call("current",new Class<?>[]{File.class,List.class},local,Arrays.asList("g:a:1")));
        }
    }

}
