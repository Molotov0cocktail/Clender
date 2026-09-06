package com.molotov.clender.ui.app

import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow

@Composable
internal fun rememberSynchronizedDrawerState(
    requestedOpen: Boolean,
    dependencies: AppDependencies
): DrawerState {
    val currentDependencies by rememberUpdatedState(dependencies)
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    LaunchedEffect(drawerState) {
        var observedTarget = DrawerValue.Closed
        snapshotFlow { drawerState.targetValue }.collect { target ->
            if (target != observedTarget) {
                observedTarget = target
                val open = target == DrawerValue.Open
                currentDependencies.settingsViewModel?.setDrawerOpen(open)
                if (open) {
                    currentDependencies.shellViewModel.openDrawer()
                } else {
                    currentDependencies.shellViewModel.closeDrawer()
                }
            }
        }
    }
    LaunchedEffect(requestedOpen) {
        dependencies.settingsViewModel?.setDrawerOpen(requestedOpen)
        val target = if (requestedOpen) DrawerValue.Open else DrawerValue.Closed
        if (drawerState.targetValue != target) {
            if (requestedOpen) drawerState.open() else drawerState.close()
        }
    }
    return drawerState
}
