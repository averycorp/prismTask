package com.averycorp.prismtask

import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.averycorp.prismtask.data.local.database.MIGRATION_64_65
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migration test for v64 → v65 — adds the `task_timings` table for per-task
 * time-tracking.
 *
 * Verifies that the new table is created with the expected columns + indices,
 * a row can be inserted, and CASCADE delete fires when the parent task row is
 * removed.
 */
@RunWith(AndroidJUnit4::class)
class Migration64To65Test {

    private fun openV64(): SupportSQLiteOpenHelper {
        val config = SupportSQLiteOpenHelper.Configuration.builder(
            ApplicationProvider.getApplicationContext()
        )
            .name(null)
            .callback(object : SupportSQLiteOpenHelper.Callback(64) {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    // Minimal v64 shape — `tasks` is the only parent the new
                    // FK references. Foreign keys must be enabled for CASCADE
                    // delete to fire in the test.
                    db.execSQL("PRAGMA foreign_keys = ON")
                    db.execSQL(
                        "CREATE TABLE `tasks` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`title` TEXT NOT NULL" +
                            ")"
                    )
                    db.execSQL("INSERT INTO `tasks` (id, title) VALUES (10, 'Write design doc')")
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
    fun migration_createsTaskTimingsTable() {
        val helper = openV64()
        val db = helper.writableDatabase

        // Sanity: table absent pre-migration.
        val pre = mutableSetOf<String>()
        db.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { c ->
            while (c.moveToNext()) pre.add(c.getString(0))
        }
        assertFalse("task_timings absent pre-migration", "task_timings" in pre)

        MIGRATION_64_65.migrate(db)

        // Table present post-migration.
        val post = mutableSetOf<String>()
        db.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { c ->
            while (c.moveToNext()) post.add(c.getString(0))
        }
        assertTrue("task_timings created", "task_timings" in post)

        // Indices present.
        val indexes = mutableSetOf<String>()
        db.query(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'task_timings'"
        ).use { c ->
            while (c.moveToNext()) indexes.add(c.getString(0))
        }
        assertTrue("task_id index", "index_task_timings_task_id" in indexes)
        assertTrue("started_at index", "index_task_timings_started_at" in indexes)
        assertTrue("created_at index", "index_task_timings_created_at" in indexes)
        assertTrue("cloud_id unique index", "index_task_timings_cloud_id" in indexes)

        helper.close()
    }

    @Test
    fun migration_insertAndQueryRoundTrip() {
        val helper = openV64()
        val db = helper.writableDatabase
        MIGRATION_64_65.migrate(db)

        db.execSQL(
            "INSERT INTO `task_timings` " +
                "(cloud_id, task_id, started_at, ended_at, duration_minutes, " +
                " source, notes, created_at) " +
                "VALUES (NULL, 10, 1234500000, 1234599999, 25, 'manual', 'pomodoro break done', 1234600000)"
        )

        var totalMinutes = -1
        db.query("SELECT duration_minutes FROM task_timings WHERE task_id = 10").use { c ->
            assertTrue("row inserted", c.moveToFirst())
            totalMinutes = c.getInt(0)
        }
        assertEquals(25, totalMinutes)
    }

    @Test
    fun migration_cascadeDeletesTimingsWhenTaskDeleted() {
        val helper = openV64()
        val db = helper.writableDatabase
        MIGRATION_64_65.migrate(db)
        // Re-enable FK enforcement on this connection (required after migration).
        db.execSQL("PRAGMA foreign_keys = ON")

        db.execSQL(
            "INSERT INTO `task_timings` " +
                "(task_id, duration_minutes, source, created_at) " +
                "VALUES (10, 30, 'manual', 1234600000)"
        )

        var preCount = 0
        db.query("SELECT COUNT(*) FROM task_timings").use { c ->
            c.moveToFirst()
            preCount = c.getInt(0)
        }
        assertEquals(1, preCount)

        db.execSQL("DELETE FROM tasks WHERE id = 10")

        var postCount = -1
        db.query("SELECT COUNT(*) FROM task_timings").use { c ->
            c.moveToFirst()
            postCount = c.getInt(0)
        }
        assertEquals("CASCADE removed orphaned timing", 0, postCount)
    }
}
