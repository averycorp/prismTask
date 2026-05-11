package com.averycorp.prismtask.data.calendar

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.averycorp.prismtask.workers.CalendarPushWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default [CalendarPushDispatcher] that forwards push/delete requests to a
 * WorkManager-scheduled [CalendarPushWorker]. The worker calls the backend
 * `/api/v1/calendar/sync/push` endpoint; retry + backoff is owned by
 * WorkManager so repositories can enqueue fire-and-forget without caring
 * about network failures.
 */
@Singleton
class DefaultCalendarPushDispatcher
@Inject
constructor(@ApplicationContext private val context: Context) : CalendarPushDispatcher {
    override fun enqueuePushTask(taskId: Long) {
        enqueue(taskId, CalendarPushWorker.OP_UPSERT)
    }

    override fun enqueueDeleteTaskEvent(taskId: Long) {
        enqueue(taskId, CalendarPushWorker.OP_DELETE)
    }

    private fun enqueue(taskId: Long, op: String) {
        val data = Data.Builder()
            .putLong(CalendarPushWorker.KEY_TASK_ID, taskId)
            .putString(CalendarPushWorker.KEY_OP, op)
            .build()
        val request = OneTimeWorkRequestBuilder<CalendarPushWorker>()
            .setInputData(data)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG_PUSH)
            .build()
        try {
            WorkManager.getInstance(context).enqueue(request)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to enqueue calendar push for task=$taskId op=$op", e)
        }
    }

    companion object {
        const val TAG_PUSH = "calendar-push"
        private const val LOG_TAG = "CalendarPushDispatch"
    }
}
