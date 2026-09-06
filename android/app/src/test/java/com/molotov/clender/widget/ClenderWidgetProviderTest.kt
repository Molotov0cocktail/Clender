package com.molotov.clender.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import com.molotov.clender.app.AppContainer
import com.molotov.clender.app.ClenderApplication
import java.io.File
import java.lang.reflect.Modifier
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], application = ClenderApplication::class)
class ClenderWidgetProviderTest {
    private val application: ClenderApplication
        get() = RuntimeEnvironment.getApplication() as ClenderApplication

    @After
    fun removeOnlyIsolatedWidgetArtifacts() {
        application.deleteDatabase(AppContainer.DATABASE_NAME)
        preferenceFile().delete()
    }

    @Test
    fun providerIsAnAppWidgetProviderWithOnlyTheThreeP2aCallbacks() {
        val provider = providerClass()

        assertTrue(AppWidgetProvider::class.java.isAssignableFrom(provider))
        val declaredCallbackNames = provider.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) }
            .map { it.name }
            .filter { it in CALLBACK_NAMES + FORBIDDEN_CALLBACK_NAMES }
            .toSet()
        assertEquals(CALLBACK_NAMES, declaredCallbackNames)
    }

    @Test
    fun callbacksHaveOnlyTheSystemSignatures() {
        val provider = providerClass()

        assertTrue(
            provider.getDeclaredMethod(
                "onUpdate",
                Context::class.java,
                AppWidgetManager::class.java,
                IntArray::class.java
            ).returnType == Void.TYPE
        )
        assertTrue(
            provider.getDeclaredMethod(
                "onAppWidgetOptionsChanged",
                Context::class.java,
                AppWidgetManager::class.java,
                Int::class.javaPrimitiveType,
                android.os.Bundle::class.java
            ).returnType == Void.TYPE
        )
        assertTrue(
            provider.getDeclaredMethod(
                "onDeleted",
                Context::class.java,
                IntArray::class.java
            ).returnType == Void.TYPE
        )
    }

    @Test
    fun providerCanBeConstructedWithoutOpeningWidgetOrAiRuntime() {
        val databaseFile = application.getDatabasePath(AppContainer.DATABASE_NAME)
        assertFalse(databaseFile.exists())
        assertFalse(preferenceFile().exists())

        providerClass().getDeclaredConstructor().newInstance()

        assertFalse(databaseFile.exists())
        assertFalse(preferenceFile().exists())
    }

    @Test
    fun everyCallbackOwnsOneGoAsyncAndDelegatesToTheSharedFinallyFinisher() {
        val source = providerSource()

        CALLBACK_NAMES.forEach { callback ->
            val body = callbackBody(source, callback)
            assertEquals(
                "$callback must acquire exactly one PendingResult",
                1,
                body.countToken("goAsync()")
            )
            assertEquals(
                "$callback must delegate once to the shared async owner",
                1,
                body.countToken("launchCallback(")
            )
        }
        assertEquals(CALLBACK_NAMES.size, source.countToken("goAsync()"))
        val launcher = functionBody(source, "launchCallback")
        assertEquals(1, launcher.countToken("finally"))
        assertTrue(launcher.contains("invokeOnCompletion"))
        assertFalse(launcher.contains("CoroutineStart.UNDISPATCHED"))
        assertTrue(source.contains("compareAndSet(false, true)"))
        assertEquals(1, source.countToken("pendingResult.finish()"))
        assertFalse(source.contains("runBlocking"))
        assertFalse(source.contains("Thread.sleep"))
        assertFalse(source.contains(".first("))
    }

    @Test
    fun providerDoesNotOverrideReceiveOrCreateActions() {
        val source = providerSource()

        listOf(
            "onReceive",
            "onEnabled",
            "onDisabled",
            "onRestored",
            "PendingIntent",
            "setOnClickPendingIntent",
            "fillInIntent",
            "RemoteViewsService",
            "Intent(",
            "Uri.parse",
            "query="
        )
            .forEach { forbidden -> assertFalse(source.contains(forbidden)) }
    }

    @Test
    fun providerDoesNotInitializeRoomSecretAiWebDavOrNetworkInConstructor() {
        val source = providerSource()

        listOf(
            "Room.",
            "Keystore",
            "KeyStore",
            "OkHttp",
            "WebDav",
            "AiCoordinator",
            "DataStore",
            "databaseBuilder",
            "PreferenceDataStoreFactory"
        )
            .forEach { forbidden -> assertFalse(source.contains(forbidden)) }
    }

    private fun providerClass(): Class<*> =
        Class.forName("com.molotov.clender.widget.ClenderWidgetProvider")

    private fun providerSource(): String =
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { current ->
            current.parentFile
        }.flatMap { root ->
            sequenceOf(
                File(root, PROVIDER_SOURCE_PATH),
                File(root, "android/$PROVIDER_SOURCE_PATH")
            )
        }.firstOrNull(File::isFile)?.readText()
            ?: error("required Provider source is missing")

    private fun callbackBody(source: String, callback: String): String =
        functionBody(source, callback, "override\\s+")

    private fun functionBody(
        source: String,
        function: String,
        prefix: String = "(?:private\\s+)?"
    ): String {
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

    private fun preferenceFile(): File =
        File(application.filesDir, AppContainer.PREFERENCES_RELATIVE_PATH)

    private companion object {
        val CALLBACK_NAMES = setOf("onUpdate", "onAppWidgetOptionsChanged", "onDeleted")
        val FORBIDDEN_CALLBACK_NAMES = setOf("onReceive", "onEnabled", "onDisabled", "onRestored")
        const val PROVIDER_SOURCE_PATH =
            "app/src/main/java/com/molotov/clender/widget/ClenderWidgetProvider.kt"
    }
}
