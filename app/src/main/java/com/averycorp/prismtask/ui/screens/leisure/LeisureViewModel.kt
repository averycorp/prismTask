package com.averycorp.prismtask.ui.screens.leisure

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.averycorp.prismtask.data.local.entity.LeisureLogEntity
import com.averycorp.prismtask.data.preferences.CustomLeisureSection
import com.averycorp.prismtask.data.preferences.LeisurePreferences
import com.averycorp.prismtask.data.preferences.LeisureSlotConfig
import com.averycorp.prismtask.data.preferences.LeisureSlotId
import com.averycorp.prismtask.data.repository.LeisureRepository
import com.averycorp.prismtask.ui.screens.leisure.components.LeisureOption
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Identifies which leisure section a [LeisureSlotState] belongs to. Built-in
 * music / flex keep their enum; user-added sections carry a string id.
 */
sealed class LeisureSectionKey {
    data class BuiltIn(val slot: LeisureSlotId) : LeisureSectionKey()

    data class Custom(val id: String) : LeisureSectionKey()
}

/**
 * UI state for one leisure slot. [options] is the fully-merged activity list
 * (built-ins minus hidden + user-added customs), ready to render.
 */
data class LeisureSlotState(
    val key: LeisureSectionKey,
    val config: LeisureSlotConfig,
    val options: List<LeisureOption>,
    val picked: String?,
    val done: Boolean
) {
    /** Convenience accessor for code paths that only handle built-ins. */
    val builtInSlot: LeisureSlotId? = (key as? LeisureSectionKey.BuiltIn)?.slot
}

@HiltViewModel
class LeisureViewModel
@Inject
constructor(private val repository: LeisureRepository, private val leisurePreferences: LeisurePreferences) :
    ViewModel() {
    val todayLog: StateFlow<LeisureLogEntity?> = repository
        .getTodayLog()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val musicSlot: StateFlow<LeisureSlotState> = builtInSlotFlow(LeisureSlotId.MUSIC)
    val flexSlot: StateFlow<LeisureSlotState> = builtInSlotFlow(LeisureSlotId.FLEX)
    val languageSlot: StateFlow<LeisureSlotState> = builtInSlotFlow(LeisureSlotId.LANGUAGE)

    val customSlots: StateFlow<List<LeisureSlotState>> = combine(
        leisurePreferences.getCustomSections(),
        repository.getTodayLog()
    ) { sections, log ->
        val states = repository.readCustomSectionStates(log)
        sections.map { section -> section.toSlotState(states[section.id]) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private fun builtInSlotFlow(slot: LeisureSlotId): StateFlow<LeisureSlotState> = combine(
        leisurePreferences.getSlotConfig(slot),
        repository.getTodayLog()
    ) { config, log ->
        val defaults = defaultsFor(slot).filter { it.id !in config.hiddenBuiltInIds }
        val customs = config.customActivities.map { LeisureOption(it.id, it.label, it.icon) }
        LeisureSlotState(
            key = LeisureSectionKey.BuiltIn(slot),
            config = config,
            options = defaults + customs,
            picked = when (slot) {
                LeisureSlotId.MUSIC -> log?.musicPick
                LeisureSlotId.FLEX -> log?.flexPick
                LeisureSlotId.LANGUAGE -> log?.languagePick
            },
            done = when (slot) {
                LeisureSlotId.MUSIC -> log?.musicDone == true
                LeisureSlotId.FLEX -> log?.flexDone == true
                LeisureSlotId.LANGUAGE -> log?.languageDone == true
            }
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        LeisureSlotState(
            key = LeisureSectionKey.BuiltIn(slot),
            config = LeisureSlotConfig.defaultFor(slot),
            options = defaultsFor(slot),
            picked = null,
            done = false
        )
    )

    fun pickActivity(key: LeisureSectionKey, activityId: String) {
        viewModelScope.launch {
            when (key) {
                is LeisureSectionKey.BuiltIn -> when (key.slot) {
                    LeisureSlotId.MUSIC -> repository.setMusicPick(activityId)
                    LeisureSlotId.FLEX -> repository.setFlexPick(activityId)
                    LeisureSlotId.LANGUAGE -> repository.setLanguagePick(activityId)
                }
                is LeisureSectionKey.Custom -> repository.setCustomSectionPick(key.id, activityId)
            }
        }
    }

    fun toggleDone(key: LeisureSectionKey, done: Boolean) {
        viewModelScope.launch {
            when (key) {
                is LeisureSectionKey.BuiltIn -> when (key.slot) {
                    LeisureSlotId.MUSIC -> repository.toggleMusicDone(done)
                    LeisureSlotId.FLEX -> repository.toggleFlexDone(done)
                    LeisureSlotId.LANGUAGE -> repository.toggleLanguageDone(done)
                }
                is LeisureSectionKey.Custom -> repository.toggleCustomSectionDone(key.id, done)
            }
        }
    }

    fun clearPick(key: LeisureSectionKey) {
        viewModelScope.launch {
            when (key) {
                is LeisureSectionKey.BuiltIn -> when (key.slot) {
                    LeisureSlotId.MUSIC -> repository.clearMusicPick()
                    LeisureSlotId.FLEX -> repository.clearFlexPick()
                    LeisureSlotId.LANGUAGE -> repository.clearLanguagePick()
                }
                is LeisureSectionKey.Custom -> repository.clearCustomSectionPick(key.id)
            }
        }
    }

    fun resetToday() {
        viewModelScope.launch { repository.resetToday() }
    }

    fun addActivity(key: LeisureSectionKey, label: String, icon: String) {
        viewModelScope.launch {
            when (key) {
                is LeisureSectionKey.BuiltIn -> leisurePreferences.addActivity(key.slot, label, icon)
                is LeisureSectionKey.Custom -> leisurePreferences.addCustomSectionActivity(key.id, label, icon)
            }
        }
    }

    fun removeActivity(key: LeisureSectionKey, id: String) {
        viewModelScope.launch {
            when (key) {
                is LeisureSectionKey.BuiltIn -> leisurePreferences.removeActivity(key.slot, id)
                is LeisureSectionKey.Custom -> leisurePreferences.removeCustomSectionActivity(key.id, id)
            }
        }
    }

    fun isCustomActivity(id: String): Boolean = id.startsWith("custom_")

    private fun CustomLeisureSection.toSlotState(
        state: LeisureRepository.CustomSectionState?
    ): LeisureSlotState = LeisureSlotState(
        key = LeisureSectionKey.Custom(id),
        config = LeisureSlotConfig(
            enabled = enabled,
            label = label,
            emoji = emoji,
            durationMinutes = durationMinutes,
            gridColumns = gridColumns,
            autoComplete = autoComplete,
            hiddenBuiltInIds = emptyList(),
            customActivities = customActivities
        ),
        options = customActivities.map { LeisureOption(it.id, it.label, it.icon) },
        picked = state?.pick,
        done = state?.done == true
    )

    companion object {
        val DEFAULT_INSTRUMENTS = listOf(
            LeisureOption("bass", "Bass", "\uD83C\uDFB8"),
            LeisureOption("guitar", "Guitar", "\uD83C\uDFB8"),
            LeisureOption("drums", "Drums", "\uD83E\uDD41"),
            LeisureOption("piano", "Piano", "\uD83C\uDFB9"),
            LeisureOption("singing", "Singing", "\uD83C\uDFA4")
        )

        val DEFAULT_FLEX_OPTIONS = listOf(
            LeisureOption("read", "Read", "\uD83D\uDCD6"),
            LeisureOption("gaming", "Gaming", "\uD83C\uDFAE"),
            LeisureOption("cook", "Cook something new", "\uD83C\uDF73"),
            LeisureOption("watch", "Watch a show or movie", "\uD83D\uDCFA"),
            LeisureOption("boardgame", "Board game / puzzle", "\uD83E\uDDE9")
        )

        val DEFAULT_LANGUAGE_OPTIONS = listOf(
            LeisureOption("italian", "Italian", "\uD83C\uDDEE\uD83C\uDDF9"),
            LeisureOption("french", "French", "\uD83C\uDDEB\uD83C\uDDF7"),
            LeisureOption("spanish", "Spanish", "\uD83C\uDDEA\uD83C\uDDF8"),
            LeisureOption("german", "German", "\uD83C\uDDE9\uD83C\uDDEA"),
            LeisureOption("chinese", "Chinese", "\uD83C\uDDE8\uD83C\uDDF3")
        )

        fun defaultsFor(slot: LeisureSlotId): List<LeisureOption> = when (slot) {
            LeisureSlotId.MUSIC -> DEFAULT_INSTRUMENTS
            LeisureSlotId.FLEX -> DEFAULT_FLEX_OPTIONS
            LeisureSlotId.LANGUAGE -> DEFAULT_LANGUAGE_OPTIONS
        }
    }
}
