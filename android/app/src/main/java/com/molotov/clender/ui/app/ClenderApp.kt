package com.molotov.clender.ui.app

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.molotov.clender.R
import com.molotov.clender.ui.about.AboutScreen
import com.molotov.clender.ui.ai.AiConversationScreen
import com.molotov.clender.ui.ai.AiConversationSubmissionActions
import com.molotov.clender.ui.ai.AiSubmissionViewModel
import com.molotov.clender.ui.ai.ConversationViewModel
import com.molotov.clender.ui.ai.QuickAiActions
import com.molotov.clender.ui.ai.QuickAiScreen
import com.molotov.clender.ui.calendar.CalendarScreen
import com.molotov.clender.ui.calendar.CalendarScreenActions
import com.molotov.clender.ui.calendar.CalendarScreenModel
import com.molotov.clender.ui.calendar.CalendarScreenState
import com.molotov.clender.ui.calendar.CalendarViewModel
import com.molotov.clender.ui.calendar.SelectedDateEventList
import com.molotov.clender.ui.event.DeleteConfirmationDialog
import com.molotov.clender.ui.event.DiscardChangesDialog
import com.molotov.clender.ui.event.EventCrudEffect
import com.molotov.clender.ui.event.EventCrudRoute
import com.molotov.clender.ui.event.EventCrudUiState
import com.molotov.clender.ui.event.EventCrudViewModel
import com.molotov.clender.ui.event.EventDetailScreen
import com.molotov.clender.ui.event.EventEditorScreen
import com.molotov.clender.ui.event.EventListNavigation
import com.molotov.clender.ui.foundation.AppearanceUiState
import com.molotov.clender.ui.navigation.AppDestination
import com.molotov.clender.ui.navigation.AppRoute
import com.molotov.clender.ui.navigation.DrawerNavigation
import com.molotov.clender.ui.settings.SettingsActions
import com.molotov.clender.ui.settings.SettingsScreen
import com.molotov.clender.ui.settings.SettingsViewModel
import com.molotov.clender.ui.state.AppShellUiState
import com.molotov.clender.ui.state.AppShellViewModel
import com.molotov.clender.ui.theme.AppBackground
import com.molotov.clender.ui.theme.AppearanceViewModel
import com.molotov.clender.ui.theme.ClenderTheme
import com.molotov.clender.ui.theme.resolveIsDark
import java.time.LocalDate
import kotlinx.coroutines.launch

internal data class AppContentModel(
    val shell: AppShellUiState,
    val calendar: CalendarScreenState,
    val eventCrud: EventCrudUiState,
    val locale: java.util.Locale
)

internal data class AppDependencies(
    val shellViewModel: AppShellViewModel,
    val calendarActions: CalendarScreenActions,
    val eventCrudViewModel: EventCrudViewModel,
    val conversationViewModel: ConversationViewModel?,
    val aiSubmissionViewModel: AiSubmissionViewModel?,
    val settingsViewModel: SettingsViewModel?
)

data class ClenderAppAiModels(
    val conversation: ConversationViewModel? = null,
    val submission: AiSubmissionViewModel? = null
)

data class ClenderAppSettingsModels(
    val appearance: AppearanceViewModel? = null,
    val settings: SettingsViewModel? = null
)

data class ClenderAppModels(
    val ai: ClenderAppAiModels = ClenderAppAiModels(),
    val settings: ClenderAppSettingsModels = ClenderAppSettingsModels()
)

internal class CrudDialogState {
    var deleteConfirmation by mutableStateOf(false)
    var discardConfirmation by mutableStateOf(false)
}

internal data class BackCallbacks(
    val closeDrawer: () -> Unit,
    val showDiscard: () -> Unit,
    val exitEditor: () -> Unit,
    val popRoute: () -> Unit,
    val defer: () -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClenderApp(
    viewModel: AppShellViewModel,
    calendarViewModel: CalendarViewModel,
    eventCrudViewModel: EventCrudViewModel,
    appearance: AppearanceUiState = AppearanceUiState.fromPersisted(null, null, null),
    models: ClenderAppModels = ClenderAppModels()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val calendarState by calendarViewModel.state.collectAsStateWithLifecycle()
    val eventCrudState by eventCrudViewModel.state.collectAsStateWithLifecycle()
    val locale = LocalConfiguration.current.locales[0]
    val dialogs = remember { CrudDialogState() }

    val productionAppearance = models.settings.appearance
        ?.state
        ?.collectAsStateWithLifecycle()
        ?.value
        ?: appearance
    CollectEventCrudEffects(viewModel, eventCrudViewModel, dialogs)
    LaunchedEffect(locale) { calendarViewModel.changeLocale(locale) }
    val model = AppContentModel(state, calendarState, eventCrudState, locale)
    val dependencies = AppDependencies(
        shellViewModel = viewModel,
        calendarActions = CalendarScreenActions(
            onSelectDate = { date ->
                viewModel.selectDate(date)
                calendarViewModel.selectDate(date)
            },
            onChangeMode = { mode ->
                viewModel.changeMode(mode)
                calendarViewModel.changeMode(mode)
            },
            onRetry = calendarViewModel::retry,
            onNavigate = viewModel::pushRoute
        ),
        eventCrudViewModel = eventCrudViewModel,
        conversationViewModel = models.ai.conversation,
        aiSubmissionViewModel = models.ai.submission,
        settingsViewModel = models.settings.settings
    )
    SynchronizeAppRoute(state, dependencies, dialogs)
    ClenderTheme(appearance = productionAppearance) {
        AppBackground(resolveIsDark(productionAppearance.themeMode, isSystemInDarkTheme())) {
            AppNavigationShell(model, dependencies, dialogs)
        }
    }
}

@Composable
private fun AppNavigationShell(
    model: AppContentModel,
    dependencies: AppDependencies,
    dialogs: CrudDialogState
) {
    val drawerState = rememberSynchronizedDrawerState(model.shell.drawerOpen, dependencies)
    val dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val settingsState = dependencies.settingsViewModel
        ?.state
        ?.collectAsStateWithLifecycle()
        ?.value
    val saveableStateHolder = rememberSaveableStateHolder()
    val callbacks = rememberAppBackCallbacks(model, dependencies, dialogs, dispatcher)
    AppBackInterception(
        model,
        dependencies,
        settingsState,
        AppBackEnvironment(drawerState, callbacks, dispatcher)
    )
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = model.shell.childRoutes.isEmpty(),
        drawerContent = {
            SettingsAwareDrawerContent(model, dependencies)
        }
    ) {
        AppShellScaffold(
            model = model,
            dependencies = dependencies,
            actions = AppShellScaffoldActions(
                openDrawer = {
                    dependencies.settingsViewModel?.setDrawerOpen(true)
                    dependencies.shellViewModel.openDrawer()
                },
                navigateBack = {
                    handleBack(model.shell, model.eventCrud, false, callbacks)
                },
                requestDelete = { dialogs.deleteConfirmation = true },
                onCreate = { createEventFromSelectedDate(dependencies.shellViewModel) }
            ),
            saveableStateHolder = saveableStateHolder
        )
    }
    CrudDialogs(model, dependencies, dialogs)
}

private fun createEventFromSelectedDate(shell: AppShellViewModel) {
    val current = shell.state.value
    if (canCreateEvent(current)) shell.pushRoute(AppRoute.NewEvent(current.selectedDate))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppShellScaffold(
    model: AppContentModel,
    dependencies: AppDependencies,
    actions: AppShellScaffoldActions,
    saveableStateHolder: SaveableStateHolder
) {
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            AppTopBar(model, actions) {
                AppConversationAction(model.shell, dependencies.conversationViewModel)
            }
        }
    ) { innerPadding ->
        val pageTitle = stringResource(screenTitle(model))
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .testTag("clender_app_content")
                .semantics { paneTitle = pageTitle }
        ) {
            saveableStateHolder.SaveableStateProvider(pageStateKey(model)) {
                ContentHost(
                    model = model,
                    dependencies = dependencies,
                    onRequestDelete = actions.requestDelete
                )
            }
        }
    }
}

@Composable
private fun ContentHost(
    model: AppContentModel,
    dependencies: AppDependencies,
    onRequestDelete: () -> Unit
) {
    val submissionViewModel = dependencies.aiSubmissionViewModel
    val submissionState = submissionViewModel?.state?.collectAsStateWithLifecycle()?.value
    val route = model.shell.childRoutes.lastOrNull()
    if (route != null) {
        ChildRouteHost(route, model, dependencies, submissionState, onRequestDelete)
    } else {
        DestinationHost(model, dependencies, submissionState)
    }
}

@Composable
private fun ChildRouteHost(
    route: AppRoute,
    model: AppContentModel,
    dependencies: AppDependencies,
    submissionState: com.molotov.clender.ui.ai.AiSubmissionUiState?,
    onRequestDelete: () -> Unit
) {
    when (route) {
        is AppRoute.EventDetail -> when (model.eventCrud.route) {
            is EventCrudRoute.Edit -> EventEditorScreen(
                state = model.eventCrud,
                onFormChange = dependencies.eventCrudViewModel::updateForm,
                onSave = dependencies.eventCrudViewModel::save
            )

            else -> EventDetailScreen(
                state = model.eventCrud,
                onRetry = dependencies.eventCrudViewModel::retry,
                onEdit = dependencies.eventCrudViewModel::startEdit,
                onRequestDelete = onRequestDelete
            )
        }

        is AppRoute.NewEvent -> EventEditorScreen(
            state = model.eventCrud,
            onFormChange = dependencies.eventCrudViewModel::updateForm,
            onSave = dependencies.eventCrudViewModel::save
        )

        AppRoute.QuickAi -> QuickAiScreen(
            state = submissionState ?: com.molotov.clender.ui.ai.AiSubmissionUiState(),
            actions = QuickAiActions(
                onDraftChange = { dependencies.aiSubmissionViewModel?.updateDraft(it) },
                onSend = {
                    dependencies.conversationViewModel?.state?.value?.activeConversation?.id?.let {
                        dependencies.aiSubmissionViewModel?.submit(it)
                    }
                },
                onDismissStatus = { dependencies.aiSubmissionViewModel?.dismissStatus() },
                onOpenConversation = {
                    dependencies.shellViewModel.navigateTo(AppDestination.AI)
                },
                onOpenSettings = {
                    dependencies.settingsViewModel?.showAiSection()
                    dependencies.shellViewModel.navigateTo(AppDestination.SETTINGS)
                },
                onBack = dependencies.shellViewModel::popRoute
            )
        )

        else -> Unit
    }
}

@Composable
private fun DestinationHost(
    model: AppContentModel,
    dependencies: AppDependencies,
    submissionState: com.molotov.clender.ui.ai.AiSubmissionUiState?
) {
    when (model.shell.destination) {
        AppDestination.CALENDAR -> CalendarScreen(
            model = CalendarScreenModel(
                state = model.calendar,
                locale = model.locale,
                today = LocalDate.now()
            ),
            actions = dependencies.calendarActions
        )

        AppDestination.EVENTS -> SelectedDateEventList(
            state = model.calendar,
            locale = model.locale,
            onRetry = dependencies.calendarActions.onRetry,
            onNavigate = dependencies.calendarActions.onNavigate
        )

        AppDestination.AI -> dependencies.conversationViewModel?.let {
            AiConversationScreen(
                viewModel = it,
                submissionState = submissionState,
                paneBackEnabled = !model.shell.drawerOpen,
                submissionActions = AiConversationSubmissionActions(
                    onDraftChange = { value ->
                        dependencies.aiSubmissionViewModel?.updateDraft(value)
                    },
                    onSubmitConversation = { conversationId ->
                        dependencies.aiSubmissionViewModel?.submit(conversationId)
                    },
                    onDismissStatus = {
                        dependencies.aiSubmissionViewModel?.dismissStatus()
                    },
                    onOpenSettings = {
                        dependencies.settingsViewModel?.showAiSection()
                        dependencies.shellViewModel.navigateTo(AppDestination.SETTINGS)
                    }
                )
            )
        } ?: PlaceholderScreen(R.string.screen_ai)

        AppDestination.SETTINGS -> SettingsDestination(dependencies.settingsViewModel)

        AppDestination.ABOUT -> AboutScreen()
    }
}

@Composable
private fun SettingsDestination(settings: SettingsViewModel?) {
    if (settings == null) {
        PlaceholderScreen(R.string.screen_settings)
        return
    }
    val state by settings.state.collectAsStateWithLifecycle()
    SettingsScreen(
        state = state,
        actions = SettingsActions(
            onSectionChange = { settings.selectSection(it) },
            onAppearanceChange = { settings.updateAppearance(it) },
            onAiChange = { settings.updateAi(it) },
            onSecretInput = { settings.updateSecretInput(it) },
            onSaveAppearance = { settings.saveAppearance() },
            onSaveAi = { settings.saveAi() },
            onFetchModels = { settings.fetchModels() },
            onRequestRemoveKey = { settings.requestApiKeyRemoval() },
            onConfirmRemoveKey = { settings.confirmApiKeyRemoval() },
            onCancelRemoveKey = { settings.cancelApiKeyRemoval() },
            onConfirmDiscard = { settings.confirmDiscard() },
            onCancelDiscard = { settings.cancelDiscard() },
            onUpdateWebDav = { settings.updateWebDav(it) },
            onWebDavSecretInput = { settings.updateWebDavSecretInput(it) },
            onSaveWebDav = { settings.saveWebDav() },
            onTestWebDavConnection = { settings.testWebDavConnection() },
            onSyncWebDavNow = { settings.syncWebDavNow() },
            onRequestRemoveWebDavPassword = { settings.requestWebDavPasswordRemoval() },
            onConfirmRemoveWebDavPassword = { settings.confirmWebDavPasswordRemoval() },
            onCancelRemoveWebDavPassword = { settings.cancelWebDavPasswordRemoval() }
        )
    )
}

@Composable
private fun CrudDialogs(
    model: AppContentModel,
    dependencies: AppDependencies,
    dialogs: CrudDialogState
) {
    if (dialogs.deleteConfirmation) {
        DeleteConfirmationDialog(
            deleting = model.eventCrud.operation ==
                com.molotov.clender.ui.event.EventCrudOperation.DELETING,
            error = model.eventCrud.deleteError,
            onConfirm = dependencies.eventCrudViewModel::delete,
            onDismiss = { dialogs.deleteConfirmation = false }
        )
    }
    if (dialogs.discardConfirmation) {
        DiscardChangesDialog(
            onDiscard = {
                dialogs.discardConfirmation = false
                exitEventEditor(
                    dependencies.shellViewModel,
                    dependencies.eventCrudViewModel,
                    model.shell.childRoutes.lastOrNull()
                )
            },
            onDismiss = { dialogs.discardConfirmation = false }
        )
    }
}

internal fun handleBack(
    state: AppShellUiState,
    eventState: EventCrudUiState,
    drawerOpen: Boolean,
    callbacks: BackCallbacks
) {
    val route = state.childRoutes.lastOrNull()
    val form = eventState.form
    val dirty = form != null && when {
        route is AppRoute.NewEvent ->
            form != com.molotov.clender.ui.event.EventFormState.new(route.date)

        route is AppRoute.EventDetail && eventState.route is EventCrudRoute.Edit ->
            eventState.event?.let(com.molotov.clender.ui.event.EventFormState::fromEvent) != form

        else -> false
    }
    when {
        drawerOpen -> callbacks.closeDrawer()

        route is AppRoute.NewEvent ||
            (route is AppRoute.EventDetail && eventState.route is EventCrudRoute.Edit) -> {
            if (dirty) callbacks.showDiscard() else callbacks.exitEditor()
        }

        state.childRoutes.isNotEmpty() -> callbacks.popRoute()

        else -> callbacks.defer()
    }
}
