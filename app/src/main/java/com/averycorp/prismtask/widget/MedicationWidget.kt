package com.averycorp.prismtask.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.Text
import androidx.glance.unit.ColorProvider
import com.averycorp.prismtask.MainActivity
import com.averycorp.prismtask.widget.launch.WidgetLaunchAction

/**
 * Medication widget — mirrors the in-app meds slot/tier model.
 *
 * Tier color mapping:
 *   ESSENTIAL    → primary
 *   PRESCRIPTION → infoColor
 *   COMPLETE     → successColor
 *   SKIPPED      → muted
 *
 * Reads slot + dose state for today via [WidgetDataProvider.getMedicationData].
 *
 * Three sizes (declared via SizeMode.Responsive):
 * - SMALL_WIDE (4×1): compact "next dose" headline + day progress bar
 * - LARGE     (4×3): full slot list with per-slot dose checks
 * - LARGE_WIDE (5×3): same as LARGE with looser spacing
 */
class MedicationWidget : GlanceAppWidget() {
    companion object {
        private val SMALL_WIDE = DpSize(200.dp, 100.dp)
        private val LARGE = DpSize(350.dp, 250.dp)
        private val LARGE_WIDE = DpSize(450.dp, 250.dp)
    }

    override val sizeMode = SizeMode.Responsive(setOf(SMALL_WIDE, LARGE, LARGE_WIDE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val palette = loadWidgetPalette(context)
        val data = try {
            WidgetDataProvider.getMedicationData(context)
        } catch (_: Exception) {
            MedicationWidgetData(slots = emptyList(), totalDoses = 0, takenDoses = 0, nextSlotIndex = -1)
        }
        val quietMode = WidgetDataProvider.getQuietMode(context)
        provideContent {
            MedicationContent(context, LocalSize.current, palette, data, quietMode)
        }
    }
}

@Composable
private fun MedicationContent(
    context: Context,
    size: DpSize,
    palette: WidgetThemePalette,
    data: MedicationWidgetData,
    quietMode: Boolean
) {
    val isSmall = size.height < 130.dp
    val openMeds = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra(MainActivity.EXTRA_LAUNCH_ACTION, WidgetLaunchAction.OpenMedication.wireId)
    }

    val slots = data.slots
    val totalDoses = data.totalDoses
    val takenDoses = data.takenDoses
    val nextSlot = data.nextSlot

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .cornerRadius(palette.widgetCornerRadius)
            .background(palette.surfaceBackground)
            .padding(if (isSmall) 11.dp else 12.dp)
            .clickable(actionStartActivity(openMeds))
    ) {
        when {
            slots.isEmpty() -> EmptyMedicationView(palette, quietMode)
            isSmall && nextSlot != null -> CompactNextDose(palette, nextSlot, takenDoses, totalDoses)
            else -> FullDayView(palette, slots, takenDoses, totalDoses, nextSlot, data)
        }
    }
}

@Composable
private fun ColumnScope.EmptyMedicationView(palette: WidgetThemePalette, quietMode: Boolean) {
    Text(
        text = WidgetTextStyles.headerLabel(palette, "Medication"),
        style = WidgetTextStyles.headerThemed(palette, palette.onSurface)
    )
    Spacer(modifier = GlanceModifier.height(8.dp))
    if (!quietMode) {
        Text(
            text = "Tap to set up your meds",
            style = WidgetTextStyles.caption(palette.onSurfaceVariant)
        )
    } else {
        Text(
            text = "No medications configured",
            style = WidgetTextStyles.caption(palette.onSurfaceVariant)
        )
    }
}

@Composable
private fun ColumnScope.CompactNextDose(
    palette: WidgetThemePalette,
    nextSlot: MedicationWidgetSlot,
    takenDoses: Int,
    totalDoses: Int
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = GlanceModifier
                .size(8.dp)
                .cornerRadius(4.dp)
                .background(tierColor(nextSlot.tier, palette))
        ) {}
        Spacer(modifier = GlanceModifier.width(6.dp))
        Text(
            text = "Next Dose",
            style = WidgetTextStyles.captionMedium(palette.onSurfaceVariant),
            modifier = GlanceModifier.defaultWeight()
        )
        Text(
            text = "$takenDoses/${totalDoses.coerceAtLeast(0)}",
            style = WidgetTextStyles.badge(palette.onSurfaceVariant)
        )
    }
    Text(
        text = WidgetTextStyles.headerLabel(palette, nextSlot.name),
        style = WidgetTextStyles.headerThemed(palette, palette.onSurface)
    )
    Text(
        text = "${nextSlot.time} · ${(nextSlot.total - nextSlot.taken).coerceAtLeast(0)} pending",
        style = WidgetTextStyles.caption(palette.onSurfaceVariant)
    )
    Spacer(modifier = GlanceModifier.defaultWeight())
    LinearProgressIndicator(
        progress = safeProgress(takenDoses, totalDoses),
        modifier = GlanceModifier.fillMaxWidth().height(4.dp),
        color = palette.successColor,
        backgroundColor = palette.surfaceVariant
    )
}

@Composable
private fun ColumnScope.FullDayView(
    palette: WidgetThemePalette,
    slots: List<MedicationWidgetSlot>,
    takenDoses: Int,
    totalDoses: Int,
    nextSlot: MedicationWidgetSlot?,
    data: MedicationWidgetData
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = WidgetTextStyles.headerLabel(palette, "Medication"),
            style = WidgetTextStyles.headerThemed(palette, palette.onSurface),
            modifier = GlanceModifier.defaultWeight()
        )
        Text(
            text = "$takenDoses/${totalDoses.coerceAtLeast(0)}",
            style = WidgetTextStyles.captionMedium(palette.successColor)
        )
    }
    Spacer(modifier = GlanceModifier.height(6.dp))
    LinearProgressIndicator(
        progress = safeProgress(takenDoses, totalDoses),
        modifier = GlanceModifier.fillMaxWidth().height(5.dp),
        color = palette.successColor,
        backgroundColor = palette.surfaceVariant
    )
    if (data.hasRefillWarning) {
        Spacer(modifier = GlanceModifier.height(6.dp))
        RefillWarningRow(palette, data)
    }
    Spacer(modifier = GlanceModifier.height(8.dp))
    slots.forEach { slot ->
        SlotRow(slot, palette, highlight = slot === nextSlot)
        Spacer(modifier = GlanceModifier.height(5.dp))
    }
}

@Composable
private fun RefillWarningRow(palette: WidgetThemePalette, data: MedicationWidgetData) {
    val days = data.lowestRefillDaysRemaining ?: return
    val name = data.lowestRefillMedicationName ?: "Refill"
    val label = when {
        days <= 0 -> "$name · out of stock"
        days == 1 -> "$name · 1 day left"
        else -> "$name · $days days left"
    }
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .cornerRadius(6.dp)
            .background(palette.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = GlanceModifier.size(8.dp).cornerRadius(4.dp).background(palette.warningColor)
        ) {}
        Spacer(modifier = GlanceModifier.width(8.dp))
        Text(
            text = label,
            style = WidgetTextStyles.badgeBold(palette.warningColor),
            modifier = GlanceModifier.defaultWeight(),
            maxLines = 1
        )
        Text(
            text = "Refill",
            style = WidgetTextStyles.badge(palette.onSurfaceVariant)
        )
    }
}

private fun safeProgress(taken: Int, total: Int): Float =
    if (total <= 0) 0f else (taken.toFloat() / total).coerceIn(0f, 1f)

@Composable
private fun SlotRow(slot: MedicationWidgetSlot, palette: WidgetThemePalette, highlight: Boolean) {
    val tcolor = tierColor(slot.tier, palette)
    val allDone = slot.active && slot.total > 0 && slot.taken >= slot.total
    val canMark = slot.active && slot.total > 0 && slot.taken < slot.total
    val rowBg: ColorProvider = if (highlight) palette.primaryContainer else palette.surfaceVariant
    val rowModifier = GlanceModifier
        .fillMaxWidth()
        .cornerRadius(6.dp)
        .background(rowBg)
        .padding(horizontal = 8.dp, vertical = 6.dp)
        .let { mod ->
            if (canMark) {
                mod.clickable(
                    actionRunCallback<MarkDoseTakenFromWidgetAction>(
                        parameters = medicationSlotIdParams(slot.slotId)
                    )
                )
            } else {
                mod
            }
        }
    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = GlanceModifier.size(8.dp).cornerRadius(4.dp).background(tcolor)) {}
        Spacer(modifier = GlanceModifier.width(8.dp))
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = slot.name,
                style = WidgetTextStyles.captionMedium(
                    if (slot.active) palette.onSurface else palette.onSurfaceVariant
                ),
                maxLines = 1
            )
            Text(
                text = if (slot.active) slot.time else "${slot.time} · skipped",
                style = WidgetTextStyles.badge(palette.onSurfaceVariant),
                maxLines = 1
            )
        }
        if (canMark) {
            // "Mark taken" affordance — tappable check pill. Distinct from the
            // dose progress badge so users immediately read the row as
            // actionable, mirroring the in-app slot tick affordance.
            Text(
                text = "✓ Taken",
                style = WidgetTextStyles.badgeBold(palette.primary)
            )
            Spacer(modifier = GlanceModifier.width(6.dp))
        }
        Text(
            text = if (allDone) "✓ Done" else "${slot.taken}/${slot.total}",
            style = WidgetTextStyles.badgeBold(
                if (allDone) palette.successColor else palette.onSurfaceVariant
            )
        )
    }
}

private fun tierColor(tier: MedicationWidgetTier, palette: WidgetThemePalette): ColorProvider = when (tier) {
    MedicationWidgetTier.ESSENTIAL -> palette.primary
    MedicationWidgetTier.PRESCRIPTION -> palette.infoColor
    MedicationWidgetTier.COMPLETE -> palette.successColor
    MedicationWidgetTier.SKIPPED -> palette.onSurfaceVariant
}

class MedicationWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MedicationWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        clearWidgetConfigOnDelete(this, context, appWidgetIds)
    }
}
