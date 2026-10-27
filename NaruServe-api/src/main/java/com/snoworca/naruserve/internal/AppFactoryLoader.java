package com.snoworca.naruserve.internal;

import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.spi.AppFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/** {@link AppFactory} lookup for {@link RestApp#create()}; public only because RestApp lives in another package. */
// @req FR-APP-001
public final class AppFactoryLoader {

    private AppFactoryLoader() {
    }

    /** The app of the single provider {@code loader} exposes; never consults the thread context class loader. */
    // @req FR-APP-001
    public static RestApp create(ClassLoader loader) {
        List<AppFactory> found = new ArrayList<>();
        for (AppFactory f : ServiceLoader.load(AppFactory.class, loader)) {
            found.add(f);
        }
        if (found.isEmpty()) {
            throw new IllegalStateException("No " + AppFactory.class.getName() + " provider found. Add the "
                    + "com.snoworca.naruserve:naruserve module to use RestApp.create(), or assemble the app with "
                    + "RestApp.builder().httpAdapter(...).jsonCodec(...).build()");
        }
        if (found.size() > 1) {
            List<String> names = new ArrayList<>();
            for (AppFactory f : found) {
                names.add(f.getClass().getName());
            }
            throw new IllegalStateException("More than one " + AppFactory.class.getName() + " provider found: "
                    + names + "; keep exactly one on the class path");
        }
        return found.get(0).create();
    }
}
