package com.meatsuitdiagnostics.app.data.api

import com.meatsuitdiagnostics.app.data.settings.Credentials
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** The server rejected the API key (revoked or regenerated). */
class AuthException : IOException("The server rejected the API key")

sealed interface ConfigResult {
    data object NotModified : ConfigResult
    data class Updated(val config: ConfigDto, val etag: String?) : ConfigResult
}

class ApiClient(private val http: OkHttpClient, private val json: Json) {

    suspend fun getConfig(credentials: Credentials, etag: String?): ConfigResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${credentials.serverUrl}/api/v1/config")
            .header(API_KEY_HEADER, credentials.apiKey)
            .apply { if (etag != null) header("If-None-Match", etag) }
            .build()
        http.newCall(request).execute().use { response ->
            if (response.code == 304) return@use ConfigResult.NotModified
            val body = response.bodyOrThrow()
            ConfigResult.Updated(json.decodeFromString(ConfigDto.serializer(), body), response.header("ETag"))
        }
    }

    suspend fun uploadResponses(credentials: Credentials, responses: List<JsonElement>): UploadResultDto =
        withContext(Dispatchers.IO) {
            val payload = JsonObject(mapOf("responses" to JsonArray(responses))).toString()
            val request = Request.Builder()
                .url("${credentials.serverUrl}/api/v1/responses")
                .header(API_KEY_HEADER, credentials.apiKey)
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            http.newCall(request).execute().use { response ->
                json.decodeFromString(UploadResultDto.serializer(), response.bodyOrThrow())
            }
        }

    private fun Response.bodyOrThrow(): String {
        if (code == 401) throw AuthException()
        if (!isSuccessful) throw IOException("Server returned HTTP $code")
        return body.string()
    }

    private companion object {
        const val API_KEY_HEADER = "X-API-Key"
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
