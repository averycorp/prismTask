package com.averycorp.prismtask.testing

/**
 * Release-build stub for [EmulatorAuthHelper]. The real implementation lives
 * in the `debug` source set; this stub exists so that `main` code can
 * reference the type without breaking release compilation. It should never
 * actually execute — the UI gates all calls behind
 * `BuildConfig.DEBUG && BuildConfig.USE_FIREBASE_EMULATOR`, both of which
 * are false in release.
 */
object EmulatorAuthHelper {
    const val DEFAULT_EMAIL = "test@prismtask.local"
    const val DEFAULT_PASSWORD = "testpass"

    suspend fun signInAsTestUser(
        @Suppress("UNUSED_PARAMETER") email: String = DEFAULT_EMAIL,
        @Suppress("UNUSED_PARAMETER") password: String = DEFAULT_PASSWORD
    ): Unit = throw UnsupportedOperationException(
        "EmulatorAuthHelper is not available in release builds"
    )
}
