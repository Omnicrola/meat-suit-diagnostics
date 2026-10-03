package com.meatsuitdiagnostics.app.data.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class Credentials(val serverUrl: String, val apiKey: String)

data class SyncStatus(
    val lastSuccessAt: Long? = null,
    val lastAttemptAt: Long? = null,
    val lastError: String? = null,
    /** The server rejected the key; syncing stops until a new setup code is scanned. */
    val authFailed: Boolean = false,
)

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsStore(context: Context, private val cipher: KeystoreCipher) {
    private val store = context.dataStore

    val credentials: Flow<Credentials?> = store.data.map { prefs ->
        val url = prefs[SERVER_URL] ?: return@map null
        val encrypted = prefs[API_KEY] ?: return@map null
        // If the Keystore key was lost (rare; e.g. a lock-screen reset), the app simply needs setting up again.
        runCatching { Credentials(url, cipher.decrypt(encrypted)) }.getOrNull()
    }

    val syncStatus: Flow<SyncStatus> = store.data.map { prefs ->
        SyncStatus(
            lastSuccessAt = prefs[LAST_SUCCESS],
            lastAttemptAt = prefs[LAST_ATTEMPT],
            lastError = prefs[LAST_ERROR],
            authFailed = prefs[AUTH_FAILED] ?: false,
        )
    }

    suspend fun credentials(): Credentials? = credentials.first()

    suspend fun saveCredentials(credentials: Credentials) {
        val encrypted = cipher.encrypt(credentials.apiKey)
        store.edit {
            it[SERVER_URL] = credentials.serverUrl
            it[API_KEY] = encrypted
            it.remove(CONFIG_ETAG)
            it.remove(LAST_ERROR)
            it[AUTH_FAILED] = false
        }
    }

    suspend fun configEtag(): String? = store.data.first()[CONFIG_ETAG]

    suspend fun setConfigEtag(etag: String?) {
        store.edit { if (etag == null) it.remove(CONFIG_ETAG) else it[CONFIG_ETAG] = etag }
    }

    suspend fun recordSyncSuccess(now: Long) {
        store.edit {
            it[LAST_SUCCESS] = now
            it[LAST_ATTEMPT] = now
            it.remove(LAST_ERROR)
            it[AUTH_FAILED] = false
        }
    }

    suspend fun recordSyncFailure(now: Long, message: String, authFailed: Boolean) {
        store.edit {
            it[LAST_ATTEMPT] = now
            it[LAST_ERROR] = message
            it[AUTH_FAILED] = authFailed
        }
    }

    private companion object {
        val SERVER_URL = stringPreferencesKey("server_url")
        val API_KEY = stringPreferencesKey("api_key_encrypted")
        val CONFIG_ETAG = stringPreferencesKey("config_etag")
        val LAST_SUCCESS = longPreferencesKey("last_sync_success")
        val LAST_ATTEMPT = longPreferencesKey("last_sync_attempt")
        val LAST_ERROR = stringPreferencesKey("last_sync_error")
        val AUTH_FAILED: Preferences.Key<Boolean> = booleanPreferencesKey("auth_failed")
    }
}
