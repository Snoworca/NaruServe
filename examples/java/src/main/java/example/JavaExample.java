package example;

import com.snoworca.naruserve.HttpException;
import com.snoworca.naruserve.RestApp;
import com.snoworca.naruserve.UploadFile;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Complete JVM example. @req OPS-DX-001 */
public final class JavaExample {
    public static final class CreateUserRequest {
        public String name; // Gson calls the no-argument constructor; missing fields remain null.
        public CreateUserRequest() {}
    }

    private static final class UserService {
        private final AtomicInteger nextId = new AtomicInteger();
        private final ConcurrentHashMap<String, String> names = new ConcurrentHashMap<>();

        Map<String, String> create(String name) {
            String id = Integer.toString(nextId.incrementAndGet());
            names.put(id, name);
            return user(id);
        }
        Map<String, String> user(String id) {
            String name = names.get(id);
            if (name == null) throw new HttpException(404, "User not found");
            Map<String, String> user = new LinkedHashMap<>();
            user.put("id", id);
            user.put("name", name);
            return user;
        }
    }

    public static RestApp create(File uploads) throws IOException {
        if (!uploads.isDirectory() && !uploads.mkdirs() && !uploads.isDirectory()) {
            throw new IOException("Cannot create upload directory: " + uploads);
        }
        RestApp app = RestApp.create();
        UserService users = new UserService();
        app.get("/hello", (req, res) -> res.send("Hello World"));
        app.get("/page", (req, res) -> res.send(Integer.toString(req.queryInt("page", 1))));
        app.get("/users/:id", (req, res) -> res.send(users.user(req.param("id"))));
        app.post("/users", (req, res) -> {
            CreateUserRequest input = req.body(CreateUserRequest.class);
            if (input == null) throw new HttpException(400, "JSON body is null");
            if (input.name == null || input.name.trim().isEmpty()) throw new HttpException(400, "name is required");
            res.status(201).send(users.create(input.name.trim()));
        });
        app.post("/upload", (req, res) -> {
            UploadFile file = req.file("file");
            if (file == null || file.size() == 0) throw new HttpException(400, "Missing file");
            file.save(new File(uploads, UUID.randomUUID() + ".bin"));
            res.status(201).send(java.util.Collections.singletonMap("size", file.size()));
        });
        app.get("/stream", (req, res) -> {
            // Ownership passes to NaruServe, which closes the stream after transfer. Do not wrap in try-with-resources.
            res.stream(new ByteArrayInputStream("stream example".getBytes(StandardCharsets.UTF_8)), "text/plain; charset=UTF-8");
        });
        return app;
    }

    public static void main(String[] args) throws IOException {
        RestApp app = create(new File(args.length > 1 ? args[1] : "./uploads"));
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 3000;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> app.shutdown(5, TimeUnit.SECONDS)));
        System.out.println("Listening on http://127.0.0.1:" + app.listen(port).port());
    }
}
