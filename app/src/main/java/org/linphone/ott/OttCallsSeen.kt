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

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.annotation.AnyThread
import androidx.annotation.WorkerThread
import androidx.lifecycle.MutableLiveData
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.core.AuthInfo
import org.linphone.core.Call
import org.linphone.core.CallLog
import org.linphone.core.Core
import org.linphone.core.GlobalState
import org.linphone.core.tools.Log
import org.linphone.utils.LinphoneUtils
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Shared per-location "unseen calls" state (oc-2532).
 *
 * Every OTT location (Amparex branch) has a server-side set of call records
 * that no device has marked as seen yet, served by the PBX sidecar ({base}
 * derived from the [ott] carddav_intern_url configuration value):
 * - GET {base}/calls/unseen returns {locationId, unseen[], newestKnownStartAt},
 * - POST {base}/calls/seen-range marks everything up to the server's "now"
 *   as seen and returns {locationId, marked}.
 *
 * Whenever any phone of the location opens its history ("Anrufe" tab) or the
 * dashboard does, all devices get an FCM data push (reason "calls_seen").
 * That push is only a HINT to re-GET the state; its payload is never applied
 * as state (see [onCallsSeenPush]).
 *
 * Server call records are matched against the device's call logs by
 * embedded call id: FreeSWITCH stamps every leg of an OTT call with the SIP
 * dialog Call-ID "<aLegUuid>_<suffix>@<domain>", where aLegUuid is the PBX
 * call record id (the value the server reports in unseen[].callId) and the
 * suffix varies (auth username, extension, group name, ...). The OTT id of
 * a call log is therefore the substring of its Call-ID before the FIRST '_'
 * (the uuid never contains '_'); see [ottIdFromCallId]. Call logs whose
 * Call-ID has no '_' (legacy, pre-embedding ids) are considered seen.
 *
 * This object owns the device-local copy of that state:
 * - persisted in the [PREFERENCES_NAME] SharedPreferences as JSON
 *   ({locationId, unseen[], newestKnownStartAt}),
 * - observable through [unseenStateChanged] (fires after every update),
 * - used to display unseen incoming calls in bold in the history list and
 *   to compute the missed calls badge / notification count
 *   ([unseenMissedCount]).
 */
object OttCallsSeen {
    private const val TAG = "[OTT Calls Seen]"

    private const val PREFERENCES_NAME = "ott_calls_seen"
    private const val PREFERENCE_STATE = "state"

    private const val JSON_LOCATION_ID = "locationId"
    private const val JSON_UNSEEN = "unseen"
    private const val JSON_CALL_ID = "callId"
    private const val JSON_NEWEST_KNOWN_START_AT = "newestKnownStartAt"

    private const val CONFIG_SECTION = "ott"
    private const val CONFIG_INTERN_URL_KEY = "carddav_intern_url"

    private const val CARD_DAV_PATH_MARKER = "/carddav/"
    private const val CALLS_UNSEEN_PATH = "/calls/unseen"
    private const val CALLS_SEEN_RANGE_PATH = "/calls/seen-range"

    /**
     * An OTT id absent from the server's unseen set may belong to a call
     * newer than the last CDR ingest on the PBX: within this slack window
     * after newestKnownStartAt the call is still considered unseen (pending
     * ingest), beyond it the record had time to arrive, so the call is
     * considered seen.
     */
    private const val PENDING_CDR_INGEST_SLACK_MS = 30_000L

    private const val CONNECT_TIMEOUT_MS = 10000
    private const val READ_TIMEOUT_MS = 10000

    private val lock = Any()

    private var stateLoaded = false // Guarded by [lock]

    private var stateValue = CallsSeenState(null, emptySet(), 0L) // Guarded by [lock]

    private var missingConfigurationLogged = false

    /**
     * Immutable snapshot of the calls-seen state. [locationId] stays null
     * until a first server response has been applied; in that pristine
     * state nothing can be unseen, which
     * leaves unprovisioned stock behavior untouched.
     */
    private class CallsSeenState(
        val locationId: String?,
        val unseenIds: Set<String>,
        val newestKnownStartAt: Long
    )

    /**
     * Fires after every change of the local unseen-calls state (server GET
     * response, local clear after marking seen). The value is the
     * newestKnownStartAt of the new state and must be treated purely as a
     * "something changed" signal: observers re-read [isUnseenCallLog] /
     * [unseenMissedCount] instead (oc-3acb keeps the missed calls badge in
     * sync this way). Rapid successive updates may coalesce, which is fine
     * for a re-compute trigger.
     */
    val unseenStateChanged: MutableLiveData<Long> by lazy {
        MutableLiveData(snapshotState().newestKnownStartAt)
    }

    /**
     * Fetches the server-side unseen set (GET {base}/calls/unseen) in the
     * background and replaces the local state with it. Called at core start
     * and on calls-seen FCM pushes so a device that was offline (or without
     * a running core) catches up with what other devices or the dashboard
     * have marked as seen.
     */
    @AnyThread
    fun refreshFromServer() {
        Log.i("$TAG Refreshing unseen calls from server")
        runOnHttpThread(CALLS_UNSEEN_PATH) { target ->
            val body = httpRequest("GET", target) ?: return@runOnHttpThread
            try {
                applyUnseenResponse(JSONObject(body))
            } catch (e: JSONException) {
                Log.w("$TAG Failed to parse calls-unseen GET response [$body]: ${e.message}")
            }
        }
    }

    /**
     * Notifies the server that this location's calls have been seen up to
     * now (POST {base}/calls/seen-range, no body) in the background.
     * The server stamps its own "now" and the response only acknowledges
     * ({locationId, marked}), so instead of a second GET round-trip the
     * local state is optimistically cleared: the unseen set is emptied and
     * newestKnownStartAt advances to the device's now (never backwards).
     * A device clock skewing from the server's is acceptable here: this is
     * a display heuristic and the next GET /calls/unseen replaces the state
     * wholesale anyway.
     */
    @AnyThread
    fun markCallsSeen() {
        Log.i("$TAG Notifying server that calls have been seen")
        runOnHttpThread(CALLS_SEEN_RANGE_PATH) { target ->
            val body = httpRequest("POST", target) ?: return@runOnHttpThread
            try {
                val locationId = JSONObject(body).optString(JSON_LOCATION_ID).takeIf { it.isNotEmpty() }
                val previous = snapshotState()
                val now = System.currentTimeMillis()
                setState(
                    CallsSeenState(
                        locationId ?: previous.locationId,
                        emptySet(),
                        maxOf(now, previous.newestKnownStartAt)
                    )
                )
                unseenStateChanged.postValue(now)
                maybeClearMissedCallIndicators()
            } catch (e: JSONException) {
                Log.w("$TAG Failed to parse calls-seen-range POST response [$body]: ${e.message}")
            }
        }
    }

    /**
     * Handles a calls-seen FCM data push. The push is only a HINT that the
     * server-side state moved (some device or the dashboard marked calls as
     * seen): its seenAt/locationId payload is NOT applied as state, the
     * server is simply re-queried.
     */
    @AnyThread
    fun onCallsSeenPush(locationId: String) {
        Log.i("$TAG Calls-seen push for location [$locationId], re-fetching unseen calls from server")
        refreshFromServer()
    }

    /**
     * Whether the given call log hasn't been seen yet on any device of the
     * location:
     * - outgoing calls are always seen;
     * - the log's OTT id being part of the server's unseen set means unseen;
     * - an OTT id NOT in the set is still considered unseen within
     *   [PENDING_CDR_INGEST_SLACK_MS] after newestKnownStartAt (the PBX CDR
     *   ingest may lag behind the call itself); beyond that window the
     *   record had time to arrive, so the call is seen;
     * - a Call-ID without '_' (legacy FS-generated ids, no embedded call
     *   id) is considered seen;
     * - as long as no server state was ever applied (feature unconfigured
     *   or not fetched yet) nothing is unseen.
     */
    @AnyThread
    fun isUnseenCallLog(callLog: CallLog): Boolean {
        if (callLog.dir == Call.Dir.Outgoing) {
            return false
        }
        return isUnseen(callLog, snapshotState())
    }

    /**
     * Number of missed call logs (LinphoneUtils.isCallLogMissed: aborted /
     * early-aborted included, same definition as the notification trigger)
     * that are still unseen. Replaces core.missedCallsCount for the badge
     * and the missed call notification when the feature is configured
     * ([isConfigured]). Must be called from a thread on which the core can
     * be accessed.
     */
    @WorkerThread
    fun unseenMissedCount(): Int {
        if (!coreContext.isCoreAvailable()) {
            return 0
        }
        val state = snapshotState()
        return coreContext.core.callLogs.count {
            LinphoneUtils.isCallLogMissed(it) && isUnseen(it, state)
        }
    }

    /**
     * Whether the calls-seen feature is configured: an [ott]
     * carddav_intern_url value is present from which the PBX sidecar base
     * URL can be derived. When false (unprovisioned stock setup) callers
     * keep the stock core.missedCallsCount behavior. Must be called from a
     * thread on which the core can be accessed.
     */
    @WorkerThread
    fun isConfigured(): Boolean {
        if (!coreContext.isCoreAvailable()) {
            return false
        }
        return ottBaseUrl(coreContext.core) != null
    }

    /**
     * Replaces the local state with a GET /calls/unseen response
     * {locationId, unseen[{callId, ...}], newestKnownStartAt}. The server
     * state is authoritative, so it wins over the local one even when it
     * looks older. [unseenStateChanged] is fired and the missed call
     * indicators re-evaluated on every response (not only on actual
     * changes), so a device whose push arrived while no core was running
     * still catches up.
     */
    @WorkerThread
    private fun applyUnseenResponse(json: JSONObject) {
        val unseenIds = mutableSetOf<String>()
        val unseenArray = json.optJSONArray(JSON_UNSEEN)
        if (unseenArray != null) {
            for (i in 0 until unseenArray.length()) {
                val callId = unseenArray.optJSONObject(i)?.optString(JSON_CALL_ID).orEmpty()
                if (callId.isNotEmpty()) {
                    unseenIds.add(callId)
                }
            }
        }
        val newestKnownStartAt = json.optLong(JSON_NEWEST_KNOWN_START_AT, 0L)
        val locationId = json.optString(JSON_LOCATION_ID).takeIf { it.isNotEmpty() }
        setState(CallsSeenState(locationId, unseenIds, newestKnownStartAt))
        Log.i(
            "$TAG Unseen calls state replaced: [${unseenIds.size}] unseen call(s), newestKnownStartAt [$newestKnownStartAt], location [$locationId]"
        )
        unseenStateChanged.postValue(newestKnownStartAt)
        maybeClearMissedCallIndicators()
    }

    /**
     * Freshness check shared by [isUnseenCallLog] and [unseenMissedCount].
     * [callLog] must be an incoming call log.
     */
    private fun isUnseen(callLog: CallLog, state: CallsSeenState): Boolean {
        if (state.locationId == null) {
            return false
        }
        val ottId = ottIdFromCallId(callLog.callId) ?: return false
        if (state.unseenIds.contains(ottId)) {
            return true
        }
        return callLog.startDate > state.newestKnownStartAt - PENDING_CDR_INGEST_SLACK_MS
    }

    /**
     * Extracts the OTT call id from a SIP dialog Call-ID: FreeSWITCH stamps
     * every leg of an OTT call with "<aLegUuid>_<suffix>@<domain>", where
     * aLegUuid is the PBX call record id and the suffix varies (auth
     * username, extension, group name, ...). The OTT id is the substring
     * before the FIRST '_' (the uuid never contains '_'). Returns null for
     * ids without '_' or with an empty id part: legacy FS-generated ids
     * carry no embedded call id and are treated as seen.
     */
    private fun ottIdFromCallId(callId: String?): String? {
        if (callId.isNullOrEmpty()) {
            return null
        }
        val separatorIndex = callId.indexOf('_')
        if (separatorIndex <= 0) {
            return null
        }
        return callId.substring(0, separatorIndex)
    }

    /**
     * Resets the missed calls counter and dismisses the missed call
     * notification when a core is running, at least one missed call log
     * exists and none of them is unseen anymore (they all have been seen,
     * on another device of the location or on the dashboard). No-op
     * otherwise.
     */
    @AnyThread
    private fun maybeClearMissedCallIndicators() {
        if (!coreContext.isCoreAvailable()) {
            Log.i("$TAG Core not available, unseen calls state only persisted")
            return
        }
        coreContext.postOnCoreThread { core ->
            if (core.globalState != GlobalState.On) {
                Log.i("$TAG Core isn't running (state [${core.globalState}]), unseen calls state only persisted")
                return@postOnCoreThread
            }

            val state = snapshotState()
            val missedLogs = core.callLogs.filter { LinphoneUtils.isCallLogMissed(it) }
            val unseenMissed = missedLogs.count { isUnseen(it, state) }
            if (missedLogs.isEmpty() || unseenMissed > 0) {
                Log.i(
                    "$TAG [$unseenMissed] of [${missedLogs.size}] missed call(s) still unseen, keeping missed calls indicators"
                )
                return@postOnCoreThread
            }

            Log.i("$TAG All missed calls have been seen, resetting missed calls count & dismissing notification")
            core.resetMissedCallsCount()
            coreContext.notificationsManager.dismissMissedCallNotification()
        }
    }

    @AnyThread
    private fun snapshotState(): CallsSeenState = synchronized(lock) {
        if (!stateLoaded) {
            stateLoaded = true
            stateValue = loadStateFromPreferences(preferences())
            Log.i(
                "$TAG Loaded persisted unseen calls state: [${stateValue.unseenIds.size}] unseen call(s), newestKnownStartAt [${stateValue.newestKnownStartAt}], location [${stateValue.locationId}]"
            )
        }
        stateValue
    }

    @AnyThread
    private fun setState(newState: CallsSeenState) {
        synchronized(lock) {
            stateValue = newState
            val json = JSONObject()
                .put(JSON_LOCATION_ID, newState.locationId ?: JSONObject.NULL)
                .put(JSON_NEWEST_KNOWN_START_AT, newState.newestKnownStartAt)
                .put(JSON_UNSEEN, JSONArray(newState.unseenIds))
            preferences().edit().putString(PREFERENCE_STATE, json.toString()).apply()
        }
    }

    private fun loadStateFromPreferences(preferences: SharedPreferences): CallsSeenState {
        val stateJson = preferences.getString(PREFERENCE_STATE, null)
        if (stateJson != null) {
            try {
                val json = JSONObject(stateJson)
                val unseenIds = mutableSetOf<String>()
                val unseenArray = json.optJSONArray(JSON_UNSEEN)
                if (unseenArray != null) {
                    for (i in 0 until unseenArray.length()) {
                        val callId = unseenArray.optJSONObject(i)?.optString(JSON_CALL_ID).orEmpty()
                        if (callId.isNotEmpty()) {
                            unseenIds.add(callId)
                        }
                    }
                }
                return CallsSeenState(
                    json.optString(JSON_LOCATION_ID).takeIf { it.isNotEmpty() },
                    unseenIds,
                    json.optLong(JSON_NEWEST_KNOWN_START_AT, 0L)
                )
            } catch (e: JSONException) {
                Log.w("$TAG Failed to parse persisted unseen calls state [$stateJson]: ${e.message}, starting fresh")
            }
        }

        return CallsSeenState(null, emptySet(), 0L)
    }

    /**
     * Resolves the PBX sidecar base URL and the basic authorization header
     * on the core thread (both come from the core configuration and auth
     * infos) then runs [block] on a short-lived background thread so the
     * caller is never blocked. Silently gives up when the feature is
     * disabled or credentials are unavailable.
     */
    @AnyThread
    private fun runOnHttpThread(path: String, block: (HttpTarget) -> Unit) {
        if (!coreContext.isCoreAvailable()) {
            Log.w("$TAG Core not available, skipping calls-seen HTTP request")
            return
        }
        coreContext.postOnCoreThread { core ->
            val url = ottBaseUrl(core)?.let { it + path } ?: return@postOnCoreThread
            val authorization = basicAuthorization(core) ?: return@postOnCoreThread
            val target = HttpTarget(url, authorization)
            Thread({ block(target) }, "OTT Calls Seen HTTP").start()
        }
    }

    /**
     * Derives the PBX sidecar base URL from the [ott] carddav_intern_url
     * configuration value by stripping the /carddav/<ext>/<scope> path
     * suffix: https://pbx.example.com:9443/carddav/42/intern becomes
     * https://pbx.example.com:9443. When the configuration value is empty
     * the feature is disabled (logged once per process only).
     */
    @WorkerThread
    private fun ottBaseUrl(core: Core): String? {
        val internUrl = core.config.getString(CONFIG_SECTION, CONFIG_INTERN_URL_KEY, "").orEmpty().trim()
        if (internUrl.isEmpty()) {
            if (!missingConfigurationLogged) {
                missingConfigurationLogged = true
                Log.i(
                    "$TAG No [$CONFIG_SECTION] $CONFIG_INTERN_URL_KEY in configuration, calls-seen feature disabled"
                )
            }
            return null
        }
        val markerIndex = internUrl.indexOf(CARD_DAV_PATH_MARKER)
        if (markerIndex <= 0) {
            Log.w(
                "$TAG [$CONFIG_SECTION] $CONFIG_INTERN_URL_KEY [$internUrl] doesn't contain the expected [$CARD_DAV_PATH_MARKER] path, cannot derive calls-seen endpoint"
            )
            return null
        }
        return internUrl.substring(0, markerIndex)
    }

    /**
     * Builds the HTTP basic authorization header from the device SIP
     * credentials, read at request time: the auth info matching the default
     * account's username when it can be found, the first complete auth info
     * as a fallback.
     */
    @WorkerThread
    private fun basicAuthorization(core: Core): String? {
        var authInfo: AuthInfo? = null
        val username = core.defaultAccount?.params?.identityAddress?.username
        if (!username.isNullOrEmpty()) {
            authInfo = core.findAuthInfo(null, username, null)
        }
        if (authInfo == null) {
            authInfo = core.authInfoList.firstOrNull { !it.username.isNullOrEmpty() && !it.password.isNullOrEmpty() }
        }
        if (authInfo == null) {
            Log.w("$TAG No auth info with username & password found, cannot build calls-seen authorization")
            return null
        }
        val authUsername = authInfo.username
        val authPassword = authInfo.password
        if (authUsername.isNullOrEmpty() || authPassword.isNullOrEmpty()) {
            Log.w("$TAG Auth info for username [$authUsername] has no password, cannot build calls-seen authorization")
            return null
        }
        val credentials = Base64.encodeToString("$authUsername:$authPassword".toByteArray(), Base64.NO_WRAP)
        return "Basic $credentials"
    }

    /**
     * Performs the HTTP request and returns the response body on HTTP 200,
     * null otherwise (status + error body logged, e.g. the 409
     * {error:"no-location"} challenge). Must be called from a background
     * thread.
     */
    @WorkerThread
    private fun httpRequest(method: String, target: HttpTarget): String? {
        var connection: HttpURLConnection? = null
        try {
            connection = URL(target.url).openConnection() as HttpURLConnection
            connection.requestMethod = method
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Authorization", target.authorization)
            connection.setRequestProperty("Accept", "application/json")

            val statusCode = connection.responseCode
            if (statusCode != HttpURLConnection.HTTP_OK) {
                val errorBody = try {
                    connection.errorStream?.bufferedReader()?.use { it.readText() }
                } catch (e: IOException) {
                    null
                }
                Log.w(
                    "$TAG $method [${target.url}] failed with HTTP status [$statusCode]" +
                        (errorBody?.takeIf { it.isNotBlank() }?.let { ", body [$it]" } ?: "")
                )
                return null
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            Log.w("$TAG $method [${target.url}] failed: ${e.message}")
            return null
        } finally {
            connection?.disconnect()
        }
    }

    @AnyThread
    private fun preferences(): SharedPreferences {
        return coreContext.context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    }

    private class HttpTarget(val url: String, val authorization: String)
}
