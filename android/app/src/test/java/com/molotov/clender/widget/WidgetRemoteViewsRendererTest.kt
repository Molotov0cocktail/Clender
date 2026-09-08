package com.molotov.clender.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.SizeF
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.molotov.clender.R
import com.molotov.clender.core.model.EventType
import com.molotov.clender.domain.calendar.EventTemporalState
import com.molotov.clender.domain.widget.WidgetConfiguration
import com.molotov.clender.domain.widget.WidgetEventItem
import com.molotov.clender.domain.widget.WidgetPresentation
import com.molotov.clender.domain.widget.WidgetPresentationPolicy.SizeClass
import com.molotov.clender.domain.widget.WidgetThemeMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class WidgetRemoteViewsRendererTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun contentIsCappedAtSmallMediumAndLargeCapacitiesAndShowsRemaining() {
        val rows = (1..10).map { index ->
            WidgetRenderRow(
                timeLabel = "09:${index.toString().padStart(2, '0')}",
                title = "Event $index",
                temporalState = if (index ==
                    1
                ) {
                    EventTemporalState.CURRENT
                } else {
                    EventTemporalState.FUTURE
                }
            )
        }

        assertRenderedRowCount(rows, SizeClass.SMALL, expectedRows = 2, remaining = 8)
        assertRenderedRowCount(rows, SizeClass.MEDIUM, expectedRows = 4, remaining = 6)
        assertRenderedRowCount(rows, SizeClass.LARGE, expectedRows = 8, remaining = 2)
    }

    @Test
    fun emptyAndUnavailableStatesAreFiniteAndKeepDateAndStatusReadable() {
        val date = LocalDate.of(2026, 9, 2)
        listOf(WidgetRenderStatus.EMPTY, WidgetRenderStatus.UNAVAILABLE).forEach { status ->
            val views = WidgetRemoteViewsRenderer.render(
                context = context,
                model = WidgetRenderModel(
                    date = date,
                    status = status,
                    rows = emptyList(),
                    remainingCount = 0,
                    fontSizeSp = 13,
                    opacityPercent = 100,
                    theme = WidgetThemeMode.SYSTEM
                ),
                sizeClass = SizeClass.SMALL
            )

            val root = apply(views)
            val text = allText(root).joinToString("|")
            assertTrue(text.contains("2026"))
            assertFalse(text.contains("IllegalStateException"))
            assertFalse(text.contains("java.lang"))
            assertTrue(text.isNotBlank())
        }
    }

    @Test
    fun unicodeEmojiAndNewlineRemainPlainTextAndLongTitlesAreEllipsizedByLayout() {
        val title = "会议 🎉\n第二行 <b>纯文本</b>" + "很长".repeat(80)
        val views = WidgetRemoteViewsRenderer.render(
            context = context,
            model = contentModel().copy(
                rows = listOf(
                    WidgetRenderRow("09:00", title, EventTemporalState.NEXT)
                )
            ),
            sizeClass = SizeClass.MEDIUM
        )

        val root = apply(views)
        val text = allText(root).joinToString("|")
        assertTrue(text.contains(title))
        assertFalse(text.contains("&lt;"))
        assertFalse(text.contains("<html"))
        val titleView = requireNotNull(root.findViewWithTag<TextView>("widget_event_title"))
        assertEquals(android.text.TextUtils.TruncateAt.END, titleView.ellipsize)
        assertTrue(titleView.maxLines in 1..2)
    }

    @Test
    fun lightDarkAndSystemFollowAuditableConfigurationColors() {
        val lightContext = configuredContext(night = false, locale = Locale.ENGLISH)
        val darkContext = configuredContext(night = true, locale = Locale.ENGLISH)
        val light = renderedBackgroundColor(lightContext, WidgetThemeMode.LIGHT)
        val dark = renderedBackgroundColor(darkContext, WidgetThemeMode.DARK)

        assertNotEquals(light, dark)
        assertEquals(light, renderedBackgroundColor(lightContext, WidgetThemeMode.SYSTEM))
        assertEquals(dark, renderedBackgroundColor(darkContext, WidgetThemeMode.SYSTEM))
        assertEquals(light, renderedBackgroundColor(darkContext, WidgetThemeMode.LIGHT))
        assertEquals(dark, renderedBackgroundColor(lightContext, WidgetThemeMode.DARK))
    }

    @Test
    fun eightAndTwentySpBoundsAndOpacityZeroAndHundredOnlyAffectBackground() {
        val transparentRoot = apply(
            WidgetRemoteViewsRenderer.render(
                context = context,
                model = contentModel().copy(fontSizeSp = 8, opacityPercent = 0),
                sizeClass = SizeClass.SMALL
            )
        )
        val opaqueRoot = apply(
            WidgetRemoteViewsRenderer.render(
                context = context,
                model = contentModel().copy(fontSizeSp = 20, opacityPercent = 100),
                sizeClass = SizeClass.SMALL
            )
        )
        assertEquals(0, Color.alpha(backgroundColor(transparentRoot)))
        assertEquals(255, Color.alpha(backgroundColor(opaqueRoot)))
        assertEquals(8f, titleSizeSp(transparentRoot), 0.01f)
        assertEquals(20f, titleSizeSp(opaqueRoot), 0.01f)
        listOf(transparentRoot, opaqueRoot).forEach { root ->
            val visibleTextViews = descendants(root)
                .filterIsInstance<TextView>()
                .filter { it.visibility == View.VISIBLE }
            assertTrue(visibleTextViews.isNotEmpty())
            assertTrue(visibleTextViews.all { Color.alpha(it.currentTextColor) == 255 })
            assertTrue(allText(root).any { it == "FUTURE" || it == "未来" })
        }
    }

    @Test
    fun rendererDoesNotExposeDescriptionOrSyncMetadataAndDoesNotAttachActions() {
        val views = WidgetRemoteViewsRenderer.render(
            context = context,
            model = contentModel().copy(
                rows = listOf(
                    WidgetRenderRow("09:00", "safe title", EventTemporalState.CURRENT)
                )
            ),
            sizeClass = SizeClass.SMALL
        )
        val root = apply(views)
        val rendered = allText(root).joinToString("|")
        listOf(
            "private description",
            "0123456789abcdef0123456789abcdef",
            "2026-09-01T00:00:00Z",
            "/data/user/0/com.molotov.clender"
        ).forEach { forbidden -> assertFalse(rendered.contains(forbidden)) }
        assertTrue(descendants(root).none(View::hasOnClickListeners))

        val renderModelFields = WidgetRenderModel::class.java.declaredFields
            .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) || it.isSynthetic }
            .map { it.name.lowercase(Locale.ROOT) }
        listOf(
            "description",
            "sync",
            "created",
            "updated",
            "deleted",
            "path",
            "error"
        ).forEach { forbiddenName ->
            assertTrue(renderModelFields.none { it.contains(forbiddenName) })
        }
    }

    @Test
    fun configuredSmallMediumAndLargeBindOnlyTheConfigureControlForTheExactInstance() {
        val appWidgetId = 321
        SizeClass.entries.forEach { sizeClass ->
            val views = WidgetRemoteViewsRenderer.render(
                context,
                contentModel(),
                sizeClass,
                appWidgetId
            )
            val root = apply(views)
            val configure = requireNotNull(root.findViewById<View>(R.id.widget_configure))
            assertTrue("$sizeClass configure must be clickable", configure.hasOnClickListeners())
            val application = RuntimeEnvironment.getApplication()
            shadowOf(application).clearNextStartedActivities()
            assertTrue(configure.performClick())
            val started = requireNotNull(shadowOf(application).nextStartedActivity)
            assertEquals(
                appWidgetId,
                started.getIntExtra(
                    AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID
                )
            )
            assertEquals(
                "clender-internal://widget/$appWidgetId/configure",
                started.dataString
            )
            listOf(
                R.id.widget_root,
                R.id.widget_date,
                R.id.widget_status,
                R.id.widget_rows,
                R.id.widget_more,
                R.id.widget_event_time,
                R.id.widget_event_title,
                R.id.widget_event_state
            ).forEach { id ->
                root.findViewById<View>(id)?.let { view ->
                    assertFalse(
                        "$sizeClass view $id must remain non-clickable",
                        view.hasOnClickListeners()
                    )
                }
            }
        }
    }

    @Test
    fun rendererUsesChineseResourcesWhenRequested() {
        val model = contentModel().copy(status = WidgetRenderStatus.EMPTY)
        val chineseContext = configuredContext(night = false, locale = Locale.SIMPLIFIED_CHINESE)
        val root =
            apply(
                WidgetRemoteViewsRenderer.render(chineseContext, model, SizeClass.SMALL),
                chineseContext
            )
        assertTrue(allText(root).any { it.contains("暂无") })
        assertFalse(allText(root).any { it.contains("No events") })
    }

    @Test
    fun currentNextAndFutureStatesUseFiniteLocalizedLabels() {
        val rows = listOf(
            WidgetRenderRow("09:00", "one", EventTemporalState.CURRENT),
            WidgetRenderRow("10:00", "two", EventTemporalState.NEXT),
            WidgetRenderRow("11:00", "three", EventTemporalState.FUTURE)
        )
        val englishContext = configuredContext(night = false, locale = Locale.ENGLISH)
        val root = apply(
            WidgetRemoteViewsRenderer.render(
                englishContext,
                contentModel().copy(rows = rows),
                SizeClass.MEDIUM
            ),
            englishContext
        )
        val rendered = allText(root)
        assertTrue(rendered.containsAll(listOf("CURRENT", "NEXT", "FUTURE")))
        assertTrue(rendered.none { it.contains("EventTemporalState") })
    }

    @Test
    fun api30And31BoundarySelectsSingleOrResponsiveRenderingWithoutDisplayGuessing() {
        assertFalse(WidgetRemoteViewsRenderer.isResponsiveApi(30))
        assertTrue(WidgetRemoteViewsRenderer.isResponsiveApi(31))
        val single = WidgetRemoteViewsRenderer.render(
            context,
            contentModel(),
            SizeClass.SMALL
        )
        assertNotNull(single.apply(context, FrameLayout(context)))
    }

    @Test
    fun renderModelFactoryUsesP1PresentationLocalizedTimesAndFiniteUnavailableDefaults() {
        val date = LocalDate.of(2026, 9, 2)
        val locale = Locale.ENGLISH
        val reminderStart = date.atTime(9, 5)
        val spanStart = date.atTime(10, 10)
        val spanEnd = date.atTime(11, 45)
        val presentation = WidgetPresentation(
            visibleItems = listOf(
                widgetItem(1, EventType.REMINDER, "reminder", reminderStart, null),
                widgetItem(2, EventType.TIMESPAN, "span", spanStart, spanEnd)
            ),
            remainingCount = 3,
            opacityPercent = 65
        )
        val configuration = WidgetConfiguration(
            appWidgetId = 7,
            startTime = LocalTime.of(8, 0),
            endTime = LocalTime.of(22, 0),
            opacityPercent = 65,
            fontSizeSp = 17,
            theme = WidgetThemeMode.DARK
        )

        val ready = WidgetRenderModelFactory.ready(date, presentation, configuration, locale)
        val timeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
        assertEquals(reminderStart.format(timeFormatter), ready.rows[0].timeLabel)
        assertEquals(
            "${spanStart.format(timeFormatter)}–${spanEnd.format(timeFormatter)}",
            ready.rows[1].timeLabel
        )
        assertEquals(listOf("reminder", "span"), ready.rows.map { it.title })
        assertEquals(3, ready.remainingCount)
        assertEquals(17, ready.fontSizeSp)
        assertEquals(65, ready.opacityPercent)
        assertEquals(WidgetThemeMode.DARK, ready.theme)

        val unavailable = WidgetRenderModelFactory.unavailable(date, locale)
        assertEquals(WidgetRenderStatus.UNAVAILABLE, unavailable.status)
        assertEquals(emptyList<WidgetRenderRow>(), unavailable.rows)
        assertEquals(13, unavailable.fontSizeSp)
        assertEquals(100, unavailable.opacityPercent)
        assertEquals(WidgetThemeMode.SYSTEM, unavailable.theme)
    }

    private fun assertRenderedRowCount(
        rows: List<WidgetRenderRow>,
        sizeClass: SizeClass,
        expectedRows: Int,
        remaining: Int
    ) {
        val root = apply(
            WidgetRemoteViewsRenderer.render(
                context,
                contentModel().copy(rows = rows),
                sizeClass
            )
        )
        assertEquals(expectedRows, viewsWithTag(root, "widget_event_row").size)
        if (remaining > 0) assertTrue(allText(root).any { it == "+$remaining" })
    }

    private fun contentModel() = WidgetRenderModel(
        date = LocalDate.of(2026, 9, 2),
        status = WidgetRenderStatus.CONTENT,
        rows = listOf(
            WidgetRenderRow("09:00", "title", EventTemporalState.FUTURE)
        ),
        remainingCount = 0,
        fontSizeSp = 13,
        opacityPercent = 100,
        theme = WidgetThemeMode.SYSTEM
    )

    private fun apply(views: android.widget.RemoteViews, targetContext: Context = context): View =
        views.apply(targetContext, FrameLayout(targetContext))

    private fun configuredContext(night: Boolean, locale: Locale): Context {
        val configuration = Configuration(context.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            setLocale(locale)
        }
        return context.createConfigurationContext(configuration)
    }

    private fun renderedBackgroundColor(targetContext: Context, theme: WidgetThemeMode): Int =
        backgroundColor(
            apply(
                WidgetRemoteViewsRenderer.render(
                    targetContext,
                    contentModel().copy(theme = theme, opacityPercent = 100),
                    SizeClass.SMALL
                ),
                targetContext
            )
        )

    private fun backgroundColor(root: View): Int = (root.background as? ColorDrawable)?.color
        ?: error("Widget root must use an auditable solid ColorDrawable background")

    private fun titleSizeSp(root: View): Float {
        val title = requireNotNull(root.findViewWithTag<TextView>("widget_event_title"))
        return title.textSize / root.resources.displayMetrics.scaledDensity
    }

    private fun allText(root: View): List<String> {
        val result = mutableListOf<String>()
        fun visit(view: View) {
            if (view is TextView) result += view.text.toString()
            if (view is android.view.ViewGroup) {
                for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
        }
        visit(root)
        return result
    }

    private fun viewsWithTag(root: View, tag: String): List<View> = descendants(root).filter {
        it.tag == tag
    }

    private fun descendants(root: View): List<View> {
        val result = mutableListOf<View>()
        fun visit(view: View) {
            result += view
            if (view is android.view.ViewGroup) {
                for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
        }
        visit(root)
        return result
    }

    private fun widgetItem(
        id: Long,
        eventType: EventType,
        title: String,
        start: LocalDateTime,
        end: LocalDateTime?
    ): WidgetEventItem = WidgetEventItem(
        id = id,
        eventType = eventType,
        title = title,
        startTime = start,
        endTime = end,
        effectiveEndTime = end ?: start.plusMinutes(30),
        temporalState = EventTemporalState.FUTURE
    )
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WidgetResponsiveRemoteViewsTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    @Test
    fun api31PlusResponsiveMapHasExactlyFourUniqueSizeKeysAndExpectedSizeClasses() {
        val map = WidgetRemoteViewsRenderer.renderResponsiveMap(
            context,
            WidgetRemoteViewsRendererTestFixture.model(rowCount = 10),
            appWidgetId = 77
        )
        assertEquals(
            setOf(
                SizeF(110f, 110f),
                SizeF(250f, 110f),
                SizeF(110f, 250f),
                SizeF(250f, 250f)
            ),
            map.keys
        )
        assertEquals(4, map.size)
        assertEquals(4, map.values.toSet().size)
        val expectedCapacities = mapOf(
            SizeF(110f, 110f) to 2,
            SizeF(250f, 110f) to 4,
            SizeF(110f, 250f) to 4,
            SizeF(250f, 250f) to 8
        )
        map.forEach { (size, views) ->
            val root = views.apply(context, FrameLayout(context))
            val rows = countRows(root)
            assertTrue(
                "$size capacity remains an upper bound",
                rows <= expectedCapacities.getValue(size)
            )
            val hidden = 12 - rows
            assertTrue(allTextForBudget(root).any { it.contains(hidden.toString()) })
        }
    }

    @Test
    fun eachResponsiveViewCanBeAppliedWithoutUnsupportedViewFailure() {
        val map = WidgetRemoteViewsRenderer.renderResponsiveMap(
            context,
            WidgetRemoteViewsRendererTestFixture.model(),
            appWidgetId = 77
        )
        map.values.forEach { views ->
            val root = views.apply(context, FrameLayout(context))
            assertNotNull(root)
        }
    }

    @Test
    fun api36HostPathBuildsAnApplicableResponsiveRemoteViewsContainer() {
        val views = WidgetRemoteViewsRenderer.renderForHost(
            context,
            WidgetRemoteViewsRendererTestFixture.model(),
            SizeClass.SMALL,
            appWidgetId = 77
        )
        assertNotNull(views.apply(context, FrameLayout(context)))
    }

    @Test
    fun everyResponsiveMappingBindsConfigureAndOnlyLargeAlsoBindsRefreshAndQuickAi() {
        val map = WidgetRemoteViewsRenderer.renderResponsiveMap(
            context,
            WidgetRemoteViewsRendererTestFixture.model(rowCount = 10),
            appWidgetId = 93
        )
        assertEquals(4, map.size)
        map.forEach { (size, views) ->
            val root = views.apply(context, FrameLayout(context))
            val configure = requireNotNull(
                root.findViewById<View>(R.id.widget_configure)
            )
            assertTrue("$size configure must be clickable", configure.hasOnClickListeners())
            val application = RuntimeEnvironment.getApplication()
            shadowOf(application).clearNextStartedActivities()
            assertTrue(configure.performClick())
            val started = requireNotNull(shadowOf(application).nextStartedActivity)
            assertEquals(
                93,
                started.getIntExtra(
                    AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID
                )
            )
            assertEquals("clender-internal://widget/93/configure", started.dataString)
            val refresh = root.findViewById<View>(R.id.widget_refresh)
            assertEquals(
                size == SizeF(250f, 250f),
                refresh?.hasOnClickListeners() == true
            )
            val quickAi = root.findViewById<View>(R.id.widget_quick_ai)
            if (size == SizeF(250f, 250f)) {
                assertQuickAiClick(quickAi)
            } else {
                assertEquals(null, quickAi)
            }
            descendants(root)
                .filter {
                    it.id !in setOf(
                        R.id.widget_configure,
                        R.id.widget_refresh,
                        R.id.widget_quick_ai
                    )
                }
                .forEach { view ->
                    assertFalse("$size non-configure view ${view.id}", view.hasOnClickListeners())
                }
        }
    }

    private fun assertQuickAiClick(quickAi: View?) {
        val application = RuntimeEnvironment.getApplication()
        assertNotNull(quickAi)
        assertTrue(requireNotNull(quickAi).hasOnClickListeners())
        shadowOf(application).clearNextStartedActivities()
        assertTrue(quickAi.performClick())
        val quickAiIntent = requireNotNull(shadowOf(application).nextStartedActivity)
        assertEquals("clender-internal://widget/93/quick-ai", quickAiIntent.dataString)
        assertEquals(
            "com.molotov.clender.widget.QuickAiActivity",
            quickAiIntent.component?.className
        )
        assertEquals(93, quickAiIntent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
    }

    private fun countRows(root: View): Int {
        var rows = 0
        fun visit(view: View) {
            if (view.tag == "widget_event_row") rows += 1
            if (view is android.view.ViewGroup) {
                for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
        }
        visit(root)
        return rows
    }

    private fun allTextForBudget(root: View): List<String> = descendants(root)
        .filterIsInstance<TextView>().map { it.text.toString() }

    private fun descendants(root: View): List<View> {
        val result = mutableListOf<View>()
        fun visit(view: View) {
            result += view
            if (view is android.view.ViewGroup) {
                for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
        }
        visit(root)
        return result
    }
}

private object WidgetRemoteViewsRendererTestFixture {
    fun model(rowCount: Int = 1) = WidgetRenderModel(
        date = LocalDate.of(2026, 9, 2),
        status = WidgetRenderStatus.CONTENT,
        rows = (1..rowCount).map { index ->
            WidgetRenderRow("09:00", "responsive $index", EventTemporalState.NEXT)
        },
        remainingCount = (rowCount - 8).coerceAtLeast(0),
        fontSizeSp = 13,
        opacityPercent = 100,
        theme = WidgetThemeMode.SYSTEM
    )
}
