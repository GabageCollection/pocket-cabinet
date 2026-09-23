package com.ambercabinet.ui.components

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ambercabinet.core.data.repo.CabinetRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class DockViewModel @Inject constructor(
    cabinet: CabinetRepository
) : ViewModel() {
    /** 有没有未完成的调酒草稿：有就在「发现」tab 上点个小红点提醒 */
    val hasDraft: StateFlow<Boolean> = cabinet.latestDraft
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
}
