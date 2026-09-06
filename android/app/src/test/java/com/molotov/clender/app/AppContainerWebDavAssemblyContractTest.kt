package com.molotov.clender.app

import com.molotov.clender.app.ai.AiOperationGate
import com.molotov.clender.app.sync.SyncState
import com.molotov.clender.app.sync.WebDavOperationGate
import com.molotov.clender.data.settings.DataStoreActiveConversationStore
import com.molotov.clender.data.settings.DataStoreAppPreferences
import java.io.File
import java.lang.reflect.Modifier
import java.util.ArrayDeque
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * T46-C2b container assembly: the webdav runtime is started lazily (first
 * state observation), observes only non-secret DataStore state while disabled
 * (zero Room/secret/network), reuses the container DataStore and keeps its own
 * gate distinct from the AI gate; close() deterministically shuts the runtime
 * down before the database.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], application = ClenderApplication::class)
class AppContainerWebDavAssemblyContractTest {
    private val containers = mutableListOf<AppContainer>()

    private val application: ClenderApplication
        get() = RuntimeEnvironment.getApplication() as ClenderApplication

    @After
    fun removeOnlyIsolatedAndroidSandboxArtifacts() {
        containers.asReversed().forEach(AppContainer::close)
        application.deleteDatabase(AppContainer.DATABASE_NAME)
        File(application.filesDir, "datastore").deleteRecursively()
    }

    @Test
    fun disabledColdStartStartsRuntimeWithoutRoomSecretOrNetworkTouch() = runBlocking {
        val container = createContainer()
        val databaseFile = application.getDatabasePath(AppContainer.DATABASE_NAME)

        try {
            assertFalse(databaseFile.exists())
            container.webDavSyncState
            withTimeout(5_000) {
                while (container.webDavSyncState.value !is SyncState.Disabled) delay(5)
            }
            assertEquals(SyncState.Disabled, container.webDavSyncState.value)
            assertFalse(
                "Runtime observation must not open the production database",
                databaseFile.exists()
            )
            assertTrue(initializedObjectGraph(container).none(::isAiRuntimeObject))
        } finally {
            container.close()
        }
    }

    @Test
    fun webdavRuntimeReusesOneDataStoreAndOwnsADistinctGate() {
        val container = createContainer()
        try {
            container.webDavSyncState
            container.activeConversationStore.activeConversationId
            val graph = initializedObjectGraph(container)
            val gates = graph.filterIsInstance<WebDavOperationGate>()
            assertEquals("container must assemble exactly one webdav gate", 1, gates.size)
            val aiGates = graph.filterIsInstance<AiOperationGate>()
            assertTrue(
                "webdav gate must be distinct from the AI operation gate",
                aiGates.none { aiGate ->
                    (aiGate as Any) === (gates.single() as Any)
                }
            )
            val appPreferences = graph.filterIsInstance<DataStoreAppPreferences>().single()
            val activeStore = graph.filterIsInstance<DataStoreActiveConversationStore>().single()
            assertSame(dataStoreOf(activeStore), dataStoreOf(appPreferences))
        } finally {
            container.close()
        }
    }

    @Test
    fun closeIdempotentlyShutsDownRuntimeClosesGateAndKeepsAiDataStoreRoomOrdering() {
        val container = createContainer()
        try {
            container.webDavSyncState
            val graph = initializedObjectGraph(container)
            val gate = graph.filterIsInstance<WebDavOperationGate>().single()
            assertTrue(gate.tryAcquire() != null)

            container.close()
            container.close()

            assertFalse("gate must be closed after container close", gate.tryAcquire() != null)
            assertTrue(container.scheduleMutationVersion.value == 0L)
        } finally {
            container.close()
        }
    }

    private fun createContainer(): AppContainer = AppContainer(application).also(containers::add)

    private fun isAiRuntimeObject(value: Any): Boolean {
        val name = value::class.java.name
        return name.endsWith(".AiCoordinator") ||
            name.endsWith(".OkHttpAiClient") ||
            name.endsWith(".EnvelopeSecretStore") ||
            name.endsWith(".AiSubmissionGateway")
    }

    private fun dataStoreOf(owner: Any): Any {
        val field = owner.javaClass.getDeclaredField("dataStore")
        field.isAccessible = true
        return checkNotNull(field.get(owner))
    }

    private fun initializedObjectGraph(root: Any): Set<Any> {
        val visited = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<Any, Boolean>()
        )
        val queue = ArrayDeque<Any>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!visited.add(current)) continue
            enqueueReachable(current, queue)
        }
        return visited
    }

    private fun enqueueReachable(current: Any, queue: ArrayDeque<Any>) {
        allFields(current.javaClass).forEach { field ->
            if (Modifier.isStatic(field.modifiers)) return@forEach
            if (!field.isAccessible && !runCatching { field.isAccessible = true }.isSuccess) {
                return@forEach
            }
            val value = runCatching { field.get(current) }.getOrNull() ?: return@forEach
            when (value) {
                is Lazy<*> -> if (value.isInitialized()) value.value?.let(queue::add)
                else -> if (shouldTraverse(value)) queue.add(value)
            }
        }
    }

    private fun allFields(type: Class<*>): Sequence<java.lang.reflect.Field> = sequence {
        var current: Class<*>? = type
        while (current != null && current != Any::class.java) {
            yieldAll(current.declaredFields.asSequence())
            current = current.superclass
        }
    }

    private fun shouldTraverse(value: Any): Boolean {
        val name = value::class.java.name
        return name.startsWith("com.molotov.clender.") ||
            value is kotlinx.coroutines.CoroutineScope
    }
}
