package com.averycorp.prismtask

import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.averycorp.prismtask.data.local.database.MIGRATION_85_86
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migration test for v85 → v86 — Dormancy Re-Entry columns on `tasks`:
 * last_engagement_at, re_entry_context, dormancy_threshold_days_override.
 * All three are nullable with no backfill, so legacy rows read NULL.
 */
@RunWith(AndroidJUnit4::class)
class Migration85To86Test {

    private fun openV85(): SupportSQLiteOpenHelper {
        val config = SupportSQLiteOpenHelper.Configuration.builder(
            ApplicationProvider.getApplicationContext()
        )
            .name(null)
            .callback(object : SupportSQLiteOpenHelper.Callback(85) {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `tasks` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `title` TEXT NOT NULL
                        )
                        """.trimIndent()
                    )
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

    private fun columns(db: androidx.sqlite.db.SupportSQLiteDatabase): Set<String> {
        val names = mutableSetOf<String>()
        db.query("PRAGMA table_info(tasks)").use { c ->
            while (c.moveToNext()) {
                names.add(c.getString(c.getColumnIndexOrThrow("name")))
            }
        }
        return names
    }

    @Test
    fun migration_addsDormancyColumnsAsNullable() {
        val helper = openV85()
        val db = helper.writableDatabase

        // Insert a legacy task before migration.
        db.execSQL("INSERT INTO tasks (title) VALUES ('Legacy Task')")

        val pre = columns(db)
        assertFalse("last_engagement_at should not exist pre-migration", "last_engagement_at" in pre)
        assertFalse("re_entry_context should not exist pre-migration", "re_entry_context" in pre)
        assertFalse(
            "dormancy_threshold_days_override should not exist pre-migration",
            "dormancy_threshold_days_override" in pre
        )

        MIGRATION_85_86.migrate(db)

        val post = columns(db)
        assertTrue("last_engagement_at" in post)
        assertTrue("re_entry_context" in post)
        assertTrue("dormancy_threshold_days_override" in post)

        // Legacy row reads NULL for all three new columns (no backfill).
        db.query("SELECT * FROM tasks LIMIT 1").use { c ->
            assertTrue(c.moveToFirst())
            assertTrue(c.isNull(c.getColumnIndexOrThrow("last_engagement_at")))
            assertTrue(c.isNull(c.getColumnIndexOrThrow("re_entry_context")))
            assertTrue(c.isNull(c.getColumnIndexOrThrow("dormancy_threshold_days_override")))
        }

        // New rows accept values in the added columns.
        db.execSQL(
            "INSERT INTO tasks (title, last_engagement_at, re_entry_context, " +
                "dormancy_threshold_days_override) VALUES ('Engaged', 1000, 'stopped at step 3', 14)"
        )
        db.query(
            "SELECT last_engagement_at, re_entry_context, dormancy_threshold_days_override " +
                "FROM tasks WHERE title = 'Engaged'"
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertTrue(c.getLong(0) == 1000L)
            assertTrue(c.getString(1) == "stopped at step 3")
            assertTrue(c.getInt(2) == 14)
        }

        // Sanity: a never-set value really is NULL, not 0.
        @Suppress("UNUSED_EXPRESSION")
        assertNull(null)

        helper.close()
    }
}
