package com.molotov.clender.widget

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.molotov.clender.app.widget.WidgetRefreshCompletion
import com.molotov.clender.domain.widget.WidgetRefreshTrigger
import kotlinx.coroutines.CancellationException

class WidgetDateBoundaryWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val owner = if (inputData.keyValueMap.isEmpty()) {
            applicationContext as? WidgetAutomaticRefreshOwner
        } else {
            null
        }
        return if (owner == null) Result.failure() else requestDateBoundary(owner)
    }

    private suspend fun requestDateBoundary(owner: WidgetAutomaticRefreshOwner): Result = try {
        val completion = owner.widgetAutomaticRefreshRuntime
            .request(WidgetRefreshTrigger.DATE_BOUNDARY).await()
        when (completion) {
            WidgetRefreshCompletion.COMPLETED,
            WidgetRefreshCompletion.NO_WIDGETS,
            WidgetRefreshCompletion.SUPERSEDED -> Result.success()

            WidgetRefreshCompletion.INVALID_TARGET,
            WidgetRefreshCompletion.CLOSED,
            WidgetRefreshCompletion.OWNERSHIP_FAILED,
            WidgetRefreshCompletion.UPDATE_FAILED,
            WidgetRefreshCompletion.SCHEDULE_FAILED,
            WidgetRefreshCompletion.TIME_OVERFLOW -> Result.failure()
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        Result.failure()
    }
}
