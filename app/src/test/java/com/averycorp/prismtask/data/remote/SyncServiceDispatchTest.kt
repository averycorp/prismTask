package com.averycorp.prismtask.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-unit dispatch tests for SyncService's three pinned dispatch tables:
 *
 *   1. [SyncService.collectionNameFor]            entity type -> Firestore collection
 *   2. [SyncService.entityTypeForCollectionName]  inverse of (1), used by delete listeners
 *   3. [SyncService.pushOrderPriorityOf]          push-order foreign-key invariant
 *
 * Pre-refactor regression net per
 * docs/audits/SYNCSERVICE_SLICE_0_TEST_SHORING_AUDIT.md. Each branch of every
 * dispatch table is asserted explicitly so a surface-axis refactor that drops
 * or renames an entry fails loudly instead of silently breaking a single
 * entity's push/pull/delete path.
 */
class SyncServiceDispatchTest {

    // -----------------------------------------------------------------
    // collectionNameFor — forward dispatch (entity type -> collection)
    // -----------------------------------------------------------------

    @Test fun collectionNameFor_habitCompletion() =
        assertEquals("habit_completions", SyncService.collectionNameFor("habit_completion"))

    @Test fun collectionNameFor_habitLog() =
        assertEquals("habit_logs", SyncService.collectionNameFor("habit_log"))

    @Test fun collectionNameFor_taskCompletion() =
        assertEquals("task_completions", SyncService.collectionNameFor("task_completion"))

    @Test fun collectionNameFor_taskTiming() =
        assertEquals("task_timings", SyncService.collectionNameFor("task_timing"))

    @Test fun collectionNameFor_taskTemplate() =
        assertEquals("task_templates", SyncService.collectionNameFor("task_template"))

    @Test fun collectionNameFor_courseCompletion() =
        assertEquals("course_completions", SyncService.collectionNameFor("course_completion"))

    @Test fun collectionNameFor_leisureLog() =
        assertEquals("leisure_logs", SyncService.collectionNameFor("leisure_log"))

    @Test fun collectionNameFor_selfCareStep() =
        assertEquals("self_care_steps", SyncService.collectionNameFor("self_care_step"))

    @Test fun collectionNameFor_selfCareLog() =
        assertEquals("self_care_logs", SyncService.collectionNameFor("self_care_log"))

    @Test fun collectionNameFor_medication() =
        assertEquals("medications", SyncService.collectionNameFor("medication"))

    @Test fun collectionNameFor_medicationDose() =
        assertEquals("medication_doses", SyncService.collectionNameFor("medication_dose"))

    @Test fun collectionNameFor_medicationSlot() =
        assertEquals("medication_slots", SyncService.collectionNameFor("medication_slot"))

    @Test fun collectionNameFor_medicationSlotOverride() =
        assertEquals(
            "medication_slot_overrides",
            SyncService.collectionNameFor("medication_slot_override")
        )

    @Test fun collectionNameFor_medicationTierState() =
        assertEquals(
            "medication_tier_states",
            SyncService.collectionNameFor("medication_tier_state")
        )

    @Test fun collectionNameFor_notificationProfile() =
        assertEquals(
            "notification_profiles",
            SyncService.collectionNameFor("notification_profile")
        )

    @Test fun collectionNameFor_customSound() =
        assertEquals("custom_sounds", SyncService.collectionNameFor("custom_sound"))

    @Test fun collectionNameFor_savedFilter() =
        assertEquals("saved_filters", SyncService.collectionNameFor("saved_filter"))

    @Test fun collectionNameFor_nlpShortcut() =
        assertEquals("nlp_shortcuts", SyncService.collectionNameFor("nlp_shortcut"))

    @Test fun collectionNameFor_habitTemplate() =
        assertEquals("habit_templates", SyncService.collectionNameFor("habit_template"))

    @Test fun collectionNameFor_projectTemplate() =
        assertEquals("project_templates", SyncService.collectionNameFor("project_template"))

    @Test fun collectionNameFor_projectPhase() =
        assertEquals("project_phases", SyncService.collectionNameFor("project_phase"))

    @Test fun collectionNameFor_projectRisk() =
        assertEquals("project_risks", SyncService.collectionNameFor("project_risk"))

    @Test fun collectionNameFor_externalAnchor() =
        assertEquals("external_anchors", SyncService.collectionNameFor("external_anchor"))

    @Test fun collectionNameFor_taskDependency() =
        assertEquals("task_dependencies", SyncService.collectionNameFor("task_dependency"))

    @Test fun collectionNameFor_boundaryRule() =
        assertEquals("boundary_rules", SyncService.collectionNameFor("boundary_rule"))

    @Test fun collectionNameFor_automationRule() =
        assertEquals("automation_rules", SyncService.collectionNameFor("automation_rule"))

    @Test fun collectionNameFor_checkInLog() =
        assertEquals("check_in_logs", SyncService.collectionNameFor("check_in_log"))

    @Test fun collectionNameFor_moodEnergyLog() =
        assertEquals("mood_energy_logs", SyncService.collectionNameFor("mood_energy_log"))

    @Test fun collectionNameFor_focusReleaseLog() =
        assertEquals("focus_release_logs", SyncService.collectionNameFor("focus_release_log"))

    @Test fun collectionNameFor_medicationRefill() =
        assertEquals("medication_refills", SyncService.collectionNameFor("medication_refill"))

    @Test fun collectionNameFor_weeklyReview() =
        assertEquals("weekly_reviews", SyncService.collectionNameFor("weekly_review"))

    @Test fun collectionNameFor_dailyEssentialSlotCompletion() =
        assertEquals(
            "daily_essential_slot_completions",
            SyncService.collectionNameFor("daily_essential_slot_completion")
        )

    @Test fun collectionNameFor_assignment() =
        assertEquals("assignments", SyncService.collectionNameFor("assignment"))

    @Test fun collectionNameFor_attachment() =
        assertEquals("attachments", SyncService.collectionNameFor("attachment"))

    @Test fun collectionNameFor_studyLog() =
        assertEquals("study_logs", SyncService.collectionNameFor("study_log"))

    // Fallback semantics — types that hit `else -> entityType + "s"` and must
    // continue to pluralize naively. `task`/`project`/`tag`/`habit` push paths
    // rely on this fallback because they are intentionally absent from the
    // explicit branch list.
    @Test fun collectionNameFor_fallback_task() =
        assertEquals("tasks", SyncService.collectionNameFor("task"))

    @Test fun collectionNameFor_fallback_project() =
        assertEquals("projects", SyncService.collectionNameFor("project"))

    @Test fun collectionNameFor_fallback_tag() =
        assertEquals("tags", SyncService.collectionNameFor("tag"))

    @Test fun collectionNameFor_fallback_habit() =
        assertEquals("habits", SyncService.collectionNameFor("habit"))

    @Test fun collectionNameFor_fallback_unknown() =
        assertEquals("unknownentitys", SyncService.collectionNameFor("unknownentity"))

    @Test fun collectionNameFor_fallback_empty() =
        assertEquals("s", SyncService.collectionNameFor(""))

    // -----------------------------------------------------------------
    // entityTypeForCollectionName — inverse dispatch
    // -----------------------------------------------------------------

    @Test fun entityTypeForCollectionName_tasks() =
        assertEquals("task", SyncService.entityTypeForCollectionName("tasks"))

    @Test fun entityTypeForCollectionName_projects() =
        assertEquals("project", SyncService.entityTypeForCollectionName("projects"))

    @Test fun entityTypeForCollectionName_tags() =
        assertEquals("tag", SyncService.entityTypeForCollectionName("tags"))

    @Test fun entityTypeForCollectionName_habits() =
        assertEquals("habit", SyncService.entityTypeForCollectionName("habits"))

    @Test fun entityTypeForCollectionName_habitCompletions() =
        assertEquals(
            "habit_completion",
            SyncService.entityTypeForCollectionName("habit_completions")
        )

    @Test fun entityTypeForCollectionName_habitLogs() =
        assertEquals("habit_log", SyncService.entityTypeForCollectionName("habit_logs"))

    @Test fun entityTypeForCollectionName_taskCompletions() =
        assertEquals(
            "task_completion",
            SyncService.entityTypeForCollectionName("task_completions")
        )

    @Test fun entityTypeForCollectionName_taskTimings() =
        assertEquals("task_timing", SyncService.entityTypeForCollectionName("task_timings"))

    @Test fun entityTypeForCollectionName_milestones() =
        assertEquals("milestone", SyncService.entityTypeForCollectionName("milestones"))

    @Test fun entityTypeForCollectionName_projectPhases() =
        assertEquals(
            "project_phase",
            SyncService.entityTypeForCollectionName("project_phases")
        )

    @Test fun entityTypeForCollectionName_projectRisks() =
        assertEquals("project_risk", SyncService.entityTypeForCollectionName("project_risks"))

    @Test fun entityTypeForCollectionName_externalAnchors() =
        assertEquals(
            "external_anchor",
            SyncService.entityTypeForCollectionName("external_anchors")
        )

    @Test fun entityTypeForCollectionName_taskDependencies() =
        assertEquals(
            "task_dependency",
            SyncService.entityTypeForCollectionName("task_dependencies")
        )

    @Test fun entityTypeForCollectionName_taskTemplates() =
        assertEquals(
            "task_template",
            SyncService.entityTypeForCollectionName("task_templates")
        )

    @Test fun entityTypeForCollectionName_courses() =
        assertEquals("course", SyncService.entityTypeForCollectionName("courses"))

    @Test fun entityTypeForCollectionName_courseCompletions() =
        assertEquals(
            "course_completion",
            SyncService.entityTypeForCollectionName("course_completions")
        )

    @Test fun entityTypeForCollectionName_leisureLogs() =
        assertEquals("leisure_log", SyncService.entityTypeForCollectionName("leisure_logs"))

    @Test fun entityTypeForCollectionName_selfCareSteps() =
        assertEquals(
            "self_care_step",
            SyncService.entityTypeForCollectionName("self_care_steps")
        )

    @Test fun entityTypeForCollectionName_selfCareLogs() =
        assertEquals(
            "self_care_log",
            SyncService.entityTypeForCollectionName("self_care_logs")
        )

    @Test fun entityTypeForCollectionName_medications() =
        assertEquals("medication", SyncService.entityTypeForCollectionName("medications"))

    @Test fun entityTypeForCollectionName_medicationDoses() =
        assertEquals(
            "medication_dose",
            SyncService.entityTypeForCollectionName("medication_doses")
        )

    @Test fun entityTypeForCollectionName_medicationSlots() =
        assertEquals(
            "medication_slot",
            SyncService.entityTypeForCollectionName("medication_slots")
        )

    @Test fun entityTypeForCollectionName_medicationSlotOverrides() =
        assertEquals(
            "medication_slot_override",
            SyncService.entityTypeForCollectionName("medication_slot_overrides")
        )

    @Test fun entityTypeForCollectionName_medicationTierStates() =
        assertEquals(
            "medication_tier_state",
            SyncService.entityTypeForCollectionName("medication_tier_states")
        )

    @Test fun entityTypeForCollectionName_notificationProfiles() =
        assertEquals(
            "notification_profile",
            SyncService.entityTypeForCollectionName("notification_profiles")
        )

    @Test fun entityTypeForCollectionName_customSounds() =
        assertEquals(
            "custom_sound",
            SyncService.entityTypeForCollectionName("custom_sounds")
        )

    @Test fun entityTypeForCollectionName_savedFilters() =
        assertEquals(
            "saved_filter",
            SyncService.entityTypeForCollectionName("saved_filters")
        )

    @Test fun entityTypeForCollectionName_nlpShortcuts() =
        assertEquals(
            "nlp_shortcut",
            SyncService.entityTypeForCollectionName("nlp_shortcuts")
        )

    @Test fun entityTypeForCollectionName_habitTemplates() =
        assertEquals(
            "habit_template",
            SyncService.entityTypeForCollectionName("habit_templates")
        )

    @Test fun entityTypeForCollectionName_projectTemplates() =
        assertEquals(
            "project_template",
            SyncService.entityTypeForCollectionName("project_templates")
        )

    @Test fun entityTypeForCollectionName_boundaryRules() =
        assertEquals(
            "boundary_rule",
            SyncService.entityTypeForCollectionName("boundary_rules")
        )

    @Test fun entityTypeForCollectionName_automationRules() =
        assertEquals(
            "automation_rule",
            SyncService.entityTypeForCollectionName("automation_rules")
        )

    @Test fun entityTypeForCollectionName_checkInLogs() =
        assertEquals(
            "check_in_log",
            SyncService.entityTypeForCollectionName("check_in_logs")
        )

    @Test fun entityTypeForCollectionName_moodEnergyLogs() =
        assertEquals(
            "mood_energy_log",
            SyncService.entityTypeForCollectionName("mood_energy_logs")
        )

    @Test fun entityTypeForCollectionName_focusReleaseLogs() =
        assertEquals(
            "focus_release_log",
            SyncService.entityTypeForCollectionName("focus_release_logs")
        )

    @Test fun entityTypeForCollectionName_medicationRefills() =
        assertEquals(
            "medication_refill",
            SyncService.entityTypeForCollectionName("medication_refills")
        )

    @Test fun entityTypeForCollectionName_weeklyReviews() =
        assertEquals(
            "weekly_review",
            SyncService.entityTypeForCollectionName("weekly_reviews")
        )

    // `daily_essential_slot_completions` reverse mapping was removed in
    // parity Batch 5 PR-9 (D-E4) — the Firestore listener no longer
    // observes this collection. The forward mapping still works via
    // the default `entityType + "s"` fallback in `collectionNameFor`.
    @Test fun entityTypeForCollectionName_dailyEssentialSlotCompletions_returnsNull() =
        assertEquals(
            null,
            SyncService.entityTypeForCollectionName("daily_essential_slot_completions")
        )

    @Test fun entityTypeForCollectionName_assignments() =
        assertEquals(
            "assignment",
            SyncService.entityTypeForCollectionName("assignments")
        )

    @Test fun entityTypeForCollectionName_attachments() =
        assertEquals(
            "attachment",
            SyncService.entityTypeForCollectionName("attachments")
        )

    @Test fun entityTypeForCollectionName_studyLogs() =
        assertEquals("study_log", SyncService.entityTypeForCollectionName("study_logs"))

    // Unknown collection -> null so processRemoteDeletions early-exits without
    // touching the DAO graph. Empty string and arbitrary garbage must both
    // route to the same "ignore" branch.
    @Test fun entityTypeForCollectionName_unknown_returnsNull() =
        assertNull(SyncService.entityTypeForCollectionName("unknown_collection"))

    @Test fun entityTypeForCollectionName_empty_returnsNull() =
        assertNull(SyncService.entityTypeForCollectionName(""))

    // -----------------------------------------------------------------
    // Bidirectional consistency — the drift-catch test. Every entityType
    // that has a forward mapping must round-trip via collection -> entity,
    // unless its forward mapping uses the `else` fallback (which we don't
    // require to be invertible — `tasks`, `projects`, `tags`, `habits`
    // and `milestones` are inverse-only since they don't have explicit
    // forward branches).
    // -----------------------------------------------------------------

    @Test
    fun bidirectional_explicitForwardEntries_roundTripViaInverse() {
        val explicitEntries = listOf(
            "habit_completion", "habit_log", "task_completion", "task_timing",
            "task_template", "course_completion", "leisure_log", "self_care_step",
            "self_care_log", "medication", "medication_dose", "medication_slot",
            "medication_slot_override", "medication_tier_state",
            "notification_profile", "custom_sound", "saved_filter",
            "nlp_shortcut", "habit_template", "project_template",
            "project_phase", "project_risk", "external_anchor",
            "task_dependency", "boundary_rule", "automation_rule",
            "check_in_log", "mood_energy_log", "focus_release_log",
            "medication_refill", "weekly_review",
            // `daily_essential_slot_completion` excluded: parity Batch 5
            // PR-9 (D-E4) removed its reverse mapping so the Firestore
            // listener no longer observes that collection. The forward
            // mapping still works via the default fallback, but the
            // bidirectional contract is intentionally relaxed.
            "assignment", "attachment",
            "study_log"
        )
        for (entity in explicitEntries) {
            val collection = SyncService.collectionNameFor(entity)
            val roundTripped = SyncService.entityTypeForCollectionName(collection)
            assertEquals(
                "round-trip drift for entity=$entity (collection=$collection)",
                entity,
                roundTripped
            )
        }
    }

    @Test
    fun bidirectional_fallbackForwardEntries_roundTripViaInverse() {
        // task / project / tag / habit hit the `else -> entityType + "s"`
        // forward branch but DO have explicit inverse entries. The pair must
        // still round-trip.
        val fallbackEntries = listOf("task", "project", "tag", "habit")
        for (entity in fallbackEntries) {
            val collection = SyncService.collectionNameFor(entity)
            val roundTripped = SyncService.entityTypeForCollectionName(collection)
            assertEquals(
                "fallback round-trip drift for entity=$entity (collection=$collection)",
                entity,
                roundTripped
            )
        }
    }

    @Test
    fun bidirectional_inverseOnly_milestoneHasNoForwardExplicitEntry() {
        // `milestones` is in the inverse table (delete listener routes it)
        // but `milestone` is NOT an explicit forward entry — it falls through
        // to `else -> "milestones"`. This test pins that current state so a
        // future addition of an explicit `"milestone" -> "milestones"` branch
        // is a deliberate edit, not an accidental rename.
        assertEquals("milestone", SyncService.entityTypeForCollectionName("milestones"))
        assertEquals("milestones", SyncService.collectionNameFor("milestone"))
    }

    // -----------------------------------------------------------------
    // pushOrderPriorityOf — push-order foreign-key invariant
    // -----------------------------------------------------------------

    @Test fun pushOrderPriority_project_first() =
        assertEquals(0, SyncService.pushOrderPriorityOf("project"))

    @Test fun pushOrderPriority_tag_second() =
        assertEquals(1, SyncService.pushOrderPriorityOf("tag"))

    @Test fun pushOrderPriority_taskCompletion_last() =
        assertEquals(3, SyncService.pushOrderPriorityOf("task_completion"))

    @Test fun pushOrderPriority_default_middle() =
        assertEquals(2, SyncService.pushOrderPriorityOf("task"))

    @Test fun pushOrderPriority_default_anyOther() {
        assertEquals(2, SyncService.pushOrderPriorityOf("habit"))
        assertEquals(2, SyncService.pushOrderPriorityOf("medication"))
        assertEquals(2, SyncService.pushOrderPriorityOf("anything_else"))
        assertEquals(2, SyncService.pushOrderPriorityOf(""))
    }

    @Test
    fun pushOrderPriority_sortsRespectsForeignKeyOrder() {
        // Mixed input proves the priority numbers actually order correctly
        // when used as `sortedBy { pushOrderPriorityOf(...) }` — projects
        // before tags before others before task_completions.
        val mixed = listOf(
            "task_completion",
            "tag",
            "task",
            "project",
            "habit",
            "task_completion",
            "project",
            "tag"
        )
        val sorted = mixed.sortedBy { SyncService.pushOrderPriorityOf(it) }
        // First two slots: projects.
        assertEquals("project", sorted[0])
        assertEquals("project", sorted[1])
        // Next two slots: tags.
        assertEquals("tag", sorted[2])
        assertEquals("tag", sorted[3])
        // Last two slots: task_completions.
        assertEquals("task_completion", sorted[6])
        assertEquals("task_completion", sorted[7])
        // Middle slots: any-order tasks/habits with priority 2.
        val middle = sorted.subList(4, 6).toSet()
        assertEquals(setOf("task", "habit"), middle)
    }
}
