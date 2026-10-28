package consumer.android;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import com.snoworca.naruserve.Deferred;
import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.Router;
import com.snoworca.naruserve.ServerHandle;
import com.snoworca.naruserve.gson.GsonJsonCodec;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

/** Kept application entry only; all NaruServe reflection rules must come from consumed artifacts. */
public final class ServerEntry {
    public static final class Payload { @SerializedName("message") public String message; }
    public static RestApp create() {
        RestApp app=RestApp.create();
        Router router=Router.create();router.get("/files/*parts",(request,response)->response.json(Collections.singletonMap("parts",request.paramValues("parts"))));app.use("/api",router);
        app.getAsync("/ready",(request,response,next)->{response.json(Collections.singletonMap("ready",true));return Deferred.resolved();});
        return app;
    }
    public static ServerHandle start() { return create().listen(0); }
    public static void stop(ServerHandle handle) { handle.shutdown(2,TimeUnit.SECONDS); }
    public static String defaultLoggerType() { return create().logger().getClass().getName(); }
    public static GsonJsonCodec explicitCodec() { return new GsonJsonCodec(new Gson()); }
    public static String parse(String json) { return new Gson().fromJson(json,Payload.class).message; }
}
