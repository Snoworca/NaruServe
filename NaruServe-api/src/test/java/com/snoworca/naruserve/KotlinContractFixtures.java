package com.snoworca.naruserve;

import org.jspecify.annotations.NonNull;

/** Negative fixtures of {@link KotlinContractChecks}; they live on the test class path, never in the api jar. */
final class KotlinContractFixtures {

    /** A package name with no package-info class, so it cannot carry {@code @NullMarked}. */
    static final String BARE_PACKAGE = "com.snoworca.naruserve.fixture.bare";

    private KotlinContractFixtures() {
    }

    /** {@code query} is a nullable row of the accessor table, but its return type lacks {@code @Nullable}. */
    public static class Unannotated {
        public String query(String name) {
            return name;
        }
    }

    /** Carries {@code @NonNull}, which the public API must not. */
    public static class NonNullMember {
        public @NonNull String name() {
            return "n";
        }
    }

    /** One method per Kotlin hard keyword that is a legal Java identifier. */
    public static class Keywords {
        public void as() {
        }

        public void fun() {
        }

        public void in() {
        }

        public void is() {
        }

        public void object() {
        }

        public void typealias() {
        }

        public void typeof() {
        }

        public void val() {
        }

        protected void var() {
        }

        public void when() {
        }
    }

    /** Soft keywords and an is-prefixed name, which Kotlin calls without backticks. */
    public static class SoftNames {
        public void get() {
        }

        public void set() {
        }

        public void use() {
        }

        public void value() {
        }

        public boolean isSecure() {
            return false;
        }
    }
}
