package com.molotov.clender.ui.event

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.molotov.clender.app.ClenderApplication
import com.molotov.clender.core.model.EventType
import com.molotov.clender.testsupport.RobolectricComposeHost
import com.molotov.clender.ui.foundation.AppearanceUiState
import com.molotov.clender.ui.foundation.ThemeMode
import com.molotov.clender.ui.theme.ClenderTheme
import java.time.LocalDateTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], application = ClenderApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EventEditorScreenTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()
    private val composeHost = RobolectricComposeHost()

    @Before
    fun startComposeHost() {
        composeHost.start()
    }

    @After
    fun closeComposeHost() {
        composeHost.close()
    }

    @Test
    fun editorDisplaysCompletePrefillAndLocalizedFieldLabels() {
        val form = validTimespanForm(
            title = "回填标题 🌏",
            description = "保留空白  \n与 Unicode",
            estimatedDurationInput = "2147483647"
        )
        setEditorContent(form)

        composeRule.onNodeWithTag("event_editor_title").assertTextContains(form.title)
        composeRule.onNodeWithTag("event_editor_description")
            .assertTextContains(form.description)
        composeRule.onNodeWithTag("event_editor_duration")
            .assertTextContains(form.estimatedDurationInput)
        composeRule.onNodeWithTag("event_editor_start_date").assertHasClickAction()
        composeRule.onNodeWithTag("event_editor_start_time").assertHasClickAction()
        composeRule.onNodeWithTag("event_editor_end_date").assertHasClickAction()
        composeRule.onNodeWithTag("event_editor_end_time").assertHasClickAction()
        scrollToTag("event_editor_save")
        composeRule.onNodeWithTag("event_editor_save").assertIsEnabled()
    }

    @Test
    fun switchingToReminderImmediatelyClearsEndAndSwitchingBackRequiresExplicitEnd() {
        var latest = validTimespanForm()
        setEditorContent(latest, onFormChange = { latest = it })

        composeRule.onNodeWithTag("event_editor_type_reminder").performClick()
        composeRule.runOnIdle {
            assertEquals(EventType.REMINDER, latest.eventType)
            assertNull(latest.endTime)
        }

        setEditorContent(latest, onFormChange = { latest = it })
        composeRule.onNodeWithTag("event_editor_type_timespan").performClick()
        composeRule.runOnIdle {
            assertEquals(EventType.TIMESPAN, latest.eventType)
            assertNull(latest.endTime)
        }
    }

    @Test
    fun invalidTitleDurationAndEndShowInlineErrorsAndDisableSave() {
        val privateDescription = "PRIVATE_DESCRIPTION_MUST_NOT_ENTER_ERROR"
        val invalid = validTimespanForm(
            title = " \t",
            description = privateDescription,
            estimatedDurationInput = "2147483648",
            endTime = LocalDateTime.of(2026, 8, 31, 9, 0)
        )

        setEditorContent(invalid)

        scrollToTag("event_editor_title")
        composeRule.onNodeWithTag("event_editor_title_error", useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
        assertNodeTextExcludes("event_editor_title_error", privateDescription)
        scrollToTag("event_editor_duration")
        composeRule.onNodeWithTag("event_editor_duration_error", useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
        assertNodeTextExcludes("event_editor_duration_error", privateDescription)
        scrollToTag("event_editor_end_section")
        composeRule.onNodeWithTag("event_editor_end_error", useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
        assertNodeTextExcludes("event_editor_end_error", privateDescription)
        scrollToTag("event_editor_save")
        composeRule.onNodeWithTag("event_editor_save").assertIsNotEnabled()
    }

    @Test
    fun editingTextPreservesUnicodeAndDescriptionWhitespace() {
        val replacementTitle = "  标题 e\u0301 🌏  "
        val replacementDescription = "  第一行\n第二行\t "
        var latest = validReminderForm()
        setEditorContent(latest, onFormChange = { latest = it })

        composeRule.onNodeWithTag("event_editor_title")
            .performTextReplacement(replacementTitle)
        composeRule.runOnIdle { assertEquals(replacementTitle, latest.title) }
        setEditorContent(latest, onFormChange = { latest = it })
        scrollToTag("event_editor_description")
        composeRule.onNodeWithTag("event_editor_description")
            .performTextReplacement(replacementDescription)

        composeRule.runOnIdle {
            assertEquals(replacementTitle, latest.title)
            assertEquals(replacementDescription, latest.description)
        }
    }

    @Test
    fun savingDisablesEveryMutatingActionAndAnnouncesBusyState() {
        var saves = 0
        setEditorContent(
            form = validReminderForm(),
            onSave = { saves += 1 },
            options = EditorRenderOptions(operation = EventCrudOperation.SAVING)
        )

        scrollToTag("event_editor_busy")
        composeRule.onNodeWithTag("event_editor_busy").assertIsDisplayed()
        scrollToTag("event_editor_save")
        composeRule.onNodeWithTag("event_editor_save").assertIsNotEnabled()
        scrollToTag("event_editor_type_timespan")
        composeRule.onNodeWithTag("event_editor_type_timespan").assertIsNotEnabled()
        scrollToTag("event_editor_start_date")
        composeRule.onNodeWithTag("event_editor_start_date").assertIsNotEnabled()
        composeRule.runOnIdle { assertEquals(0, saves) }
    }

    @Test
    fun boundedSaveErrorKeepsCurrentFormAndNeverDisplaysThrowableOrBody() {
        val form = validReminderForm(description = "PRIVATE_CURRENT_FORM_BODY")
        setEditorContent(
            form = form,
            options = EditorRenderOptions(saveError = EventSaveErrorCode.SAVE_FAILED)
        )

        scrollToTag("event_editor_error")
        composeRule.onNodeWithTag("event_editor_error").assertIsDisplayed()
        assertNodeTextExcludes("event_editor_error", form.description)
        scrollToTag("event_editor_description")
        composeRule.onNodeWithTag("event_editor_description")
            .assertTextContains(form.description)
        composeRule.onNodeWithText("SQLiteException: /data/user/0/private.db")
            .assertDoesNotExist()
    }

    @Test
    fun longFormIsScrollableAtCompactAndExpandedWidthsWithLargeFontsAndThemes() {
        val form = validTimespanForm(description = "长内容 ".repeat(120))
        listOf(360 to ThemeMode.LIGHT, 600 to ThemeMode.SYSTEM, 840 to ThemeMode.DARK)
            .forEach { (width, theme) ->
                setEditorContent(
                    form = form,
                    options = EditorRenderOptions(
                        widthDp = width,
                        themeMode = theme,
                        appFontSizeSp = if (width == 360) 20 else 8,
                        fontScale = 2f
                    )
                )
                composeRule.onNode(hasScrollAction()).assertIsDisplayed()
                scrollToTag("event_editor_save")
                composeRule.onNodeWithTag("event_editor_save").assertIsDisplayed()
            }
    }

    private fun setEditorContent(
        form: EventFormState,
        onFormChange: (EventFormState) -> Unit = {},
        onSave: () -> Unit = {},
        options: EditorRenderOptions = EditorRenderOptions()
    ) {
        composeRule.runOnUiThread {
            composeHost.activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, options.fontScale)
                ) {
                    ClenderTheme(
                        AppearanceUiState(
                            themeMode = options.themeMode,
                            appFontSizeSp = options.appFontSizeSp,
                            widgetFontSizeSp = 13
                        )
                    ) {
                        EventEditorScreen(
                            state = EventCrudUiState(
                                loadStatus = EventCrudLoadStatus.CONTENT,
                                form = form,
                                operation = options.operation,
                                saveError = options.saveError
                            ),
                            onFormChange = onFormChange,
                            onSave = onSave,
                            modifier = Modifier.width(options.widthDp.dp)
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun validReminderForm(
        title: String = "提醒",
        description: String = "说明",
        estimatedDurationInput: String = "0"
    ): EventFormState = EventFormState(
        eventType = EventType.REMINDER,
        title = title,
        startTime = LocalDateTime.of(2026, 8, 31, 9, 0),
        endTime = null,
        description = description,
        estimatedDurationInput = estimatedDurationInput
    )

    private fun assertNodeTextExcludes(tag: String, forbidden: String) {
        val rendered = composeRule.onNodeWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config
            .toString()
        assertFalse("Error semantics leaked private form content", rendered.contains(forbidden))
    }

    private data class EditorRenderOptions(
        val operation: EventCrudOperation = EventCrudOperation.IDLE,
        val saveError: EventSaveErrorCode? = null,
        val widthDp: Int = 360,
        val themeMode: ThemeMode = ThemeMode.SYSTEM,
        val appFontSizeSp: Int = 13,
        val fontScale: Float = 1f
    )

    private fun scrollToTag(tag: String) {
        composeRule.onNodeWithTag("event_editor_scroll")
            .performScrollToNode(hasTestTag(tag))
    }

    private fun validTimespanForm(
        title: String = "时间段",
        description: String = "说明",
        estimatedDurationInput: String = "0",
        endTime: LocalDateTime = LocalDateTime.of(2026, 9, 1, 10, 0)
    ): EventFormState = EventFormState(
        eventType = EventType.TIMESPAN,
        title = title,
        startTime = LocalDateTime.of(2026, 8, 31, 9, 0),
        endTime = endTime,
        description = description,
        estimatedDurationInput = estimatedDurationInput
    )
}
