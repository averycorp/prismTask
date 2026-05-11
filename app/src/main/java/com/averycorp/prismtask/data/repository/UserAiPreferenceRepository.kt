package com.averycorp.prismtask.data.repository

import com.averycorp.prismtask.data.local.dao.UserAiPreferenceDao
import com.averycorp.prismtask.data.local.entity.UserAiPreferenceEntity
import com.averycorp.prismtask.data.remote.api.PrismTaskApi
import com.averycorp.prismtask.data.remote.api.UserAiPreferenceCreateRequest
import com.averycorp.prismtask.data.remote.api.UserAiPreferenceDto
import com.averycorp.prismtask.data.remote.api.UserAiPreferenceUpdateRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AI-memory repository — single source of truth for the user's stored
 * AI preferences on the client side.
 *
 * The backend's `/api/v1/ai/chat` handler is authoritative: when the AI
 * emits `remember_preference` / `forget_preference` tool calls, Postgres
 * is updated and the full list is returned on every chat response. The
 * client mirrors it locally via [mirrorFromChat] so the Settings UI
 * renders the same data offline.
 *
 * The CRUD methods ([create], [update], [delete]) keep the two sides in
 * sync when the user edits from Settings. Server is always the source
 * of truth — local writes happen only after the server confirms.
 */
@Singleton
class UserAiPreferenceRepository
@Inject
constructor(
    private val api: PrismTaskApi,
    private val dao: UserAiPreferenceDao
) {
    /** Server-enforced cap; mirrored client-side for UI ("X of 15"). */
    val maxPreferences: Int = 15

    val preferences: Flow<List<UserAiPreferenceEntity>> = dao.observeAll()

    /**
     * Render-side helper: emit a Flow of domain models. Kept here so the
     * ViewModel doesn't have to know about the entity type.
     */
    val preferencesAsDomain: Flow<List<UserAiPreference>> =
        preferences.map { rows -> rows.map { it.toDomain() } }

    /**
     * Replace the local mirror with the full server snapshot returned on
     * every `/chat` response. Idempotent — safe to call on every turn.
     */
    suspend fun mirrorFromChat(dtos: List<UserAiPreferenceDto>) {
        dao.replaceAll(dtos.map { it.toEntity() })
    }

    /**
     * Force a server-roundtrip refresh. Used by the Settings screen on
     * open so the user sees fresh state without sending a chat turn.
     */
    suspend fun refresh() {
        val response = api.listAiMemory()
        dao.replaceAll(response.preferences.map { it.toEntity() })
    }

    /** Manually add a preference from the Settings UI. */
    suspend fun create(text: String): UserAiPreference {
        val trimmed = text.trim()
        require(trimmed.isNotEmpty()) { "Preference text is empty" }
        val dto = api.createAiMemory(
            UserAiPreferenceCreateRequest(preferenceText = trimmed)
        )
        dao.upsert(dto.toEntity())
        return dto.toEntity().toDomain()
    }

    /** Edit the text of a stored preference. */
    suspend fun update(id: String, text: String): UserAiPreference {
        val trimmed = text.trim()
        require(trimmed.isNotEmpty()) { "Preference text is empty" }
        val dto = api.updateAiMemory(
            preferenceId = id,
            request = UserAiPreferenceUpdateRequest(preferenceText = trimmed)
        )
        dao.upsert(dto.toEntity())
        return dto.toEntity().toDomain()
    }

    /** Remove a preference. Idempotent server-side (204 even if missing). */
    suspend fun delete(id: String) {
        api.deleteAiMemory(id)
        dao.deleteById(id)
    }

    private fun UserAiPreferenceDto.toEntity(): UserAiPreferenceEntity {
        val created = runCatching { Instant.parse(createdAt).toEpochMilli() }
            .getOrDefault(System.currentTimeMillis())
        val updated = runCatching { Instant.parse(updatedAt).toEpochMilli() }
            .getOrDefault(created)
        return UserAiPreferenceEntity(
            id = id,
            preferenceText = preferenceText,
            sourceMessageId = sourceMessageId,
            createdAt = created,
            updatedAt = updated
        )
    }

    private fun UserAiPreferenceEntity.toDomain(): UserAiPreference = UserAiPreference(
        id = id,
        text = preferenceText,
        sourceMessageId = sourceMessageId,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}

/**
 * Plain-data domain model used by the AI Memory Settings UI.
 */
data class UserAiPreference(
    val id: String,
    val text: String,
    val sourceMessageId: String?,
    val createdAt: Long,
    val updatedAt: Long
)
