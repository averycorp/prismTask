package com.averycorp.prismtask.domain.model

import java.util.UUID

/**
 * User-defined leisure category. Stored client-side in DataStore (no
 * server table yet — the server's `leisure_activities.category` CHECK
 * constraint still pins built-in rows to the four [LeisureCategory]
 * buckets). Sessions and activities reference a custom category by its
 * [id], which is namespaced with [ID_PREFIX] so it never collides with
 * a [LeisureCategory] enum name.
 */
data class CustomLeisureCategory(
    val id: String,
    val label: String,
    val emoji: String
) {
    companion object {
        const val ID_PREFIX: String = "custom:"

        fun isCustomId(raw: String?): Boolean =
            raw != null && raw.startsWith(ID_PREFIX)

        fun newId(): String = ID_PREFIX + UUID.randomUUID().toString().take(8)
    }
}

/**
 * Union of built-in [LeisureCategory] and [CustomLeisureCategory]. Acts
 * as the single addressable thing across UI / sampling / breakdown so
 * the rest of the leisure stack doesn't need to know which kind it is.
 */
sealed interface LeisureCategoryRef {
    /** Stable identifier matching the `category` column in DB rows. */
    val id: String
    val label: String
    val emoji: String

    data class BuiltIn(
        val category: LeisureCategory,
        override val label: String,
        override val emoji: String
    ) : LeisureCategoryRef {
        override val id: String get() = category.name
    }

    data class Custom(
        val custom: CustomLeisureCategory
    ) : LeisureCategoryRef {
        override val id: String get() = custom.id
        override val label: String get() = custom.label
        override val emoji: String get() = custom.emoji
    }
}
