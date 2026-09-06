package com.molotov.clender.ui.app

import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.molotov.clender.R
import com.molotov.clender.ui.navigation.AppDestination
import com.molotov.clender.ui.state.AppShellUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppTopBar(
    model: AppContentModel,
    actions: AppShellScaffoldActions,
    conversationAction: @Composable () -> Unit = {}
) {
    TopAppBar(
        title = {
            Text(
                stringResource(screenTitle(model)),
                modifier = Modifier.semantics { heading() },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        actions = {
            if (canCreateEvent(model.shell)) CreateEventButton(actions.onCreate)
            conversationAction()
        },
        navigationIcon = {
            if (model.shell.childRoutes.isNotEmpty()) {
                IconButton(
                    onClick = actions.navigateBack,
                    modifier = Modifier
                        .testTag("clender_navigate_back")
                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.semantics_navigate_back)
                    )
                }
            } else {
                IconButton(
                    onClick = actions.openDrawer,
                    modifier = Modifier
                        .testTag("clender_open_navigation_drawer")
                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Menu,
                        contentDescription = stringResource(
                            R.string.semantics_open_navigation_drawer
                        )
                    )
                }
            }
        }
    )
}

@Composable
private fun CreateEventButton(onCreate: () -> Unit) {
    IconButton(
        onClick = onCreate,
        modifier = Modifier.testTag("clender_create_event")
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.Add,
            contentDescription = stringResource(R.string.screen_event_new)
        )
    }
}

internal fun canCreateEvent(state: AppShellUiState): Boolean = state.childRoutes.isEmpty() &&
    (state.destination == AppDestination.CALENDAR || state.destination == AppDestination.EVENTS)
