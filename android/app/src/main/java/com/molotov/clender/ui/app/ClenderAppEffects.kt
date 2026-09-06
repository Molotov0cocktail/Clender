package com.molotov.clender.ui.app

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.molotov.clender.ui.event.EventCrudEffect
import com.molotov.clender.ui.event.EventCrudRoute
import com.molotov.clender.ui.event.EventCrudViewModel
import com.molotov.clender.ui.event.EventListNavigation
import com.molotov.clender.ui.navigation.AppDestination
import com.molotov.clender.ui.navigation.AppRoute
import com.molotov.clender.ui.settings.SettingsViewModel
import com.molotov.clender.ui.state.AppShellUiState
import com.molotov.clender.ui.state.AppShellViewModel

@Composable
internal fun SynchronizeAppRoute(
    state: AppShellUiState,
    dependencies: AppDependencies,
    dialogs: CrudDialogState
) {
    LaunchedEffect(state.childRoutes.lastOrNull(), state.destination) {
        dialogs.deleteConfirmation = false
        dialogs.discardConfirmation = false
        val currentRoute = state.childRoutes.lastOrNull()
        if (state.destination == AppDestination.AI || currentRoute == AppRoute.QuickAi) {
            dependencies.conversationViewModel?.activate()
            dependencies.aiSubmissionViewModel?.activate()
        }
        if (state.destination == AppDestination.SETTINGS) {
            dependencies.settingsViewModel?.activate()
        }
        if (currentRoute != null) dependencies.shellViewModel.closeDrawer()
        when (currentRoute) {
            is AppRoute.EventDetail ->
                synchronizeEventDetail(dependencies.eventCrudViewModel, currentRoute.id.toLong())

            is AppRoute.NewEvent -> {
                dependencies.shellViewModel.drafts.ensure()
                val existing = dependencies.eventCrudViewModel.state.value
                if (existing.route != EventCrudRoute.New(currentRoute.date) ||
                    existing.form == null
                ) {
                    dependencies.eventCrudViewModel.startNew(currentRoute.date)
                }
            }

            else -> dependencies.eventCrudViewModel.clearRoute()
        }
    }
}

private fun synchronizeEventDetail(viewModel: EventCrudViewModel, id: Long) {
    val route = viewModel.state.value.route
    if (route != EventCrudRoute.Detail(id) && route != EventCrudRoute.Edit(id)) {
        viewModel.openDetail(id)
    }
}

@Composable
internal fun CollectEventCrudEffects(
    shellViewModel: AppShellViewModel,
    eventCrudViewModel: EventCrudViewModel,
    dialogs: CrudDialogState
) {
    LaunchedEffect(eventCrudViewModel) {
        eventCrudViewModel.effects.collect { effect ->
            when (effect) {
                is EventCrudEffect.ReplaceWithDetail -> {
                    shellViewModel.drafts.clear()
                    EventListNavigation.detailRoute(effect.id)?.let(shellViewModel::replaceTopRoute)
                        ?: shellViewModel.popRoute()
                }

                is EventCrudEffect.ShowDetail -> Unit

                EventCrudEffect.PopRoute -> {
                    dialogs.deleteConfirmation = false
                    shellViewModel.popRoute()
                }
            }
        }
    }
}

@Composable
internal fun PlaceholderScreen(@StringRes titleRes: Int) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(text = stringResource(titleRes))
    }
}

internal fun exitEventEditor(
    shellViewModel: AppShellViewModel,
    eventCrudViewModel: EventCrudViewModel,
    route: AppRoute?
) {
    when (route) {
        is AppRoute.NewEvent -> {
            shellViewModel.drafts.clear()
            shellViewModel.popRoute()
            eventCrudViewModel.clearRoute()
        }

        is AppRoute.EventDetail -> eventCrudViewModel.openDetail(route.id.toLong())

        else -> Unit
    }
}
