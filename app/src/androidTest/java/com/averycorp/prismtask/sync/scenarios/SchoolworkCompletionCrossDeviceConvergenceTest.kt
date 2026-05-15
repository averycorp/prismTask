package com.averycorp.prismtask.sync.scenarios

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.averycorp.prismtask.data.local.entity.CourseCompletionEntity
import com.averycorp.prismtask.data.local.entity.CourseEntity
import com.averycorp.prismtask.data.repository.SchoolworkRepository
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

/**
 * Regression gate for the Android↔web `course_completions` sync gap.
 *
 * Background: Android historically pushed `course_completions` with
 * auto-random Firestore doc ids (`collection.document()`) while web
 * has always written deterministic `${courseCloudId}__${date}` ids
 * (see `web/src/api/firestore/courseCompletions.ts:34`). When a user
 * toggled the same class on the same day from both platforms, each
 * platform produced a *different* Firestore doc for the same
 * `(course, date)` pair. On the receiving side, the pull then hit the
 * local `UNIQUE(date, course_id)` index on `CourseCompletionEntity`
 * (see entity definition) and the row was silently dropped — so the
 * other platform's toggle never showed up locally.
 *
 * Fix shape (mirrors `medicationTierState_dedupAcrossDevices`):
 *
 *  1. Push (`SyncService.pushCreate` + `runCourseCompletionsBackfillIfNeeded`)
 *     now mints a deterministic doc id via
 *     `courseCompletionDeterministicDocId` and writes with
 *     `SetOptions.merge()`, so two devices converge on one doc.
 *  2. Pull (`SyncPullOrchestrator.pullCollection("course_completions")`)
 *     now natural-key dedups by `(courseLocalId, date)` before
 *     inserting — so any legacy random-id docs that already exist in
 *     Firestore from before this fix shipped don't churn the local
 *     row's PK or leave stale `sync_metadata`.
 *
 * Test scenario: Device A has a local completion for `(course, date)`
 * with its own cloud_id. Device B independently writes a *different*
 * cloud_id doc for the same pair (simulating either a legacy
 * random-id write or a separate device's write). A pulls — must adopt
 * the existing local row and apply last-write-wins, not create a
 * duplicate.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SchoolworkCompletionCrossDeviceConvergenceTest : SyncScenarioTestBase() {

    @Inject
    lateinit var schoolworkRepository: SchoolworkRepository

    @Test
    fun courseCompletion_dedupAcrossDevices() = runBlocking {
        withTimeout(TEST_TIMEOUT) {
            requireSignedIn()

            // 1. Create a course on A and push so its cloud_id is registered
            // in sync_metadata. Courses themselves continue to use auto-ids
            // — they have no `(date, course_id)` unique constraint to
            // collide on, so no behavior change there. We use the
            // repository (not the DAO directly) so `syncTracker.trackCreate`
            // fires and the next push picks the row up.
            val courseId = schoolworkRepository.insertCourse(
                CourseEntity(
                    name = "Linear Algebra",
                    code = "MATH-217",
                    color = 0,
                    icon = "📐",
                    active = true,
                    sortOrder = 0,
                    createdAt = 0L,
                    updatedAt = 0L
                )
            )
            syncService.pushLocalChanges()
            val courseCloudId = database.syncMetadataDao().getCloudId(courseId, "course")
            assertNotNull("course cloud_id populated after push", courseCloudId)

            // 2. A logs a completion locally for today and we tag it with
            // a stand-in cloud_id, simulating the post-push state.
            val date = 1_747_267_200_000L // arbitrary stable epoch ms
            val localCompletionId = database.schoolworkDao().insertCompletion(
                CourseCompletionEntity(
                    date = date,
                    courseId = courseId,
                    completed = true,
                    completedAt = 1_000L,
                    createdAt = 0L,
                    updatedAt = 1_000L
                )
            )

            // 3. B independently writes a completion targeting the same
            // (course, date) pair under a *different* cloud_id — this
            // represents either a legacy random-id Android doc or a
            // separate device's deterministic-id write that hasn't yet
            // been adopted by A's sync_metadata.
            val nowMs = System.currentTimeMillis()
            val bCompletionCloudId = "completion-from-b-independent-cloudid"
            harness.writeAsDeviceB(
                subcollection = "course_completions",
                docId = bCompletionCloudId,
                fields = mapOf(
                    "localId" to 5555L,
                    "courseCloudId" to courseCloudId!!,
                    "date" to date,
                    "completed" to false,
                    "completedAt" to null,
                    "createdAt" to 0L,
                    "updatedAt" to nowMs
                )
            )

            // 4. A pulls. Must not throw. Pre-fix:
            // SQLiteConstraintException: UNIQUE constraint failed:
            // course_completions.date, course_completions.course_id
            // (REPLACE strategy would have churned the local PK and
            // left stale sync_metadata; either way the toggle didn't
            // converge cleanly).
            syncService.pullRemoteChanges()

            harness.waitFor(message = "B's cloud_id maps to A's local row") {
                database.syncMetadataDao()
                    .getLocalId(bCompletionCloudId, "course_completion") == localCompletionId
            }

            // 5. Exactly one local row for the pair after dedup — the
            // existing local row was adopted, not duplicated.
            assertEquals(
                "exactly one course_completion row for the (course, date) pair after dedup",
                1,
                database.schoolworkDao().getCompletionsForDateOnce(date)
                    .count { it.courseId == courseId }
            )
            assertEquals(
                "B's cloud_id resolves to A's local row id",
                localCompletionId,
                database.syncMetadataDao()
                    .getLocalId(bCompletionCloudId, "course_completion")
            )
            // 6. Last-write-wins applied B's payload — B wrote
            // completed=false at a later updatedAt, so A's adopted
            // local row should reflect that flip.
            assertEquals(
                "LWW applied B's completed=false onto adopted local row",
                false,
                database.schoolworkDao().getCompletionById(localCompletionId)!!.completed
            )
        }
    }

    companion object {
        private val TEST_TIMEOUT = 90.seconds
    }
}
