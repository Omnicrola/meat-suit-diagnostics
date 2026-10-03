package com.meatsuitdiagnostics.app.data

import com.meatsuitdiagnostics.app.data.api.ApiClient
import com.meatsuitdiagnostics.app.data.api.AuthException
import com.meatsuitdiagnostics.app.data.api.ConfigResult
import com.meatsuitdiagnostics.app.data.db.OutboxDao
import com.meatsuitdiagnostics.app.data.settings.Credentials
import com.meatsuitdiagnostics.app.data.settings.SettingsStore
import java.io.IOException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

enum class SyncResult { SUCCESS, NOT_CONFIGURED, AUTH_FAILED, RETRY }

/** Uploads queued answers, then refreshes questions and check-ins from the server. */
class Syncer(
    private val settings: SettingsStore,
    private val api: ApiClient,
    private val config: ConfigRepository,
    private val outbox: OutboxDao,
    private val json: Json,
) {
    private val mutex = Mutex()

    suspend fun sync(): SyncResult = mutex.withLock {
        val credentials = settings.credentials() ?: return SyncResult.NOT_CONFIGURED
        val now = System.currentTimeMillis()
        try {
            uploadOutbox(credentials)
            when (val result = api.getConfig(credentials, settings.configEtag())) {
                ConfigResult.NotModified -> Unit
                is ConfigResult.Updated -> {
                    config.apply(result.config)
                    settings.setConfigEtag(result.etag)
                }
            }
            settings.recordSyncSuccess(now)
            SyncResult.SUCCESS
        } catch (e: AuthException) {
            settings.recordSyncFailure(now, "The server rejected the API key. Scan a new setup code.", authFailed = true)
            SyncResult.AUTH_FAILED
        } catch (e: IOException) {
            settings.recordSyncFailure(now, e.message ?: "Couldn't reach the server", authFailed = false)
            SyncResult.RETRY
        } catch (e: SerializationException) {
            settings.recordSyncFailure(now, "Unexpected response from the server", authFailed = false)
            SyncResult.RETRY
        }
    }

    private suspend fun uploadOutbox(credentials: Credentials) {
        while (true) {
            val batch = outbox.pending(BATCH_SIZE)
            if (batch.isEmpty()) return
            val result = api.uploadResponses(credentials, batch.map { json.parseToJsonElement(it.payloadJson) })
            val done = result.accepted + result.duplicates
            if (done.isNotEmpty()) outbox.delete(done)
            // A rejected response would fail the same way on every retry, so park it for the user to see.
            result.rejected.forEach { rejected -> rejected.id?.let { outbox.markFailed(it, rejected.error) } }
            val handled = done.size + result.rejected.size
            if (batch.size < BATCH_SIZE || handled == 0) return
        }
    }

    private companion object {
        const val BATCH_SIZE = 500
    }
}
