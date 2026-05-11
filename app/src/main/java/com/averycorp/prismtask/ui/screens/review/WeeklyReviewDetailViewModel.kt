package com.averycorp.prismtask.ui.screens.review

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.averycorp.prismtask.data.local.dao.WeeklyReviewDao
import com.averycorp.prismtask.data.local.entity.WeeklyReviewEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WeeklyReviewDetailViewModel
@Inject
constructor(savedStateHandle: SavedStateHandle, private val dao: WeeklyReviewDao) : ViewModel() {
    private val reviewId: Long = savedStateHandle.get<String>("reviewId")?.toLongOrNull() ?: -1L

    private val _review = MutableStateFlow<WeeklyReviewEntity?>(null)
    val review: StateFlow<WeeklyReviewEntity?> = _review.asStateFlow()

    init {
        if (reviewId >= 0) {
            viewModelScope.launch {
                _review.value = dao.getByIdOnce(reviewId)
            }
        }
    }
}
