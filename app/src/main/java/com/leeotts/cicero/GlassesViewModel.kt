package com.leeotts.cicero

import android.app.Application
import android.content.Context
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.leeotts.cicero.audio.ScoProbe
import com.leeotts.cicero.glasses.GlassesController
import com.leeotts.cicero.glasses.MockGlassesSupport
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.PermissionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Everything the UI knows about the glasses.
 *
 * Registration and permission state used to live as `mutableStateOf` properties
 * on the Activity, so they were lost on every rotation. As flows here they
 * survive, and they can feed a status indicator anywhere in the app.
 */
class GlassesViewModel(app: Application) : AndroidViewModel(app) {

    private val glasses = getApplication<CiceroApp>().glasses

    val glassesState: StateFlow<GlassesController.State> = glasses.state

    val registration = Wearables.registrationState
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _cameraGranted = MutableStateFlow(false)
    val cameraGranted: StateFlow<Boolean> = _cameraGranted.asStateFlow()

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _photo = MutableStateFlow<ImageBitmap?>(null)
    val photo: StateFlow<ImageBitmap?> = _photo.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _mockEnabled = MutableStateFlow(false)
    val mockEnabled: StateFlow<Boolean> = _mockEnabled.asStateFlow()

    private val _probing = MutableStateFlow(false)
    val probing: StateFlow<Boolean> = _probing.asStateFlow()

    /** Null until the probe has been run at least once this session. */
    private val _micProbe = MutableStateFlow<List<ScoProbe.Result>?>(null)
    val micProbe: StateFlow<List<ScoProbe.Result>?> = _micProbe.asStateFlow()

    fun onCameraPermission(status: PermissionStatus) {
        _cameraGranted.value = status == PermissionStatus.Granted
    }

    fun onPermissionFailure(description: String) {
        _status.value = getApplication<Application>()
            .getString(R.string.glasses_permission_failed, description)
    }

    fun enableMock(context: Context) {
        _status.value = MockGlassesSupport.enable(context)
        _mockEnabled.value = true
    }

    fun disableMock(context: Context) {
        MockGlassesSupport.disable(context)
        _mockEnabled.value = false
        _status.value = getApplication<Application>().getString(R.string.glasses_mock_disabled)
    }

    /**
     * The Phase 2 spike, on a button: what sample rate does the glasses
     * microphone actually deliver over Bluetooth HFP? 8 kHz narrowband and
     * 16 kHz wideband call for different wake-word engines, and the docs only
     * promise the former. Real glasses only - MockDeviceKit does not simulate
     * Bluetooth audio.
     */
    fun probeMic() {
        if (_probing.value) return
        viewModelScope.launch {
            _probing.value = true
            _micProbe.value = null
            try {
                _micProbe.value = ScoProbe(getApplication()).probe()
            } finally {
                _probing.value = false
            }
        }
    }

    fun capture() {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                val bitmap = glasses.capture()
                if (bitmap != null) {
                    _photo.value = withContext(Dispatchers.Default) { bitmap.asImageBitmap() }
                }
            } finally {
                // Sessions are short-lived on purpose: holding one open suspends
                // Meta AI's own features on the glasses. NonCancellable because
                // release() now suspends on the controller's lock, and a
                // cancelled scope would skip the teardown entirely.
                withContext(NonCancellable) { glasses.release() }
                _busy.value = false
            }
        }
    }

    /** The single owner of teardown, now that the controller outlives the Activity. */
    override fun onCleared() {
        // Async because release() suspends on the controller's lock and this
        // cannot: viewModelScope is already cancelled by the time we get here.
        glasses.releaseAsync()
        super.onCleared()
    }
}
