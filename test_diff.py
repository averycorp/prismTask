with open("pushCreate.kt", "r") as f:
    create_content = f.read()

with open("pushUpdate.kt", "r") as f:
    update_content = f.read()

create_when = create_content[create_content.find("val data = when"):create_content.rfind("docRef.set(data)")].strip()
update_when = update_content[update_content.find("val data = when"):update_content.rfind("// Delete-wins contract")].strip()

# We need to remove the line numbers if any. But I got strings directly so they don't have line numbers
import re

create_when_clean = re.sub(r'^\s*\d+\s+', '', create_when, flags=re.MULTILINE)
update_when_clean = re.sub(r'^\s*\d+\s+', '', update_when, flags=re.MULTILINE)

# And remove // comments to ignore "course not yet synced..." comments difference
create_when_clean = re.sub(r'//.*$', '', create_when_clean, flags=re.MULTILINE)
update_when_clean = re.sub(r'//.*$', '', update_when_clean, flags=re.MULTILINE)

# Compare ignoring whitespace
def normalize(s):
    return re.sub(r'\s+', ' ', s).strip()

if normalize(create_when_clean) == normalize(update_when_clean):
    print("Logic inside when is identical!")
else:
    print("Not identical.")
    import difflib
    diff = difflib.unified_diff(
        [l.strip() for l in create_when_clean.splitlines() if l.strip()],
        [l.strip() for l in update_when_clean.splitlines() if l.strip()]
    )
    for line in diff:
        print(line)
