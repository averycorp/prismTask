package com.averycorp.prismtask.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [MedicationWidget] data shapes and the
 * [MarkDoseTakenFromWidgetAction] parameter wiring.
 *
 * Action callback execution and the `markMedicationSlotTaken` repo path
 * require a running Glance/Room host, so those branches are covered by
 * instrumentation tests. Here we exercise the pure model + helpers.
 */
class MedicationWidgetTest {
    @Test
    fun `medication slot preserves shape`() {
        val slot = MedicationWidgetSlot(
            slotId = 7L,
            name = "Morning",
            time = "08:00",
            tier = MedicationWidgetTier.ESSENTIAL,
            taken = 1,
            total = 2,
            active = true
        )
        assertEquals(7L, slot.slotId)
        assertEquals("Morning", slot.name)
        assertEquals(MedicationWidgetTier.ESSENTIAL, slot.tier)
        assertEquals(1, slot.taken)
        assertEquals(2, slot.total)
        assertTrue(slot.active)
    }

    @Test
    fun `nextSlot resolves from nextSlotIndex`() {
        val slots = listOf(
            MedicationWidgetSlot(1L, "AM", "08:00", MedicationWidgetTier.ESSENTIAL, 0, 1, true),
            MedicationWidgetSlot(2L, "PM", "20:00", MedicationWidgetTier.ESSENTIAL, 0, 1, true)
        )
        val data = MedicationWidgetData(
            slots = slots,
            totalDoses = 2,
            takenDoses = 0,
            nextSlotIndex = 1
        )
        assertEquals(2L, data.nextSlot?.slotId)
    }

    @Test
    fun `nextSlot is null when index out of range`() {
        val data = MedicationWidgetData(
            slots = emptyList(),
            totalDoses = 0,
            takenDoses = 0,
            nextSlotIndex = -1
        )
        assertNull(data.nextSlot)
    }

    @Test
    fun `empty data renders empty state`() {
        val data = MedicationWidgetData(
            slots = emptyList(),
            totalDoses = 0,
            takenDoses = 0,
            nextSlotIndex = -1
        )
        assertEquals(0, data.slots.size)
        assertEquals(0, data.totalDoses)
        assertFalse(data.hasRefillWarning)
    }

    @Test
    fun `refill warning surfaces when days remaining low`() {
        val data = MedicationWidgetData(
            slots = emptyList(),
            totalDoses = 0,
            takenDoses = 0,
            nextSlotIndex = -1,
            lowestRefillDaysRemaining = 3,
            lowestRefillMedicationName = "Vitamin D"
        )
        assertTrue(data.hasRefillWarning)
        assertEquals("Vitamin D", data.lowestRefillMedicationName)
    }

    @Test
    fun `refill warning omitted when days remaining above threshold`() {
        val data = MedicationWidgetData(
            slots = emptyList(),
            totalDoses = 0,
            takenDoses = 0,
            nextSlotIndex = -1,
            lowestRefillDaysRemaining = 30,
            lowestRefillMedicationName = "Magnesium"
        )
        assertFalse(data.hasRefillWarning)
    }

    @Test
    fun `refill warning omitted when no refill rows tracked`() {
        val data = MedicationWidgetData(
            slots = emptyList(),
            totalDoses = 0,
            takenDoses = 0,
            nextSlotIndex = -1,
            lowestRefillDaysRemaining = null,
            lowestRefillMedicationName = null
        )
        assertFalse(data.hasRefillWarning)
    }

    @Test
    fun `medicationSlotIdParams builds correct parameter bundle`() {
        val params = medicationSlotIdParams(12L)
        val slotId = params[WidgetActionKeys.MEDICATION_SLOT_ID]
        assertNotNull(slotId)
        assertEquals(12L, slotId)
    }

    @Test
    fun `medication slot id key name is stable`() {
        // The key name is persisted in PendingIntent extras across launcher
        // restarts; changing it would silently break placed widgets, so the
        // identity is locked in by this test.
        assertEquals(
            "prismtask-widget-medication-slot-id",
            WidgetActionKeys.MEDICATION_SLOT_ID.name
        )
    }

    @Test
    fun `tier enum exposes all four states`() {
        val tiers = MedicationWidgetTier.values().toSet()
        assertTrue(MedicationWidgetTier.ESSENTIAL in tiers)
        assertTrue(MedicationWidgetTier.PRESCRIPTION in tiers)
        assertTrue(MedicationWidgetTier.COMPLETE in tiers)
        assertTrue(MedicationWidgetTier.SKIPPED in tiers)
    }
}
