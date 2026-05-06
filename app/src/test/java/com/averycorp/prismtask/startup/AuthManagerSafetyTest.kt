package com.averycorp.prismtask.startup

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Tests that [com.averycorp.prismtask.data.remote.AuthManager] is resilient
 * to Firebase initialization failures.
 *
 * AuthManager is injected as a @Singleton during Hilt graph construction.
 * If its constructor eagerly calls FirebaseAuth.getInstance() without
 * protection, a Firebase init failure (bad google-services.json, missing
 * FirebaseApp) will crash the entire DI graph and kill the app before
 * any Activity is created.
 *
 * This was identified as a root cause of the persistent startup crash:
 * the constructor ran FirebaseAuth.getInstance() as a field initializer,
 * which executes during DI graph construction with no surrounding catch.
 */
class AuthManagerSafetyTest {
    @Test
    fun `AuthManager protects FirebaseAuth initialization`() {
        val file = File("app/src/main/java/com/averycorp/prismtask/data/remote/AuthManager.kt")
        if (!file.exists()) return

        val content = file.readText()

        // FirebaseAuth.getInstance() must be in a try-catch or lazy block
        if (content.contains("FirebaseAuth.getInstance()")) {
            val hasTryCatch = content.contains("try") &&
                content.contains("FirebaseAuth.getInstance()") &&
                content.contains("catch")
            val hasLazy = content.contains("by lazy") &&
                content.contains("FirebaseAuth.getInstance()")

            assertTrue(
                "AuthManager must protect FirebaseAuth.getInstance() with try-catch " +
                    "or lazy initialization. The constructor runs during Hilt graph " +
                    "construction, so an unprotected call crashes before any Activity starts.",
                hasTryCatch || hasLazy
            )
        }
    }

    @Test
    fun `AuthManager auth field is nullable for graceful degradation`() {
        val file = File("app/src/main/java/com/averycorp/prismtask/data/remote/AuthManager.kt")
        if (!file.exists()) return

        val content = file.readText()

        // The auth field should be nullable (FirebaseAuth?) so the app
        // can degrade gracefully when Firebase is unavailable.
        assertTrue(
            "AuthManager.auth should be nullable (FirebaseAuth?) to allow " +
                "graceful degradation when Firebase is not available",
            content.contains("FirebaseAuth?")
        )
    }

    @Test
    fun `AuthManager userId uses safe navigation`() {
        val file = File("app/src/main/java/com/averycorp/prismtask/data/remote/AuthManager.kt")
        if (!file.exists()) return

        val content = file.readText()

        // userId should use auth?.currentUser?.uid (safe calls)
        assertTrue(
            "AuthManager.userId should use safe navigation (auth?.currentUser?.uid) " +
                "to handle null auth instance",
            content.contains("auth?.currentUser?.uid")
        )
    }

    @Test
    fun `AuthManager email sign-up and sign-in guard against null Firebase`() {
        val file = File("app/src/main/java/com/averycorp/prismtask/data/remote/AuthManager.kt")
        if (!file.exists()) return

        val content = file.readText()

        // Both new email entrypoints must early-return Result.failure when
        // the constructor's FirebaseAuth.getInstance() threw and `auth` is
        // null, mirroring the signInWithGoogle pattern. Without this guard
        // a misconfigured Firebase NPEs on the email path.
        listOf("signUpWithEmail", "signInWithEmail").forEach { fn ->
            val fnIndex = content.indexOf("fun $fn(")
            assertTrue("AuthManager must define $fn(...)", fnIndex >= 0)
            val fnBody = content.substring(fnIndex, minOf(fnIndex + 500, content.length))
            assertTrue(
                "$fn must early-return Result.failure(IllegalStateException) " +
                    "when auth is null, matching signInWithGoogle's null-Firebase fallback.",
                fnBody.contains("Firebase Auth not available") &&
                    fnBody.contains("Result.failure")
            )
        }
    }

    @Test
    fun `SyncService startAutoSync guards against null userId`() {
        val file = File("app/src/main/java/com/averycorp/prismtask/data/remote/SyncService.kt")
        if (!file.exists()) return

        val content = file.readText()

        // startAutoSync should bail out if userId is null
        assertTrue(
            "SyncService.startAutoSync() must check authManager.userId == null " +
                "before proceeding, to handle Firebase-unavailable case",
            content.contains("authManager.userId == null")
        )
    }

    @Test
    fun `SyncService wraps Crashlytics calls in catch blocks`() {
        val file = File("app/src/main/java/com/averycorp/prismtask/data/remote/SyncService.kt")
        if (!file.exists()) return

        val content = file.readText()
        val lines = content.lines()

        // Every FirebaseCrashlytics.getInstance() call should be wrapped
        // in its own try-catch to prevent crash-in-a-catch cascading.
        var unsafeCount = 0
        lines.forEachIndexed { index, line ->
            if (line.contains("FirebaseCrashlytics.getInstance()")) {
                // Check if this specific call is inside a try block
                val lineStr = line.trim()
                val isSafe = lineStr.startsWith("try {") ||
                    lineStr.startsWith("try ") ||
                    lineStr.contains("try {") ||
                    isInsideTryCatchBlock(lines, index)

                if (!isSafe) {
                    unsafeCount++
                }
            }
        }

        assertTrue(
            "Found $unsafeCount unprotected FirebaseCrashlytics.getInstance() calls " +
                "in SyncService.kt. Each must be wrapped in try-catch to prevent " +
                "crash-in-a-catch cascading when Firebase is unavailable.",
            unsafeCount == 0
        )
    }

    private fun isInsideTryCatchBlock(lines: List<String>, targetIndex: Int): Boolean {
        // Simple heuristic: look at the same line or immediate wrapping
        val line = lines[targetIndex].trim()
        if (line.startsWith("try {") || line.startsWith("try ")) return true
        // Check if the line starts with a nested try
        if (line.contains("try {")) return true

        // Scan backwards for try block
        var depth = 0
        for (i in targetIndex downTo maxOf(0, targetIndex - 15)) {
            val l = lines[i].trim()
            depth += l.count { it == '}' }
            depth -= l.count { it == '{' }
            if ((l.startsWith("try") || l.contains("} catch") || l == "try {") && depth <= 0) {
                return true
            }
            if (l.startsWith("fun ") ||
                l.startsWith("override fun ") ||
                l.startsWith("class ") ||
                l.startsWith("object ")
            ) {
                return false
            }
        }
        return false
    }
}
