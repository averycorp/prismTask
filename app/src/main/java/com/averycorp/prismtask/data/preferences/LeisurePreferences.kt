package com.averycorp.prismtask.data.preferences

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.averycorp.prismtask.BuildConfig
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class CustomLeisureActivity(
    val id: String,
    val label: String,
    val icon: String
)

enum class LeisureSlotId { MUSIC, FLEX, LANGUAGE }

data class LeisureSlotConfig(
    val enabled: Boolean,
    val label: String,
    val emoji: String,
    val durationMinutes: Int,
    val gridColumns: Int,
    val autoComplete: Boolean,
    val hiddenBuiltInIds: List<String>,
    val customActivities: List<CustomLeisureActivity>
) {
    companion object {
        fun defaultFor(slot: LeisureSlotId): LeisureSlotConfig = when (slot) {
            LeisureSlotId.MUSIC -> LeisureSlotConfig(
                enabled = true,
                label = "Music Practice",
                emoji = "\uD83C\uDFB5",
                durationMinutes = 15,
                gridColumns = 3,
                autoComplete = true,
                hiddenBuiltInIds = emptyList(),
                customActivities = emptyList()
            )
            LeisureSlotId.FLEX -> LeisureSlotConfig(
                enabled = true,
                label = "Flexible",
                emoji = "\uD83C\uDFB2",
                durationMinutes = 30,
                gridColumns = 2,
                autoComplete = true,
                hiddenBuiltInIds = emptyList(),
                customActivities = emptyList()
            )
            // LANGUAGE defaults to disabled so existing installs don't
            // suddenly require completing a third slot to finish the
            // shared "Leisure" meta-habit. Onboarding flips this on when
            // the user picks any language in the template picker, and
            // the leisure-settings screen exposes the toggle for users
            // who want to opt in later.
            LeisureSlotId.LANGUAGE -> LeisureSlotConfig(
                enabled = false,
                label = "Language Practice",
                emoji = "\uD83D\uDDE3\uFE0F",
                durationMinutes = 15,
                gridColumns = 3,
                autoComplete = true,
                hiddenBuiltInIds = emptyList(),
                customActivities = emptyList()
            )
        }
    }
}

/**
 * User-added leisure section that lives alongside the built-in MUSIC / FLEX
 * slots. Unlike built-ins, a custom section has no seeded activity list — its
 * options are only the user-added [customActivities].
 */
data class CustomLeisureSection(
    val id: String,
    val label: String,
    val emoji: String,
    val enabled: Boolean,
    val durationMinutes: Int,
    val gridColumns: Int,
    val autoComplete: Boolean,
    val customActivities: List<CustomLeisureActivity>
)

internal val Context.leisureDataStore: DataStore<Preferences> by preferencesDataStore(name = "leisure_prefs")

@Singleton
class LeisurePreferences
@Inject
constructor(
    @ApplicationContext private val context: Context
) {
    private val gson = Gson()
    private val activityListType = object : TypeToken<List<CustomLeisureActivity>>() {}.type
    private val stringListType = object : TypeToken<List<String>>() {}.type
    private val sectionListType = object : TypeToken<List<CustomLeisureSection>>() {}.type

    companion object {
        private val CUSTOM_MUSIC_KEY = stringPreferencesKey("custom_music_activities")
        private val CUSTOM_FLEX_KEY = stringPreferencesKey("custom_flex_activities")
        private val CUSTOM_LANGUAGE_KEY = stringPreferencesKey("custom_language_activities")
        private val CUSTOM_SECTIONS_KEY = stringPreferencesKey("custom_sections")

        private val MUSIC_ENABLED_KEY = stringPreferencesKey("music_enabled")
        private val FLEX_ENABLED_KEY = stringPreferencesKey("flex_enabled")
        private val LANGUAGE_ENABLED_KEY = stringPreferencesKey("language_enabled")

        private val MUSIC_LABEL_KEY = stringPreferencesKey("music_label")
        private val FLEX_LABEL_KEY = stringPreferencesKey("flex_label")
        private val LANGUAGE_LABEL_KEY = stringPreferencesKey("language_label")

        private val MUSIC_EMOJI_KEY = stringPreferencesKey("music_emoji")
        private val FLEX_EMOJI_KEY = stringPreferencesKey("flex_emoji")
        private val LANGUAGE_EMOJI_KEY = stringPreferencesKey("language_emoji")

        private val MUSIC_DURATION_KEY = stringPreferencesKey("music_duration_minutes")
        private val FLEX_DURATION_KEY = stringPreferencesKey("flex_duration_minutes")
        private val LANGUAGE_DURATION_KEY = stringPreferencesKey("language_duration_minutes")

        private val MUSIC_COLUMNS_KEY = stringPreferencesKey("music_grid_columns")
        private val FLEX_COLUMNS_KEY = stringPreferencesKey("flex_grid_columns")
        private val LANGUAGE_COLUMNS_KEY = stringPreferencesKey("language_grid_columns")

        private val MUSIC_AUTO_KEY = stringPreferencesKey("music_auto_complete")
        private val FLEX_AUTO_KEY = stringPreferencesKey("flex_auto_complete")
        private val LANGUAGE_AUTO_KEY = stringPreferencesKey("language_auto_complete")

        private val MUSIC_HIDDEN_KEY = stringPreferencesKey("music_hidden_builtins")
        private val FLEX_HIDDEN_KEY = stringPreferencesKey("flex_hidden_builtins")
        private val LANGUAGE_HIDDEN_KEY = stringPreferencesKey("language_hidden_builtins")

        const val MIN_DURATION_MINUTES = 1
        const val MAX_DURATION_MINUTES = 240
        const val MIN_GRID_COLUMNS = 1
        const val MAX_GRID_COLUMNS = 4
        private const val LOG_TAG = "LeisurePrefs"
    }

    fun getSlotConfig(slot: LeisureSlotId): Flow<LeisureSlotConfig> =
        context.leisureDataStore.data.map { prefs -> readSlotConfig(prefs, slot) }

    private fun readSlotConfig(prefs: Preferences, slot: LeisureSlotId): LeisureSlotConfig {
        val default = LeisureSlotConfig.defaultFor(slot)
        val keys = keysFor(slot)
        // Wrap every gson.fromJson in runCatching: DataStore preference sync
        // (GenericPreferenceSyncService) round-trips values through Firestore,
        // and a malformed payload — empty string, partial write, type drift
        // from another device — would otherwise propagate JsonSyntaxException
        // straight up the StateFlow and crash the LeisureScreen composition
        // when the user navigates in. Default-on-failure mirrors the
        // behaviour readCustomSections already had.
        val hidden: List<String> = prefs[keys.hiddenKey]?.let { raw ->
            runCatching { gson.fromJson<List<String>>(raw, stringListType) }.getOrNull()
        }?.let { list -> (list as List<String?>?).orEmpty().filterNotNull() } ?: emptyList()
        val custom: List<CustomLeisureActivity> = prefs[keys.customKey]?.let { raw ->
            runCatching { gson.fromJson<List<CustomLeisureActivity>>(raw, activityListType) }.getOrNull()
        }?.let { list ->
            (list as List<CustomLeisureActivity?>?).orEmpty()
                .filterNotNull()
                .mapNotNull { it.sanitizedActivity() }
        } ?: emptyList()
        return LeisureSlotConfig(
            enabled = prefs[keys.enabledKey]?.toBooleanStrictOrNull() ?: default.enabled,
            label = prefs[keys.labelKey]?.takeIf { it.isNotBlank() } ?: default.label,
            emoji = prefs[keys.emojiKey]?.takeIf { it.isNotBlank() } ?: default.emoji,
            durationMinutes = prefs[keys.durationKey]?.toIntOrNull()
                ?.coerceIn(MIN_DURATION_MINUTES, MAX_DURATION_MINUTES)
                ?: default.durationMinutes,
            gridColumns = prefs[keys.columnsKey]?.toIntOrNull()
                ?.coerceIn(MIN_GRID_COLUMNS, MAX_GRID_COLUMNS)
                ?: default.gridColumns,
            autoComplete = prefs[keys.autoKey]?.toBooleanStrictOrNull() ?: default.autoComplete,
            hiddenBuiltInIds = hidden,
            customActivities = custom
        )
    }

    suspend fun updateSlotConfig(
        slot: LeisureSlotId,
        enabled: Boolean? = null,
        label: String? = null,
        emoji: String? = null,
        durationMinutes: Int? = null,
        gridColumns: Int? = null,
        autoComplete: Boolean? = null
    ) {
        val keys = keysFor(slot)
        context.leisureDataStore.edit { prefs ->
            enabled?.let { prefs[keys.enabledKey] = it.toString() }
            label?.let {
                val trimmed = it.trim()
                if (trimmed.isNotEmpty()) prefs[keys.labelKey] = trimmed
            }
            emoji?.let {
                val trimmed = it.trim()
                if (trimmed.isNotEmpty()) prefs[keys.emojiKey] = trimmed
            }
            durationMinutes?.let {
                prefs[keys.durationKey] = it
                    .coerceIn(MIN_DURATION_MINUTES, MAX_DURATION_MINUTES)
                    .toString()
            }
            gridColumns?.let {
                prefs[keys.columnsKey] = it
                    .coerceIn(MIN_GRID_COLUMNS, MAX_GRID_COLUMNS)
                    .toString()
            }
            autoComplete?.let { prefs[keys.autoKey] = it.toString() }
        }
    }

    suspend fun setBuiltInHidden(slot: LeisureSlotId, builtInId: String, hidden: Boolean) {
        val keys = keysFor(slot)
        context.leisureDataStore.edit { prefs ->
            val current: List<String> = prefs[keys.hiddenKey]?.let { raw ->
                runCatching { gson.fromJson<List<String>>(raw, stringListType) }.getOrNull()
            } ?: emptyList()
            val updated = if (hidden) (current + builtInId).distinct() else current.filter { it != builtInId }
            prefs[keys.hiddenKey] = gson.toJson(updated)
        }
    }

    suspend fun resetSlotConfig(slot: LeisureSlotId) {
        val keys = keysFor(slot)
        context.leisureDataStore.edit { prefs ->
            prefs.remove(keys.enabledKey)
            prefs.remove(keys.labelKey)
            prefs.remove(keys.emojiKey)
            prefs.remove(keys.durationKey)
            prefs.remove(keys.columnsKey)
            prefs.remove(keys.autoKey)
            prefs.remove(keys.hiddenKey)
        }
    }

    suspend fun addActivity(slot: LeisureSlotId, label: String, icon: String) {
        val key = keysFor(slot).customKey
        context.leisureDataStore.edit { prefs ->
            val current: List<CustomLeisureActivity> = prefs[key]?.let { raw ->
                runCatching { gson.fromJson<List<CustomLeisureActivity>>(raw, activityListType) }.getOrNull()
            } ?: emptyList()
            val id = "custom_${slot.name.lowercase()}_${System.currentTimeMillis()}"
            prefs[key] = gson.toJson(current + CustomLeisureActivity(id, label, icon))
        }
    }

    suspend fun removeActivity(slot: LeisureSlotId, id: String) {
        val key = keysFor(slot).customKey
        context.leisureDataStore.edit { prefs ->
            val current: List<CustomLeisureActivity> = prefs[key]?.let { raw ->
                runCatching { gson.fromJson<List<CustomLeisureActivity>>(raw, activityListType) }.getOrNull()
            } ?: emptyList()
            prefs[key] = gson.toJson(current.filter { it.id != id })
        }
    }

    fun getCustomMusicActivities(): Flow<List<CustomLeisureActivity>> =
        context.leisureDataStore.data.map { prefs ->
            prefs[CUSTOM_MUSIC_KEY]?.let { raw ->
                runCatching { gson.fromJson<List<CustomLeisureActivity>>(raw, activityListType) }.getOrNull()
            } ?: emptyList()
        }

    fun getCustomFlexActivities(): Flow<List<CustomLeisureActivity>> =
        context.leisureDataStore.data.map { prefs ->
            prefs[CUSTOM_FLEX_KEY]?.let { raw ->
                runCatching { gson.fromJson<List<CustomLeisureActivity>>(raw, activityListType) }.getOrNull()
            } ?: emptyList()
        }

    suspend fun addMusicActivity(label: String, icon: String) = addActivity(LeisureSlotId.MUSIC, label, icon)

    suspend fun addFlexActivity(label: String, icon: String) = addActivity(LeisureSlotId.FLEX, label, icon)

    suspend fun removeMusicActivity(id: String) = removeActivity(LeisureSlotId.MUSIC, id)

    suspend fun removeFlexActivity(id: String) = removeActivity(LeisureSlotId.FLEX, id)

    fun getCustomSections(): Flow<List<CustomLeisureSection>> =
        context.leisureDataStore.data
            .catch { exception ->
                if (exception is IOException) {
                    reportMitigation(
                        mitigationId = "M3_datastore_read_fail",
                        message = "M3_DATASTORE_READ_FAIL",
                        exception = exception,
                        customKeys = mapOf("exception_type" to exception.javaClass.simpleName)
                    )
                    emit(emptyPreferences())
                } else {
                    throw exception
                }
            }
            .map { prefs -> readCustomSections(prefs) }

    private fun readCustomSections(prefs: Preferences): List<CustomLeisureSection> {
        val raw = prefs[CUSTOM_SECTIONS_KEY] ?: return emptyList()
        val parsed = parseCustomSections(raw)
        val validSections = parsed.mapNotNull { section -> validateCustomSection(section) }
        // gson DOES NOT honour Kotlin non-null annotations — JSON `null`
        // (or a missing field) deserializes to a real Java `null` even
        // when the data class declares the property non-nullable. The
        // Firebase Crashlytics issue 0fe0a45f saw NPE inside
        // LeisureSlotConfig.<init> via LeisureViewModel.toSlotState
        // because a synced CustomLeisureSection had at least one null
        // String field (label/emoji/id) or a null List<CustomLeisureActivity>.
        // Filter out items that can't be made whole so the downstream
        // LeisureSlotConfig constructor never sees null.
        val sanitizedSections = validSections
            .mapNotNull { runCatching { it.sanitized() }.getOrNull() }
        logDebug(
            "readCustomSections POST-SANITIZE: input=${parsed.size} " +
                "output=${sanitizedSections.size} dropped=${parsed.size - sanitizedSections.size}"
        )
        if (parsed.size != sanitizedSections.size) {
            val droppedCount = parsed.size - sanitizedSections.size
            reportMitigation(
                mitigationId = "M2_sanitize_dropped",
                message = "M2_SANITIZE_DROPPED: $droppedCount sections; " +
                    "raw=${parsed.size} sanitized=${sanitizedSections.size}",
                exception = IllegalStateException("M2_SANITIZE_DROPPED"),
                customKeys = mapOf(
                    "dropped_count" to droppedCount.toString(),
                    "raw_count" to parsed.size.toString()
                )
            )
        }
        return sanitizedSections
    }

    private fun parseCustomSections(raw: String): List<CustomLeisureSection?> = try {
        (
            gson.fromJson<List<CustomLeisureSection>>(raw, sectionListType)
                as List<CustomLeisureSection?>?
        ).orEmpty()
    } catch (exception: JsonParseException) {
        reportMitigation(
            mitigationId = "M1_gson_parse_fail",
            message = "M1_GSON_PARSE_FAIL",
            exception = exception,
            customKeys = mapOf("raw_length" to raw.length.toString())
        )
        emptyList()
    }

    private fun validateCustomSection(
        section: CustomLeisureSection?
    ): CustomLeisureSection? {
        if (section == null) {
            reportInvalidCustomSection("section", "null")
            return null
        }
        if ((section.id as String?).isNullOrBlank()) {
            reportInvalidCustomSection("id", "null_or_blank")
            return null
        }
        if ((section.label as String?) == null) {
            reportInvalidCustomSection("label", sectionSummary(section))
            return null
        }
        val activities = section.customActivities as List<CustomLeisureActivity?>?
        if (activities == null) {
            reportInvalidCustomSection("activities", sectionSummary(section))
            return null
        }
        val invalidActivityIndex = activities.indexOfFirst { activity ->
            activity == null ||
                (activity.id as String?).isNullOrBlank() ||
                (activity.label as String?).isNullOrBlank()
        }
        if (invalidActivityIndex != -1) {
            val invalidActivity = activities[invalidActivityIndex]
            val invalidField = when {
                invalidActivity == null -> "activity"
                invalidActivity.idOrNull().isNullOrBlank() -> "activity_id"
                invalidActivity.labelOrNull().isNullOrBlank() -> "activity_label"
                else -> "activity"
            }
            reportInvalidCustomSection(invalidField, sectionSummary(section))
            return null
        }
        return section
    }

    private fun reportInvalidCustomSection(invalidField: String, details: String) {
        reportMitigation(
            mitigationId = "M1_section_invalid_field",
            message = "M1_SECTION_INVALID: field=$invalidField details=$details",
            exception = IllegalStateException("M1_SECTION_INVALID"),
            customKeys = mapOf("invalid_field" to invalidField)
        )
    }

    /**
     * Coerce gson-leaked nulls into safe defaults so this section can be
     * passed to non-null constructors without an NPE. Returns null only
     * when [id] is missing — every section needs a stable identity, and
     * fabricating one risks colliding with future legitimate sections.
     *
     * The casts to `String?` / `List<…>?` are intentional: gson bypasses
     * Kotlin's compiler-generated parameter-not-null checks via Unsafe
     * + reflection, so a property declared `val label: String` can hold
     * a real Java null at runtime even though the static type forbids
     * it. Casting to nullable type before the elvis preserves the null
     * check at bytecode level — without the cast, `label ?: "Section"`
     * is `SENSELESS_COMPARISON` and the compiler may elide the check.
     */
    private fun CustomLeisureSection.sanitized(): CustomLeisureSection? {
        val safeId = (id as String?) ?: return null
        val safeLabel = (label as String?) ?: "Section"
        val safeEmoji = (emoji as String?) ?: "✨"
        val safeActivities: List<CustomLeisureActivity> =
            ((customActivities as List<CustomLeisureActivity?>?) ?: emptyList())
                .filterNotNull()
                .mapNotNull { it.sanitizedActivity() }
        return copy(
            id = safeId,
            label = safeLabel,
            emoji = safeEmoji,
            durationMinutes = durationMinutes.coerceIn(MIN_DURATION_MINUTES, MAX_DURATION_MINUTES),
            gridColumns = gridColumns.coerceIn(MIN_GRID_COLUMNS, MAX_GRID_COLUMNS),
            customActivities = safeActivities
        )
    }

    private fun CustomLeisureActivity.sanitizedActivity(): CustomLeisureActivity? {
        val safeId = (id as String?) ?: return null
        val safeLabel = (label as String?) ?: return null
        val safeIcon = (icon as String?) ?: "✨"
        return copy(id = safeId, label = safeLabel, icon = safeIcon)
    }

    /**
     * Adds a new custom section. Returns the generated id so callers can
     * immediately reference it.
     */
    suspend fun addCustomSection(label: String, emoji: String): String {
        val trimmedLabel = label.trim().ifEmpty { "New Section" }
        val trimmedEmoji = emoji.trim().ifEmpty { "\u2728" }
        val id = "custom_section_${System.currentTimeMillis()}"
        logDebug("addCustomSection ENTER: label=$trimmedLabel emoji=$trimmedEmoji id=$id")
        context.leisureDataStore.edit { prefs ->
            val current = readCustomSections(prefs)
            logDebug("addCustomSection PRE-WRITE: ${customSectionSummary(current)}")
            val section = CustomLeisureSection(
                id = id,
                label = trimmedLabel,
                emoji = trimmedEmoji,
                enabled = true,
                durationMinutes = 15,
                gridColumns = 2,
                autoComplete = true,
                customActivities = emptyList()
            )
            prefs[CUSTOM_SECTIONS_KEY] = gson.toJson(current + section)
        }
        logCustomSectionsPostWrite("addCustomSection")
        return id
    }

    suspend fun removeCustomSection(id: String) {
        logDebug("removeCustomSection ENTER: id=$id")
        context.leisureDataStore.edit { prefs ->
            val current = readCustomSections(prefs)
            logDebug("removeCustomSection PRE-WRITE: ${customSectionSummary(current)}")
            prefs[CUSTOM_SECTIONS_KEY] = gson.toJson(current.filter { it.id != id })
        }
        logCustomSectionsPostWrite("removeCustomSection")
    }

    suspend fun updateCustomSection(
        id: String,
        enabled: Boolean? = null,
        label: String? = null,
        emoji: String? = null,
        durationMinutes: Int? = null,
        gridColumns: Int? = null,
        autoComplete: Boolean? = null
    ) {
        logDebug(
            "updateCustomSection ENTER: id=$id enabled=$enabled label=$label emoji=$emoji " +
                "durationMinutes=$durationMinutes gridColumns=$gridColumns autoComplete=$autoComplete"
        )
        context.leisureDataStore.edit { prefs ->
            val current = readCustomSections(prefs)
            logDebug("updateCustomSection PRE-WRITE: ${customSectionSummary(current)}")
            // If the target section is no longer present (e.g. wiped by a
            // sync pull between dialog-open and dialog-submit), don't write
            // back the unchanged list — the assignment would still trigger
            // a Preferences emission and add noise to the sync-fingerprint
            // cache without changing observable state. See
            // docs/audits/LEISURE_ADD_ACTIVITY_DELETES_SECTION_AUDIT.md
            // (Phase 1 batch 2, Item 2').
            if (current.none { it.id == id }) return@edit
            val updated = current.map { section ->
                if (section.id != id) return@map section
                logDebug("updateCustomSection MATCH: ${sectionSummary(section)}")
                section.copy(
                    enabled = enabled ?: section.enabled,
                    label = label?.trim()?.takeIf { it.isNotEmpty() } ?: section.label,
                    emoji = emoji?.trim()?.takeIf { it.isNotEmpty() } ?: section.emoji,
                    durationMinutes = durationMinutes
                        ?.coerceIn(MIN_DURATION_MINUTES, MAX_DURATION_MINUTES)
                        ?: section.durationMinutes,
                    gridColumns = gridColumns
                        ?.coerceIn(MIN_GRID_COLUMNS, MAX_GRID_COLUMNS)
                        ?: section.gridColumns,
                    autoComplete = autoComplete ?: section.autoComplete
                )
            }
            prefs[CUSTOM_SECTIONS_KEY] = gson.toJson(updated)
        }
        logCustomSectionsPostWrite("updateCustomSection")
    }

    suspend fun addCustomSectionActivity(sectionId: String, label: String, icon: String) {
        val trimmedLabel = label.trim()
        val trimmedIcon = icon.trim()
        logDebug(
            "addCustomSectionActivity ENTER: sectionId=$sectionId " +
                "label=$trimmedLabel icon=$trimmedIcon"
        )
        if (trimmedLabel.isEmpty() || trimmedIcon.isEmpty()) return
        context.leisureDataStore.edit { prefs ->
            val current = readCustomSections(prefs)
            logDebug("addCustomSectionActivity PRE-WRITE: ${customSectionSummary(current)}")
            if (current.none { it.id == sectionId }) return@edit
            val updated = current.map { section ->
                if (section.id != sectionId) return@map section
                logDebug("addCustomSectionActivity MATCH: ${sectionSummary(section)}")
                val activity = CustomLeisureActivity(
                    id = "custom_${sectionId}_${System.currentTimeMillis()}",
                    label = trimmedLabel,
                    icon = trimmedIcon
                )
                section.copy(customActivities = section.customActivities + activity)
            }
            prefs[CUSTOM_SECTIONS_KEY] = gson.toJson(updated)
        }
        logCustomSectionsPostWrite("addCustomSectionActivity")
    }

    suspend fun removeCustomSectionActivity(sectionId: String, activityId: String) {
        logDebug("removeCustomSectionActivity ENTER: sectionId=$sectionId activityId=$activityId")
        context.leisureDataStore.edit { prefs ->
            val current = readCustomSections(prefs)
            logDebug("removeCustomSectionActivity PRE-WRITE: ${customSectionSummary(current)}")
            if (current.none { it.id == sectionId }) return@edit
            val updated = current.map { section ->
                if (section.id != sectionId) return@map section
                logDebug("removeCustomSectionActivity MATCH: ${sectionSummary(section)}")
                section.copy(customActivities = section.customActivities.filter { it.id != activityId })
            }
            prefs[CUSTOM_SECTIONS_KEY] = gson.toJson(updated)
        }
        logCustomSectionsPostWrite("removeCustomSectionActivity")
    }

    private suspend fun logCustomSectionsPostWrite(functionName: String) {
        if (!BuildConfig.DEBUG) return
        val sections = context.leisureDataStore.data.first().let { prefs -> readCustomSections(prefs) }
        Log.d(LOG_TAG, "$functionName POST-WRITE: ${customSectionSummary(sections)}")
    }

    private fun logDebug(message: String) {
        if (BuildConfig.DEBUG) Log.d(LOG_TAG, message)
    }

    private fun reportMitigation(
        mitigationId: String,
        message: String,
        exception: Throwable,
        customKeys: Map<String, String> = emptyMap()
    ) {
        Log.e(LOG_TAG, message, exception)
        // Defensive: Crashlytics requires FirebaseApp.initializeApp(); in
        // Robolectric unit tests it isn't, and getInstance() throws.
        runCatching {
            FirebaseCrashlytics.getInstance().apply {
                setCustomKey("mitigation_id", mitigationId)
                customKeys.forEach { (key, value) -> setCustomKey(key, value) }
                recordException(exception)
            }
        }
    }

    private fun customSectionSummary(sections: List<CustomLeisureSection>): String =
        "size=${sections.size} [" + sections.joinToString { sectionSummary(it) } + "]"

    private fun sectionSummary(section: CustomLeisureSection): String =
        "${section.idOrNull()}:${section.labelOrNull()}:${section.activityCountOrNull()}"

    private fun CustomLeisureSection.idOrNull(): String? = id as String?

    private fun CustomLeisureSection.labelOrNull(): String? = label as String?

    private fun CustomLeisureSection.activityCountOrNull(): Int? =
        (customActivities as List<CustomLeisureActivity?>?)?.size

    private fun CustomLeisureActivity.idOrNull(): String? = id as String?

    private fun CustomLeisureActivity.labelOrNull(): String? = label as String?

    suspend fun clearAll() {
        context.leisureDataStore.edit { it.clear() }
    }

    private data class SlotKeys(
        val enabledKey: Preferences.Key<String>,
        val labelKey: Preferences.Key<String>,
        val emojiKey: Preferences.Key<String>,
        val durationKey: Preferences.Key<String>,
        val columnsKey: Preferences.Key<String>,
        val autoKey: Preferences.Key<String>,
        val hiddenKey: Preferences.Key<String>,
        val customKey: Preferences.Key<String>
    )

    private fun keysFor(slot: LeisureSlotId): SlotKeys = when (slot) {
        LeisureSlotId.MUSIC -> SlotKeys(
            MUSIC_ENABLED_KEY,
            MUSIC_LABEL_KEY,
            MUSIC_EMOJI_KEY,
            MUSIC_DURATION_KEY,
            MUSIC_COLUMNS_KEY,
            MUSIC_AUTO_KEY,
            MUSIC_HIDDEN_KEY,
            CUSTOM_MUSIC_KEY
        )
        LeisureSlotId.FLEX -> SlotKeys(
            FLEX_ENABLED_KEY,
            FLEX_LABEL_KEY,
            FLEX_EMOJI_KEY,
            FLEX_DURATION_KEY,
            FLEX_COLUMNS_KEY,
            FLEX_AUTO_KEY,
            FLEX_HIDDEN_KEY,
            CUSTOM_FLEX_KEY
        )
        LeisureSlotId.LANGUAGE -> SlotKeys(
            LANGUAGE_ENABLED_KEY,
            LANGUAGE_LABEL_KEY,
            LANGUAGE_EMOJI_KEY,
            LANGUAGE_DURATION_KEY,
            LANGUAGE_COLUMNS_KEY,
            LANGUAGE_AUTO_KEY,
            LANGUAGE_HIDDEN_KEY,
            CUSTOM_LANGUAGE_KEY
        )
    }
}
