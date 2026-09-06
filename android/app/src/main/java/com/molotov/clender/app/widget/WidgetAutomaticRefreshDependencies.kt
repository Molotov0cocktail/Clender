package com.molotov.clender.app.widget

import java.time.Clock
import java.time.ZoneId
import kotlinx.coroutines.flow.StateFlow

data class WidgetAutomaticRefreshDependencies(
    val ownedIds: WidgetOwnedIdsPort,
    val localUpdates: WidgetLocalUpdatePort,
    val dateWork: WidgetDateWorkPort,
    val mutationVersion: StateFlow<Long>,
    val clock: Clock,
    val zoneId: () -> ZoneId
)
