package com.molotov.clender.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import com.molotov.clender.domain.conversation.ActiveConversationStore
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

class DataStoreActiveConversationStore(private val dataStore: DataStore<Preferences>) :
    ActiveConversationStore {
    override val activeConversationId: Flow<String?> = dataStore.data
        .catch { failure ->
            if (failure is IOException) emit(emptyPreferences()) else throw failure
        }
        .map { preferences ->
            preferences[ACTIVE_ID_KEY]?.trim()?.takeIf(String::isNotEmpty)
        }

    override suspend fun setActiveConversationId(id: String?) {
        val normalized = id?.trim()
        require(normalized == null || normalized.isNotEmpty()) {
            "Active conversation ID cannot be blank"
        }
        dataStore.edit { preferences ->
            if (normalized == null) {
                preferences.remove(ACTIVE_ID_KEY)
            } else {
                preferences[ACTIVE_ID_KEY] = normalized
            }
        }
    }

    companion object {
        private val ACTIVE_ID_KEY =
            PreferenceWireCodec.string(AppPreferenceKeys.ACTIVE_CONVERSATION_ID)

        fun preferencesFile(context: Context): File =
            File(context.filesDir, "datastore/clender.preferences_pb")
    }
}
