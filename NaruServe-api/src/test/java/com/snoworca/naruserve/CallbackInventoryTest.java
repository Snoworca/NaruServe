package com.snoworca.naruserve;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

import org.junit.Test;

/** Callback interfaces of the api jar are SAM types with @FunctionalInterface, listed in CallbackMatrix (FR-KT-003). */
public class CallbackInventoryTest {

    public interface Unannotated {
        void call(String value);
    }

    @FunctionalInterface
    public interface WithDefault {
        void call();

        @Override
        boolean equals(Object other);

        default void twice() {
            call();
            call();
        }
    }

    public interface NoAbstract {
        default void call() {
        }
    }

    public interface TwoAbstract {
        void first();

        void second();
    }

    public static final class Host {
        public void unannotated(Unannotated callback) {
        }

        public void withDefault(WithDefault callback) {
        }

        public void noAbstract(NoAbstract callback) {
        }

        public void twoAbstract(TwoAbstract callback) {
        }
    }

    private static final List<Class<?>> FIXTURES = Collections.unmodifiableList(Arrays.<Class<?>>asList(Host.class));

    private static List<String> names(Collection<Class<?>> types) {
        List<String> names = new ArrayList<>();
        for (Class<?> c : types) {
            names.add(c.getName());
        }
        return names;
    }

    /** Empty when equal; otherwise both one-sided differences, sorted. */
    private static String difference(Collection<String> inventory, Collection<String> declared) {
        TreeSet<String> missing = new TreeSet<>(inventory);
        missing.removeAll(declared);
        TreeSet<String> extra = new TreeSet<>(declared);
        extra.removeAll(inventory);
        if (missing.isEmpty() && extra.isEmpty()) {
            return "";
        }
        return "missing from CALLBACK_TYPES: " + missing + "; not in the inventory: " + extra;
    }

    // SDS-AC-1; FR-KT-003 AC-1
    @Test
    public void sdsAc1_FR_KT_003_AC1_realJarCallbacksAllCarryFunctionalInterface() {
        assertEquals(Collections.emptyList(),
                CallbackInventory.failures(ApiJarClasses.publicTypes(), CallbackMatrix.CALLBACK_TYPES));
    }

    // SDS-AC-1; FR-KT-003 AC-1
    @Test
    public void sdsAc1_FR_KT_003_AC1_nestedUnannotatedSingleMethodInterfaceIsNamed() {
        assertEquals(Arrays.asList(Unannotated.class.getName(), WithDefault.class.getName()),
                names(CallbackInventory.inventory(FIXTURES)));
        assertEquals(Collections.singletonList(Unannotated.class.getName() + " lacks @FunctionalInterface"),
                CallbackInventory.failures(FIXTURES, Collections.<String>emptyList()));
    }

    // SDS-AC-2; FR-KT-003 AC-2
    @Test
    public void sdsAc2_FR_KT_003_AC2_declaredZeroAndTwoAbstractInterfacesAreNamed() {
        List<String> declared = Arrays.asList(NoAbstract.class.getName(), TwoAbstract.class.getName(),
                WithDefault.class.getName());
        assertEquals(Arrays.asList(NoAbstract.class.getName() + " is not a SAM",
                        TwoAbstract.class.getName() + " is not a SAM",
                        Unannotated.class.getName() + " lacks @FunctionalInterface"),
                CallbackInventory.failures(FIXTURES, declared));
    }

    // SDS-AC-4; FR-KT-003 AC-4
    @Test
    public void sdsAc4_FR_KT_003_AC4_inventoryEqualsCallbackTypes() {
        assertEquals("", difference(names(CallbackInventory.inventory(ApiJarClasses.publicTypes())),
                CallbackMatrix.CALLBACK_TYPES));
    }

    // SDS-AC-4; FR-KT-003 AC-4
    @Test
    public void sdsAc4_FR_KT_003_AC4_removedEntryShowsAsDifference() {
        List<String> inventory = names(CallbackInventory.inventory(ApiJarClasses.publicTypes()));
        List<String> declared = new ArrayList<>(CallbackMatrix.CALLBACK_TYPES);
        String removed = declared.remove(0);
        declared.add("com.snoworca.naruserve.NotACallback");
        assertEquals("missing from CALLBACK_TYPES: [" + removed + "]; not in the inventory: "
                        + "[com.snoworca.naruserve.NotACallback]",
                difference(inventory, declared));
    }
}
