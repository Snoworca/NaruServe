package com.snoworca.naruserve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import org.junit.Test;

public class TypeRefTest {

    static final class User {
    }

    private static <X> TypeRef<X> make() {
        return new TypeRef<X>() {
        };
    }

    // IR-JSON-001 AC-1
    @Test
    public void IR_JSON_001_AC1_listOfUserIsCapturedAsAParameterizedType() {
        Type t = new TypeRef<List<User>>() {
        }.getType();
        assertTrue(t.toString(), t instanceof ParameterizedType);
        ParameterizedType p = (ParameterizedType) t;
        assertSame(List.class, p.getRawType());
        assertEquals(1, p.getActualTypeArguments().length);
        assertSame(User.class, p.getActualTypeArguments()[0]);
    }

    // IR-JSON-001 AC-1
    @Test
    public void IR_JSON_001_AC1_stringIsCapturedAsStringClass() {
        assertSame(String.class, new TypeRef<String>() {
        }.getType());
    }

    // IR-JSON-001 AC-1
    @Test
    public void IR_JSON_001_AC1_typeRefIsPublicAbstractWithProtectedNoArgConstructor() throws Exception {
        assertTrue(Modifier.isPublic(TypeRef.class.getModifiers()));
        assertTrue(Modifier.isAbstract(TypeRef.class.getModifiers()));
        assertEquals("com.snoworca.naruserve", TypeRef.class.getPackage().getName());
        assertEquals(1, TypeRef.class.getDeclaredConstructors().length);
        assertTrue(Modifier.isProtected(TypeRef.class.getDeclaredConstructor().getModifiers()));
        assertTrue(Modifier.isPublic(TypeRef.class.getMethod("getType").getModifiers()));
        assertSame(Type.class, TypeRef.class.getMethod("getType").getReturnType());
    }

    // SDS-AC-1; IR-JSON-001 AC-2
    @Test
    @SuppressWarnings("rawtypes")
    public void sdsAc1_IR_JSON_001_AC2_rawSubclassThrowsIllegalStateException() {
        try {
            new TypeRef() {
            };
            fail("a raw TypeRef must be refused");
        } catch (IllegalStateException e) {
            assertEquals("TypeRef needs a concrete type argument", e.getMessage());
        }
    }

    // SDS-AC-1; IR-JSON-001 AC-2
    @Test
    public void sdsAc1_IR_JSON_001_AC2_typeVariableArgumentThrowsIllegalStateException() {
        try {
            make();
            fail("a type-variable TypeRef must be refused");
        } catch (IllegalStateException e) {
            assertEquals("TypeRef needs a concrete type argument", e.getMessage());
        }
    }
}
