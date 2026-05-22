with open("pushCreate.kt", "r") as f:
    create_content = f.read()

with open("pushUpdate.kt", "r") as f:
    update_content = f.read()

# We need to extract the `when (meta.entityType)` into a single helper function.
# Let's see what inputs it requires. It needs all the DAOs.
# But `SyncService` already has all the DAOs since it is in `SyncService`.
