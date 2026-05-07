# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# ── Room entities & DAOs ──
-keep class com.averycorp.prismtask.data.local.entity.** { *; }
-keep interface com.averycorp.prismtask.data.local.dao.** { *; }
-keep class com.averycorp.prismtask.data.local.dao.ProjectWithCount { *; }
-keep class com.averycorp.prismtask.data.local.dao.EntityFrequency { *; }

# ── Room relation / cross-ref classes ──
-keep class com.averycorp.prismtask.data.local.entity.TaskTagCrossRef { *; }
-keep class com.averycorp.prismtask.data.local.entity.TaskWithTags { *; }

# ── Domain models (serialized with Gson) ──
-keep class com.averycorp.prismtask.domain.model.** { *; }

# ── Room type converters ──
-keep class com.averycorp.prismtask.data.local.converter.** { *; }

# ── Notification receivers ──
-keep class com.averycorp.prismtask.notifications.** { *; }

# ── WorkManager workers ──
-keep class com.averycorp.prismtask.workers.** { *; }

# ── Firebase / Firestore ──
-keep class com.averycorp.prismtask.data.remote.model.** { *; }
-keep class com.averycorp.prismtask.data.remote.mapper.** { *; }

# ── Claude API models (inner classes in ClaudeParserService, deserialized by Gson) ──
-keep class com.averycorp.prismtask.data.remote.ClaudeParserService$* { *; }

# ── gRPC (transitive dependency from Firebase/Firestore) ──
# Keep all gRPC classes needed by Firebase Firestore
-keep class io.grpc.** { *; }
-dontwarn io.grpc.**

# Keep gRPC service providers loaded via reflection
-keepnames class io.grpc.internal.DnsNameResolverProvider
-keepnames class io.grpc.okhttp.OkHttpChannelProvider
-keep class io.grpc.internal.JndiResourceResolverFactory* { *; }

# Suppress warnings for javax.naming used by gRPC internals
-dontwarn javax.naming.**

# ── Google Drive API ──
-keep class com.google.api.services.drive.** { *; }
-keep class com.google.api.client.** { *; }
-dontwarn com.google.api.client.**
-dontwarn com.google.api.services.**

# ── Google Play Billing ──
-keep class com.android.vending.billing.** { *; }
-keep class com.android.billingclient.** { *; }

# ── Google Calendar API ──
-keep class com.google.api.services.calendar.** { *; }

# ── Billing data classes ──
-keep class com.averycorp.prismtask.data.billing.** { *; }

# ── Firebase Crashlytics ──
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ── Bug report model (serialized with Gson for Firestore) ──
-keep class com.averycorp.prismtask.domain.model.BugReport { *; }
-keep class com.averycorp.prismtask.domain.model.BugCategory { *; }
-keep class com.averycorp.prismtask.domain.model.BugSeverity { *; }
-keep class com.averycorp.prismtask.domain.model.ReportStatus { *; }

# ── Backend API models (Gson-serialized request/response classes) ──
-keep class com.averycorp.prismtask.data.remote.api.** { *; }
-keep class com.averycorp.prismtask.data.remote.sync.** { *; }

# ── Widget data models ──
-keep class com.averycorp.prismtask.widget.WidgetTaskRow { *; }
-keep class com.averycorp.prismtask.widget.TodayWidgetData { *; }
-keep class com.averycorp.prismtask.widget.HabitWidgetData { *; }
-keep class com.averycorp.prismtask.widget.HabitWidgetItem { *; }
-keep class com.averycorp.prismtask.widget.UpcomingWidgetData { *; }
-keep class com.averycorp.prismtask.widget.ProductivityWidgetData { *; }

# ── Export/import data classes ──
-keep class com.averycorp.prismtask.data.export.** { *; }

# ── Checklist/NLP parser inner classes (Gson-serialized) ──
-keep class com.averycorp.prismtask.domain.usecase.ChecklistParser$* { *; }
-keep class com.averycorp.prismtask.domain.usecase.TodoListParser$* { *; }

# ── Gson ──
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class * extends com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
# Preserve generic type info for Gson reflection
-keepattributes EnclosingMethod
-keepattributes InnerClasses
-keepattributes Signature
