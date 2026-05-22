with open("app/src/main/java/com/averycorp/prismtask/data/remote/SyncService.kt", "r") as f:
    content = f.read()

start_create = content.find("        val data = when (meta.entityType) {", content.find("private suspend fun pushCreate("))
end_create = content.find("        docRef.set(data).await()", start_create)

start_update = content.find("        val data = when (meta.entityType) {", content.find("private suspend fun pushUpdate("))
end_update = content.find("        // Delete-wins contract:", start_update)

create_when_block = content[start_create:end_create]

# Map func
map_entity_func = f"""
    private suspend fun mapEntityToPayload(meta: SyncMetadataEntity): Any? {{
{create_when_block.replace("        val data = ", "        return ")}    }}
"""

# Replace in pushCreate
new_push_create_content = content[:start_create] + "        val data = mapEntityToPayload(meta) ?: return\n" + content[end_create:]

# Update the content first with pushCreate replacement
content = new_push_create_content

# Now find update again
start_update = content.find("        val data = when (meta.entityType) {", content.find("private suspend fun pushUpdate("))
end_update = content.find("        // Delete-wins contract:", start_update)

new_push_update_content = content[:start_update] + "        val data = mapEntityToPayload(meta) ?: return\n" + content[end_update:]

# Add the new function before pushCreate
idx_push_create = new_push_update_content.find("    @Suppress(\"ReturnCount\", \"CyclomaticComplexMethod\", \"LongMethod\")\n    // Dispatch across every synced entityType")

final_content = new_push_update_content[:idx_push_create] + map_entity_func + "\n" + new_push_update_content[idx_push_create:]

# Wait, `mapEntityToPayload` uses `return` but the `return` inside the when block, like `?: return`, will return from `mapEntityToPayload` to the caller!
# Wait! In Kotlin, `?: return` inside `mapEntityToPayload` will return from `mapEntityToPayload`, which means it returns `null` because the return type of `mapEntityToPayload` is `Any?`?
# NO! `return` returns from the innermost function, which is `mapEntityToPayload`. Wait, `?: return null` is better, but `?: return` returns `Unit` by default if the return type is `Unit`. Since `mapEntityToPayload` returns `Any?`, `?: return` will fail to compile because it's returning `Unit` instead of `null`!
# Let's check!
