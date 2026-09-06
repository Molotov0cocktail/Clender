package com.molotov.clender.app

import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Build
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.room.Room
import androidx.work.WorkManager
import com.molotov.clender.app.ai.AiCoordinator
import com.molotov.clender.app.ai.AiCoordinatorDependencies
import com.molotov.clender.app.ai.AiOperationGate
import com.molotov.clender.app.ai.AiProcessLifecycleBinding
import com.molotov.clender.app.ai.AiSubmissionDecision
import com.molotov.clender.app.ai.AiSubmissionGateway
import com.molotov.clender.app.ai.ConfiguredAiSubmissionGateway
import com.molotov.clender.app.settings.AiSettingsApplicationService
import com.molotov.clender.app.settings.AiSettingsAtomicPort
import com.molotov.clender.app.settings.AppearanceSettingsApplicationService
import com.molotov.clender.app.settings.AppearanceSettingsPort
import com.molotov.clender.app.settings.PersistedAiSettings
import com.molotov.clender.app.settings.WebDavSettingsApplicationService
import com.molotov.clender.app.settings.WebDavSettingsAtomicPort
import com.molotov.clender.app.settings.WebDavStoredPassword
import com.molotov.clender.app.settings.WebDavSyncNowDecision
import com.molotov.clender.app.sync.SyncRequestDecision
import com.molotov.clender.app.sync.SyncState
import com.molotov.clender.app.sync.WebDavAvailability
import com.molotov.clender.app.sync.WebDavConnectionProbe
import com.molotov.clender.app.sync.WebDavOperationGate
import com.molotov.clender.app.sync.WebDavRunResult
import com.molotov.clender.app.sync.WebDavSecretException
import com.molotov.clender.app.sync.WebDavSyncRuntime
import com.molotov.clender.app.sync.WebDavSyncSignals
import com.molotov.clender.app.widget.WidgetAutomaticRefreshDependencies
import com.molotov.clender.app.widget.WidgetAutomaticRefreshPort
import com.molotov.clender.app.widget.WidgetAutomaticRefreshRuntime
import com.molotov.clender.app.widget.WidgetConfigurationRuntimePort
import com.molotov.clender.app.widget.WidgetConfigurationStore
import com.molotov.clender.app.widget.WidgetDateWorkPort
import com.molotov.clender.app.widget.WidgetFontSizeProvider
import com.molotov.clender.app.widget.WidgetLocalUpdatePort
import com.molotov.clender.app.widget.WidgetProcessLifecycleBinding
import com.molotov.clender.app.widget.WidgetRenderSink
import com.molotov.clender.app.widget.WidgetUpdateCoordinator
import com.molotov.clender.app.widget.WidgetUpdateResult
import com.molotov.clender.app.widget.WidgetUpdateTimePolicy
import com.molotov.clender.data.local.ClenderDatabase
import com.molotov.clender.data.local.RoomConversationRepository
import com.molotov.clender.data.local.RoomEventRepository
import com.molotov.clender.data.network.ai.OkHttpAiClient
import com.molotov.clender.data.network.webdav.WebDavClient
import com.molotov.clender.data.network.webdav.WebDavSettings
import com.molotov.clender.data.settings.AesGcmSecretCipher
import com.molotov.clender.data.settings.AiSettings
import com.molotov.clender.data.settings.AndroidKeyStoreSecretKeyProvider
import com.molotov.clender.data.settings.ApiKeyMutation
import com.molotov.clender.data.settings.AppPreferencesState
import com.molotov.clender.data.settings.AppearanceSettings
import com.molotov.clender.data.settings.DataStoreActiveConversationStore
import com.molotov.clender.data.settings.DataStoreAppPreferences
import com.molotov.clender.data.settings.DataStoreSecretEnvelopeStorage
import com.molotov.clender.data.settings.DataStoreWidgetConfigurationStore
import com.molotov.clender.data.settings.EnvelopeSecretStore
import com.molotov.clender.data.settings.SecretAlias
import com.molotov.clender.data.settings.SecretCipher
import com.molotov.clender.data.settings.SecretDecryptionResult
import com.molotov.clender.data.settings.SecretStore
import com.molotov.clender.data.settings.WebDavPasswordMutation
import com.molotov.clender.data.settings.WebDavPreferences
import com.molotov.clender.data.settings.WebDavSectionSnapshot
import com.molotov.clender.domain.ai.AiContextProvider
import com.molotov.clender.domain.ai.AiMessageBudgeter
import com.molotov.clender.domain.ai.AiOperationExecutor
import com.molotov.clender.domain.ai.AiResponseParser
import com.molotov.clender.domain.ai.FlowVisibleScheduleSource
import com.molotov.clender.domain.conversation.ActiveConversationStore
import com.molotov.clender.domain.conversation.ConversationRepository
import com.molotov.clender.domain.event.EventRepository
import com.molotov.clender.domain.event.EventService
import com.molotov.clender.domain.event.ScheduleMutation
import com.molotov.clender.domain.event.ScheduleMutationSink
import com.molotov.clender.domain.event.SyncUidGenerator
import com.molotov.clender.domain.sync.SyncService
import com.molotov.clender.domain.widget.WidgetConfiguration
import com.molotov.clender.domain.widget.WidgetDateBoundarySchedule
import com.molotov.clender.domain.widget.WidgetPresentationPolicy
import com.molotov.clender.domain.widget.WidgetRefreshTrigger
import com.molotov.clender.sync.RoomSyncRecordStore
import com.molotov.clender.sync.SyncTrigger
import com.molotov.clender.widget.PlatformWidgetOwnedIds
import com.molotov.clender.widget.WidgetProviderRuntime
import com.molotov.clender.widget.WidgetRemoteViewsRenderer
import com.molotov.clender.widget.WidgetRenderModelFactory
import com.molotov.clender.widget.WidgetSizeClassResolver
import com.molotov.clender.widget.WorkManagerWidgetDateScheduler
import java.io.File
import java.time.Clock
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient

class ScheduleMutationVersionSignal : ScheduleMutationSink {
    private val mutableVersion = MutableStateFlow(0L)

    val version: StateFlow<Long> = mutableVersion.asStateFlow()

    override fun onMutation(mutation: ScheduleMutation) {
        mutableVersion.update { current ->
            check(current < Long.MAX_VALUE) { "Schedule mutation version exhausted" }
            current + 1L
        }
    }
}

class AppContainer internal constructor(
    context: Context,
    private val widgetDateWork: WidgetDateWorkPort?,
    private val widgetEvents: EventRepository? = null
) {
    constructor(context: Context) : this(context, null)

    private val applicationContext = context.applicationContext
    private val closed = AtomicBoolean(false)
    private val appClock = Clock.systemUTC()

    private val databaseHolder = lazy {
        Room.databaseBuilder(
            applicationContext,
            ClenderDatabase::class.java,
            DATABASE_NAME
        ).build()
    }
    private val database: ClenderDatabase by databaseHolder

    private val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dataStoreHolder = lazy {
        PreferenceDataStoreFactory.create(scope = dataStoreScope) {
            File(applicationContext.filesDir, PREFERENCES_RELATIVE_PATH)
        }
    }

    private val roomEventRepository: RoomEventRepository by lazy {
        RoomEventRepository(database)
    }
    private val roomConversationRepository: RoomConversationRepository by lazy {
        RoomConversationRepository(database)
    }
    private val dataStoreActiveConversationStore: DataStoreActiveConversationStore by lazy {
        DataStoreActiveConversationStore(dataStoreHolder.value)
    }
    private val dataStoreAppPreferences: DataStoreAppPreferences by lazy {
        DataStoreAppPreferences(dataStoreHolder.value)
    }
    private val widgetScopeJobHolder = lazy { SupervisorJob() }
    private val widgetScopeHolder = lazy {
        CoroutineScope(widgetScopeJobHolder.value + Dispatchers.IO)
    }
    private val widgetConfigurationStoreHolder = lazy {
        DataStoreWidgetConfigurationStore(dataStoreHolder.value)
    }
    private val widgetCoordinatorHolder = lazy {
        WidgetUpdateCoordinator(
            configurations = WidgetConfigurationStoreAdapter(widgetConfigurationStoreHolder.value),
            appearance = WidgetFontSizeProvider {
                dataStoreAppPreferences.appearance.first().widgetFontSizeSp
            },
            events = widgetEvents ?: roomEventRepository,
            renderSink = ProductionWidgetRenderSink(applicationContext),
            timePolicy = WidgetUpdateTimePolicy(appClock, ZoneId::systemDefault)
        )
    }
    private val widgetProviderRuntimeHolder = lazy {
        ProductionWidgetProviderRuntime(
            runtime = widgetAutomaticRefreshRuntimeHolder.value
        )
    }
    private val widgetLocalUpdatePortHolder = lazy<WidgetLocalUpdatePort> {
        object : WidgetLocalUpdatePort {
            override fun invalidate(ids: Set<Int>) {
                if (widgetCoordinatorHolder.isInitialized()) {
                    widgetCoordinatorHolder.value.invalidate(ids)
                }
            }

            override fun prepareUpdate(id: Int): suspend () -> Unit {
                if (closed.get()) return {}
                // Construct lazy wrappers only; configuration reads and Room queries run on invoke.
                return widgetCoordinatorHolder.value.prepareUpdate(
                    listOf(id),
                    widgetSizeClass(applicationContext, id)
                )
            }

            override suspend fun update(id: Int) {
                prepareUpdate(id).invoke()
            }

            override suspend fun delete(ids: Set<Int>) {
                if (widgetCoordinatorHolder.isInitialized()) {
                    widgetCoordinatorHolder.value.delete(ids)
                } else {
                    ids.filter { it > 0 }.forEach { id ->
                        widgetConfigurationStoreHolder.value.delete(id)
                    }
                }
            }
        }
    }
    private val widgetDateSchedulerHolder = lazy {
        WorkManagerWidgetDateScheduler(WorkManager.getInstance(applicationContext))
    }
    private val widgetAutomaticRefreshRuntimeHolder = lazy<WidgetAutomaticRefreshPort> {
        WidgetAutomaticRefreshRuntime(
            scope = widgetScopeHolder.value,
            dependencies = WidgetAutomaticRefreshDependencies(
                ownedIds = PlatformWidgetOwnedIds(applicationContext),
                localUpdates = widgetLocalUpdatePortHolder.value,
                dateWork = widgetDateWork ?: object : WidgetDateWorkPort {
                    override suspend fun replace(schedule: WidgetDateBoundarySchedule) {
                        widgetDateSchedulerHolder.value.replace(schedule)
                    }

                    override suspend fun cancel() {
                        widgetDateSchedulerHolder.value.cancel()
                    }
                },
                mutationVersion = mutationVersionSignal.version,
                clock = appClock,
                zoneId = ZoneId::systemDefault
            )
        ).also { if (closed.get()) it.close() }
    }
    private var widgetLifecycleBinding: WidgetProcessLifecycleBinding? = null
    private val widgetConfigurationRuntimeHolder = lazy<WidgetConfigurationRuntimePort> {
        object : WidgetConfigurationRuntimePort {
            override suspend fun loadConfiguration(appWidgetId: Int): WidgetConfiguration? =
                widgetConfigurationStoreHolder.value.get(appWidgetId)

            override suspend fun widgetFontSizeSp(): Int =
                dataStoreAppPreferences.appearance.first().widgetFontSizeSp

            override suspend fun saveConfiguration(configuration: WidgetConfiguration) {
                widgetConfigurationStoreHolder.value.upsert(configuration)
            }

            override suspend fun requestWidgetUpdate(appWidgetId: Int) {
                require(appWidgetId > 0) { "Widget ID must be positive" }
                widgetAutomaticRefreshRuntimeHolder.value.request(
                    WidgetRefreshTrigger.CONFIGURATION_CHANGE,
                    appWidgetId
                )
            }
        }
    }
    private val secretEnvelopeStorageHolder = lazy {
        DataStoreSecretEnvelopeStorage(dataStoreHolder.value)
    }
    private val secretCipherHolder = lazy<SecretCipher> {
        AesGcmSecretCipher(
            applicationId = applicationContext.packageName,
            keyProvider = AndroidKeyStoreSecretKeyProvider()
        )
    }
    private val secretStoreHolder = lazy<SecretStore> {
        EnvelopeSecretStore(
            storage = secretEnvelopeStorageHolder.value,
            cipher = secretCipherHolder.value
        )
    }
    private val aiClientHolder = lazy {
        OkHttpAiClient(OkHttpClient.Builder().build())
    }
    private val aiOperationGateHolder = lazy { AiOperationGate() }
    private val aiSettingsAtomicPortHolder = lazy {
        DataStoreAiSettingsAtomicPort(
            preferences = dataStoreAppPreferences,
            cipher = { secretCipherHolder.value },
            secrets = { secretStoreHolder.value },
            isConfigured = {
                secretEnvelopeStorageHolder.value.isConfigured(SecretAlias.AI_API_KEY)
            }
        )
    }
    private val appearanceSettingsServiceHolder = lazy {
        AppearanceSettingsApplicationService(
            object : AppearanceSettingsPort {
                override suspend fun updateAppearance(settings: AppearanceSettings) {
                    dataStoreAppPreferences.updateAppearance(settings)
                }
            }
        )
    }
    private val aiSettingsServiceHolder = lazy {
        AiSettingsApplicationService(
            port = aiSettingsAtomicPortHolder.value,
            client = aiClientHolder.value,
            operationGate = aiOperationGateHolder.value
        )
    }
    private val webDavGateHolder = lazy { WebDavOperationGate() }
    private val webDavHttpClientHolder = lazy { OkHttpClient.Builder().build() }
    private val webDavSyncScopeHolder = lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
    private val webDavAtomicPortHolder = lazy {
        DataStoreWebDavAtomicPort(
            preferences = dataStoreAppPreferences,
            cipher = { secretCipherHolder.value },
            secrets = { secretStoreHolder.value },
            envelopeStorage = { secretEnvelopeStorageHolder.value }
        )
    }
    private val webDavAvailability: Flow<WebDavAvailability> by lazy {
        combine(
            dataStoreAppPreferences.state.map { state -> state.webDav },
            secretEnvelopeStorageHolder.value.presence(SecretAlias.WEB_DAV_PASSWORD)
        ) { webDav, present ->
            WebDavAvailability(enabled = webDav.enabled, passwordConfigured = present)
        }
    }
    private val webDavSyncRuntimeHolder = lazy {
        WebDavSyncRuntime(
            scope = webDavSyncScopeHolder.value,
            lifecycle = ProcessLifecycleOwner.get().lifecycle,
            gate = webDavGateHolder.value,
            signals = WebDavSyncSignals(
                availability = webDavAvailability,
                mutationVersion = mutationVersionSignal.version,
                onRemoteVisibleChanged = {
                    if (!closed.get()) {
                        widgetAutomaticRefreshRuntimeHolder.value.request(
                            WidgetRefreshTrigger.REMOTE_VISIBLE_CHANGE
                        )
                    }
                }
            ),
            runSync = { binding -> runWebDavSync(binding) }
        )
    }
    private val webDavProbeHolder = lazy {
        WebDavConnectionProbe(
            gate = webDavGateHolder.value,
            clientFactory = { url, username ->
                WebDavClient(WebDavSettings.create(url, username), webDavHttpClientHolder.value)
            },
            cancelAll = {
                if (webDavHttpClientHolder.isInitialized()) {
                    webDavHttpClientHolder.value.dispatcher.cancelAll()
                }
            }
        )
    }
    private val webDavSettingsServiceHolder = lazy {
        WebDavSettingsApplicationService(
            port = webDavAtomicPortHolder.value,
            probe = webDavProbeHolder.value,
            requestSyncNow = { snapshot ->
                webDavSyncRuntimeHolder.value
                    .requestSync(SyncTrigger.MANUAL, snapshot)
                    .toSyncNow()
            },
            persistedPasswordConfigured = {
                secretEnvelopeStorageHolder.value.isConfigured(SecretAlias.WEB_DAV_PASSWORD)
            }
        )
    }
    private val settingsPortHolder = lazy {
        ProductionSettingsPort(
            loadState = { dataStoreAppPreferences.state.first() },
            isApiKeyConfigured = {
                secretEnvelopeStorageHolder.value.isConfigured(SecretAlias.AI_API_KEY)
            },
            webDav = WebDavPortDependencies(
                service = { webDavSettingsServiceHolder.value },
                syncState = { webDavSyncRuntimeHolder.value.state },
                passwordConfigured = {
                    secretEnvelopeStorageHolder.value.isConfigured(SecretAlias.WEB_DAV_PASSWORD)
                }
            ),
            appearanceService = { appearanceSettingsServiceHolder.value },
            aiService = { aiSettingsServiceHolder.value },
            cancelAiClient = {
                if (aiClientHolder.isInitialized()) aiClientHolder.value.cancelInFlight()
            }
        )
    }
    private val activeConversationStoreProxy = object : ActiveConversationStore {
        override val activeConversationId: Flow<String?>
            get() = dataStoreActiveConversationStore.activeConversationId

        override suspend fun setActiveConversationId(id: String?) {
            dataStoreActiveConversationStore.setActiveConversationId(id)
        }
    }
    private val mutationVersionSignal: ScheduleMutationVersionSignal by lazy {
        ScheduleMutationVersionSignal()
    }
    private val aiScopeHolder = lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
    private val aiSubmissionGatewayHolder = lazy {
        val coordinator = AiCoordinator(
            scope = aiScopeHolder.value,
            dependencies = AiCoordinatorDependencies(
                client = aiClientHolder.value,
                conversations = roomConversationRepository,
                budgeter = AiMessageBudgeter(),
                parser = AiResponseParser(),
                executor = AiOperationExecutor(eventService),
                clock = appClock,
                contextProvider = AiContextProvider(
                    appClock,
                    FlowVisibleScheduleSource(roomEventRepository.observeVisible())
                )
            )
        )
        val lifecycleBinding = AiProcessLifecycleBinding(
            ProcessLifecycleOwner.get().lifecycle,
            coordinator
        )
        AppScopedAiSubmissionGateway(
            delegate = ConfiguredAiSubmissionGateway(
                preferences = dataStoreAppPreferences,
                secrets = secretStoreHolder.value,
                coordinator = coordinator,
                operationGate = aiOperationGateHolder.value
            ),
            lifecycleBinding = lifecycleBinding
        )
    }

    val eventRepository: EventRepository
        get() = roomEventRepository

    val conversationRepository: ConversationRepository
        get() = roomConversationRepository

    val activeConversationStore: ActiveConversationStore
        get() = activeConversationStoreProxy

    val scheduleMutationVersion: StateFlow<Long>
        get() = mutationVersionSignal.version

    val aiSubmissionGateway: AiSubmissionGateway
        get() = aiSubmissionGatewayHolder.value

    val appearance: Flow<AppearanceSettings>
        get() = dataStoreAppPreferences.appearance

    internal val widgetProviderScope: CoroutineScope
        get() = widgetScopeHolder.value

    internal val widgetProviderRuntime: WidgetProviderRuntime
        get() = widgetProviderRuntimeHolder.value

    internal val widgetConfigurationRuntime: WidgetConfigurationRuntimePort
        get() = widgetConfigurationRuntimeHolder.value

    internal val widgetAutomaticRefreshRuntime: WidgetAutomaticRefreshPort
        get() = widgetAutomaticRefreshRuntimeHolder.value

    internal val widgetLocalUpdatePort: WidgetLocalUpdatePort
        get() = widgetLocalUpdatePortHolder.value

    internal fun bindWidgetLifecycle(lifecycle: Lifecycle) {
        if (closed.get() || widgetLifecycleBinding != null) return
        val alreadyStarted = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        widgetLifecycleBinding = WidgetProcessLifecycleBinding(lifecycle) {
            restoreWidgetsIfOwned()
        }
        if (!alreadyStarted) restoreWidgetsIfOwned()
    }

    private fun restoreWidgetsIfOwned() {
        if (closed.get()) return
        val manager = AppWidgetManager.getInstance(applicationContext)
        val provider = android.content.ComponentName(
            applicationContext,
            com.molotov.clender.widget.ClenderWidgetProvider::class.java
        )
        if (widgetAutomaticRefreshRuntimeHolder.isInitialized() ||
            manager.getAppWidgetIds(provider).any { it > 0 }
        ) {
            widgetAutomaticRefreshRuntimeHolder.value.restore()
        }
    }

    val webDavSyncState: StateFlow<SyncState>
        get() = webDavSyncRuntimeHolder.value.state

    internal val settingsPort
        get() = settingsPortHolder.value

    val eventService: EventService by lazy {
        EventService(
            repository = roomEventRepository,
            clock = appClock,
            uidGenerator = SyncUidGenerator {
                UUID.randomUUID().toString().replace("-", "")
            },
            mutationSink = mutationVersionSignal
        )
    }

    internal fun close() {
        if (!closed.compareAndSet(false, true)) return
        closeWidgetRuntime()
        if (settingsPortHolder.isInitialized()) settingsPortHolder.value.cancelModelFetch()
        if (webDavSettingsServiceHolder.isInitialized()) {
            webDavSettingsServiceHolder.value.cancelProbe()
        }
        if (webDavHttpClientHolder.isInitialized()) {
            webDavHttpClientHolder.value.dispatcher.cancelAll()
        }
        if (webDavSyncRuntimeHolder.isInitialized()) webDavSyncRuntimeHolder.value.close()
        if (webDavGateHolder.isInitialized()) webDavGateHolder.value.close()
        if (webDavSyncScopeHolder.isInitialized()) webDavSyncScopeHolder.value.cancel()
        if (aiClientHolder.isInitialized()) aiClientHolder.value.cancelInFlight()
        if (aiOperationGateHolder.isInitialized()) aiOperationGateHolder.value.close()
        if (aiSubmissionGatewayHolder.isInitialized()) {
            aiSubmissionGatewayHolder.value.close()
        }
        if (aiScopeHolder.isInitialized()) aiScopeHolder.value.cancel()
        dataStoreScope.cancel()
        if (databaseHolder.isInitialized()) database.close()
    }

    private fun closeWidgetRuntime() {
        widgetLifecycleBinding?.close()
        widgetLifecycleBinding = null
        if (widgetAutomaticRefreshRuntimeHolder.isInitialized()) {
            widgetAutomaticRefreshRuntimeHolder.value.close()
        }
        if (widgetCoordinatorHolder.isInitialized()) widgetCoordinatorHolder.value.close()
        if (!widgetScopeJobHolder.isInitialized()) return
        val widgetJob = widgetScopeJobHolder.value
        widgetJob.cancel()
        runBlocking { widgetJob.join() }
    }

    private suspend fun runWebDavSync(binding: WebDavSectionSnapshot?): WebDavRunResult {
        val resolved = resolveWebDavRun(binding) ?: return WebDavRunResult.Unconfigured
        val (settings, password) = resolved
        return try {
            val client = WebDavClient(
                WebDavSettings.create(settings.url, settings.username),
                webDavHttpClientHolder.value
            )
            val session = client.openSession(password)
            try {
                val result = SyncService(RoomSyncRecordStore(roomEventRepository), session).sync()
                WebDavRunResult.Completed(
                    uploaded = result.uploaded,
                    localChanged = result.localChanged,
                    eventCount = result.eventCount
                )
            } finally {
                session.close()
            }
        } finally {
            password.fill('\u0000')
        }
    }

    private suspend fun resolveWebDavRun(
        binding: WebDavSectionSnapshot?
    ): Pair<WebDavPreferences, CharArray>? = binding?.let { bound ->
        webDavAtomicPortHolder.value.readPassword(bound)?.let { password ->
            return Pair(bound.toPreferences(), password)
        }
    }.let {
        val preferences = dataStoreAppPreferences.state.first()
        if (!preferences.webDav.enabled) return null
        val envelope = secretEnvelopeStorageHolder.value.read(SecretAlias.WEB_DAV_PASSWORD)
            ?: return null
        when (
            val decrypted = secretCipherHolder.value.decrypt(
                SecretAlias.WEB_DAV_PASSWORD,
                envelope
            )
        ) {
            is SecretDecryptionResult.Success -> Pair(preferences.webDav, decrypted.value)

            else -> {
                secretStoreHolder.value.delete(SecretAlias.WEB_DAV_PASSWORD)
                throw WebDavSecretException()
            }
        }
    }

    companion object {
        const val DATABASE_NAME: String = "clender.db"
        const val PREFERENCES_RELATIVE_PATH: String = "datastore/clender.preferences_pb"
    }
}

private class WidgetConfigurationStoreAdapter(
    private val delegate: DataStoreWidgetConfigurationStore
) : WidgetConfigurationStore {
    override suspend fun get(appWidgetId: Int): WidgetConfiguration? = delegate.get(appWidgetId)

    override suspend fun upsert(configuration: WidgetConfiguration) {
        delegate.upsert(configuration)
    }

    override suspend fun delete(appWidgetId: Int) {
        delegate.delete(appWidgetId)
    }
}

private class ProductionWidgetProviderRuntime(private val runtime: WidgetAutomaticRefreshPort) :
    WidgetProviderRuntime {
    override suspend fun update(appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        if (normalizedWidgetIds(appWidgetIds).isNotEmpty()) runtime.instancesChanged().await()
    }

    override suspend fun optionsChanged(appWidgetManager: AppWidgetManager, appWidgetId: Int) {
        if (appWidgetId > 0) {
            runtime.request(WidgetRefreshTrigger.CONFIGURATION_CHANGE, appWidgetId).await()
        }
    }

    override suspend fun localRefresh(appWidgetManager: AppWidgetManager, appWidgetId: Int) {
        runtime.request(WidgetRefreshTrigger.MANUAL_LOCAL_REFRESH, appWidgetId).await()
    }

    override suspend fun delete(appWidgetIds: IntArray) {
        prepareDelete(appWidgetIds).invoke()
    }

    override fun prepareDelete(appWidgetIds: IntArray): suspend () -> Unit {
        val receipt = runtime.instancesChanged(normalizedWidgetIds(appWidgetIds).toSet())
        return { receipt.await() }
    }
}

private fun widgetSizeClass(
    context: Context,
    appWidgetId: Int
): WidgetPresentationPolicy.SizeClass = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    WidgetPresentationPolicy.SizeClass.LARGE
} else {
    WidgetSizeClassResolver.resolve(
        AppWidgetManager.getInstance(context).getAppWidgetOptions(appWidgetId),
        context.resources.configuration.orientation
    )
}

private class ProductionWidgetRenderSink(private val context: Context) : WidgetRenderSink {
    override suspend fun render(appWidgetId: Int, result: WidgetUpdateResult) {
        val locale = context.resources.configuration.locales[0]
        val model = when (result) {
            is WidgetUpdateResult.Ready -> WidgetRenderModelFactory.ready(
                date = result.date,
                presentation = result.presentation,
                configuration = result.configuration,
                locale = locale
            )

            is WidgetUpdateResult.Unavailable -> {
                WidgetRenderModelFactory.unavailable(result.date, locale)
            }
        }
        val manager = AppWidgetManager.getInstance(context)
        val legacySizeClass = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            WidgetPresentationPolicy.SizeClass.LARGE
        } else {
            WidgetSizeClassResolver.resolve(
                manager.getAppWidgetOptions(appWidgetId),
                context.resources.configuration.orientation
            )
        }
        manager.updateAppWidget(
            appWidgetId,
            WidgetRemoteViewsRenderer.renderForHost(
                context,
                model,
                legacySizeClass,
                appWidgetId
            )
        )
    }
}

private fun normalizedWidgetIds(appWidgetIds: IntArray): List<Int> = appWidgetIds
    .asSequence()
    .filter { it > 0 }
    .distinct()
    .sorted()
    .toList()

private class DataStoreAiSettingsAtomicPort(
    private val preferences: DataStoreAppPreferences,
    private val cipher: () -> SecretCipher,
    private val secrets: () -> SecretStore,
    private val isConfigured: suspend () -> Boolean
) : AiSettingsAtomicPort {
    @Volatile
    private var latestSnapshot: PersistedAiSettings? = null

    override suspend fun updateAi(
        settings: AiSettings,
        mutation: ApiKeyMutation
    ): PersistedAiSettings {
        preferences.updateAi(settings, mutation, cipher())
        return PersistedAiSettings(
            settings = preferences.state.first().ai,
            keyConfigured = isConfigured()
        ).also { latestSnapshot = it }
    }

    override suspend fun readApiKey(snapshot: PersistedAiSettings): CharArray? {
        check(snapshot === latestSnapshot) { "AI settings snapshot is stale" }
        return secrets().get(SecretAlias.AI_API_KEY)
    }
}

private class DataStoreWebDavAtomicPort(
    private val preferences: DataStoreAppPreferences,
    private val cipher: () -> SecretCipher,
    private val secrets: () -> SecretStore,
    private val envelopeStorage: () -> DataStoreSecretEnvelopeStorage
) : WebDavSettingsAtomicPort {
    @Volatile
    private var latestSnapshot: WebDavSectionSnapshot? = null

    override suspend fun updateWebDav(
        settings: com.molotov.clender.data.settings.WebDavPreferences,
        mutation: WebDavPasswordMutation
    ): WebDavSectionSnapshot =
        preferences.updateWebDav(settings, mutation, cipher()).also { latestSnapshot = it }

    override suspend fun readPassword(snapshot: WebDavSectionSnapshot): CharArray? {
        if (snapshot !== latestSnapshot) return null
        return secrets().get(SecretAlias.WEB_DAV_PASSWORD)
    }

    override suspend fun readPersistedPassword(): WebDavStoredPassword {
        val envelope = envelopeStorage().read(SecretAlias.WEB_DAV_PASSWORD)
            ?: return WebDavStoredPassword.Missing
        return when (val result = cipher().decrypt(SecretAlias.WEB_DAV_PASSWORD, envelope)) {
            is SecretDecryptionResult.Success -> WebDavStoredPassword.Granted(result.value)

            else -> {
                secrets().delete(SecretAlias.WEB_DAV_PASSWORD)
                WebDavStoredPassword.Invalid
            }
        }
    }
}

private fun WebDavSectionSnapshot.toPreferences():
    com.molotov.clender.data.settings.WebDavPreferences =
    com.molotov.clender.data.settings.WebDavPreferences(enabled, url, username)

private fun SyncRequestDecision.toSyncNow(): WebDavSyncNowDecision = when (this) {
    SyncRequestDecision.STARTED -> WebDavSyncNowDecision.SYNC_STARTED

    SyncRequestDecision.COALESCED,
    SyncRequestDecision.REFRESHING -> WebDavSyncNowDecision.SYNC_COALESCED

    SyncRequestDecision.DISABLED -> WebDavSyncNowDecision.DISABLED

    SyncRequestDecision.UNCONFIGURED -> WebDavSyncNowDecision.UNCONFIGURED

    SyncRequestDecision.SHUTDOWN -> WebDavSyncNowDecision.SAVE_FAILED
}

private class AppScopedAiSubmissionGateway(
    private val delegate: AiSubmissionGateway,
    private val lifecycleBinding: AiProcessLifecycleBinding
) : AiSubmissionGateway {
    override val state = delegate.state

    override suspend fun submit(conversationId: String, message: String): AiSubmissionDecision =
        delegate.submit(conversationId, message)

    override fun acknowledgeTerminal() {
        delegate.acknowledgeTerminal()
    }

    fun close() {
        lifecycleBinding.close()
    }
}
