import re

with open("app/src/main/java/com/averycorp/prismtask/data/remote/SyncService.kt", "r") as f:
    content = f.read()

# I noticed there is a difference in the 'when' block. `pushCreate` has:
#             "habit_completion" -> {
#                 val completion = habitCompletionDao.getAllCompletionsOnce().find { it.id == meta.localId }
#                 if (completion == null) {
#                     logger.error(...)
#                     return
#                 }
#                 ...
#             "habit_log" -> {
#                 ...
#             "task_completion" -> {
#                 ...
#             "task_timing" -> {
#                 ...
#
# Wait, `pushUpdate` doesn't have "habit_completion", "habit_log", "task_completion", or "task_timing" in its `when` block?
