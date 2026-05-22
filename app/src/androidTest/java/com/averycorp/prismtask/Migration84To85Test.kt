package com.averycorp.prismtask

import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.averycorp.prismtask.data.local.database.MIGRATION_84_85
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migration test for v84 → v85 — Per-habit streak forgiveness overrides.
 * Adds streak_max_missed_days, forgiveness_enabled, forgiveness_allowed_misses,
 * and forgiveness_grace_period_days columns to `habits` table.
 */
@RunWith(AndroidJUnit4::class)
class Migration84To85Test {

    private fun openV84(): SupportSQLiteOpenHelper {
        val config = SupportSQLiteOpenHelper.Configuration.builder(
            ApplicationProvider.getApplicationContext()
        )
            .name(null)
            .callback(object : SupportSQLiteOpenHelper.Callback(84) {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `habits` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `name` TEXT NOT NULL
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

    @Test
    fun migration_addsForgivenessColumnsToHabitsTable() {
        val helper = openV84()
        val db = helper.writableDatabase

        // Verify columns do not exist pre-migration
        var hasStreakMaxMissedDays = false
        db.query("PRAGMA table_info(habits)").use { c ->
            while (c.moveToNext()) {
                val name = c.getString(c.getColumnIndexOrThrow("name"))
                if (name == "streak_max_missed_days") {
                    hasStreakMaxMissedDays = true
                }
            }
        }
        assertTrue("streak_max_missed_days should not exist pre-migration", !hasStreakMaxMissedDays)

        // Run the migration
        MIGRATION_84_85.migrate(db)

        // Insert a dummy habit to verify columns accept values and pick up defaults
        db.execSQL("INSERT INTO habits (name) VALUES ('Test Habit')")

        db.query("SELECT * FROM habits LIMIT 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(-1, c.getInt(c.getColumnIndexOrThrow("streak_max_missed_days")))
            assertEquals(-1, c.getInt(c.getColumnIndexOrThrow("forgiveness_enabled")))
            assertEquals(-1, c.getInt(c.getColumnIndexOrThrow("forgiveness_allowed_misses")))
            assertEquals(-1, c.getInt(c.getColumnIndexOrThrow("forgiveness_grace_period_days")))
        }

        helper.close()
    }
}
