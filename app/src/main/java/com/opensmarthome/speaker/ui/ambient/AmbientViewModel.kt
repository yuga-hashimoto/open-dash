package com.opensmarthome.speaker.ui.ambient

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opensmarthome.speaker.device.DeviceManager
import com.opensmarthome.speaker.device.model.DeviceType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AmbientViewModel @Inject constructor(
    private val deviceManager: DeviceManager
) : ViewModel() {

    private val _weatherState = MutableStateFlow("")
    val weatherState: StateFlow<String> = _weatherState.asStateFlow()

    private val _temperature = MutableStateFlow("")
    val temperature: StateFlow<String> = _temperature.asStateFlow()

    private val _humidity = MutableStateFlow("")
    val humidity: StateFlow<String> = _humidity.asStateFlow()

    init {
        viewModelScope.launch {
            deviceManager.devices.collect { devices ->
                val weather = devices.values.firstOrNull {
                    it.state.temperature != null && it.state.humidity != null
                }
                if (weather != null) {
                    _weatherState.value = weather.state.attributes["state"] as? String ?: ""
                    _temperature.value = weather.state.temperature?.toString() ?: ""
                    _humidity.value = weather.state.humidity?.toString() ?: ""
                }
            }
        }
    }
}
