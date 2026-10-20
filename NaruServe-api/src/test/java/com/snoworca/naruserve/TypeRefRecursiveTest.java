package com.snoworca.naruserve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.WildcardType;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class TypeRefRecursiveTest {
    private static class Owner<T> {
        class Member {
        }
    }

    private static <T> TypeRef<?> listVariable() {
        return new TypeRef<List<T>>() {};
    }

    private static <T> TypeRef<?> nestedVariable() {
        return new TypeRef<Map<String, List<T>>>() {};
    }

    private static <T> TypeRef<?> arrayVariable() {
        return new TypeRef<T[]>() {};
    }

    private static <T> TypeRef<?> collectionArrayVariable() {
        return new TypeRef<List<T>[]>() {};
    }

    private static <T> TypeRef<?> upperVariable() {
        return new TypeRef<List<? extends T>>() {};
    }

    private static <T> TypeRef<?> lowerVariable() {
        return new TypeRef<List<? super T>>() {};
    }

    private static <T> TypeRef<?> ownerVariable() {
        return new TypeRef<Owner<T>.Member>() {};
    }

    @Test
    public void FR_DX_011_AC1_unresolvedNestedCollectionsAreRejectedAtConstruction() { // FR-DX-011 AC-1
        assertConcreteRequired(TypeRefRecursiveTest::listVariable);
        assertConcreteRequired(TypeRefRecursiveTest::nestedVariable);
    }

    @Test
    public void FR_DX_011_AC1_unresolvedGenericArrayComponentsAreRejectedAtConstruction() { // FR-DX-011 AC-1
        assertConcreteRequired(TypeRefRecursiveTest::arrayVariable);
        assertConcreteRequired(TypeRefRecursiveTest::collectionArrayVariable);
    }

    @Test
    public void FR_DX_011_AC1_unresolvedWildcardBoundsAreRejectedAtConstruction() { // FR-DX-011 AC-1
        assertConcreteRequired(TypeRefRecursiveTest::upperVariable);
        assertConcreteRequired(TypeRefRecursiveTest::lowerVariable);
    }

    @Test
    public void FR_DX_011_AC1_unresolvedEnclosingOwnerIsRejectedAtConstruction() { // FR-DX-011 AC-1
        assertConcreteRequired(TypeRefRecursiveTest::ownerVariable);
    }

    @Test
    public void FR_DX_011_AC2_concreteNestedCollectionsArraysOwnersAndWildcardsAreAccepted() { // FR-DX-011 AC-2
        assertTrue(new TypeRef<Map<String, List<Integer>>>() {}.getType() instanceof ParameterizedType);
        assertTrue(new TypeRef<List<String>[]>() {}.getType() instanceof GenericArrayType);
        assertTrue(new TypeRef<Owner<String>.Member>() {}.getType() instanceof ParameterizedType);
        ParameterizedType upper = (ParameterizedType) new TypeRef<List<? extends Number>>() {}.getType();
        WildcardType upperBound = (WildcardType) upper.getActualTypeArguments()[0];
        assertEquals(Number.class, upperBound.getUpperBounds()[0]);
        ParameterizedType lower = (ParameterizedType) new TypeRef<List<? super Integer>>() {}.getType();
        WildcardType lowerBound = (WildcardType) lower.getActualTypeArguments()[0];
        assertEquals(Integer.class, lowerBound.getLowerBounds()[0]);
        assertTrue(new TypeRef<List<?>>() {}.getType() instanceof ParameterizedType);
    }

    private static void assertConcreteRequired(org.junit.function.ThrowingRunnable construction) {
        IllegalStateException error = assertThrows(IllegalStateException.class, construction);
        assertEquals("TypeRef needs a concrete type argument", error.getMessage());
    }
}
