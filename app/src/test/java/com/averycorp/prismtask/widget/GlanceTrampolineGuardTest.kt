package com.averycorp.prismtask.widget

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the predicate that decides whether [GlanceTrampolineGuard] hardens
 * the launch Intent. If this ever returns `true` for an Intent missing
 * either `ACTION_INTENT` or `ACTION_TYPE`, the Glance trampoline's
 * `IllegalArgumentException` leaks straight through the guard.
 *
 * Also pins the constants the guard injects — Glance's bytecode reads
 * `ACTION_INTENT` / `ACTION_TYPE` literally, so any drift in this file
 * is the actual crash.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class GlanceTrampolineGuardTest {

    private val guard = GlanceTrampolineGuard()

    @Test
    fun `null intent is not considered carrying trampoline extras`() {
        assertFalse(guard.intentCarriesTrampolineExtras(null))
    }

    @Test
    fun `empty intent is not considered carrying trampoline extras`() {
        assertFalse(guard.intentCarriesTrampolineExtras(Intent()))
    }

    @Test
    fun `intent missing ACTION_INTENT triggers hardening`() {
        val intent = Intent().putExtra(GlanceTrampolineGuard.KEY_ACTION_TYPE, "ACTIVITY")
        assertFalse(guard.intentCarriesTrampolineExtras(intent))
    }

    @Test
    fun `intent missing ACTION_TYPE triggers hardening`() {
        val intent = Intent().putExtra(
            GlanceTrampolineGuard.KEY_ACTION_INTENT,
            Intent("com.example.SOMETHING") as android.os.Parcelable
        )
        assertFalse(guard.intentCarriesTrampolineExtras(intent))
    }

    @Test
    fun `intent with both extras is considered safe`() {
        val intent = Intent()
            .putExtra(
                GlanceTrampolineGuard.KEY_ACTION_INTENT,
                Intent("com.example.SOMETHING") as android.os.Parcelable
            )
            .putExtra(GlanceTrampolineGuard.KEY_ACTION_TYPE, "BROADCAST")
        assertTrue(guard.intentCarriesTrampolineExtras(intent))
    }

    @Test
    fun `injected synthetic extras are detected as safe by the predicate`() {
        // Mirrors what onActivityPreCreated does when hardening.
        val intent = Intent()
            .putExtra(GlanceTrampolineGuard.KEY_ACTION_INTENT, Intent() as android.os.Parcelable)
            .putExtra(
                GlanceTrampolineGuard.KEY_ACTION_TYPE,
                GlanceTrampolineGuard.ACTION_TYPE_BROADCAST
            )
        assertTrue(
            "guard must not re-fire on its own injected extras",
            guard.intentCarriesTrampolineExtras(intent)
        )
        // The Parcelable extra round-trips as a real Intent.
        assertNotNull(
            intent.getParcelableExtra<Intent>(GlanceTrampolineGuard.KEY_ACTION_INTENT)
        )
    }

    @Test
    fun `extra keys match Glance library bytecode literals`() {
        // These literals are read directly by
        // androidx.glance.appwidget.action.ActionTrampolineKt.launchTrampolineAction.
        // Renaming Glance's keys (very unlikely) requires updating these.
        assertEquals("ACTION_INTENT", GlanceTrampolineGuard.KEY_ACTION_INTENT)
        assertEquals("ACTION_TYPE", GlanceTrampolineGuard.KEY_ACTION_TYPE)
        // BROADCAST is the safest dispatch mode — Glance routes it to
        // Activity.sendBroadcast(actionIntent), which is a silent no-op
        // when no receiver matches.
        assertEquals("BROADCAST", GlanceTrampolineGuard.ACTION_TYPE_BROADCAST)
    }
}
