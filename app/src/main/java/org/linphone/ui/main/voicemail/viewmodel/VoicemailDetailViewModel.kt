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

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import androidx.annotation.AnyThread
import androidx.annotation.UiThread
import androidx.lifecycle.MutableLiveData
import androidx.media.AudioFocusRequestCompat
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.R
import org.linphone.core.Call
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.tools.Log
import org.linphone.ott.OttPhoneServices
import org.linphone.ott.OttVoicemailClient
import org.linphone.ui.GenericViewModel
import org.linphone.ui.main.voicemail.model.VoicemailModel
import org.linphone.utils.AppUtils
import org.linphone.utils.AudioUtils
import org.linphone.utils.Event
import org.linphone.utils.LinphoneUtils
import org.linphone.utils.TimestampUtils

/**
 * ViewModel of one store voicemail (oc-aa79): the detail is fetched from the
 * sidecar (GET /voicemails/:id, same carddav credential convention as
 * calls-seen) and the audio is played with Android's [MediaPlayer]
 * DIRECTLY from the storage URL the backend returned — never from the
 * sidecar and never with the phone's Basic credential attached (the sidecar
 * GET refuses redirects for the same reason).
 *
 * The existing call-recording player is liblinphone's local-file Player and
 * cannot open HTTPS URLs; only the UX conventions are reused: audio focus
 * through [AudioUtils], a position ticker and pause on lifecycle events.
 *
 * Playback pauses/releases on: leaving the detail (fragment onPause /
 * viewModel onCleared), any SIP call activity (ringing, outgoing or
 * connected — a call that ALREADY exists also blocks starting/seeking, so
 * the user can never miss a call because a voicemail is playing), playback
 * errors, and identity changes (a stale request can never publish another
 * store's voicemail: epoch + identity checks on every response).
 * A definitive failure (revoked credentials, device without location,
 * missing record, unconfigured) withdraws the previously published detail,
 * transcript and audio URL — nothing of the old store stays playable.
 */
class VoicemailDetailViewModel : GenericViewModel() {
    companion object {
        private const val TAG = "[Voicemail Detail ViewModel]"

        /** Position ticker period while playing. */
        private const val POSITION_UPDATE_INTERVAL_MS = 500L
    }

    // Detail state

    val detailInProgress = MutableLiveData<Boolean>()

    /** Last detail failure, null while loading/success. */
    val detailError = MutableLiveData<OttVoicemailClient.VoicemailError?>()

    val errorLabel = MutableLiveData<String?>()

    val detailReady = MutableLiveData<Boolean>()

    val displayName = MutableLiveData<String>()

    val dateTime = MutableLiveData<String>()

    val audioAvailable = MutableLiveData<Boolean>()

    val transcriptVisible = MutableLiveData<Boolean>()

    val transcriptPending = MutableLiveData<Boolean>()

    val transcriptFailed = MutableLiveData<Boolean>()

    val transcriptDisplay = MutableLiveData<String>()

    /** Fires once per successfully loaded detail (opens the sliding pane). */
    val voicemailLoadedEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData()
    }

    // Playback state

    val isPlaying = MutableLiveData<Boolean>()

    val position = MutableLiveData<Int>()

    val duration = MutableLiveData<Int>()

    val formattedPosition = MutableLiveData<String>()

    val formattedDuration = MutableLiveData<String>()

    private var voicemailId: String? = null

    private var audioUrl: String? = null

    private var requestEpoch = 0L

    private var currentIdentity: String? = null

    private var cancellation: OttVoicemailClient.Cancellation? = null

    private var player: MediaPlayer? = null

    private var playerPrepared = false

    /** Play as soon as the (async) prepare finishes. */
    private var playWhenPrepared = false
    /** Seek requested while the player was absent or still preparing. */
    private var pendingSeekPositionMs: Int? = null

    /** Between seekTo() and onSeekComplete: the position ticker stays quiet. */
    private var seekInFlight = false

    /**
     * Whether at least one SIP call currently exists (ringing, outgoing,
     * connected or paused). Written from the core thread only, read on the
     * main thread — the core itself is never accessed from another thread.
     */
    @Volatile
    private var sipCallActivity = false

    private var audioFocusRequest: AudioFocusRequestCompat? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private val positionUpdater = object : Runnable {
        override fun run() {
            val mp = player
            if (mp != null && mp.isPlaying) {
                // While a seek is still in flight the player would report its
                // old position — never claim progress it does not have.
                if (!seekInFlight) {
                    publishPosition(mp.currentPosition)
                }
                mainHandler.postDelayed(this, POSITION_UPDATE_INTERVAL_MS)
            }
        }
    }

    /**
     * Any SIP call activity (ringing, outgoing, connected, paused) wins the
     * audio path over voicemail playback.
     */
    private val coreListener = object : CoreListenerStub() {
        @AnyThread
        override fun onCallStateChanged(core: Core, call: Call, state: Call.State?, message: String) {
            if (state == null) return
            // Computed where the core is accessible; the main thread only
            // consumes the result and never touches the core itself.
            val callActivity = hasCallActivity(core)
            sipCallActivity = callActivity
            coreContext.postOnMainThread {
                onSipCallActivity(callActivity)
            }
        }
    }

    /**
     * Whether at least one call still needs the audio path: ringing,
     * outgoing, connected or paused — anything but a terminating call.
     * MUST only be called with core access (core listener / core thread).
     */
    private fun hasCallActivity(core: Core): Boolean {
        return core.calls.any { !LinphoneUtils.isCallEnding(it.state, considerReleasedAsEnding = true) }
    }

    /**
     * A SIP call appeared (or the call list changed while one exists): any
     * pending start/seek is dropped and ongoing playback pauses with the
     * audio focus released — even if the player was still preparing, so a
     * late onPrepared can never start audio over a call. When the last call
     * is gone nothing auto-resumes; the user plays again manually.
     */
    @UiThread
    private fun onSipCallActivity(callActivity: Boolean) {
        if (!callActivity) return
        if (player == null && !playWhenPrepared && pendingSeekPositionMs == null) return
        Log.i("$TAG SIP call activity, pausing voicemail playback and dropping pending play/seek")
        pausePlayback()
        pendingSeekPositionMs = null
        seekInFlight = false
    }

    init {
        detailInProgress.value = true
        detailError.value = null
        detailReady.value = false
        displayName.value = ""
        dateTime.value = ""
        audioAvailable.value = false
        transcriptVisible.value = false
        transcriptPending.value = false
        transcriptFailed.value = false
        transcriptDisplay.value = ""
        isPlaying.value = false
        position.value = 0
        duration.value = 0
        formattedPosition.value = TimestampUtils.durationToString(0)
        formattedDuration.value = TimestampUtils.durationToString(0)

        coreContext.postOnCoreThread { core ->
            // Covers "detail opened while a call is already up": the listener
            // alone would only fire on the NEXT call state change.
            sipCallActivity = hasCallActivity(core)
            core.addListener(coreListener)
        }
    }

    /**
     * Loads the voicemail [id]: fetches the detail and prepares everything
     * for display. Called with the nav argument and by the retry/refresh
     * actions (a pending transcript can be refreshed manually).
     */
    @UiThread
    fun load(id: String) {
        if (voicemailId == id && detailInProgress.value != true && detailError.value == null) {
            Log.i("$TAG Voicemail [$id] already loaded, nothing to do")
            return
        }
        voicemailId = id
        refreshDetail()
    }

    /**
     * (Re-)fetches the detail of the current voicemail. Cancels an in-flight
     * request first; playback of the old audio stops (the state may have
     * changed server-side, e.g. the audio expired or the transcript landed).
     */
    @AnyThread
    fun refreshDetail() {
        val id = voicemailId
        if (id == null) {
            Log.w("$TAG No voicemail id to refresh")
            return
        }
        val epoch = ++requestEpoch
        cancellation?.cancel()
        cancellation = null
        stopPlayback(resetPosition = true)
        // Errors are transient UI state: null while loading/success.
        detailError.postValue(null)
        errorLabel.postValue(null)
        detailInProgress.postValue(true)

        OttPhoneServices.resolveTarget(OttVoicemailClient.VOICEMAILS_PATH) { target ->
            if (target == null) {
                if (epoch == requestEpoch) {
                    publishDetailFailure(epoch, OttVoicemailClient.VoicemailError.UNCONFIGURED)
                }
                return@resolveTarget
            }
            if (epoch != requestEpoch) {
                return@resolveTarget
            }
            currentIdentity = target.username
            val requestCancellation = OttVoicemailClient.Cancellation()
            cancellation = requestCancellation
            Thread({
                val result = OttVoicemailClient.detail(target, id, requestCancellation)
                onDetailResult(epoch, target, result)
            }, "OTT Voicemail Detail HTTP").start()
        }
    }

    @AnyThread
    private fun onDetailResult(
        epoch: Long,
        target: OttPhoneServices.Target,
        result: OttVoicemailClient.Result
    ) {
        if (epoch != requestEpoch || target.username != currentIdentity) {
            Log.i("$TAG Dropping stale voicemail detail response")
            return
        }
        when (result) {
            is OttVoicemailClient.Result.DetailSuccess -> publishDetailSuccess(result.detail)
            is OttVoicemailClient.Result.Failure -> publishDetailFailure(epoch, result.error)
            else -> publishDetailFailure(epoch, OttVoicemailClient.VoicemailError.PARSE)
        }
    }

    @AnyThread
    private fun publishDetailSuccess(detail: OttVoicemailClient.VoicemailDetail) {
        Log.i("$TAG Voicemail [${detail.voicemail.id}] loaded, audio ${if (detail.voicemail.audioAvailable) "available" else "NOT available"}")
        val model = VoicemailModel(detail.voicemail)
        audioUrl = if (detail.voicemail.audioAvailable) detail.audioUrl else null
        detailInProgress.postValue(false)
        detailError.postValue(null)
        errorLabel.postValue(null)
        detailReady.postValue(true)
        displayName.postValue(model.displayName)
        dateTime.postValue(model.dateTime)
        audioAvailable.postValue(model.audioAvailable)
        transcriptVisible.postValue(
            model.transcriptionPending || model.transcriptionFailed ||
                (
                    detail.voicemail.transcriptionStatus == OttVoicemailClient.TranscriptionStatus.COMPLETED &&
                        !detail.voicemail.transcriptText.isNullOrEmpty()
                    )
        )
        transcriptPending.postValue(model.transcriptionPending)
        transcriptFailed.postValue(model.transcriptionFailed)
        transcriptDisplay.postValue(
            when {
                model.transcriptionPending -> AppUtils.getString(R.string.ott_voicemail_transcript_pending)
                model.transcriptionFailed -> AppUtils.getString(R.string.ott_voicemail_transcript_failed)
                else -> detail.voicemail.transcriptText.orEmpty()
            }
        )
        duration.postValue((detail.voicemail.durationMs).toInt())
        formattedDuration.postValue(model.formattedDuration)
        publishPosition(0)
        voicemailLoadedEvent.postValue(Event(true))
    }

    @AnyThread
    private fun publishDetailFailure(epoch: Long, failure: OttVoicemailClient.VoicemailError) {
        if (epoch != requestEpoch) {
            return
        }
        Log.w("$TAG Loading voicemail detail failed: [$failure]")
        detailInProgress.postValue(false)
        detailError.postValue(failure)
        errorLabel.postValue(
            when (failure) {
                OttVoicemailClient.VoicemailError.UNCONFIGURED ->
                    AppUtils.getString(R.string.ott_voicemail_error_generic)
                OttVoicemailClient.VoicemailError.UNAUTHORIZED ->
                    AppUtils.getString(R.string.ott_voicemail_error_unauthorized)
                OttVoicemailClient.VoicemailError.NO_LOCATION ->
                    AppUtils.getString(R.string.ott_voicemail_error_no_location)
                OttVoicemailClient.VoicemailError.NETWORK ->
                    AppUtils.getString(R.string.ott_voicemail_error_network)
                // 404 and every other server answer is the same public error.
                else -> AppUtils.getString(R.string.ott_voicemail_detail_not_found)
            }
        )
        when (failure) {
            // This device can no longer access the store's voicemails:
            // withdraw everything already published so no old-store detail,
            // transcript or audio URL survives the failure.
            OttVoicemailClient.VoicemailError.UNCONFIGURED,
            OttVoicemailClient.VoicemailError.UNAUTHORIZED,
            OttVoicemailClient.VoicemailError.NO_LOCATION,
            OttVoicemailClient.VoicemailError.SERVER -> clearWithdrawnDetail()
            // NETWORK/PARSE are transient: keep the last detail displayed,
            // the user can retry with the refresh/retry action.
            else -> Unit
        }
    }

    /**
     * Clears every published trace of the previously loaded voicemail — an
     * UNAUTHORIZED/NO_LOCATION/SERVER/UNCONFIGURED refresh means the old
     * store must not stay readable or playable. Runs on the HTTP thread:
     * LiveDatas via postValue, the player teardown hops to the main thread
     * (audioUrl is dropped synchronously, so no play request can win the
     * hop).
     */
    @AnyThread
    private fun clearWithdrawnDetail() {
        audioUrl = null
        detailReady.postValue(false)
        displayName.postValue("")
        dateTime.postValue("")
        audioAvailable.postValue(false)
        transcriptVisible.postValue(false)
        transcriptPending.postValue(false)
        transcriptFailed.postValue(false)
        transcriptDisplay.postValue("")
        duration.postValue(0)
        formattedDuration.postValue(TimestampUtils.durationToString(0))
        publishPosition(0)
        mainHandler.post {
            stopPlayback(resetPosition = true)
        }
    }

    // ---------------------------------------------------------------------
    // Playback (android.media.MediaPlayer, main thread only)
    // ---------------------------------------------------------------------

    /**
     * Play/pause toggle for the detail's play button. Without audio the call
     * is ignored (the UI disables the button anyway).
     */
    @UiThread
    fun togglePlayPause() {
        if (audioAvailable.value != true) {
            Log.w("$TAG No audio available for this voicemail, ignoring play request")
            return
        }
        if (isPlaying.value == true) {
            pausePlayback()
        } else {
            startPlayback()
        }
    }

    @UiThread
    fun startPlayback() {
        if (sipCallActivity) {
            Log.w("$TAG A SIP call is active, refusing to start voicemail playback")
            playWhenPrepared = false
            return
        }
        if (!ensurePlayerPrepared(startWhenReady = true)) {
            return
        }
        val mp = player ?: return
        if (!playerPrepared) {
            // prepareAsync is running; startPlayback resumes in onPrepared.
            playWhenPrepared = true
            return
        }
        Log.i("$TAG Starting voicemail playback")
        acquireAudioFocus()
        pendingSeekPositionMs?.let { requested ->
            // A seek asked for before the player existed: the real duration
            // is known now, clamp and apply instead of restarting at 0.
            pendingSeekPositionMs = null
            val clamped = if (mp.duration > 0) requested.coerceIn(0, mp.duration) else requested
            Log.i("$TAG Applying deferred seek to [$clamped] ms before starting")
            seekInFlight = true
            mp.seekTo(clamped)
            publishPosition(clamped)
        }
        mp.start()
        isPlaying.value = true
        mainHandler.removeCallbacks(positionUpdater)
        positionUpdater.run()
    }

    @UiThread
    fun pausePlayback() {
        val mp = player ?: return
        playWhenPrepared = false
        if (mp.isPlaying) {
            Log.i("$TAG Pausing voicemail playback")
            mp.pause()
        }
        isPlaying.value = false
        releaseAudioFocus()
        mainHandler.removeCallbacks(positionUpdater)
    }

    /**
     * Seek (dragged seekbar): pauses while dragging is done by the listener,
     * then seeks and resumes playing like the call recording player does.
     * With no player yet (or while it is still preparing) the request is
     * kept and applied — clamped to the real duration — once the player is
     * prepared, instead of silently restarting from 0.
     */
    @UiThread
    fun seekTo(newPositionMs: Int) {
        if (audioAvailable.value != true) {
            return
        }
        if (sipCallActivity) {
            Log.w("$TAG A SIP call is active, ignoring voicemail seek request")
            return
        }
        val mp = player
        if (mp != null && playerPrepared) {
            val clamped = if (mp.duration > 0) newPositionMs.coerceIn(0, mp.duration) else newPositionMs
            Log.i("$TAG Seeking voicemail playback to [$clamped] ms")
            seekInFlight = true
            mp.seekTo(clamped)
            publishPosition(clamped)
        } else {
            Log.i("$TAG No prepared player yet, deferring seek to [$newPositionMs] ms")
            pendingSeekPositionMs = newPositionMs
            publishPosition(newPositionMs)
        }
        startPlayback()
    }

    /**
     * Lazily creates the MediaPlayer for the storage audio URL. Returns
     * false when there is nothing to play or creation failed. The request
     * carries NO credential — plain Android trust on the URL the sidecar
     * returned.
     */
    @UiThread
    private fun ensurePlayerPrepared(startWhenReady: Boolean): Boolean {
        player?.let {
            return true
        }
        val url = audioUrl ?: return false
        return try {
            Log.i("$TAG Creating media player for storage URL (no credentials attached)")
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(url)
            mp.setOnPreparedListener {
                playerPrepared = true
                duration.postValue(it.duration)
                formattedDuration.postValue(TimestampUtils.durationToString(it.duration / 1000))
                if (playWhenPrepared) {
                    startPlayback()
                } else {
                    pendingSeekPositionMs?.let { requested ->
                        // Stage a deferred seek even without auto-play so the
                        // position shown matches what the player will do.
                        val clamped = if (it.duration > 0) requested.coerceIn(0, it.duration) else requested
                        pendingSeekPositionMs = null
                        seekInFlight = true
                        it.seekTo(clamped)
                        publishPosition(clamped)
                    }
                }
            }
            mp.setOnSeekCompleteListener { seekPlayer ->
                // The real position once the seek landed: the ticker stayed
                // quiet until now, so the UI never claimed progress the
                // player did not actually have.
                seekInFlight = false
                publishPosition(seekPlayer.currentPosition)
            }
            mp.setOnCompletionListener {
                Log.i("$TAG Voicemail playback reached the end")
                stopPlayback(resetPosition = true)
            }
            mp.setOnErrorListener { _, what, extra ->
                Log.e("$TAG Media player error what [$what] extra [$extra]")
                coreContext.postOnMainThread {
                    stopPlayback(resetPosition = false)
                    showRedToast(R.string.ott_voicemail_playback_failed_toast, R.drawable.warning_circle)
                }
                true
            }
            mp.prepareAsync()
            player = mp
            playWhenPrepared = startWhenReady
            true
        } catch (e: Exception) {
            Log.e("$TAG Failed to create media player for voicemail audio: ${e.message}")
            showRedToast(R.string.ott_voicemail_playback_failed_toast, R.drawable.warning_circle)
            false
        }
    }

    /**
     * Stops and releases the player; position resets when asked (leaving the
     * detail) and stays when only the audio failed.
     */
    @UiThread
    private fun stopPlayback(resetPosition: Boolean) {
        mainHandler.removeCallbacks(positionUpdater)
        playWhenPrepared = false
        playerPrepared = false
        pendingSeekPositionMs = null
        seekInFlight = false
        isPlaying.value = false
        releaseAudioFocus()
        player?.let { mp ->
            try {
                mp.stop()
            } catch (e: IllegalStateException) {
                // Player wasn't started yet — nothing to stop.
            }
            mp.release()
        }
        player = null
        if (resetPosition) {
            publishPosition(0)
        }
    }

    @UiThread
    private fun acquireAudioFocus() {
        if (audioFocusRequest == null) {
            audioFocusRequest = AudioUtils.acquireAudioFocusForVoiceRecordingOrPlayback(
                coreContext.context
            )
        }
    }

    @UiThread
    private fun releaseAudioFocus() {
        audioFocusRequest?.let {
            AudioUtils.releaseAudioFocusForVoiceRecordingOrPlayback(coreContext.context, it)
        }
        audioFocusRequest = null
    }

    @AnyThread
    private fun publishPosition(positionMs: Int) {
        position.postValue(positionMs)
        formattedPosition.postValue(TimestampUtils.durationToString(positionMs / 1000))
    }

    override fun onCleared() {
        requestEpoch++
        cancellation?.cancel()
        coreContext.postOnCoreThread { core ->
            core.removeListener(coreListener)
        }
        stopPlayback(resetPosition = true)
        super.onCleared()
    }
}
