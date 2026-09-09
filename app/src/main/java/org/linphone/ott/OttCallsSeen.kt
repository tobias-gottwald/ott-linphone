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
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.annotation.AnyThread
import androidx.annotation.WorkerThread
import androidx.lifecycle.MutableLiveData
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.linphone.LinphoneApplication.Companion.coreContext
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
 * Shared per-location "unseen calls" state (oc-2532, per-call oc-28fd,
 * carddav identity + core-less refresh oc-bc3a).
 *
 * Every OTT location (Amparex branch) has a server-side set of call records
 * that no device has marked as seen yet, served by the PBX sidecar ({base}
 * derived from the [ott] carddav_intern_url configuration value):
 * - GET {base}/calls/unseen returns {locationId, unseen[], newestKnownStartAt},
 * - POST {base}/calls/seen marks exactly the listed OTT call ids as seen
 *   (the ids this device's call log actually showed the user) and returns
 *   {locationId, marked}.
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
 * Authentication uses the CardDAV identity from the [ott] configuration
 * section (carddav_username + carddav_password, i.e. `<ext>@carddav` and
 * the org-wide contacts secret) — NOT the SIP credentials: liblinphone
 * converts auth infos to HA1 on config write-back (store_ha1_passwd), so
 * the SIP password is unreadable after the first provisioning apply and can
 * never serve as HTTP Basic auth. The carddav entries are plain config
 * strings and stay cleartext; they are mirrored into the shared preferences
 * so calls-seen requests also work when the push arrived while no core was
 * running (the missed call notification is dismissed directly through the
 * NotificationManager in that case — cancelling needs no core).
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

    // Legacy oc-2532 watermark keys this preferences file used to carry;
    // removed once on first load (oc-bc3a).
    private const val LEGACY_PREFERENCE_SEEN_AT = "seenAt"
    private const val LEGACY_PREFERENCE_LOCATION_ID = "locationId"

    // Mirror of the last resolved sidecar endpoint + carddav identity, so
    // a calls-seen FCM push can be served without a running core.
    private const val PREFERENCE_MIRROR_BASE_URL = "mirror_base_url"
    private const val PREFERENCE_MIRROR_AUTHORIZATION = "mirror_authorization"

    private const val JSON_LOCATION_ID = "locationId"
    private const val JSON_UNSEEN = "unseen"
    private const val JSON_CALL_ID = "callId"
    private const val JSON_NEWEST_KNOWN_START_AT = "newestKnownStartAt"
    private const val JSON_CALL_IDS = "callIds"

    private const val CONFIG_SECTION = "ott"
    private const val CONFIG_INTERN_URL_KEY = "carddav_intern_url"
    private const val CONFIG_CARDDAV_USERNAME_KEY = "carddav_username"
    private const val CONFIG_CARDDAV_PASSWORD_KEY = "carddav_password"

    private const val CARD_DAV_PATH_MARKER = "/carddav/"
    private const val CALLS_UNSEEN_PATH = "/calls/unseen"
    private const val CALLS_SEEN_PATH = "/calls/seen"

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

    private val refreshSchedulerLock = Any()

    private var stateLoaded = false // Guarded by [lock]

    private var stateValue = CallsSeenState(null, emptySet(), 0L) // Guarded by [lock]

    private var missingConfigurationLogged = false

    /** Runs the delayed convergence refreshes (see [scheduleRefreshFromServer]). */
    private val refreshHandler = Handler(Looper.getMainLooper())

    private var convergenceRefreshPending = false // Guarded by [refreshSchedulerLock]

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
     * Schedules a delayed convergence refresh: a missed-call notification
     * was just posted while the CDR of that call may still be travelling to
     * the PBX (the ingest-lag heuristic intentionally counts such calls as
     * unseen). When the record lands, calls answered on another device of
     * the location are seen at ingestion and NEVER enter the unseen
     * summary — so no calls-seen push will ever fire for them. This
     * scheduled re-fetch is what converges the posted notification: it
     * re-evaluates the missed-call indicators against fresh server state
     * and dismisses the notification when every missed call turns out seen
     * (see [maybeClearMissedCallIndicators]). No-op while a refresh is
     * already pending: every state change re-evaluates the indicators
     * anyway, one in-flight convergence is enough.
     */
    @AnyThread
    fun scheduleRefreshFromServer(delayMs: Long) {
        synchronized(refreshSchedulerLock) {
            if (convergenceRefreshPending) return
            convergenceRefreshPending = true
        }
        refreshHandler.postDelayed({
            synchronized(refreshSchedulerLock) {
                convergenceRefreshPending = false
            }
            refreshFromServer()
        }, delayMs)
    }

    /**
     * Fetches the server-side unseen set (GET {base}/calls/unseen) in the
     * background and replaces the local state with it. Called at core start
     * and on calls-seen FCM pushes so a device that was offline (or without
     * a running core) catches up with what other devices or the dashboard
     * have marked as seen. Works without a running core through the
     * persisted endpoint mirror (no mirror yet — feature never configured
     * with a core — means the request is skipped).
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
     * Notifies the server that this device's user just looked at the calls
     * their call log shows (POST {base}/calls/seen with the exact OTT call
     * ids) in the background. Per-call, never a range: unseen calls this
     * device's log doesn't show stay unseen for the location. On the 200
     * response exactly the sent ids are removed from the local state (the
     * server stamps its own now) and the missed call indicators are
     * re-evaluated. Requires a running core — only it can enumerate the
     * device's call logs.
     */
    @AnyThread
    fun markCallsSeen() {
        if (!coreContext.isCoreAvailable()) {
            Log.w("$TAG Core not available, cannot compute the seen call ids")
            return
        }
        coreContext.postOnCoreThread { core ->
            val state = snapshotState()
            if (state.locationId == null) {
                Log.i("$TAG No server unseen state applied yet, nothing to mark as seen")
                return@postOnCoreThread
            }
            val seenIds = core.callLogs
                .filter { it.dir != Call.Dir.Outgoing }
                .mapNotNull { callLog -> ottIdFromCallId(callLog.callId) }
                .filter { state.unseenIds.contains(it) }
                .distinct()
            if (seenIds.isEmpty()) {
                Log.i("$TAG None of this device's calls is unseen, nothing to mark as seen")
                return@postOnCoreThread
            }
            val target = resolveHttpTarget(core, CALLS_SEEN_PATH) ?: return@postOnCoreThread
            Thread({ postSeenCallIds(target, seenIds, state) }, "OTT Calls Seen HTTP").start()
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
     * Whether the calls-seen feature is configured: the [ott]
     * carddav_intern_url value (sidecar base URL) AND the carddav
     * credentials ([ott] carddav_username/carddav_password — the identity
     * the sidecar's /calls routes authenticate, see class doc). When false
     * (unprovisioned stock setup, or anonymous dev mode without a contacts
     * secret) callers keep the stock core.missedCallsCount behavior. Must
     * be called from a thread on which the core can be accessed.
     */
    @WorkerThread
    fun isConfigured(): Boolean {
        if (!coreContext.isCoreAvailable()) {
            return false
        }
        val core = coreContext.core
        return ottBaseUrl(core) != null && carddavCredentials(core) != null
    }

    /**
     * Whether a server unseen state has been applied at least once
     * ([CallsSeenState.locationId] non-null). Distinct from
     * [isConfigured]: a configured device whose first fetch hasn't
     * completed yet is not ready, and callers must fall back to stock
     * counters instead of trusting an empty unseen set.
     */
    @AnyThread
    fun isReady(): Boolean {
        return snapshotState().locationId != null
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
    @AnyThread
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
     * POSTs the seen OTT call ids and removes exactly them from the local
     * state on the 200 response. Must be called from a background thread.
     */
    @WorkerThread
    private fun postSeenCallIds(target: HttpTarget, seenIds: List<String>, previous: CallsSeenState) {
        val body = JSONObject().put(JSON_CALL_IDS, JSONArray(seenIds)).toString()
        val response = httpRequest("POST", target, body) ?: return
        try {
            val marked = JSONObject(response).optInt("marked", 0)
            Log.i("$TAG Server marked [$marked] of [${seenIds.size}] reported call(s) as seen")
            val newState = CallsSeenState(
                previous.locationId,
                previous.unseenIds - seenIds.toSet(),
                previous.newestKnownStartAt
            )
            setState(newState)
            unseenStateChanged.postValue(newState.newestKnownStartAt)
            maybeClearMissedCallIndicators()
        } catch (e: JSONException) {
            Log.w("$TAG Failed to parse calls-seen POST response [$response]: ${e.message}")
        }
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
     * on another device of the location or on the dashboard). Without a
     * running core there are no call logs to check — an EMPTY unseen set
     * still proves every call seen, so the notification is dismissed
     * directly (cancelling needs no core); a non-empty set keeps it until
     * the next core start re-evaluates precisely.
     */
    @AnyThread
    private fun maybeClearMissedCallIndicators() {
        if (!coreContext.isCoreAvailable()) {
            maybeClearMissedCallIndicatorsWithoutCore()
            return
        }
        coreContext.postOnCoreThread { core ->
            if (core.globalState != GlobalState.On) {
                Log.i("$TAG Core isn't running (state [${core.globalState}]), unseen calls state only persisted")
                maybeClearMissedCallIndicatorsWithoutCore()
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

    /**
     * Core-less variant of [maybeClearMissedCallIndicators]: call logs are
     * unavailable, so only the empty-unseen-set case is decidable.
     */
    @AnyThread
    private fun maybeClearMissedCallIndicatorsWithoutCore() {
        val state = snapshotState()
        if (state.locationId == null) {
            return
        }
        if (state.unseenIds.isEmpty()) {
            Log.i("$TAG No core but every call has been seen, dismissing missed call notification")
            coreContext.notificationsManager.dismissMissedCallNotification()
        } else {
            Log.i("$TAG No core and [${state.unseenIds.size}] unseen call(s), keeping missed calls indicators")
        }
    }

    @AnyThread
    private fun snapshotState(): CallsSeenState = synchronized(lock) {
        if (!stateLoaded) {
            stateLoaded = true
            stateValue = loadStateFromPreferences(preferences())
            // One-time cleanup of the oc-2532 watermark format that used to
            // live in this file (oc-bc3a): the per-call state lives under
            // PREFERENCE_STATE and the stale keys only confuse readers.
            preferences().edit()
                .remove(LEGACY_PREFERENCE_SEEN_AT)
                .remove(LEGACY_PREFERENCE_LOCATION_ID)
                .apply()
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
     * Derives the full request target from the core configuration and
     * refreshes the core-less mirror. Must be called from the core thread.
     */
    @WorkerThread
    private fun resolveHttpTarget(core: Core, path: String): HttpTarget? {
        val baseUrl = ottBaseUrl(core) ?: return null
        val credentials = carddavCredentials(core) ?: return null
        preferences().edit()
            .putString(PREFERENCE_MIRROR_BASE_URL, baseUrl)
            .putString(PREFERENCE_MIRROR_AUTHORIZATION, credentials.authorization)
            .apply()
        return HttpTarget(baseUrl + path, credentials.authorization)
    }

    /**
     * Rebuilds the request target from the persisted mirror for core-less
     * operation. Null when no core-thread resolve ever persisted one.
     */
    @AnyThread
    private fun mirroredTarget(path: String): HttpTarget? {
        val prefs = preferences()
        val baseUrl = prefs.getString(PREFERENCE_MIRROR_BASE_URL, null) ?: return null
        val authorization = prefs.getString(PREFERENCE_MIRROR_AUTHORIZATION, null) ?: return null
        return HttpTarget(baseUrl + path, authorization)
    }

    /**
     * Resolves the PBX sidecar endpoint (base URL + carddav Basic identity)
     * on the core thread, refreshes the core-less mirror, then runs [block]
     * on a short-lived background thread so the caller is never blocked.
     * Without a core the persisted mirror serves the same purpose (an FCM
     * push may wake the process with no core running). Silently gives up
     * when the feature is disabled or the mirror is absent.
     */
    @AnyThread
    private fun runOnHttpThread(path: String, block: (HttpTarget) -> Unit) {
        if (coreContext.isCoreAvailable()) {
            coreContext.postOnCoreThread { core ->
                val target = resolveHttpTarget(core, path) ?: return@postOnCoreThread
                Thread({ block(target) }, "OTT Calls Seen HTTP").start()
            }
        } else {
            val target = mirroredTarget(path)
            if (target == null) {
                Log.w("$TAG Core not available and no endpoint mirror persisted, skipping calls-seen HTTP request")
                return
            }
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
     * The CardDAV identity for the calls-seen Basic auth: [ott]
     * carddav_username (the sidecar's `<ext>@carddav` login) and
     * carddav_password (the org-wide contacts secret). Read from the raw
     * config entries, never from an AuthInfo object — liblinphone
     * ha1-ifies auth infos on config write-back, config entries stay
     * cleartext. Anonymous dev setups (no carddav_password entry) disable
     * the feature. Must be called from the core thread.
     */
    @WorkerThread
    private fun carddavCredentials(core: Core): Credentials? {
        val username = core.config.getString(CONFIG_SECTION, CONFIG_CARDDAV_USERNAME_KEY, "").orEmpty().trim()
        val password = core.config.getString(CONFIG_SECTION, CONFIG_CARDDAV_PASSWORD_KEY, "").orEmpty().trim()
        if (username.isEmpty() || password.isEmpty()) {
            if (!missingConfigurationLogged) {
                missingConfigurationLogged = true
                Log.i(
                    "$TAG No [$CONFIG_SECTION] carddav credentials in configuration, calls-seen feature disabled"
                )
            }
            return null
        }
        return Credentials(username, basicAuthorization(username, password))
    }

    private fun basicAuthorization(username: String, password: String): String {
        return "Basic " + Base64.encodeToString("$username:$password".toByteArray(), Base64.NO_WRAP)
    }

    /**
     * Performs the HTTP request and returns the response body on HTTP 200,
     * null otherwise (status + error body logged, e.g. the 409
     * {error:"no-location"} challenge). Must be called from a background
     * thread.
     */
    @WorkerThread
    private fun httpRequest(method: String, target: HttpTarget, body: String? = null): String? {
        var connection: HttpURLConnection? = null
        try {
            connection = URL(target.url).openConnection() as HttpURLConnection
            connection.requestMethod = method
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Authorization", target.authorization)
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

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

    private class Credentials(val username: String, val authorization: String)

    private class HttpTarget(val url: String, val authorization: String)
}
