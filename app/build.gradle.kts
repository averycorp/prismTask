plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
    id("org.jlleitschuh.gradle.ktlint")
    id("io.gitlab.arturbosch.detekt")
}

android {
    namespace = "com.averycorp.prismtask"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.averycorp.prismtask"
        minSdk = 26
        targetSdk = 35
        versionCode = 847
        versionName = "1.8.49"

        testInstrumentationRunner = "com.averycorp.prismtask.HiltTestRunner"
        // Wipe app state between instrumented test methods so each method
        // starts in a clean process. Pairs with the
        // `ANDROIDX_TEST_ORCHESTRATOR` execution mode set below — without
        // this flag Orchestrator still runs each test in its own process
        // but leaves /data/data state intact, so any process-singleton
        // (Firestore, WorkManager) carries over. The combination is what
        // closes the long-suite `ConnectivityManager$TooManyRequestsException`
        // accumulation that broke `connectedDebugAndroidTest` at test
        // ~397/422.
        testInstrumentationRunnerArguments["clearPackageData"] = "true"
        vectorDrawables { useSupportLibrary = true }

        buildConfigField(
            "String",
            "WEB_CLIENT_ID",
            "\"${System.getenv("WEB_CLIENT_ID") ?: "403186103462-j09m2o9781jgnpb2eqotn65jdcg7qgqj.apps.googleusercontent.com"}\""
        )
        // Widgets shipped — full Glance lineup with PrismTheme atmospherics
        // (Cyberpunk scanlines, Synthwave sunset, Matrix phosphor, Void
        // editorial). Toggle this flag back to false to disable every
        // GlanceAppWidget receiver in one place.
        buildConfigField("boolean", "WIDGETS_ENABLED", "true")
    }

    val keystorePath = System.getenv("KEYSTORE_PATH")
    val hasReleaseSigning = keystorePath != null && file(keystorePath).exists()

    signingConfigs {
        // Override the default auto-generated ~/.android/debug.keystore with
        // a stable keystore committed to the repo. Without this, every CI
        // runner signs the debug APK with a freshly-generated key, so each
        // release has a different signature and the in-app updater's
        // installed-signature check rejects every update.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
                keyAlias = System.getenv("KEY_ALIAS") ?: "prismtask"
                keyPassword = System.getenv("KEY_PASSWORD") ?: ""
            }
        }
    }
    buildTypes {
        debug {
            // Point debug builds at the live Railway backend so that sideloaded
            // debug APKs can actually reach the Claude-Haiku parse endpoint.
            // Override with a `API_BASE_URL_DEBUG` env var if you need to
            // target a local FastAPI server from the emulator (use
            // "http://10.0.2.2:8000" for emulator → host loopback).
            val debugApiUrl = System.getenv("API_BASE_URL_DEBUG")
                ?: "https://averytask-production.up.railway.app"
            buildConfigField("String", "API_BASE_URL", "\"$debugApiUrl\"")
            // Route Firebase clients at the local Firebase Emulator Suite.
            // Debug builds default to emulator OFF; read USE_FIREBASE_EMULATOR
            // from the environment so CI (see
            // .github/workflows/android-integration.yml) can flip it on for
            // instrumented tests without hand-editing this file. Accepts
            // "true"/"false"; anything else falls back to false. See
            // docs/FIREBASE_EMULATOR.md for the local-dev workflow.
            val useEmulator = when (System.getenv("USE_FIREBASE_EMULATOR")?.lowercase()) {
                "true" -> "true"
                else -> "false"
            }
            buildConfigField("boolean", "USE_FIREBASE_EMULATOR", useEmulator)
            // Speed up debug builds
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            buildConfigField("String", "API_BASE_URL", "\"https://averytask-production.up.railway.app\"")
            buildConfigField("boolean", "USE_FIREBASE_EMULATOR", "false")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }

    buildTypes.getByName("release") {
        configure<com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension> {
            mappingFileUploadEnabled = true
        }
    }

    testOptions {
        // Run each instrumented test method in its own process via
        // androidx.test.orchestrator (the `androidTestUtil` dependency
        // below). Each FirebaseFirestore client registers a
        // `ConnectivityManager.registerDefaultNetworkCallback` callback
        // (Android caps these at ~100 per UID); a single-process run of
        // 422 instrumented tests cumulatively burnt the quota and
        // dropped the suite at the offline-toggle smoke test. Per-test
        // process isolation hard-resets the count between tests.
        execution = "ANDROIDX_TEST_ORCHESTRATOR"

        unitTests {
            isIncludeAndroidResources = true
            // Return default values (nulls, zeros, empty strings) for any
            // Android framework method the tests don't explicitly mock —
            // e.g. `android.util.Log.i(...)`. Without this, any production
            // code path that calls a framework method from a plain JVM
            // unit test throws "Method X not mocked" and the test fails.
            isReturnDefaultValues = true
            all {
                // Parallelize across test classes. Each fork is a separate JVM,
                // so keep the count at half the host's CPU count to leave
                // headroom for Robolectric's per-JVM memory footprint.
                it.maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2)
                    .coerceAtLeast(1)
                it.maxHeapSize = "1536m"
                // TieredStopAtLevel=1 skips C2 JIT compilation — tests are
                // short-lived, so the C2 compile time never pays off.
                it.jvmArgs(
                    "-XX:TieredStopAtLevel=1",
                    "-XX:+UseParallelGC",
                    "-noverify"
                )
            }
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1,INDEX.LIST,DEPENDENCIES}"
            // JUnit Jupiter ships LICENSE.md + LICENSE-notice.md across six
            // jars (junit-jupiter / -api / -params / -engine / -platform
            // commons / -engine); without these excludes the androidTest
            // APK merge fails with "6 files found with path 'META-INF/LICENSE.md'".
            excludes += "/META-INF/{LICENSE.md,LICENSE-notice.md}"
        }
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

ksp {
    arg("dagger.hilt.disableModulesHaveInstallInCheck", "true")
}

// ktlint — Kotlin linter / formatter
// Plugin: jlleitschuh/ktlint-gradle 12.1.1
// Target Kotlin: 2.2.10
ktlint {
    outputToConsole.set(true)
    coloredOutput.set(true)
    ignoreFailures.set(false)
    // NOTE: Do not set `additionalEditorconfig` here with keys that aren't
    // registered in ktlint 1.2.1's EditorConfigPropertyRegistry (e.g.
    // `ktlint_kotlin_version`). The ktlint-gradle 12.1.1 plugin routes
    // `additionalEditorconfig` entries through a strict
    // `EditorConfigPropertyRegistry.find(key)` lookup; unknown keys throw
    // an exception that the plugin wraps as "KtLint failed to parse file:
    // <path>" against whichever file the worker happened to be processing.
    // The ktlint CLI silently ignores unknown keys in `.editorconfig`, so
    // any non-registered settings belong in the project-root `.editorconfig`
    // only.
    filter {
        exclude { element -> element.file.path.contains("/build/") }
        exclude { element -> element.file.path.contains("/generated/") }
    }
}

// detekt — static analysis for Kotlin
detekt {
    toolVersion = "1.23.6"
    config.setFrom(files("$rootDir/detekt.yml"))
    buildUponDefaultConfig = true
    autoCorrect = true
    parallel = true
    source.setFrom(
        files(
            "src/main/java",
            "src/main/kotlin",
            "src/test/java",
            "src/test/kotlin",
            "src/androidTest/java",
            "src/androidTest/kotlin"
        )
    )
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    jvmTarget = JavaVersion.VERSION_21.toString()
    exclude("**/build/**", "**/generated/**")
    reports {
        html.required.set(true)
        xml.required.set(true)
        txt.required.set(false)
        sarif.required.set(false)
        md.required.set(false)
    }
}

tasks.withType<io.gitlab.arturbosch.detekt.DetektCreateBaselineTask>().configureEach {
    jvmTarget = JavaVersion.VERSION_21.toString()
    exclude("**/build/**", "**/generated/**")
}

// Copy built AAB files to the repository root
android.applicationVariants.all {
    val variant = this
    tasks.named("bundle${variant.name.replaceFirstChar { it.uppercase() }}").configure {
        doLast {
            val aabDir = file("${project.layout.buildDirectory.get()}/outputs/bundle/${variant.name}")
            aabDir.listFiles()?.filter { it.extension == "aab" }?.forEach { aab ->
                aab.copyTo(rootProject.layout.projectDirectory.file(aab.name).asFile, overwrite = true)
                println("Copied ${aab.name} to project root")
            }
        }
    }
}

dependencies {
    // Core
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core-splashscreen:1.2.0")
    implementation("androidx.activity:activity-compose:1.9.3")

    // Compose BOM
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.9.8")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // Room
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.59.2")
    ksp("com.google.dagger:hilt-compiler:2.59.2")
    implementation("androidx.hilt:hilt-navigation-compose:1.3.0")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.hilt:hilt-work:1.3.0")
    ksp("androidx.hilt:hilt-compiler:1.3.0")

    // Gson
    implementation("com.google.code.gson:gson:2.11.0")

    // OkHttp
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    // F7 D.1: SSE companion artifact for the chat-streaming endpoint.
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")

    // Retrofit
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Encrypted storage
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Reorderable (drag-to-reorder for LazyColumn)
    implementation("sh.calvin.reorderable:reorderable:2.4.3")

    // Glance Widgets
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.glance:glance-material3:1.1.1")

    // Firebase
    implementation(platform("com.google.firebase:firebase-bom:33.16.0"))
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-firestore-ktx")
    implementation("com.google.firebase:firebase-storage-ktx")
    implementation("com.google.firebase:firebase-crashlytics-ktx")
    implementation("com.google.firebase:firebase-analytics-ktx")

    // gRPC — Firestore requires gRPC 1.62.2+ for InternalGlobalInterceptors,
    // but google-api-client-android pulls an older version that wins in resolution.
    // Force 1.65.0 to ensure the class exists at runtime.
    implementation("io.grpc:grpc-api:1.80.0")
    implementation("io.grpc:grpc-android:1.80.0")
    implementation("io.grpc:grpc-okhttp:1.80.0")

    // Credential Manager (Google Sign-In)
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    // Google Drive API
    implementation("com.google.android.gms:play-services-auth:21.3.0")
    implementation("com.google.api-client:google-api-client-android:2.7.2")
    implementation("com.google.apis:google-api-services-drive:v3-rev20241206-2.0.0")

    // Google Calendar API
    implementation("com.google.apis:google-api-services-calendar:v3-rev20241101-2.0.0")
    implementation("com.google.http-client:google-http-client-gson:2.1.0")

    // Google Play Billing
    implementation("com.android.billingclient:billing-ktx:7.1.1")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.room:room-testing:2.8.4")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("io.mockk:mockk:1.13.13")
    testImplementation("app.cash.turbine:turbine:1.1.0")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("androidx.work:work-testing:2.9.1")
    // InstantTaskExecutorRule for Robolectric unit tests that need it
    // (e.g. TaskDependencyRepositoryTest from PR-2 #1086 which uses
    // androidx.arch.core.executor.testing.InstantTaskExecutorRule).
    // Already present in androidTestImplementation; mirror here so
    // Robolectric-driven unit tests can use the same rule.
    testImplementation("androidx.arch.core:core-testing:2.2.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.room:room-testing:2.8.4")
    androidTestImplementation("androidx.arch.core:core-testing:2.2.0")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation("com.google.dagger:hilt-android-testing:2.59.2")
    androidTestImplementation("io.mockk:mockk-android:1.13.13")
    androidTestImplementation("androidx.work:work-testing:2.9.1")
    // Required by `testOptions.execution = "ANDROIDX_TEST_ORCHESTRATOR"`.
    // The `androidTestUtil` configuration installs the orchestrator APK
    // alongside the test APK rather than bundling it.
    androidTestUtil("androidx.test:orchestrator:1.5.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    kspAndroidTest("com.google.dagger:hilt-compiler:2.59.2")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // detekt — formatting rules (ktlint wrapper) so detekt can auto-correct formatting issues
    detektPlugins("io.gitlab.arturbosch.detekt:detekt-formatting:1.23.6")
    // detekt — custom rules for PrismTask theme token enforcement
    detektPlugins(project(":detekt-rules"))
}
