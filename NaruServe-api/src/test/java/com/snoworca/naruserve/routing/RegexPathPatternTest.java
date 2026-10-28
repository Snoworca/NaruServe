package com.snoworca.naruserve.routing;

import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class RegexPathPatternTest {
    private static RegexPathPattern.Match matched(RegexPathPattern p,String value) {RegexPathPattern.Match m=p.match(value);assertNotNull("no match for "+value,m);return m;}
    private static List<String> list(String... values) {return Arrays.asList(values);}
    private static void value(RegexPathPattern.Match m,String key,String value) {assertEquals(Collections.singletonList(value),m.captures().get(key));}

    // FR-ROUTE-019 AC-2 AC-3
    @Test public void searchReturnsActualSubstringUtf16OffsetsAndOrderedKeys() {
        RegexPathPattern p=RegexPathPattern.compile(Pattern.compile("(?<name>foo)(bar)"));RegexPathPattern.Match m=matched(p,"\uD83D\uDE00foobar!");
        assertEquals(list("name","0"),p.keys());assertEquals("foobar",m.matchedPath());assertEquals(2,m.start());assertEquals(8,m.end());value(m,"name","foo");value(m,"0","bar");assertNull(p.match("absent"));
    }
    // FR-ROUTE-019 AC-2 AC-4
    @Test public void explicitJavaCaseDotallAndMultilineFlagsAreRetained() {
        assertNull(RegexPathPattern.compile(Pattern.compile("foo")).match("FOO"));assertNotNull(RegexPathPattern.compile(Pattern.compile("foo",Pattern.CASE_INSENSITIVE)).match("FOO"));
        assertNotNull(RegexPathPattern.compile(Pattern.compile("a.b",Pattern.DOTALL)).match("a\nb"));assertNull(RegexPathPattern.compile(Pattern.compile("a.b")).match("a\nb"));
        assertEquals("b",matched(RegexPathPattern.compile(Pattern.compile("^b$",Pattern.MULTILINE)),"a\nb\nc").matchedPath());
    }
    // FR-ROUTE-019 AC-2 AC-4
    @Test public void javaOnlyEscapeIntersectionAndLiteralSemanticsAreNotTranslated() {
        assertNotNull(RegexPathPattern.compile(Pattern.compile("\\a")).match("\u0007"));assertNull(RegexPathPattern.compile(Pattern.compile("\\a")).match("a"));
        assertNotNull(RegexPathPattern.compile(Pattern.compile("[a-z&&[^b]]")).match("c"));assertNull(RegexPathPattern.compile(Pattern.compile("[a-z&&[^b]]")).match("b"));
        RegexPathPattern literal=RegexPathPattern.compile(Pattern.compile("(?<fake>[)",Pattern.LITERAL));assertEquals(Collections.emptyList(),literal.keys());assertEquals("(?<fake>[)",matched(literal,"prefix(?<fake>[)suffix").matchedPath());
    }
    // FR-ROUTE-019 AC-3
    @Test public void namedGroupsDoNotConsumeUnnamedNumericOrdinal() {
        RegexPathPattern p=RegexPathPattern.compile(Pattern.compile("(a)(?<middle>b)(c)(?<last>d)(e)"));RegexPathPattern.Match m=matched(p,"abcde");
        assertEquals(list("0","middle","1","last","2"),p.keys());assertEquals(p.keys(),new ArrayList<>(m.captures().keySet()));value(m,"0","a");value(m,"middle","b");value(m,"1","c");value(m,"last","d");value(m,"2","e");
    }
    // FR-ROUTE-019 AC-3
    @Test public void nonparticipatingIsAbsentWhileParticipatingEmptyRemainsPresent() {
        RegexPathPattern p=RegexPathPattern.compile(Pattern.compile("(a)?(?<absent>z)?(b*)(?<empty>c*)"));RegexPathPattern.Match m=matched(p,"");
        assertEquals(list("0","absent","1","empty"),p.keys());assertFalse(m.captures().containsKey("0"));assertFalse(m.captures().containsKey("absent"));value(m,"1","");value(m,"empty","");
    }
    // FR-ROUTE-019 AC-3: central HTTP decoding owns percent handling, not this helper.
    @Test public void capturesRemainRawIncludingMalformedPercentEscapes() {
        RegexPathPattern p=RegexPathPattern.compile(Pattern.compile("/x/(.*)"));value(matched(p,"/x/a%2Fb"),"0","a%2Fb");value(matched(p,"/x/%ZZ"),"0","%ZZ");value(matched(p,"/x/%00"),"0","%00");
    }
    // FR-ROUTE-019 AC-3 AC-4
    @Test public void capturesAndMetadataAreDeeplyImmutableSnapshots() {
        RegexPathPattern p=RegexPathPattern.compile(Pattern.compile("(a)"));RegexPathPattern.Match m=matched(p,"a");
        try {p.keys().add("bad");fail("mutable keys");}catch(UnsupportedOperationException expected){}
        try {m.captures().put("bad",list("x"));fail("mutable map");}catch(UnsupportedOperationException expected){}
        try {m.captures().get("0").add("bad");fail("mutable capture");}catch(UnsupportedOperationException expected){}
        matched(p,"a");value(m,"0","a");
    }
    // FR-ROUTE-019 AC-3
    @Test public void quoteEscapesHideApparentGroupsAndClasses() {
        String literal="(foo)[bar](?<fake>x)";RegexPathPattern p=RegexPathPattern.compile(Pattern.compile("\\Q"+literal+"\\E(?<real>z)"));
        assertEquals(list("real"),p.keys());value(matched(p,literal+"z"),"real","z");
        RegexPathPattern open=RegexPathPattern.compile(Pattern.compile("\\Q(foo)[bar](?<fake>x)"));assertEquals(Collections.emptyList(),open.keys());assertNotNull(open.match(literal));
    }
    // FR-ROUTE-019 AC-3
    @Test public void escapedParensBackslashAndControlEscapeDoNotInventGroups() {
        RegexPathPattern escaped=RegexPathPattern.compile(Pattern.compile("\\((?<real>a)\\)"));assertEquals(list("real"),escaped.keys());value(matched(escaped,"(a)"),"real","a");
        RegexPathPattern slash=RegexPathPattern.compile(Pattern.compile("\\\\(a)"));assertEquals(list("0"),slash.keys());value(matched(slash,"\\a"),"0","a");
        RegexPathPattern control=RegexPathPattern.compile(Pattern.compile("\\c((a)"));assertEquals(list("0"),control.keys());value(matched(control,"\ba"),"0","a");
    }
    // FR-ROUTE-019 AC-3
    @Test public void characterClassFirstClosingBracketAndIntersectionsHideParens() {
        RegexPathPattern p=RegexPathPattern.compile(Pattern.compile("[]()]([a-z&&[^b]])"));assertEquals(list("0"),p.keys());value(matched(p,"]c"),"0","c");assertNull(p.match("]b"));
    }
    // FR-ROUTE-019 AC-3
    @Test public void lookaroundsLookbehindAtomicAndNoncaptureGroupsPreserveActualIndices() {
        RegexPathPattern p=RegexPathPattern.compile(Pattern.compile("(?=(a))a(?<tail>b)(?:c)(?>d)(?<=(d))"));RegexPathPattern.Match m=matched(p,"abcd");
        assertEquals(list("0","tail","1"),p.keys());value(m,"0","a");value(m,"tail","b");value(m,"1","d");
        RegexPathPattern behind=RegexPathPattern.compile(Pattern.compile("(?<=(a))(b)"));m=matched(behind,"ab");assertEquals("b",m.matchedPath());value(m,"0","a");value(m,"1","b");
    }
    // FR-ROUTE-019 AC-3
    @Test public void namedBackreferencesAreNotAdditionalCaptures() {
        RegexPathPattern p=RegexPathPattern.compile(Pattern.compile("(?<id>a)\\k<id>(b)"));assertEquals(list("id","0"),p.keys());RegexPathPattern.Match m=matched(p,"aab");value(m,"id","a");value(m,"0","b");
    }
    // FR-ROUTE-019 AC-3 AC-6
    @Test public void commentsHideGroupsIncludingInsideCharacterClasses() {
        RegexPathPattern p=RegexPathPattern.compile(Pattern.compile("(?x)(a) # (?<fake>ignored)\n (?<real>b)"));assertEquals(list("0","real"),p.keys());value(matched(p,"ab"),"real","b");
        p=RegexPathPattern.compile(Pattern.compile("(?x)[a# ignored (?<fake>x)\n](?<real>b)"));assertEquals(list("real"),p.keys());value(matched(p,"ab"),"real","b");
    }
    // FR-ROUTE-019 AC-3 AC-6
    @Test public void commentsWhitespaceInJavaGroupNamesAndGroupPrefixIsHandled() {
        RegexPathPattern p=RegexPathPattern.compile(Pattern.compile("(?x)(?<n a m e>a)"));assertEquals(list("name"),p.keys());value(matched(p,"a"),"name","a");
        p=RegexPathPattern.compile(Pattern.compile("(?x)( ?<name> a )"));assertEquals(list("name"),p.keys());value(matched(p,"a"),"name","a");
    }
    // FR-ROUTE-019 AC-3 AC-6
    @Test public void scopedFlagsRestoreCommentsAndHashSpaceMeaning() {
        RegexPathPattern p=RegexPathPattern.compile(Pattern.compile("(?x:(a) # ignored (?<fake>x)\n (?<name>b))(?-x:(c #d))"));RegexPathPattern.Match m=matched(p,"abc #d");
        assertEquals(list("0","name","1"),p.keys());value(m,"0","a");value(m,"name","b");value(m,"1","c #d");
        p=RegexPathPattern.compile(Pattern.compile("(?x)(?-x:(?<raw>a # b))(?<next>c)"));assertEquals(list("raw","next"),p.keys());value(matched(p,"a # bc"),"raw","a # b");
    }
    // FR-ROUTE-019 AC-3 AC-6
    @Test public void embeddedFlagRemovalDoesNotTreatFollowingLiteralHashAsComment() {
        RegexPathPattern p=RegexPathPattern.compile(Pattern.compile("(?x)(a)(?-x)#(?<name>b)"));assertEquals(list("0","name"),p.keys());value(matched(p,"a#b"),"name","b");
    }
    // FR-ROUTE-019 AC-3 AC-6
    @Test public void groupCountDisambiguatesLostInitialCommentsModeWhenPossible() {
        String source="(a)# (?<middle>b)\n(?x)(?<last>c)";
        RegexPathPattern plain=RegexPathPattern.compile(Pattern.compile(source));assertEquals(list("0","middle","last"),plain.keys());value(matched(plain,"a# b\nc"),"middle","b");
        RegexPathPattern comments=RegexPathPattern.compile(Pattern.compile(source,Pattern.COMMENTS));assertEquals(list("0","last"),comments.keys());value(matched(comments,"ac"),"last","c");
    }
    // FR-ROUTE-019 AC-3 AC-6
    @Test public void unixLinesChangesCommentTerminationWithoutMiscountingGroups() {
        String source="(?x)(a)# comment\r(?<middle>b)\n(?<last>c)";
        RegexPathPattern plain=RegexPathPattern.compile(source,0);assertEquals(list("0","middle","last"),plain.keys());value(matched(plain,"abc"),"middle","b");
        RegexPathPattern unix=RegexPathPattern.compile(source,Pattern.UNIX_LINES);assertEquals(list("0","last"),unix.keys());value(matched(unix,"ac"),"last","c");
    }
    // FR-ROUTE-019 AC-3 AC-6: two valid Patterns expose identical public metadata but different group names.
    @Test public void genuinelyAmbiguousPrecompiledMetadataRejectsAndSourceFlagsIsPrecise() {
        String source="#[\n(?<a>a)] [#]\n(?<b>b)] (?x)";Pattern plain=Pattern.compile(source),comments=Pattern.compile(source,Pattern.COMMENTS);
        assertEquals(plain.pattern(),comments.pattern());assertEquals(plain.flags(),comments.flags());assertEquals(plain.matcher("").groupCount(),comments.matcher("").groupCount());
        for(Pattern p:new Pattern[]{plain,comments})try {RegexPathPattern.compile(p);fail("ambiguous metadata accepted");}catch(IllegalArgumentException expected){assertTrue(expected.getMessage(),expected.getMessage().contains("compile(String"));}
        RegexPathPattern p=RegexPathPattern.compile(source,0);assertEquals(list("b"),p.keys());value(matched(p,"#a #\nb] "),"b","b");
        p=RegexPathPattern.compile(source,Pattern.COMMENTS);assertEquals(list("a"),p.keys());value(matched(p,"a]b"),"a","a");
    }
    // FR-ROUTE-019 AC-2 AC-4
    @Test public void repeatedMatchesStartFreshInsteadOfEmulatingLastIndex() {
        RegexPathPattern p=RegexPathPattern.compile(Pattern.compile("(foo)"));for(int i=0;i<5;i++){RegexPathPattern.Match m=matched(p,"foo foo");assertEquals(0,m.start());value(m,"0","foo");}
    }
    // FR-ROUTE-019 AC-4
    @Test public void sharedCompiledPatternHasNoConcurrentMatcherState() throws Exception {
        RegexPathPattern p=RegexPathPattern.compile(Pattern.compile("(?<name>n[0-9]+)-([0-9]+)"));ExecutorService workers=Executors.newFixedThreadPool(4);
        try {List<Future<?>> results=new ArrayList<>();for(int i=0;i<200;i++){final int n=i;results.add(workers.submit(()->{RegexPathPattern.Match m=matched(p,"n"+n+"-"+n);value(m,"name","n"+n);value(m,"0",String.valueOf(n));}));}for(Future<?> result:results)result.get(10,TimeUnit.SECONDS);}finally{workers.shutdownNow();assertTrue(workers.awaitTermination(5,TimeUnit.SECONDS));}
    }
    // FR-ROUTE-019 AC-1 AC-2 AC-6
    @Test public void nullInputAndInvalidSourceFlagsFailAtRegistration() {
        try{RegexPathPattern.compile((Pattern)null);fail("null pattern accepted");}catch(NullPointerException expected){}
        try{RegexPathPattern.compile(null,0);fail("null source accepted");}catch(NullPointerException expected){}
        try{RegexPathPattern.compile("(",0);fail("invalid source accepted");}catch(PatternSyntaxException expected){}
        try{RegexPathPattern.compile("a",Integer.MIN_VALUE);fail("invalid flags accepted");}catch(IllegalArgumentException expected){}
        RegexPathPattern p=RegexPathPattern.compile("a",0);try{p.match(null);fail("null path accepted");}catch(NullPointerException expected){}
    }
}
