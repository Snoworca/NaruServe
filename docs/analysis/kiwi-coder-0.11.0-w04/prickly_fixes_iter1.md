Fixes for prickly_review_iter1.json (run 0.11.0-w04):
P-1 empty secret/key -> treated as missing (PROD advice, DEV temp key); empty key file -> named error. Tests: KeySourcesTest.emptySecretIsMissing, fromFile empty file.
P-2 fromFile writes inside a fresh owner-only staging directory, checks chmod results, then renames. Residual: a rename race with another process is still possible (kept file wins only if it exists before the rename).
P-3 Javadoc/docs corrected: auth uses one hasher; a delegating hasher reads several formats (PasswordHashers.chain is FR-TOKEN-003).
P-4 HashCost.fixed below 50,000 -> doctor FAIL (PROD) / WARN (DEV); package-private test cost exempt. Test lowFixedCostIsAFindingByMode.
P-5 retryAfter validates limits. P-6 order by last failure; empty keys dropped. P-7 generation counter + pending WARN after the minute (test extended).
P-8 MiniJson.write(value, Limits). P-9 docs corrected. P-10 public half asserts anon. P-11 gate requires @Test adjacency; whitespace-tolerant regex.
P-12 comment. P-13 MessageDigest check. P-14 docs note. P-15 stored cap 2,000,000. P-16 dummy prepared at doctor (accepted-but-malformed timing left). P-17 file origin/messages.
P-18 exact negative longs, strict UTF-8, paired surrogates. P-19 bcrypt ERROR once a minute. P-20 short warm-up. P-21 FAIL on store error. P-22 path-scoped layers count only for their router. P-23 partner wait up to 10 s.
Not re-reviewed by a second prickly pass (Normal mode, one pass); full build green after fixes.
