package com.snoworca.naruserve;

import org.junit.Test;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import static org.junit.Assert.*;

/** Additive SAM overloads let Kotlin use trailing lambdas without replacing Java varargs or two-argument handlers. */
public class HttpCallbackCompatibilityContractTest {
    @Test public void appAndRouterShareDefaultFixedArityAndVarargsMiddlewareSignatures() throws Exception { // FR-KT-003 AC-3; FR-APP-021 AC-3
        for(String verb:Arrays.asList("get","post","put","patch","delete","head","options")) {
            Method fixed=HttpRoutes.class.getMethod(verb,Object.class,Middleware.class);
            assertTrue(fixed.toString(),fixed.isDefault());assertFalse(fixed.isVarArgs());
            assertTrue(HttpRoutes.class.getMethod(verb,Object.class,Middleware[].class).isVarArgs());
            assertNotNull(HttpRoutes.class.getMethod(verb,Object.class,RouteHandler.class));
        }
        assertTrue(HttpRoutes.class.getMethod("method",String.class,Object.class,Middleware.class).isDefault());
        assertTrue(HttpRoutes.class.getMethod("method",String.class,Object.class,Middleware[].class).isVarArgs());
    }
    @Test public void routeBuildersHaveDefaultFixedArityWithoutAddingAbstractObligations() throws Exception { // FR-KT-003 AC-3; FR-APP-021 AC-3
        for(String verb:Arrays.asList("get","post","put","patch","delete","head","options")) {
            Method fixed=RouteBuilder.class.getMethod(verb,Middleware.class);
            assertTrue(fixed.toString(),fixed.isDefault());assertFalse(fixed.isVarArgs());
            assertTrue(RouteBuilder.class.getMethod(verb,Middleware[].class).isVarArgs());
            assertNotNull(RouteBuilder.class.getMethod(verb,RouteHandler.class));
        }
        assertTrue(RouteBuilder.class.getMethod("method",String.class,Middleware.class).isDefault());
        assertTrue(RouteBuilder.class.getMethod("method",String.class,Middleware[].class).isVarArgs());
    }
    @Test public void existingTwoArgumentAndNewThreeArgumentJavaLambdasAreUnambiguous() { // FR-KT-003 AC-3; FR-APP-021 AC-3
        com.snoworca.naruserve.testing.TestApp app=com.snoworca.naruserve.testing.TestApp.create();
        app.app().get("/x",(request,response,next)->next.run());
        app.app().get("/x",(request,response)->response.send("ok"));
        assertEquals("ok",app.send("GET","/x").text());
    }
}
