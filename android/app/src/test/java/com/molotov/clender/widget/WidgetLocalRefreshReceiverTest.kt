package com.molotov.clender.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Intent
import com.molotov.clender.app.AppContainer
import com.molotov.clender.app.ClenderApplication
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], application = ClenderApplication::class)
class WidgetLocalRefreshReceiverTest {
    private val application: ClenderApplication
        get() = RuntimeEnvironment.getApplication() as ClenderApplication

    @After
    fun removeOnlyIsolatedWidgetArtifacts() {
        application.deleteDatabase(AppContainer.DATABASE_NAME)
        preferenceFile().delete()
    }

    @Test
    fun receiverIsDedicatedBroadcastReceiverWithoutProviderInheritance() {
        val receiver = receiverClass()

        assertTrue(BroadcastReceiver::class.java.isAssignableFrom(receiver))
        assertFalse(android.appwidget.AppWidgetProvider::class.java.isAssignableFrom(receiver))
        assertNotNull(
            receiver.getDeclaredMethod(
                "onReceive",
                android.content.Context::class.java,
                Intent::class.java
            )
        )
    }

    @Test
    fun malformedAndUnownedActionsAreRejectedBeforeApplicationContainerInitialization() {
        val receiver = receiverClass().getDeclaredConstructor().newInstance() as BroadcastReceiver
        val target = ComponentName(application, receiverClass())
        val manager = AppWidgetManager.getInstance(application)
        val otherProvider = ComponentName(application.packageName, "other.Provider")
        shadowOf(manager).addBoundWidget(
            OWNED_BY_OTHER_PROVIDER,
            AppWidgetProviderInfo().apply { provider = otherProvider }
        )
        val invalid = listOf(
            Intent(application, receiverClass()),
            Intent(WidgetActionIntentContract.ACTION_LOCAL_REFRESH).apply {
                component = target
                setPackage(application.packageName)
                data = "https://widget/$VALID_WIDGET_ID/refresh".toUri()
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, VALID_WIDGET_ID)
            },
            localRefreshIntent(VALID_WIDGET_ID),
            localRefreshIntent(OWNED_BY_OTHER_PROVIDER)
        )

        assertFalse(application.getDatabasePath(AppContainer.DATABASE_NAME).exists())
        assertFalse(preferenceFile().exists())
        invalid.forEach { receiver.onReceive(application, it) }

        assertFalse(application.getDatabasePath(AppContainer.DATABASE_NAME).exists())
        assertFalse(preferenceFile().exists())
    }

    @Test
    fun validationPrecedesOwnerRuntimeScopeAndGoAsyncAccess() {
        val source = receiverSource()
        val onReceive = functionBody(source, "onReceive", "override\\s+")
        val validateIndex = onReceive.indexOf("WidgetActionIntentContract.validateLocalRefresh")

        assertTrue(validateIndex >= 0)
        listOf("applicationContext", "WidgetProviderRuntimeOwner", "goAsync()")
            .forEach { later ->
                assertTrue(
                    "$later must be accessed only after validation",
                    onReceive.indexOf(later) > validateIndex
                )
            }
        assertFalse(onReceive.substring(0, validateIndex).contains("container"))
        assertFalse(onReceive.substring(0, validateIndex).contains("runtime"))
    }

    @Test
    fun validDispatchUsesExistingApplicationWidgetScopeAndRuntimeOnly() {
        val source = receiverSource()
        val onReceive = functionBody(source, "onReceive", "override\\s+")

        assertTrue(onReceive.contains("WidgetProviderRuntimeOwner"))
        assertTrue(onReceive.contains("widgetProviderScope"))
        assertTrue(onReceive.contains("widgetProviderRuntime"))
        assertTrue(onReceive.contains("localRefresh"))
        assertEquals(1, source.countToken("goAsync()"))
        listOf(
            "CoroutineScope(",
            "Dispatchers.",
            "Executor",
            "GlobalScope",
            "runBlocking",
            "Thread.sleep"
        ).forEach { forbidden -> assertFalse(source.contains(forbidden)) }
    }

    @Test
    fun ownerScopeLaunchRuntimeCancellationTimeoutAndExceptionAllShareFinishOnce() {
        val source = receiverSource()

        assertTrue(source.contains("withTimeout"))
        assertTrue(source.contains("invokeOnCompletion"))
        assertTrue(source.contains("finally"))
        assertTrue(source.contains("compareAndSet(false, true)"))
        assertEquals(1, source.countToken("pendingResult.finish()"))
        assertEquals(1, source.countToken("goAsync()"))
        assertTrue(source.contains("catch (_: Throwable)"))
        val timeout = Regex("(?:RECEIVER_|LOCAL_REFRESH_)?TIMEOUT_MILLIS\\s*=\\s*([0-9_]+)")
            .find(source)
            ?.groupValues
            ?.get(1)
            ?.replace("_", "")
            ?.toLong()
        assertNotNull("receiver timeout must be an explicit finite constant", timeout)
        assertTrue(
            "receiver timeout must stay below the platform ten-second boundary",
            timeout!! in 1..9_500
        )
    }

    @Test
    fun receiverHasNoNetworkAiWebDavMutationEventServiceOrWorkManagerSurface() {
        val source = receiverSource()

        listOf(
            "OkHttp",
            "WebDav",
            "AiCoordinator",
            "Secret",
            "KeyStore",
            "EventService",
            "ScheduleMutation",
            "WorkManager",
            "Room.",
            "DataStore",
            "Notification",
            "Toast",
            "AlarmManager"
        ).forEach { forbidden -> assertFalse(source.contains(forbidden)) }
    }

    @Test
    fun sourceManifestKeepsLocalRefreshFilterlessAndBootExactlyScoped() {
        val document = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(org.xml.sax.InputSource(java.io.StringReader(manifestSource())))
        val nodes = document.getElementsByTagName("receiver")
        val receivers = (0 until nodes.length).map { nodes.item(it) as org.w3c.dom.Element }
        assertEquals(3, receivers.size)
        assertEquals(
            setOf(
                ".widget.ClenderWidgetProvider",
                ".widget.WidgetLocalRefreshReceiver",
                ".widget.WidgetBootReceiver"
            ),
            receivers.map { it.getAttribute("android:name") }.toSet()
        )
        val local = receivers.single {
            it.getAttribute("android:name") == ".widget.WidgetLocalRefreshReceiver"
        }
        val boot = receivers.single {
            it.getAttribute("android:name") == ".widget.WidgetBootReceiver"
        }
        listOf(local, boot).forEach { receiver ->
            assertEquals("true", receiver.getAttribute("android:enabled"))
            assertEquals("false", receiver.getAttribute("android:exported"))
            assertEquals(3, receiver.attributes.length)
        }
        assertEquals(0, local.getElementsByTagName("*").length)
        assertEquals(2, boot.getElementsByTagName("*").length)
        val filters = boot.getElementsByTagName("intent-filter")
        assertEquals(1, filters.length)
        assertEquals(0, filters.item(0).attributes.length)
        val actions = (filters.item(0) as org.w3c.dom.Element).getElementsByTagName("action")
        assertEquals(1, actions.length)
        val action = actions.item(0) as org.w3c.dom.Element
        assertEquals(1, action.attributes.length)
        assertEquals(Intent.ACTION_BOOT_COMPLETED, action.getAttribute("android:name"))
    }

    private fun localRefreshIntent(appWidgetId: Int): Intent =
        Intent(application, receiverClass()).apply {
            setPackage(application.packageName)
            action = WidgetActionIntentContract.ACTION_LOCAL_REFRESH
            data = "clender-internal://widget/$appWidgetId/refresh".toUri()
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }

    private fun receiverClass(): Class<*> =
        Class.forName("com.molotov.clender.widget.WidgetLocalRefreshReceiver")

    private fun receiverSource(): String = requiredSource(RECEIVER_SOURCE_PATH)

    private fun manifestSource(): String = requiredSource(MANIFEST_SOURCE_PATH)

    private fun requiredSource(path: String): String =
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .flatMap { root -> sequenceOf(File(root, path), File(root, "android/$path")) }
            .firstOrNull(File::isFile)
            ?.readText()
            ?: error("required source is missing: $path")

    private fun functionBody(source: String, function: String, prefix: String): String {
        val declaration = Regex("${prefix}fun\\s+$function\\s*\\(").find(source)
            ?: error("missing function source: $function")
        val openBrace = source.indexOf('{', declaration.range.last)
        require(openBrace >= 0) { "missing function body: $function" }
        var depth = 0
        for (index in openBrace until source.length) {
            when (source[index]) {
                '{' -> depth += 1

                '}' -> {
                    depth -= 1
                    if (depth == 0) return source.substring(openBrace, index + 1)
                }
            }
        }
        error("unterminated function body: $function")
    }

    private fun String.countToken(token: String): Int = windowed(token.length).count { it == token }

    private fun String.toUri(): android.net.Uri = android.net.Uri.parse(this)

    private fun preferenceFile(): File =
        File(application.filesDir, AppContainer.PREFERENCES_RELATIVE_PATH)

    private companion object {
        const val VALID_WIDGET_ID = 731
        const val OWNED_BY_OTHER_PROVIDER = 732
        const val RECEIVER_SOURCE_PATH =
            "app/src/main/java/com/molotov/clender/widget/WidgetLocalRefreshReceiver.kt"
        const val MANIFEST_SOURCE_PATH = "app/src/main/AndroidManifest.xml"
    }
}
