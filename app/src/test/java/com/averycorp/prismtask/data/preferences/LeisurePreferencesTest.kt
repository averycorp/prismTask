package com.averycorp.prismtask.data.preferences

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE, sdk = [33], application = Application::class)
class LeisurePreferencesTest {
    private lateinit var prefs: LeisurePreferences

    @Before
    fun setUp() = runBlocking {
        prefs = LeisurePreferences(ApplicationProvider.getApplicationContext())
        prefs.clearAll()
    }

    @Test
    fun music_slot_defaults_match_legacy_values() = runTest {
        val config = prefs.getSlotConfig(LeisureSlotId.MUSIC).first()
        assertTrue(config.enabled)
        assertEquals("Music Practice", config.label)
        assertEquals(15, config.durationMinutes)
        assertEquals(3, config.gridColumns)
        assertTrue(config.autoComplete)
        assertTrue(config.hiddenBuiltInIds.isEmpty())
        assertTrue(config.customActivities.isEmpty())
    }

    @Test
    fun flex_slot_defaults_match_legacy_values() = runTest {
        val config = prefs.getSlotConfig(LeisureSlotId.FLEX).first()
        assertTrue(config.enabled)
        assertEquals("Flexible", config.label)
        assertEquals(30, config.durationMinutes)
        assertEquals(2, config.gridColumns)
    }

    @Test
    fun language_slot_defaults_to_disabled_so_existing_meta_habit_unaffected() = runTest {
        // LANGUAGE is opt-in; defaulting it to disabled keeps the shared
        // "Leisure" meta-habit completion definition unchanged for users
        // upgrading from a build that only had MUSIC + FLEX.
        val config = prefs.getSlotConfig(LeisureSlotId.LANGUAGE).first()
        assertFalse(config.enabled)
        assertEquals("Language Practice", config.label)
        assertEquals(15, config.durationMinutes)
        assertEquals(3, config.gridColumns)
    }

    @Test
    fun setBuiltInHidden_isolates_language_slot_from_other_slots() = runTest {
        prefs.setBuiltInHidden(LeisureSlotId.LANGUAGE, "italian", hidden = true)
        prefs.setBuiltInHidden(LeisureSlotId.MUSIC, "bass", hidden = true)
        assertEquals(
            listOf("italian"),
            prefs.getSlotConfig(LeisureSlotId.LANGUAGE).first().hiddenBuiltInIds
        )
        assertEquals(
            listOf("bass"),
            prefs.getSlotConfig(LeisureSlotId.MUSIC).first().hiddenBuiltInIds
        )
    }

    @Test
    fun updateSlotConfig_persists_each_field_independently() = runTest {
        prefs.updateSlotConfig(
            LeisureSlotId.MUSIC,
            enabled = false,
            label = "Practice",
            emoji = "\uD83C\uDFB6",
            durationMinutes = 25,
            gridColumns = 4,
            autoComplete = false
        )
        val config = prefs.getSlotConfig(LeisureSlotId.MUSIC).first()
        assertFalse(config.enabled)
        assertEquals("Practice", config.label)
        assertEquals("\uD83C\uDFB6", config.emoji)
        assertEquals(25, config.durationMinutes)
        assertEquals(4, config.gridColumns)
        assertFalse(config.autoComplete)
    }

    @Test
    fun updateSlotConfig_ignores_blank_label_and_emoji() = runTest {
        prefs.updateSlotConfig(LeisureSlotId.FLEX, label = "Chill", emoji = "\uD83E\uDDD8")
        prefs.updateSlotConfig(LeisureSlotId.FLEX, label = "   ", emoji = "   ")
        val config = prefs.getSlotConfig(LeisureSlotId.FLEX).first()
        assertEquals("Chill", config.label)
        assertEquals("\uD83E\uDDD8", config.emoji)
    }

    @Test
    fun updateSlotConfig_coerces_duration_and_columns_to_bounds() = runTest {
        prefs.updateSlotConfig(LeisureSlotId.MUSIC, durationMinutes = 9999, gridColumns = 99)
        var config = prefs.getSlotConfig(LeisureSlotId.MUSIC).first()
        assertEquals(LeisurePreferences.MAX_DURATION_MINUTES, config.durationMinutes)
        assertEquals(LeisurePreferences.MAX_GRID_COLUMNS, config.gridColumns)

        prefs.updateSlotConfig(LeisureSlotId.MUSIC, durationMinutes = 0, gridColumns = 0)
        config = prefs.getSlotConfig(LeisureSlotId.MUSIC).first()
        assertEquals(LeisurePreferences.MIN_DURATION_MINUTES, config.durationMinutes)
        assertEquals(LeisurePreferences.MIN_GRID_COLUMNS, config.gridColumns)
    }

    @Test
    fun setBuiltInHidden_adds_and_removes_ids() = runTest {
        prefs.setBuiltInHidden(LeisureSlotId.MUSIC, "bass", hidden = true)
        prefs.setBuiltInHidden(LeisureSlotId.MUSIC, "drums", hidden = true)
        assertEquals(
            listOf("bass", "drums"),
            prefs.getSlotConfig(LeisureSlotId.MUSIC).first().hiddenBuiltInIds
        )

        prefs.setBuiltInHidden(LeisureSlotId.MUSIC, "bass", hidden = false)
        assertEquals(
            listOf("drums"),
            prefs.getSlotConfig(LeisureSlotId.MUSIC).first().hiddenBuiltInIds
        )
    }

    @Test
    fun setBuiltInHidden_is_idempotent() = runTest {
        prefs.setBuiltInHidden(LeisureSlotId.FLEX, "read", hidden = true)
        prefs.setBuiltInHidden(LeisureSlotId.FLEX, "read", hidden = true)
        assertEquals(
            listOf("read"),
            prefs.getSlotConfig(LeisureSlotId.FLEX).first().hiddenBuiltInIds
        )
    }

    @Test
    fun addActivity_assigns_unique_ids_per_slot() = runTest {
        prefs.addActivity(LeisureSlotId.MUSIC, "Violin", "\uD83C\uDFBB")
        prefs.addActivity(LeisureSlotId.FLEX, "Hike", "\uD83E\uDD7E")

        val music = prefs.getSlotConfig(LeisureSlotId.MUSIC).first().customActivities
        val flex = prefs.getSlotConfig(LeisureSlotId.FLEX).first().customActivities
        assertEquals(1, music.size)
        assertEquals(1, flex.size)
        assertTrue(music.single().id.startsWith("custom_music_"))
        assertTrue(flex.single().id.startsWith("custom_flex_"))
        assertEquals("Violin", music.single().label)
        assertEquals("Hike", flex.single().label)
    }

    @Test
    fun removeActivity_only_affects_target_slot() = runTest {
        prefs.addActivity(LeisureSlotId.MUSIC, "Violin", "\uD83C\uDFBB")
        prefs.addActivity(LeisureSlotId.FLEX, "Hike", "\uD83E\uDD7E")
        val musicId = prefs.getSlotConfig(LeisureSlotId.MUSIC).first().customActivities.single().id

        prefs.removeActivity(LeisureSlotId.MUSIC, musicId)

        assertTrue(prefs.getSlotConfig(LeisureSlotId.MUSIC).first().customActivities.isEmpty())
        assertEquals(1, prefs.getSlotConfig(LeisureSlotId.FLEX).first().customActivities.size)
    }

    @Test
    fun resetSlotConfig_restores_defaults_and_keeps_customs() = runTest {
        prefs.updateSlotConfig(
            LeisureSlotId.MUSIC,
            enabled = false,
            label = "Practice",
            durationMinutes = 45
        )
        prefs.addActivity(LeisureSlotId.MUSIC, "Violin", "\uD83C\uDFBB")
        prefs.setBuiltInHidden(LeisureSlotId.MUSIC, "bass", hidden = true)

        prefs.resetSlotConfig(LeisureSlotId.MUSIC)

        val config = prefs.getSlotConfig(LeisureSlotId.MUSIC).first()
        assertTrue(config.enabled)
        assertEquals("Music Practice", config.label)
        assertEquals(15, config.durationMinutes)
        assertTrue(config.hiddenBuiltInIds.isEmpty())
        assertEquals(1, config.customActivities.size)
    }

    @Test
    fun legacy_addMusicActivity_helper_round_trips_to_new_api() = runTest {
        prefs.addMusicActivity("Violin", "\uD83C\uDFBB")
        val config = prefs.getSlotConfig(LeisureSlotId.MUSIC).first()
        assertEquals(1, config.customActivities.size)
        assertEquals("Violin", config.customActivities.single().label)
    }

    /**
     * Regression: GenericPreferenceSyncService can round-trip a leisure
     * preference through Firestore and write a malformed JSON payload back
     * into the local DataStore (empty string, partial write, type drift
     * from another device). Before the runCatching guards in readSlotConfig,
     * the next read would propagate JsonSyntaxException straight up the
     * StateFlow chain and crash LeisureScreen the moment the user tapped
     * "Leisure". The reads must now fall back to defaults instead.
     */
    @Test
    fun getSlotConfig_returnsDefaults_whenHiddenBuiltinsJsonIsMalformed() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.leisureDataStore.edit { p ->
            p[stringPreferencesKey("music_hidden_builtins")] = "{not json"
        }
        val config = prefs.getSlotConfig(LeisureSlotId.MUSIC).first()
        // Default is empty list — the malformed payload is silently ignored.
        assertTrue(config.hiddenBuiltInIds.isEmpty())
    }

    @Test
    fun getSlotConfig_returnsDefaults_whenCustomActivitiesJsonIsMalformed() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.leisureDataStore.edit { p ->
            p[stringPreferencesKey("custom_music_activities")] = ""
        }
        val config = prefs.getSlotConfig(LeisureSlotId.MUSIC).first()
        assertTrue(config.customActivities.isEmpty())
    }

    @Test
    fun setBuiltInHidden_recoversFromMalformedExistingValue() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.leisureDataStore.edit { p ->
            p[stringPreferencesKey("flex_hidden_builtins")] = "garbage"
        }
        // Must not throw despite the corrupt prior payload.
        prefs.setBuiltInHidden(LeisureSlotId.FLEX, "read", hidden = true)
        assertEquals(
            listOf("read"),
            prefs.getSlotConfig(LeisureSlotId.FLEX).first().hiddenBuiltInIds
        )
    }

    /**
     * Regression for Crashlytics issue 0fe0a45f5a45b88be3d81baa85c79c4a:
     * gson bypasses Kotlin non-null contracts via Unsafe + reflection, so
     * a JSON payload with a null String field deserializes to a
     * `CustomLeisureSection` with a literal Java null in a non-null Kotlin
     * property. Downstream `LeisureViewModel.toSlotState` then passed that
     * null straight to `LeisureSlotConfig.<init>`, where the
     * compiler-generated `getClass()` null check fired and crashed
     * LeisureScreen the moment the user navigated in. The sanitizer pass
     * in `readCustomSections` must drop or repair such items so the
     * non-null Kotlin contract holds end-to-end.
     */
    @Test
    fun getCustomSections_dropsRowsWhereGsonLeakedANullStringField() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.leisureDataStore.edit { p ->
            p[stringPreferencesKey("custom_sections")] = """
                [
                  {"id":"sec_1","label":"Reading","emoji":"X","enabled":true,
                   "durationMinutes":15,"gridColumns":2,"autoComplete":true,
                   "customActivities":[]},
                  {"id":"sec_2","label":null,"emoji":"Y","enabled":true,
                   "durationMinutes":15,"gridColumns":2,"autoComplete":true,
                   "customActivities":[]},
                  {"id":null,"label":"No identity","emoji":"Z","enabled":true,
                   "durationMinutes":15,"gridColumns":2,"autoComplete":true,
                   "customActivities":[]}
                ]
            """.trimIndent()
        }
        val sections = prefs.getCustomSections().first()
        // sec_1 keeps its label; sec_2's null label and the id-less row
        // are dropped loudly instead of silently coercing malformed data.
        assertEquals(1, sections.size)
        val byId = sections.associateBy { it.id }
        assertEquals("Reading", byId["sec_1"]?.label)
    }

    @Test
    fun getCustomSections_coercesOutOfRangeDurationAndColumns() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.leisureDataStore.edit { p ->
            p[stringPreferencesKey("custom_sections")] = """
                [{"id":"sec_3","label":"Bad","emoji":"!","enabled":true,
                  "durationMinutes":0,"gridColumns":0,"autoComplete":true,
                  "customActivities":[]}]
            """.trimIndent()
        }
        val sections = prefs.getCustomSections().first()
        assertEquals(1, sections.size)
        // Coerced so GridCells.Fixed and the timer math don't trip on zero.
        assertEquals(LeisurePreferences.MIN_DURATION_MINUTES, sections[0].durationMinutes)
        assertEquals(LeisurePreferences.MIN_GRID_COLUMNS, sections[0].gridColumns)
    }

    /**
     * Repro for the user-reported bug "Adding an option to a custom leisure
     * category deletes the category." Verifies the round-trip: create a
     * custom section, then call addCustomSectionActivity — the section must
     * still be present and now own the new activity. If
     * addCustomSectionActivity ever clobbers `custom_sections` (e.g. by
     * writing an empty list, or by writing only the activity), this test
     * fails.
     */
    @Test
    fun addCustomSectionActivity_preservesSection_andAppendsActivity() = runTest {
        val sectionId = prefs.addCustomSection("Reading", "📖")

        prefs.addCustomSectionActivity(sectionId, "Books", "📕")

        val sections = prefs.getCustomSections().first()
        assertEquals(1, sections.size)
        val section = sections.single()
        assertEquals(sectionId, section.id)
        assertEquals("Reading", section.label)
        assertEquals(1, section.customActivities.size)
        assertEquals("Books", section.customActivities.single().label)
    }

    /**
     * Defensive guard: if the target section is no longer present (e.g.
     * because a sync pull wiped `custom_sections` between dialog-open and
     * dialog-submit), the mutation must skip the write entirely instead of
     * writing back the unchanged list. The assignment would still trigger a
     * Preferences emission and noise the sync-fingerprint cache without
     * changing observable state. See
     * docs/audits/LEISURE_ADD_ACTIVITY_DELETES_SECTION_AUDIT.md (Phase 1
     * batch 2, Item 2').
     */
    @Test
    fun addCustomSectionActivity_skipsWriteWhenSectionMissing() = runTest {
        val a = prefs.addCustomSection("A", "🔵")
        val b = prefs.addCustomSection("B", "🟢")

        prefs.addCustomSectionActivity("nonexistent_id", "Phantom", "👻")

        val sections = prefs.getCustomSections().first()
        assertEquals(2, sections.size)
        val byId = sections.associateBy { it.id }
        assertTrue(byId[a]?.customActivities?.isEmpty() == true)
        assertTrue(byId[b]?.customActivities?.isEmpty() == true)
    }

    @Test
    fun removeCustomSectionActivity_skipsWriteWhenSectionMissing() = runTest {
        val a = prefs.addCustomSection("A", "🔵")
        prefs.addCustomSectionActivity(a, "Books", "📕")

        prefs.removeCustomSectionActivity("nonexistent_id", "any_activity_id")

        val sections = prefs.getCustomSections().first()
        assertEquals(1, sections.size)
        assertEquals(1, sections.single().customActivities.size)
        assertEquals("Books", sections.single().customActivities.single().label)
    }

    @Test
    fun updateCustomSection_skipsWriteWhenSectionMissing() = runTest {
        val a = prefs.addCustomSection("A", "🔵")

        prefs.updateCustomSection("nonexistent_id", enabled = false, label = "Renamed")

        val sections = prefs.getCustomSections().first()
        assertEquals(1, sections.size)
        // Original section unchanged.
        assertEquals(a, sections.single().id)
        assertEquals("A", sections.single().label)
        assertTrue(sections.single().enabled)
    }

    @Test
    fun getCustomSections_dropsActivitiesWithNullIdOrLabel() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.leisureDataStore.edit { p ->
            p[stringPreferencesKey("custom_sections")] = """
                [{"id":"sec_4","label":"Mixed","emoji":"X","enabled":true,
                  "durationMinutes":15,"gridColumns":2,"autoComplete":true,
                  "customActivities":[
                    {"id":"act_1","label":"Real","icon":"A"},
                    {"id":null,"label":"Phantom","icon":"B"},
                    {"id":"act_2","label":null,"icon":"C"}
                  ]}]
            """.trimIndent()
        }
        val sections = prefs.getCustomSections().first()
        // Any invalid activity drops the owning section loudly instead of
        // silently coercing or partially rewriting malformed data.
        assertTrue(sections.isEmpty())
    }
}
