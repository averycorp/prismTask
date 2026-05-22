import sys

files = [
    ("app/src/androidTest/java/com/averycorp/prismtask/ui/screens/medication/components/MedicationEditorDialogTest.kt", 168),
    ("app/src/test/java/com/averycorp/prismtask/domain/StreakCalculatorTest.kt", 127),
    ("app/src/test/java/com/averycorp/prismtask/domain/usecase/HabitForgivenessResolverTest.kt", 199),
    ("app/src/test/java/com/averycorp/prismtask/domain/usecase/HabitForgivenessResolverTest.kt", 200),
    ("app/src/test/java/com/averycorp/prismtask/ui/components/AnalogClockPickerTest.kt", 93),
    ("app/src/test/java/com/averycorp/prismtask/ui/components/AnalogClockPickerTest.kt", 124),
    ("app/src/test/java/com/averycorp/prismtask/ui/screens/today/TodayViewModelTest.kt", 194),
    ("app/src/test/java/com/averycorp/prismtask/ui/screens/today/TodayViewModelTest.kt", 195),
    ("app/src/test/java/com/averycorp/prismtask/widget/HabitStreakWidgetTest.kt", 39),
    ("app/src/main/java/com/averycorp/prismtask/ui/screens/settings/sections/CustomBrainModeSubSection.kt", 241),
    ("app/src/main/java/com/averycorp/prismtask/data/local/dao/HabitCompletionDao.kt", 143),
    ("app/src/main/java/com/averycorp/prismtask/ui/components/DurationPickerDialog.kt", 59),
    ("app/src/main/java/com/averycorp/prismtask/ui/screens/onboarding/OnboardingScreen.kt", 531)
]

for f, line in files:
    try:
        with open(f, 'r') as file:
            lines = file.readlines()
            print(f"--- {f}:{line} ---")
            start = max(0, line - 3)
            end = min(len(lines), line + 2)
            for i in range(start, end):
                prefix = "*" if i == line - 1 else " "
                print(f"{prefix} {i + 1}: {lines[i].rstrip()}")
            print("")
    except Exception as e:
        print(f"Error reading {f}: {e}")
