package com.averycorp.prismtask

import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.averycorp.prismtask.data.local.database.MIGRATION_72_73
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migration test for v72 → v73 — PrismTask-timeline-class scope, PR-1.
 * Adds `project_phases` + `project_risks` tables and `tasks.phase_id` /
 * `tasks.progress_percent` columns.
 */
@RunWith(AndroidJUnit4::class)
class Migration72To73Test {

    private fun openV72(): SupportSQLiteOpenHelper {
        val config = SupportSQLiteOpenHelper.Configuration.builder(
            ApplicationProvider.getApplicationContext()
        )
            .name(null)
            .callback(object : SupportSQLiteOpenHelper.Callback(72) {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL("PRAGMA foreign_keys = ON")
                    // Minimal v72 shape — only the tables the new schema
                    // references as parents. `tasks.cognitive_load` matches the
                    // v71→v72 ALTER so the sqlite_master state mirrors a real
                    // device upgrading from v72.
                    db.execSQL(
                        "CREATE TABLE `projects` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`name` TEXT NOT NULL" +
                            ")"
                    )
                    db.execSQL(
                        "CREATE TABLE `tasks` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`title` TEXT NOT NULL, " +
                            "`cognitive_load` TEXT" +
                            ")"
                    )
                    db.execSQL("INSERT INTO `projects` (id, name) VALUES (5, 'PrismTask')")
                    db.execSQL("INSERT INTO `tasks` (id, title) VALUES (1, 'Phase F kickoff')")
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
    fun migration_createsProjectPhasesAndRisksTables() {
        val helper = openV72()
        val db = helper.writableDatabase

        val pre = mutableSetOf<String>()
        db.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { c ->
            while (c.moveToNext()) pre.add(c.getString(0))
        }
        assertFalse("project_phases absent pre-migration", "project_phases" in pre)
        assertFalse("project_risks absent pre-migration", "project_risks" in pre)

        MIGRATION_72_73.migrate(db)

        val post = mutableSetOf<String>()
        db.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { c ->
            while (c.moveToNext()) post.add(c.getString(0))
        }
        assertTrue("project_phases created", "project_phases" in post)
        assertTrue("project_risks created", "project_risks" in post)

        val indexes = mutableSetOf<String>()
        db.query(
            "SELECT name FROM sqlite_master WHERE type = 'index'"
        ).use { c -> while (c.moveToNext()) indexes.add(c.getString(0)) }
        assertTrue(
            "project_phases cloud_id unique index",
            "index_project_phases_cloud_id" in indexes
        )
        assertTrue(
            "project_phases project_id index",
            "index_project_phases_project_id" in indexes
        )
        assertTrue(
            "project_risks cloud_id unique index",
            "index_project_risks_cloud_id" in indexes
        )
        assertTrue(
            "tasks phase_id index",
            "index_tasks_phase_id" in indexes
        )

        helper.close()
    }

    @Test
    fun migration_addsTaskColumnsBackfilledNull() {
        val helper = openV72()
        val db = helper.writableDatabase
        MIGRATION_72_73.migrate(db)

        // Pre-existing tasks must read NULL for the new nullable columns.
        db.query("SELECT phase_id, progress_percent FROM tasks WHERE id = 1").use { c ->
            assertTrue("row exists", c.moveToFirst())
            assertTrue("phase_id null", c.isNull(0))
            assertTrue("progress_percent null", c.isNull(1))
        }
        helper.close()
    }

    @Test
    fun migration_phaseDeletionCascadesAndOrphansTasks() {
        val helper = openV72()
        val db = helper.writableDatabase
        MIGRATION_72_73.migrate(db)
        db.execSQL("PRAGMA foreign_keys = ON")

        db.execSQL(
            "INSERT INTO `project_phases` " +
                "(id, project_id, title, order_index, created_at, updated_at) " +
                "VALUES (7, 5, 'Phase F', 0, 1, 1)"
        )
        db.execSQL("UPDATE `tasks` SET phase_id = 7 WHERE id = 1")

        // Project deletion CASCADEs to phases.
        db.execSQL("DELETE FROM `projects` WHERE id = 5")
        var phaseCount = -1
        db.query("SELECT COUNT(*) FROM project_phases").use { c ->
            c.moveToFirst()
            phaseCount = c.getInt(0)
        }
        assertEquals("phase removed via cascade", 0, phaseCount)

        // Task survives but phase_id was nulled out (SET NULL).
        var phaseFkAfterDelete: Int? = -1
        db.query("SELECT phase_id FROM tasks WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            phaseFkAfterDelete = if (c.isNull(0)) null else c.getInt(0)
        }
        assertNull("task phase_id reset to NULL", phaseFkAfterDelete)

        helper.close()
    }
}
