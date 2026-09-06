package com.molotov.clender.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.SizeF
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.molotov.clender.domain.calendar.EventTemporalState
import com.molotov.clender.domain.widget.WidgetActionSpec
import com.molotov.clender.domain.widget.WidgetPresentationPolicy.SizeClass
import com.molotov.clender.domain.widget.WidgetThemeMode
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class WidgetP2B2aPendingIntentFactoryTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun configureRemainsTheExactP2B1ActivityToken() {
        val token = WidgetPendingIntentFactory.create(context, WidgetActionSpec.Configure(73))
        val shadow = shadowOf(token)
        val intent = shadow.savedIntent

        assertTrue(shadow.isActivity)
        assertFalse(shadow.isBroadcast)
        assertFalse(shadow.isService)
        assertEquals(0, shadow.requestCode)
        assertEquals(EXACT_PENDING_INTENT_FLAGS, shadow.flags)
        assertEquals(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE, intent.action)
        assertEquals(
            ComponentName(context, WidgetConfigurationActivity::class.java),
            intent.component
        )
        assertExactCommonIntent(
            intent,
            widgetId = 73,
            expectedData = "clender-internal://widget/73/configure"
        )
        assertEquals(0, intent.flags)
    }

    @Test
    fun editEventIsExplicitImmutableUpdateCurrentAndUsesOnlyCanonicalEventIdentity() {
        listOf(1, Int.MAX_VALUE).forEach { eventId ->
            val token = WidgetPendingIntentFactory.create(
                context,
                WidgetActionSpec.EditEvent(widgetId = 41, eventId = eventId)
            )
            val shadow = shadowOf(token)
            val intent = shadow.savedIntent

            assertTrue(shadow.isActivity)
            assertFalse(shadow.isBroadcast)
            assertFalse(shadow.isService)
            assertTrue(shadow.isImmutable)
            assertEquals(0, shadow.requestCode)
            assertEquals(EXACT_PENDING_INTENT_FLAGS, shadow.flags)
            assertEquals("com.molotov.clender.action.WIDGET_EDIT_EVENT", intent.action)
            assertEquals(
                ComponentName(context.packageName, "com.molotov.clender.app.MainActivity"),
                intent.component
            )
            assertExactCommonIntent(
                intent,
                widgetId = 41,
                expectedData = "clender-internal://widget/41/edit/$eventId"
            )
            assertEquals(
                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
                intent.flags
            )
            assertEquals(
                WidgetActionSpec.EditEvent(41, eventId),
                WidgetActionSpec.parse(intent.dataString)
            )
        }
    }

    @Test
    fun localRefreshIsExplicitImmutableUpdateCurrentBroadcastWithoutForegroundFlag() {
        val token = WidgetPendingIntentFactory.create(
            context,
            WidgetActionSpec.LocalRefresh(97)
        )
        val shadow = shadowOf(token)
        val intent = shadow.savedIntent

        assertFalse(shadow.isActivity)
        assertTrue(shadow.isBroadcast)
        assertFalse(shadow.isService)
        assertTrue(shadow.isImmutable)
        assertEquals(0, shadow.requestCode)
        assertEquals(EXACT_PENDING_INTENT_FLAGS, shadow.flags)
        assertEquals("com.molotov.clender.action.WIDGET_LOCAL_REFRESH", intent.action)
        assertEquals(
            ComponentName(
                context.packageName,
                "com.molotov.clender.widget.WidgetLocalRefreshReceiver"
            ),
            intent.component
        )
        assertExactCommonIntent(intent, 97, "clender-internal://widget/97/refresh")
        assertEquals(0, intent.flags)
    }

    @Test
    fun quickAiRejectsInvalidWidgetIdentityBeforePlatformTokenCreation() {
        assertThrows(IllegalArgumentException::class.java) {
            WidgetPendingIntentFactory.create(context, WidgetActionSpec.QuickAi(0))
        }
    }

    @Test
    fun tokenIdentityIsStableForRepeatsAndIsolatedAcrossWidgetEventAndAction() {
        fun create(spec: WidgetActionSpec) = WidgetPendingIntentFactory.create(context, spec)
        val edit = create(WidgetActionSpec.EditEvent(7, 11))
        assertEquals(edit, create(WidgetActionSpec.EditEvent(7, 11)))
        assertNotEquals(edit, create(WidgetActionSpec.EditEvent(7, 12)))
        assertNotEquals(edit, create(WidgetActionSpec.EditEvent(8, 11)))
        assertNotEquals(edit, create(WidgetActionSpec.Configure(7)))
        assertNotEquals(edit, create(WidgetActionSpec.LocalRefresh(7)))
        assertNotEquals(
            create(WidgetActionSpec.LocalRefresh(7)),
            create(WidgetActionSpec.LocalRefresh(8))
        )
    }

    private fun assertExactCommonIntent(intent: Intent, widgetId: Int, expectedData: String) {
        assertEquals(context.packageName, intent.`package`)
        assertEquals(expectedData, intent.dataString)
        assertEquals(widgetId, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
        assertEquals(setOf(AppWidgetManager.EXTRA_APPWIDGET_ID), intent.extras?.keySet())
        assertEquals(null, intent.categories)
        assertEquals(null, intent.clipData)
        assertEquals(null, intent.selector)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class WidgetP2B2aRemoteViewsActionTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun everyVisibleValidRowAtTwoFourAndEightCapacityBindsOnlyItsWholeRow() {
        val application = RuntimeEnvironment.getApplication()
        val expectedCapacities = mapOf(
            SizeClass.SMALL to 2,
            SizeClass.MEDIUM to 4,
            SizeClass.LARGE to 8
        )
        expectedCapacities.forEach { (sizeClass, capacity) ->
            val rows = (1L..10L).map { id ->
                reflectedRow(
                    id,
                    "09:${id.toString().padStart(2, '0')}",
                    "Event $id"
                )
            }
            val root = apply(configuredViews(model(rows = rows), sizeClass, appWidgetId = 91))
            val visibleRows = descendants(root).filter { it.tag == EVENT_ROW_TAG }
            assertEquals("$sizeClass capacity", capacity, visibleRows.size)
            visibleRows.forEachIndexed { index, row ->
                assertTrue("$sizeClass row ${index + 1}", row.hasOnClickListeners())
                childViews(row).forEach { child ->
                    assertFalse(
                        "child ${child.id} must not own a token",
                        child.hasOnClickListeners()
                    )
                }
                shadowOf(application).clearNextStartedActivities()
                assertTrue(row.performClick())
                val started = requireNotNull(shadowOf(application).nextStartedActivity)
                assertEquals(
                    "clender-internal://widget/91/edit/${index + 1}",
                    started.dataString
                )
                assertEquals("com.molotov.clender.action.WIDGET_EDIT_EVENT", started.action)
            }
        }
    }

    @Test
    fun maxIntEventIsClickableWhileOverflowLongStillDisplaysButHasNoAction() {
        val rows = listOf(
            reflectedRow(Int.MAX_VALUE.toLong(), "09:00", "MAX clickable"),
            reflectedRow(Int.MAX_VALUE.toLong() + 1L, "10:00", "Long overflow visible")
        )
        val root = apply(configuredViews(model(rows = rows), SizeClass.SMALL, appWidgetId = 3))
        val renderedRows = descendants(root).filter { it.tag == EVENT_ROW_TAG }
        assertEquals(2, renderedRows.size)
        assertTrue(renderedRows[0].hasOnClickListeners())
        assertFalse(renderedRows[1].hasOnClickListeners())
        assertTrue(allText(renderedRows[1]).contains("Long overflow visible"))
    }

    @Test
    fun refreshExistsOnlyOnLargeAndIsAvailableForContentEmptyAndUnavailable() {
        WidgetRenderStatus.entries.forEach { status ->
            SizeClass.entries.forEach { sizeClass ->
                val root = apply(
                    configuredViews(
                        model(
                            status = status,
                            rows = if (status == WidgetRenderStatus.CONTENT) {
                                listOf(reflectedRow(1))
                            } else {
                                emptyList()
                            }
                        ),
                        sizeClass,
                        appWidgetId = 62
                    )
                )
                val refreshId = resourceId("widget_refresh")
                val refresh = descendants(root).singleOrNull { it.id == refreshId }
                if (sizeClass == SizeClass.LARGE) {
                    assertNotNull("$status LARGE refresh", refresh)
                    assertTrue(
                        "$status LARGE refresh click",
                        requireNotNull(refresh).hasOnClickListeners()
                    )
                    val minimumTouchPx = (48f * root.resources.displayMetrics.density).toInt()
                    assertTrue(refresh.minimumWidth >= minimumTouchPx)
                    assertTrue(refresh.minimumHeight >= minimumTouchPx)
                    val application = RuntimeEnvironment.getApplication()
                    shadowOf(application).broadcastIntents.clear()
                    assertTrue(refresh.performClick())
                    val broadcast = shadowOf(application).broadcastIntents.last()
                    assertEquals(
                        "com.molotov.clender.action.WIDGET_LOCAL_REFRESH",
                        broadcast.action
                    )
                    assertEquals("clender-internal://widget/62/refresh", broadcast.dataString)
                } else {
                    assertEquals("$status $sizeClass must omit refresh", null, refresh)
                }
            }
        }
    }

    @Test
    fun previewAndNoIdRenderingNeverAttachEditOrRefreshActions() {
        SizeClass.entries.forEach { sizeClass ->
            val root = apply(WidgetRemoteViewsRenderer.render(context, model(), sizeClass))
            descendants(root).forEach { view ->
                assertFalse("preview $sizeClass view ${view.id}", view.hasOnClickListeners())
            }
        }
    }

    @Test
    fun actionsPreserveUnicodeEmojiNewlineThemeFontAndOpacityRendering() {
        val title = "会议 🎉\n第二行 <b>纯文本</b>"
        val root = apply(
            configuredViews(
                model(
                    rows = listOf(reflectedRow(Int.MAX_VALUE.toLong(), "23:59", title)),
                    fontSizeSp = 20,
                    opacityPercent = 0,
                    theme = WidgetThemeMode.DARK
                ),
                SizeClass.LARGE,
                appWidgetId = Int.MAX_VALUE
            )
        )
        assertTrue(allText(root).contains(title))
        val row = descendants(root).single { it.tag == EVENT_ROW_TAG }
        assertTrue(row.hasOnClickListeners())
        val rowTitle = descendants(row)
            .filterIsInstance<TextView>()
            .single { it.text.toString() == title }
        assertEquals(
            20f,
            rowTitle.textSize / root.resources.displayMetrics.scaledDensity,
            0.01f
        )
        assertEquals(0, Color.alpha((root.background as ColorDrawable).color))
    }

    private fun configuredViews(
        model: WidgetRenderModel,
        sizeClass: SizeClass,
        appWidgetId: Int
    ): android.widget.RemoteViews =
        WidgetRemoteViewsRenderer.render(context, model, sizeClass, appWidgetId)

    private fun apply(views: android.widget.RemoteViews): View =
        views.apply(context, FrameLayout(context))

    private fun model(
        status: WidgetRenderStatus = WidgetRenderStatus.CONTENT,
        rows: List<WidgetRenderRow> = listOf(reflectedRow(1)),
        fontSizeSp: Int = 13,
        opacityPercent: Int = 100,
        theme: WidgetThemeMode = WidgetThemeMode.SYSTEM
    ) = WidgetRenderModel(
        date = LocalDate.of(2026, 9, 2),
        status = status,
        rows = rows,
        remainingCount = 0,
        fontSizeSp = fontSizeSp,
        opacityPercent = opacityPercent,
        theme = theme
    )

    private fun resourceId(name: String): Int {
        val id = context.resources.getIdentifier(name, "id", context.packageName)
        assertNotEquals("missing @$name", 0, id)
        return id
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WidgetP2B2aResponsiveActionTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    @Test
    fun fourResponsiveMappingsKeepTwoFourFourEightRowsAndOnly250SquareRefresh() {
        val map = WidgetRemoteViewsRenderer.renderResponsiveMap(
            context,
            WidgetRenderModel(
                date = LocalDate.of(2026, 9, 2),
                status = WidgetRenderStatus.CONTENT,
                rows = (1L..10L).map { reflectedRow(it, title = "row $it") },
                remainingCount = 0,
                fontSizeSp = 13,
                opacityPercent = 100,
                theme = WidgetThemeMode.SYSTEM
            ),
            appWidgetId = 77
        )
        val capacities = mapOf(
            SizeF(110f, 110f) to 2,
            SizeF(250f, 110f) to 4,
            SizeF(110f, 250f) to 4,
            SizeF(250f, 250f) to 8
        )
        assertEquals(capacities.keys, map.keys)
        map.forEach { (size, views) ->
            val root = views.apply(context, FrameLayout(context))
            assertEquals(
                capacities.getValue(size),
                descendants(root).count { it.tag == EVENT_ROW_TAG }
            )
            val refreshId = context.resources.getIdentifier(
                "widget_refresh",
                "id",
                context.packageName
            )
            assertNotEquals("missing refresh id", 0, refreshId)
            val refresh = descendants(root).singleOrNull { it.id == refreshId }
            assertEquals(size == SizeF(250f, 250f), refresh?.hasOnClickListeners() == true)
        }
    }
}

private fun reflectedRow(
    eventId: Long,
    time: String = "09:00",
    title: String = "event $eventId",
    state: EventTemporalState = EventTemporalState.FUTURE
): WidgetRenderRow {
    val constructor = WidgetRenderRow::class.java.declaredConstructors.singleOrNull { candidate ->
        candidate.parameterTypes.contentEquals(
            arrayOf(
                String::class.java,
                String::class.java,
                EventTemporalState::class.java,
                java.lang.Long.TYPE
            )
        )
    } ?: throw AssertionError(
        "WidgetRenderRow must preserve the source Long eventId as its first field"
    )
    return constructor.newInstance(time, title, state, eventId) as WidgetRenderRow
}

private fun descendants(root: View): List<View> {
    val result = mutableListOf<View>()
    fun visit(view: View) {
        result += view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) visit(view.getChildAt(index))
        }
    }
    visit(root)
    return result
}

private fun childViews(root: View): List<View> = descendants(root).drop(1)

private fun allText(root: View): List<String> = descendants(root)
    .filterIsInstance<TextView>()
    .map { it.text.toString() }

private const val EVENT_ROW_TAG = "widget_event_row"
private const val EXACT_PENDING_INTENT_FLAGS =
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
