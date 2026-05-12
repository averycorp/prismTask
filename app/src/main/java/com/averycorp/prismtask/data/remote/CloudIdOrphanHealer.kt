package com.averycorp.prismtask.data.remote

import com.averycorp.prismtask.data.local.dao.AttachmentDao
import com.averycorp.prismtask.data.local.dao.BoundaryRuleDao
import com.averycorp.prismtask.data.local.dao.CheckInLogDao
import com.averycorp.prismtask.data.local.dao.CustomSoundDao
import com.averycorp.prismtask.data.local.dao.DailyEssentialSlotCompletionDao
import com.averycorp.prismtask.data.local.dao.ExternalAnchorDao
import com.averycorp.prismtask.data.local.dao.FocusReleaseLogDao
import com.averycorp.prismtask.data.local.dao.HabitCompletionDao
import com.averycorp.prismtask.data.local.dao.HabitDao
import com.averycorp.prismtask.data.local.dao.HabitLogDao
import com.averycorp.prismtask.data.local.dao.HabitTemplateDao
import com.averycorp.prismtask.data.local.dao.MedicationDao
import com.averycorp.prismtask.data.local.dao.MedicationDoseDao
import com.averycorp.prismtask.data.local.dao.MedicationRefillDao
import com.averycorp.prismtask.data.local.dao.MedicationSlotDao
import com.averycorp.prismtask.data.local.dao.MedicationSlotOverrideDao
import com.averycorp.prismtask.data.local.dao.MedicationTierStateDao
import com.averycorp.prismtask.data.local.dao.MilestoneDao
import com.averycorp.prismtask.data.local.dao.MoodEnergyLogDao
import com.averycorp.prismtask.data.local.dao.NlpShortcutDao
import com.averycorp.prismtask.data.local.dao.NotificationProfileDao
import com.averycorp.prismtask.data.local.dao.ProjectDao
import com.averycorp.prismtask.data.local.dao.ProjectPhaseDao
import com.averycorp.prismtask.data.local.dao.ProjectRiskDao
import com.averycorp.prismtask.data.local.dao.ProjectTemplateDao
import com.averycorp.prismtask.data.local.dao.SavedFilterDao
import com.averycorp.prismtask.data.local.dao.SchoolworkDao
import com.averycorp.prismtask.data.local.dao.SelfCareDao
import com.averycorp.prismtask.data.local.dao.SyncMetadataDao
import com.averycorp.prismtask.data.local.dao.TagDao
import com.averycorp.prismtask.data.local.dao.TaskCompletionDao
import com.averycorp.prismtask.data.local.dao.TaskDao
import com.averycorp.prismtask.data.local.dao.TaskDependencyDao
import com.averycorp.prismtask.data.local.dao.TaskTemplateDao
import com.averycorp.prismtask.data.local.dao.WeeklyReviewDao
import com.averycorp.prismtask.data.local.entity.SyncMetadataEntity
import com.averycorp.prismtask.data.remote.sync.PrismSyncLogger
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Heals the "local row with a cloud_id whose Firestore doc no longer
 * exists" state. This arises when a user's Firestore subcollection is
 * wiped out of band (manual delete, account reset, phase-3 Fix-D cleanup)
 * while local rows still carry the now-stale cloud_id. Without recovery,
 * those rows stay locally usable but never push back to Firestore — the
 * reactive push observer only fires on Room-tracked changes, and the
 * one-shot [SyncService.initialUpload] never re-runs.
 *
 * The healer runs once per [SyncService.fullSync], after
 * [SyncService.pullRemoteChanges] and before the built-in reconcilers,
 * so the post-pull Firestore snapshot is the most recent possible view.
 *
 * Scope: all syncable entity families regardless of which upload path
 * originally minted them. The v1.4 "new entity" families
 * (`self_care_steps`, `self_care_logs`, `courses`, `course_completions`,
 * `leisure_logs`) land via [SyncService.maybeRunEntityBackfill]; Tier-1
 * entities (`tasks`, `projects`, `tags`, `habits`, `habit_completions`,
 * `habit_logs`, `task_completions`, `task_templates`, `milestones`) land
 * via [SyncService.doInitialUpload]. v1.4.37 added 7 config families
 * (`notification_profiles`, `custom_sounds`, `saved_filters`,
 * `nlp_shortcuts`, `habit_templates`, `project_templates`,
 * `boundary_rules`); v1.4.38 added 9 content families (`check_in_logs`,
 * `mood_energy_logs`, `focus_release_logs`, `medication_refills`,
 * `weekly_reviews`, `daily_essential_slot_completions`, `assignments`,
 * `attachments`, `study_logs`). All paths are one-shot and share the
 * same "local rows with stale cloud_id after out-of-band wipe" failure
 * mode — so the healer covers them uniformly.
 *
 * Algorithm per family:
 *  1. Enumerate local rows with a non-blank `cloud_id`.
 *  2. Fetch the current Firestore subcollection's document IDs.
 *  3. For each local row whose `cloud_id` is not in the remote ID set,
 *     upsert a [SyncMetadataEntity] with `pendingAction = "update"`
 *     AND `cloudId = row.cloud_id`. The upsert deliberately **preserves**
 *     the existing cloud_id so that [SyncService.pushUpdate]'s
 *     `docRef(cloudId).update(data)` has an explicit target.
 *  4. The reactive-push observer picks up the new pending actions
 *     ~500ms later (`observePending().debounce(500L)`) and pushes.
 *
 * **Reconciliation outcome after the delete-wins fix (2026-04-24):**
 * [SyncService.pushUpdate] now calls `docRef.update(data)` rather than
 * `docRef.set(data)`. `update` on a missing doc throws NOT_FOUND /
 * FAILED_PRECONDITION, which pushUpdate catches and routes through
 * [SyncService.processRemoteDeletions] to hard-delete the local row
 * and clear its `sync_metadata`. So when the healer enqueues an orphan:
 *  - If the doc is missing because another device deleted it (the
 *    common case), the push cleans up the orphan on the local device —
 *    delete wins, as specified.
 *  - If the doc is missing because of a catastrophic out-of-band
 *    Firestore wipe (rare), the push also cleans up the local row.
 *    Explicit "restore from local backup" is a separate product
 *    feature, not a silent side effect of normal sync. Pre-fix the
 *    healer silently resurrected docs, which silently undid legitimate
 *    cross-device deletions for every other user — never the intended
 *    behavior.
 *
 * FK considerations for Tier-1 families:
 *  - `habit_completions` / `habit_logs` reference `habits` via cloudId
 *    embedded in their Firestore doc body (resolved via
 *    `syncMetadataDao.getCloudId(completion.habitId, "habit")` at push
 *    time). In the orphan scenario, the parent habit's `sync_metadata`
 *    row is present with its stale cloud_id, so the child's push
 *    resolves to that same stale id — which is exactly what we want:
 *    the re-pushed parent will be at that id, so the child's reference
 *    is correct.
 *  - `task_completions` same reasoning for its `taskId` / `projectId`
 *    references.
 *  - [SyncService.pushLocalChanges] sorts pending actions so
 *    `project` / `tag` push before everything else, and
 *    `task_completion` pushes last; the healer's enqueue order is
 *    irrelevant because that sort reorders at push time.
 *  - `milestones` reference `projects` via cloudId embedded in the
 *    Firestore doc body (resolved via
 *    `syncMetadataDao.getCloudId(milestone.projectId, "project")` at
 *    push time). Same orphan-safe resolution story as
 *    habit_completions / task_completions: the parent project's
 *    sync_metadata is preserved, so the child's push resolves to the
 *    stale parent id which is the id the parent will re-create under.
 *
 * The healer does not gate on a one-shot flag. It is cheap to run every
 * sync (one `.get()` per tracked collection, skipped for families with
 * no local rows carrying a cloud_id) and correct — non-orphans exit the
 * per-row branch immediately.
 */
@Singleton
class CloudIdOrphanHealer
@Inject
constructor(
    private val authManager: AuthManager,
    private val syncMetadataDao: SyncMetadataDao,
    private val selfCareDao: SelfCareDao,
    private val schoolworkDao: SchoolworkDao,
    private val taskDao: TaskDao,
    private val projectDao: ProjectDao,
    private val tagDao: TagDao,
    private val habitDao: HabitDao,
    private val habitCompletionDao: HabitCompletionDao,
    private val habitLogDao: HabitLogDao,
    private val taskCompletionDao: TaskCompletionDao,
    private val taskTemplateDao: TaskTemplateDao,
    private val milestoneDao: MilestoneDao,
    // v1.4.37 config-entity sync (added 2026-04)
    private val notificationProfileDao: NotificationProfileDao,
    private val customSoundDao: CustomSoundDao,
    private val savedFilterDao: SavedFilterDao,
    private val nlpShortcutDao: NlpShortcutDao,
    private val habitTemplateDao: HabitTemplateDao,
    private val projectTemplateDao: ProjectTemplateDao,
    private val boundaryRuleDao: BoundaryRuleDao,
    // v1.4.38 content-entity sync (added 2026-04)
    private val checkInLogDao: CheckInLogDao,
    private val moodEnergyLogDao: MoodEnergyLogDao,
    private val focusReleaseLogDao: FocusReleaseLogDao,
    private val medicationRefillDao: MedicationRefillDao,
    private val weeklyReviewDao: WeeklyReviewDao,
    private val dailyEssentialSlotCompletionDao: DailyEssentialSlotCompletionDao,
    private val attachmentDao: AttachmentDao,
    // v1.5 medication core — was a pre-existing gap; added to healer alongside
    // the v1.5 medication slot system (A2 #6 PR1).
    private val medicationDao: MedicationDao,
    private val medicationDoseDao: MedicationDoseDao,
    // v1.5 medication slot system (A2 #6 PR1)
    private val medicationSlotDao: MedicationSlotDao,
    private val medicationSlotOverrideDao: MedicationSlotOverrideDao,
    private val medicationTierStateDao: MedicationTierStateDao,
    // PrismTask-timeline-class scope, PR-1.
    private val projectPhaseDao: ProjectPhaseDao,
    private val projectRiskDao: ProjectRiskDao,
    private val taskDependencyDao: TaskDependencyDao,
    private val externalAnchorDao: ExternalAnchorDao,
    private val logger: PrismSyncLogger
) {
    /**
     * Function type for fetching the document IDs currently present in a
     * Firestore subcollection. Extracted as a parameter so tests can
     * inject a fake without mocking the Firestore SDK's fluent chain.
     * Returns null to signal a fetch error (logged + treated as "skip
     * this family, retry next sync").
     */
    fun interface RemoteIdFetcher {
        suspend fun fetch(collection: String): Set<String>?
    }

    private val firestore by lazy { FirebaseFirestore.getInstance() }

    private val defaultFetcher = RemoteIdFetcher { collection ->
        val userId = authManager.userId ?: return@RemoteIdFetcher null
        val coll = firestore.collection("users").document(userId).collection(collection)
        try {
            coll.get().await().documents.map { it.id }.toSet()
        } catch (e: Exception) {
            logger.error(
                operation = "healer.$collection",
                entity = collection,
                throwable = e
            )
            null
        }
    }

    suspend fun healOrphans(fetcher: RemoteIdFetcher = defaultFetcher) {
        if (authManager.userId == null) return

        // ── v1.4 "new entity" families ──
        healFamily("self_care_steps", "self_care_step", fetcher) {
            selfCareDao.getAllStepsOnce().mapNotNull { entity ->
                entity.cloudId?.takeIf { it.isNotBlank() }?.let { CloudIdRow(entity.id, it) }
            }
        }
        healFamily("self_care_logs", "self_care_log", fetcher) {
            selfCareDao.getAllLogsOnce().mapNotNull { entity ->
                entity.cloudId?.takeIf { it.isNotBlank() }?.let { CloudIdRow(entity.id, it) }
            }
        }
        healFamily("courses", "course", fetcher) {
            schoolworkDao.getAllCoursesOnce().mapNotNull { entity ->
                entity.cloudId?.takeIf { it.isNotBlank() }?.let { CloudIdRow(entity.id, it) }
            }
        }
        healFamily("course_completions", "course_completion", fetcher) {
            schoolworkDao.getAllCompletionsOnce().mapNotNull { entity ->
                entity.cloudId?.takeIf { it.isNotBlank() }?.let { CloudIdRow(entity.id, it) }
            }
        }
        // Leisure Budget v2.0: v1.x leisure_logs Firestore healing retired
        // in migration 81→82.

        // ── Tier-1 families (via doInitialUpload) ──
        healFamily("projects", "project", fetcher) {
            projectDao.getAllProjectsOnce().mapNotNull { entity ->
                entity.cloudId?.takeIf { it.isNotBlank() }?.let { CloudIdRow(entity.id, it) }
            }
        }
        healFamily("tags", "tag", fetcher) {
            tagDao.getAllTagsOnce().mapNotNull { entity ->
                entity.cloudId?.takeIf { it.isNotBlank() }?.let { CloudIdRow(entity.id, it) }
            }
        }
        healFamily("habits", "habit", fetcher) {
            habitDao.getAllHabitsOnce().mapNotNull { entity ->
                entity.cloudId?.takeIf { it.isNotBlank() }?.let { CloudIdRow(entity.id, it) }
            }
        }
        healFamily("habit_completions", "habit_completion", fetcher) {
            habitCompletionDao.getAllCompletionsOnce().mapNotNull { entity ->
                entity.cloudId?.takeIf { it.isNotBlank() }?.let { CloudIdRow(entity.id, it) }
            }
        }
        healFamily("habit_logs", "habit_log", fetcher) {
            habitLogDao.getAllLogsOnce().mapNotNull { entity ->
                entity.cloudId?.takeIf { it.isNotBlank() }?.let { CloudIdRow(entity.id, it) }
            }
        }
        healFamily("tasks", "task", fetcher) {
            taskDao.getAllTasksOnce().mapNotNull { entity ->
                entity.cloudId?.takeIf { it.isNotBlank() }?.let { CloudIdRow(entity.id, it) }
            }
        }
        healFamily("task_completions", "task_completion", fetcher) {
            taskCompletionDao.getAllCompletionsOnce().mapNotNull { entity ->
                entity.cloudId?.takeIf { it.isNotBlank() }?.let { CloudIdRow(entity.id, it) }
            }
        }
        healFamily("task_templates", "task_template", fetcher) {
            taskTemplateDao.getAllTemplatesOnce().mapNotNull { entity ->
                entity.cloudId?.takeIf { it.isNotBlank() }?.let { CloudIdRow(entity.id, it) }
            }
        }
        healFamily("milestones", "milestone", fetcher) {
            milestoneDao.getAllMilestonesOnce().mapNotNull { entity ->
                entity.cloudId?.takeIf { it.isNotBlank() }?.let { CloudIdRow(entity.id, it) }
            }
        }
        healFamily("project_phases", "project_phase", fetcher) {
            projectPhaseDao.getAllPhasesOnce().mapNotNull { entity ->
                entity.cloudId?.takeIf { it.isNotBlank() }?.let { CloudIdRow(entity.id, it) }
            }
        }
        healFamily("project_risks", "project_risk", fetcher) {
            projectRiskDao.getAllRisksOnce().mapNotNull { entity ->
                entity.cloudId?.takeIf { it.isNotBlank() }?.let { CloudIdRow(entity.id, it) }
            }
        }
        healFamily("task_dependencies", "task_dependency", fetcher) {
            taskDependencyDao.getAllOnce().mapNotNull { entity ->
                entity.cloudId?.takeIf { it.isNotBlank() }?.let { CloudIdRow(entity.id, it) }
            }
        }
        healFamily("external_anchors", "external_anchor", fetcher) {
            externalAnchorDao.getAllOnce().mapNotNull { entity ->
                entity.cloudId?.takeIf { it.isNotBlank() }?.let { CloudIdRow(entity.id, it) }
            }
        }

        // ── v1.4.37 config-entity families ──
        // SyncMapper uses entityType "notification_profile" even though the
        // table is reminder_profiles and the Firestore collection is
        // notification_profiles (SyncService line 446).
        healFamily("notification_profiles", "notification_profile", fetcher) {
            notificationProfileDao.getAllOnce().mapNotNull { entity ->
                entity.cloudId?.takeIf { it.isNotBlank() }?.let { CloudIdRow(entity.id, it) }
            }
        }
        healFamily("custom_sounds", "custom_sound", fetcher) {
            customSoundDao.getAllOnce().mapNotNull { entity ->
                entity.cloudId?.takeIf { it.isNotBlank() }?.let { CloudIdRow(entity.id, it) }
            }
        }
        healFamily("saved_filters", "saved_filter", fetcher) {
            savedFilterDao.getAllOnce().mapNotNull { entity ->
                entity.cloudId?.takeIf { it.isNotBlank() }?.let { CloudIdRow(entity.id, it) }
            }
        }
        healFamily("nlp_shortcuts", "nlp_shortcut", fetcher) {
            nlpShortcutDao.getAllOnce().mapNotNull { entity ->
                entity.cloudId?.takeIf { it.isNotBlank() }?.let { CloudIdRow(entity.id, it) }
            }
        }
        healFamily("habit_templates", "habit_template", fetcher) {
            habitTemplateDao.getAllOnce().mapNotNull { entity ->
                entity.cloudId?.takeIf { it.isNotBlank() }?.let { CloudIdRow(entity.id, it) }
            }
        }
        healFamily("project_templates", "project_template", fetcher) {
            projectTemplateDao.getAllOnce().mapNotNull { entity ->
                entity.cloudId?.takeIf { it.isNotBlank() }?.let { CloudIdRow(entity.id, it) }
            }
        }
        healFamily("boundary_rules", "boundary_rule", fetcher) {
            boundaryRuleDao.getAllOnce().mapNotNull { entity ->
                entity.cloudId?.takeIf { it.isNotBlank() }?.let { CloudIdRow(entity.id, it) }
            }
        }

        // ── v1.4.38 content-entity families ──
        healFamily("check_in_logs", "check_in_log", fetcher) {
            checkInLogDao.getAllOnce().mapNotNull { entity ->
                entity.cloudId?.takeIf { it.isNotBlank() }?.let { CloudIdRow(entity.id, it) }
            }
        }
        healFamily("mood_energy_logs", "mood_energy_log", fetcher) {
            moodEnergyLogDao.getAllOnce().mapNotNull { entity ->
                entity.cloudId?.takeIf { it.isNotBlank() }?.let { CloudIdRow(entity.id, it) }
            }
        }
        // focus_release_logs carry an FK task_id (SET_NULL on task delete).
        // The parent task's sync_metadata is preserved separately, so the
        // child row's push resolves via taskCloudId lookup at push time.
        healFamily("focus_release_logs", "focus_release_log", fetcher) {
            focusReleaseLogDao.getAllOnce().mapNotNull { entity ->
                entity.cloudId?.takeIf { it.isNotBlank() }?.let { CloudIdRow(entity.id, it) }
            }
        }
        healFamily("medication_refills", "medication_refill", fetcher) {
            medicationRefillDao.getAllOnce().mapNotNull { entity ->
                entity.cloudId?.takeIf { it.isNotBlank() }?.let { CloudIdRow(entity.id, it) }
            }
        }
        healFamily("weekly_reviews", "weekly_review", fetcher) {
            weeklyReviewDao.getAllOnce().mapNotNull { entity ->
                entity.cloudId?.takeIf { it.isNotBlank() }?.let { CloudIdRow(entity.id, it) }
            }
        }
        healFamily("daily_essential_slot_completions", "daily_essential_slot_completion", fetcher) {
            dailyEssentialSlotCompletionDao.getAllOnce().mapNotNull { entity ->
                entity.cloudId?.takeIf { it.isNotBlank() }?.let { CloudIdRow(entity.id, it) }
            }
        }
        // assignments + study_logs live in SchoolworkDao alongside the
        // pre-existing courses + course_completions healers.
        healFamily("assignments", "assignment", fetcher) {
            schoolworkDao.getAllAssignmentsOnce().mapNotNull { entity ->
                entity.cloudId?.takeIf { it.isNotBlank() }?.let { CloudIdRow(entity.id, it) }
            }
        }
        healFamily("study_logs", "study_log", fetcher) {
            schoolworkDao.getAllStudyLogsOnce().mapNotNull { entity ->
                entity.cloudId?.takeIf { it.isNotBlank() }?.let { CloudIdRow(entity.id, it) }
            }
        }
        healFamily("attachments", "attachment", fetcher) {
            attachmentDao.getAllOnce().mapNotNull { entity ->
                entity.cloudId?.takeIf { it.isNotBlank() }?.let { CloudIdRow(entity.id, it) }
            }
        }

        // ── v1.5 medication core + slot system ──
        // medications + medication_doses were shipped in v1.4.37 but never
        // wired into this healer (pre-existing gap). Closed here alongside
        // the new slot-system families.
        healFamily("medications", "medication", fetcher) {
            medicationDao.getAllOnce().mapNotNull { entity ->
                entity.cloudId?.takeIf { it.isNotBlank() }?.let { CloudIdRow(entity.id, it) }
            }
        }
        // medication_doses carry an FK medication_id (CASCADE). The parent's
        // sync_metadata is preserved separately; child push resolves via
        // medCloudId lookup at push time, same orphan-safe story as
        // habit_completions.
        healFamily("medication_doses", "medication_dose", fetcher) {
            medicationDoseDao.getAllOnce().mapNotNull { entity ->
                entity.cloudId?.takeIf { it.isNotBlank() }?.let { CloudIdRow(entity.id, it) }
            }
        }
        healFamily("medication_slots", "medication_slot", fetcher) {
            medicationSlotDao.getAllOnce().mapNotNull { entity ->
                entity.cloudId?.takeIf { it.isNotBlank() }?.let { CloudIdRow(entity.id, it) }
            }
        }
        healFamily("medication_slot_overrides", "medication_slot_override", fetcher) {
            medicationSlotOverrideDao.getAllOnce().mapNotNull { entity ->
                entity.cloudId?.takeIf { it.isNotBlank() }?.let { CloudIdRow(entity.id, it) }
            }
        }
        healFamily("medication_tier_states", "medication_tier_state", fetcher) {
            medicationTierStateDao.getAllOnce().mapNotNull { entity ->
                entity.cloudId?.takeIf { it.isNotBlank() }?.let { CloudIdRow(entity.id, it) }
            }
        }
    }

    private suspend fun healFamily(
        collection: String,
        entityType: String,
        fetcher: RemoteIdFetcher,
        localSource: suspend () -> List<CloudIdRow>
    ) {
        val candidates = localSource()
        if (candidates.isEmpty()) return

        val remoteIds = fetcher.fetch(collection) ?: return

        var orphaned = 0
        for (row in candidates) {
            if (row.cloudId in remoteIds) continue
            syncMetadataDao.upsert(
                SyncMetadataEntity(
                    localId = row.localId,
                    entityType = entityType,
                    cloudId = row.cloudId,
                    pendingAction = "update",
                    lastSyncedAt = System.currentTimeMillis()
                )
            )
            orphaned++
        }

        if (orphaned > 0) {
            logger.info(
                operation = "healer.$collection",
                status = "healed",
                detail = "checked=${candidates.size} orphaned=$orphaned"
            )
        } else {
            logger.debug(
                operation = "healer.$collection",
                status = "no_op",
                detail = "checked=${candidates.size}"
            )
        }
    }

    private data class CloudIdRow(val localId: Long, val cloudId: String)
}
