package com.molotov.clender.ui.theme

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.molotov.clender.data.settings.BackgroundSelection
import com.molotov.clender.data.settings.BackgroundStore
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Main-thread UI state; all content and private-file access runs on Dispatchers.IO. */
class BackgroundController(
    private val store: BackgroundStore,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    var selection by mutableStateOf(BackgroundSelection())
        private set
    var bitmap by mutableStateOf<Bitmap?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set

    fun load() = update(reloadImage = true) { store.read() }

    fun choose(resolver: ContentResolver, uri: Uri) = update(reloadImage = true) {
        require(uri.scheme == "content") { "Unsupported image location" }
        store.importImage { resolver.openInputStream(uri) }
    }

    fun remove() = update(reloadImage = true) { store.remove() }

    fun setStrength(value: Int) = update(reloadImage = false) { store.saveStrength(value) }

    private fun update(reloadImage: Boolean, action: () -> BackgroundSelection) {
        if (busy) return
        busy = true
        failed = false
        val previousBitmap = bitmap
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val result = withContext(ioDispatcher) {
                    val selected = action()
                    selected to if (reloadImage) store.loadBitmap(selected) else previousBitmap
                }
                selection = result.first
                bitmap = result.second
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                failed = true
            } catch (_: SecurityException) {
                failed = true
            } catch (_: IllegalArgumentException) {
                failed = true
            } catch (_: IllegalStateException) {
                failed = true
            } finally {
                busy = false
            }
        }
    }
}
