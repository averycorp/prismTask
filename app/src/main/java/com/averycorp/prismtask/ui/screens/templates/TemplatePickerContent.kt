package com.averycorp.prismtask.ui.screens.templates

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.averycorp.prismtask.domain.model.SelfCareRoutines

/**
 * Stateless template picker, rendered inside the onboarding flow and the
 * Settings "Browse templates" screen. All four sections (Music, Flex,
 * Self-Care Morning + Bedtime, Housework) default to "nothing selected" — the
 * user opts into any templates they want and hits Next / Add to commit.
 *
 * [state] and [onChange] are hoisted so the caller owns persistence; this
 * composable only drives UI state (expansion, customize toggle).
 *
 * The `show*` flags let onboarding hide sections whose owning Life Mode the
 * user just turned off on the prior page. Settings "Browse Templates" omits
 * them so all sections render unconditionally.
 */
@Composable
fun TemplatePickerContent(
    state: TemplateSelections,
    onChange: (TemplateSelections) -> Unit,
    modifier: Modifier = Modifier,
    showLeisure: Boolean = true,
    showSelfCare: Boolean = true,
    showHousework: Boolean = true
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Leisure Budget v2.0 \u2014 Templates picker no longer seeds music
        // / flex / language slots; the new pool is populated via
        // LeisurePoolScreen. showLeisure stays in the signature for
        // backwards-compat with callers that pass it explicitly, but is
        // now a no-op so the templates browser doesn't accidentally
        // surface a half-deleted UI surface.
        if (showSelfCare) {
            RoutineSectionCard(
                emoji = "\uD83C\uDF05",
                title = "Self-Care",
                subtitle = "Morning and bedtime routines",
                state = state,
                onChange = onChange,
                routineTypes = listOf("morning" to "Morning", "bedtime" to "Bedtime")
            )
        }
        if (showHousework) {
            RoutineSectionCard(
                emoji = "\uD83E\uDDF9",
                title = "Housework",
                subtitle = "Daily home upkeep",
                state = state,
                onChange = onChange,
                routineTypes = listOf("housework" to "Housework")
            )
        }
    }
}

// Leisure Budget v2.0: v1.x LeisureSectionCard removed alongside the
// rest of the v1.x slot model.

@Composable
private fun RoutineSectionCard(
    emoji: String,
    title: String,
    subtitle: String,
    state: TemplateSelections,
    onChange: (TemplateSelections) -> Unit,
    routineTypes: List<Pair<String, String>>
) {
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    val totalSelected = routineTypes.sumOf { (type, _) -> state.effectiveStepIds(type).size }
    SectionCard(
        emoji = emoji,
        title = title,
        subtitle = subtitle,
        selectionSummary = if (totalSelected > 0) "$totalSelected steps" else "None",
        expanded = expanded,
        onToggleExpanded = { expanded = !expanded }
    ) {
        routineTypes.forEachIndexed { index, (routineType, label) ->
            if (index > 0) Spacer(modifier = Modifier.height(12.dp))
            RoutineTierPicker(
                label = label,
                routineType = routineType,
                state = state,
                onChange = onChange
            )
        }
    }
}

@Composable
private fun RoutineTierPicker(
    label: String,
    routineType: String,
    state: TemplateSelections,
    onChange: (TemplateSelections) -> Unit
) {
    val tiers = SelfCareRoutines.getTiers(routineType)
    val selectedTier = state.tierFor(routineType)
    val customStepIds = state.customStepIdsFor(routineType)
    val customizing = customStepIds != null
    var showSteps by rememberSaveable(routineType) { mutableStateOf(false) }

    Text(
        text = label,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold
    )
    Spacer(modifier = Modifier.height(4.dp))

    RadioRow(
        label = "None",
        selected = selectedTier == null && !customizing,
        onClick = { onChange(state.withTier(routineType, null)) }
    )
    tiers.forEach { tier ->
        RadioRow(
            label = "${tier.label}  ·  ${tier.time}",
            selected = selectedTier == tier.id && !customizing,
            onClick = { onChange(state.withTier(routineType, tier.id)) }
        )
    }

    Spacer(modifier = Modifier.height(4.dp))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showSteps = !showSteps }
            .padding(vertical = 4.dp)
    ) {
        Text(
            text = "Customize individual steps",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = showSteps,
            onCheckedChange = { showSteps = it }
        )
    }
    AnimatedVisibility(visible = showSteps) {
        Column {
            val effective = state.effectiveStepIds(routineType)
            SelfCareRoutines.getSteps(routineType).forEach { step ->
                CheckboxRow(
                    label = "${step.label}  ·  ${step.duration}",
                    checked = step.id in effective,
                    onCheckedChange = { onChange(state.withStepToggled(routineType, step.id)) }
                )
            }
        }
    }
}

@Composable
private fun SectionCard(
    emoji: String,
    title: String,
    subtitle: String,
    selectionSummary: String,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleExpanded() }
            ) {
                Text(emoji, style = MaterialTheme.typography.headlineSmall)
                Spacer(modifier = Modifier.padding(end = 12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = selectionSummary,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand"
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun CheckboxRow(
    label: String,
    checked: Boolean,
    onCheckedChange: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange() }
            .padding(vertical = 2.dp)
    ) {
        Checkbox(checked = checked, onCheckedChange = { onCheckedChange() })
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun RadioRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 2.dp)
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}
