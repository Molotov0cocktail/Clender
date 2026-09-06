package com.molotov.clender.domain.conversation

import kotlinx.coroutines.flow.Flow

interface ActiveConversationStore {
    val activeConversationId: Flow<String?>

    suspend fun setActiveConversationId(id: String?)
}
