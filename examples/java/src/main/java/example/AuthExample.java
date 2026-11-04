package example;

import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.utils.auth.Auth;
import com.snoworca.naruserve.utils.auth.AuthMode;
import com.snoworca.naruserve.utils.auth.rbac.RolePolicy;
import java.util.concurrent.TimeUnit;

/** Complete auth example: DEV mode, seed users, a public, a protected and an editor-only route. @req OPS-AUTH-007 */
public final class AuthExample {
    private AuthExample() {}

    public static RestApp create() {
        Auth auth = Auth.builder(System.getenv("JWT_SECRET"))
            .mode(AuthMode.DEV)                                   // local development only; see docs/api/en/auth.md to deploy
            .roles(RolePolicy.builder().role("editor", "posts:read", "posts:write").build())
            .user("alice", "alice-password-1", "editor")          // created at start when missing
            .user("bob", "bob-password-1")                        // signed in, but no role
            .permit("/health")                                    // GET and HEAD /health are public
            .build();

        RestApp app = RestApp.create();
        app.use(auth);                                            // before the routes
        app.get("/health", (req, res) -> res.send("ok"));
        app.get("/me", (req, res) -> res.send(auth.user(req).getUsername()));
        app.post("/posts", auth.require("posts:write"), (req, res) ->
            res.status(201).send("created by " + auth.user(req).getUsername()));
        app.onError(auth.errors());                               // last: auth errors as JSON
        return app;
    }

    public static void main(String[] args) {
        RestApp app = create();
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 3000;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> app.shutdown(5, TimeUnit.SECONDS)));
        System.out.println("Listening on http://127.0.0.1:" + app.listen(port).port());
    }
}
