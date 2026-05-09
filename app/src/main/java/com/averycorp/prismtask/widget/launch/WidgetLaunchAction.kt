package com.averycorp.prismtask.widget.launch

/**
 * Typed contract for widget → app deep links.
 *
 * Replaces the previous `String` literals carried under
 * `MainActivity.EXTRA_LAUNCH_ACTION`. Widgets serialize via [wireId]; the
 * Activity rehydrates via [deserialize] and `NavGraph` routes via an
 * exhaustive `when` so adding a new subclass without wiring it up is a
 * compile error rather than a silent no-op.
 *
 * @see docs/audits/DEFECT_FAMILY_HARDENING_AUDIT.md §C
 */
sealed class WidgetLaunchAction {
    abstract val wireId: String

    data object QuickAdd : WidgetLaunchAction() {
        override val wireId: String = "quick_add"
    }

    data object VoiceInput : WidgetLaunchAction() {
        override val wireId: String = "voice_input"
    }

    data object OpenTemplates : WidgetLaunchAction() {
        override val wireId: String = "open_templates"
    }

    data object OpenHabits : WidgetLaunchAction() {
        override val wireId: String = "open_habits"
    }

    data object OpenTimer : WidgetLaunchAction() {
        override val wireId: String = "open_timer"
    }

    data object OpenMatrix : WidgetLaunchAction() {
        override val wireId: String = "open_matrix"
    }

    data object OpenToday : WidgetLaunchAction() {
        override val wireId: String = "open_today"
    }

    data object OpenInbox : WidgetLaunchAction() {
        override val wireId: String = "open_inbox"
    }

    data object OpenMedication : WidgetLaunchAction() {
        override val wireId: String = "open_medication"
    }

    data object OpenInsights : WidgetLaunchAction() {
        override val wireId: String = "open_insights"
    }

    data class OpenTask(val taskId: Long) : WidgetLaunchAction() {
        override val wireId: String = WIRE_ID

        companion object {
            const val WIRE_ID: String = "open_task"
        }
    }

    /**
     * Resume the post-onboarding coachmark tour at [stepIndex]. Used by a
     * future tour resume notification / widget; the in-app resume chip
     * doesn't need this since it talks to the controller directly.
     */
    data class OpenTourStep(val stepIndex: Int) : WidgetLaunchAction() {
        override val wireId: String = "$WIRE_ID_PREFIX:$stepIndex"

        companion object {
            const val WIRE_ID_PREFIX: String = "open_tour_step"

            fun parseWireId(wireId: String): OpenTourStep? {
                if (!wireId.startsWith("$WIRE_ID_PREFIX:")) return null
                val idx = wireId.removePrefix("$WIRE_ID_PREFIX:").toIntOrNull() ?: return null
                if (idx < 0) return null
                return OpenTourStep(idx)
            }
        }
    }

    companion object {
        /**
         * Rehydrate a wire-format action. `wireId` is the string the widget
         * stamped onto the intent; `taskId` is the optional payload pulled
         * from the same intent (only meaningful for [OpenTask]).
         *
         * Returns null if the wire id is unknown or if [OpenTask] is missing
         * its required taskId.
         */
        fun deserialize(wireId: String?, taskId: Long? = null): WidgetLaunchAction? {
            if (wireId == null) return null
            return when (wireId) {
                QuickAdd.wireId -> QuickAdd
                VoiceInput.wireId -> VoiceInput
                OpenTemplates.wireId -> OpenTemplates
                OpenHabits.wireId -> OpenHabits
                OpenTimer.wireId -> OpenTimer
                OpenMatrix.wireId -> OpenMatrix
                OpenToday.wireId -> OpenToday
                OpenInbox.wireId -> OpenInbox
                OpenMedication.wireId -> OpenMedication
                OpenInsights.wireId -> OpenInsights
                OpenTask.WIRE_ID -> taskId?.let(::OpenTask)
                else -> OpenTourStep.parseWireId(wireId)
            }
        }
    }
}
