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

import android.util.Base64
import androidx.annotation.AnyThread
import androidx.annotation.WorkerThread
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.core.Core
import org.linphone.core.tools.Log

/**
 * Shared derivation of the PBX sidecar endpoint and its HTTP Basic identity
 * from the raw [ott] configuration section (extracted from
 * [OttCallsSeen], oc-aa79, so the voicemail client and the calls-seen
 * feature share ONE credential convention — never a second one).
 *
 * - Sidecar origin: the [ott] carddav_intern_url value with the
 *   /carddav/<ext>/<scope> path suffix stripped
 *   (https://pbx.example.com:9443/carddav/42/intern -> https://pbx.example.com:9443).
 * - Identity: [ott] carddav_username (the device-unique `<ext>-<hex>` login
 *   minted at provisioning time) + carddav_password (its per-device secret),
 *   both read from the RAW config entries. Never from an AuthInfo object:
 *   liblinphone converts auth infos to HA1 on config write-back
 *   (store_ha1_passwd), so the SIP password is unreadable after the first
 *   provisioning apply; the carddav entries are plain config strings and stay
 *   cleartext.
 * - Anonymous dev setups (empty values) disable the sidecar features.
 *
 * [OttCallsSeen] keeps its OWN core-less endpoint mirror in its preferences
 * (for FCM pushes without a running core); this helper deliberately has no
 * persistence of its own. UI-scoped consumers (voicemail) always run with a
 * started core and resolve through [resolveTarget] instead.
 */
object OttPhoneServices {
    private const val TAG = "[OTT Phone Services]"

    const val CONFIG_SECTION = "ott"
    const val CONFIG_INTERN_URL_KEY = "carddav_intern_url"
    const val CONFIG_CARDDAV_USERNAME_KEY = "carddav_username"
    const val CONFIG_CARDDAV_PASSWORD_KEY = "carddav_password"

    const val CARD_DAV_PATH_MARKER = "/carddav/"

    private var missingConfigurationLogged = false // Guarded by the calling core thread only

    /**
     * A fully resolved sidecar request target: absolute URL of one route and
     * the Basic Authorization header value to send. [username] is kept so
     * callers can detect an identity change between request dispatch and
     * response (a stale callback must never publish another store's data).
     */
    class Target(val url: String, val authorization: String, val username: String)

    /**
     * The CardDAV identity as a ready-to-use Basic Authorization header value.
     */
    class Credentials(val username: String, val authorization: String)

    /**
     * Resolves the sidecar target for [path] on the core thread and reports
     * it (or null when the feature isn't configured) back on the SAME core
     * thread. Background HTTP work must be started by the callback, so the
     * caller is never blocked. Without a running core the answer is always
     * null (voicemail is UI-only and requires a started core anyway).
     */
    @AnyThread
    fun resolveTarget(path: String, onResolved: (Target?) -> Unit) {
        if (!coreContext.isCoreAvailable()) {
            Log.w("$TAG Core not available, cannot resolve phone services target")
            onResolved(null)
            return
        }
        coreContext.postOnCoreThread { core ->
            onResolved(buildTarget(core, path))
        }
    }

    /**
     * Builds the request target for [path] from the core configuration.
     * Must be called from the core thread.
     */
    @WorkerThread
    fun buildTarget(core: Core, path: String): Target? {
        val baseUrl = sidecarBaseUrl(core) ?: return null
        val credentials = carddavCredentials(core) ?: return null
        return Target(baseUrl + path, credentials.authorization, credentials.username)
    }

    /**
     * Derives the PBX sidecar base URL from the [ott] carddav_intern_url
     * configuration value by stripping the /carddav/<ext>/<scope> path
     * suffix. When the configuration value is empty the sidecar features
     * are disabled (logged once per process only). Must be called from the
     * core thread.
     */
    @WorkerThread
    fun sidecarBaseUrl(core: Core): String? {
        val internUrl = core.config.getString(CONFIG_SECTION, CONFIG_INTERN_URL_KEY, "").orEmpty().trim()
        if (internUrl.isEmpty()) {
            if (!missingConfigurationLogged) {
                missingConfigurationLogged = true
                Log.i(
                    "$TAG No [$CONFIG_SECTION] $CONFIG_INTERN_URL_KEY in configuration, phone services features disabled"
                )
            }
            return null
        }
        val markerIndex = internUrl.indexOf(CARD_DAV_PATH_MARKER)
        if (markerIndex <= 0) {
            Log.w(
                "$TAG [$CONFIG_SECTION] $CONFIG_INTERN_URL_KEY [$internUrl] doesn't contain the expected [$CARD_DAV_PATH_MARKER] path, cannot derive sidecar endpoint"
            )
            return null
        }
        return internUrl.substring(0, markerIndex)
    }

    /**
     * The CardDAV identity for the sidecar Basic auth: [ott]
     * carddav_username (the device-unique `<ext>-<hex>` login minted at
     * provisioning) and carddav_password (its per-device secret). Anonymous
     * dev setups (no carddav_password entry) disable the feature. Must be
     * called from the core thread.
     */
    @WorkerThread
    fun carddavCredentials(core: Core): Credentials? {
        val username = core.config.getString(CONFIG_SECTION, CONFIG_CARDDAV_USERNAME_KEY, "").orEmpty().trim()
        val password = core.config.getString(CONFIG_SECTION, CONFIG_CARDDAV_PASSWORD_KEY, "").orEmpty().trim()
        if (username.isEmpty() || password.isEmpty()) {
            if (!missingConfigurationLogged) {
                missingConfigurationLogged = true
                Log.i(
                    "$TAG No [$CONFIG_SECTION] carddav credentials in configuration, phone services features disabled"
                )
            }
            return null
        }
        return Credentials(username, basicAuthorization(username, password))
    }

    /**
     * The Basic Authorization header value for the carddav identity.
     */
    fun basicAuthorization(username: String, password: String): String {
        return "Basic " + Base64.encodeToString("$username:$password".toByteArray(), Base64.NO_WRAP)
    }
}
