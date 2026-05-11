package com.averycorp.prismtask.data.repository

import com.averycorp.prismtask.data.remote.api.HabitCorrelationsResponse
import com.averycorp.prismtask.data.remote.api.PrismTaskApi
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Outcome wrapper for the habit-correlations endpoint. Distinguishes the
 * three "expected" failure modes from a generic transport failure so the
 * UI can render different copy:
 *
 *  - [Success]            — the AI returned interpretations
 *  - [AiFeaturesDisabled] — caller's master AI toggle is off (HTTP 451)
 *  - [RateLimited]        — already called this within the last 24h (HTTP 429)
 *  - [NotPro]             — Pro gate rejected the request (HTTP 402 / 403)
 *  - [BackendUnavailable] — anything else (network / 5xx)
 */
sealed class HabitCorrelationsOutcome {
    data class Success(val response: HabitCorrelationsResponse) : HabitCorrelationsOutcome()
    object AiFeaturesDisabled : HabitCorrelationsOutcome()
    object RateLimited : HabitCorrelationsOutcome()
    object NotPro : HabitCorrelationsOutcome()
    data class BackendUnavailable(val cause: Throwable) : HabitCorrelationsOutcome()
}

@Singleton
class HabitCorrelationsRepository
@Inject
constructor(private val api: PrismTaskApi) {
    suspend fun fetch(): HabitCorrelationsOutcome = try {
        HabitCorrelationsOutcome.Success(api.getHabitCorrelations())
    } catch (e: HttpException) {
        when (e.code()) {
            451 -> HabitCorrelationsOutcome.AiFeaturesDisabled
            429 -> HabitCorrelationsOutcome.RateLimited
            402, 403 -> HabitCorrelationsOutcome.NotPro
            else -> HabitCorrelationsOutcome.BackendUnavailable(e)
        }
    } catch (e: Throwable) {
        HabitCorrelationsOutcome.BackendUnavailable(e)
    }
}
