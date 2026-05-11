package com.averycorp.prismtask.ui.screens.tags

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.averycorp.prismtask.data.local.entity.TagEntity
import com.averycorp.prismtask.data.repository.TagRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TagManagementViewModel
@Inject
constructor(private val tagRepository: TagRepository) : ViewModel() {
    val tags: StateFlow<List<TagEntity>> = tagRepository
        .getAllTags()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun onAddTag(name: String, color: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            try {
                tagRepository.addTag(name.trim(), color)
            } catch (e: Exception) {
                Log.e("TagManagementVM", "Failed to add tag", e)
            }
        }
    }

    fun onUpdateTag(tag: TagEntity) {
        viewModelScope.launch {
            try {
                tagRepository.updateTag(tag)
            } catch (e: Exception) {
                Log.e("TagManagementVM", "Failed to update tag", e)
            }
        }
    }

    fun onDeleteTag(tag: TagEntity) {
        viewModelScope.launch {
            try {
                tagRepository.deleteTag(tag)
            } catch (e: Exception) {
                Log.e("TagManagementVM", "Failed to delete tag", e)
            }
        }
    }
}
