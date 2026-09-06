package com.molotov.clender.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class PlatformWidgetOwnedIdsTest {
    @Test
    fun optionalOwnedInstancesAreDiscoveredWithoutConfigurationRegistry() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val manager = shadowOf(AppWidgetManager.getInstance(app))
        listOf(71, 72).forEach { id ->
            manager.addBoundWidget(
                id,
                AppWidgetProviderInfo().apply {
                    provider = ComponentName(app, ClenderWidgetProvider::class.java)
                }
            )
        }
        manager.addBoundWidget(
            99,
            AppWidgetProviderInfo().apply {
                provider = ComponentName(app.packageName, "other.Provider")
            }
        )

        assertEquals(setOf(71, 72), PlatformWidgetOwnedIds(app).ownedIds())
        assertFalse(app.filesDir.resolve("datastore").exists())
        assertFalse(app.getDatabasePath("clender.db").exists())
    }

    @Test
    fun noBindingsReturnEmptyWithoutRuntimeFiles() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        assertEquals(emptySet<Int>(), PlatformWidgetOwnedIds(app).ownedIds())
        assertFalse(app.filesDir.resolve("datastore").exists())
        assertFalse(app.getDatabasePath("clender.db").exists())
    }
}
