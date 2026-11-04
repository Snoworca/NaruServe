package com.snoworca.naruserve.utils.auth.group;

import java.util.List;

/** Test decorators for group admin stores used by tests in other packages. */
public final class GroupAdminStoreAccess {

    private GroupAdminStoreAccess() {
    }

    /** Records removeUser calls into a shared order list. */
    public static final class Recording extends GroupAdminStoreTest.DelegatingAdminStore {
        private final List<String> order;

        public Recording(GroupAdminStore d, List<String> order) {
            super(d);
            this.order = order;
        }

        @Override
        public void removeUser(String userId) throws Exception {
            order.add("removeUser");
            super.removeUser(userId);
        }
    }
}
