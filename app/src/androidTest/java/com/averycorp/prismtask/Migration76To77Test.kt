package com.averycorp.prismtask

import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.averycorp.prismtask.data.local.database.MIGRATION_76_77
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migration test for v76 → v77 — D11 E.3 chat conversation persistence.
 * Adds `chat_messages` table with two indexes (Path A, forward-only).
 */
@RunWith(AndroidJUnit4::class)
class Migration76To77Test {

    private fun openV76(): SupportSQLiteOpenHelper {
        val config = SupportSQLiteOpenHelper.Configuration.builder(
            ApplicationProvider.getApplicationContext()
        )
            .name(null)
            .callback(object : SupportSQLiteOpenHelper.Callback(76) {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    // Empty baseline — migration is purely additive.
                }

                override fun onUpgrade(
                    db: androidx.sqlite.db.SupportSQLiteDatabase,
                    oldVersion: Int,
                    newVersion: Int
                ) = Unit
            })
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(config)
    }

    @Test
    fun migration_createsChatMessagesTable() {
        val helper = openV76()
        val db = helper.writableDatabase

        val pre = mutableSetOf<String>()
        db.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { c ->
            while (c.moveToNext()) pre.add(c.getString(0))
        }
        assertFalse("chat_messages absent pre-migration", "chat_messages" in pre)

        MIGRATION_76_77.migrate(db)

        val post = mutableSetOf<String>()
        db.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { c ->
            while (c.moveToNext()) post.add(c.getString(0))
        }
        assertTrue("chat_messages created", "chat_messages" in post)

        val indexes = mutableSetOf<String>()
        db.query(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'chat_messages'"
        ).use { c -> while (c.moveToNext()) indexes.add(c.getString(0)) }
        assertTrue(
            "(conversation_id, created_at) index present",
            "index_chat_messages_conversation_id_created_at" in indexes
        )
        assertTrue(
            "created_at index present",
            "index_chat_messages_created_at" in indexes
        )

        helper.close()
    }

    @Test
    fun migration_chatMessagesAcceptsAllColumnShapes() {
        val helper = openV76()
        val db = helper.writableDatabase
        MIGRATION_76_77.migrate(db)

        // Insert a fully-populated assistant row with JSON columns + tokens.
        db.execSQL(
            "INSERT INTO chat_messages " +
                "(id, conversation_id, role, content, actions_json, " +
                "task_context_json, tokens_input, tokens_output, created_at) " +
                "VALUES ('m1', 'chat_2026-05-08_x', 'assistant', 'hello', " +
                "'[{\"type\":\"start_timer\"}]', '{\"title\":\"t\"}', 4, 2, 1000)"
        )
        // Insert a minimal user row with NULL JSON + tokens.
        db.execSQL(
            "INSERT INTO chat_messages " +
                "(id, conversation_id, role, content, created_at) " +
                "VALUES ('m2', 'chat_2026-05-08_x', 'user', 'hi', 999)"
        )

        var count = -1
        db.query("SELECT COUNT(*) FROM chat_messages").use { c ->
            c.moveToFirst()
            count = c.getInt(0)
        }
        assertEquals(2, count)

        // Index-backed lookup by (conversation_id, created_at) returns
        // chronological order; user row (created_at=999) must precede
        // assistant row (created_at=1000).
        val ordered = mutableListOf<String>()
        db.query(
            "SELECT id FROM chat_messages " +
                "WHERE conversation_id = 'chat_2026-05-08_x' " +
                "ORDER BY created_at ASC"
        ).use { c -> while (c.moveToNext()) ordered.add(c.getString(0)) }
        assertEquals(listOf("m2", "m1"), ordered)

        helper.close()
    }

    @Test
    fun migration_chatMessagesRequiresPrimaryKey() {
        val helper = openV76()
        val db = helper.writableDatabase
        MIGRATION_76_77.migrate(db)

        db.execSQL(
            "INSERT INTO chat_messages (id, conversation_id, role, content, created_at) " +
                "VALUES ('dup-id', 'c', 'user', 'first', 1)"
        )
        var threw = false
        try {
            db.execSQL(
                "INSERT INTO chat_messages (id, conversation_id, role, content, created_at) " +
                    "VALUES ('dup-id', 'c', 'user', 'second', 2)"
            )
        } catch (_: Exception) {
            threw = true
        }
        assertTrue("primary key rejects duplicate id", threw)

        // Confirm only the first row survived.
        var content: String? = null
        db.query("SELECT content FROM chat_messages WHERE id = 'dup-id'").use { c ->
            if (c.moveToNext()) content = c.getString(0)
        }
        assertNotNull(content)
        assertEquals("first", content)

        helper.close()
    }
}
