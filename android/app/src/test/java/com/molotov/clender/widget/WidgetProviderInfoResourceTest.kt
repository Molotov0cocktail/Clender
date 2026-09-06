package com.molotov.clender.widget

import android.content.Context
import android.content.res.Configuration
import android.util.Xml
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.molotov.clender.R
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class WidgetProviderInfoResourceTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun allThreeLayoutsAndEventRowExistAndApplyWithNoUserContent() {
        listOf(
            "widget_clender_small",
            "widget_clender_medium",
            "widget_clender_large",
            "widget_clender_event_row"
        ).forEach { layoutName ->
            val id = context.resources.getIdentifier(layoutName, "layout", context.packageName)
            assertNotEquals("missing $layoutName", 0, id)
            val views = android.widget.RemoteViews(context.packageName, id)
            val root = views.apply(context, FrameLayout(context))
            val rendered = descendants(root)
                .filterIsInstance<TextView>()
                .joinToString("|") { it.text.toString() }
            listOf(
                "Event 1",
                "private description",
                "0123456789abcdef0123456789abcdef",
                "/data/user/0/com.molotov.clender"
            ).forEach { forbidden -> assertFalse(rendered.contains(forbidden)) }
        }
    }

    @Test
    fun providerInfoKeepsFrozenSizingAndDeclaresOnlyConfigurationFeatures() {
        val id = context.resources.getIdentifier(
            "clender_widget_info",
            "xml",
            context.packageName
        )
        assertNotEquals(0, id)
        val parser = context.resources.getXml(id)
        parser.use {
            var eventType = it.eventType
            while (eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT &&
                !(
                    eventType == org.xmlpull.v1.XmlPullParser.START_TAG &&
                        it.name == "appwidget-provider"
                    )
            ) {
                eventType = it.next()
            }
            assertEquals(org.xmlpull.v1.XmlPullParser.START_TAG, eventType)
            val androidNs = Xml.asAttributeSet(it)
            fun attribute(name: String): String? = androidNs.getAttributeValue(ANDROID_NS, name)
            fun intAttribute(name: String): Int =
                androidNs.getAttributeIntValue(ANDROID_NS, name, -1)
            fun resourceAttribute(name: String): Int =
                androidNs.getAttributeResourceValue(ANDROID_NS, name, 0)
            assertDimensionDp(110f, attribute("minWidth"))
            assertDimensionDp(110f, attribute("minHeight"))
            assertDimensionDp(110f, attribute("minResizeWidth"))
            assertDimensionDp(110f, attribute("minResizeHeight"))
            assertDimensionDp(360f, attribute("maxResizeWidth"))
            assertDimensionDp(360f, attribute("maxResizeHeight"))
            assertEquals(2, intAttribute("targetCellWidth"))
            assertEquals(2, intAttribute("targetCellHeight"))
            assertEquals(3, intAttribute("resizeMode"))
            assertEquals(1, intAttribute("widgetCategory"))
            assertEquals(0, intAttribute("updatePeriodMillis"))
            assertEquals(R.layout.widget_clender_small, resourceAttribute("initialLayout"))
            assertEquals(R.layout.widget_clender_medium, resourceAttribute("previewLayout"))
            assertEquals(R.string.widget_description, resourceAttribute("description"))
            assertEquals(
                "com.molotov.clender.widget.WidgetConfigurationActivity",
                attribute("configure")
            )
            assertEquals(5, intAttribute("widgetFeatures"))
            listOf(
                "initialKeyguardLayout",
                "previewImage",
                "autoAdvanceViewId"
            ).forEach { forbidden ->
                assertEquals(null, attribute(forbidden))
            }
        }
    }

    @Test
    fun allThreeHostLayoutsExposeOneReadableConfigureControlWithMinimumTouchBounds() {
        val configureId = context.resources.getIdentifier(
            "widget_configure",
            "id",
            context.packageName
        )
        assertNotEquals("missing widget_configure id", 0, configureId)
        val minimumTouchPx = (48f * context.resources.displayMetrics.density).toInt()
        listOf(
            "widget_clender_small",
            "widget_clender_medium",
            "widget_clender_large"
        ).forEach { layoutName ->
            val layoutId = context.resources.getIdentifier(
                layoutName,
                "layout",
                context.packageName
            )
            val root = android.widget.RemoteViews(context.packageName, layoutId)
                .apply(context, FrameLayout(context))
            val controls = descendants(root).filter { it.id == configureId }
            assertEquals("$layoutName must contain one configure control", 1, controls.size)
            val control = controls.single()
            assertTrue("$layoutName configure width", control.minimumWidth >= minimumTouchPx)
            assertTrue("$layoutName configure height", control.minimumHeight >= minimumTouchPx)
            assertTrue(control.contentDescription?.toString()?.isNotBlank() == true)
            assertFalse("preview layouts must not attach actions", control.hasOnClickListeners())
        }
    }

    @Test
    fun eventRowHasOneStableWholeRowTargetAndLocalizedDescriptionWithoutInflatingTouchSize() {
        val rowId = context.resources.getIdentifier("widget_event_row", "id", context.packageName)
        assertNotEquals("missing stable widget_event_row id", 0, rowId)
        val layoutId = context.resources.getIdentifier(
            "widget_clender_event_row",
            "layout",
            context.packageName
        )
        val row = android.widget.RemoteViews(context.packageName, layoutId)
            .apply(context, FrameLayout(context))
        assertEquals(rowId, row.id)
        assertTrue(row.contentDescription?.toString()?.isNotBlank() == true)
        assertFalse(
            "compact event rows must not claim a 48dp minimum",
            row.minimumHeight >= 48f * context.resources.displayMetrics.density
        )
        descendants(row).drop(1).forEach { child ->
            assertFalse(
                "event child ${child.id} must not become a separate target",
                child.hasOnClickListeners()
            )
        }
    }

    @Test
    fun refreshControlExistsOnlyInLargeLayoutAndHasLocalizedDescriptionAnd48DpBounds() {
        val refreshId = context.resources.getIdentifier("widget_refresh", "id", context.packageName)
        assertNotEquals("missing widget_refresh id", 0, refreshId)
        val minimumTouchPx = (48f * context.resources.displayMetrics.density).toInt()
        listOf("widget_clender_small", "widget_clender_medium", "widget_clender_large")
            .forEach { layoutName ->
                val layoutId = context.resources.getIdentifier(
                    layoutName,
                    "layout",
                    context.packageName
                )
                val root = android.widget.RemoteViews(context.packageName, layoutId)
                    .apply(context, FrameLayout(context))
                val controls = descendants(root).filter { it.id == refreshId }
                if (layoutName == "widget_clender_large") {
                    assertEquals(1, controls.size)
                    val refresh = controls.single()
                    assertTrue(refresh.minimumWidth >= minimumTouchPx)
                    assertTrue(refresh.minimumHeight >= minimumTouchPx)
                    assertTrue(refresh.contentDescription?.toString()?.isNotBlank() == true)
                } else {
                    assertEquals("$layoutName must omit local refresh", 0, controls.size)
                }
            }
    }

    @Test
    fun allWidgetStringsExistInEnglishAndChineseWithoutHardcodedStatusFallback() {
        val required = listOf(
            "widget_empty",
            "widget_unavailable",
            "widget_current",
            "widget_next",
            "widget_future",
            "widget_more",
            "widget_description",
            "widget_configure",
            "widget_event_open_description",
            "widget_refresh"
        )
        val englishConfiguration = Configuration(context.resources.configuration).apply {
            setLocale(Locale.ENGLISH)
        }
        val chineseConfiguration = Configuration(context.resources.configuration).apply {
            setLocale(Locale.SIMPLIFIED_CHINESE)
        }
        val english = context.createConfigurationContext(englishConfiguration)
        val chinese = context.createConfigurationContext(chineseConfiguration)
        required.forEach { name ->
            val id = context.resources.getIdentifier(name, "string", context.packageName)
            assertNotEquals("missing default string $name", 0, id)
            assertTrue(context.getString(id).isNotBlank())
            assertTrue(english.getString(id).isNotBlank())
            assertTrue(chinese.getString(id).isNotBlank())
        }
        listOf(
            "widget_empty",
            "widget_unavailable",
            "widget_current",
            "widget_next",
            "widget_future",
            "widget_description",
            "widget_event_open_description",
            "widget_refresh"
        )
            .forEach { name ->
                val id = context.resources.getIdentifier(name, "string", context.packageName)
                assertNotEquals(
                    "$name must have a real zh translation",
                    english.getString(id),
                    chinese.getString(id)
                )
            }
    }

    @Test
    fun widgetLayoutsDeclareNoXmlClickHooksOrSensitiveMetadata() {
        listOf(
            "widget_clender_small",
            "widget_clender_medium",
            "widget_clender_large",
            "widget_clender_event_row"
        ).forEach(::assertSafeWidgetLayout)
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

    private fun assertDimensionDp(expected: Float, raw: String?) {
        val match = requireNotNull(DP_PATTERN.matchEntire(requireNotNull(raw)))
        assertEquals(expected, match.groupValues[1].toFloat(), 0f)
    }

    private fun assertSafeWidgetLayout(layoutName: String) {
        val id = context.resources.getIdentifier(layoutName, "layout", context.packageName)
        context.resources.getLayout(id).use { parser ->
            while (parser.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG) {
                    assertSafeAttributes(parser)
                }
                parser.next()
            }
        }
    }

    private fun assertSafeAttributes(parser: android.content.res.XmlResourceParser) {
        for (index in 0 until parser.attributeCount) {
            val name = parser.getAttributeName(index)
            val value = parser.getAttributeValue(index)
            assertFalse(name.equals("onClick", ignoreCase = true))
            FORBIDDEN_LAYOUT_VALUES.forEach { forbidden ->
                assertFalse(value.contains(forbidden, ignoreCase = true))
            }
        }
    }
}

private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
private val DP_PATTERN = Regex("([0-9]+(?:\\.0)?)d(?:i)?p")
private val FORBIDDEN_LAYOUT_VALUES = listOf(
    "sync_uid",
    "description",
    "created_at",
    "updated_at",
    "deleted_at"
)
