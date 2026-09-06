package com.molotov.clender.app

import android.database.sqlite.SQLiteDatabase
import com.molotov.clender.data.local.ClenderDatabase
import com.molotov.clender.data.local.RoomConversationRepository
import com.molotov.clender.data.local.RoomEventRepository
import java.io.File
import java.util.concurrent.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], application = ClenderApplication::class)
class AppContainerConversationTest {
    private val containers = mutableListOf<AppContainer>()

    private val application: ClenderApplication
        get() = RuntimeEnvironment.getApplication() as ClenderApplication

    @After
    fun removeOnlyIsolatedAndroidSandboxArtifacts() {
        containers.asReversed().forEach(AppContainer::close)
        application.deleteDatabase(AppContainer.DATABASE_NAME)
        dataStoreFile().parentFile?.deleteRecursively()
    }

    @Test
    fun eventAndConversationRepositoriesShareOneLazyRoomV1Database() {
        val databaseFile = application.getDatabasePath(AppContainer.DATABASE_NAME)
        val container = createContainer()

        assertFalse(databaseFile.exists())
        val eventRepository = container.eventRepository
        val conversationRepository = container.conversationRepository
        assertTrue(eventRepository is RoomEventRepository)
        assertTrue(conversationRepository is RoomConversationRepository)
        assertFalse(databaseFile.exists())

        runBlocking { conversationRepository.listConversations() }

        assertTrue(databaseFile.exists())
        assertSame(roomDatabase(eventRepository), roomDatabase(conversationRepository))
        SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READONLY).use {
            val tables = it.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name IN ('events','conversations','messages')",
                null
            ).use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(0))
                }
            }
            assertEquals(setOf("events", "conversations", "messages"), tables)
        }
        container.close()
    }

    @Test
    fun calendarColdStartKeepsConversationAndDataStoreSideEffectsLazy() {
        val container = createContainer()
        val preferenceFile = dataStoreFile()

        assertFalse(preferenceFile.exists())
        container.eventRepository
        assertFalse(preferenceFile.exists())
        container.activeConversationStore
        assertFalse(preferenceFile.exists())

        assertTrue(runBlocking { container.conversationRepository.listConversations().isEmpty() })
        assertFalse(preferenceFile.exists())
        container.close()
    }

    @Test
    fun activeSelectionUsesFixedSandboxDataStoreAndCloseCancelsItsScope() {
        val container = createContainer()
        val preferenceFile = dataStoreFile().canonicalFile
        val sandbox = File(requireNotNull(application.applicationInfo.dataDir)).canonicalFile
        val repositoryRoot = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        val windowsData = File(repositoryRoot, "../data").canonicalFile
        val packagedData = File(repositoryRoot, "../dist/data").canonicalFile

        assertTrue(preferenceFile.toPath().startsWith(sandbox.toPath()))
        assertFalse(preferenceFile.toPath().startsWith(windowsData.toPath()))
        assertFalse(preferenceFile.toPath().startsWith(packagedData.toPath()))

        runBlocking {
            container.activeConversationStore.setActiveConversationId(
                "0123456789abcdef0123456789abcdef"
            )
            assertEquals(
                "0123456789abcdef0123456789abcdef",
                container.activeConversationStore.activeConversationId.first()
            )
        }
        assertEquals("clender.preferences_pb", preferenceFile.name)
        assertEquals("datastore", preferenceFile.parentFile?.name)
        assertTrue(preferenceFile.exists())

        container.close()
        val failure = runCatching {
            runBlocking {
                container.activeConversationStore.setActiveConversationId(
                    "fedcba9876543210fedcba9876543210"
                )
            }
        }.exceptionOrNull()
        assertNotNull("A closed container must reject new DataStore writes", failure)
        assertTrue(
            failure is CancellationException || failure?.cause is CancellationException
        )
    }

    @Test
    fun offlineConversationContainerDoesNotEagerlyInitializeAiSecretOrNetworkObjects() {
        val container = createContainer()

        container.conversationRepository
        container.activeConversationStore

        val initializedValues = container.javaClass.declaredFields.mapNotNull { field ->
            field.isAccessible = true
            when (val value = field.get(container)) {
                is Lazy<*> -> value.takeIf(Lazy<*>::isInitialized)?.value
                else -> value
            }
        }
        val initializedTypes = initializedValues.map { it::class.java.name }
        assertTrue(initializedTypes.none { it.contains("AiCoordinator") })
        assertTrue(initializedTypes.none { it.contains("SecretStore") })
        assertTrue(initializedTypes.none { it.contains("OkHttp", ignoreCase = true) })
        assertTrue(initializedTypes.none { it.contains("network", ignoreCase = true) })
        container.close()
    }

    private fun dataStoreFile(): File =
        File(application.filesDir, "datastore/clender.preferences_pb")

    private fun createContainer(): AppContainer = AppContainer(application).also(containers::add)

    private fun roomDatabase(repository: Any): ClenderDatabase {
        val field = repository.javaClass.getDeclaredField("database")
        field.isAccessible = true
        return field.get(repository) as ClenderDatabase
    }
}
