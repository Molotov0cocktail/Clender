package com.molotov.clender.ui.settings

import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import com.molotov.clender.data.settings.AlarmSoundStore
import java.io.File

class AlarmSoundViewModel(store: AlarmSoundStore) : ViewModel() {
    val controller = AlarmSoundController(store, viewModelScope)
}

internal fun alarmSoundViewModel(context: Context): AlarmSoundViewModel {
    var ownerContext = context
    while (ownerContext !is ViewModelStoreOwner && ownerContext is ContextWrapper) {
        ownerContext = ownerContext.baseContext
    }
    val owner = requireNotNull(ownerContext as? ViewModelStoreOwner) { "Activity owner required" }
    val application = context.applicationContext
    return ViewModelProvider(
        owner,
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T = requireNotNull(
                modelClass.cast(
                    AlarmSoundViewModel(AlarmSoundStore(File(application.filesDir, "alarm-sound")))
                )
            )
        }
    )[AlarmSoundViewModel::class.java]
}
