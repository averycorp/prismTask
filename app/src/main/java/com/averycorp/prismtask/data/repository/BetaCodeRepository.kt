package com.averycorp.prismtask.data.repository

import com.averycorp.prismtask.data.remote.api.BetaRedeemRequest
import com.averycorp.prismtask.data.remote.api.PrismTaskApi
import com.averycorp.prismtask.data.remote.sync.BackendSyncService
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Outcome of a beta-code redemption attempt. Maps the backend's HTTP
 * shape into a sealed type the ViewModel can pattern-match on; UI
 * copy is owned by the screen, not this layer.
 */
sealed interface BetaRedeemOutcome {
    data class Granted(val proUntil: String?) : BetaRedeemOutcome

    sealed interface Failure : BetaRedeemOutcome {
        data object UnknownCode : Failure
        data object Revoked : Failure
        data object Expired : Failure
        data object AlreadyRedeemed : Failure
        data object CapReached : Failure
        data object NotSignedIn : Failure
        data class Network(val cause: Throwable) : Failure
    }
}

@Singleton
class BetaCodeRepository
@Inject
constructor(private val api: PrismTaskApi, private val backendSyncService: BackendSyncService) {
    suspend fun redeem(code: String): BetaRedeemOutcome {
        val trimmed = code.trim()
        return try {
            val response = api.redeemBetaCode(BetaRedeemRequest(code = trimmed))
            if (response.granted) {
                // Refresh the BillingManager from /auth/me so the new
                // beta-pro signal lands without waiting for the next sync.
                backendSyncService.checkAdminStatus()
                BetaRedeemOutcome.Granted(response.proUntil)
            } else {
                BetaRedeemOutcome.Failure.UnknownCode
            }
        } catch (e: HttpException) {
            when (e.code()) {
                400 -> classifyBadRequest(e)
                401, 403 -> BetaRedeemOutcome.Failure.NotSignedIn
                409 -> BetaRedeemOutcome.Failure.AlreadyRedeemed
                410 -> BetaRedeemOutcome.Failure.CapReached
                else -> BetaRedeemOutcome.Failure.Network(e)
            }
        } catch (e: Exception) {
            BetaRedeemOutcome.Failure.Network(e)
        }
    }

    private fun classifyBadRequest(e: HttpException): BetaRedeemOutcome.Failure {
        val body = runCatching { e.response()?.errorBody()?.string().orEmpty() }
            .getOrDefault("")
            .lowercase()
        return when {
            "revoked" in body -> BetaRedeemOutcome.Failure.Revoked
            "expired" in body || "not yet valid" in body -> BetaRedeemOutcome.Failure.Expired
            else -> BetaRedeemOutcome.Failure.UnknownCode
        }
    }
}
