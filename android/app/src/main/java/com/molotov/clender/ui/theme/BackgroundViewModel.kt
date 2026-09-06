package com.molotov.clender.ui.theme

import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import com.molotov.clender.data.settings.BackgroundStore
import java.io.File

/** Activity-retained owner: image imports and busy state survive configuration changes. */
class BackgroundViewModel(store: BackgroundStore) : ViewModel() {
    val controller = BackgroundController(store, viewModelScope)

    init {
        controller.load()
    }
}

internal fun backgroundViewModel(context: Context): BackgroundViewModel {
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
                    BackgroundViewModel(BackgroundStore(File(application.filesDir, "background")))
                )
            )
        }
    )[BackgroundViewModel::class.java]
}
