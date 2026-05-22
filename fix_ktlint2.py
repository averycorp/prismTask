import os

def process_file(filepath):
    lines = []
    with open(filepath, 'r') as f:
        lines = f.readlines()

    changed = False
    new_lines = []
    for line in lines:
        if '// off-preset → custom on open' in line and not line.strip().startswith('//'):
             parts = line.split('//')
             new_lines.append('            //' + parts[1])
             new_lines.append(parts[0].rstrip() + '\n')
             changed = True
        elif '// Start the interval off a known 30m boundary to avoid rounding flakiness' in line and not line.strip().startswith('//'):
             parts = line.split('//')
             new_lines.append('            //' + parts[1])
             new_lines.append(parts[0].rstrip() + '\n')
             changed = True
        elif '// Advance halfway through the grace period (4 minutes)' in line and not line.strip().startswith('//'):
             parts = line.split('//')
             new_lines.append('            //' + parts[1])
             new_lines.append(parts[0].rstrip() + '\n')
             changed = True
        elif '// Advance another 5 minutes, crossing the grace boundary' in line and not line.strip().startswith('//'):
             parts = line.split('//')
             new_lines.append('            //' + parts[1])
             new_lines.append(parts[0].rstrip() + '\n')
             changed = True
        elif '// Force initialization logic to run again with new context' in line and not line.strip().startswith('//'):
             parts = line.split('//')
             new_lines.append('            //' + parts[1])
             new_lines.append(parts[0].rstrip() + '\n')
             changed = True
        elif 'Exceeded max line length' in line:
             pass # Ignore
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
