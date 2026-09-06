package com.molotov.clender.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.molotov.clender.domain.conversation.ActiveConversationStore
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class DataStoreActiveConversationStoreTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val files = mutableListOf<File>()

    @After
    fun tearDown() {
        scope.cancel()
        files.forEach(File::delete)
    }

    @Test
    fun freshStoreIsNullAndRoundTripTrimsUnicodeSafeId() = runBlocking {
        val (store, _) = storeWithDataStore()

        assertNull(store.activeConversationId.first())
        store.setActiveConversationId("  会话-🌏-0001  ")

        assertEquals("会话-🌏-0001", store.activeConversationId.first())
    }

    @Test
    fun blankIsRejectedWithoutChangingExistingValueAndNullRemovesOnlyTheKey() = runBlocking {
        val (store, dataStore) = storeWithDataStore()
        store.setActiveConversationId("kept")

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.setActiveConversationId(" \t\n") }
        }
        assertEquals("kept", store.activeConversationId.first())

        store.setActiveConversationId(null)

        assertNull(store.activeConversationId.first())
        assertFalse(
            dataStore.data.first().asMap().keys.any {
                it.name == AppPreferenceKeys.ACTIVE_CONVERSATION_ID
            }
        )
    }

    @Test
    fun blankStoredValueIsTreatedAsCorruptAndReturnsNull() = runBlocking {
        val (store, dataStore) = storeWithDataStore()
        dataStore.edit { values ->
            values[stringPreferencesKey(AppPreferenceKeys.ACTIVE_CONVERSATION_ID)] = " \t"
        }

        assertNull(store.activeConversationId.first())
    }

    @Test
    fun ioReadFailureFallsBackToNullButNonIoFailurePropagates() = runBlocking {
        val ioStore = DataStoreActiveConversationStore(FailingReadDataStore(IOException("disk")))
        assertNull(ioStore.activeConversationId.first())

        val failure = IllegalStateException("programming failure")
        val nonIoStore = DataStoreActiveConversationStore(FailingReadDataStore(failure))
        val thrown = assertThrows(IllegalStateException::class.java) {
            runBlocking { nonIoStore.activeConversationId.first() }
        }
        assertEquals(failure, thrown)
    }

    @Test
    fun partialEditPreservesEveryUnrelatedPreference() = runBlocking {
        val (store, dataStore) = storeWithDataStore()
        val unrelated = mapOf(
            AppPreferenceKeys.THEME to "DARK",
            AppPreferenceKeys.APP_FONT_SIZE_SP to "20",
            AppPreferenceKeys.AI_ENDPOINT to "https://example.invalid/base",
            AppPreferenceKeys.WEB_DAV_URL to "https://dav.example.invalid/root/",
            AppPreferenceKeys.LAST_TOP_DESTINATION to "EVENTS"
        )
        dataStore.edit { values ->
            unrelated.forEach { (name, value) -> values[stringPreferencesKey(name)] = value }
        }

        store.setActiveConversationId(" active ")
        store.setActiveConversationId(null)

        val persisted = dataStore.data.first()
        unrelated.forEach { (name, value) ->
            assertEquals(value, persisted[stringPreferencesKey(name)])
        }
        assertNull(persisted[stringPreferencesKey(AppPreferenceKeys.ACTIVE_CONVERSATION_ID)])
    }

    @Test
    fun concurrentUpdatesAreSerializedAndLastCompletedWriteWins() = runBlocking {
        val (store, _) = storeWithDataStore()
        val writes = (0 until 24).map { index ->
            async {
                delay(index.toLong())
                store.setActiveConversationId("id-$index")
            }
        }
        writes.forEach { it.await() }

        assertEquals("id-23", store.activeConversationId.first())
    }

    @Test
    fun fixedPreferencesFileLivesOnlyUnderApplicationSandboxDatastoreDirectory() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val file = DataStoreActiveConversationStore.preferencesFile(context)

        assertEquals(
            File(context.filesDir, "datastore/clender.preferences_pb").canonicalFile,
            file.canonicalFile
        )
        assertTrue(file.canonicalPath.startsWith(context.filesDir.canonicalPath + File.separator))
        assertFalse(file.path.contains("dist${File.separator}data"))
    }

    private fun storeWithDataStore(): Pair<
        ActiveConversationStore,
        DataStore<Preferences>
        > {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.filesDir, "datastore/c1a-${UUID.randomUUID()}.preferences_pb")
        files += file
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
        return DataStoreActiveConversationStore(dataStore) to dataStore
    }
}

private class FailingReadDataStore(private val failure: Throwable) : DataStore<Preferences> {
    override val data: Flow<Preferences> = flow { throw failure }

    override suspend fun updateData(
        transform: suspend (t: Preferences) -> Preferences
    ): Preferences = transform(emptyPreferences())
}
