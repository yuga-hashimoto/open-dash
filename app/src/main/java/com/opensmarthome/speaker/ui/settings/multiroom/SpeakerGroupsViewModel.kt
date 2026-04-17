package com.opensmarthome.speaker.ui.settings.multiroom

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opensmarthome.speaker.multiroom.SpeakerGroup
import com.opensmarthome.speaker.multiroom.SpeakerGroupRepository
import com.opensmarthome.speaker.util.DiscoveredSpeaker
import com.opensmarthome.speaker.util.MulticastDiscovery
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs [SpeakerGroupsScreen]. Exposes the live list of persisted groups
 * joined with the latest discovered-peer snapshot so the UI can render
 * membership as "in group / discovered but not in group / discovered
 * elsewhere".
 *
 * All mutation goes through [SpeakerGroupRepository]; the viewmodel only
 * coordinates state, it doesn't own the storage model.
 */
@HiltViewModel
class SpeakerGroupsViewModel @Inject constructor(
    private val repository: SpeakerGroupRepository,
    discovery: MulticastDiscovery
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val groups: List<SpeakerGroup> = emptyList(),
        val discoveredPeers: List<DiscoveredSpeaker> = emptyList(),
        /** The group currently open in the edit sheet, or null. */
        val editing: SpeakerGroup? = null
    )

    private val editing = MutableStateFlow<SpeakerGroup?>(null)

    val state: StateFlow<UiState> = combine(
        repository.flow(),
        discovery.speakers,
        editing
    ) { groups, peers, edit ->
        UiState(
            loading = false,
            groups = groups,
            discoveredPeers = peers,
            editing = edit?.let { e -> groups.firstOrNull { it.name == e.name } ?: e }
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = UiState()
    )

    private val _snackbar = MutableStateFlow<String?>(null)
    val snackbar: StateFlow<String?> = _snackbar.asStateFlow()

    fun createGroup(name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) {
            _snackbar.value = "Group name can't be blank"
            return
        }
        viewModelScope.launch {
            if (repository.get(trimmed) != null) {
                _snackbar.value = "Group '$trimmed' already exists"
                return@launch
            }
            repository.save(SpeakerGroup(name = trimmed, memberServiceNames = emptySet()))
        }
    }

    fun deleteGroup(name: String) {
        viewModelScope.launch {
            repository.delete(name)
            if (editing.value?.name == name) editing.value = null
        }
    }

    fun openEditor(group: SpeakerGroup) {
        editing.value = group
    }

    fun closeEditor() {
        editing.value = null
    }

    fun toggleMember(group: SpeakerGroup, serviceName: String) {
        val next = if (serviceName in group.memberServiceNames) {
            group.memberServiceNames - serviceName
        } else {
            group.memberServiceNames + serviceName
        }
        val updated = group.copy(memberServiceNames = next)
        editing.value = updated
        viewModelScope.launch { repository.save(updated) }
    }

    fun consumeSnackbar() {
        _snackbar.value = null
    }
}
