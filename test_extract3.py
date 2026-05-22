with open("app/src/main/java/com/averycorp/prismtask/data/remote/SyncService.kt", "r") as f:
    content = f.read()

start_create = content.find("        val data = when (meta.entityType) {", content.find("private suspend fun pushCreate("))
end_create = content.find("        docRef.set(data).await()", start_create)

create_when_block = content[start_create:end_create]

# Wait, `val task = taskDao.getTaskByIdOnce(meta.localId) ?: return`
# If we extract this to a function that returns `Any?`, we need to change `?: return` to `?: return null`.
import re

create_when_block = re.sub(r'\?: return(\s*\n)', r'?: return null\1', create_when_block)
create_when_block = re.sub(r'else -> return(\s*\n)', r'else -> return null\1', create_when_block)
create_when_block = create_when_block.replace("        val data = ", "        return ")
# Also fixing any explicit return, like in habit_completion error logging:
# if (completion == null) { ... return } -> if (completion == null) { ... return null }
create_when_block = re.sub(r'(\s+)return(\s*\n)', r'\1return null\2', create_when_block)

print(create_when_block)
