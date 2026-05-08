package com.averycorp.prismtask.data.remote.sync

import com.averycorp.prismtask.data.billing.BillingManager
import com.averycorp.prismtask.data.local.dao.DailyEssentialSlotCompletionDao
import com.averycorp.prismtask.data.local.dao.HabitCompletionDao
import com.averycorp.prismtask.data.local.dao.HabitDao
import com.averycorp.prismtask.data.local.dao.MedicationDao
import com.averycorp.prismtask.data.local.dao.MedicationSlotDao
import com.averycorp.prismtask.data.local.dao.MedicationTierStateDao
import com.averycorp.prismtask.data.local.dao.ProjectDao
import com.averycorp.prismtask.data.local.dao.TagDao
import com.averycorp.prismtask.data.local.dao.TaskDao
import com.averycorp.prismtask.data.local.dao.TaskTemplateDao
import com.averycorp.prismtask.data.local.entity.DailyEssentialSlotCompletionEntity
import com.averycorp.prismtask.data.local.entity.HabitCompletionEntity
import com.averycorp.prismtask.data.local.entity.HabitEntity
import com.averycorp.prismtask.data.local.entity.MedicationEntity
import com.averycorp.prismtask.data.local.entity.ProjectEntity
import com.averycorp.prismtask.data.local.entity.TagEntity
import com.averycorp.prismtask.data.local.entity.TaskEntity
import com.averycorp.prismtask.data.local.entity.TaskTemplateEntity
import com.averycorp.prismtask.data.preferences.AuthTokenPreferences
import com.averycorp.prismtask.data.preferences.BackendSyncPreferences
import com.averycorp.prismtask.data.preferences.TemplatePreferences
import com.averycorp.prismtask.data.remote.AuthManager
import com.averycorp.prismtask.data.remote.api.FirebaseTokenRequest
import com.averycorp.prismtask.data.remote.api.PrismTaskApi
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Syncs local Room data with the FastAPI backend sync endpoints
 * (`/api/v1/sync/push` and `/api/v1/sync/pull`).
 *
 * This is independent of the Firebase [com.averycorp.prismtask.data.remote.SyncService]
 * — both can be enabled side-by-side.
 *
 * Conflict resolution: last-write-wins by `updated_at` timestamp. When a pulled
 * entity has an older `updated_at` than the local copy, the local copy is kept.
 *
 * The payload shape mirrors the Pydantic models in `backend/app/schemas/sync.py`.
 * All timestamps crossing the wire are ISO 8601 strings (the backend uses
 * `datetime` fields, which Pydantic rejects with 422 if we send epoch millis).
 */
@Singleton
class BackendSyncService
@Inject
constructor(
    private val api: PrismTaskApi,
    private val taskDao: TaskDao,
    private val projectDao: ProjectDao,
    private val tagDao: TagDao,
    private val habitDao: HabitDao,
    private val habitCompletionDao: HabitCompletionDao,
    private val taskTemplateDao: TaskTemplateDao,
    private val slotCompletionDao: DailyEssentialSlotCompletionDao,
    private val medicationDao: MedicationDao,
    private val medicationSlotDao: MedicationSlotDao,
    private val medicationTierStateDao: MedicationTierStateDao,
    private val authTokenPreferences: AuthTokenPreferences,
    private val backendSyncPreferences: BackendSyncPreferences,
    private val templatePreferences: TemplatePreferences,
    private val billingManager: BillingManager,
    private val authManager: AuthManager,
    private val logger: PrismSyncLogger,
    private val syncStateRepository: SyncStateRepository
) {
    /**
     * True when the user has backend JWTs stored (i.e. they've logged into or
     * registered with the FastAPI backend at least once).
     */
    suspend fun isConnected(): Boolean =
        !authTokenPreferences.getAccessToken().isNullOrBlank()

    /**
     * Run a full sync: push local changes, then pull remote changes. Returns
     * the server timestamp from the pull response on success.
     */
    suspend fun fullSync(trigger: String = "manual"): Result<SyncSummary> {
        val start = System.currentTimeMillis()
        syncStateRepository.markSyncStarted(source = SOURCE_BACKEND, trigger = trigger)
        var pushed = 0
        var pulled = 0
        return try {
            check(isConnected()) { "Not connected to backend. Sign in first." }
            checkAdminStatus()
            ensureTemplatesPushedOnFirstConnect()
            pushed = pushChanges()
            pulled = pullChanges()
            val summary = SyncSummary(
                pushed = pushed,
                pulled = pulled,
                lastSyncAt = backendSyncPreferences.getLastSyncAt()
            )
            syncStateRepository.markSyncCompleted(
                source = SOURCE_BACKEND,
                success = true,
                durationMs = System.currentTimeMillis() - start,
                pushed = pushed,
                pulled = pulled
            )
            Result.success(summary)
        } catch (e: Exception) {
            syncStateRepository.markSyncCompleted(
                source = SOURCE_BACKEND,
                success = false,
                durationMs = System.currentTimeMillis() - start,
                pushed = pushed,
                pulled = pulled,
                throwable = e
            )
            Result.failure(e)
        }
    }

    /**
     * Fetch user info from the backend and update admin status on the
     * BillingManager. Admin users automatically receive PRO tier access.
     *
     * Public so callers can refresh admin status independently of a full
     * sync — e.g., on app launch once Firebase auth confirms the user is
     * signed in, so the UI reflects admin state without waiting for the
     * next manual sync. Safely no-ops when the user has no backend JWT.
     */
    suspend fun checkAdminStatus() {
        if (!ensureBackendAuth()) return
        try {
            val userInfo = api.getMe()
            billingManager.setAdminStatus(userInfo.isAdmin)
            // Beta-tester Pro: server reports PRO via effective_tier but
            // the user is neither admin nor a paid PRO. The remaining
            // path is an active beta-code redemption — extend the
            // existing _isAdmin override pattern with a parallel lever.
            val betaPro = userInfo.effectiveTier == "PRO" &&
                !userInfo.isAdmin &&
                userInfo.tier != "PRO"
            billingManager.setBetaProStatus(betaPro)
            logger.debug(
                operation = "auth.admin_status",
                status = "fetched",
                detail = "isAdmin=${userInfo.isAdmin} betaPro=$betaPro"
            )
        } catch (e: Exception) {
            logger.warn(
                operation = "auth.admin_status",
                status = "failed",
                detail = "could not fetch /auth/me",
                throwable = e
            )
        }
    }

    /**
     * Ensures a backend JWT is stored, exchanging the current Firebase ID
     * token for one via `/auth/firebase` if the user is Firebase-signed-in
     * but has no backend tokens yet. Returns true when a backend JWT is
     * available after the attempt.
     *
     * Without this bootstrap, admin-only UI (and any other backend-gated
     * feature) never appears on fresh installs, because `AuthInterceptor`
     * can't attach a Bearer token to `/auth/me`.
     */
    private suspend fun ensureBackendAuth(): Boolean {
        if (isConnected()) return true
        val firebaseToken = authManager.getFirebaseIdToken()
        if (firebaseToken == null) {
            logger.debug(
                operation = "auth.token_exchange",
                status = "skipped",
                detail = "no firebase id token"
            )
            return false
        }
        return try {
            val tokens = api.firebaseLogin(FirebaseTokenRequest(firebaseToken = firebaseToken))
            authTokenPreferences.saveTokens(tokens.accessToken, tokens.refreshToken)
            logger.info(
                operation = "auth.token_exchange",
                status = "success",
                detail = "firebase -> backend JWT"
            )
            true
        } catch (e: Exception) {
            logger.warn(
                operation = "auth.token_exchange",
                status = "failed",
                throwable = e
            )
            false
        }
    }

    /**
     * First-connect helper: pushes *every* local template to the backend the
     * first time the user signs in with a JWT, regardless of the template's
     * `updatedAt` timestamp. After this runs once, subsequent syncs use the
     * normal `updatedAt > since` incremental filter in [pushChanges].
     *
     * The flag is stored in [TemplatePreferences] so it persists across app
     * restarts but resets when the user signs out (via [TemplatePreferences.clear]).
     *
     * Merge behavior for the opposite direction (remote templates landing
     * locally on a new device) is handled in [applyTemplateChanges] using
     * [com.averycorp.prismtask.data.repository.TaskTemplateRepository.mergeTemplatesByName].
     */
    suspend fun ensureTemplatesPushedOnFirstConnect() {
        if (!isConnected()) return
        if (templatePreferences.isFirstSyncDone()) return

        val templates = taskTemplateDao.getAllTemplatesOnce()
        if (templates.isEmpty()) {
            templatePreferences.setFirstSyncDone(true)
            return
        }

        val operations = templates.map { taskTemplateToOperation(it) }
        val request = SyncPushRequest(
            operations = operations,
            lastSync = null
        )
        try {
            api.syncPush(request)
            templatePreferences.setFirstSyncDone(true)
            logger.info(
                operation = "push.templates_first_connect",
                status = "success",
                detail = "templates=${templates.size}"
            )
        } catch (e: Exception) {
            logger.error(
                operation = "push.templates_first_connect",
                status = "failed",
                detail = "templates=${templates.size}",
                throwable = e
            )
            com.google.firebase.crashlytics.FirebaseCrashlytics
                .getInstance()
                .recordException(e)
            // Leave the flag unset so we retry next sync.
            throw e
        }
    }

    /**
     * Serialize all locally modified entities since last sync and send them
     * to `/api/v1/sync/push`. Returns the number of operations pushed.
     */
    suspend fun pushChanges(): Int {
        val since = backendSyncPreferences.getLastSyncAt()
        val operations = mutableListOf<SyncOperation>()

        // Tasks — use updatedAt
        taskDao
            .getAllTasksOnce()
            .filter { it.updatedAt > since }
            .forEach { operations += taskToOperation(it) }

        // Projects — use updatedAt
        projectDao
            .getAllProjectsOnce()
            .filter { it.updatedAt > since }
            .forEach { operations += projectToOperation(it) }

        // Tags — TagEntity only has createdAt
        tagDao
            .getAllTagsOnce()
            .filter { it.createdAt > since }
            .forEach { operations += tagToOperation(it) }

        // Habits — use updatedAt
        habitDao
            .getAllHabitsOnce()
            .filter { it.updatedAt > since }
            .forEach { operations += habitToOperation(it) }

        // Habit completions — use completedAt (no updatedAt field)
        habitCompletionDao
            .getAllCompletionsOnce()
            .filter { it.completedAt > since }
            .forEach { operations += habitCompletionToOperation(it) }

        // Task templates — use updatedAt. First-connect push is handled
        // separately by ensureTemplatesPushedOnFirstConnect; here we only pick
        // up templates the user has touched since the last successful sync.
        taskTemplateDao
            .getAllTemplatesOnce()
            .filter { it.updatedAt > since }
            .forEach { operations += taskTemplateToOperation(it) }

        // Daily Essentials medication slot completions — use updatedAt.
        slotCompletionDao
            .getChangedSince(since)
            .forEach { operations += slotCompletionToOperation(it) }

        // Medication time-logging entities (PR4 follow-up). Push order
        // matters: parents (medication, medication_slot) must arrive
        // before children (tier_state, mark) so the server-side
        // cloud_id resolution can find them. Within each row, we look
        // up the parent cloud_ids from local Room — entities whose
        // parents haven't synced yet (no cloud_id) are skipped and
        // retried next sync.
        val medications = medicationDao.getAllOnce().filter { it.updatedAt > since }
        medications.forEach { operations += medicationToOperation(it) }

        val slots = medicationSlotDao.getAllOnce().filter { it.updatedAt > since }
        slots.forEach { operations += medicationSlotToOperation(it) }

        // Build local id -> cloud_id maps once so the per-row lookups
        // below don't fan out into N+1 DB queries.
        val medCloudIdsById: Map<Long, String?> =
            medicationDao.getAllOnce().associate { it.id to it.cloudId }
        val slotCloudIdsById: Map<Long, String?> =
            medicationSlotDao.getAllOnce().associate { it.id to it.cloudId }

        val tierStates = medicationTierStateDao.getAllOnce().filter { it.updatedAt > since }
        tierStates.forEach { state ->
            val op = medicationTierStateToOperation(
                state,
                medicationCloudId = medCloudIdsById[state.medicationId],
                slotCloudId = slotCloudIdsById[state.slotId]
            )
            if (op != null) operations += op
        }

        if (operations.isEmpty()) return 0

        val request = SyncPushRequest(
            operations = operations,
            lastSync = if (since > 0) millisToIso(since) else null
        )
        val start = System.currentTimeMillis()
        try {
            api.syncPush(request)
            logger.debug(
                operation = "push.backend",
                status = "success",
                durationMs = System.currentTimeMillis() - start,
                detail = "operations=${operations.size}"
            )
        } catch (e: Exception) {
            logger.error(
                operation = "push.backend",
                status = "failed",
                durationMs = System.currentTimeMillis() - start,
                detail = "operations=${operations.size}",
                throwable = e
            )
            com.google.firebase.crashlytics.FirebaseCrashlytics
                .getInstance()
                .recordException(e)
            throw e
        }
        return operations.size
    }

    /**
     * Fetch all changes since the last sync timestamp and upsert them into
     * Room using last-write-wins conflict resolution. Returns the number of
     * rows applied locally.
     */
    suspend fun pullChanges(): Int {
        val since = backendSyncPreferences.getLastSyncAt()
        val sinceParam = if (since > 0) millisToIso(since) else null
        val pullStart = System.currentTimeMillis()
        val response = try {
            api.syncPull(sinceParam)
        } catch (e: Exception) {
            logger.error(
                operation = "pull.backend",
                status = "failed",
                durationMs = System.currentTimeMillis() - pullStart,
                throwable = e
            )
            com.google.firebase.crashlytics.FirebaseCrashlytics
                .getInstance()
                .recordException(e)
            throw e
        }

        var applied = 0
        // The backend applies projects before tasks so FK references resolve
        // correctly; mirror that ordering here.
        applied += applyProjectChanges(response.changes.filter { it.entityType == "project" })
        applied += applyTagChanges(response.changes.filter { it.entityType == "tag" })
        applied += applyHabitChanges(response.changes.filter { it.entityType == "habit" })
        applied += applyTaskChanges(response.changes.filter { it.entityType == "task" })
        applied += applyHabitCompletionChanges(
            response.changes.filter { it.entityType == "habit_completion" }
        )
        applied += applyTemplateChanges(
            response.changes.filter { it.entityType == "task_template" }
        )
        applied += applySlotCompletionChanges(
            response.changes.filter { it.entityType == "daily_essential_slot_completion" }
        )
        // Medications must apply before any child entity (slots, tier_states)
        // gains a backend-pull handler, so the cloud_id-keyed lookup the
        // children rely on can resolve. Push has handled medications since
        // PR4; pull was missed on the same round.
        applied += applyMedicationChanges(
            response.changes.filter { it.entityType == "medication" }
        )

        val timestampMillis = response.serverTimestamp?.let { isoToMillisOrNull(it) }
            ?: System.currentTimeMillis()
        backendSyncPreferences.setLastSyncAt(timestampMillis)
        logger.debug(
            operation = "pull.backend",
            status = "success",
            durationMs = System.currentTimeMillis() - pullStart,
            detail = "applied=$applied serverTs=$timestampMillis"
        )
        return applied
    }

    // region Push serialization

    // endregion

    // region Pull application

    private suspend fun applyTaskChanges(changes: List<SyncChange>): Int {
        var applied = 0
        for (change in changes) {
            val clientId = change.entityId
            if (change.operation == "delete") {
                taskDao.deleteById(clientId)
                applied++
                continue
            }
            val data = change.data ?: continue
            val remoteUpdatedAt = data.optLong("updated_at")
                ?: change.timestamp?.let { isoToMillisOrNull(it) }
                ?: System.currentTimeMillis()
            val existing = taskDao.getTaskByIdOnce(clientId)
            if (existing != null && existing.updatedAt >= remoteUpdatedAt) {
                // Local copy is newer or equal — keep it (last-write-wins).
                continue
            }
            val task = TaskEntity(
                id = clientId,
                title = data.optString("title") ?: "",
                description = data.optString("description"),
                dueDate = data.optLong("due_date"),
                dueTime = data.optLong("due_time"),
                priority = data.optInt("priority") ?: 0,
                isCompleted = data.optBool("is_completed") ?: false,
                projectId = data.optLong("project_id"),
                parentTaskId = data.optLong("parent_task_id"),
                recurrenceRule = data.optString("recurrence_rule"),
                reminderOffset = data.optLong("reminder_offset"),
                createdAt = data.optLong("created_at") ?: System.currentTimeMillis(),
                updatedAt = remoteUpdatedAt,
                completedAt = data.optLong("completed_at"),
                archivedAt = data.optLong("archived_at"),
                notes = data.optString("notes"),
                plannedDate = data.optLong("planned_date"),
                estimatedDuration = data.optInt("estimated_duration"),
                scheduledStartTime = data.optLong("scheduled_start_time"),
                sourceHabitId = data.optLong("source_habit_id"),
                lifeCategory = data.optString("life_category"),
                taskMode = data.optString("task_mode"),
                cognitiveLoad = data.optString("cognitive_load")
            )
            taskDao.insert(task)
            applied++
        }
        return applied
    }

    private suspend fun applyProjectChanges(changes: List<SyncChange>): Int {
        var applied = 0
        for (change in changes) {
            val clientId = change.entityId
            if (change.operation == "delete") {
                projectDao.getProjectByIdOnce(clientId)?.let { projectDao.delete(it) }
                applied++
                continue
            }
            val data = change.data ?: continue
            val remoteUpdatedAt = data.optLong("updated_at")
                ?: change.timestamp?.let { isoToMillisOrNull(it) }
                ?: System.currentTimeMillis()
            val existing = projectDao.getProjectByIdOnce(clientId)
            if (existing != null && existing.updatedAt >= remoteUpdatedAt) continue
            val project = ProjectEntity(
                id = clientId,
                name = data.optString("name") ?: "",
                color = data.optString("color") ?: "#4A90D9",
                icon = data.optString("icon") ?: "\uD83D\uDCC1",
                createdAt = data.optLong("created_at") ?: System.currentTimeMillis(),
                updatedAt = remoteUpdatedAt
            )
            projectDao.insert(project)
            applied++
        }
        return applied
    }

    private suspend fun applyTagChanges(changes: List<SyncChange>): Int {
        var applied = 0
        for (change in changes) {
            val clientId = change.entityId
            if (change.operation == "delete") {
                tagDao.getTagByIdOnce(clientId)?.let { tagDao.delete(it) }
                applied++
                continue
            }
            val data = change.data ?: continue
            val remoteCreatedAt = data.optLong("created_at")
                ?: change.timestamp?.let { isoToMillisOrNull(it) }
                ?: System.currentTimeMillis()
            val existing = tagDao.getTagByIdOnce(clientId)
            if (existing != null && existing.createdAt >= remoteCreatedAt) continue
            val tag = TagEntity(
                id = clientId,
                name = data.optString("name") ?: "",
                color = data.optString("color") ?: "#6B7280",
                createdAt = remoteCreatedAt
            )
            tagDao.insert(tag)
            applied++
        }
        return applied
    }

    private suspend fun applyHabitChanges(changes: List<SyncChange>): Int {
        var applied = 0
        for (change in changes) {
            val clientId = change.entityId
            if (change.operation == "delete") {
                habitDao.deleteById(clientId)
                applied++
                continue
            }
            val data = change.data ?: continue
            val remoteUpdatedAt = data.optLong("updated_at")
                ?: change.timestamp?.let { isoToMillisOrNull(it) }
                ?: System.currentTimeMillis()
            val existing = habitDao.getHabitByIdOnce(clientId)
            if (existing != null && existing.updatedAt >= remoteUpdatedAt) continue
            val habit = HabitEntity(
                id = clientId,
                name = data.optString("name") ?: "",
                description = data.optString("description"),
                targetFrequency = data.optInt("target_frequency") ?: 1,
                frequencyPeriod = data.optString("frequency_period") ?: "daily",
                activeDays = data.optString("active_days"),
                color = data.optString("color") ?: "#4A90D9",
                icon = data.optString("icon") ?: "\u2B50",
                reminderTime = data.optLong("reminder_time"),
                sortOrder = data.optInt("sort_order") ?: 0,
                isArchived = data.optBool("is_archived") ?: false,
                category = data.optString("category"),
                createDailyTask = data.optBool("create_daily_task") ?: false,
                reminderIntervalMillis = data.optLong("reminder_interval_millis"),
                reminderTimesPerDay = data.optInt("reminder_times_per_day") ?: 1,
                hasLogging = data.optBool("has_logging") ?: false,
                trackBooking = data.optBool("track_booking") ?: false,
                trackPreviousPeriod = data.optBool("track_previous_period") ?: false,
                isBookable = data.optBool("is_bookable") ?: false,
                isBooked = data.optBool("is_booked") ?: false,
                bookedDate = data.optLong("booked_date"),
                bookedNote = data.optString("booked_note"),
                showStreak = data.optBool("show_streak") ?: false,
                createdAt = data.optLong("created_at") ?: System.currentTimeMillis(),
                updatedAt = remoteUpdatedAt
            )
            habitDao.insert(habit)
            applied++
        }
        return applied
    }

    /**
     * Apply incoming medication changes. The backend stores a minimal
     * subset of the [MedicationEntity] columns (`cloud_id`, `name`,
     * `notes`, `is_active`) so the pull payload can only authoritatively
     * update those fields — every other column on a pre-existing local
     * row is preserved.
     *
     * Row resolution: medications are keyed by `cloud_id` across systems
     * (Android local id != backend integer id), so we look up local rows
     * by `cloud_id` first. If a remote medication shares a `name` with a
     * local row that hasn't been cloud-tagged yet, we adopt the remote
     * `cloud_id` onto the local row rather than inserting a duplicate
     * (the `medications.name` UNIQUE index would reject the second
     * insert anyway).
     */
    private suspend fun applyMedicationChanges(changes: List<SyncChange>): Int {
        var applied = 0
        for (change in changes) {
            // Per-change try/catch: a single bad change (e.g. a name that
            // races the medications.name UNIQUE index against a concurrent
            // local insert) must not abort the rest of the pull. Mirrors
            // SyncService.pullCollection's per-doc isolation.
            try {
                val data = change.data ?: continue
                val cloudId = data.optString("cloud_id") ?: continue
                val existing = medicationDao.getByCloudIdOnce(cloudId)
                    ?: data.optString("name")?.let { medicationDao.getByNameOnce(it) }

                if (change.operation == "delete") {
                    if (existing != null) {
                        medicationDao.deleteById(existing.id)
                        applied++
                    }
                    continue
                }

                val remoteUpdatedAt = data.optString("updated_at")
                    ?.let { isoToMillisOrNull(it) }
                    ?: change.timestamp?.let { isoToMillisOrNull(it) }
                    ?: System.currentTimeMillis()
                if (existing != null && existing.updatedAt >= remoteUpdatedAt) continue

                val name = data.optString("name") ?: existing?.name ?: continue
                val notes = data.optString("notes") ?: existing?.notes ?: ""
                val isArchived = data.optBool("is_active")?.let { !it }
                    ?: existing?.isArchived
                    ?: false
                val createdAt = data.optString("created_at")?.let { isoToMillisOrNull(it) }
                    ?: existing?.createdAt
                    ?: System.currentTimeMillis()

                if (existing != null) {
                    medicationDao.update(
                        existing.copy(
                            cloudId = cloudId,
                            name = name,
                            notes = notes,
                            isArchived = isArchived,
                            createdAt = createdAt,
                            updatedAt = remoteUpdatedAt
                        )
                    )
                } else {
                    medicationDao.insert(
                        MedicationEntity(
                            cloudId = cloudId,
                            name = name,
                            notes = notes,
                            isArchived = isArchived,
                            createdAt = createdAt,
                            updatedAt = remoteUpdatedAt
                        )
                    )
                }
                applied++
            } catch (e: Exception) {
                logger.error(
                    operation = "pull.apply",
                    entity = "medication",
                    id = change.entityId.toString(),
                    throwable = e
                )
                try {
                    com.google.firebase.crashlytics.FirebaseCrashlytics
                        .getInstance()
                        .recordException(e)
                } catch (_: Exception) {
                }
            }
        }
        return applied
    }

    /**
     * Apply incoming template changes using merge-by-name semantics. When a
     * remote template arrives that shares a name with a local template:
     *
     *  - If the remote copy has a strictly higher `usage_count`, overwrite the
     *    local row with the remote one (keeping the local row id intact so
     *    nothing else references a stale id).
     *  - Otherwise the local copy wins and we skip the remote update.
     *
     * Templates with a new name are inserted as-is. Deletes are honored
     * unconditionally.
     */
    private suspend fun applyTemplateChanges(changes: List<SyncChange>): Int {
        var applied = 0
        for (change in changes) {
            val clientId = change.entityId
            if (change.operation == "delete") {
                taskTemplateDao.deleteTemplate(clientId)
                applied++
                continue
            }
            val data = change.data ?: continue
            val remoteName = data.optString("name") ?: continue
            val remoteUsage = data.optInt("usage_count") ?: 0
            val remoteUpdatedAt = data.optLong("updated_at")
                ?: change.timestamp?.let { isoToMillisOrNull(it) }
                ?: System.currentTimeMillis()

            val remote = TaskTemplateEntity(
                id = clientId,
                name = remoteName,
                description = data.optString("description"),
                icon = data.optString("icon"),
                category = data.optString("category"),
                templateTitle = data.optString("template_title"),
                templateDescription = data.optString("template_description"),
                templatePriority = data.optInt("template_priority"),
                templateProjectId = data.optLong("template_project_id"),
                templateTagsJson = data.optString("template_tags_json"),
                templateRecurrenceJson = data.optString("template_recurrence_json"),
                templateDuration = data.optInt("template_duration"),
                templateSubtasksJson = data.optString("template_subtasks_json"),
                isBuiltIn = data.optBool("is_built_in") ?: false,
                usageCount = remoteUsage,
                lastUsedAt = data.optLong("last_used_at"),
                createdAt = data.optLong("created_at") ?: System.currentTimeMillis(),
                updatedAt = remoteUpdatedAt
            )

            // Merge-by-name: if there's already a local template with this
            // name (possibly at a different id, e.g., seeded built-in on a
            // new device), keep the higher-usage copy.
            val existingByName = taskTemplateDao.getTemplateByName(remoteName)
            if (existingByName != null) {
                if (remote.usageCount > existingByName.usageCount) {
                    taskTemplateDao.updateTemplate(
                        remote.copy(id = existingByName.id)
                    )
                    applied++
                }
                // Otherwise: local wins, no-op.
                continue
            }

            // No name collision — upsert as a normal sync.
            taskTemplateDao.insertTemplate(remote)
            applied++
        }
        return applied
    }

    private suspend fun applyHabitCompletionChanges(changes: List<SyncChange>): Int {
        var applied = 0
        for (change in changes) {
            val clientId = change.entityId
            if (change.operation == "delete") {
                // No delete-by-id on HabitCompletionDao; fall back to habit+date.
                val data = change.data ?: continue
                val habitId = data.optLong("habit_id") ?: continue
                val completedDate = data.optLong("completed_date") ?: continue
                val completedDateLocal = data.optString("completed_date_local")
                    ?: millisToLocalDate(completedDate)
                habitCompletionDao.deleteByHabitAndDateLocal(habitId, completedDateLocal)
                applied++
                continue
            }
            val data = change.data ?: continue
            val habitId = data.optLong("habit_id") ?: continue
            val fallbackTimestamp = change.timestamp?.let { isoToMillisOrNull(it) }
                ?: System.currentTimeMillis()
            val completedDate = data.optLong("completed_date") ?: 0L
            val completedDateLocal = data.optString("completed_date_local")
                ?: millisToLocalDate(completedDate)
            val completion = HabitCompletionEntity(
                id = clientId,
                habitId = habitId,
                completedDate = completedDate,
                completedAt = data.optLong("completed_at") ?: fallbackTimestamp,
                notes = data.optString("notes"),
                completedDateLocal = completedDateLocal
            )
            habitCompletionDao.insert(completion)
            applied++
        }
        return applied
    }

    private suspend fun applySlotCompletionChanges(changes: List<SyncChange>): Int {
        var applied = 0
        for (change in changes) {
            val clientId = change.entityId
            if (change.operation == "delete") {
                slotCompletionDao.deleteById(clientId)
                applied++
                continue
            }
            val data = change.data ?: continue
            val dateMillis = localDateToMillisOrNull(data.optString("date")) ?: continue
            val slotKey = data.optString("slot_key") ?: continue
            val remoteUpdatedAt = data.optString("updated_at")
                ?.let { isoToMillisOrNull(it) }
                ?: change.timestamp?.let { isoToMillisOrNull(it) }
                ?: System.currentTimeMillis()

            val existing = slotCompletionDao.getBySlotOnce(dateMillis, slotKey)
            if (existing != null && existing.updatedAt >= remoteUpdatedAt) continue

            val takenAtMillis = data.optString("taken_at")?.let { isoToMillisOrNull(it) }
            val row = DailyEssentialSlotCompletionEntity(
                id = existing?.id ?: clientId,
                date = dateMillis,
                slotKey = slotKey,
                medIdsJson = data.optString("med_ids_json") ?: "[]",
                takenAt = takenAtMillis,
                createdAt = data.optString("created_at")?.let { isoToMillisOrNull(it) }
                    ?: existing?.createdAt
                    ?: System.currentTimeMillis(),
                updatedAt = remoteUpdatedAt
            )
            slotCompletionDao.upsert(row)
            applied++
        }
        return applied
    }

    // endregion

    // region JsonObject helpers

    companion object {
        const val SOURCE_BACKEND: String = "backend"
    }
}

/**
 * Summary of a completed sync round returned by [BackendSyncService.fullSync].
 */
data class SyncSummary(
    val pushed: Int,
    val pulled: Int,
    val lastSyncAt: Long
)
