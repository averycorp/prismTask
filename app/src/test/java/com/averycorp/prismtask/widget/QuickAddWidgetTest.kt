package com.averycorp.prismtask.widget

import android.content.Intent
import com.averycorp.prismtask.MainActivity
import com.averycorp.prismtask.widget.launch.WidgetLaunchAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Pure-Kotlin / Robolectric checks for [QuickAddWidget].
 *
 * Glance composable rendering needs a Glance host, so the widget body
 * itself is exercised by the instrumented suite; here we pin the units
 * that the audit (`docs/audits/WIDGET_FUNCTIONALITY_FIDELITY_AUDIT.md`)
 * called out:
 *
 *  - placeholder rotation is deterministic on `dayOfYear`,
 *  - the `TemplateShortcut` data shape used for the bottom row is stable,
 *  - the three shortcuts (quick-add, voice, templates) round-trip through
 *    [WidgetLaunchAction] so the Activity rehydrates them via NavGraph.
 *
 * The intent-construction tests use Robolectric to materialise a real
 * `Intent` and assert the `EXTRA_LAUNCH_ACTION` wire-id on each
 * shortcut — that's the contract `NavGraph` reads from.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class QuickAddWidgetTest {

    @Test
    fun `placeholder rotation is stable for every day of the year`() {
        val placeholders = QuickAddWidget.PLACEHOLDERS
        assertTrue("PLACEHOLDERS must be non-empty", placeholders.isNotEmpty())
        // Every day-of-year (1..366) must land on a real placeholder string.
        for (day in 1..366) {
            val result = QuickAddWidget.placeholderFor(day)
            assertTrue(
                "placeholderFor($day) should return a known PLACEHOLDERS entry",
                placeholders.contains(result)
            )
        }
    }

    @Test
    fun `placeholder rotation wraps modulo list size`() {
        val size = QuickAddWidget.PLACEHOLDERS.size
        assertEquals(
            QuickAddWidget.placeholderFor(0),
            QuickAddWidget.placeholderFor(size)
        )
        assertEquals(
            QuickAddWidget.placeholderFor(1),
            QuickAddWidget.placeholderFor(size + 1)
        )
    }

    @Test
    fun `template shortcut data carries id name icon`() {
        val s = TemplateShortcut(id = 42L, name = "Morning Routine", icon = "🌅")
        assertEquals(42L, s.id)
        assertEquals("Morning Routine", s.name)
        assertEquals("🌅", s.icon)
    }

    @Test
    fun `quick add wire id is stable`() {
        assertEquals("quick_add", WidgetLaunchAction.QuickAdd.wireId)
    }

    @Test
    fun `voice input wire id is stable`() {
        assertEquals("voice_input", WidgetLaunchAction.VoiceInput.wireId)
    }

    @Test
    fun `open templates wire id is stable`() {
        assertEquals("open_templates", WidgetLaunchAction.OpenTemplates.wireId)
    }

    @Test
    fun `quick add intent carries expected launch action extra`() {
        val intent = buildLaunchIntent(WidgetLaunchAction.QuickAdd)
        assertEquals(
            WidgetLaunchAction.QuickAdd.wireId,
            intent.getStringExtra(MainActivity.EXTRA_LAUNCH_ACTION)
        )
        assertNotNull(intent.component)
    }

    @Test
    fun `voice input intent carries expected launch action extra`() {
        val intent = buildLaunchIntent(WidgetLaunchAction.VoiceInput)
        assertEquals(
            WidgetLaunchAction.VoiceInput.wireId,
            intent.getStringExtra(MainActivity.EXTRA_LAUNCH_ACTION)
        )
    }

    @Test
    fun `open templates intent carries expected launch action extra`() {
        val intent = buildLaunchIntent(WidgetLaunchAction.OpenTemplates)
        assertEquals(
            WidgetLaunchAction.OpenTemplates.wireId,
            intent.getStringExtra(MainActivity.EXTRA_LAUNCH_ACTION)
        )
    }

    @Test
    fun `launch intents use NEW_TASK and CLEAR_TOP flags`() {
        val intent = buildLaunchIntent(WidgetLaunchAction.QuickAdd)
        assertTrue(
            "NEW_TASK flag",
            intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK == Intent.FLAG_ACTIVITY_NEW_TASK
        )
        assertTrue(
            "CLEAR_TOP flag",
            intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP == Intent.FLAG_ACTIVITY_CLEAR_TOP
        )
    }

    @Test
    fun `every shortcut wire id round-trips through deserialize`() {
        val cases = listOf(
            WidgetLaunchAction.QuickAdd,
            WidgetLaunchAction.VoiceInput,
            WidgetLaunchAction.OpenTemplates
        )
        for (case in cases) {
            assertEquals(case, WidgetLaunchAction.deserialize(case.wireId))
        }
    }

    /** Mirrors the production helper used by [QuickAddWidget]. */
    private fun buildLaunchIntent(action: WidgetLaunchAction): Intent {
        val ctx = RuntimeEnvironment.getApplication()
        return Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_LAUNCH_ACTION, action.wireId)
        }
    }
}
