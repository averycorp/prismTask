import os

def process_file(filepath):
    lines = []
    with open(filepath, 'r') as f:
        lines = f.readlines()

    changed = False
    new_lines = []
    for line in lines:
        if line.strip().endswith('// TODO: Re-enable once dialog testing is stable'):
            new_line = line.replace('// TODO: Re-enable once dialog testing is stable', '').rstrip() + '\n'
            new_lines.append(new_line)
            new_lines.append('            // TODO: Re-enable once dialog testing is stable\n')
            changed = True
        elif '// TODO: Migrate once UI components are updated' in line:
            new_line = line.replace('// TODO: Migrate once UI components are updated', '').rstrip() + '\n'
            new_lines.append(new_line)
            new_lines.append('            // TODO: Migrate once UI components are updated\n')
            changed = True
        elif '// Testing forgiveness rule resolution via mocked DB' in line and not line.strip().startswith('//'):
             parts = line.split('//')
             new_lines.append('            //' + parts[1])
             new_lines.append(parts[0].rstrip() + '\n')
             changed = True
        elif '// Requires mock time injection' in line and not line.strip().startswith('//'):
             parts = line.split('//')
             new_lines.append('            //' + parts[1])
             new_lines.append(parts[0].rstrip() + '\n')
             changed = True
        elif '// Use specific streak algorithm' in line and not line.strip().startswith('//'):
             parts = line.split('//')
             new_lines.append('            //' + parts[1])
             new_lines.append(parts[0].rstrip() + '\n')
             changed = True
        elif '// Verify non-blocking widget update' in line and not line.strip().startswith('//'):
             parts = line.split('//')
             new_lines.append('            //' + parts[1])
             new_lines.append(parts[0].rstrip() + '\n')
             changed = True
        elif 'Exceeded max line length' in line:
             pass # Will handle these manually if needed
        else:
            new_lines.append(line)

    if changed:
        with open(filepath, 'w') as f:
            f.writelines(new_lines)

file_paths = [
    'app/src/androidTest/java/com/averycorp/prismtask/ui/screens/medication/components/MedicationEditorDialogTest.kt',
    'app/src/test/java/com/averycorp/prismtask/domain/StreakCalculatorTest.kt',
    'app/src/test/java/com/averycorp/prismtask/domain/usecase/HabitForgivenessResolverTest.kt',
    'app/src/test/java/com/averycorp/prismtask/ui/components/AnalogClockPickerTest.kt',
    'app/src/test/java/com/averycorp/prismtask/ui/screens/today/TodayViewModelTest.kt',
    'app/src/test/java/com/averycorp/prismtask/widget/HabitStreakWidgetTest.kt',
    'app/src/main/java/com/averycorp/prismtask/ui/screens/settings/sections/CustomBrainModeSubSection.kt'
]

for p in file_paths:
    if os.path.exists(p):
        print(f"Checking {p}")
        process_file(p)
