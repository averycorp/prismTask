package com.averycorp.prismtask.data.remote.api

import com.averycorp.prismtask.data.remote.sync.SyncPullResponse
import com.averycorp.prismtask.data.remote.sync.SyncPushRequest
import com.averycorp.prismtask.data.remote.sync.SyncPushResponse
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit interface for the PrismTask FastAPI backend.
 *
 * Base URL is provided by Hilt via [ApiClient] from `BuildConfig.API_BASE_URL`.
 */
interface PrismTaskApi {
    @GET("api/v1/auth/me")
    suspend fun getMe(): UserInfoResponse

    @PATCH("api/v1/auth/me/tier")
    suspend fun updateTier(
        @Body request: UpdateTierRequest
    ): UserInfoResponse

    @POST("api/v1/auth/register")
    suspend fun register(
        @Body request: RegisterRequest
    ): TokenResponse

    @POST("api/v1/auth/login")
    suspend fun login(
        @Body request: LoginRequest
    ): TokenResponse

    @POST("api/v1/auth/firebase")
    suspend fun firebaseLogin(
        @Body request: FirebaseTokenRequest
    ): TokenResponse

    @POST("api/v1/auth/refresh")
    suspend fun refresh(
        @Body request: RefreshRequest
    ): TokenResponse

    @GET("api/v1/auth/me/deletion")
    suspend fun getDeletionStatus(): DeletionStatusResponse

    @POST("api/v1/auth/me/deletion")
    suspend fun requestDeletion(
        @Body request: DeletionRequest
    ): DeletionStatusResponse

    @DELETE("api/v1/auth/me/deletion")
    suspend fun cancelDeletion(): DeletionStatusResponse

    @POST("api/v1/auth/me/purge")
    suspend fun purgeAccount(): retrofit2.Response<Unit>

    @POST("api/v1/beta/redeem")
    suspend fun redeemBetaCode(
        @Body request: BetaRedeemRequest
    ): BetaRedeemResponse

    @POST("api/v1/tasks/parse")
    suspend fun parseTask(
        @Body request: ParseRequest
    ): ParsedTaskResponse

    @POST("api/v1/ai/tasks/extract-from-text")
    suspend fun extractTasksFromText(
        @Body request: ExtractFromTextRequest
    ): ExtractFromTextResponse

    @GET("api/v1/app/version")
    suspend fun getVersion(): VersionResponse

    @POST("api/v1/sync/push")
    suspend fun syncPush(
        @Body request: SyncPushRequest
    ): SyncPushResponse

    @GET("api/v1/sync/pull")
    suspend fun syncPull(
        @Query("since") since: String? = null
    ): SyncPullResponse

    @POST("api/v1/ai/eisenhower")
    suspend fun categorizeEisenhower(
        @Body request: EisenhowerRequest
    ): EisenhowerResponse

    @POST("api/v1/ai/eisenhower/classify_text")
    suspend fun classifyEisenhowerText(
        @Body request: EisenhowerClassifyTextRequest
    ): EisenhowerClassifyTextResponse

    @POST("api/v1/ai/life-category/classify_text")
    suspend fun classifyLifeCategoryText(
        @Body request: LifeCategoryClassifyTextRequest
    ): LifeCategoryClassifyTextResponse

    @POST("api/v1/ai/pomodoro-plan")
    suspend fun planPomodoro(
        @Body request: PomodoroRequest
    ): PomodoroResponse

    @POST("api/v1/ai/pomodoro-coaching")
    suspend fun getPomodoroCoaching(
        @Body request: PomodoroCoachingRequest
    ): PomodoroCoachingResponse

    @POST("api/v1/ai/daily-briefing")
    suspend fun getDailyBriefing(
        @Body request: DailyBriefingRequest
    ): DailyBriefingResponse

    @POST("api/v1/ai/weekly-plan")
    suspend fun getWeeklyPlan(
        @Body request: WeeklyPlanRequest
    ): WeeklyPlanResponse

    @POST("api/v1/ai/weekly-review")
    suspend fun getWeeklyReview(
        @Body request: WeeklyReviewRequest
    ): WeeklyReviewResponse

    @POST("api/v1/ai/time-block")
    suspend fun getTimeBlock(
        @Body request: TimeBlockRequest
    ): TimeBlockResponse

    @POST("api/v1/ai/batch-parse")
    suspend fun parseBatchCommand(
        @Body request: BatchParseRequest
    ): BatchParseResponse

    @POST("api/v1/ai/chat")
    suspend fun aiChat(
        @Body request: ChatRequest
    ): ChatResponse

    @POST("api/v1/tasks/parse-import")
    suspend fun parseImport(
        @Body request: ParseImportRequest
    ): ParseImportResponse

    @POST("api/v1/tasks/parse-checklist")
    suspend fun parseChecklist(
        @Body request: ParseChecklistRequest
    ): ParseChecklistResponse

    @POST("api/v1/ai/evening-summary")
    suspend fun getEveningSummary(
        @Body request: EveningSummaryRequest
    ): EveningSummaryResponse

    @POST("api/v1/ai/reengagement-nudge")
    suspend fun getReengagementNudge(
        @Body request: ReengagementRequest
    ): ReengagementResponse

    @POST("api/v1/ai/coaching")
    suspend fun getCoaching(
        @Body request: CoachingRequest
    ): CoachingResponse

    /**
     * `ai.complete` automation action. AI-gated via [AiFeatureGateInterceptor]
     * (the `/ai/` prefix is already covered — no list update needed). Returns
     * 451 when the master AI toggle is off, 503 when Anthropic is unavailable.
     */
    @POST("api/v1/ai/automation/complete")
    suspend fun automationComplete(
        @Body request: AutomationCompleteRequest
    ): AutomationCompleteResponse

    /**
     * `ai.summarize` automation action. Same 451/503 contract as
     * [automationComplete].
     */
    @POST("api/v1/ai/automation/summarize")
    suspend fun automationSummarize(
        @Body request: AutomationSummarizeRequest
    ): AutomationSummarizeResponse

    @GET("api/v1/export/json")
    suspend fun exportJson(): ResponseBody

    @Multipart
    @POST("api/v1/import/json")
    suspend fun importJson(
        @Part file: MultipartBody.Part,
        @Query("mode") mode: String = "merge"
    ): ImportResponse

    @Multipart
    @POST("api/v1/syllabus/parse")
    suspend fun parseSyllabus(
        @Part file: MultipartBody.Part
    ): SyllabusParseResponse

    @POST("api/v1/syllabus/confirm")
    suspend fun confirmSyllabus(
        @Body request: SyllabusConfirmRequest
    ): SyllabusConfirmResponse

    @POST("api/v1/feedback/report")
    suspend fun submitBugReport(
        @Body body: Map<String, @JvmSuppressWildcards Any?>
    ): BugReportMirrorResponse

    @GET("api/v1/feedback/bug-reports")
    suspend fun listBugReports(
        @Query("status_filter") statusFilter: String? = null,
        @Query("severity") severity: String? = null,
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 20
    ): List<AdminBugReportResponse>

    @PATCH("api/v1/feedback/bug-reports/{reportId}")
    suspend fun updateBugReportStatus(
        @Path("reportId") reportId: String,
        @Body body: BugReportStatusUpdateRequest
    ): AdminBugReportResponse

    /**
     * Anthropic-backed habit-correlation analysis. Server enforces a
     * 1-call/day/user rate limit (returns 429), the
     * require_ai_features_enabled gate (returns 451 when the master AI
     * toggle is disabled), and Pro-tier billing. Surface those distinct
     * states to the user — `Result.failure` with a clearly typed
     * exception is fine since this isn't a blocking flow.
     */
    @GET("api/v1/analytics/habit-correlations")
    suspend fun getHabitCorrelations(): HabitCorrelationsResponse
}
