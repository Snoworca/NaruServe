# Carried into wave 4 (from wave 3)

- `listen()` runs the same route-table freeze that the wave 3 harness runs on the internal `RestApp` implementation (`DefaultRestApp`), and the public entry point of D-111 constructs that implementation; wave 3 added no public factory.
- The wave 3 rule that a registration or `routerPolicy` call after the freeze throws `IllegalStateException` becomes the `NEW`-state rule of D-114 and D-152.
- Wave 4 replaces the wave 3 interim failure handling: a handler exception gave `500` with an empty body before commit and aborted the response after commit; a handler that returned without finishing a non-streaming response had it finished by the dispatcher. D-126..D-134 behaviour replaces both.
- Wave 4 gives the error statuses (`400`, `404`, `405`, `426`, `501`, and `500`) the standard error body; in wave 3 they carry an empty body with `Content-Length: 0`.
- The pre-request rejections (unrecognised method `501`, raw path not starting with `/` `400`, path-validator rejection `400`, `OPTIONS *` `204`) are answered before a `Request` exists, so middleware and `onError` never see them; D-117 and D-129 apply to every request for which a `Request` is built, including a malformed query (`400`).
- The SPI exchange outlives `ExchangeHandler#handle` until the core calls `finish` or `abort`; the adapter may force-close only at its stop deadline (wave 3 `HttpExchange` javadoc). Shutdown (D-162 and the stop path) uses that deadline.
