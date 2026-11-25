package consumer.android;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import com.snoworca.naruserve.Deferred;
import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.Router;
import com.snoworca.naruserve.ServerHandle;
import com.snoworca.naruserve.gson.GsonJsonCodec;
import com.snoworca.naruserve.proxy.Proxy;
import com.snoworca.naruserve.proxy.ProxyException;
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
    /** Gateway app that forwards /backend/* to backendUrl through the published naruserve-proxy module. */
    public static RestApp createGateway(String backendUrl) {
        RestApp app=RestApp.create();
        app.use("/backend",Proxy.to(backendUrl).stripPrefix(true).connectTimeout(3,TimeUnit.SECONDS));
        app.onError((error,request,response)->{
            if(error instanceof ProxyException)response.status(((ProxyException)error).status()).json(Collections.singletonMap("upstream",((ProxyException)error).kind().name()));
            else response.status(500).json(Collections.singletonMap("error","internal"));
        });
        return app;
    }
    public static void stop(ServerHandle handle) { handle.shutdown(2,TimeUnit.SECONDS); }
    public static String defaultLoggerType() { return create().logger().getClass().getName(); }
    public static GsonJsonCodec explicitCodec() { return new GsonJsonCodec(new Gson()); }
    public static String parse(String json) { return new Gson().fromJson(json,Payload.class).message; }
}
