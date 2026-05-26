package com.averycorp.prismtask.data.remote.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FirestoreValidatorTest {

    @Test
    fun `getString returns value for string field`() {
        val data = mapOf("title" to "Buy milk")
        assertEquals("Buy milk", FirestoreValidator.getString(data, "title"))
    }

    @Test
    fun `getString returns default for missing field`() {
        val data = emptyMap<String, Any?>()
        assertEquals("", FirestoreValidator.getString(data, "missing"))
        assertEquals("fallback", FirestoreValidator.getString(data, "missing", "fallback"))
    }

    @Test
    fun `getString converts non-string to string`() {
        val data = mapOf<String, Any?>("count" to 42)
        assertEquals("42", FirestoreValidator.getString(data, "count"))
    }

    @Test
    fun `getLong returns value for long field`() {
        val data = mapOf<String, Any?>("ts" to 1234567890L)
        assertEquals(1234567890L, FirestoreValidator.getLong(data, "ts"))
    }

    @Test
    fun `getLong converts Int to Long`() {
        val data = mapOf<String, Any?>("count" to 42)
        assertEquals(42L, FirestoreValidator.getLong(data, "count"))
    }

    @Test
    fun `getLong returns default for missing field`() {
        assertEquals(0L, FirestoreValidator.getLong(emptyMap(), "missing"))
    }

    @Test
    fun `getBoolean returns value`() {
        val data = mapOf<String, Any?>("active" to true)
        assertEquals(true, FirestoreValidator.getBoolean(data, "active"))
    }

    @Test
    fun `getBoolean returns default for missing`() {
        assertEquals(false, FirestoreValidator.getBoolean(emptyMap(), "missing"))
    }

    @Test
    fun `getStringList filters non-string entries`() {
        val data = mapOf<String, Any?>("tags" to listOf("work", "personal", 42))
        val result = FirestoreValidator.getStringList(data, "tags")
        assertEquals(listOf("work", "personal"), result)
    }

    @Test
    fun `getStringList returns empty for missing field`() {
        assertTrue(FirestoreValidator.getStringList(emptyMap(), "missing").isEmpty())
    }

    @Test
    fun `validateRequiredFields returns missing keys`() {
        val data = mapOf<String, Any?>("name" to "Alice", "email" to null)
        val missing = FirestoreValidator.validateRequiredFields(data, listOf("name", "email", "phone"))
        assertEquals(listOf("email", "phone"), missing)
    }

    @Test
    fun `getStringOrNull returns null for missing field`() {
        assertNull(FirestoreValidator.getStringOrNull(emptyMap(), "missing"))
    }
}
