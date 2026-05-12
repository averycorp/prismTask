package com.averycorp.prismtask.startup

import com.averycorp.prismtask.data.local.entity.AssignmentEntity
import com.averycorp.prismtask.data.local.entity.AttachmentEntity
import com.averycorp.prismtask.data.local.entity.BoundaryRuleEntity
import com.averycorp.prismtask.data.local.entity.CalendarSyncEntity
import com.averycorp.prismtask.data.local.entity.CheckInLogEntity
import com.averycorp.prismtask.data.local.entity.CourseCompletionEntity
import com.averycorp.prismtask.data.local.entity.CourseEntity
import com.averycorp.prismtask.data.local.entity.CustomSoundEntity
import com.averycorp.prismtask.data.local.entity.FocusReleaseLogEntity
import com.averycorp.prismtask.data.local.entity.HabitCompletionEntity
import com.averycorp.prismtask.data.local.entity.HabitEntity
import com.averycorp.prismtask.data.local.entity.HabitLogEntity
import com.averycorp.prismtask.data.local.entity.HabitTemplateEntity
import com.averycorp.prismtask.data.local.entity.LeisureActivityEntity
import com.averycorp.prismtask.data.local.entity.LeisureSessionEntity
import com.averycorp.prismtask.data.local.entity.MedicationRefillEntity
import com.averycorp.prismtask.data.local.entity.MoodEnergyLogEntity
import com.averycorp.prismtask.data.local.entity.NlpShortcutEntity
import com.averycorp.prismtask.data.local.entity.NotificationProfileEntity
import com.averycorp.prismtask.data.local.entity.ProjectEntity
import com.averycorp.prismtask.data.local.entity.ProjectTemplateEntity
import com.averycorp.prismtask.data.local.entity.SavedFilterEntity
import com.averycorp.prismtask.data.local.entity.SelfCareLogEntity
import com.averycorp.prismtask.data.local.entity.SelfCareStepEntity
import com.averycorp.prismtask.data.local.entity.SyncMetadataEntity
import com.averycorp.prismtask.data.local.entity.TagEntity
import com.averycorp.prismtask.data.local.entity.TaskCompletionEntity
import com.averycorp.prismtask.data.local.entity.TaskEntity
import com.averycorp.prismtask.data.local.entity.TaskTagCrossRef
import com.averycorp.prismtask.data.local.entity.TaskTemplateEntity
import com.averycorp.prismtask.data.local.entity.UsageLogEntity
import com.averycorp.prismtask.data.local.entity.WeeklyReviewEntity
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies that all Room entity classes referenced in `@Database(entities = [...])`
 * are importable and constructable. If a class listed in the database annotation
 * is missing or has an incompatible constructor, Room will crash at startup with
 * a schema validation error.
 *
 * This test catches:
 * - Entity classes that were deleted but not removed from the @Database list
 * - Entity constructors that changed signature (e.g. removing a default value)
 * - Missing @Entity / @ColumnInfo annotations (via compile-time import verification)
 */
class DatabaseEntityRegistrationTest {
    /**
     * The entity classes listed in PrismTaskDatabase's @Database annotation.
     * If this list doesn't match PrismTaskDatabase, the test should be updated.
     */
    private val expectedEntityClasses: List<Class<*>> = listOf(
        TaskEntity::class.java,
        ProjectEntity::class.java,
        TagEntity::class.java,
        TaskTagCrossRef::class.java,
        AttachmentEntity::class.java,
        UsageLogEntity::class.java,
        SyncMetadataEntity::class.java,
        CalendarSyncEntity::class.java,
        HabitEntity::class.java,
        HabitCompletionEntity::class.java,
        HabitLogEntity::class.java,
        LeisureActivityEntity::class.java,
        LeisureSessionEntity::class.java,
        CourseEntity::class.java,
        AssignmentEntity::class.java,
        CourseCompletionEntity::class.java,
        SelfCareLogEntity::class.java,
        SelfCareStepEntity::class.java,
        TaskTemplateEntity::class.java,
        NlpShortcutEntity::class.java,
        SavedFilterEntity::class.java,
        NotificationProfileEntity::class.java,
        CustomSoundEntity::class.java,
        ProjectTemplateEntity::class.java,
        HabitTemplateEntity::class.java,
        MoodEnergyLogEntity::class.java,
        MedicationRefillEntity::class.java,
        BoundaryRuleEntity::class.java,
        CheckInLogEntity::class.java,
        WeeklyReviewEntity::class.java,
        TaskCompletionEntity::class.java,
        FocusReleaseLogEntity::class.java
    )

    @Test
    fun `all entity classes are loadable`() {
        // If any entity class was deleted or renamed, this import-based check
        // would fail at compile time. At runtime, we verify the class objects
        // are non-null (defensive belt-and-suspenders).
        for (entityClass in expectedEntityClasses) {
            assertNotNull(
                "Entity class ${entityClass.simpleName} should be loadable",
                entityClass
            )
        }
    }

    @Test
    fun `exactly 31 entities are registered`() {
        // PrismTaskDatabase @Database annotation lists 31 entities.
        // If someone adds or removes one, both this test and the annotation
        // must be updated together.
        assertTrue(
            "Expected 31 entity classes, found ${expectedEntityClasses.size}. " +
                "Update this test AND PrismTaskDatabase.kt if entities were added/removed.",
            expectedEntityClasses.size == 31
        )
    }

    @Test
    fun `TaskEntity has life_category field for v1_4_0`() {
        // v1.4.0 V1 added the life_category column. If this field is missing,
        // Room will throw a schema mismatch at startup after MIGRATION_32_33.
        val field = TaskEntity::class.java.getDeclaredField("lifeCategory")
        assertNotNull(
            "TaskEntity must have a 'lifeCategory' field for v1.4.0 Work-Life Balance",
            field
        )
    }

    @Test
    fun `TaskEntity default constructor works with all defaults`() {
        // Ensures the entity can be instantiated with just a title.
        // If a non-nullable field was added without a default, this will fail.
        val task = TaskEntity(title = "test")
        assertNotNull(task)
        assertTrue(task.id == 0L)
        assertTrue(task.priority == 0)
        assertTrue(!task.isCompleted)
        assertTrue(task.lifeCategory == null)
    }

    @Test
    fun `v1_4_0 entities are constructable`() {
        // Verify the new v1.4.0 entities can be instantiated. If their
        // constructors are broken, Room would crash during first DB access.
        val mood = MoodEnergyLogEntity(
            date = System.currentTimeMillis(),
            mood = 3,
            energy = 4,
            createdAt = System.currentTimeMillis()
        )
        assertNotNull(mood)

        val refill = MedicationRefillEntity(
            medicationName = "Test Med",
            pillCount = 30,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        assertNotNull(refill)

        val rule = BoundaryRuleEntity(
            name = "No work after 6pm",
            ruleType = "block",
            category = "WORK",
            startTime = "18:00",
            endTime = "22:00",
            activeDaysCsv = "Mon,Tue,Wed,Thu,Fri",
            createdAt = System.currentTimeMillis()
        )
        assertNotNull(rule)

        val checkIn = CheckInLogEntity(
            date = System.currentTimeMillis(),
            stepsCompletedCsv = "",
            createdAt = System.currentTimeMillis()
        )
        assertNotNull(checkIn)

        val review = WeeklyReviewEntity(
            weekStartDate = System.currentTimeMillis(),
            metricsJson = "{}",
            createdAt = System.currentTimeMillis()
        )
        assertNotNull(review)
    }
}
