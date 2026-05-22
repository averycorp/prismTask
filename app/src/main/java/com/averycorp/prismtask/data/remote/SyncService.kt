package com.averycorp.prismtask.data.remote

import androidx.annotation.VisibleForTesting
import com.averycorp.prismtask.data.local.dao.AttachmentDao
import com.averycorp.prismtask.data.local.dao.AutomationRuleDao
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
import com.averycorp.prismtask.data.local.dao.TaskTimingDao
import com.averycorp.prismtask.data.local.dao.WeeklyReviewDao
import com.averycorp.prismtask.data.local.entity.SyncMetadataEntity
import com.averycorp.prismtask.data.preferences.BuiltInSyncPreferences
import com.averycorp.prismtask.data.remote.mapper.MedicationSyncMapper
import com.averycorp.prismtask.data.remote.mapper.SyncMapper
import com.averycorp.prismtask.data.remote.sync.PrismSyncLogger
import com.averycorp.prismtask.data.remote.sync.ReactiveSyncDriver
import com.averycorp.prismtask.data.remote.sync.SyncPullOrchestrator
import com.averycorp.prismtask.data.remote.sync.SyncStateRepository
import com.averycorp.prismtask.domain.usecase.ProFeatureGate
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

// See docs/audits/SYNCSERVICE_GOD_CLASS_REFACTOR_AUDIT.md for the surface-axis
// refactor plan (operator-confirmed May 4, 2026; Phase 1 + Slice 0 shipped via
// PRs #1118 + #1122; Phase 2 sub-PR 7a — Firestore constructor injection —
// shipped via this PR).
// TODO(sync-refactor): split SyncService — separate push, pull, listener,
// and initial-upload surfaces. Each PR that touches this file widens the
// file further; the next refactor should land before the next feature.
@Suppress("LargeClass")
@Singleton
class SyncService
@Inject
constructor(
    private val authManager: AuthManager,
    private val taskDao: TaskDao,
    private val projectDao: ProjectDao,
    private val tagDao: TagDao,
    private val syncMetadataDao: SyncMetadataDao,
    private val habitDao: HabitDao,
    private val habitCompletionDao: HabitCompletionDao,
    private val habitLogDao: HabitLogDao,
    private val taskTemplateDao: TaskTemplateDao,
    private val milestoneDao: MilestoneDao,
    private val taskCompletionDao: TaskCompletionDao,
    private val taskTimingDao: TaskTimingDao,
    private val proFeatureGate: ProFeatureGate,
    private val logger: PrismSyncLogger,
    private val syncStateRepository: SyncStateRepository,
    private val builtInHabitReconciler: BuiltInHabitReconciler,
    private val builtInTaskTemplateReconciler: BuiltInTaskTemplateReconciler,
    private val builtInTaskTemplateBackfiller: BuiltInTaskTemplateBackfiller,
    private val builtInUpdateDetector: com.averycorp.prismtask.domain.usecase.BuiltInUpdateDetector,
    private val cloudIdOrphanHealer: CloudIdOrphanHealer,
    private val builtInMedicationReconciler: BuiltInMedicationReconciler,
    private val medicationDao: MedicationDao,
    private val medicationDoseDao: MedicationDoseDao,
    private val medicationSlotDao: MedicationSlotDao,
    private val medicationSlotOverrideDao: MedicationSlotOverrideDao,
    private val medicationTierStateDao: MedicationTierStateDao,
    private val medicationMigrationPreferences: com.averycorp.prismtask.data.preferences.MedicationMigrationPreferences,
    private val sortPreferencesSyncService: SortPreferencesSyncService,
    private val schoolworkDao: SchoolworkDao,
    private val selfCareDao: SelfCareDao,
    private val notificationProfileDao: NotificationProfileDao,
    private val customSoundDao: CustomSoundDao,
    private val savedFilterDao: SavedFilterDao,
    private val nlpShortcutDao: NlpShortcutDao,
    private val habitTemplateDao: HabitTemplateDao,
    private val projectTemplateDao: ProjectTemplateDao,
    private val boundaryRuleDao: BoundaryRuleDao,
    private val automationRuleDao: AutomationRuleDao,
    private val checkInLogDao: CheckInLogDao,
    private val moodEnergyLogDao: MoodEnergyLogDao,
    private val focusReleaseLogDao: FocusReleaseLogDao,
    private val medicationRefillDao: MedicationRefillDao,
    private val weeklyReviewDao: WeeklyReviewDao,
    private val dailyEssentialSlotCompletionDao: DailyEssentialSlotCompletionDao,
    private val attachmentDao: AttachmentDao,
    private val builtInSyncPreferences: BuiltInSyncPreferences,
    private val database: com.averycorp.prismtask.data.local.database.PrismTaskDatabase,
    private val projectPhaseDao: ProjectPhaseDao,
    private val projectRiskDao: ProjectRiskDao,
    private val taskDependencyDao: TaskDependencyDao,
    private val externalAnchorDao: ExternalAnchorDao,
    private val firestore: FirebaseFirestore,
    /**
     * D8 Item 7 Strangler Fig 7c — realtime listener surface.
     */
    private val listenerManager: SyncListenerManager,
    /**
     * D8 Item 7 Strangler Fig 7b — push surface (orchestrator slice).
     * Owns the sort / iterate / log / retry loop. The 36-branch
     * pushCreate / pushUpdate / pushDelete dispatch remains on
     * SyncService for now; the orchestrator routes through a single
     * dispatch lambda passed in from [pushLocalChanges].
     */
    private val pushOrchestrator: SyncPushOrchestrator,
    /**
     * D8 Item 7 Strangler Fig 7d — initial-upload surface (orchestrator
     * slice). Owns the one-shot guard, isSyncing lifecycle, completion
     * telemetry, and post-release pull. `doInitialUpload` body remains
     * on SyncService and is passed in as a lambda.
     */
    private val initialUploadOrchestrator: SyncInitialUploadOrchestrator,
    private val syncPusher: com.averycorp.prismtask.data.remote.sync.SyncPusher,
    private val syncInitialUploader: com.averycorp.prismtask.data.remote.sync.SyncInitialUploader,
    /**
     * Refactor Tier 1 Slice 1 — pull surface (orchestrator slice). Owns
     * the ~1,400 LOC `pullRemoteChanges` body and its two private
     * helpers (`pullCollection`, `pullRoomConfigFamily`). The public
     * [pullRemoteChanges] entry on this class is now a one-line delegate
     * so every existing caller (full sync, listener manager, fuzz tests)
     * keeps compiling unchanged.
     */
    private val pullOrchestrator: SyncPullOrchestrator
) {
    private val listeners = mutableListOf<ListenerRegistration>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var isSyncing = false

    private fun userCollection(collection: String) =
        authManager.userId?.let { firestore.collection("users").document(it).collection(collection) }

    suspend fun initialUpload() {
        if (authManager.userId == null) return
        // D8 Item 7 7d — orchestration extracted to
        // [SyncInitialUploadOrchestrator]. The dispatch (`doInitialUpload`)
        // and the post-release pull (`pullRemoteChanges`) remain on
        // SyncService and are passed in as lambdas; once those surfaces
        // extract in follow-on PRs the lambdas become direct method
        // references with no signature change.
        initialUploadOrchestrator.runIfNeeded(
            isSyncing = { isSyncing },
            setSyncing = { isSyncing = it },
            doUpload = { syncInitialUploader.doInitialUpload() },
            postReleasePull = {
                val pullSummary = pullRemoteChanges()
                logger.debug(
                    operation = "initialUpload.post_release_pull",
                    status = "success",
                    detail = "applied=${pullSummary.applied} permanently_failed=${pullSummary.skippedPermanent}"
                )
            }
        )
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
        fun launchInitialUpload() {
        scope.launch {
            val start = System.currentTimeMillis()
            try {
                initialUpload()
                logger.info(
                    operation = "upload.initial",
                    status = "success",
                    durationMs = System.currentTimeMillis() - start
                )
            } catch (e: Exception) {
                logger.error(
                    operation = "upload.initial",
                    durationMs = System.currentTimeMillis() - start,
                    throwable = e
                )
            }
        }
    }


    suspend fun pushLocalChanges(): Int = syncPusher.pushLocalChanges(::processRemoteDeletions)

    /**
     * Refactor Tier 1 Slice 1 — extracted to [SyncPullOrchestrator].
     * Returns the number of remote documents applied locally across
     * all collections and the count that threw during apply (the
     * permanent-data-loss kind). Pull order is dependency-first so
     * FK resolution always finds a registered cloud→local mapping
     * (projects → tags → habits → tasks → task_completions → …).
     * Pull order, FK resolution, dedup paths, and the `pull.summary`
     * log shape are owned by the orchestrator — this method stays
     * as a one-line delegate so every caller (full sync, listener
     * manager, fuzz tests) compiles unchanged.
     */
    suspend fun pullRemoteChanges(): PullSummary = pullOrchestrator.pull()

    /**
     * Result of a [pullRemoteChanges] cycle. P0 sync audit PR-D — exposes
     * `skippedPermanent` so [fullSync] can plumb it into
     * `markSyncCompleted` and flip `sync.completed` to a data-loss
     * status when any doc threw during apply.
     */
    data class PullSummary(val applied: Int, val skippedPermanent: Int)

    suspend fun fullSync(trigger: String = "manual") {
        if (isSyncing) {
            logger.debug(
                operation = "sync.skipped",
                entity = "service",
                id = "firebase",
                status = "already_running",
                detail = "trigger=$trigger"
            )
            return
        }
        isSyncing = true
        val start = System.currentTimeMillis()
        syncStateRepository.markSyncStarted(source = SOURCE_FIREBASE, trigger = trigger)
        var pushed = 0
        var pulled = 0
        var permanentlyFailed = 0
        try {
            pushed = pushLocalChanges()
            val pullSummary = pullRemoteChanges()
            pulled = pullSummary.applied
            permanentlyFailed = pullSummary.skippedPermanent
            // Re-queue pushes for any local row with a cloud_id that no
            // longer has a matching Firestore doc. See
            // [CloudIdOrphanHealer] — covers post-Fix-D out-of-band wipe.
            try {
                cloudIdOrphanHealer.healOrphans()
            } catch (e: Exception) {
                logger.error(operation = "healer.error", throwable = e)
                try {
                    com.google.firebase.crashlytics.FirebaseCrashlytics
                        .getInstance()
                        .recordException(e)
                } catch (_: Exception) {
                }
            }
            builtInHabitReconciler.reconcileAfterSyncIfNeeded()
            builtInTaskTemplateReconciler.reconcileAfterSyncIfNeeded()
            builtInMedicationReconciler.reconcileAfterSyncIfNeeded()
            try {
                builtInUpdateDetector.refreshPendingUpdates()
            } catch (e: Exception) {
                try {
                    com.google.firebase.crashlytics.FirebaseCrashlytics
                        .getInstance()
                        .recordException(e)
                } catch (_: Exception) {
                }
            }
            syncStateRepository.markSyncCompleted(
                source = SOURCE_FIREBASE,
                success = true,
                durationMs = System.currentTimeMillis() - start,
                pushed = pushed,
                pulled = pulled,
                permanentlyFailed = permanentlyFailed
            )
        } catch (e: Exception) {
            syncStateRepository.markSyncCompleted(
                source = SOURCE_FIREBASE,
                success = false,
                durationMs = System.currentTimeMillis() - start,
                pushed = pushed,
                pulled = pulled,
                permanentlyFailed = permanentlyFailed,
                throwable = e
            )
            throw e
        } finally {
            isSyncing = false
        }
    }

    /**
     * Phase 2.5 one-shot restore. Migration_51_52 backfilled
     * `cloud_id` on every syncable entity table from `sync_metadata` at
     * upgrade time, but every subsequent `pullRemoteChanges` then NULLed
     * the column because `SyncMapper.mapToX` didn't yet accept a `cloudId`
     * parameter. This patch fixes the mapper AND runs this restore once
     * to re-populate the column on rows that were pre-existing when the
     * patch landed. Gated by [BuiltInSyncPreferences.isCloudIdRestoreDone]
     * so it runs exactly once; flag only flips to true on success.
     *
     * Uses `UPDATE OR IGNORE` so any row that would collide with the
     * unique index on `cloud_id` (if two local rows still point at the
     * same cloud doc via `sync_metadata`) is silently skipped — a belt-
     * and-suspenders for collision cases beyond what Migration_51_52
     * already resolved. Collisions are logged per-table as the `updated`
     * count being less than the null-cloud_id row count; the skipped
     * row keeps `cloud_id = NULL` and a later sync cycle can mend it.
     *
     * Does NOT mutate `sync_metadata` (read-only) and does NOT write to
     * Firestore.
     */
    private suspend fun restoreCloudIdFromMetadata() {
        if (builtInSyncPreferences.isCloudIdRestoreDone()) return

        // Mirrors [Migration_51_52.syncableTables] — the two must stay in
        // sync. If a new syncable entity is added in a future release,
        // extend both lists.
        val syncableTables = listOf(
            "tasks" to "task",
            "projects" to "project",
            "tags" to "tag",
            "habits" to "habit",
            "habit_completions" to "habit_completion",
            "habit_logs" to "habit_log",
            "task_completions" to "task_completion",
            "task_timings" to "task_timing",
            "task_templates" to "task_template",
            "milestones" to "milestone",
            "project_phases" to "project_phase",
            "project_risks" to "project_risk",
            "external_anchors" to "external_anchor",
            "task_dependencies" to "task_dependency",
            "courses" to "course",
            "course_completions" to "course_completion",
            "leisure_logs" to "leisure_log",
            "self_care_steps" to "self_care_step",
            "self_care_logs" to "self_care_log"
        )

        val db = database.openHelper.writableDatabase
        var totalUpdated = 0
        try {
            for ((table, entityType) in syncableTables) {
                val sql = """
                    UPDATE OR IGNORE `$table` SET `cloud_id` = (
                        SELECT NULLIF(sm.cloud_id, '')
                        FROM sync_metadata sm
                        WHERE sm.local_id = `$table`.id
                          AND sm.entity_type = '$entityType'
                    )
                    WHERE cloud_id IS NULL
                """.trimIndent()
                val updated = db.compileStatement(sql).use { it.executeUpdateDelete() }
                totalUpdated += updated
                logger.info(
                    operation = "cloudId.restore",
                    entity = entityType,
                    status = "success",
                    detail = "table=$table updated=$updated"
                )
            }
            builtInSyncPreferences.setCloudIdRestoreDone(true)
            logger.info(
                operation = "cloudId.restore",
                status = "success",
                detail = "total_updated=$totalUpdated"
            )
        } catch (e: Throwable) {
            logger.error(operation = "cloudId.restore", throwable = e)
            // Flag intentionally NOT set — retry on next boot.
        }
    }

    fun startAutoSync() {
        if (authManager.userId == null) return
        startRealtimeListeners()
        scope.launch {
            // Phase 2.5 — re-populate `cloud_id` on pre-existing rows
            // before any pull activity. Runs once; see [restoreCloudIdFromMetadata].
            try {
                restoreCloudIdFromMetadata()
            } catch (e: Exception) {
                try {
                    com.google.firebase.crashlytics.FirebaseCrashlytics
                        .getInstance()
                        .recordException(e)
                } catch (_: Exception) {
                }
            }
            // Per-family backfill guards mean maybeRunEntityBackfill is now
            // safe and cheap to call unconditionally — each family's internal
            // flag short-circuits the loop when already done. Previously
            // guarded by [isNewEntitiesBackfillDone]; that master flag is
            // still written on full success and remains the legacy-user
            // fallback inside each per-family accessor.
            try {
                syncInitialUploader.maybeRunEntityBackfill()
            } catch (e: Exception) {
                try {
                    com.google.firebase.crashlytics.FirebaseCrashlytics
                        .getInstance()
                        .recordException(e)
                } catch (_: Exception) {
                }
            }
            // Heal pre-template_key task_templates rows before the first
            // fullSync so the reconciler sees a correctly-shaped dataset on
            // the same cycle. See [BuiltInTaskTemplateBackfiller].
            try {
                builtInTaskTemplateBackfiller.runBackfillIfNeeded()
            } catch (e: Exception) {
                try {
                    com.google.firebase.crashlytics.FirebaseCrashlytics
                        .getInstance()
                        .recordException(e)
                } catch (_: Exception) {
                }
            }
            try {
                fullSync(trigger = "startAutoSync")
            } catch (e: Exception) {
                // Error already logged by fullSync / markSyncCompleted.
                try {
                    com.google.firebase.crashlytics.FirebaseCrashlytics
                        .getInstance()
                        .recordException(e)
                } catch (_: Exception) {
                }
            }
        }
        scope.launch {
            syncMetadataDao.observePending()
                .debounce(500L)
                .collect { entries ->
                    if (entries.isEmpty()) {
                        logger.debug(operation = "reactive.push.skipped", detail = "reason=queue_empty")
                        return@collect
                    }
                    if (isSyncing) {
                        logger.debug(operation = "reactive.push.skipped", detail = "reason=already_syncing")
                        return@collect
                    }
                    if (!syncStateRepository.isOnline.value) {
                        logger.debug(operation = "reactive.push.skipped", detail = "reason=offline")
                        return@collect
                    }
                    if (authManager.userId == null) {
                        logger.debug(operation = "reactive.push.skipped", detail = "reason=not_signed_in")
                        return@collect
                    }
                    isSyncing = true
                    logger.info(operation = "reactive.push.triggered", detail = "pendingCount=${entries.size}")
                    try {
                        pushLocalChanges()
                        logger.info(operation = "reactive.push.completed", detail = "pendingCount=${entries.size}")
                    } catch (e: Exception) {
                        logger.error(operation = "reactive.push.error", throwable = e)
                        try {
                            com.google.firebase.crashlytics.FirebaseCrashlytics
                                .getInstance()
                                .recordException(e)
                        } catch (_: Exception) {
                        }
                    } finally {
                        isSyncing = false
                    }
                }
        }
        // Backstop syncs:
        //   1. Reactive — fire fullSync when isOnline flips false→true so
        //      pending writes made offline don't sit in the queue until the
        //      next local edit. observePending above only re-emits on queue
        //      changes, so a network return alone never re-triggers it.
        //   2. Periodic — bound worst-case staleness while online, even if
        //      a connectivity callback is missed. fullSync's own isSyncing
        //      guard makes this safe to fire concurrently with manual /
        //      reactive runs.
        ReactiveSyncDriver(
            isOnline = syncStateRepository.isOnline,
            isSignedIn = { authManager.userId != null },
            periodMs = PERIODIC_SYNC_INTERVAL_MS,
            onTrigger = { trigger ->
                try {
                    fullSync(trigger = trigger)
                } catch (e: Exception) {
                    // fullSync logs its own failures via markSyncCompleted.
                    try {
                        com.google.firebase.crashlytics.FirebaseCrashlytics
                            .getInstance()
                            .recordException(e)
                    } catch (_: Exception) {
                    }
                }
            }
        ).start(scope)
    }

    fun startRealtimeListeners() {
        // D8 Item 7 7c — listener surface extracted to SyncListenerManager.
        // The orchestrator passes the still-inlined pull + remote-delete
        // surfaces as lambdas; once those land in their own classes
        // (sub-PRs 7b + 7d) the lambdas become direct method references.
        listenerManager.start(
            scope = scope,
            isSyncing = { isSyncing },
            onRemoteDeletes = ::processRemoteDeletions,
            pull = ::pullRemoteChanges
        )
    }

    private suspend fun processRemoteDeletions(collection: String, cloudIds: List<String>) {
        val entityType = entityTypeForCollectionName(collection) ?: return
        var deleted = 0
        for (cloudId in cloudIds) {
            val localId = syncMetadataDao.getLocalId(cloudId, entityType) ?: continue
            try {
                when (entityType) {
                    "task" -> taskDao.deleteById(localId)
                    "project" -> projectDao.deleteById(localId)
                    "tag" -> tagDao.getTagByIdOnce(localId)?.let { tagDao.delete(it) }
                    "habit" -> habitDao.deleteById(localId)
                    "habit_completion" -> habitCompletionDao.deleteById(localId)
                    "habit_log" -> { /* HabitLogDao has no by-ID delete; metadata is still cleaned up below */ }
                    "task_completion" -> taskCompletionDao.deleteById(localId)
                    "task_timing" -> taskTimingDao.deleteById(localId)
                    "milestone" -> milestoneDao.deleteById(localId)
                    "project_phase" -> projectPhaseDao.deleteById(localId)
                    "project_risk" -> projectRiskDao.deleteById(localId)
                    "external_anchor" -> externalAnchorDao.deleteById(localId)
                    "task_dependency" -> taskDependencyDao.deleteById(localId)
                    "task_template" -> taskTemplateDao.deleteTemplate(localId)
                    "course" -> schoolworkDao.deleteCourse(localId)
                    "course_completion" -> schoolworkDao.deleteCompletionById(localId)
                    // Leisure Budget v2.0: v1.x "leisure_log" delete dispatch
                    // retired alongside the table (migration 81→82). New
                    // "leisure_activity" / "leisure_session" deletes flow
                    // through BackendSyncMappers instead.
                    "self_care_step" -> selfCareDao.deleteStepById(localId)
                    "self_care_log" -> selfCareDao.deleteLogById(localId)
                    "medication" -> medicationDao.deleteById(localId)
                    "medication_dose" -> medicationDoseDao.deleteById(localId)
                    "medication_slot" -> medicationSlotDao.deleteById(localId)
                    "medication_slot_override" -> medicationSlotOverrideDao.deleteById(localId)
                    "medication_tier_state" -> medicationTierStateDao.deleteById(localId)
                    "notification_profile" ->
                        notificationProfileDao.getById(localId)?.let { notificationProfileDao.delete(it) }
                    "custom_sound" -> customSoundDao.deleteById(localId)
                    "saved_filter" -> savedFilterDao.deleteById(localId)
                    "nlp_shortcut" -> nlpShortcutDao.deleteById(localId)
                    "habit_template" -> habitTemplateDao.deleteById(localId)
                    "project_template" -> projectTemplateDao.deleteById(localId)
                    "boundary_rule" -> boundaryRuleDao.delete(localId)
                    "automation_rule" -> automationRuleDao.deleteById(localId)
                    "check_in_log" -> checkInLogDao.deleteById(localId)
                    "mood_energy_log" -> moodEnergyLogDao.deleteById(localId)
                    "focus_release_log" -> focusReleaseLogDao.deleteById(localId)
                    "medication_refill" -> medicationRefillDao.deleteById(localId)
                    "weekly_review" -> weeklyReviewDao.deleteById(localId)
                    "daily_essential_slot_completion" -> dailyEssentialSlotCompletionDao.deleteById(localId)
                    "assignment" -> schoolworkDao.deleteAssignment(localId)
                    "attachment" -> attachmentDao.deleteById(localId)
                    "study_log" -> schoolworkDao.deleteStudyLogById(localId)
                }
                syncMetadataDao.delete(localId, entityType)
                logger.info(
                    operation = "pull.delete",
                    entity = entityType,
                    id = cloudId,
                    status = "success"
                )
                deleted++
            } catch (e: Exception) {
                logger.error(
                    operation = "pull.delete",
                    entity = entityType,
                    id = cloudId,
                    throwable = e
                )
            }
        }
        logger.info(
            operation = "pull.delete.summary",
            entity = entityType,
            status = "success",
            detail = "deleted=$deleted"
        )
    }

    fun stopRealtimeListeners() {
        listenerManager.stop()
    }

    companion object {
        const val SOURCE_FIREBASE: String = "firebase"

        // 30 s — user-requested maximum interval between syncs while
        // online + signed in. Acts as a safety net for any reactive
        // trigger that doesn't fire (e.g. ConnectivityManager callback
        // missed because no collector was active).
        private const val PERIODIC_SYNC_INTERVAL_MS: Long = 30_000L

        // D8 Item 7 Strangler Fig 7e — pure dispatch tables now live in
        // [SyncDispatchTables]; these companion-object members delegate so
        // existing call-sites + [SyncServiceDispatchTest] keep working
        // unchanged. Behaviour-identical pass-throughs.

        @VisibleForTesting
        internal fun collectionNameFor(entityType: String): String =
            SyncDispatchTables.collectionNameFor(entityType)

        @VisibleForTesting
        internal fun entityTypeForCollectionName(collection: String): String? =
            SyncDispatchTables.entityTypeForCollectionName(collection)

        @VisibleForTesting
        internal fun pushOrderPriorityOf(entityType: String): Int =
            SyncDispatchTables.pushOrderPriorityOf(entityType)
    }
}
