package com.averycorp.prismtask.data.repository

import com.averycorp.prismtask.data.remote.api.AdminBugReportResponse
import com.averycorp.prismtask.data.remote.api.BugReportStatusUpdateRequest
import com.averycorp.prismtask.data.remote.api.PrismTaskApi
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AdminBugReportRepository
@Inject
constructor(private val api: PrismTaskApi) {
    suspend fun listReports(
        statusFilter: String? = null,
        severity: String? = null,
        page: Int = 1,
        limit: Int = 50
    ): Result<List<AdminBugReportResponse>> = runCatching {
        api.listBugReports(
            statusFilter = statusFilter,
            severity = severity,
            page = page,
            limit = limit
        )
    }

    suspend fun updateStatus(
        reportId: String,
        status: String,
        adminNotes: String? = null
    ): Result<AdminBugReportResponse> = runCatching {
        api.updateBugReportStatus(
            reportId = reportId,
            body = BugReportStatusUpdateRequest(status = status, adminNotes = adminNotes)
        )
    }
}
