package com.averycorp.prismtask.ui.screens.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.averycorp.prismtask.data.local.entity.WeeklyReviewEntity
import com.averycorp.prismtask.data.repository.WeeklyReviewRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Drives [WeeklyReviewsListScreen]. Reads the full history straight from
 * the repository's Flow so rows stream in as [WeeklyReviewWorker] persists
 * them and as sync pulls remote rows.
 */
@HiltViewModel
class WeeklyReviewsListViewModel
@Inject
constructor(repository: WeeklyReviewRepository) : ViewModel() {
    val reviews: StateFlow<List<WeeklyReviewEntity>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
