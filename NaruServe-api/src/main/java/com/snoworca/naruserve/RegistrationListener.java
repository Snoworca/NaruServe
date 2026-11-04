package com.snoworca.naruserve;

/**
 * Watches registrations: {@link RestApp#onRegistration(RegistrationListener)} calls it on the registering thread for
 * each valid registration before it takes effect. Throwing vetoes the registration: it is not applied, not recorded,
 * and the exception reaches the caller of {@code get}, {@code use}, ... unchanged.
 */
// @req IR-APP-008
// @req SEC-APP-002
@FunctionalInterface
public interface RegistrationListener {
    /**
     * Called before {@code registration} is applied; must not register, listen, freeze or shut down (each throws
     * {@link IllegalStateException}). It runs holding a registration lock shared by every app, so it must not wait
     * for other threads; calling a method of another app throws {@link IllegalStateException} at once instead of
     * deadlocking. There is no rollback call: when a Router mounted in several apps is vetoed by the watcher of a
     * later app, the watchers of earlier apps already saw the record.
     */
    void onRegistration(Registration registration);
}
