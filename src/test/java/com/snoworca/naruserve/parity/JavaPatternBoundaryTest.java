package com.snoworca.naruserve.parity;

import java.util.regex.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Java contract controls; these are deliberately outside the Express RegExp equivalence denominator. */
public class JavaPatternBoundaryTest {
    // OPS-ROUTE-004 AC-5
    @Test public void javaIdentityEscapeAndCharacterClassSemanticsAreRetained() {
        assertTrue(Pattern.compile("\\a").matcher("\u0007").find());
        assertFalse(Pattern.compile("\\a").matcher("a").find());
        assertTrue(Pattern.compile("[a-z&&[^b]]").matcher("c").find());
        assertFalse(Pattern.compile("[a-z&&[^b]]").matcher("b").find());
    }
    // OPS-ROUTE-004 AC-5
    @Test public void explicitJavaFlagsAndPerInvocationMatcherStateAreObservable() {
        Pattern pattern=Pattern.compile("foo",Pattern.CASE_INSENSITIVE);
        Matcher first=pattern.matcher("FOOfoo"),second=pattern.matcher("FOOfoo");
        assertTrue(first.find());assertEquals(0,first.start());assertTrue(first.find());assertEquals(3,first.start());
        assertTrue(second.find());assertEquals(0,second.start());assertFalse(pattern.matcher("prefixFOO").matches());
    }
    // OPS-ROUTE-004 AC-5
    @Test public void invalidJavaRegexIsRejectedWithoutAnEcmaEngine() {
        try {Pattern.compile("[^");fail("invalid pattern accepted");} catch(PatternSyntaxException expected) {assertTrue(expected.getIndex()>=0);}
    }
}
