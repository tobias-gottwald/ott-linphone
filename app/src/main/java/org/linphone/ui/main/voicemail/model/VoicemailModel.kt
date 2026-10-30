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
package org.linphone.ui.main.voicemail.model

import org.linphone.R
import org.linphone.ott.OttVoicemailClient
import org.linphone.utils.AppUtils
import org.linphone.utils.TimestampUtils

/**
 * Display model for one store voicemail row (oc-aa79). Wraps the immutable
 * VoicemailSummary DTO delivered by the sidecar; all strings are computed
 * once on the main thread (timestamps in the DTO are milliseconds).
 */
class VoicemailModel(val summary: OttVoicemailClient.VoicemailSummary) {

    val id: String = summary.id

    /**
     * callerName when the PBX knows one, otherwise the caller's number,
     * otherwise the generic unknown-caller label.
     */
    val displayName: String = summary.callerName?.takeIf { it.isNotEmpty() }
        ?: summary.callerNumber?.takeIf { it.isNotEmpty() }
        ?: AppUtils.getString(R.string.ott_voicemail_unknown_caller)

    /**
     * Localized "date | time" of the message, same format as the call
     * history rows (Today/Yesterday words included).
     */
    val dateTime: String = if (summary.timestamp > 0L) {
        val date = if (TimestampUtils.isToday(summary.timestamp, timestampInSecs = false)) {
            AppUtils.getString(R.string.today)
        } else if (TimestampUtils.isYesterday(summary.timestamp, timestampInSecs = false)) {
            AppUtils.getString(R.string.yesterday)
        } else {
            TimestampUtils.toString(
                summary.timestamp,
                onlyDate = true,
                timestampInSecs = false,
                shortDate = true,
                hideYear = true
            )
        }
        val time = TimestampUtils.timeToString(summary.timestamp, timestampInSecs = false)
        "$date | $time"
    } else {
        ""
    }

    /** Duration as mm:ss (or HH:mm:ss for long messages). */
    val formattedDuration: String = TimestampUtils.durationToString(
        (summary.durationMs / 1000L).toInt()
    )

    /** Whether the audio can still be played (not expired, not deleted). */
    val audioAvailable: Boolean = summary.audioAvailable

    /** Whether a transcript is expected but not finished yet. */
    val transcriptionPending: Boolean = summary.transcriptionStatus == OttVoicemailClient.TranscriptionStatus.PENDING ||
        summary.transcriptionStatus == OttVoicemailClient.TranscriptionStatus.IN_PROGRESS

    /** Whether transcription ended in a permanent failure. */
    val transcriptionFailed: Boolean = summary.transcriptionStatus == OttVoicemailClient.TranscriptionStatus.FAILED

}
