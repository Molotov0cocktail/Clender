package com.molotov.clender.app.widget

import com.molotov.clender.domain.widget.WidgetConfiguration
import kotlinx.coroutines.CancellationException

interface WidgetConfigurationRuntimePort {
    suspend fun loadConfiguration(appWidgetId: Int): WidgetConfiguration?

    suspend fun widgetFontSizeSp(): Int

    suspend fun saveConfiguration(configuration: WidgetConfiguration)

    suspend fun requestWidgetUpdate(appWidgetId: Int)
}

class WidgetConfigurationApplicationService(private val runtime: WidgetConfigurationRuntimePort) {
    suspend fun load(appWidgetId: Int): WidgetConfiguration {
        require(appWidgetId > 0) { "Widget ID must be positive" }
        return runtime.loadConfiguration(appWidgetId)
            ?: WidgetConfiguration.defaults(appWidgetId, runtime.widgetFontSizeSp())
    }

    suspend fun save(configuration: WidgetConfiguration) {
        runtime.saveConfiguration(configuration)
        try {
            runtime.requestWidgetUpdate(configuration.appWidgetId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The configuration is already durable. A finite local rebuild failure must not roll it back.
        }
    }
}
