package com.averycorp.prismtask

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.averycorp.prismtask.data.remote.AuthManager
import com.averycorp.prismtask.data.remote.GenericPreferenceSyncService
import com.averycorp.prismtask.data.remote.sync.PreferenceSyncSpec
import com.averycorp.prismtask.data.remote.sync.PrismSyncLogger
import com.averycorp.prismtask.data.remote.sync.SyncDeviceIdProvider
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * End-to-end test exercising [GenericPreferenceSyncService] against the
 * live Firebase Emulator Suite. Closes a structural gap — prior to this
 * test the only sync-side coverage was unit-level
 * `PreferenceSyncSerializationTest`, leaving the entire
 * DataStore → Firestore → DataStore round-trip untested.
 *
 * Three scenarios cover the failure modes that would mask the user
 * report "settings such as appearance or experience level aren't being
 * synced":
 *
 *  1. [startAfterSignIn_pushesLocalStateToFirestore] — local DataStore
 *     change + initial force-push lands in `/users/{uid}/prefs/{doc}`.
 *  2. [pullListener_appliesSiblingDeviceWrite] — a write from another
 *     device (different `__pref_device_id`) flows back to the local
 *     DataStore via the snapshot listener.
 *  3. [pullListener_suppressesSelfEcho] — a write tagged with the local
 *     device id does NOT mutate the local DataStore (preventing the
 *     push→pull→push echo loop).
 *
 * Gated by `BuildConfig.USE_FIREBASE_EMULATOR` — no-op on default debug
 * builds, fires only when the integration-CI workflow points the SDK at
 * the emulator. Each test uses a unique uid (`prefs-sync-emulator-{ts}`)
 * so concurrent / repeated runs don't share state.
 */
@RunWith(AndroidJUnit4::class)
class GenericPreferenceSyncServiceEmulatorTest {

    private lateinit var context: Context
    private lateinit var firestore: FirebaseFirestore
    private lateinit var userId: String
    private lateinit var deviceId: String
    private lateinit var service: GenericPreferenceSyncService
    private lateinit var spec: PreferenceSyncSpec

    @Before
    fun setUp() {
        assumeTrue(
            "Requires USE_FIREBASE_EMULATOR=true — skipped on default debug builds.",
            BuildConfig.USE_FIREBASE_EMULATOR
        )
        context = InstrumentationRegistry.getInstrumentation().targetContext
        FirebaseApp.initializeApp(context)
        try {
            FirebaseFirestore.getInstance().useEmulator(EMULATOR_HOST, FIRESTORE_PORT)
            FirebaseFirestore.getInstance().firestoreSettings =
                FirebaseFirestoreSettings.Builder()
                    .setPersistenceEnabled(false)
                    .build()
        } catch (_: IllegalStateException) {
            // Already routed by an earlier test in the same process.
        }
        try {
            FirebaseAuth.getInstance().useEmulator(EMULATOR_HOST, AUTH_PORT)
        } catch (_: IllegalStateException) {
            // Already routed.
        }
        // firestore.rules requires request.auth != null; sign in to the
        // Auth emulator so the real-SDK reads/writes below aren't rejected
        // with PERMISSION_DENIED. The Firestore path uid is independent of
        // the auth uid (rules don't check uid match), so the per-test
        // synthetic userId still works.
        runBlocking {
            val auth = FirebaseAuth.getInstance()
            if (auth.currentUser == null) {
                auth.signInAnonymously().await()
            }
        }
        firestore = FirebaseFirestore.getInstance()

        // Unique per-test uid so back-to-back runs in the same emulator
        // session can't see each other's docs and the self-echo / sibling
        // assertions stay deterministic. The mocked AuthManager returns
        // this uid; the service trusts that for its `users/{uid}/...` path.
        userId = "prefs-sync-emulator-${System.currentTimeMillis()}-${counter.incrementAndGet()}"
        deviceId = "device-A-${counter.get()}"

        runBlocking { context.testPrefsDataStore.edit { it.clear() } }

        val authManager = mockk<AuthManager> {
            every { this@mockk.userId } returns this@GenericPreferenceSyncServiceEmulatorTest.userId
        }
        val deviceIdProvider = mockk<SyncDeviceIdProvider> {
            coEvery { get() } returns deviceId
        }
        val logger = mockk<PrismSyncLogger>(relaxed = true)
        spec = PreferenceSyncSpec(
            firestoreDocName = TEST_DOC_NAME,
            dataStore = context.testPrefsDataStore
        )
        service = GenericPreferenceSyncService(
            specs = setOf(spec),
            authManager = authManager,
            deviceIdProvider = deviceIdProvider,
            logger = logger
        )
    }

    @After
    fun tearDown() {
        if (!::service.isInitialized) return
        service.stopAfterSignOut()
        runBlocking {
            context.testPrefsDataStore.edit { it.clear() }
            runCatching {
                firestore
                    .collection("users").document(userId)
                    .collection("prefs").document(TEST_DOC_NAME)
                    .delete()
                    .await()
            }
        }
    }

    @Test
    fun startAfterSignIn_pushesLocalStateToFirestore() = runBlocking {
        withTimeout(TEST_TIMEOUT_MS) {
            context.testPrefsDataStore.edit { it[KEY_UI_TIER] = "POWER" }

            service.startAfterSignIn()

            waitFor("doc to appear with ui_complexity_tier=POWER") {
                val data = remoteDoc()?.data ?: return@waitFor false
                data["ui_complexity_tier"] == "POWER"
            }

            val data = requireNotNull(remoteDoc()?.data)
            assertEquals("POWER", data["ui_complexity_tier"])
            assertEquals(deviceId, data["__pref_device_id"])
            @Suppress("UNCHECKED_CAST")
            val types = data["__pref_types"] as Map<String, String>
            assertEquals("string", types["ui_complexity_tier"])
        }
    }

    @Test
    fun pullListener_appliesSiblingDeviceWrite() = runBlocking {
        withTimeout(TEST_TIMEOUT_MS) {
            // Register the snapshot listener with no local state to push.
            context.testPrefsDataStore.edit { it.clear() }
            service.startAfterSignIn()

            // Sibling-device payload (different __pref_device_id).
            val siblingPayload = mapOf(
                "ui_complexity_tier" to "BASIC",
                "__pref_device_id" to "device-B-sibling",
                "__pref_updated_at" to System.currentTimeMillis(),
                "__pref_types" to mapOf("ui_complexity_tier" to "string")
            )
            firestore
                .collection("users").document(userId)
                .collection("prefs").document(TEST_DOC_NAME)
                .set(siblingPayload)
                .await()

            waitFor("local DataStore to reflect sibling write") {
                context.testPrefsDataStore.data.first()[KEY_UI_TIER] == "BASIC"
            }

            assertEquals("BASIC", context.testPrefsDataStore.data.first()[KEY_UI_TIER])
        }
    }

    @Test
    fun pullListener_suppressesSelfEcho() = runBlocking {
        withTimeout(TEST_TIMEOUT_MS) {
            // Seed local DataStore with a known value so we can assert
            // the self-echo write does not overwrite it with a stale one.
            context.testPrefsDataStore.edit { it[KEY_UI_TIER] = "POWER" }
            service.startAfterSignIn()

            // Wait for our own initial push to land — confirms the listener
            // is wired before we plant the self-echo doc.
            waitFor("initial push to land") {
                remoteDoc()?.getString("ui_complexity_tier") == "POWER"
            }

            // Plant a self-echo: same __pref_device_id as the local device,
            // but a stale value. The listener must skip it.
            val selfEcho = mapOf(
                "ui_complexity_tier" to "STANDARD",
                "__pref_device_id" to deviceId,
                "__pref_updated_at" to System.currentTimeMillis(),
                "__pref_types" to mapOf("ui_complexity_tier" to "string")
            )
            firestore
                .collection("users").document(userId)
                .collection("prefs").document(TEST_DOC_NAME)
                .set(selfEcho)
                .await()

            // Give the listener time to receive and (correctly) ignore.
            delay(SETTLE_DELAY_MS)

            assertEquals(
                "Self-echo must not mutate local DataStore",
                "POWER",
                context.testPrefsDataStore.data.first()[KEY_UI_TIER]
            )
        }
    }

    private suspend fun remoteDoc() =
        firestore
            .collection("users").document(userId)
            .collection("prefs").document(TEST_DOC_NAME)
            .get()
            .await()
            .takeIf { it.exists() }

    private suspend fun waitFor(
        message: String,
        predicate: suspend () -> Boolean
    ) {
        val deadline = System.nanoTime() + WAIT_FOR_TIMEOUT_NS
        while (System.nanoTime() < deadline) {
            if (predicate()) return
            delay(POLL_INTERVAL_MS)
        }
        if (predicate()) return
        throw AssertionError("waitFor($message) did not converge")
    }

    companion object {
        private const val EMULATOR_HOST = "10.0.2.2"
        private const val FIRESTORE_PORT = 8080
        private const val AUTH_PORT = 9099
        private const val TEST_DOC_NAME = "pref_sync_emulator_test"
        private const val TEST_TIMEOUT_MS = 60_000L
        private const val POLL_INTERVAL_MS = 200L
        private const val SETTLE_DELAY_MS = 1_500L
        private const val WAIT_FOR_TIMEOUT_NS = 30_000_000_000L // 30s

        private val KEY_UI_TIER = stringPreferencesKey("ui_complexity_tier")

        private val counter = AtomicInteger(0)
    }
}

/**
 * Test-only DataStore. Defined at file scope (Compose/DataStore extension
 * properties must be top-level) so each test class instance reuses the
 * same on-disk file. `@Before` clears it; the file is small and emulator
 * tests run in their own data dir so cross-test bleed-over is bounded.
 */
private val Context.testPrefsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "pref_sync_emulator_test_prefs"
)
