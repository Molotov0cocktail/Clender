package com.molotov.clender.ui.app

internal data class AppShellScaffoldActions(
    val openDrawer: () -> Unit,
    val navigateBack: () -> Unit,
    val requestDelete: () -> Unit,
    val onCreate: () -> Unit = {}
)
