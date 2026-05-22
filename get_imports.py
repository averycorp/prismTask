with open("app/src/main/java/com/averycorp/prismtask/data/remote/SyncService.kt", "r") as f:
    content = f.read()

# We'll just extract the whole `val data = when(...)` and put it into a `mapEntityToPayload` function.
