with open("app/src/main/java/com/averycorp/prismtask/data/remote/SyncService.kt", "r") as f:
    content = f.read()

start_idx = content.find("private suspend fun pushCreate(")
end_idx = content.find("private suspend fun pushUpdate(")
print(content[start_idx:end_idx])
