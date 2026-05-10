package com.averycorp.prismtask.data.remote

/**
 * D8 Item 7 Strangler Fig 7e — shared scaffolding extraction. The three
 * pure dispatch tables that route between entity types and Firestore
 * collection names live here as a top-level object so they're trivially
 * importable by future surface-axis classes (push / pull / listener /
 * initial-upload) without dragging the whole [SyncService] god-class
 * along.
 *
 * Behaviour-identical to the inline `when` blocks they replaced
 * (Slice 0 audit, PR #1122). [SyncService]'s companion-object members
 * delegate to this object so existing call-sites and the
 * [SyncServiceDispatchTest] entry points keep working unchanged.
 */
internal object SyncDispatchTables {
    fun collectionNameFor(entityType: String): String = when (entityType) {
        "habit_completion" -> "habit_completions"
        "habit_log" -> "habit_logs"
        "task_completion" -> "task_completions"
        "task_timing" -> "task_timings"
        "task_template" -> "task_templates"
        "course_completion" -> "course_completions"
        "leisure_log" -> "leisure_logs"
        "self_care_step" -> "self_care_steps"
        "self_care_log" -> "self_care_logs"
        "medication" -> "medications"
        "medication_dose" -> "medication_doses"
        "medication_slot" -> "medication_slots"
        "medication_slot_override" -> "medication_slot_overrides"
        "medication_tier_state" -> "medication_tier_states"
        "notification_profile" -> "notification_profiles"
        "custom_sound" -> "custom_sounds"
        "saved_filter" -> "saved_filters"
        "nlp_shortcut" -> "nlp_shortcuts"
        "habit_template" -> "habit_templates"
        "project_template" -> "project_templates"
        "project_phase" -> "project_phases"
        "project_risk" -> "project_risks"
        "external_anchor" -> "external_anchors"
        "task_dependency" -> "task_dependencies"
        "boundary_rule" -> "boundary_rules"
        "automation_rule" -> "automation_rules"
        "check_in_log" -> "check_in_logs"
        "mood_energy_log" -> "mood_energy_logs"
        "focus_release_log" -> "focus_release_logs"
        "medication_refill" -> "medication_refills"
        "weekly_review" -> "weekly_reviews"
        "daily_essential_slot_completion" -> "daily_essential_slot_completions"
        "assignment" -> "assignments"
        "attachment" -> "attachments"
        "study_log" -> "study_logs"
        else -> entityType + "s"
    }

    fun entityTypeForCollectionName(collection: String): String? = when (collection) {
        "tasks" -> "task"
        "projects" -> "project"
        "tags" -> "tag"
        "habits" -> "habit"
        "habit_completions" -> "habit_completion"
        "habit_logs" -> "habit_log"
        "task_completions" -> "task_completion"
        "task_timings" -> "task_timing"
        "milestones" -> "milestone"
        "project_phases" -> "project_phase"
        "project_risks" -> "project_risk"
        "external_anchors" -> "external_anchor"
        "task_dependencies" -> "task_dependency"
        "task_templates" -> "task_template"
        "courses" -> "course"
        "course_completions" -> "course_completion"
        "leisure_logs" -> "leisure_log"
        "self_care_steps" -> "self_care_step"
        "self_care_logs" -> "self_care_log"
        "medications" -> "medication"
        "medication_doses" -> "medication_dose"
        "medication_slots" -> "medication_slot"
        "medication_slot_overrides" -> "medication_slot_override"
        "medication_tier_states" -> "medication_tier_state"
        "notification_profiles" -> "notification_profile"
        "custom_sounds" -> "custom_sound"
        "saved_filters" -> "saved_filter"
        "nlp_shortcuts" -> "nlp_shortcut"
        "habit_templates" -> "habit_template"
        "project_templates" -> "project_template"
        "boundary_rules" -> "boundary_rule"
        "automation_rules" -> "automation_rule"
        "check_in_logs" -> "check_in_log"
        "mood_energy_logs" -> "mood_energy_log"
        "focus_release_logs" -> "focus_release_log"
        "medication_refills" -> "medication_refill"
        "weekly_reviews" -> "weekly_review"
        "daily_essential_slot_completions" -> "daily_essential_slot_completion"
        "assignments" -> "assignment"
        "attachments" -> "attachment"
        "study_logs" -> "study_log"
        else -> null
    }

    fun pushOrderPriorityOf(entityType: String): Int = when (entityType) {
        "project" -> 0
        "tag" -> 1
        "task_completion" -> 3
        else -> 2
    }
}
