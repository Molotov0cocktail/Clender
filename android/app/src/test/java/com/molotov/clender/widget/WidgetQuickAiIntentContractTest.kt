package com.molotov.clender.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import com.molotov.clender.app.AppContainer
import com.molotov.clender.app.ClenderApplication
import com.molotov.clender.domain.widget.WidgetActionSpec
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], application = ClenderApplication::class)
class WidgetQuickAiIntentContractTest {
    private val context: ClenderApplication
        get() = RuntimeEnvironment.getApplication() as ClenderApplication

    @Test
    fun exactOwnedQuickAiTokenUsesOnlyCanonicalIdentityAtPositiveIntBoundaries() {
        listOf(1, 71, Int.MAX_VALUE).forEach { widgetId ->
            bind(widgetId)
            val token = WidgetPendingIntentFactory.create(
                context,
                WidgetActionSpec.QuickAi(widgetId)
            )
            val shadow = shadowOf(token)
            assertTrue(shadow.isActivity)
            assertFalse(shadow.isBroadcast)
            assertFalse(shadow.isService)
            assertTrue(shadow.isImmutable)
            assertEquals(0, shadow.requestCode)
            assertEquals(
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                shadow.flags
            )
            assertEnvelope(shadow.savedIntent, widgetId, QUICK_ACTION, QUICK_ACTIVITY)
            assertEquals(
                WidgetActionSpec.QuickAi(widgetId),
                WidgetActionIntentContract.validateQuickAi(context, shadow.savedIntent)
            )
        }
        assertNoRuntimeFiles()
    }

    @Test
    fun quickAiRejectsEveryMalformedEnvelopeAndNonCanonicalIdentity() {
        bind(71)
        invalidIntents(quickIntent(71), allowNewTask = true).forEachIndexed { index, intent ->
            assertNull(
                "invalid case $index",
                WidgetActionIntentContract.validateQuickAi(context, intent)
            )
        }
        assertNoRuntimeFiles()
    }

    @Test
    fun quickAiAcceptsSystemDeliveredExcludeFromRecentsWithoutChangingCanonicalToken() {
        bind(71)
        val original = shadowOf(
            WidgetPendingIntentFactory.create(context, WidgetActionSpec.QuickAi(71))
        ).savedIntent
        assertEnvelope(original, 71, QUICK_ACTION, QUICK_ACTIVITY)
        val delivered = Intent(original).addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)

        // API 26 Launcher token 0x24000000 arrives at onCreate as 0x24800000.
        assertEquals(0x24800000, delivered.flags)
        assertEquals(
            WidgetActionSpec.QuickAi(71),
            WidgetActionIntentContract.validateQuickAi(context, delivered)
        )
        assertEnvelope(original, 71, QUICK_ACTION, QUICK_ACTIVITY)
        assertNoRuntimeFiles()
    }

    @Test
    fun quickAiAcceptsSystemNewTaskWithOrWithoutExcludeWithoutChangingToken() {
        bind(71)
        val original = shadowOf(
            WidgetPendingIntentFactory.create(context, WidgetActionSpec.QuickAi(71))
        ).savedIntent
        listOf(0x34000000, 0x34800000, 0x34400000, 0x34c00000).forEach { deliveredFlags ->
            val delivered = Intent(original).apply { flags = deliveredFlags }
            assertEquals(
                WidgetActionSpec.QuickAi(71),
                WidgetActionIntentContract.validateQuickAi(context, delivered)
            )
            assertEnvelope(original, 71, QUICK_ACTION, QUICK_ACTIVITY)
        }
        assertNoRuntimeFiles()
    }

    @Test
    fun quickAiRejectsEveryOtherFlagBitForOriginalAndSystemDeliveredIntents() {
        bind(71)
        val original = quickIntent(71)
        val allowedFlags = listOf(
            0x24000000,
            0x24800000,
            0x34000000,
            0x34800000,
            0x34400000,
            0x34c00000
        )
        allowedFlags.forEach { flags ->
            val canonical = Intent(original).apply { this.flags = flags }
            (0 until Int.SIZE_BITS).map { 1 shl it }
                .filter { (flags xor it) !in allowedFlags }
                .forEach { flag ->
                    val invalid = Intent(canonical).apply { this.flags = canonical.flags xor flag }
                    assertNull(
                        "base flags ${canonical.flags}, toggled flag $flag",
                        WidgetActionIntentContract.validateQuickAi(context, invalid)
                    )
                }
            invalidIntents(canonical, allowNewTask = true).forEachIndexed { index, intent ->
                assertNull(
                    "system delivered flags $flags invalid envelope $index",
                    WidgetActionIntentContract.validateQuickAi(context, intent)
                )
            }
        }
        assertNoRuntimeFiles()
    }

    @Test
    fun systemTaskFlagsRemainForbiddenForInternalNavigationAndLocalRefresh() {
        bind(71)
        listOf(
            Intent.FLAG_ACTIVITY_NEW_TASK,
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
        ).forEach { flags ->
            WidgetQuickAiDestination.entries.forEach { destination ->
                val navigation = WidgetActionIntentContract.quickAiNavigationIntent(
                    context,
                    71,
                    destination
                ).addFlags(flags)
                assertNull(
                    WidgetActionIntentContract.validateQuickAiNavigation(context, navigation)
                )
            }
            val refresh = shadowOf(
                WidgetPendingIntentFactory.create(context, WidgetActionSpec.LocalRefresh(71))
            ).savedIntent
            assertNull(
                WidgetActionIntentContract.validateLocalRefresh(
                    context,
                    Intent(refresh).addFlags(flags)
                )
            )
        }
        assertNoRuntimeFiles()
    }

    @Test
    fun excludeFromRecentsRemainsForbiddenForNavigationEditAndRefresh() {
        bind(71)
        WidgetQuickAiDestination.entries.forEach { destination ->
            val intent = WidgetActionIntentContract.quickAiNavigationIntent(
                context,
                71,
                destination
            )
            assertEquals(
                WidgetQuickAiNavigation(71, destination),
                WidgetActionIntentContract.validateQuickAiNavigation(context, intent)
            )
            assertNull(
                WidgetActionIntentContract.validateQuickAiNavigation(
                    context,
                    Intent(intent).addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                )
            )
        }
        val edit = shadowOf(
            WidgetPendingIntentFactory.create(context, WidgetActionSpec.EditEvent(71, 1))
        ).savedIntent
        val refresh = shadowOf(
            WidgetPendingIntentFactory.create(context, WidgetActionSpec.LocalRefresh(71))
        ).savedIntent
        assertEquals(
            WidgetActionSpec.EditEvent(71, 1),
            WidgetActionIntentContract.validateEditEvent(context, edit)
        )
        assertEquals(
            WidgetActionSpec.LocalRefresh(71),
            WidgetActionIntentContract.validateLocalRefresh(context, refresh)
        )
        assertNull(
            WidgetActionIntentContract.validateEditEvent(
                context,
                Intent(edit).addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            )
        )
        assertNull(
            WidgetActionIntentContract.validateLocalRefresh(
                context,
                Intent(refresh).addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            )
        )
        assertNoRuntimeFiles()
    }

    @Test
    fun navigationBuilderProducesExactFiniteTargetsAndRoundTripsOwnedInstances() {
        assertEquals(2, WidgetQuickAiDestination.entries.size)
        listOf(1, 71, Int.MAX_VALUE).forEach { widgetId ->
            bind(widgetId)
            WidgetQuickAiDestination.entries.forEach { destination ->
                val intent = WidgetActionIntentContract.quickAiNavigationIntent(
                    context,
                    widgetId,
                    destination
                )
                val action = when (destination) {
                    WidgetQuickAiDestination.CONVERSATION -> OPEN_CONVERSATION
                    WidgetQuickAiDestination.SETTINGS -> OPEN_SETTINGS
                }
                assertEnvelope(intent, widgetId, action, MAIN_ACTIVITY)
                assertEquals(
                    WidgetQuickAiNavigation(widgetId, destination),
                    WidgetActionIntentContract.validateQuickAiNavigation(context, intent)
                )
                assertNull(WidgetActionIntentContract.validateQuickAi(context, intent))
                assertNull(WidgetActionIntentContract.validateEditEvent(context, intent))
                assertNull(WidgetActionIntentContract.validateLocalRefresh(context, intent))
            }
        }
        assertNoRuntimeFiles()
    }

    @Test
    fun bothMainActivityTargetsRejectMalformedEnvelopeAndWrongOwner() {
        bind(71)
        WidgetQuickAiDestination.entries.forEach { destination ->
            val canonical = WidgetActionIntentContract.quickAiNavigationIntent(
                context,
                71,
                destination
            )
            invalidIntents(canonical).forEachIndexed { index, intent ->
                assertNull(
                    "$destination invalid case $index",
                    WidgetActionIntentContract.validateQuickAiNavigation(context, intent)
                )
            }
        }
        assertNull(WidgetActionIntentContract.validateQuickAiNavigation(context, quickIntent(71)))
        assertNoRuntimeFiles()
    }

    @Test
    fun ownershipIsRecheckedForRepeatedQuickAiAndNavigationAfterInstanceDisappears() {
        bind(71)
        val quick = quickIntent(71)
        val navigation = WidgetQuickAiDestination.entries.map {
            WidgetActionIntentContract.quickAiNavigationIntent(context, 71, it)
        }
        assertEquals(
            WidgetActionSpec.QuickAi(71),
            WidgetActionIntentContract.validateQuickAi(context, quick)
        )
        shadowOf(AppWidgetManager.getInstance(context)).putWidgetInfo(71, AppWidgetProviderInfo())
        assertNull(WidgetActionIntentContract.validateQuickAi(context, quick))
        navigation.forEach {
            assertNull(WidgetActionIntentContract.validateQuickAiNavigation(context, it))
        }
        assertNoRuntimeFiles()
    }

    @Test
    fun navigationBuilderRejectsNonPositiveIds() {
        listOf(0, -1, Int.MIN_VALUE).forEach { id ->
            WidgetQuickAiDestination.entries.forEach { destination ->
                assertThrows(IllegalArgumentException::class.java) {
                    WidgetActionIntentContract.quickAiNavigationIntent(context, id, destination)
                }
            }
        }
    }

    private fun invalidIntents(canonical: Intent, allowNewTask: Boolean = false): List<Intent> {
        bind(72, ComponentName(context.packageName, "example.OtherProvider"))
        val envelope = listOf<Intent.() -> Unit>(
            { action = null },
            { action = "${canonical.action} " },
            { action = canonical.action?.lowercase() },
            { action = WidgetActionIntentContract.ACTION_EDIT_EVENT },
            { component = null },
            { component = ComponentName("example.other", QUICK_ACTIVITY) },
            { component = ComponentName(context, ClenderWidgetProvider::class.java) },
            { `package` = null },
            { setPackage("example.other") },
            { flags = 0 },
            { flags = Intent.FLAG_ACTIVITY_CLEAR_TOP },
            { addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) },
            { data = null },
            { removeExtra(AppWidgetManager.EXTRA_APPWIDGET_ID) },
            { putExtra("draft", "synthetic forbidden payload") },
            { putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, "71") },
            { putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 71L) },
            { putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, Int.MAX_VALUE.toLong() + 1) },
            { putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0) },
            { putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1) },
            { putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 72) },
            { addCategory(Intent.CATEGORY_DEFAULT) },
            { clipData = ClipData.newPlainText("test", "payload") },
            {
                setPackage(null)
                selector = Intent(Intent.ACTION_VIEW)
            }
        ).map { change -> Intent(canonical).apply(change) }
        val malformed = malformedIdentities().map { raw ->
            Intent(canonical).apply { data = Uri.parse(raw) }
        }
        val unowned = listOf(72, 73).map { id ->
            Intent(canonical).apply {
                data = Uri.parse("clender-internal://widget/$id/quick-ai")
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            }
        }
        val forbiddenTaskFlag = if (allowNewTask) {
            emptyList()
        } else {
            listOf(Intent(canonical).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        return envelope + malformed + unowned + forbiddenTaskFlag
    }

    private fun malformedIdentities(): List<String> = listOf(
        "",
        " clender-internal://widget/71/quick-ai",
        "clender-internal://widget/71/quick-ai ",
        "CLENDER-internal://widget/71/quick-ai",
        "clender-internal://WIDGET/71/quick-ai",
        "clender-internal://widget/071/quick-ai",
        "clender-internal://widget/%37%31/quick-ai",
        "clender-internal://widget/0/quick-ai",
        "clender-internal://widget/-1/quick-ai",
        "clender-internal://widget/2147483648/quick-ai",
        "clender-internal://widget/71/quick%2Dai",
        "clender-internal://widget/71/quick-ai/",
        "clender-internal://widget/71/quick-ai/extra",
        "clender-internal://widget/71/quick-ai?q=x",
        "clender-internal://widget/71/quick-ai#x",
        "clender-internal://widget/71/edit/1",
        "clender-internal://widget/71/configure",
        "clender-internal://widget/71/refresh",
        "https://widget/71/quick-ai"
    )

    private fun quickIntent(widgetId: Int): Intent = Intent(QUICK_ACTION).apply {
        component = ComponentName(context.packageName, QUICK_ACTIVITY)
        setPackage(context.packageName)
        data = Uri.parse("clender-internal://widget/$widgetId/quick-ai")
        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }

    private fun assertEnvelope(intent: Intent, widgetId: Int, action: String, className: String) {
        assertEquals(action, intent.action)
        assertEquals(ComponentName(context.packageName, className), intent.component)
        assertEquals(context.packageName, intent.`package`)
        assertEquals("clender-internal://widget/$widgetId/quick-ai", intent.dataString)
        assertEquals(
            Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            intent.flags
        )
        assertEquals(setOf(AppWidgetManager.EXTRA_APPWIDGET_ID), intent.extras?.keySet())
        assertEquals(widgetId, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
        assertNull(intent.clipData)
        assertNull(intent.selector)
        assertTrue(intent.categories.isNullOrEmpty())
    }

    private fun bind(
        id: Int,
        provider: ComponentName = ComponentName(context, ClenderWidgetProvider::class.java)
    ) {
        shadowOf(AppWidgetManager.getInstance(context)).putWidgetInfo(
            id,
            AppWidgetProviderInfo().apply { this.provider = provider }
        )
    }

    private fun assertNoRuntimeFiles() {
        assertFalse(context.getDatabasePath(AppContainer.DATABASE_NAME).exists())
        assertFalse(File(context.filesDir, AppContainer.PREFERENCES_RELATIVE_PATH).exists())
    }

    private companion object {
        const val QUICK_ACTION = "com.molotov.clender.action.WIDGET_QUICK_AI"
        const val OPEN_CONVERSATION = "com.molotov.clender.action.WIDGET_QUICK_AI_OPEN_CONVERSATION"
        const val OPEN_SETTINGS = "com.molotov.clender.action.WIDGET_QUICK_AI_OPEN_SETTINGS"
        const val QUICK_ACTIVITY = "com.molotov.clender.widget.QuickAiActivity"
        const val MAIN_ACTIVITY = "com.molotov.clender.app.MainActivity"
    }
}
