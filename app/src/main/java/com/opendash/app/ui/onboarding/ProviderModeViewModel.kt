package com.opendash.app.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opendash.app.data.preferences.AppPreferences
import com.opendash.app.data.preferences.PreferenceKeys
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProviderModeViewModel @Inject constructor(
    private val appPreferences: AppPreferences
) : ViewModel() {

    fun selectMode(mode: String) {
        viewModelScope.launch {
            appPreferences.set(PreferenceKeys.ASSISTANT_MODE, mode)
        }
    }
}
