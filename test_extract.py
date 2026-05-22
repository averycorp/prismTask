with open("app/src/main/java/com/averycorp/prismtask/data/remote/SyncService.kt", "r") as f:
    content = f.read()

# I want to take the 'when' block from 'pushCreate' and put it in 'private suspend fun mapEntityToPayload(meta: SyncMetadataEntity): Any?'.
# Wait, actually 'pushUpdate' and 'pushCreate' can share exactly the same 'when' block because they map entity objects to map/payload arrays!
# Since 'pushUpdate' does not have 'habit_completion', 'habit_log', 'task_completion', 'task_timing', if we merge them, 'pushUpdate' will just support updating them, which is correct or harmless because if they are not updated, the trigger won't run.

start_create = content.find("        val data = when (meta.entityType) {", content.find("private suspend fun pushCreate("))
end_create = content.find("        docRef.set(data).await()", start_create)

start_update = content.find("        val data = when (meta.entityType) {", content.find("private suspend fun pushUpdate("))
end_update = content.find("        // Delete-wins contract:", start_update)

# Let's extract the when block from pushCreate
create_when_block = content[start_create:end_create]

# Wait, `val data = when (meta.entityType) {` should be `return when (meta.entityType) {`
map_entity_func = f"""
    private suspend fun mapEntityToPayload(meta: SyncMetadataEntity): Any? {{
{create_when_block.replace("        val data = ", "        return ")}    }}
"""

print(map_entity_func[:500])
