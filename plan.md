1. **Analyze the Problem**:
   The `TODO` item mentions: "refactor pushUpdate to reduce early return statements."
   Currently, `pushUpdate` repeats a huge `when` block of 100+ lines that exists almost exactly identically in `pushCreate` (with few minor exceptions that should be safe to merge/copy like `habit_completion` missing in `pushUpdate` which is just extra payload mappings).
   Both `pushCreate` and `pushUpdate` do a ton of `?: return` checking.

2. **Design the Solution**:
   - Extract the entire `when (meta.entityType)` block from `pushCreate` into a shared helper function:
     ```kotlin
     @Suppress("CyclomaticComplexMethod", "LongMethod")
     private suspend fun mapEntityToPayload(meta: SyncMetadataEntity): Any? {
         return when (meta.entityType) { ... }
     }
     ```
   - Change `?: return` to `?: return null` in `mapEntityToPayload`.
   - Update `pushCreate` to use `val data = mapEntityToPayload(meta) ?: return`.
   - Update `pushUpdate` to use `val data = mapEntityToPayload(meta) ?: return`.
   - Ensure `@Suppress("ReturnCount")` is removed if it's no longer needed on `pushCreate` / `pushUpdate`, but added to `mapEntityToPayload` instead.
   - Address the TODO comment on `pushUpdate` and remove it.

3. **Check Pre-commit**: Provide instructions and run pre-commit steps (build/lint).
4. **Commit & Submit**.
