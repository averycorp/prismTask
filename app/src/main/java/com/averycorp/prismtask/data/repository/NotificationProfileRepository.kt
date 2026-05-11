package com.averycorp.prismtask.data.repository

import com.averycorp.prismtask.data.local.dao.NotificationProfileDao
import com.averycorp.prismtask.data.local.entity.NotificationProfileEntity
import com.averycorp.prismtask.data.remote.SyncTracker
import com.averycorp.prismtask.domain.model.notifications.BuiltInSound
import com.averycorp.prismtask.domain.model.notifications.LockScreenVisibility
import com.averycorp.prismtask.domain.model.notifications.NotificationDisplayMode
import com.averycorp.prismtask.domain.model.notifications.UrgencyTier
import com.averycorp.prismtask.domain.model.notifications.VibrationIntensity
import com.averycorp.prismtask.domain.model.notifications.VibrationPreset
import com.averycorp.prismtask.domain.model.notifications.WatchSyncMode
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Replaces the earlier `ReminderProfileRepository`. Exposes CRUD for
 * [NotificationProfileEntity] plus [seedBuiltInsIfEmpty] which installs a
 * curated set of starter profiles (Default, Work, Focus, Weekend, Sleep,
 * Travel) on first run.
 */
@Singleton
class NotificationProfileRepository
@Inject
constructor(private val dao: NotificationProfileDao, private val syncTracker: SyncTracker) {
    fun getAll(): Flow<List<NotificationProfileEntity>> = dao.getAll()

    suspend fun getById(id: Long): NotificationProfileEntity? = dao.getById(id)

    suspend fun getByName(name: String): NotificationProfileEntity? = dao.getByName(name)

    suspend fun insert(profile: NotificationProfileEntity): Long {
        val stamped = profile.copy(updatedAt = System.currentTimeMillis())
        val id = dao.insert(stamped)
        if (!stamped.isBuiltIn) syncTracker.trackCreate(id, "notification_profile")
        return id
    }

    suspend fun update(profile: NotificationProfileEntity) {
        val stamped = profile.copy(updatedAt = System.currentTimeMillis())
        dao.update(stamped)
        if (!stamped.isBuiltIn) syncTracker.trackUpdate(stamped.id, "notification_profile")
    }

    suspend fun delete(profile: NotificationProfileEntity) {
        dao.delete(profile)
        syncTracker.trackDelete(profile.id, "notification_profile")
    }

    suspend fun count(): Int = dao.count()

    /**
     * Installs the built-in starter profiles exactly once. Idempotent: a
     * second call after data exists is a no-op. Callers (app startup,
     * onboarding) can invoke this freely.
     */
    suspend fun seedBuiltInsIfEmpty() {
        if (dao.count() > 0) return
        val now = System.currentTimeMillis()
        BUILT_IN_PROFILES.forEach { template ->
            dao.insert(template.toEntity(now))
        }
    }

    companion object {
        private const val DAY = 24L * 60 * 60 * 1000
        private const val HOUR = 60L * 60 * 1000

        /**
         * Template for seeding a built-in profile. Exposed so tests can
         * assert the catalog without requiring a DB.
         */
        data class BuiltInProfileTemplate(
            val name: String,
            val offsets: List<Long>,
            val urgencyTier: UrgencyTier = UrgencyTier.MEDIUM,
            val soundId: String = BuiltInSound.SYSTEM_DEFAULT_ID,
            val silent: Boolean = false,
            val volumePercent: Int = 70,
            val vibrationPreset: VibrationPreset = VibrationPreset.SINGLE_PULSE,
            val vibrationIntensity: VibrationIntensity = VibrationIntensity.MEDIUM,
            val displayMode: NotificationDisplayMode = NotificationDisplayMode.STANDARD_BANNER,
            val lockScreenVisibility: LockScreenVisibility = LockScreenVisibility.APP_NAME_ONLY,
            val escalation: Boolean = false,
            val escalationIntervalMinutes: Int? = null,
            val watchSyncMode: WatchSyncMode = WatchSyncMode.MIRROR_PHONE,
            val volumeOverride: Boolean = false
        ) {
            fun toEntity(now: Long): NotificationProfileEntity = NotificationProfileEntity(
                name = name,
                offsetsCsv = NotificationProfileEntity.encodeOffsets(offsets),
                escalation = escalation,
                escalationIntervalMinutes = escalationIntervalMinutes,
                isBuiltIn = true,
                createdAt = now,
                urgencyTierKey = urgencyTier.key,
                soundId = soundId,
                soundVolumePercent = volumePercent,
                silent = silent,
                vibrationPresetKey = vibrationPreset.key,
                vibrationIntensityKey = vibrationIntensity.key,
                displayModeKey = displayMode.key,
                lockScreenVisibilityKey = lockScreenVisibility.key,
                watchSyncModeKey = watchSyncMode.key,
                watchHapticPresetKey = vibrationPreset.key,
                quietHoursJson = null,
                escalationChainJson = null,
                volumeOverride = volumeOverride
            )
        }

        val BUILT_IN_PROFILES: List<BuiltInProfileTemplate> = listOf(
            BuiltInProfileTemplate(
                name = "Default",
                offsets = listOf(15 * 60 * 1000L, 0L),
                urgencyTier = UrgencyTier.MEDIUM
            ),
            BuiltInProfileTemplate(
                name = "Gentle",
                offsets = listOf(DAY, 0L),
                urgencyTier = UrgencyTier.LOW,
                vibrationPreset = VibrationPreset.SINGLE_PULSE,
                vibrationIntensity = VibrationIntensity.LIGHT,
                volumePercent = 40
            ),
            BuiltInProfileTemplate(
                name = "Aggressive",
                offsets = listOf(DAY, HOUR, 0L),
                urgencyTier = UrgencyTier.HIGH,
                vibrationPreset = VibrationPreset.TRIPLE,
                vibrationIntensity = VibrationIntensity.STRONG,
                escalation = true,
                escalationIntervalMinutes = 15,
                displayMode = NotificationDisplayMode.FULL_SCREEN,
                volumePercent = 90,
                volumeOverride = true
            ),
            BuiltInProfileTemplate(
                name = "Minimal",
                offsets = listOf(0L),
                urgencyTier = UrgencyTier.LOW,
                silent = true,
                vibrationPreset = VibrationPreset.NONE,
                volumePercent = 0
            ),
            BuiltInProfileTemplate(
                name = "Work",
                offsets = listOf(HOUR, 15 * 60 * 1000L, 0L),
                urgencyTier = UrgencyTier.MEDIUM,
                vibrationPreset = VibrationPreset.DOUBLE_PULSE,
                lockScreenVisibility = LockScreenVisibility.APP_NAME_ONLY
            ),
            BuiltInProfileTemplate(
                name = "Focus",
                offsets = listOf(0L),
                urgencyTier = UrgencyTier.LOW,
                silent = true,
                vibrationPreset = VibrationPreset.NONE,
                displayMode = NotificationDisplayMode.MINIMAL_CORNER
            ),
            BuiltInProfileTemplate(
                name = "Sleep",
                offsets = listOf(0L),
                urgencyTier = UrgencyTier.CRITICAL,
                silent = true,
                vibrationPreset = VibrationPreset.NONE,
                lockScreenVisibility = LockScreenVisibility.HIDDEN
            ),
            BuiltInProfileTemplate(
                name = "Weekend",
                offsets = listOf(HOUR, 0L),
                urgencyTier = UrgencyTier.LOW,
                vibrationPreset = VibrationPreset.SINGLE_PULSE,
                vibrationIntensity = VibrationIntensity.LIGHT
            ),
            BuiltInProfileTemplate(
                name = "Travel",
                offsets = listOf(DAY, HOUR, 15 * 60 * 1000L, 0L),
                urgencyTier = UrgencyTier.HIGH,
                watchSyncMode = WatchSyncMode.DIFFERENTIATED
            )
        )
    }
}
