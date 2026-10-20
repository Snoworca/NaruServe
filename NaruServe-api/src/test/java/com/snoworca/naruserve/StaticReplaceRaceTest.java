package com.snoworca.naruserve;

import static com.snoworca.naruserve.StaticTraversalTest.write;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.TestApp;
import com.sun.management.UnixOperatingSystemMXBean;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** FR-STATIC-009: a file replaced between the stat and the open is never sent truncated under the old validators. */
public class StaticReplaceRaceTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void FR_STATIC_009_fileReplacedByALargerOneBetweenStatAndOpenIsAnsweredWhole() throws Exception {
        File root = new File(tmp.getRoot(), "root");
        File f = new File(root, "bundle.js");
        write(f, "OLD");
        TestApp t = TestApp.create();
        t.app().staticFiles("/", root);
        assertEquals("OLD", t.send("GET", "/bundle.js").text()); // starts the app
        AtomicBoolean once = new AtomicBoolean();
        hook(t, () -> {
            if (once.compareAndSet(false, true)) {
                try {
                    write(f, "NEW CONTENT, LONGER");
                } catch (java.io.IOException e) {
                    throw new AssertionError(e);
                }
            }
        });
        ClientResponse r = t.send("GET", "/bundle.js");
        assertEquals(200, r.status());
        assertEquals("NEW CONTENT, LONGER", r.text());
    }

    /** A file deleted between the open and the re-stat must not leave the just-opened stream open. */
    @Test
    public void FR_STATIC_009_fileDeletedBetweenOpenAndRestatClosesTheOpenedStream() throws Exception {
        File root = new File(tmp.getRoot(), "root");
        File f = new File(root, "gone.js");
        write(f, "OLD");
        TestApp t = TestApp.create();
        t.app().staticFiles("/", root);
        assertEquals("OLD", t.send("GET", "/gone.js").text()); // starts the app
        UnixOperatingSystemMXBean os = (UnixOperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        int rounds = 30;
        long before = os.getOpenFileDescriptorCount();
        for (int i = 0; i < rounds; i++) {
            write(f, "OLD");
            hook(t, "afterOpen", f::delete);
            assertEquals(404, t.send("GET", "/gone.js").status());
        }
        assertTrue("leaked descriptors: " + (os.getOpenFileDescriptorCount() - before),
                os.getOpenFileDescriptorCount() - before < rounds / 2);
    }

    private static void hook(TestApp t, Runnable hook) throws Exception {
        hook(t, "afterCheck", hook);
    }

    private static void hook(TestApp t, String name, Runnable hook) throws Exception {
        Object dispatcher = t.app().getClass().getMethod("dispatcher").invoke(t.app());
        Object statics = field(dispatcher, "statics");
        Object mount = ((List<?>) field(statics, "mounts")).get(0);
        Method resolverM = mount.getClass().getDeclaredMethod("resolver");
        resolverM.setAccessible(true);
        Object resolver = resolverM.invoke(mount);
        Method hookM = resolver.getClass().getDeclaredMethod(name, Runnable.class);
        hookM.setAccessible(true);
        hookM.invoke(resolver, hook);
    }

    private static Object field(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }
}
