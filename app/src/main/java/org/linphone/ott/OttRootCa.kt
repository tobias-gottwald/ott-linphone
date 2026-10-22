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
import androidx.annotation.WorkerThread
import java.io.File
import org.linphone.core.Core
import org.linphone.core.tools.Log

/**
 * Pins liblinphone's TLS trust anchors to the app-bundled [ASSET_NAME]
 * (ISRG Root X1 + X2, the Let's Encrypt roots).
 *
 * liblinphone does not use the Android system trust store: by default it
 * verifies SIP TLS, remote provisioning and CardDAV against the static
 * Mozilla-derived `rootca.pem` shipped inside the linphone SDK (copied to
 * `<filesDir>/share/linphone/rootca.pem`), with no revocation or CT checks.
 * That bundle still contains roots that validate the eight compromised
 * eMudhra intermediate CAs (October 2026), so a network attacker on the
 * branch LAN (before the WireGuard FritzBox tunnel) could impersonate
 * sip-internal/sip-deploy.otthoeren.de with a forged certificate.
 *
 * The pin replaces the SDK bundle entirely: only certificates chaining to
 * the bundled anchors are accepted. Must run after core creation and
 * BEFORE `core.start()` so the very first provisioning fetch is covered.
 *
 * Precedence: an existing non-default `core.rootCa` (MDM managed-config
 * `rootCa` restriction or a server-side `[sip] root_ca` from the
 * provisioning XML, both persisted in the config) wins and is never
 * overridden; only the SDK's own bundle path is replaced.
 */
object OttRootCa {
    private const val TAG = "[OTT Root CA]"

    const val ASSET_NAME = "ott_root_ca.pem"

    /** Path suffix of the SDK's own bundle once copied out of the APK assets. */
    private const val SDK_BUNDLE_SUFFIX = "share/linphone/rootca.pem"

    @WorkerThread
    fun apply(context: Context, core: Core) {
        val pem = try {
            context.assets.open(ASSET_NAME).use { it.readBytes() }
        } catch (e: Exception) {
            Log.e("$TAG Failed to read bundled CA asset $ASSET_NAME: $e")
            return // fail open: keep the SDK bundle rather than break TLS
        }
        if (!pem.decodeToString().contains("BEGIN CERTIFICATE")) {
            Log.e("$TAG Bundled CA asset $ASSET_NAME contains no certificate")
            return
        }

        val target = File(context.filesDir, ASSET_NAME)
        if (!target.exists() || !target.readBytes().contentEquals(pem)) {
            target.writeBytes(pem)
            Log.i("$TAG Installed pinned CA bundle to ${target.absolutePath}")
        }

        val current = core.rootCa
        val pinPath = target.absolutePath
        when {
            current == pinPath -> Log.i("$TAG Pinned CA bundle already active")
            current.isNullOrBlank() || current.endsWith(SDK_BUNDLE_SUFFIX) -> {
                core.rootCa = pinPath
                Log.i("$TAG Pinned liblinphone TLS trust to $pinPath")
            }
            else -> Log.i("$TAG Keeping non-default root CA [$current] (MDM/provisioning override)")
        }
    }
}
