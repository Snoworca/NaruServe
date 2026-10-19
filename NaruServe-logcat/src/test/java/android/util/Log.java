package android.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Test-scope fake of the Android class: records each call as {@code method|tag|msg[|throwable message]}. */
public final class Log {

    public static final List<String> CALLS = Collections.synchronizedList(new ArrayList<String>());
    /** When set, every call throws it instead of recording. */
    public static volatile RuntimeException failure;

    private Log() {
    }

    private static int record(String call) {
        RuntimeException f = failure;
        if (f != null) {
            throw f;
        }
        CALLS.add(call);
        return 0;
    }

    public static int v(String tag, String msg) {
        return record("v|" + tag + "|" + msg);
    }

    public static int d(String tag, String msg) {
        return record("d|" + tag + "|" + msg);
    }

    public static int i(String tag, String msg) {
        return record("i|" + tag + "|" + msg);
    }

    public static int w(String tag, String msg) {
        return record("w|" + tag + "|" + msg);
    }

    public static int e(String tag, String msg) {
        return record("e|" + tag + "|" + msg);
    }

    public static int e(String tag, String msg, Throwable tr) {
        return record("e|" + tag + "|" + msg + "|" + tr.getMessage());
    }
}
