package com.averycorp.prismtask.ui.screens.feedback

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.averycorp.prismtask.BuildConfig
import com.averycorp.prismtask.data.diagnostics.DiagnosticLogger
import com.averycorp.prismtask.data.local.dao.HabitDao
import com.averycorp.prismtask.data.local.dao.TaskDao
import com.averycorp.prismtask.data.remote.AuthManager
import com.averycorp.prismtask.data.remote.api.PrismTaskApi
import com.averycorp.prismtask.domain.model.BugCategory
import com.averycorp.prismtask.domain.model.BugReport
import com.averycorp.prismtask.domain.model.BugSeverity
import com.averycorp.prismtask.domain.model.ReportStatus
import com.averycorp.prismtask.domain.rating.RecentCrashSignal
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class BugReportViewModel
@Inject
constructor(
    @ApplicationContext private val appContext: Context,
    private val taskDao: TaskDao,
    private val habitDao: HabitDao,
    private val diagnosticLogger: DiagnosticLogger,
    private val authManager: AuthManager,
    private val api: PrismTaskApi,
    private val recentCrashSignal: RecentCrashSignal,
    savedStateHandle: SavedStateHandle
) : ViewModel() {
    private val fromScreen: String = savedStateHandle.get<String>("fromScreen") ?: "Unknown"
    private val initialScreenshotUri: Uri? = savedStateHandle
        .get<String>("screenshotUri")
        ?.takeIf { it.isNotBlank() }
        ?.let {
            try {
                Uri.parse(it)
            } catch (_: Exception) {
                null
            }
        }

    // Form state
    private val _category = MutableStateFlow(BugCategory.OTHER)
    val category: StateFlow<BugCategory> = _category.asStateFlow()

    private val _description = MutableStateFlow("")
    val description: StateFlow<String> = _description.asStateFlow()

    private val _severity = MutableStateFlow(BugSeverity.MINOR)
    val severity: StateFlow<BugSeverity> = _severity.asStateFlow()

    private val _steps = MutableStateFlow(listOf("", ""))
    val steps: StateFlow<List<String>> = _steps.asStateFlow()

    private val _screenshotUris = MutableStateFlow<List<Uri>>(
        listOfNotNull(initialScreenshotUri)
    )
    val screenshotUris: StateFlow<List<Uri>> = _screenshotUris.asStateFlow()

    private val _includeDiagnosticLog = MutableStateFlow(true)
    val includeDiagnosticLog: StateFlow<Boolean> = _includeDiagnosticLog.asStateFlow()

    private val _isSubmitting = MutableStateFlow(false)
    val isSubmitting: StateFlow<Boolean> = _isSubmitting.asStateFlow()

    private val _submitSuccess = MutableStateFlow(false)
    val submitSuccess: StateFlow<Boolean> = _submitSuccess.asStateFlow()

    private val _messages = MutableSharedFlow<String>()
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _contextExpanded = MutableStateFlow(false)
    val contextExpanded: StateFlow<Boolean> = _contextExpanded.asStateFlow()

    // Auto-context
    private val _deviceInfo = MutableStateFlow<DeviceInfo?>(null)
    val deviceInfo: StateFlow<DeviceInfo?> = _deviceInfo.asStateFlow()

    private val _diagnosticLogText = MutableStateFlow("")
    val diagnosticLogText: StateFlow<String> = _diagnosticLogText.asStateFlow()

    private val _diagnosticLogCount = MutableStateFlow(0)
    val diagnosticLogCount: StateFlow<Int> = _diagnosticLogCount.asStateFlow()

    val isSignedIn: StateFlow<Boolean> = authManager.isSignedIn

    // Feature request mode
    private val _isFeatureRequest = MutableStateFlow(false)
    val isFeatureRequest: StateFlow<Boolean> = _isFeatureRequest.asStateFlow()

    private val _importance = MutableStateFlow("Nice to Have")
    val importance: StateFlow<String> = _importance.asStateFlow()

    val isValid: Boolean
        get() = _description.value.isNotBlank()

    init {
        viewModelScope.launch {
            collectAutoContext()
            _diagnosticLogText.value = diagnosticLogger.exportAsText()
            _diagnosticLogCount.value = diagnosticLogger.getEntryCount()
        }
        // Pre-fill step 1 with the current screen
        if (fromScreen.isNotBlank() && fromScreen != "Unknown") {
            _steps.value = listOf("I was on the $fromScreen screen", "")
        }
    }

    fun setCategory(category: BugCategory) {
        _category.value = category
    }

    fun setDescription(desc: String) {
        _description.value = desc
    }

    fun setSeverity(severity: BugSeverity) {
        _severity.value = severity
    }

    fun setIncludeDiagnosticLog(include: Boolean) {
        _includeDiagnosticLog.value = include
    }

    fun toggleContextExpanded() {
        _contextExpanded.value = !_contextExpanded.value
    }

    fun setIsFeatureRequest(value: Boolean) {
        _isFeatureRequest.value = value
        if (value) _category.value = BugCategory.FEATURE_REQUEST
    }

    fun setImportance(value: String) {
        _importance.value = value
    }

    fun updateStep(index: Int, text: String) {
        val current = _steps.value.toMutableList()
        if (index < current.size) {
            current[index] = text
            // Auto-add a new step when typing in the last one
            if (index == current.size - 1 && text.isNotEmpty() && current.size < 10) {
                current.add("")
            }
            _steps.value = current
        }
    }

    fun addScreenshot(uri: Uri) {
        val current = _screenshotUris.value
        if (current.size < 3) {
            _screenshotUris.value = current + uri
        }
    }

    fun removeScreenshot(index: Int) {
        val current = _screenshotUris.value.toMutableList()
        if (index < current.size) {
            current.removeAt(index)
            _screenshotUris.value = current
        }
    }

    fun submit() {
        if (!isValid || _isSubmitting.value) return

        val userId = authManager.userId
        if (userId == null) {
            viewModelScope.launch {
                _messages.emit("Please sign in to submit reports.")
            }
            return
        }

        _isSubmitting.value = true

        viewModelScope.launch {
            try {
                val reportId = UUID.randomUUID().toString()

                // Upload screenshots to Firebase Storage (user-scoped path)
                val screenshotUrls = mutableListOf<String>()
                for ((index, uri) in _screenshotUris.value.withIndex()) {
                    try {
                        val ref = FirebaseStorage
                            .getInstance()
                            .reference
                            .child("users/$userId/bug_reports/$reportId/screenshot_$index.jpg")
                        ref.putFile(uri).await()
                        val url = ref.downloadUrl.await().toString()
                        screenshotUrls.add(url)
                    } catch (e: Exception) {
                        Log.e("BugReport", "Screenshot upload failed", e)
                    }
                }

                val info = _deviceInfo.value ?: collectDeviceInfo()
                val report = BugReport(
                    id = reportId,
                    userId = userId,
                    category = _category.value,
                    description = _description.value,
                    severity = _severity.value,
                    steps = _steps.value.filter { it.isNotBlank() },
                    screenshotUris = screenshotUrls,
                    deviceModel = info.model,
                    deviceManufacturer = info.manufacturer,
                    androidVersion = info.sdkVersion,
                    appVersion = info.appVersion,
                    appVersionCode = info.appVersionCode,
                    buildType = info.buildType,
                    userTier = info.userTier,
                    currentScreen = fromScreen,
                    taskCount = info.taskCount,
                    habitCount = info.habitCount,
                    availableRamMb = info.availableRamMb,
                    freeStorageMb = info.freeStorageMb,
                    networkType = info.networkType,
                    batteryPercent = info.batteryPercent,
                    isCharging = info.isCharging,
                    timestamp = System.currentTimeMillis(),
                    status = ReportStatus.SUBMITTED,
                    diagnosticLog = if (_includeDiagnosticLog.value) _diagnosticLogText.value else null,
                    submittedVia = "firestore"
                )

                // Write to Firestore under user-scoped path
                val data = reportToMap(report)
                FirebaseFirestore
                    .getInstance()
                    .collection("users")
                    .document(userId)
                    .collection("bug_reports")
                    .document(reportId)
                    .set(data)
                    .await()

                // Also mirror to the backend PostgreSQL so the admin debug-logs
                // panel can see it. Fire-and-forget: the Firestore write above
                // is authoritative, so a backend failure must not surface to
                // the user as a submit failure.
                try {
                    api.submitBugReport(data)
                } catch (e: Exception) {
                    Log.w("BugReport", "Backend mirror failed (non-fatal)", e)
                }

                _submitSuccess.value = true
                _messages.emit("Thanks! We'll look into this.")
                resetForm()
            } catch (e: Exception) {
                Log.e("BugReport", "Submit failed", e)
                FirebaseCrashlytics.getInstance().recordException(e)
                // Mirror the crash timestamp into RatingPromptPreferences so
                // the in-app rating trigger heuristic suppresses prompts for
                // 24h after a non-fatal report (see E2 audit § Item 3).
                recentCrashSignal.recordCrash()
                _messages.emit("Failed to submit report. Please try again.")
            } finally {
                _isSubmitting.value = false
            }
        }
    }

    private fun resetForm() {
        _description.value = ""
        _category.value = BugCategory.OTHER
        _severity.value = BugSeverity.MINOR
        _steps.value = listOf("", "")
        _screenshotUris.value = emptyList()
    }

    private suspend fun collectAutoContext() {
        _deviceInfo.value = collectDeviceInfo()
    }

    private suspend fun collectDeviceInfo(): DeviceInfo = withContext(Dispatchers.IO) {
        val taskCount = try {
            taskDao.getAllTasksOnce().size
        } catch (_: Exception) {
            0
        }
        val habitCount = try {
            habitDao.getAllHabitsOnce().size
        } catch (_: Exception) {
            0
        }

        val activityManager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memInfo)
        val availableRamMb = (memInfo.availMem / (1024 * 1024)).toInt()

        val statFs = StatFs(Environment.getDataDirectory().path)
        val freeStorageMb = (statFs.availableBlocksLong * statFs.blockSizeLong / (1024 * 1024)).toInt()

        val networkType = getNetworkType()
        val (batteryPercent, isCharging) = getBatteryInfo()

        DeviceInfo(
            model = Build.MODEL,
            manufacturer = Build.MANUFACTURER,
            sdkVersion = Build.VERSION.SDK_INT,
            appVersion = BuildConfig.VERSION_NAME,
            appVersionCode = BuildConfig.VERSION_CODE,
            buildType = if (BuildConfig.DEBUG) "debug" else "release",
            // Default; could be injected from BillingManager
            userTier = "Free",
            taskCount = taskCount,
            habitCount = habitCount,
            availableRamMb = availableRamMb,
            freeStorageMb = freeStorageMb,
            networkType = networkType,
            batteryPercent = batteryPercent,
            isCharging = isCharging
        )
    }

    private fun getNetworkType(): String {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return "unknown"
        val network = cm.activeNetwork ?: return "offline"
        val caps = cm.getNetworkCapabilities(network) ?: return "offline"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
    }

    private fun getBatteryInfo(): Pair<Int, Boolean> {
        val batteryStatus = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return Pair(percent, isCharging)
    }

    private fun reportToMap(report: BugReport): Map<String, Any?> = mapOf(
        "id" to report.id,
        "userId" to report.userId,
        "category" to report.category.name,
        "description" to report.description,
        "severity" to report.severity.name,
        "steps" to report.steps,
        "screenshotUris" to report.screenshotUris,
        "deviceModel" to report.deviceModel,
        "deviceManufacturer" to report.deviceManufacturer,
        "androidVersion" to report.androidVersion,
        "appVersion" to report.appVersion,
        "appVersionCode" to report.appVersionCode,
        "buildType" to report.buildType,
        "userTier" to report.userTier,
        "currentScreen" to report.currentScreen,
        "taskCount" to report.taskCount,
        "habitCount" to report.habitCount,
        "availableRamMb" to report.availableRamMb,
        "freeStorageMb" to report.freeStorageMb,
        "networkType" to report.networkType,
        "batteryPercent" to report.batteryPercent,
        "isCharging" to report.isCharging,
        "timestamp" to report.timestamp,
        "status" to report.status.name,
        "diagnosticLog" to report.diagnosticLog,
        "submittedVia" to report.submittedVia
    )
}

data class DeviceInfo(
    val model: String,
    val manufacturer: String,
    val sdkVersion: Int,
    val appVersion: String,
    val appVersionCode: Int,
    val buildType: String,
    val userTier: String,
    val taskCount: Int,
    val habitCount: Int,
    val availableRamMb: Int,
    val freeStorageMb: Int,
    val networkType: String,
    val batteryPercent: Int,
    val isCharging: Boolean
)
