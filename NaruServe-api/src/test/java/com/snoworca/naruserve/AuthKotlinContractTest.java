package com.snoworca.naruserve;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.junit.Test;

/** The Kotlin compatibility checks extended to the utils and utils.auth packages (CON-AUTH-004). */
public class AuthKotlinContractTest {

    static final List<String> AUTH_PACKAGES = Collections.unmodifiableList(Arrays.asList(
            "com.snoworca.naruserve.utils",
            "com.snoworca.naruserve.utils.auth",
            "com.snoworca.naruserve.utils.auth.jwt",
            "com.snoworca.naruserve.utils.auth.password",
            "com.snoworca.naruserve.utils.auth.user",
            "com.snoworca.naruserve.utils.auth.group",
            "com.snoworca.naruserve.utils.auth.rbac",
            "com.snoworca.naruserve.utils.auth.audit",
            "com.snoworca.naruserve.utils.auth.apikey",
            "com.snoworca.naruserve.utils.auth.spi",
            "com.snoworca.naruserve.utils.auth.testing"));

    private static List<Class<?>> authTypes() {
        List<Class<?>> out = new ArrayList<>();
        for (Class<?> c : ApiJarClasses.publicTypes()) {
            if (c.getName().startsWith("com.snoworca.naruserve.utils.")) {
                out.add(c);
            }
        }
        return out;
    }

    private static Class<?> jar(String name) throws ClassNotFoundException {
        return Class.forName(name, false, ApiJarClasses.loader());
    }

    private static boolean nullableReturn(Class<?> type, String method, Class<?>... params) throws Exception {
        Method m = type.getMethod(method, params);
        return m.getAnnotatedReturnType().isAnnotationPresent(Nullable.class);
    }

    // CON-AUTH-004 AC-1: every public utils package has an @NullMarked package-info and is a checked package
    @Test
    public void CON_AUTH_004_AC1_publicUtilsPackagesAreNullMarkedAndChecked() {
        assertTrue(ApiJarClasses.PUBLIC_PACKAGES.containsAll(AUTH_PACKAGES));
        assertFalse(ApiJarClasses.PUBLIC_PACKAGES.contains("com.snoworca.naruserve.utils.auth.internal"));
        assertEquals(Collections.emptyList(),
                KotlinContractChecks.nullMarkedFailures(ApiJarClasses.loader(), AUTH_PACKAGES));
        for (String pkg : AUTH_PACKAGES) {
            Class<?> info = ApiJarClasses.packageInfo(pkg);
            assertNotNull(pkg, info);
            assertTrue(pkg, info.isAnnotationPresent(NullMarked.class));
        }
    }

    /** The identity and grants packages (CON-IAM-001). */
    static final List<String> IAM_PACKAGES = Collections.unmodifiableList(Arrays.asList(
            "com.snoworca.naruserve.utils.auth.user",
            "com.snoworca.naruserve.utils.auth.group",
            "com.snoworca.naruserve.utils.auth.rbac"));

    // CON-IAM-001 AC-4: the FR-KT-001 and FR-KT-002 checks over the IAM packages: @NullMarked, nullable positions
    // annotated @Nullable, no @NonNull, no Kotlin keyword method names
    @Test
    public void CON_IAM_001_AC4_iamPackagesPassTheKotlinChecks() throws Exception {
        assertTrue(AUTH_PACKAGES.containsAll(IAM_PACKAGES));
        assertEquals(Collections.emptyList(), KotlinContractChecks.nullMarkedFailures(ApiJarClasses.loader(),
                IAM_PACKAGES));
        List<Class<?>> iam = new ArrayList<>();
        for (Class<?> c : authTypes()) {
            for (String p : IAM_PACKAGES) {
                if (c.getPackage().getName().equals(p)) {
                    iam.add(c);
                }
            }
        }
        assertTrue(iam.toString(), iam.size() >= 15);
        assertEquals(Collections.emptyList(), KotlinContractChecks.nonNullFailures(iam));
        assertEquals(Collections.emptyList(), KotlinContractChecks.keywordFailures(iam));
        Class<?> store = jar("com.snoworca.naruserve.utils.auth.user.UserStore");
        assertTrue(nullableReturn(store, "findByUsername", String.class));
        assertTrue(nullableReturn(store, "findById", String.class));
        assertFalse(nullableReturn(store, "create", jar("com.snoworca.naruserve.utils.auth.user.AuthUser")));
        Class<?> users = jar("com.snoworca.naruserve.utils.auth.user.AuthUsers");
        assertTrue(nullableReturn(users, "findById", String.class));
        assertFalse(nullableReturn(jar("com.snoworca.naruserve.utils.auth.group.GroupStore"), "groupsOfUser",
                String.class));
        assertFalse(nullableReturn(jar("com.snoworca.naruserve.utils.auth.rbac.RolePolicy"), "scopesOf",
                String.class));
    }

    // CON-AUTH-004 AC-2: no @NonNull anywhere in the auth packages; nullable positions carry @Nullable
    @Test
    public void CON_AUTH_004_AC2_nullabilityOfTheAuthPackages() throws Exception {
        List<Class<?>> types = authTypes();
        assertTrue(types.toString(), types.size() >= 10);
        assertEquals(Collections.emptyList(), KotlinContractChecks.nonNullFailures(types));
        assertTrue(nullableReturn(jar("com.snoworca.naruserve.utils.auth.AuthEventContext"), "getOrigin"));
        assertTrue(nullableReturn(jar("com.snoworca.naruserve.utils.auth.AuthRejection"), "getRetryAfterSeconds"));
        Class<?> auth = jar("com.snoworca.naruserve.utils.auth.Auth");
        assertTrue(nullableReturn(auth, "userOrNull", jar("com.snoworca.naruserve.Request")));
        assertTrue(nullableReturn(auth, "loadUser", jar("com.snoworca.naruserve.Request")));
        assertFalse(nullableReturn(auth, "user", jar("com.snoworca.naruserve.Request")));
        assertTrue(nullableReturn(jar("com.snoworca.naruserve.utils.auth.AuthPrincipal"), "getApiKeyId"));
        Class<?> user = jar("com.snoworca.naruserve.utils.auth.user.AuthUser");
        assertTrue(nullableReturn(user, "getPasswordHash"));
        assertTrue(nullableReturn(user, "getId"));
        assertTrue(nullableReturn(user, "getAttribute", String.class));
        assertFalse(nullableReturn(user, "getUsername"));
        assertFalse(nullableReturn(user, "getRoles"));
        Class<?> ids = jar("com.snoworca.naruserve.utils.auth.user.AuthIds");
        assertTrue(nullableReturn(ids, "parseLong", String.class));
        assertTrue(nullableReturn(ids, "parseUuid", String.class));
        assertFalse(nullableReturn(jar("com.snoworca.naruserve.utils.auth.rbac.RoleStore"), "scopesOf",
                String.class));
        Class<?> builder = jar("com.snoworca.naruserve.utils.auth.user.AuthUser$Builder");
        assertArrayEquals(new boolean[] {true}, nullableParameters(builder, "id", String.class));
        assertArrayEquals(new boolean[] {true}, nullableParameters(builder, "passwordHash", String.class));
        assertArrayEquals(new boolean[] {false, true},
                nullableParameters(builder, "attribute", String.class, String.class));
        assertArrayEquals(new boolean[] {false}, nullableParameters(builder, "username", String.class));
        Class<?> request = jar("com.snoworca.naruserve.Request");
        Class<?> origin = jar("com.snoworca.naruserve.utils.auth.UserOrigin");
        assertArrayEquals(new boolean[] {true, true, true, false}, nullableConstructor(
                jar("com.snoworca.naruserve.utils.auth.AuthEventContext"), origin, request, String.class, long.class));
        assertArrayEquals(new boolean[] {false, false, true, true}, nullableConstructor(
                jar("com.snoworca.naruserve.utils.auth.AuthRejection"), int.class, String.class, String.class,
                Long.class));
        assertArrayEquals(new boolean[] {false, true, true}, nullableConstructor(
                jar("com.snoworca.naruserve.utils.auth.AuthUnavailableException"), String.class, String.class,
                Throwable.class));
    }

    private static boolean[] flags(java.lang.reflect.AnnotatedType[] types) {
        boolean[] out = new boolean[types.length];
        for (int i = 0; i < types.length; i++) {
            out[i] = types[i].isAnnotationPresent(Nullable.class);
        }
        return out;
    }

    private static boolean[] nullableParameters(Class<?> type, String method, Class<?>... params) throws Exception {
        return flags(type.getMethod(method, params).getAnnotatedParameterTypes());
    }

    private static boolean[] nullableConstructor(Class<?> type, Class<?>... params) throws Exception {
        return flags(type.getConstructor(params).getAnnotatedParameterTypes());
    }

    // CON-AUTH-004 AC-3: no public method of the auth packages is named with a Kotlin hard keyword
    @Test
    public void CON_AUTH_004_AC3_noKeywordNamedMethods() {
        assertEquals(Collections.emptyList(), KotlinContractChecks.keywordFailures(authTypes()));
    }

    // CON-AUTH-004 AC-4: the callback inventory over the auth packages agrees with CallbackMatrix, every entry is a
    // @FunctionalInterface, and CallbackMatrixKotlinTest (case j) reaches each as a Kotlin lambda through a request
    @Test
    public void CON_AUTH_004_AC4_authCallbacksAreInTheMatrix() {
        List<String> authCallbacks = new ArrayList<>();
        for (Class<?> c : CallbackInventory.inventory(ApiJarClasses.publicTypes())) {
            if (c.getName().startsWith("com.snoworca.naruserve.utils.")) {
                authCallbacks.add(c.getName());
                assertTrue(c.getName(), c.isAnnotationPresent(FunctionalInterface.class));
            }
        }
        List<String> declared = new ArrayList<>();
        for (String n : CallbackMatrix.CALLBACK_TYPES) {
            if (n.startsWith("com.snoworca.naruserve.utils.")) {
                declared.add(n);
            }
        }
        Collections.sort(authCallbacks);
        Collections.sort(declared);
        assertEquals(declared, authCallbacks);
        assertTrue(authCallbacks.toString(), authCallbacks.containsAll(java.util.Arrays.asList(
                "com.snoworca.naruserve.utils.TimeSource", "com.snoworca.naruserve.utils.auth.AccessRule",
                "com.snoworca.naruserve.utils.auth.user.UserMutator")));
        assertEquals(Collections.emptyList(),
                CallbackInventory.failures(ApiJarClasses.publicTypes(), CallbackMatrix.CALLBACK_TYPES));
    }
}
