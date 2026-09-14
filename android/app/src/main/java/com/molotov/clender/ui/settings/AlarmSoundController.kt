package com.molotov.clender.ui.settings

import android.content.ActivityNotFoundException
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.molotov.clender.data.settings.AlarmSoundStore
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Main-thread state; picker tickets and cancellation guard every IO operation. */
class AlarmSoundController(
    private val store: AlarmSoundStore,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    var selected by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set
    private var active = false
    private var generation = 0L
    private var pickerTicket: Long? = null
    private var job: Job? = null

    fun enter() {
        if (active) return
        active = true
        update { store.selectedFile() != null }
    }

    fun leave() {
        active = false
        generation += 1
        pickerTicket = null
        job?.cancel()
        job = null
        busy = false
    }

    fun beginSelection(): Long? {
        if (!active || busy) return null
        generation += 1
        pickerTicket = generation
        busy = true
        failed = false
        return pickerTicket
    }

    fun launchSelection(launch: (Long, Array<String>) -> Unit) {
        val ticket = beginSelection() ?: return
        try {
            launch(ticket, arrayOf("audio/*"))
        } catch (_: ActivityNotFoundException) {
            selectionFailed(ticket)
        } catch (_: SecurityException) {
            selectionFailed(ticket)
        }
    }

    private fun selectionFailed(ticket: Long) {
        if (!active || pickerTicket != ticket) return
        pickerTicket = null
        busy = false
        failed = true
    }

    fun choose(ticket: Long, open: (() -> InputStream?)?) {
        if (!active || pickerTicket != ticket) return
        pickerTicket = null
        if (open == null) {
            busy = false
        } else {
            update { checkActive ->
                store.importSound(open, checkActive)
                true
            }
        }
    }

    fun remove() {
        if (!active || busy) return
        update { checkActive ->
            store.remove(checkActive)
            false
        }
    }

    private fun update(action: (() -> Unit) -> Boolean) {
        generation += 1
        val request = generation
        busy = true
        failed = false
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = withContext(ioDispatcher) {
                    val context = currentCoroutineContext()
                    action { context.ensureActive() }
                }
                if (active && generation == request) selected = result
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                reportFailure(request)
            } catch (_: SecurityException) {
                reportFailure(request)
            } catch (_: IllegalArgumentException) {
                reportFailure(request)
            } catch (_: IllegalStateException) {
                reportFailure(request)
            } finally {
                if (generation == request) {
                    busy = false
                    job = null
                }
            }
        }
        job?.start()
    }

    private fun reportFailure(request: Long) {
        if (active && generation == request) failed = true
    }
}
