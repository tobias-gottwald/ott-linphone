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
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
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
import org.json.JSONException
import org.json.JSONObject
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.core.AuthInfo
import org.linphone.core.Core
import org.linphone.core.GlobalState
import org.linphone.core.tools.Log
import org.linphone.utils.LinphoneUtils
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Shared per-location "calls seen" watermark.
 *
 * Every OTT location (Amparex branch) has a single monotonic timestamp stored
 * on the PBX sidecar ({base}/calls/seen, base derived from the [ott]
 * carddav_intern_url configuration value). Whenever any phone of the location
 * marks the calls as seen (opening the history list) or the dashboard does,
 * all devices are notified (FCM data push with reason "calls_seen") so the
 * call history highlighting, the missed calls counter and the missed call
 * notification stay in sync across devices.
 *
 * This object owns the device-local copy of that watermark:
 * - persisted in the [PREFERENCES_NAME] SharedPreferences (seenAt + locationId),
 * - observable through [seenAt] (unix milliseconds, 0 = nothing seen yet),
 * - only ever moving forward: [onWatermark] ignores equal or older values.
 *
 * When a core is running and none of its missed call logs is newer than the
 * watermark, the missed calls counter is reset and the missed call
 * notification dismissed (the calls have been seen somewhere else).
 */
object OttCallsSeen {
    private const val TAG = "[OTT Calls Seen]"

    private const val PREFERENCES_NAME = "ott_calls_seen"
    private const val PREFERENCE_SEEN_AT = "seenAt"
    private const val PREFERENCE_LOCATION_ID = "locationId"

    private const val CONFIG_SECTION = "ott"
    private const val CONFIG_INTERN_URL_KEY = "carddav_intern_url"

    private const val CARD_DAV_PATH_MARKER = "/carddav/"
    private const val CALLS_SEEN_PATH = "/calls/seen"

    private const val CONNECT_TIMEOUT_MS = 10000
    private const val READ_TIMEOUT_MS = 10000

    private val lock = Any()

    private var preferencesLoaded = false // Guarded by [lock]

    private var seenAtValue = 0L // Guarded by [lock]

    private var locationIdValue: String? = null // Guarded by [lock]

    private var missingConfigurationLogged = false

    /**
     * The shared calls-seen watermark in unix milliseconds (0 = nothing seen
     * yet). Observers are notified on the main thread whenever it advances;
     * the history list uses it to display not-yet-seen incoming calls in
     * bold.
     */
    val seenAt: MutableLiveData<Long> by lazy {
        MutableLiveData(currentSeenAt())
    }

    @AnyThread
    fun currentSeenAt(): Long = synchronized(lock) {
        if (!preferencesLoaded) {
            preferencesLoaded = true
            val preferences = preferences()
            seenAtValue = preferences.getLong(PREFERENCE_SEEN_AT, 0L)
            locationIdValue = preferences.getString(PREFERENCE_LOCATION_ID, null)
            Log.i(
                "$TAG Loaded persisted calls-seen watermark [$seenAtValue] for location [$locationIdValue]"
            )
        }
        seenAtValue
    }

    /**
     * Applies a (possibly stale) watermark value, typically received through
     * a FCM push or a server response. Only strictly newer values are
     * accepted; an accepted value is persisted, observers are notified and
     * the missed call indicators are re-evaluated. When no core is running
     * only the persistence happens (the server fetch at next core start
     * catches the indicators up).
     */
    @AnyThread
    fun onWatermark(locationId: String, newSeenAt: Long) {
        if (newSeenAt <= 0L) {
            Log.w("$TAG Ignoring invalid calls-seen watermark [$newSeenAt] for location [$locationId]")
            return
        }

        val advanced = synchronized(lock) {
            if (newSeenAt <= currentSeenAt()) {
                false
            } else {
                seenAtValue = newSeenAt
                locationIdValue = locationId
                preferences().edit()
                    .putLong(PREFERENCE_SEEN_AT, newSeenAt)
                    .putString(PREFERENCE_LOCATION_ID, locationId)
                    .apply()
                true
            }
        }

        if (!advanced) {
            Log.i(
                "$TAG Calls-seen watermark [$newSeenAt] for location [$locationId] is not newer than local value [${currentSeenAt()}], ignoring it"
            )
            return
        }

        Log.i("$TAG Calls-seen watermark advanced to [$newSeenAt] for location [$locationId]")
        seenAt.postValue(newSeenAt)
        maybeClearMissedCallIndicators()
    }

    /**
     * Fetches the server-side watermark (GET {base}/calls/seen) in the
     * background and applies it. Called at core start so a device that was
     * offline or without a running core when a calls-seen push arrived can
     * catch up (and clear its missed call indicators).
     */
    @AnyThread
    fun refreshFromServer() {
        Log.i("$TAG Refreshing calls-seen watermark from server")
        runOnHttpThread { target ->
            val body = httpRequest("GET", target) ?: return@runOnHttpThread
            try {
                val advanced = applyServerWatermark(JSONObject(body))
                if (!advanced) {
                    // Even without a local advance, a running core's missed
                    // call indicators may be covered by the already known
                    // watermark, for example when the push arrived while no
                    // core was running.
                    maybeClearMissedCallIndicators()
                }
            } catch (e: JSONException) {
                Log.w("$TAG Failed to parse calls-seen GET response [$body]: ${e.message}")
            }
        }
    }

    /**
     * Notifies the server that this location's calls have been seen (POST
     * {base}/calls/seen, no body) in the background and applies the effective
     * watermark returned by the server. Called when the history list is
     * opened; the applied watermark un-bolds the incoming rows older than it.
     */
    @AnyThread
    fun markCallsSeen() {
        Log.i("$TAG Notifying server that calls have been seen")
        runOnHttpThread { target ->
            val body = httpRequest("POST", target) ?: return@runOnHttpThread
            try {
                applyServerWatermark(JSONObject(body))
            } catch (e: JSONException) {
                Log.w("$TAG Failed to parse calls-seen POST response [$body]: ${e.message}")
            }
        }
    }

    /**
     * Applies the {locationId, seenAt} JSON object returned by the server and
     * returns whether the local watermark advanced. A null seenAt (no
     * watermark on the server yet) only logs.
     */
    @WorkerThread
    private fun applyServerWatermark(json: JSONObject): Boolean {
        val locationId = json.optString("locationId")
        if (json.isNull("seenAt")) {
            Log.i("$TAG Server has no calls-seen watermark yet for location [$locationId]")
            return false
        }
        val localSeenAt = currentSeenAt()
        onWatermark(locationId, json.optLong("seenAt", 0L))
        return currentSeenAt() != localSeenAt
    }

    /**
     * Resets the missed calls counter and dismisses the missed call
     * notification when a core is running and none of its missed call logs is
     * newer than the current watermark (they all have been seen, on another
     * device of the location or on the dashboard). No-op otherwise.
     */
    @AnyThread
    private fun maybeClearMissedCallIndicators() {
        if (!coreContext.isCoreAvailable()) {
            Log.i("$TAG Core not available, calls-seen watermark only persisted")
            return
        }
        coreContext.postOnCoreThread { core ->
            if (core.globalState != GlobalState.On) {
                Log.i("$TAG Core isn't running (state [${core.globalState}]), calls-seen watermark only persisted")
                return@postOnCoreThread
            }

            val watermark = currentSeenAt()
            val newestMissedCallStartDate = core.callLogs
                // Same definition as the notification trigger (oc-3acb):
                // Aborted/EarlyAborted missed calls clear together with
                // status==Missed ones.
                .filter { LinphoneUtils.isCallLogMissed(it) }
                .maxOfOrNull { it.startDate }
            if (newestMissedCallStartDate == null || newestMissedCallStartDate > watermark) {
                Log.i(
                    "$TAG Newest missed call startDate is [$newestMissedCallStartDate], calls-seen watermark is [$watermark], keeping missed calls indicators"
                )
                return@postOnCoreThread
            }

            Log.i(
                "$TAG Newest missed call startDate [$newestMissedCallStartDate] is covered by calls-seen watermark [$watermark], resetting missed calls count & dismissing notification"
            )
            core.resetMissedCallsCount()
            coreContext.notificationsManager.dismissMissedCallNotification()
        }
    }

    /**
     * Resolves the endpoint URL and the basic authorization header on the
     * core thread (both come from the core configuration and auth infos) then
     * runs [block] on a short-lived background thread so the caller is never
     * blocked. Silently gives up when the feature is disabled or credentials
     * are unavailable.
     */
    @AnyThread
    private fun runOnHttpThread(block: (HttpTarget) -> Unit) {
        if (!coreContext.isCoreAvailable()) {
            Log.w("$TAG Core not available, skipping calls-seen HTTP request")
            return
        }
        coreContext.postOnCoreThread { core ->
            val url = callsSeenUrl(core) ?: return@postOnCoreThread
            val authorization = basicAuthorization(core) ?: return@postOnCoreThread
            val target = HttpTarget(url, authorization)
            Thread({ block(target) }, "OTT Calls Seen HTTP").start()
        }
    }

    /**
     * Derives the calls-seen endpoint from the [ott] carddav_intern_url
     * configuration value by stripping the /carddav/<ext>/<scope> path
     * suffix: https://pbx.example.com:9443/carddav/42/intern becomes
     * https://pbx.example.com:9443/calls/seen. When the configuration value
     * is empty the feature is disabled (logged once per process only).
     */
    @WorkerThread
    private fun callsSeenUrl(core: Core): String? {
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
        return internUrl.substring(0, markerIndex) + CALLS_SEEN_PATH
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
     * null otherwise (logged). Must be called from a background thread.
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
                Log.w("$TAG $method [${target.url}] failed with HTTP status [$statusCode], giving up")
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
