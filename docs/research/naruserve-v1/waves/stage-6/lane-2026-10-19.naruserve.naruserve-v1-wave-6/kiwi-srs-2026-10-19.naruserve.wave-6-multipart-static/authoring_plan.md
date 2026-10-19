# Wave 6 SRS authoring plan (kiwi-srs run 2026-10-19.naruserve.wave-6-multipart-static)

Target `wave-6`, status `planned`, tags `wave-6, feasibility:high, <area>`. Every AC ends with its `[D-xxx]` / `[wave decision: <name>]` / `[residual: ...]` / `[carried: V1-00x]` tag. Rationale lists the D-ids covered. Code traces: `notes: trace_intent=addition_site`. githubIssue: MULTIPART -> https://github.com/Snoworca/NaruServe/issues/11, STATIC -> https://github.com/Snoworca/NaruServe/issues/12.

## C-07 implementer decisions (state them in the AC as "Decision: ...")

Multipart:
- Config entry: `RestApp multipart(Configurer<MultipartOptions> configurer)`; public interface `com.snoworca.naruserve.MultipartOptions` with fluent setters `maxFileSize(long bytes)` 32 MiB (33554432), `maxRequestSize(long bytes)` 64 MiB (67108864), `maxParts(int)` 20, `memoryThreshold(int bytes)` 64 KiB (65536), `tempDirectory(File)` default `new File(System.getProperty("java.io.tmpdir"))` (on Android the app cache dir). Every size/count setter rejects `0`, negatives and has no value meaning unlimited (IllegalArgumentException naming the setting); inclusive ranges: 1..4 GiB (4294967296) for maxFileSize and maxRequestSize, 1..10000 for maxParts, 1..16 MiB for memoryThreshold; `tempDirectory(null)` NPE. Unlimited limits are not allowed (D-239).
- Part header limits (D-235, wave decision Oversized part headers): fixed, not configurable: part header line 8192 bytes, total header size per part 16384 bytes; above -> 413; recorded in `docs/api/defaults.md`.
- Form limit (D-238): `RestApp formLimit(long bytes)` default 1 MiB (1048576), range 1..1 GiB, mirrors `jsonLimit`; Express `express.urlencoded({ limit })`. Recorded in request.md and defaults.md.
- `parts()` (D-239): `List<UploadFile> parts()`: every part of a multipart body in body order, text fields included (their `filename()` is `null`); empty list for a urlencoded or non-form body.
- `UploadFile.bytes()` in-memory limit (D-239, D-251): fixed 8 MiB (8388608); above it `bytes()` throws `IllegalStateException` naming the size and the limit; documented in multipart.md and defaults.md.
- `UploadFile.filename()` and `contentType()` are `@Nullable` (null when the part header has none).
- `req.form(name)` on a body that is neither urlencoded nor multipart returns `null` without reading the body.
- Using an `UploadFile` after its request ended -> `IllegalStateException`.

Static:
- `RestApp staticFiles(String prefix, File dir)` and `RestApp staticFiles(String prefix, File dir, Configurer<StaticOptions> options)`; `StaticOptions` setters `index(String)`, `directoryListing(boolean)`, `spaFallback(boolean)`, `spaExclude(String... prefixes)`, `cache(String pattern, CachePolicy policy)`, `mime(String ext, String type)`, `allowSymlinks(boolean)` (default false; D-272: when true, symbolic links below the root are served but the canonical-root containment of D-269 still applies).
- SPA exclusions (D-267): `/api/` and `/ws/` always; `spaExclude` adds prefixes, matching the full request path on segment boundaries.
- Directory without trailing slash: `301` to path + `/` (query kept), `Location` from the raw encoded path; empty segment (`//assets`) -> `404`.
- Cache pattern syntax (D-282): pattern is matched against the path relative to the mount prefix, beginning with `/`; a pattern not starting with `/` is matched as if prefixed by `**/`; `*` matches within one segment, `**` matches zero or more segments (so `**/*.html` matches `/index.html`); when several patterns match, the first registered wins.
- Server default Cache-Control (D-410): `RestApp defaultCache(@Nullable CachePolicy policy)`, default `null` (no header added; Express sends none); applied only when no `Cache-Control` was set by `res.cache`, `res.header`, an `options.cache` pattern or a built-in header.
- `app.mimeTypes()` returns `MimeTypes` with `MimeTypes register(String ext, String type)`; per app.
- `res.cache(CachePolicy)`, `res.file(File)`, `res.download(File, String filename)`; `res.file` sends no `Content-Disposition` (D-293, like Express `res.sendFile`).
- ETag: strong, `"<hex length>-<hex lastModified millis>"`.
- `ResourceResolver` public interface `com.snoworca.naruserve.ResourceResolver` with `open`, `length`, `etag`, `modified`; built-in `java.io.File` resolver is the only v1 implementation; no public method accepts another implementation in v1 (SAF resolver post-v1 P5, D-380).

## Requirement breakdown

MULTIPART (docs/spec/09.multipart.srs.md):
1. IR — Multipart and form configuration API (D-231, D-232, D-238 API part, D-239 tempDirectory/unlimited, D-235 values)
2. IR — Request form/file/files/parts accessors and the UploadFile interface (D-240, D-241, D-248, D-253, D-239 parts type/bytes limit); Kotlin compile test (C-03)
3. FR — Engine-free multipart parser shared by every adapter: boundary 400, nested part ordinary, part-header limits 413 (D-235, D-236, D-237, D-247, wave decision Oversized part headers)
4. FR — Accumulated-byte limits 413 incl. chunked/no Content-Length, part count, urlencoded limit (D-233, D-234, D-238)
5. FR — form/file/files/parts behaviour, mixed request T13, one parse under BodyState FORM (D-240, D-241, D-242)
6. FR — Memory/disk spooling, heap bound T14, server-generated temp names, filename unchanged T16 (D-243..D-246)
7. FR — UploadFile save/transferTo/bytes/after-request behaviour T18 (D-249..D-252)
8. FR — Temp cleanup at request end, after save, after 413, disconnect T15/T17, after-onStop shutdown participant closing the spool registry -> HttpException(500) (D-254, D-255, carried V1-003, wave decision Spool cleanup closes the spool registry)
9. OPS — Wave 6 multipart decisions recorded in docs/api (multipart.md, request.md, defaults.md) with markers removed (D-238, D-239)

STATIC (docs/spec/10.static-files.srs.md):
1. IR — staticFiles, StaticOptions and ResourceResolver API (D-256, D-261, D-409, D-272 setting)
2. IR — CachePolicy, MimeTypes, res.cache/res.file/res.download and app.defaultCache API (D-277, D-278, D-280, D-292, D-410 API)
3. FR — Mount mapping, index, multiple mounts, routes first, global middleware T35, trailing-slash 301 (D-256..D-259, D-407, D-267 redirect part)
4. FR — Method handling on static paths in the dispatcher: 405 Allow: GET, HEAD, GET/HEAD fall-through on route method mismatch, OPTIONS 405, 501 first, ws path ownership, residual meaning of "served only by a static mount" incl. POST missing -> 404 (D-260, wave decisions Static files after a route's method mismatch / OPTIONS on a static-only path, residual)
5. FR — Directory index 404, directory listing format, nosniff, bounded streaming (D-262, D-420, D-263, D-264)
6. FR — SPA fallback conditions and exclusions (D-265, D-266, D-267)
7. FR — Path traversal 400 and hidden segments 404 (D-268, D-271, wave decision Dot segments)
8. FR — Canonical root, symlink refusal, allowSymlinks, post-open re-check, res.file root rule, Java 8 canon cache (D-269, D-270, D-411, D-447, D-272, carried V1-008, wave decisions root of res.file / Java 8 canonical-path cache)
9. FR — MIME lookup order, built-in table, case-insensitive, text charset, per-app registry T28 (D-273..D-277)
10. FR — CachePolicy values, res.cache, options.cache patterns and syntax, default Cache-Control T32 (D-278..D-282, D-410)
11. FR — ETag/Last-Modified/Accept-Ranges, 304 precedence, HEAD T30 (D-283..D-286)
12. FR — Single byte ranges 206/416, multi/malformed 200, If-Range, combined test (D-287..D-290, D-432)
13. FR — res.file/res.download, Content-Disposition, engine-free evaluation, bodies from the opened stream through commit (D-291, D-292, D-293, D-409, wave decision File bodies come from the opened stream)
14. OPS — Wave 6 static decisions recorded in docs/api (static.md, response.md, defaults.md, express-migration.md) with markers removed (D-267, D-272, D-282, D-293, D-410, D-447 doc, W2-004 doc)

Supersedes of verified wave 1-5 requirements: see existing_srs_context.md.
