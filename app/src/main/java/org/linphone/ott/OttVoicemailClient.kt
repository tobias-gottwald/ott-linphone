/*
 * Copyright (c) 2026 OTT Hoeren
 *
 * This file is part of the OTT softphone app, based on linphone-android
 * (see https://www.linphone.org).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.linphone.ott

import androidx.annotation.WorkerThread
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.linphone.core.tools.Log

/**
 * HTTP client for the store-scoped PBX sidecar voicemail routes (oc-aa79,
 * mirrors the backend contract of oc-6a62):
 *
 * - GET {base}/voicemails?limit=30&cursor=<opaque> returning
 *   {locationId, items: VoicemailSummary[], nextCursor: string|null},
 *   newest-first with bounded pagination;
 * - GET {base}/voicemails/:id returning
 *   {locationId, voicemail: VoicemailSummary & {audioUrl: string|null}}.
 *
 * Authentication reuses the ONE existing credential convention: the
 * [OttPhoneServices] target derived from the raw [ott] carddav configuration
 * (HTTP Basic over HTTPS). The Basic header is ONLY ever sent to the sidecar
 * URL itself — redirects are refused (never followed), so the credential can
 * not leak to another origin, and the audio of a voicemail is played from the
 * storage URL returned by the detail route WITHOUT any phone credential.
 *
 * Methods are blocking and MUST be called from a background thread; the
 * caller owns cancellation ([Cancellation]) and drops results whose request
 * was cancelled or whose identity changed in the meantime.
 *
 * Error mapping of the documented backend statuses:
 * - 401/403 -> [VoicemailError.UNAUTHORIZED] (missing/revoked/wrong credentials)
 * - 409 -> [VoicemailError.NO_LOCATION] (active device without location)
 * - 502, 404 and every other non-200 -> [VoicemailError.SERVER] (wrong
 *   store/org, missing record and record without voicemail are all the same
 *   public error on purpose)
 * - [IOException] -> [VoicemailError.NETWORK]
 * - malformed JSON -> [VoicemailError.PARSE]
 */
object OttVoicemailClient {
    private const val TAG = "[OTT Voicemail Client]"

    const val VOICEMAILS_PATH = "/voicemails"

    private const val DEFAULT_PAGE_LIMIT = 30

    private const val CONNECT_TIMEOUT_MS = 10000
    private const val READ_TIMEOUT_MS = 15000

    /** The documented transcriptionStatus values of VoicemailSummary. */
    enum class TranscriptionStatus {
        PENDING,
        IN_PROGRESS,
        COMPLETED,
        FAILED
    }

    /** Coarse failure classification for user-facing error states. */
    enum class VoicemailError {
        /** The phone has no sidecar/carddav configuration at all. */
        UNCONFIGURED,
        UNAUTHORIZED,
        NO_LOCATION,
        SERVER,
        NETWORK,
        PARSE
    }

    /**
     * One voicemail row, exactly the VoicemailSummary DTO of the contract.
     * All timestamps are milliseconds since epoch (the backend derives them
     * from Date.now()-based fields).
     */
    class VoicemailSummary(
        val id: String,
        val callId: String,
        val timestamp: Long,
        val callerNumber: String?,
        val callerName: String?,
        val durationMs: Long,
        val transcriptionStatus: TranscriptionStatus?,
        val transcriptText: String?,
        val audioAvailable: Boolean,
        val audioExpiresAt: Long
    )

    /** One page of GET /voicemails. */
    class VoicemailPage(
        val locationId: String,
        val items: List<VoicemailSummary>,
        val nextCursor: String?
    )

    /** The result of GET /voicemails/:id. */
    class VoicemailDetail(
        val locationId: String,
        val voicemail: VoicemailSummary,
        val audioUrl: String?
    )

    sealed class Result {
        class ListSuccess(val page: VoicemailPage) : Result()

        class DetailSuccess(val detail: VoicemailDetail) : Result()

        class Failure(val error: VoicemailError, val httpStatus: Int? = null) : Result()
    }

    /**
     * Cooperative cancellation for one in-flight request: [cancel] flags the
     * request AND disconnects its connection, so a blocked read aborts
     * immediately instead of timing out. Results of cancelled requests are
     * meaningless and must be dropped by the caller.
     */
    class Cancellation {
        @Volatile
        var cancelled = false
            private set

        @Volatile
        internal var connection: HttpURLConnection? = null

        fun cancel() {
            cancelled = true
            connection?.disconnect()
        }
    }

    /**
     * GET {base}/voicemails?limit=[limit]&cursor=[cursor]. [limit] is clamped
     * to the documented 1..100 range. Must be called from a background thread.
     */
    @WorkerThread
    fun list(
        target: OttPhoneServices.Target,
        limit: Int = DEFAULT_PAGE_LIMIT,
        cursor: String? = null,
        cancellation: Cancellation
    ): Result {
        val clampedLimit = limit.coerceIn(1, 100)
        val url = buildString {
            append(target.url)
            append("?limit=").append(clampedLimit)
            if (!cursor.isNullOrEmpty()) {
                append("&cursor=").append(URLEncoder.encode(cursor, "UTF-8"))
            }
        }
        return request(url, target.authorization, cancellation, ::parsePage)
    }

    /**
     * GET {base}/voicemails/[id]. Must be called from a background thread.
     */
    @WorkerThread
    fun detail(
        target: OttPhoneServices.Target,
        id: String,
        cancellation: Cancellation
    ): Result {
        val url = target.url + "/" + URLEncoder.encode(id, "UTF-8")
        return request(url, target.authorization, cancellation, ::parseDetail)
    }

    @WorkerThread
    private fun request(
        url: String,
        authorization: String,
        cancellation: Cancellation,
        parse: (String) -> Result
    ): Result {
        return when (val body = executeGet(url, authorization, cancellation)) {
            is String -> try {
                parse(body)
            } catch (e: JSONException) {
                Log.w("$TAG Failed to parse voicemail response of [$url]: ${e.message}")
                Result.Failure(VoicemailError.PARSE)
            }
            is Result.Failure -> body
            // IOException (cancelled or real network problem): both map to
            // NETWORK; the caller drops results of cancelled requests anyway.
            else -> Result.Failure(VoicemailError.NETWORK)
        }
    }

    /**
     * Performs the authenticated GET. Returns the response body on HTTP 200,
     * a [Result.Failure] for any other status (including refused redirects)
     * and null on [IOException] (the caller distinguishes cancellation).
     * The Authorization header is set for this sidecar URL ONLY and redirects
     * are never followed, so the Basic credential cannot leak elsewhere.
     * Must be called from a background thread.
     */
    @WorkerThread
    private fun executeGet(
        url: String,
        authorization: String,
        cancellation: Cancellation
    ): Any? {
        var connection: HttpURLConnection? = null
        try {
            connection = URL(url).openConnection() as HttpURLConnection
            cancellation.connection = connection
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", authorization)
            connection.setRequestProperty("Accept", "application/json")

            val statusCode = connection.responseCode
            if (statusCode != HttpURLConnection.HTTP_OK) {
                if (statusCode == 409) {
                    Log.w("$TAG GET [$url] answered 409: device has no assigned location")
                    return Result.Failure(VoicemailError.NO_LOCATION, statusCode)
                }
                val errorBody = try {
                    connection.errorStream?.bufferedReader()?.use { it.readText() }
                } catch (e: IOException) {
                    null
                }
                Log.w(
                    "$TAG GET [$url] failed with HTTP status [$statusCode]" +
                        (errorBody?.takeIf { it.isNotBlank() }?.let { ", body [$it]" } ?: "")
                )
                val error = if (statusCode == HttpURLConnection.HTTP_UNAUTHORIZED ||
                    statusCode == HttpURLConnection.HTTP_FORBIDDEN
                ) {
                    VoicemailError.UNAUTHORIZED
                } else {
                    VoicemailError.SERVER
                }
                return Result.Failure(error, statusCode)
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            if (cancellation.cancelled) {
                Log.i("$TAG GET [$url] was cancelled")
            } else {
                Log.w("$TAG GET [$url] failed: ${e.message}")
            }
            return null
        } finally {
            cancellation.connection = null
            connection?.disconnect()
        }
    }

    @Throws(JSONException::class)
    private fun parsePage(body: String): Result {
        val json = JSONObject(body)
        val locationId = json.optString("locationId")
        if (locationId.isEmpty()) {
            throw JSONException("missing locationId")
        }
        val items = parseSummaryArray(json.optJSONArray("items") ?: JSONArray())
        val nextCursor = optNullableString(json, "nextCursor")
        return Result.ListSuccess(VoicemailPage(locationId, items, nextCursor))
    }

    @Throws(JSONException::class)
    private fun parseDetail(body: String): Result {
        val json = JSONObject(body)
        val locationId = json.optString("locationId")
        if (locationId.isEmpty()) {
            throw JSONException("missing locationId")
        }
        val voicemailJson = json.optJSONObject("voicemail") ?: throw JSONException("missing voicemail")
        val summary = parseSummary(voicemailJson)
        val audioUrl = optNullableString(voicemailJson, "audioUrl")
        return Result.DetailSuccess(VoicemailDetail(locationId, summary, audioUrl))
    }

    @Throws(JSONException::class)
    private fun parseSummaryArray(array: JSONArray): List<VoicemailSummary> {
        val items = ArrayList<VoicemailSummary>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val summary = try {
                parseSummary(item)
            } catch (e: JSONException) {
                Log.w("$TAG Skipping malformed voicemail item #[$i]: ${e.message}")
                continue
            }
            items.add(summary)
        }
        return items
    }

    @Throws(JSONException::class)
    private fun parseSummary(json: JSONObject): VoicemailSummary {
        val id = json.optString("id")
        if (id.isEmpty()) {
            throw JSONException("missing id")
        }
        return VoicemailSummary(
            id,
            json.optString("callId"),
            json.optLong("timestamp", 0L),
            optNullableString(json, "callerNumber"),
            optNullableString(json, "callerName"),
            json.optLong("durationMs", 0L),
            when (json.optString("transcriptionStatus")) {
                "pending" -> TranscriptionStatus.PENDING
                "in_progress" -> TranscriptionStatus.IN_PROGRESS
                "completed" -> TranscriptionStatus.COMPLETED
                "failed" -> TranscriptionStatus.FAILED
                else -> null
            },
            optNullableString(json, "transcriptText"),
            json.optBoolean("audioAvailable", false),
            json.optLong("audioExpiresAt", 0L)
        )
    }

    private fun optNullableString(json: JSONObject, key: String): String? {
        if (!json.has(key) || json.isNull(key)) {
            return null
        }
        return json.optString(key).takeIf { it.isNotEmpty() }
    }
}
