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
package org.linphone.ui.main.voicemail.viewmodel

import androidx.annotation.AnyThread
import androidx.annotation.UiThread
import androidx.lifecycle.MutableLiveData
import org.linphone.R
import org.linphone.core.tools.Log
import org.linphone.ott.OttPhoneServices
import org.linphone.ott.OttVoicemailClient
import org.linphone.ui.main.viewmodel.AbstractMainViewModel
import org.linphone.ui.main.voicemail.model.VoicemailModel
import org.linphone.utils.AppUtils

/**
 * ViewModel of the store voicemail list tab (oc-aa79).
 *
 * Data source is the PBX sidecar through [OttVoicemailClient] — the same
 * carddav credential convention as calls-seen (see [OttPhoneServices]).
 * The list:
 * - refreshes fully when the tab opens and on explicit refresh,
 * - paginates through nextCursor while the user scrolls,
 * - treats UNAUTHORIZED / NO_LOCATION / UNCONFIGURED as definitive: rows,
 *   pagination cursor and loaded state are all cleared and only the
 *   full-screen error with retry stays; transient failures keep the
 *   already loaded content and surface a red toast instead,
 * - belongs to the identity (carddav username) that was current when the
 *   request was dispatched: every response re-checks that identity and the
 *   request epoch, so a stale network callback can never publish another
 *   store's voicemails (identity change = [onIdentityChanged]: drop
 *   everything and start over).
 *
 * All HTTP work runs on short-lived background threads (the app has no
 * workmanager here; same convention as OttCallsSeen) and results are
 * published with postValue, so no caller is ever blocked.
 */
class VoicemailListViewModel : AbstractMainViewModel() {
    companion object {
        private const val TAG = "[Voicemail List ViewModel]"

        /** Matches the backend contract's default page size. */
        private const val PAGE_LIMIT = 30

        /** Rows before the end of the list at which the next page loads. */
        private const val LOAD_MORE_THRESHOLD = 8
    }

    val voicemails = MutableLiveData<List<VoicemailModel>>()

    /** A full (cursor-resetting) refresh is running. */
    val listInProgress = MutableLiveData<Boolean>()

    /** The next page is being appended. */
    val loadMoreInProgress = MutableLiveData<Boolean>()

    /** Last refresh failure, null while unknown/none. */
    val error = MutableLiveData<OttVoicemailClient.VoicemailError?>()

    /** At least one page was successfully loaded at some point. */
    val dataLoaded = MutableLiveData<Boolean>()

    /** Derived flags/labels for the data-binding layouts. */
    val emptyList = MutableLiveData<Boolean>()
    val errorNotEmptyList = MutableLiveData<Boolean>()
    val errorLabel = MutableLiveData<String>()

    private var nextCursor: String? = null

    /** Bumped on every full refresh/identity change; stale responses check it. */
    private var requestEpoch = 0L

    /** Identity (carddav username) the in-flight requests belong to. */
    private var currentIdentity: String? = null

    private var listCancellation: OttVoicemailClient.Cancellation? = null
    private var loadMoreCancellation: OttVoicemailClient.Cancellation? = null

    /** Unfiltered rows of all loaded pages; [voicemails] is the filtered view. */
    private var allVoicemails: List<VoicemailModel> = emptyList()

    init {
        voicemails.value = arrayListOf()
        listInProgress.value = false
        loadMoreInProgress.value = false
        error.value = null
        dataLoaded.value = false
        emptyList.value = false
        errorNotEmptyList.value = false
    }

    /** How many rows before the end trigger [loadMore]. */
    val loadMoreThreshold: Int = LOAD_MORE_THRESHOLD

    /**
     * Full refresh (tab opened or user hit refresh): cancels in-flight work,
     * resets the cursor and reloads the first page. Keeps the current rows
     * visible while it runs.
     */
    @AnyThread
    fun refresh() {
        val epoch = ++requestEpoch
        listCancellation?.cancel()
        loadMoreCancellation?.cancel()
        listCancellation = null
        loadMoreCancellation = null
        nextCursor = null
        listInProgress.postValue(true)

        OttPhoneServices.resolveTarget(OttVoicemailClient.VOICEMAILS_PATH) { target ->
            if (target == null) {
                if (epoch == requestEpoch) {
                    publishListFailure(OttVoicemailClient.VoicemailError.UNCONFIGURED)
                }
                return@resolveTarget
            }
            if (epoch != requestEpoch) {
                return@resolveTarget
            }
            currentIdentity = target.username
            val cancellation = OttVoicemailClient.Cancellation()
            listCancellation = cancellation
            Thread({
                val result = OttVoicemailClient.list(target, PAGE_LIMIT, null, cancellation)
                onListResult(epoch, target, result)
            }, "OTT Voicemail List HTTP").start()
        }
    }

    /**
     * Appends the next page when the user scrolls near the end of the list.
     * No-op without a next cursor or while a page is already loading.
     */
    @AnyThread
    fun loadMore() {
        val cursor = nextCursor ?: return
        if (loadMoreInProgress.value == true) return
        val epoch = requestEpoch
        loadMoreInProgress.postValue(true)

        OttPhoneServices.resolveTarget(OttVoicemailClient.VOICEMAILS_PATH) { target ->
            if (target == null || epoch != requestEpoch) {
                loadMoreInProgress.postValue(false)
                return@resolveTarget
            }
            val cancellation = OttVoicemailClient.Cancellation()
            loadMoreCancellation = cancellation
            Thread({
                val result = OttVoicemailClient.list(target, PAGE_LIMIT, cursor, cancellation)
                onLoadMoreResult(epoch, target, result)
            }, "OTT Voicemail Load More HTTP").start()
        }
    }

    /**
     * The provisioned identity changed (default account switched): drop the
     * whole list — a new store's voicemails must never be mixed with or
     * mistaken for the previous one — then reload from scratch.
     */
    @UiThread
    fun onIdentityChanged() {
        Log.i("$TAG Identity changed, dropping voicemail list content")
        requestEpoch++
        listCancellation?.cancel()
        loadMoreCancellation?.cancel()
        listCancellation = null
        loadMoreCancellation = null
        nextCursor = null
        currentIdentity = null
        voicemails.value = arrayListOf()
        allVoicemails = emptyList()
        dataLoaded.value = false
        error.value = null
        emptyList.value = false
        errorNotEmptyList.value = false
        refresh()
    }

    override fun onCleared() {
        requestEpoch++
        listCancellation?.cancel()
        loadMoreCancellation?.cancel()
        super.onCleared()
    }

    @AnyThread
    private fun onListResult(
        epoch: Long,
        target: OttPhoneServices.Target,
        result: OttVoicemailClient.Result
    ) {
        if (epoch != requestEpoch || target.username != currentIdentity) {
            Log.i("$TAG Dropping stale voicemail list response")
            return
        }
        when (result) {
            is OttVoicemailClient.Result.ListSuccess -> publishListSuccess(result.page)
            is OttVoicemailClient.Result.Failure -> publishListFailure(result.error)
            else -> publishListFailure(OttVoicemailClient.VoicemailError.PARSE)
        }
    }

    @AnyThread
    private fun onLoadMoreResult(
        epoch: Long,
        target: OttPhoneServices.Target,
        result: OttVoicemailClient.Result
    ) {
        if (epoch != requestEpoch || target.username != currentIdentity) {
            Log.i("$TAG Dropping stale voicemail load-more response")
            loadMoreInProgress.postValue(false)
            return
        }
        loadMoreInProgress.postValue(false)
        when (result) {
            is OttVoicemailClient.Result.ListSuccess -> {
                val page = result.page
                Log.i(
                    "$TAG Appended [${page.items.size}] voicemail(s), more: ${page.nextCursor != null}"
                )
                nextCursor = page.nextCursor
                val existingIds = allVoicemails.map { it.id }.toSet()
                val fresh = page.items.filter { it.id !in existingIds }.map { VoicemailModel(it) }
                publishRows(allVoicemails + fresh, null, dataLoaded.value == true)
            }
            is OttVoicemailClient.Result.Failure -> {
                Log.w("$TAG Loading more voicemails failed: [${result.error}]")
                if (isDefinitiveFailure(result.error)) {
                    // Auth/location state changed mid-session: the loaded
                    // content belongs to a store this phone may no longer
                    // represent — drop it all and show the full error.
                    publishListFailure(result.error)
                } else {
                    showRedToast(R.string.ott_voicemail_load_more_failed_toast, R.drawable.warning_circle)
                }
            }
            else -> {
                Log.w("$TAG Loading more voicemails failed: [$result]")
                showRedToast(R.string.ott_voicemail_load_more_failed_toast, R.drawable.warning_circle)
            }
        }
    }

    /**
     * Publishes a successful first page together with every derived state.
     * Called from background threads; all values are computed locally and
     * posted, never read back from the not-yet-delivered LiveData.
     */
    @AnyThread
    private fun publishListSuccess(page: OttVoicemailClient.VoicemailPage) {
        Log.i(
            "$TAG Loaded [${page.items.size}] voicemail(s) for location [${page.locationId}], more: ${page.nextCursor != null}"
        )
        nextCursor = page.nextCursor
        publishRows(page.items.map { VoicemailModel(it) }, null, true)
        listInProgress.postValue(false)
    }

    @AnyThread
    private fun publishListFailure(failure: OttVoicemailClient.VoicemailError) {
        Log.w("$TAG Loading voicemails failed: [$failure]")
        listInProgress.postValue(false)
        if (isDefinitiveFailure(failure)) {
            // UNAUTHORIZED / NO_LOCATION / UNCONFIGURED are definitive: no
            // voicemail of this state may stay on screen (wrong or revoked
            // credentials, unassigned or unknown device). Clear the rows,
            // the pagination cursor and the loaded marker; only the
            // full-screen error with retry remains.
            nextCursor = null
            publishRows(emptyList(), failure, false)
        } else {
            // Transient failure: keep the known content, only tell the
            // user the refresh failed.
            val hasContent = allVoicemails.isNotEmpty()
            publishRows(allVoicemails, failure, dataLoaded.value == true)
            if (hasContent) {
                showRedToast(R.string.ott_voicemail_refresh_failed_toast, R.drawable.warning_circle)
            }
        }
    }

    private fun isDefinitiveFailure(failure: OttVoicemailClient.VoicemailError): Boolean {
        return failure == OttVoicemailClient.VoicemailError.UNAUTHORIZED ||
            failure == OttVoicemailClient.VoicemailError.NO_LOCATION ||
            failure == OttVoicemailClient.VoicemailError.UNCONFIGURED
    }

    /**
     * Single place computing every list-state LiveData from the given
     * values, so the layout's derived flags can never disagree.
     */
    @AnyThread
    private fun publishRows(
        rows: List<VoicemailModel>,
        failure: OttVoicemailClient.VoicemailError?,
        loaded: Boolean
    ) {
        allVoicemails = rows
        voicemails.postValue(rows)
        error.postValue(failure)
        dataLoaded.postValue(loaded)
        emptyList.postValue(rows.isEmpty() && failure == null && loaded)
        errorNotEmptyList.postValue(rows.isEmpty() && failure != null)
        errorLabel.postValue(
            when (failure) {
                OttVoicemailClient.VoicemailError.UNAUTHORIZED ->
                    AppUtils.getString(R.string.ott_voicemail_error_unauthorized)
                OttVoicemailClient.VoicemailError.NO_LOCATION ->
                    AppUtils.getString(R.string.ott_voicemail_error_no_location)
                OttVoicemailClient.VoicemailError.SERVER ->
                    AppUtils.getString(R.string.ott_voicemail_error_server)
                OttVoicemailClient.VoicemailError.NETWORK ->
                    AppUtils.getString(R.string.ott_voicemail_error_network)
                else -> AppUtils.getString(R.string.ott_voicemail_error_generic)
            }
        )
    }
}
