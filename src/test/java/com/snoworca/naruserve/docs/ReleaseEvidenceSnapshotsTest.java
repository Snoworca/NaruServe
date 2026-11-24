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

    // OPS-REL-011 AC-4 (replaces the OPS-REL-010 AC-3 pending rule after the 0.11.0 release run): version 0.11.0 is
    // checked against its own records only, the 0.10.0 records no longer stand in for it, and an unknown version is
    // refused
    @Test public void version011IsCheckedAgainstItsOwnRecords()throws Exception {
        Class<?>[] types=new Class<?>[]{File.class,List.class};
        String next="docs/release/0.11.0-test-results.md", nextScan="docs/release/0.11.0-vulnerability-scan.md";
        rejected("current evidence",()->call("current",types,fixture("0.11.0"),Collections.emptyList()));
        File old=fixture("0.11.0");write(old,PATH,"class Example { void currentOnly() {} }\n");
        write(old,CURRENT,currentResults());write(old,CURRENT_SCAN,report("g:a:1"));
        rejected("current evidence",()->call("current",types,old,Arrays.asList("g:a:1")));
        File local=fixture("0.11.0");write(local,PATH,"class Example { void currentOnly() {} }\n");
        write(local,next,currentResults());write(local,nextScan,report("g:a:1"));
        assertEquals(true,call("current",types,local,Arrays.asList("g:a:1")));
        write(local,nextScan,report("g:b:2"));
        rejected("current scan dependencies",()->call("current",types,local,Arrays.asList("g:a:1")));
        rejected("unsupported release version",()->call("current",types,fixture("0.13.0"),Collections.emptyList()));
    }
    // OPS-REL-015 AC-4 (replaces the OPS-REL-013 pending interval after the 0.12.0 release run): version 0.12.0 is
    // checked against its own records only, the 0.11.0 records no longer stand in for it, and an unknown version is
    // refused
    @Test public void version012IsCheckedAgainstItsOwnRecords()throws Exception {
        Class<?>[] types=new Class<?>[]{File.class,List.class};
        String previous="docs/release/0.11.0-test-results.md", previousScan="docs/release/0.11.0-vulnerability-scan.md";
        String latest="docs/release/0.12.0-test-results.md", latestScan="docs/release/0.12.0-vulnerability-scan.md";
        rejected("current evidence",()->call("current",types,fixture("0.12.0"),Collections.emptyList()));
        File old=fixture("0.12.0");write(old,PATH,"class Example { void currentOnly() {} }\n");
        write(old,previous,currentResults());write(old,previousScan,report("g:a:1"));
        rejected("current evidence",()->call("current",types,old,Arrays.asList("g:a:1")));
        File resultsOnly=fixture("0.12.0");write(resultsOnly,PATH,"class Example { void currentOnly() {} }\n");
        write(resultsOnly,latest,currentResults());
        rejected("current evidence",()->call("current",types,resultsOnly,Arrays.asList("g:a:1")));
        File local=fixture("0.12.0");write(local,PATH,"class Example { void currentOnly() {} }\n");
        write(local,latest,currentResults());write(local,latestScan,report("g:a:1"));
        assertEquals(true,call("current",types,local,Arrays.asList("g:a:1")));
        write(local,latestScan,report("g:a:1").replace("Status: completed","Status: pending\nReason: NO_NETWORK"));
        rejected("completed",()->call("current",types,local,Arrays.asList("g:a:1")));
        write(local,latestScan,report("g:b:2"));
        rejected("current scan dependencies",()->call("current",types,local,Arrays.asList("g:a:1")));
        write(local,latestScan,report("g:a:1"));write(local,PATH,"class Example {}\n");
        rejected("method",()->call("current",types,local,Arrays.asList("g:a:1")));
        rejected("unsupported release version",()->call("current",types,fixture("0.13.0"),Collections.emptyList()));
    }
    // OPS-REL-011 AC-1, AC-2: the 0.11.0 records hold the full-suite and Java 8 runs with a 40-hex commit and zero
    // failures, errors and skips, and a completed scan. Since the 0.12.0 release run (OPS-REL-015 AC-4) they are
    // historical files: the gate checks the 0.12.0 records against the current runtime graph
    // (realRecords012PassTheGateAndHoldEveryRun), and the 0.11.0 records keep the runs of their own release commit
    @Test public void realRecords011PassTheGateAndHoldBothRuns()throws Exception {
        String results=ReadmeDocsTest.read("docs/release/0.11.0-test-results.md");
        for(String run:new String[]{"full-suite","java8-full"}){
            java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?m)^- run "+run
                    +": command=(.+?); date=\\d{4}-\\d{2}-\\d{2}; result=exit=0; commit=([0-9a-f]{40}); tests=([0-9]+); failures=0; errors=0; skipped=0$").matcher(results);
            assertTrue(run+" run line",m.find());
            assertTrue(m.group(1),m.group(1).startsWith("./gradlew ")&&m.group(1).contains(" build"));
            assertTrue(m.group(3),Long.parseLong(m.group(3))>0);
        }
        assertTrue("java8 run uses the Java 8 init script",results.contains("-I gradle/java8-tests.gradle"));
        assertTrue(ReadmeDocsTest.read("docs/release/0.11.0-vulnerability-scan.md").contains("\nStatus: completed\n"));
    }
    /** The eleven artifactIds of 0.12.0 (FR-REL-006 AC-4). */
    private static final List<String> ARTIFACTS_012=Arrays.asList("naruserve","naruserve-api","naruserve-servlet",
            "naruserve-tinyhttpd","naruserve-gson","naruserve-kotlin","naruserve-slf4j","naruserve-filelog","naruserve-logcat",
            "naruserve-proxy","naruserve-android");
    /** The eleven published modules whose SBOM a 0.12.0 releaseSbom run writes (FR-REL-006 AC-5, FR-REL-007). */
    private static final List<String> MODULES_012=Arrays.asList("NaruServe-api","NaruServe-servlet","NaruServe-tinyhttpd",
            "NaruServe-gson","NaruServe","NaruServe-kotlin","NaruServe-slf4j","NaruServe-filelog","NaruServe-logcat",
            "NaruServe-proxy","NaruServe-android");
    private static java.util.regex.Matcher runLine(String results,String run){
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?m)^- run "+java.util.regex.Pattern.quote(run)
                +": command=(.+?); date=\\d{4}-\\d{2}-\\d{2}; result=exit=0; commit=([0-9a-f]{40}); (.+)$").matcher(results);
        assertTrue(run+" run line",m.find());
        assertFalse(run+" run line appears once",m.find(m.end()));
        m.reset();m.find();
        return m;
    }
    // OPS-REL-015 AC-1, AC-2, AC-4: the gate passes on this repository's own 0.12.0 records against the current runtime
    // graph (completed scan, equal dependency section, live method references, full-suite witness); the Java 8 run is
    // checked here with the same fields, and the automated references include NaruServe-proxy tests
    @Test public void realRecords012PassTheGateAndHoldEveryRun()throws Exception {
        List<String> graph=com.snoworca.naruserve.release.ReleaseMain.graphLines(new File(System.getProperty("naruserve.depReport")));
        List<String> runtime=new ArrayList<>(com.snoworca.naruserve.release.DependencyList.runtime(graph));
        assertEquals(true,call("current",new Class<?>[]{File.class,List.class},root(),runtime));
        String results=ReadmeDocsTest.read("docs/release/0.12.0-test-results.md");
        for(String run:new String[]{"full-suite","java8-full"}){
            java.util.regex.Matcher m=runLine(results,run);
            assertTrue(m.group(1),m.group(1).startsWith("./gradlew ")&&m.group(1).contains(" build"));
            java.util.regex.Matcher c=java.util.regex.Pattern.compile("^tests=([0-9]+); failures=0; errors=0; skipped=0$").matcher(m.group(3));
            assertTrue(run+": "+m.group(3),c.find());
            assertTrue(c.group(1),Long.parseLong(c.group(1))>0);
        }
        assertTrue("java8 run uses the Java 8 init script",runLine(results,"java8-full").group(1).contains("-I gradle/java8-tests.gradle"));
        int proxy=0;
        for(String[] row:ReleaseResultsDocsTest.rows(results))if("automated".equals(row[2]))
            for(String ref:row[3].split(", "))if(ref.startsWith("NaruServe-proxy/src/test/"))proxy++;
        assertTrue("automated references to NaruServe-proxy tests: "+proxy,proxy>0);
        assertTrue(ReadmeDocsTest.read("docs/release/0.12.0-vulnerability-scan.md").contains("\nStatus: completed\n"));
    }
    // OPS-REL-015 AC-5; FR-REL-006 AC-4; FR-REL-007 AC-1: the 0.12.0 record holds the checkCentralStaging run (run name
    // central-staging, the eleven artifactIds at 0.12.0) and the -Prelease releaseSbom run (one SBOM file with its
    // component count per published module, NaruServe-proxy included), each with exit=0 and a 40-hex commit
    @Test public void realRecords012HoldTheCentralStagingAndReleaseSbomRuns()throws Exception {
        String results=ReadmeDocsTest.read("docs/release/0.12.0-test-results.md");
        java.util.regex.Matcher staging=runLine(results,"central-staging");
        assertTrue(staging.group(1),staging.group(1).startsWith("./gradlew ")&&staging.group(1).endsWith(" checkCentralStaging"));
        java.util.regex.Matcher ids=java.util.regex.Pattern.compile("^version=0\\.12\\.0; artifactIds=([a-z0-9,-]+)$").matcher(staging.group(3));
        assertTrue("central-staging result: "+staging.group(3),ids.find());
        assertEquals(new java.util.TreeSet<>(ARTIFACTS_012),new java.util.TreeSet<>(Arrays.asList(ids.group(1).split(","))));
        assertEquals("eleven artifactIds, none twice",11,ids.group(1).split(",").length);
        java.util.regex.Matcher sbom=runLine(results,"releaseSbom");
        assertTrue(sbom.group(1),sbom.group(1).startsWith("./gradlew ")&&sbom.group(1).contains("-Prelease")
                &&sbom.group(1).endsWith(" releaseSbom"));
        for(String module:MODULES_012){
            java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?<![\\w-])"+java.util.regex.Pattern.quote(module+".cdx.json")
                    +"=([0-9]+)").matcher(sbom.group(3));
            assertTrue("releaseSbom result names "+module+".cdx.json=<component count>: "+sbom.group(3),m.find());
        }
        assertTrue(sbom.group(3),sbom.group(3).startsWith("files=11; "));
    }
    // FR-REL-007 AC-3: the 0.12.0 record holds an offline default build run with a Gradle user home that holds every
    // default-build dependency but neither release plugin (CycloneDX, Dependency-Check), with exit=0 and a 40-hex commit
    @Test public void realRecords012HoldTheOfflineBuildWithoutTheReleasePlugins()throws Exception {
        String results=ReadmeDocsTest.read("docs/release/0.12.0-test-results.md");
        java.util.regex.Matcher m=runLine(results,"offline-no-release-plugins");
        assertTrue(m.group(1),m.group(1).startsWith("GRADLE_USER_HOME=<freshHome> ./gradlew ")&&m.group(1).contains(" build")
                &&m.group(1).contains("--offline")&&!m.group(1).contains("-Prelease"));
        java.util.regex.Matcher c=java.util.regex.Pattern.compile("^tests=([0-9]+); failures=0; errors=0; skipped=0; releasePluginFiles=0$")
                .matcher(m.group(3));
        assertTrue("offline-no-release-plugins result: "+m.group(3),c.find());
        assertTrue(c.group(1),Long.parseLong(c.group(1))>0);
    }
    // OPS-REL-015 AC-3: both 0.12.0 release notes describe the raw access API, NaruServe-proxy with trustCertificates,
    // the BodyTooLargeException 413 change, the upgrade from 0.11.0 and the declared limits
    @Test public void releaseNotes012DescribeRawAccessProxyThe413ChangeAndUpgrade()throws Exception {
        String[][] files={{"docs/release/0.12.0-release-notes.md","Upgrading from 0.11.0","## Raw access API","Declared limits"},
                {"docs/release/0.12.0-release-notes.ko.md","0.11.0에서 올리기","## 원시 접근 API","알려진 한계"}};
        for(String[] f:files){
            String body=new String(Files.readAllBytes(new File(root(),f[0]).toPath()),StandardCharsets.UTF_8);
            for(String part:new String[]{f[2],"req.bodyStream()","res.relay(","res.append(","res.onAbort(","req.headerNames()",
                    "req.isFromTrustedProxy()","## NaruServe-proxy","com.snoworca.naruserve:naruserve-proxy","Proxy.to(",
                    "trustCertificates(","BodyTooLargeException","`413`","`500`","**0.12.0**","WRITE_TIMEOUT","MD5","API 21"})
                assertTrue(f[0]+" names "+part,body.contains(part));
            assertTrue(f[0]+" has the upgrade section",body.contains("## "+f[1]));
            assertTrue(f[0]+" has the declared limits",body.contains("### "+f[3]));
        }
    }
    // OPS-REL-011 AC-3: both 0.11.0 release notes name the auth module, the app.use(auth) install and the 0.10.0 upgrade
    @Test public void releaseNotes011DescribeAuthInstallAndUpgrade()throws Exception {
        String[][] files={{"docs/release/0.11.0-release-notes.md","Upgrading from 0.10.0"},
                {"docs/release/0.11.0-release-notes.ko.md","0.10.0에서 올리기"}};
        for(String[] f:files){
            String body=new String(Files.readAllBytes(new File(f[0]).toPath()),StandardCharsets.UTF_8);
            assertTrue(f[0]+" names the auth module",body.contains("com.snoworca.naruserve.utils.auth"));
            assertTrue(f[0]+" names app.use(auth)",body.contains("app.use(auth)"));
            assertTrue(f[0]+" has the upgrade section",body.contains("## "+f[1]));
        }
    }
}
