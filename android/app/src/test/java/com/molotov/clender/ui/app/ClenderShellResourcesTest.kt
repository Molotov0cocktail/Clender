package com.molotov.clender.ui.app

import android.content.res.Configuration
import com.molotov.clender.R
import com.molotov.clender.app.ClenderApplication
import java.util.Locale
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], application = ClenderApplication::class)
class ClenderShellResourcesTest {
    private val drawerLabelKeys = listOf(
        R.string.drawer_calendar,
        R.string.drawer_events,
        R.string.drawer_ai,
        R.string.drawer_settings,
        R.string.drawer_about
    )

    private fun localizedString(key: Int, locale: Locale): String {
        val application = RuntimeEnvironment.getApplication()
        val configuration = Configuration(application.resources.configuration).apply {
            setLocale(locale)
        }
        return application.createConfigurationContext(configuration).getString(key)
    }

    @Test
    fun drawerLabelsResolveToNonBlankLocalizedValues() {
        listOf(Locale.SIMPLIFIED_CHINESE, Locale.US).forEach { locale ->
            drawerLabelKeys.forEach { key ->
                val value = localizedString(key, locale)
                assertTrue("drawer label $key must be non-blank for $locale", value.isNotBlank())
            }
        }
    }

    @Test
    fun screenTitlesResolveToNonBlankLocalizedValues() {
        val keys = listOf(
            R.string.screen_calendar,
            R.string.screen_events,
            R.string.screen_ai,
            R.string.screen_settings,
            R.string.screen_about,
            R.string.screen_event_detail,
            R.string.screen_event_new,
            R.string.screen_quick_ai
        )
        listOf(Locale.SIMPLIFIED_CHINESE, Locale.US).forEach { locale ->
            keys.forEach { key ->
                val value = localizedString(key, locale)
                assertTrue("screen title $key must be non-blank for $locale", value.isNotBlank())
            }
        }
    }

    @Test
    fun drawerLabelsDifferBetweenChineseAndEnglish() {
        val zh = localizedString(R.string.drawer_calendar, Locale.SIMPLIFIED_CHINESE)
        val en = localizedString(R.string.drawer_calendar, Locale.US)

        assertNotEquals(zh, en)
    }

    @Test
    fun drawerToggleContentDescriptionIsLocalizedAndNonBlank() {
        val zh = localizedString(
            R.string.semantics_open_navigation_drawer,
            Locale.SIMPLIFIED_CHINESE
        )
        val en = localizedString(
            R.string.semantics_open_navigation_drawer,
            Locale.US
        )

        assertTrue(zh.isNotBlank())
        assertTrue(en.isNotBlank())
    }
}
