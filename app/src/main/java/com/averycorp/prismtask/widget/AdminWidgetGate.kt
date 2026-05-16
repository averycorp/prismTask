package com.averycorp.prismtask.widget

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.averycorp.prismtask.data.billing.BillingManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gates every home-screen widget [android.appwidget.AppWidgetProvider]
 * receiver behind [BillingManager.isAdmin]. Non-admin users do not see
 * widgets in the system picker; already-placed instances stop receiving
 * APPWIDGET_UPDATE broadcasts (their last RemoteViews stays on screen).
 *
 * Receivers are declared `android:enabled="false"` in the manifest so a
 * fresh install on a non-admin device starts in the hidden state. The
 * runtime override flips back to ENABLED once `isAdmin` flips true.
 */
@Singleton
class AdminWidgetGate
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val billingManager: BillingManager
) {
    fun start(scope: CoroutineScope) {
        scope.launch {
            billingManager.isAdmin.collect { isAdmin -> setWidgetsEnabled(isAdmin) }
        }
    }

    fun setWidgetsEnabled(enabled: Boolean) {
        val pm = context.packageManager
        val target = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        WIDGET_RECEIVERS.forEach { className ->
            val component = ComponentName(context, className)
            try {
                val current = pm.getComponentEnabledSetting(component)
                if (current != target) {
                    pm.setComponentEnabledSetting(
                        component,
                        target,
                        PackageManager.DONT_KILL_APP
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to toggle $className -> $target", e)
            }
        }
    }

    companion object {
        private const val TAG = "AdminWidgetGate"
        private const val PKG = "com.averycorp.prismtask"

        val WIDGET_RECEIVERS: List<String> = listOf(
            "$PKG.widget.TodayWidgetReceiver",
            "$PKG.widget.HabitStreakWidgetReceiver",
            "$PKG.widget.ProjectWidgetReceiver",
            "$PKG.widget.QuickAddWidgetReceiver",
            "$PKG.widget.ProductivityWidgetReceiver",
            "$PKG.widget.UpcomingWidgetReceiver",
            "$PKG.widget.TimerWidgetReceiver",
            "$PKG.widget.CalendarWidgetReceiver",
            "$PKG.widget.EisenhowerWidgetReceiver",
            "$PKG.widget.StreakCalendarWidgetReceiver",
            "$PKG.widget.FocusWidgetReceiver",
            "$PKG.widget.MedicationWidgetReceiver",
            "$PKG.widget.StatsSparklineWidgetReceiver",
            "$PKG.widget.InboxWidgetReceiver"
        )
    }
}
