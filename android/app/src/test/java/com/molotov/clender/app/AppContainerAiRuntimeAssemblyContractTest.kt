package com.molotov.clender.app

import androidx.datastore.core.DataStore
import com.molotov.clender.app.ai.AiSubmissionDecision
import com.molotov.clender.app.ai.AiSubmissionGateway
import com.molotov.clender.data.settings.DataStoreActiveConversationStore
import com.molotov.clender.data.settings.DataStoreAppPreferences
import java.io.File
import java.lang.reflect.Modifier
import java.util.ArrayDeque
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.After
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
class AppContainerAiRuntimeAssemblyContractTest {
    private val containers = mutableListOf<AppContainer>()

    private val application: ClenderApplication
        get() = RuntimeEnvironment.getApplication() as ClenderApplication

    @After
    fun removeOnlyIsolatedAndroidSandboxArtifacts() {
        containers.asReversed().forEach(AppContainer::close)
        application.deleteDatabase(AppContainer.DATABASE_NAME)
        preferenceFile().parentFile?.deleteRecursively()
    }

    @Test
    fun calendarColdStartKeepsAiPreferencesSecretsKeystoreAndNetworkLazy() {
        val container = createContainer()
        val databaseFile = application.getDatabasePath(AppContainer.DATABASE_NAME)

        try {
            assertFalse(databaseFile.exists())
            assertFalse(preferenceFile().exists())

            container.eventRepository
            container.scheduleMutationVersion

            assertFalse(databaseFile.exists())
            assertFalse(preferenceFile().exists())
            assertTrue(initializedObjectGraph(container).none(::isAiRuntimeObject))
            assertNotNull(
                "C1b must expose one lazy gateway without activating it during calendar startup",
                gatewayGetter(container)
            )
        } finally {
            container.close()
        }
    }

    @Test
    fun aiAndQuickAiShareOneLazyAppScopedSubmissionGateway() {
        val container = createContainer()

        try {
            assertFalse(preferenceFile().exists())
            val getter = requireNotNull(gatewayGetter(container))
            val fromAi = getter.invoke(container)
            val fromQuickAi = getter.invoke(container)

            assertNotNull(fromAi)
            assertSame(fromAi, fromQuickAi)
            assertFalse(
                "Constructing the runtime must not read or write DataStore",
                preferenceFile().exists()
            )
        } finally {
            container.close()
        }
    }

    @Test
    fun productionGatewayUsesRealClientSecretStoreCoordinatorAndSharedEventService() {
        val container = createContainer()

        try {
            val gateway = requireNotNull(requireNotNull(gatewayGetter(container)).invoke(container))
            val graph = initializedObjectGraph(gateway)
            val names = graph.map { it::class.java.name }.toSet()

            assertTrue(names.any { it.endsWith(".OkHttpAiClient") })
            assertTrue(names.any { it.endsWith(".EnvelopeSecretStore") })
            assertTrue(names.any { it.endsWith(".DataStoreAppPreferences") })
            assertTrue(names.any { it.endsWith(".AiCoordinator") })
            assertTrue(names.any { it.endsWith(".AiOperationExecutor") })
            assertTrue(
                "AI operations must reuse the container EventService",
                graph.any { it === container.eventService }
            )
            assertTrue(
                "Release assembly must not contain a fake, mock, test transport, or test hook",
                names.none { name ->
                    listOf("Fake", "Mock", "TestHook", "TestTransport").any(name::contains)
                }
            )
        } finally {
            container.close()
        }
    }

    @Test
    fun unconfiguredProductionGatewayRejectsBeforeRoomSecretOrNetworkWork() = runBlocking {
        val container = createContainer()
        val databaseFile = application.getDatabasePath(AppContainer.DATABASE_NAME)

        try {
            val gateway = requireNotNull(gatewayGetter(container)).invoke(container)
                as AiSubmissionGateway
            val decision = gateway.submit(
                conversationId = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                message = "must stay local"
            )

            assertSame(AiSubmissionDecision.UNCONFIGURED, decision)
            assertFalse(databaseFile.exists())
            assertFalse(preferenceFile().exists())
            assertTrue(container.scheduleMutationVersion.value == 0L)
        } finally {
            container.close()
        }
    }

    @Test
    fun aiPreferencesAndActiveConversationSelectionReuseOneDataStore() {
        val container = createContainer()

        try {
            container.activeConversationStore.activeConversationId
            val gateway = requireNotNull(gatewayGetter(container)).invoke(container)
            val graph = initializedObjectGraph(listOf(container, gateway))
            val appPreferences = graph.filterIsInstance<DataStoreAppPreferences>().single()
            val activeStore = graph.filterIsInstance<DataStoreActiveConversationStore>().single()

            assertSame(dataStoreOf(activeStore), dataStoreOf(appPreferences))
        } finally {
            container.close()
        }
    }

    @Test
    fun closingInitializedContainerCancelsAiScopeAndClosesLifecycleBinding() {
        val container = createContainer()
        val gateway = requireNotNull(requireNotNull(gatewayGetter(container)).invoke(container))
        val graph = initializedObjectGraph(gateway)
        val coordinator = graph.single {
            it::class.java.name.endsWith(".AiCoordinator")
        }
        val scope = declaredFieldValue(coordinator, "scope") as CoroutineScope
        val binding = graph.singleOrNull {
            it::class.java.name.endsWith(".AiProcessLifecycleBinding")
        }

        assertNotNull("The app-scoped coordinator must be bound to process lifecycle once", binding)
        assertTrue(scope.coroutineContext[Job]?.isActive == true)

        container.close()

        assertFalse(scope.coroutineContext[Job]?.isActive == true)
        assertTrue(
            "close must release the lifecycle binding instead of retaining an observer",
            bindingHasBeenClosed(binding!!)
        )
    }

    private fun gatewayGetter(container: AppContainer) = container.javaClass.methods.singleOrNull {
        it.name == "getAiSubmissionGateway" && it.parameterCount == 0
    }

    private fun createContainer(): AppContainer = AppContainer(application).also(containers::add)

    private fun preferenceFile(): File =
        File(application.filesDir, AppContainer.PREFERENCES_RELATIVE_PATH)

    private fun isAiRuntimeObject(value: Any): Boolean {
        val name = value::class.java.name
        return name.endsWith(".AiCoordinator") ||
            name.endsWith(".OkHttpAiClient") ||
            name.endsWith(".EnvelopeSecretStore") ||
            name.endsWith(".DataStoreAppPreferences") ||
            name.endsWith(".AiSubmissionGateway")
    }

    private fun dataStoreOf(owner: Any): DataStore<*> {
        val field = owner.javaClass.getDeclaredField("dataStore")
        field.isAccessible = true
        return field.get(owner) as DataStore<*>
    }

    private fun declaredFieldValue(owner: Any, name: String): Any {
        val field = owner.javaClass.getDeclaredField(name)
        field.isAccessible = true
        return requireNotNull(field.get(owner))
    }

    private fun bindingHasBeenClosed(binding: Any): Boolean {
        val fields = binding.javaClass.declaredFields
        return fields.firstOrNull { it.name == "closed" }?.let { field ->
            field.isAccessible = true
            when (val value = field.get(binding)) {
                is Boolean -> value
                is java.util.concurrent.atomic.AtomicBoolean -> value.get()
                else -> false
            }
        } ?: false
    }

    private fun initializedObjectGraph(root: Any): Set<Any> = initializedObjectGraph(listOf(root))

    private fun initializedObjectGraph(roots: List<Any>): Set<Any> {
        val visited = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<Any, Boolean>()
        )
        val queue = ArrayDeque<Any>()
        roots.forEach(queue::add)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!visited.add(current)) continue
            enqueueReachableFields(current, queue)
        }
        return visited
    }

    private fun enqueueReachableFields(current: Any, queue: ArrayDeque<Any>) {
        allFields(current.javaClass).forEach { field ->
            if (Modifier.isStatic(field.modifiers)) return@forEach
            field.isAccessible = true
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
            value is CoroutineScope ||
            value is DataStore<*>
    }
}
