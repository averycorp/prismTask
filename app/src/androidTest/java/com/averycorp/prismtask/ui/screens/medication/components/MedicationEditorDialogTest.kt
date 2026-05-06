package com.averycorp.prismtask.ui.screens.medication.components

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.averycorp.prismtask.data.local.entity.MedicationSlotEntity
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
 *
 * See `docs/audits/ALLOW_UNSCHEDULED_MEDICATION_AUDIT.md` and
 * `docs/audits/F5_MEDICATION_HYGIENE_FOLLOWONS_AUDIT.md` § B.3.
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
}
