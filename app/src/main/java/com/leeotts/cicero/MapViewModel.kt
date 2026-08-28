package com.leeotts.cicero

import android.app.Application
import android.location.Location
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.leeotts.cicero.location.Place
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The Map screen's state: where the user is, and the last place the assistant
 * was asked about.
 *
 * Both come from Application-scoped holders, so a fix taken by the where_am_i
 * tool and the dot drawn on the map are the same fix.
 */
class MapViewModel(app: Application) : AndroidViewModel(app) {

    private val provider = getApplication<CiceroApp>().location

    val destination: StateFlow<Place?> = getApplication<CiceroApp>().destinations.last

    private val _here = MutableStateFlow<Location?>(null)
    val here: StateFlow<Location?> = _here.asStateFlow()

    private val _locating = MutableStateFlow(false)
    val locating: StateFlow<Boolean> = _locating.asStateFlow()

    fun hasPermission(): Boolean = provider.hasPermission()

    /** Called when the screen appears and when the user asks to re-centre. */
    fun refresh() {
        if (_locating.value || !provider.hasPermission()) return
        viewModelScope.launch {
            _locating.value = true
            try {
                _here.value = provider.current()
            } finally {
                _locating.value = false
            }
        }
    }
}
