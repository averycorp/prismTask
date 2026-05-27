package com.averycorp.prismtask.data.remote.sync

import android.util.Log

/**
 * Validates and safely extracts fields from Firestore document maps.
 * Prevents null pointer exceptions when Firestore documents have missing
 * or unexpected field types.
 */
object FirestoreValidator {
    private const val TAG = "FirestoreValidator"

    fun getString(data: Map<String, Any?>, key: String, default: String = ""): String {
        return when (val v = data[key]) {
            is String -> v
            null -> default
            else -> {
                Log.w(TAG, "Field '$key' expected String, got ${v::class.simpleName}")
                v.toString()
            }
        }
    }

    fun getStringOrNull(data: Map<String, Any?>, key: String): String? {
        return when (val v = data[key]) {
            is String -> v
            null -> null
            else -> {
                Log.w(TAG, "Field '$key' expected String?, got ${v::class.simpleName}")
                v.toString()
            }
        }
    }

    fun getLong(data: Map<String, Any?>, key: String, default: Long = 0L): Long {
        return when (val v = data[key]) {
            is Long -> v
            is Int -> v.toLong()
            is Double -> v.toLong()
            is Number -> v.toLong()
            null -> default
            else -> {
                Log.w(TAG, "Field '$key' expected Long, got ${v::class.simpleName}")
                default
            }
        }
    }

    fun getBoolean(data: Map<String, Any?>, key: String, default: Boolean = false): Boolean {
        return when (val v = data[key]) {
            is Boolean -> v
            null -> default
            else -> {
                Log.w(TAG, "Field '$key' expected Boolean, got ${v::class.simpleName}")
                default
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun getStringList(data: Map<String, Any?>, key: String): List<String> {
        return when (val v = data[key]) {
            is List<*> -> v.filterIsInstance<String>()
            null -> emptyList()
            else -> {
                Log.w(TAG, "Field '$key' expected List<String>, got ${v::class.simpleName}")
                emptyList()
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun getMap(data: Map<String, Any?>, key: String): Map<String, Any?> {
        return when (val v = data[key]) {
            is Map<*, *> -> v as Map<String, Any?>
            null -> emptyMap()
            else -> {
                Log.w(TAG, "Field '$key' expected Map, got ${v::class.simpleName}")
                emptyMap()
            }
        }
    }

    fun validateRequiredFields(data: Map<String, Any?>, requiredKeys: List<String>): List<String> {
        return requiredKeys.filter { key -> data[key] == null || data[key] == "" }
    }
}
