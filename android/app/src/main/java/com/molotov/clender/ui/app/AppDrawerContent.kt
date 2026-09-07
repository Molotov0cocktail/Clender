package com.molotov.clender.ui.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.molotov.clender.R
import com.molotov.clender.ui.navigation.AppDestination
import com.molotov.clender.ui.navigation.DrawerNavigation
import com.molotov.clender.ui.state.AppShellUiState

@Composable
internal fun AppDrawerContent(
    state: AppShellUiState,
    onDestinationClick: (AppDestination) -> Unit
) {
    ModalDrawerSheet(
        modifier = Modifier.testTag("clender_drawer_sheet"),
        drawerContainerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            DrawerBrand()
            DrawerNavigation.destinations.forEach { destination ->
                when (destination) {
                    AppDestination.CALENDAR -> DrawerSectionHeading(
                        R.string.drawer_schedule_section,
                        "drawer_schedule_heading"
                    )

                    AppDestination.SETTINGS -> DrawerSectionHeading(
                        R.string.drawer_application_section,
                        "drawer_application_heading"
                    )

                    else -> Unit
                }
                DrawerDestinationItem(destination, destination == state.destination) {
                    onDestinationClick(destination)
                }
            }
        }
    }
}

@Composable
private fun DrawerBrand() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp).testTag("drawer_brand"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(16.dp)) {
            Image(
                painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.size(52.dp)
            )
        }
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun DrawerSectionHeading(label: Int, tag: String) {
    Text(
        stringResource(label),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(start = 14.dp, top = 14.dp, bottom = 6.dp)
            .semantics { heading() }.testTag(tag)
    )
}

@Composable
private fun DrawerDestinationItem(
    destination: AppDestination,
    selected: Boolean,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = if (selected) colors.secondaryContainer else colors.surface,
        contentColor = if (selected) colors.onSecondaryContainer else colors.onSurface,
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .selectable(selected = selected, role = Role.Tab, onClick = onClick)
                .heightIn(min = 56.dp)
                .testTag("clender_drawer_${destination.route}")
                .padding(horizontal = 14.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                destination.drawerIcon(),
                contentDescription = null,
                modifier = Modifier.size(24.dp).testTag("drawer_icon_${destination.route}")
            )
            Text(
                stringResource(destination.drawerLabel()),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

private fun AppDestination.drawerIcon(): ImageVector = when (this) {
    AppDestination.CALENDAR -> Icons.Default.DateRange
    AppDestination.EVENTS -> Icons.AutoMirrored.Filled.List
    AppDestination.AI -> Icons.AutoMirrored.Filled.Send
    AppDestination.SETTINGS -> Icons.Default.Settings
    AppDestination.ABOUT -> Icons.Default.Info
}

private fun AppDestination.drawerLabel(): Int = when (this) {
    AppDestination.CALENDAR -> R.string.drawer_calendar
    AppDestination.EVENTS -> R.string.drawer_events
    AppDestination.AI -> R.string.drawer_ai
    AppDestination.SETTINGS -> R.string.drawer_settings
    AppDestination.ABOUT -> R.string.drawer_about
}
