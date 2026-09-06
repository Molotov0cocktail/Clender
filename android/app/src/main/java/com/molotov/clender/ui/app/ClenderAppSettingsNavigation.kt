package com.molotov.clender.ui.app

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.DrawerState
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.molotov.clender.R
import com.molotov.clender.ui.navigation.AppDestination
import com.molotov.clender.ui.navigation.DrawerNavigation
import com.molotov.clender.ui.settings.SettingsDialog
import com.molotov.clender.ui.settings.SettingsUiState

@Composable
internal fun rememberAppBackCallbacks(
    model: AppContentModel,
    dependencies: AppDependencies,
    dialogs: CrudDialogState,
    dispatcher: OnBackPressedDispatcher?
): BackCallbacks {
    val route = model.shell.childRoutes.lastOrNull()
    return BackCallbacks(
        closeDrawer = {
            dependencies.shellViewModel.closeDrawer()
            dependencies.settingsViewModel?.setDrawerOpen(false)
        },
        showDiscard = { dialogs.discardConfirmation = true },
        exitEditor = {
            exitEventEditor(
                dependencies.shellViewModel,
                dependencies.eventCrudViewModel,
                route
            )
        },
        popRoute = {
            dependencies.shellViewModel.popRoute()
            dependencies.eventCrudViewModel.clearRoute()
        },
        defer = { dispatcher?.onBackPressed() }
    )
}

@Composable
internal fun AppBackInterception(
    model: AppContentModel,
    dependencies: AppDependencies,
    settingsState: SettingsUiState?,
    environment: AppBackEnvironment
) {
    val settingsInterceptsBack = model.shell.destination == AppDestination.SETTINGS &&
        settingsState?.let {
            it.dirty || it.removeKeyConfirmation || it.dialog != SettingsDialog.NONE
        } == true
    BackHandler(
        enabled = environment.drawerState.isOpen || model.shell.childRoutes.isNotEmpty() ||
            settingsInterceptsBack
    ) {
        if (model.shell.destination == AppDestination.SETTINGS &&
            model.shell.childRoutes.isEmpty()
        ) {
            handleSettingsBack(dependencies, environment)
        } else {
            handleBack(
                model.shell,
                model.eventCrud,
                environment.drawerState.isOpen,
                environment.callbacks
            )
        }
    }
}

internal data class AppBackEnvironment(
    val drawerState: DrawerState,
    val callbacks: BackCallbacks,
    val dispatcher: OnBackPressedDispatcher?
)

@Composable
internal fun SettingsAwareDrawerContent(model: AppContentModel, dependencies: AppDependencies) {
    AppDrawerContent(model.shell) { destination ->
        val navigate = navigationAction(destination, dependencies)
        if (model.shell.destination == AppDestination.SETTINGS) {
            dependencies.settingsViewModel?.requestNavigation(navigate) ?: navigate()
        } else {
            navigate()
        }
    }
}

private fun navigationAction(
    destination: AppDestination,
    dependencies: AppDependencies
): () -> Unit = {
    if (destination == AppDestination.SETTINGS) {
        dependencies.settingsViewModel?.showApplicationSection()
    } else {
        dependencies.settingsViewModel?.leaveSettings()
    }
    dependencies.shellViewModel.navigateTo(destination)
    dependencies.shellViewModel.closeDrawer()
    dependencies.settingsViewModel?.setDrawerOpen(false)
}

private fun handleSettingsBack(dependencies: AppDependencies, environment: AppBackEnvironment) {
    val settings = dependencies.settingsViewModel
    when {
        environment.drawerState.isOpen -> environment.callbacks.closeDrawer()

        settings?.state?.value?.removeKeyConfirmation == true ||
            settings?.state?.value?.dialog != SettingsDialog.NONE -> settings?.handleBack()

        settings?.state?.value?.dirty == true -> settings.requestNavigation {
            settings.leaveSettings()
            environment.dispatcher?.onBackPressed()
        }

        else -> environment.dispatcher?.onBackPressed()
    }
}

@Composable
internal fun AppDrawerContent(
    state: com.molotov.clender.ui.state.AppShellUiState,
    onDestinationClick: (AppDestination) -> Unit
) {
    ModalDrawerSheet {
        DrawerNavigation.destinations.forEach { destination ->
            NavigationDrawerItem(
                label = { Text(stringResource(destination.drawerLabel())) },
                selected = destination == state.destination,
                onClick = { onDestinationClick(destination) },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("clender_drawer_${destination.route}")
            )
        }
    }
}

private fun AppDestination.drawerLabel(): Int = when (this) {
    AppDestination.CALENDAR -> R.string.drawer_calendar
    AppDestination.EVENTS -> R.string.drawer_events
    AppDestination.AI -> R.string.drawer_ai
    AppDestination.SETTINGS -> R.string.drawer_settings
    AppDestination.ABOUT -> R.string.drawer_about
}
