package com.leeotts.cicero

import android.app.Application
import android.content.Context
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.leeotts.cicero.glasses.GlassesController
import com.leeotts.cicero.glasses.MockGlassesSupport
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.PermissionStatus
import kotlinx.coroutines.Dispatchers
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

    fun onCameraPermission(status: PermissionStatus) {
        _cameraGranted.value = status == PermissionStatus.Granted
    }

    fun onPermissionFailure(description: String) {
        _status.value = getApplication<Application>()
            .getString(R.string.glasses_permission_failed, description)
    }

    fun enableMock(context: Context) {
        _status.value = MockGlassesSupport.enable(context)
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
                // Meta AI's own features on the glasses.
                glasses.release()
                _busy.value = false
            }
        }
    }

    /** The single owner of teardown, now that the controller outlives the Activity. */
    override fun onCleared() {
        glasses.release()
        super.onCleared()
    }
}
