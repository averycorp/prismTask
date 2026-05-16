package com.averycorp.prismtask.widget

import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.averycorp.prismtask.data.billing.BillingManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class AdminWidgetGateTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val pm get() = context.packageManager

    private fun gateWith(isAdmin: MutableStateFlow<Boolean>): AdminWidgetGate {
        val billing = mockk<BillingManager>()
        every { billing.isAdmin } returns isAdmin
        return AdminWidgetGate(context, billing)
    }

    @Test
    fun `widget receiver list is exhaustive`() {
        // Locks the contract: every AppWidgetProvider declared in the
        // manifest must be in this list, or it won't be gated.
        assertEquals(14, AdminWidgetGate.WIDGET_RECEIVERS.size)
    }

    @Test
    fun `setWidgetsEnabled true enables every receiver`() {
        val gate = gateWith(MutableStateFlow(false))
        gate.setWidgetsEnabled(true)
        AdminWidgetGate.WIDGET_RECEIVERS.forEach { name ->
            val state = pm.getComponentEnabledSetting(ComponentName(context, name))
            assertEquals(
                "Receiver $name should be ENABLED",
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                state
            )
        }
    }

    @Test
    fun `setWidgetsEnabled false disables every receiver`() {
        val gate = gateWith(MutableStateFlow(true))
        gate.setWidgetsEnabled(false)
        AdminWidgetGate.WIDGET_RECEIVERS.forEach { name ->
            val state = pm.getComponentEnabledSetting(ComponentName(context, name))
            assertEquals(
                "Receiver $name should be DISABLED",
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                state
            )
        }
    }

    @Test
    fun `start flips state when isAdmin emits`() = runTest {
        val isAdmin = MutableStateFlow(false)
        val gate = gateWith(isAdmin)
        val scope = TestScope(StandardTestDispatcher(testScheduler))

        gate.start(scope)
        scope.advanceUntilIdle()

        val sample = AdminWidgetGate.WIDGET_RECEIVERS.first()
        val component = ComponentName(context, sample)

        assertEquals(
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            pm.getComponentEnabledSetting(component)
        )

        isAdmin.value = true
        scope.advanceUntilIdle()
        assertEquals(
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            pm.getComponentEnabledSetting(component)
        )

        isAdmin.value = false
        scope.advanceUntilIdle()
        assertEquals(
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            pm.getComponentEnabledSetting(component)
        )
    }
}
