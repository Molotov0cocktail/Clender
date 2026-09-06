package com.molotov.clender.app.widget

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Starts collecting synchronously before the first restore update can mutate the schedule. */
internal class WidgetRefreshObservation(
    private val scope: CoroutineScope,
    private val owner: Job,
    private val version: StateFlow<Long>,
    private val request: () -> Unit
) {
    private var collector: Job? = null

    fun reconcile(hasWidgets: Boolean) {
        if (!hasWidgets) {
            collector?.cancel()
            collector = null
        } else if (collector?.isActive != true) {
            collector = scope.launch(context = owner, start = CoroutineStart.UNDISPATCHED) {
                var previous = version.value
                version.collect { current ->
                    if (current > previous) request()
                    previous = current
                }
            }
        }
    }
}
