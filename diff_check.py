with open("pushCreate.kt", "r") as f:
    create_content = f.read()

with open("pushUpdate.kt", "r") as f:
    update_content = f.read()

create_when = create_content[create_content.find("val data = when"):create_content.rfind("docRef.set(data)")]
update_when = update_content[update_content.find("val data = when"):update_content.rfind("try {")]

# Remove everything after the when block brace
create_when = create_when[:create_when.rfind("}")+1]
update_when = update_when[:update_when.rfind("}")+1]

if create_when == update_when:
    print("Identical!")
else:
    print("Not identical!")
    import difflib
    diff = difflib.unified_diff(create_when.splitlines(), update_when.splitlines())
    for line in diff:
        print(line)
