package com.carya.jm.ui.download

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.carya.jm.data.download.DownloadManager
import com.carya.jm.data.download.DownloadProgress
import com.carya.jm.data.download.DownloadService
import com.carya.jm.data.download.DownloadedComic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DownloadUiState(
    val downloads: List<DownloadedComic> = emptyList(),
    /** 正在下载的漫画列表（支持多本并发）。 */
    val activeDownloads: List<DownloadProgress> = emptyList(),
    val isLoading: Boolean = false,
    val selectedIds: Set<String> = emptySet(),
    val isMultiSelectMode: Boolean = false,
)

class DownloadViewModel : ViewModel() {

    private val _state = MutableStateFlow(DownloadUiState(isLoading = true))
    val state: StateFlow<DownloadUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            DownloadService.activeDownloads.collect { map ->
                val active = map.values.filter { it.isDownloading }
                val completed = map.values.any { it.completed }
                _state.value = _state.value.copy(activeDownloads = active)
                // 有下载完成时刷新已下载列表（进度条目 3 秒后由服务清理）
                if (completed) loadDownloads()
            }
        }
    }

    fun loadDownloads() {
        _state.value = _state.value.copy(isLoading = true)
        viewModelScope.launch {
            val downloads = withContext(Dispatchers.IO) {
                DownloadManager.get().getDownloads()
            }
            _state.value = _state.value.copy(downloads = downloads, isLoading = false)
        }
    }

    fun toggleSelection(albumId: String) {
        val current = _state.value
        val newSelected = if (albumId in current.selectedIds) {
            current.selectedIds - albumId
        } else {
            current.selectedIds + albumId
        }
        _state.value = current.copy(
            selectedIds = newSelected,
            isMultiSelectMode = newSelected.isNotEmpty(),
        )
    }

    fun clearSelection() {
        _state.value = _state.value.copy(
            selectedIds = emptySet(),
            isMultiSelectMode = false,
        )
    }

    fun deleteSelected() {
        val ids = _state.value.selectedIds.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                DownloadManager.get().removeDownloads(ids)
            }
            _state.value = _state.value.copy(
                downloads = _state.value.downloads.filter { it.albumId !in ids.toSet() },
                selectedIds = emptySet(),
                isMultiSelectMode = false,
            )
        }
    }

    fun deleteSingle(albumId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                DownloadManager.get().removeDownload(albumId)
            }
            _state.value = _state.value.copy(
                downloads = _state.value.downloads.filter { it.albumId != albumId },
            )
        }
    }
}
