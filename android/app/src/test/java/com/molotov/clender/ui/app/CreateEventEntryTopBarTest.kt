package com.molotov.clender.ui.app

import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.molotov.clender.R
import com.molotov.clender.testsupport.RobolectricComposeHost
import com.molotov.clender.ui.calendar.CalendarLoadStatus
import com.molotov.clender.ui.calendar.CalendarRangePolicy
import com.molotov.clender.ui.calendar.CalendarScreenState
import com.molotov.clender.ui.event.EventCrudUiState
import com.molotov.clender.ui.foundation.AppearanceUiState
import com.molotov.clender.ui.foundation.ThemeMode
import com.molotov.clender.ui.navigation.AppDestination
import com.molotov.clender.ui.navigation.AppRoute
import com.molotov.clender.ui.state.AppShellUiState
import com.molotov.clender.ui.state.CalendarMode
import com.molotov.clender.ui.theme.ClenderTheme
import java.time.LocalDate
import java.util.Locale
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CreateEventEntryTopBarTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val host = RobolectricComposeHost()

    @Before
    fun startComponentHost() = host.start()

    @After
    fun closeComponentHost() = host.close()

    @Test
    fun calendarAndEventsExposeLocalizedAccessibleEntryInEveryQueryState() {
        listOf(AppDestination.CALENDAR, AppDestination.EVENTS).forEach { destination ->
            CalendarLoadStatus.entries.forEach { loadStatus ->
                render(model(destination, loadStatus = loadStatus))
                assertEntry()
            }
        }
    }

    @Test
    fun aiSettingsAndAboutNeverExposeCreateEntry() {
        listOf(AppDestination.AI, AppDestination.SETTINGS, AppDestination.ABOUT).forEach {
            render(model(it))
            composeRule.onNodeWithTag(CREATE_EVENT_TAG).assertDoesNotExist()
        }
    }

    @Test
    fun childRoutesHideCreateEntryAndKeepExistingBackChrome() {
        val children = listOf(
            AppRoute.NewEvent(LocalDate.of(2028, 2, 29)),
            AppRoute.EventDetail(1),
            AppRoute.QuickAi
        )
        listOf(AppDestination.CALENDAR, AppDestination.EVENTS).forEach { destination ->
            children.forEach { child ->
                render(model(destination, listOf(child)))
                composeRule.onNodeWithTag(CREATE_EVENT_TAG).assertDoesNotExist()
                composeRule.onNodeWithTag("clender_navigate_back").assertIsDisplayed()
            }
        }
    }

    @Config(qualifiers = "zh-rCN-w640dp-h360dp-land-420dpi")
    @Test
    fun landscapeDoubleFontScaleKeepsEntryReachableAcrossThemeAndFontChanges() {
        listOf(ThemeMode.LIGHT, ThemeMode.DARK, ThemeMode.SYSTEM).forEach { theme ->
            listOf(8, 20).forEach { fontSize ->
                render(model(AppDestination.CALENDAR), AppearanceUiState(theme, fontSize, 13), 2f)
                assertEntry()
            }
        }
    }

    @Config(qualifiers = "en-rUS-w360dp-h640dp-420dpi")
    @Test
    fun englishCompactEntryUsesExistingLocalizedNewEventLabel() {
        render(model(AppDestination.EVENTS))
        assertEntry()
    }

    private fun assertEntry() {
        composeRule.onNodeWithTag(CREATE_EVENT_TAG)
            .assertIsDisplayed().assertHasClickAction()
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            .assertContentDescriptionEquals(host.activity.getString(R.string.screen_event_new))
    }

    private fun render(
        model: AppContentModel,
        appearance: AppearanceUiState = AppearanceUiState.fromPersisted(null, null, null),
        fontScale: Float = 1f
    ) {
        composeRule.runOnUiThread {
            host.activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, fontScale)
                ) {
                    ClenderTheme(appearance) {
                        AppTopBar(model, AppShellScaffoldActions({}, {}, {}))
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun model(
        destination: AppDestination,
        children: List<AppRoute> = emptyList(),
        loadStatus: CalendarLoadStatus = CalendarLoadStatus.EMPTY
    ): AppContentModel {
        val date = LocalDate.of(2028, 2, 29)
        return AppContentModel(
            shell = AppShellUiState(destination, date, CalendarMode.MONTH, false, children, null),
            calendar = CalendarScreenState(
                CalendarMode.MONTH,
                date,
                CalendarRangePolicy.queryRange(date, CalendarMode.MONTH, Locale.US),
                loadStatus = loadStatus
            ),
            eventCrud = EventCrudUiState(),
            locale = Locale.US
        )
    }
}

internal const val CREATE_EVENT_TAG = "clender_create_event"
