# Duplication audit — wave-7 (stage 4)

Input: the stage-4 ranges on the integration branch — wave-7 `02be199..1e74c58` (merge `043ee25`), wave-8 `464cd31..354f5ad` (merge `7a1de25`), wave-9 `c2a554f..9378d6b` (merge `0dda584`), each limited to its lane write set (wave-8 also its two accepted Javadoc lines).

| symbol_or_block | lanes | paths | verdict |
|---|---|---|---|

No candidates found. Wave-7 changes only `TinyHttpdBodyStream` (TinyHttpd's `BodyTooLargeException` named by FQN) and adds TinyHttpd socket tests; waves 8 and 9 touch neither the TinyHttpd module nor its test helpers.
