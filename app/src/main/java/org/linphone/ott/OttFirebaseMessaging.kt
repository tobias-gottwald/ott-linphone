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

import com.google.firebase.messaging.RemoteMessage
import org.linphone.core.tools.Log
import org.linphone.core.tools.firebase.FirebaseMessaging

/**
 * OTT flavor of the SDK's Firebase messaging service: handles the shared
 * calls-seen watermark pushes (data messages with reason "calls_seen") and
 * delegates everything else (call pushes) to the SDK implementation exactly
 * as if this class didn't exist.
 *
 * Replaces org.linphone.core.tools.firebase.FirebaseMessaging in the
 * manifest; see [OttCallsSeen] for the watermark itself.
 */
class OttFirebaseMessaging : FirebaseMessaging() {

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        val data = remoteMessage.data
        if (data[KEY_REASON] == REASON_CALLS_SEEN) {
            val locationId = data[KEY_LOCATION_ID].orEmpty()
            val seenAt = data[KEY_SEEN_AT]?.toLongOrNull() ?: 0L
            Log.i("$TAG Received calls-seen push for location [$locationId] with seenAt [$seenAt]")
            if (locationId.isNotEmpty() && seenAt > 0L) {
                OttCallsSeen.onWatermark(locationId, seenAt)
            } else {
                Log.w("$TAG Calls-seen push is malformed, ignoring it")
            }
            return
        }
        super.onMessageReceived(remoteMessage)
    }

    companion object {
        private const val TAG = "[OTT Firebase Messaging]"

        private const val KEY_REASON = "reason"
        private const val KEY_LOCATION_ID = "locationId"
        private const val KEY_SEEN_AT = "seenAt"
        private const val REASON_CALLS_SEEN = "calls_seen"
    }
}
