package com.kawaiipet.app.ui

import androidx.lifecycle.ViewModel
import com.kawaiipet.app.assets.AssetDownloadManager
import com.kawaiipet.app.assets.AssetDownloadState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import androidx.lifecycle.viewModelScope

@HiltViewModel
class AssetsBootstrapViewModel @Inject constructor(
    private val assetDownloadManager: AssetDownloadManager,
) : ViewModel() {

    val state: StateFlow<AssetDownloadState> = assetDownloadManager.state
        .stateIn(viewModelScope, SharingStarted.Eagerly, AssetDownloadState.Checking)

    init {
        assetDownloadManager.refresh()
    }

    fun ensureAssets() {
        assetDownloadManager.ensureAssetsDownloadedAsync()
    }

    fun retry() {
        assetDownloadManager.ensureAssetsDownloadedAsync()
    }
}
