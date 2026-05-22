import re

def process():
    with open("app/src/main/java/com/averycorp/prismtask/data/remote/SyncService.kt", "r") as f:
        content = f.read()

    # Find the pushUpdate method
    start_idx = content.find("private suspend fun pushUpdate(")
    if start_idx == -1:
        print("Not found")
        return

    # Find the when block
    when_start = content.find("val data = when (meta.entityType) {", start_idx)

    # We will just write a python script that transforms the code or do it manually.
