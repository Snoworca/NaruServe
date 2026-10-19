package com.snoworca.naruserve;

import static com.snoworca.naruserve.StaticTraversalTest.write;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.snoworca.naruserve.testing.InMemoryHttpAdapter.ClientResponse;
import com.snoworca.naruserve.testing.TestApp;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** T29 symbolic links over the in-memory adapter. SDS-AC-n cites docs/sds/2026-10-19.naruserve.naruserve-v1-wave-6-7.sds.md. */
public class StaticSymlinkTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    private File root;
    private File outside;

    private void tree() throws IOException {
        root = new File(tmp.getRoot(), "root");
        outside = new File(tmp.getRoot(), "outside");
        write(new File(root, "real.txt"), "REAL");
        write(new File(root, "dir/in.txt"), "INSIDE");
        write(new File(outside, "secret.txt"), "OUTSIDE");
        write(new File(outside, "dir/in.txt"), "OUTSIDE");
        Files.createSymbolicLink(new File(root, "inlink.txt").toPath(), new File(root, "real.txt").toPath());
        Files.createSymbolicLink(new File(root, "outlink.txt").toPath(), new File(outside, "secret.txt").toPath());
        Files.createSymbolicLink(new File(root, "indir").toPath(), new File(root, "dir").toPath());
    }

    // SDS-AC-1; FR-STATIC-006 AC-2 (T29)
    @Test
    public void sdsAc1_FR_STATIC_006_AC2_linkToFileInsideRootIsRefused404ByDefault() throws IOException {
        tree();
        TestApp t = TestApp.create();
        t.app().staticFiles("/", root);
        assertEquals("REAL", t.send("GET", "/real.txt").text());
        assertEquals(404, t.send("GET", "/inlink.txt").status());
        assertEquals(404, t.send("GET", "/indir/in.txt").status());
        ClientResponse out = t.send("GET", "/outlink.txt");
        assertEquals(404, out.status());
        assertFalse(out.text().contains("OUTSIDE"));
    }

    // SDS-AC-1; FR-STATIC-006 AC-1
    @Test
    public void sdsAc1_FR_STATIC_006_AC1_rootReachedThroughLinkServesButLinkInsideRefused() throws IOException {
        tree();
        File link = new File(tmp.getRoot(), "link");
        Files.createSymbolicLink(link.toPath(), root.toPath());
        TestApp t = TestApp.create();
        t.app().staticFiles("/", link);
        assertEquals("REAL", t.send("GET", "/real.txt").text());
        assertEquals("INSIDE", t.send("GET", "/dir/in.txt").text());
        assertEquals(404, t.send("GET", "/inlink.txt").status());
    }

    // SDS-AC-2; FR-STATIC-006 AC-3
    @Test
    public void sdsAc2_FR_STATIC_006_AC3_allowSymlinksServesInsideTargetsAndRefusesOutside() throws IOException {
        tree();
        TestApp t = TestApp.create();
        t.app().staticFiles("/", root, o -> o.allowSymlinks(true));
        ClientResponse in = t.send("GET", "/inlink.txt");
        assertEquals(200, in.status());
        assertEquals("REAL", in.text());
        assertEquals("INSIDE", t.send("GET", "/indir/in.txt").text());
        ClientResponse out = t.send("GET", "/outlink.txt");
        assertEquals(404, out.status());
        assertFalse(out.text().contains("OUTSIDE"));
    }

    // SDS-AC-3; FR-STATIC-006 AC-4 (T29 swap through the afterCheck hook)
    @Test
    public void sdsAc3_FR_STATIC_006_AC4_componentSwappedForLinkBetweenCheckAndOpenIsRefused() throws Exception {
        tree();
        TestApp t = TestApp.create();
        t.app().staticFiles("/", root);
        assertEquals("INSIDE", t.send("GET", "/dir/in.txt").text()); // starts the app and proves the path serves
        File dir = new File(root, "dir");
        Runnable swap = () -> {
            try {
                assertTrue(dir.renameTo(new File(tmp.getRoot(), "moved")));
                Files.createSymbolicLink(dir.toPath(), new File(outside, "dir").toPath());
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        };
        afterCheck(t, swap);
        ClientResponse r = t.send("GET", "/dir/in.txt");
        assertEquals(404, r.status());
        assertFalse(r.text().contains("OUTSIDE"));
    }

    // SDS-AC-6; FR-STATIC-006 AC-6
    @Test
    public void sdsAc6_FR_STATIC_006_AC6_testJvmRunsWithCanonCachesOff() {
        assertEquals("false", System.getProperty("sun.io.useCanonCaches"));
    }

    /** Sets the test hook of the first mount's resolver: app, dispatcher, statics, mounts[0], resolver. */
    private static void afterCheck(TestApp t, Runnable hook) throws Exception {
        Object dispatcher = t.app().getClass().getMethod("dispatcher").invoke(t.app());
        Object statics = field(dispatcher, "statics");
        Object mount = ((List<?>) field(statics, "mounts")).get(0);
        Method resolverM = mount.getClass().getDeclaredMethod("resolver");
        resolverM.setAccessible(true);
        Object resolver = resolverM.invoke(mount);
        Method hookM = resolver.getClass().getDeclaredMethod("afterCheck", Runnable.class);
        hookM.setAccessible(true);
        hookM.invoke(resolver, hook);
    }

    private static Object field(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }
}
