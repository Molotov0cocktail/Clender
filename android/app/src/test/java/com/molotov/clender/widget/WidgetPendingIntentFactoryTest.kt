package com.molotov.clender.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.molotov.clender.domain.widget.WidgetActionSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class WidgetPendingIntentFactoryTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun configurePendingIntentIsExplicitImmutableAndCarriesOnlyCanonicalIdentityAndWidgetId() {
        val appWidgetId = 73
        val pendingIntent = WidgetPendingIntentFactory.create(
            context,
            WidgetActionSpec.Configure(appWidgetId)
        )
        val shadow = shadowOf(pendingIntent)
        val intent = shadow.savedIntent

        assertTrue(shadow.isActivity)
        assertFalse(shadow.isBroadcast)
        assertFalse(shadow.isService)
        assertEquals(0, shadow.requestCode)
        assertEquals(
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            shadow.flags
        )
        assertTrue(shadow.isImmutable)
        assertEquals(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE, intent.action)
        assertEquals(
            ComponentName(context, WidgetConfigurationActivity::class.java),
            intent.component
        )
        assertEquals(context.packageName, intent.`package`)
        assertEquals(
            WidgetActionSpec.Configure(appWidgetId).canonicalIdentity,
            intent.dataString
        )
        assertEquals(
            WidgetActionSpec.Configure(appWidgetId),
            WidgetActionSpec.parse(intent.dataString)
        )
        assertEquals(
            appWidgetId,
            intent.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID
            )
        )
        assertEquals(setOf(AppWidgetManager.EXTRA_APPWIDGET_ID), intent.extras?.keySet())
        assertEquals(null, intent.categories)
        assertEquals(null, intent.clipData)
        assertEquals(0, intent.selector?.extras?.size() ?: 0)
    }

    @Test
    fun canonicalDataMakesInstancesUniqueWhileRepeatedCreationReusesTheSameToken() {
        val first = WidgetPendingIntentFactory.create(context, WidgetActionSpec.Configure(41))
        val repeated = WidgetPendingIntentFactory.create(context, WidgetActionSpec.Configure(41))
        val other = WidgetPendingIntentFactory.create(context, WidgetActionSpec.Configure(42))

        assertEquals(first, repeated)
        assertEquals(first.hashCode(), repeated.hashCode())
        assertNotEquals(first, other)
        assertNotEquals(
            shadowOf(first).savedIntent.dataString,
            shadowOf(other).savedIntent.dataString
        )
    }

    @Test
    fun nonPositiveIdsFailBeforeAnyPlatformPendingIntentCanBeCreated() {
        listOf(0, -1, Int.MIN_VALUE).forEach { appWidgetId ->
            assertThrows(IllegalArgumentException::class.java) {
                WidgetPendingIntentFactory.configure(context, appWidgetId)
            }
        }
    }

    @Test
    fun quickAiUsesItsOwnStableActivityTokenAcrossInstancesAndActions() {
        val first = WidgetPendingIntentFactory.create(context, WidgetActionSpec.QuickAi(7))
        assertEquals(
            first,
            WidgetPendingIntentFactory.create(context, WidgetActionSpec.QuickAi(7))
        )
        listOf(
            WidgetActionSpec.QuickAi(8),
            WidgetActionSpec.Configure(7),
            WidgetActionSpec.EditEvent(7, 1),
            WidgetActionSpec.LocalRefresh(7)
        ).forEach { action ->
            assertNotEquals(first, WidgetPendingIntentFactory.create(context, action))
        }
    }
}
