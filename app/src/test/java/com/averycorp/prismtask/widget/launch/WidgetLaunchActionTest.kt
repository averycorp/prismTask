package com.averycorp.prismtask.widget.launch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Round-trip and exhaustiveness checks for the widget deep-link contract.
 *
 * The previous stringly-typed contract drifted silently: a widget could put
 * a literal that didn't match anything `NavGraph` checked for, and the
 * deep-link no-op'd without any signal. Sealed-enum routing fixes the
 * runtime side; this test pins the wire format itself.
 *
 * @see docs/audits/DEFECT_FAMILY_HARDENING_AUDIT.md §C
 */
class WidgetLaunchActionTest {
    /**
     * Every singleton case (everything except [WidgetLaunchAction.OpenTask])
     * round-trips through [WidgetLaunchAction.deserialize] without losing
     * identity. Listed explicitly so adding a subclass requires updating
     * this list — which forces a deliberate decision about whether the new
     * case should round-trip.
     */
    private val singletonCases: List<WidgetLaunchAction> = listOf(
        WidgetLaunchAction.QuickAdd,
        WidgetLaunchAction.VoiceInput,
        WidgetLaunchAction.OpenTemplates,
        WidgetLaunchAction.OpenHabits,
        WidgetLaunchAction.OpenTimer,
        WidgetLaunchAction.OpenMatrix,
        WidgetLaunchAction.OpenToday,
        WidgetLaunchAction.OpenInbox,
        WidgetLaunchAction.OpenMedication,
        WidgetLaunchAction.OpenInsights
    )

    @Test
    fun `every singleton case round-trips through deserialize`() {
        for (case in singletonCases) {
            val rehydrated = WidgetLaunchAction.deserialize(case.wireId)
            assertEquals(
                "wire-format round-trip failed for ${case::class.simpleName}",
                case,
                rehydrated
            )
        }
    }

    @Test
    fun `OpenTask round-trips with its taskId payload`() {
        val original = WidgetLaunchAction.OpenTask(taskId = 42L)
        val rehydrated = WidgetLaunchAction.deserialize(
            wireId = original.wireId,
            taskId = 42L
        )
        assertEquals(original, rehydrated)
    }

    @Test
    fun `OpenTask without taskId is rejected`() {
        // Defensive: an OpenTask intent stamped without a task_id extra is
        // malformed. Returning null lets the Activity fall back to the
        // default tab rather than crashing or routing to a bogus task.
        val rehydrated = WidgetLaunchAction.deserialize(
            wireId = WidgetLaunchAction.OpenTask.WIRE_ID,
            taskId = null
        )
        assertNull(rehydrated)
    }

    @Test
    fun `ResumeTiny round-trips with its taskId payload`() {
        val original = WidgetLaunchAction.ResumeTiny(taskId = 77L)
        val rehydrated = WidgetLaunchAction.deserialize(
            wireId = original.wireId,
            taskId = 77L
        )
        assertEquals(original, rehydrated)
        assertEquals("resume_tiny", WidgetLaunchAction.ResumeTiny.WIRE_ID)
    }

    @Test
    fun `ResumeTiny without taskId is rejected`() {
        val rehydrated = WidgetLaunchAction.deserialize(
            wireId = WidgetLaunchAction.ResumeTiny.WIRE_ID,
            taskId = null
        )
        assertNull(rehydrated)
    }

    @Test
    fun `unknown wire id deserializes to null instead of crashing`() {
        assertNull(WidgetLaunchAction.deserialize("totally_made_up"))
    }

    @Test
    fun `null wire id deserializes to null`() {
        assertNull(WidgetLaunchAction.deserialize(null))
    }

    @Test
    fun `wire ids are unique across all subclasses`() {
        val singletonIds = singletonCases.map { it.wireId }
        val allIds = singletonIds +
            WidgetLaunchAction.OpenTask.WIRE_ID +
            WidgetLaunchAction.ResumeTiny.WIRE_ID
        assertEquals(
            "wire ids must be unique — duplicates make deserialize ambiguous",
            allIds.size,
            allIds.toSet().size
        )
    }

    @Test
    fun `wire ids are stable strings (cross-process intent contract)`() {
        // Pin the literal wire format. Widgets are stamped at install time
        // and survive app upgrades; renaming a wireId on the Activity side
        // without bumping the widget receivers would silently break every
        // pre-upgrade widget instance until the user re-pins it.
        assertEquals("quick_add", WidgetLaunchAction.QuickAdd.wireId)
        assertEquals("voice_input", WidgetLaunchAction.VoiceInput.wireId)
        assertEquals("open_templates", WidgetLaunchAction.OpenTemplates.wireId)
        assertEquals("open_habits", WidgetLaunchAction.OpenHabits.wireId)
        assertEquals("open_timer", WidgetLaunchAction.OpenTimer.wireId)
        assertEquals("open_matrix", WidgetLaunchAction.OpenMatrix.wireId)
        assertEquals("open_today", WidgetLaunchAction.OpenToday.wireId)
        assertEquals("open_inbox", WidgetLaunchAction.OpenInbox.wireId)
        assertEquals("open_medication", WidgetLaunchAction.OpenMedication.wireId)
        assertEquals("open_insights", WidgetLaunchAction.OpenInsights.wireId)
        assertEquals("open_task", WidgetLaunchAction.OpenTask.WIRE_ID)
        assertNotNull(WidgetLaunchAction.OpenTask(1L).wireId)
    }
}
