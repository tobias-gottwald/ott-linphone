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

import androidx.annotation.WorkerThread
import org.linphone.core.Core
import org.linphone.core.Factory
import org.linphone.core.FriendList
import org.linphone.core.tools.Log

/**
 * Creates (or re-synchronizes) the OTT "Intern" and "Extern" CardDAV friend lists
 * from the [ott] section of the (remote) provisioning configuration.
 *
 * Idempotent: friend lists are matched by exact CardDAV URI, an already existing
 * list is only re-synchronized, never re-created, and no other friend list is
 * ever touched. Does nothing (except logging) when the configuration section or
 * both URLs are missing/empty, leaving the stock behaviour unchanged.
 */
object OttCardDavProvisioning {
    private const val TAG = "[OTT CardDAV Provisioning]"

    private const val CONFIG_SECTION = "ott"
    private const val CONFIG_INTERN_URL_KEY = "carddav_intern_url"
    private const val CONFIG_EXTERN_URL_KEY = "carddav_extern_url"
    private const val CONFIG_USERNAME_KEY = "carddav_username"
    private const val CONFIG_PASSWORD_KEY = "carddav_password"
    private const val CONFIG_REALM_KEY = "carddav_realm"

    private const val INTERN_LIST_NAME = "Intern"
    private const val EXTERN_LIST_NAME = "Extern"

    @WorkerThread
    fun apply(core: Core) {
        val config = core.config
        val internUrl = config.getString(CONFIG_SECTION, CONFIG_INTERN_URL_KEY, "").orEmpty().trim()
        val externUrl = config.getString(CONFIG_SECTION, CONFIG_EXTERN_URL_KEY, "").orEmpty().trim()
        if (internUrl.isEmpty() && externUrl.isEmpty()) {
            Log.i("$TAG No CardDAV URLs in [$CONFIG_SECTION] configuration section, nothing to do")
            return
        }

        val username = config.getString(CONFIG_SECTION, CONFIG_USERNAME_KEY, "").orEmpty().trim()
        val password = config.getString(CONFIG_SECTION, CONFIG_PASSWORD_KEY, "").orEmpty().trim()
        val realm = config.getString(CONFIG_SECTION, CONFIG_REALM_KEY, "").orEmpty().trim()
        if (username.isNotEmpty() && password.isNotEmpty() && realm.isNotEmpty()) {
            val foundAuthInfo = core.findAuthInfo(realm, username, null)
            if (foundAuthInfo == null) {
                Log.i("$TAG Adding auth info with username [$username] and realm [$realm]")
                val authInfo = Factory.instance().createAuthInfo(
                    username,
                    null,
                    password,
                    null,
                    realm,
                    null
                )
                core.addAuthInfo(authInfo)
            } else {
                Log.w(
                    "$TAG Auth info with username [$username] and realm [$realm] already exists, keeping it"
                )
            }
        } else {
            Log.i(
                "$TAG CardDAV credentials in [$CONFIG_SECTION] configuration section incomplete, skipping auth info"
            )
        }

        if (internUrl.isNotEmpty()) {
            upsertCardDavFriendList(core, INTERN_LIST_NAME, internUrl)
        }
        if (externUrl.isNotEmpty()) {
            upsertCardDavFriendList(core, EXTERN_LIST_NAME, externUrl)
        }
    }

    @WorkerThread
    private fun upsertCardDavFriendList(core: Core, displayName: String, url: String) {
        val existingFriendList = core.friendsLists.find { it.uri == url }
        if (existingFriendList != null) {
            Log.i(
                "$TAG CardDAV friend list [$displayName] with URI [$url] already exists, synchronizing it"
            )
            existingFriendList.synchronizeFriendsFromServer()
            return
        }

        Log.i("$TAG Creating CardDAV friend list [$displayName] with URI [$url], synchronizing it")
        val friendList = core.createFriendList()
        friendList.displayName = displayName
        friendList.type = FriendList.Type.CardDAV
        friendList.uri = url
        friendList.isDatabaseStorageEnabled = true
        core.addFriendList(friendList)
        friendList.synchronizeFriendsFromServer()
    }
}
