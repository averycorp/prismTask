package com.averycorp.prismtask.ui.screens.medication.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.averycorp.prismtask.data.local.entity.MedicationSlotEntity
import com.averycorp.prismtask.domain.model.medication.MedicationTier
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression coverage for the [MedicationEditorDialog] Save-button
 * gate. Post-update the gate is just
 *
 *   `name.isNotBlank()`
 *
 * — a med with no linked slot is a legitimate as-needed (PRN) entry
 * that surfaces in the "Unscheduled" section of the Medication screen,
 * so the historical `(selections.isNotEmpty() || activeSlots.isEmpty())`
 * clause from PR #1141 was lifted. Crash protection for the original
 * duplicate-name `SQLiteConstraintException` lives upstream in
 * `MedicationViewModel.addMedication`'s `getByNameOnce` pre-flight +
 * outer try/catch (covered by `MedicationViewModelAddMedicationTest`).
 *
 * The ViewModel-layer regression test pins what the ViewModel does
 * once `onConfirm` fires; this test pins the rendered UI state — i.e.
 * does the Save button refuse the click in the first place. Without
 * this test, a future refactor of `enabled = ...` would not regress
 * the unit tests but would silently regress the dialog.
 */
@RunWith(AndroidJUnit4::class)
class MedicationEditorDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val morningSlot = MedicationSlotEntity(
        id = 10L,
        name = "Morning",
        idealTime = "09:00",
        driftMinutes = 60
    )

    private val eveningSlot = MedicationSlotEntity(
        id = 11L,
        name = "Evening",
        idealTime = "21:00",
        driftMinutes = 60
    )

    @Test
    fun saveDisabled_whenNameBlankAndSelectionsEmpty_andActiveSlotsPresent() {
        composeRule.setContent {
            MedicationEditorDialog(
                title = "Add Medication",
                initialName = "",
                initialSelections = emptyList(),
                activeSlots = listOf(morningSlot, eveningSlot),
                onDismiss = {},
                onConfirm = { _, _, _, _, _, _, _ -> },
                onCreateNewSlot = {}
            )
        }
        composeRule.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    fun saveEnabled_whenNamePresentButSelectionsEmpty_andActiveSlotsPresent_unscheduled() {
        // PRN / as-needed medication: a user can save a med without
        // picking any slot, even when active slots exist. The med
        // surfaces in the Medication screen's "Unscheduled" section.
        // The advisory text inside the dialog confirms the routing
        // ("…will appear in the Unscheduled section as an as-needed
        // dose."), and the dose-log path uses `slotKey = "anytime"`.
        composeRule.setContent {
            MedicationEditorDialog(
                title = "Add Medication",
                initialName = "Ibuprofen",
                initialSelections = emptyList(),
                activeSlots = listOf(morningSlot, eveningSlot),
                onDismiss = {},
                onConfirm = { _, _, _, _, _, _, _ -> },
                onCreateNewSlot = {}
            )
        }
        composeRule.onNodeWithText("Save").assertIsEnabled()
    }

    @Test
    fun saveEnabled_whenNamePresentAndSelectionsPresent() {
        composeRule.setContent {
            MedicationEditorDialog(
                title = "Add Medication",
                initialName = "Lamotrigine",
                initialSelections = listOf(MedicationSlotSelection(slotId = morningSlot.id)),
                activeSlots = listOf(morningSlot, eveningSlot),
                onDismiss = {},
                onConfirm = { _, _, _, _, _, _, _ -> },
                onCreateNewSlot = {}
            )
        }
        composeRule.onNodeWithText("Save").assertIsEnabled()
    }

    @Test
    fun saveEnabled_whenNamePresentAndActiveSlotsEmpty_bootstrapPath() {
        // Empty `activeSlots` is the only branch where empty selections
        // is still a valid Save state — the user has no slots yet, so
        // they can't pick any. Forcing slot-picked here would be a
        // chicken-and-egg trap on first launch.
        composeRule.setContent {
            MedicationEditorDialog(
                title = "Add Medication",
                initialName = "Lamotrigine",
                initialSelections = emptyList(),
                activeSlots = emptyList(),
                onDismiss = {},
                onConfirm = { _, _, _, _, _, _, _ -> },
                onCreateNewSlot = {}
            )
        }
        composeRule.onNodeWithText("Save").assertIsEnabled()
    }

    @Test
    fun saveDisabled_whenNameBlankEvenWithSelections() {
        composeRule.setContent {
            MedicationEditorDialog(
                title = "Add Medication",
                initialName = "   ",
                initialSelections = listOf(MedicationSlotSelection(slotId = morningSlot.id)),
                activeSlots = listOf(morningSlot, eveningSlot),
                onDismiss = {},
                onConfirm = { _, _, _, _, _, _, _ -> },
                onCreateNewSlot = {}
            )
        }
        composeRule.onNodeWithText("Save").assertIsNotEnabled()
    }

    /**
     * Regression for the typing-through-preset trap: previously the
     * custom-interval TextField only rendered when
     * `intervalMinutes !in intervalPresets`. Typing toward 1200 passed
     * through 120 (a preset), which hid the field mid-keystroke and
     * prevented the user from completing the value. Post-fix, the
     * field's visibility is governed by an explicit `isCustomInterval`
     * state that only flips on chip clicks, so typing through any
     * preset is non-disruptive.
     */
    @Test
    fun customIntervalField_staysVisible_whenTypedValueLandsOnPreset() {
        composeRule.setContent {
            MedicationEditorDialog(
                title = "Add Medication",
                initialName = "Test",
                initialTier = MedicationTier.ESSENTIAL,
                initialSelections = emptyList(),
                activeSlots = listOf(morningSlot),
                initialReminderMode = "INTERVAL",
                initialReminderIntervalMinutes = 241,
// off-preset → custom on open
                onDismiss = {},
                onConfirm = { _, _, _, _, _, _, _ -> },
                onCreateNewSlot = {}
            )
        }
        val field = composeRule.onNodeWithText("Custom interval (minutes, 60–1440)")
        field.assertIsDisplayed()
        field.performTextClearance()
        // Type digit-by-digit to mimic real key events. After "120" the
        // pre-fix code re-derived `isCustomInterval = false`, hiding
        // the field. We type the trailing "0" *after* the assertion to
        // prove the field accepted further keystrokes.
        field.performTextInput("1")
        field.performTextInput("2")
        field.performTextInput("0")
        composeRule
            .onNodeWithText("Custom interval (minutes, 60–1440)")
            .assertIsDisplayed()
        field.performTextInput("0")
        composeRule
            .onNodeWithText("Custom interval (minutes, 60–1440)")
            .assertIsDisplayed()
    }

    @Test
    fun customIntervalField_persistsTypedValue_throughPreset_onSave() {
        var capturedInterval: Int? = null
        composeRule.setContent {
            MedicationEditorDialog(
                title = "Add Medication",
                initialName = "Test",
                initialTier = MedicationTier.ESSENTIAL,
                initialSelections = emptyList(),
                activeSlots = listOf(morningSlot),
                initialReminderMode = "INTERVAL",
                initialReminderIntervalMinutes = 241,
                onDismiss = {},
                onConfirm = { _, _, _, _, _, intervalMinutes, _ ->
                    capturedInterval = intervalMinutes
                },
                onCreateNewSlot = {}
            )
        }
        val field = composeRule.onNodeWithText("Custom interval (minutes, 60–1440)")
        field.performTextClearance()
        field.performTextInput("1")
        field.performTextInput("2")
        field.performTextInput("0") // would have trapped pre-fix
        field.performTextInput("0")
        composeRule.onNodeWithText("Save").performClick()
        assertEquals(1200, capturedInterval)
    }
}
