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
import org.linphone.core.Config
import org.linphone.core.Core
import org.linphone.core.Factory
import org.linphone.core.FriendList
import org.linphone.core.tools.Log

/**
 * Creates (or re-synchronizes) the OTT "Intern" and "Extern" CardDAV friend lists
 * from the [ott] section of the (remote) provisioning configuration.
 *
 * Idempotent: friend lists are matched by exact CardDAV URI, an already existing
 * list is only re-synchronized (its display name is always reset to the canonical
 * tier name), never re-created. CardDAV credentials (realm/username) are rotated
 * on every apply so a password change in the provisioning takes effect. The set
 * of provisioned URIs is persisted in the [ott_managed] configuration section
 * (key [OttCardDavProvisioning.CONFIG_FRIEND_LIST_URIS_KEY]) and friend lists
 * recorded there but no longer configured are removed - lists not provisioned by
 * this app are never touched.
 *
 * When the [ott] section is missing entirely the configuration is considered
 * stock and nothing at all is touched (log only). When the section exists but
 * both CardDAV URLs are empty, every previously provisioned friend list is
 * removed (full deprovisioning) and no new list is created.
 */
object OttCardDavProvisioning {
    private const val TAG = "[OTT CardDAV Provisioning]"

    private const val CONFIG_SECTION = "ott"
    private const val CONFIG_INTERN_URL_KEY = "carddav_intern_url"
    private const val CONFIG_EXTERN_URL_KEY = "carddav_extern_url"
    private const val CONFIG_USERNAME_KEY = "carddav_username"
    private const val CONFIG_PASSWORD_KEY = "carddav_password"
    private const val CONFIG_REALM_KEY = "carddav_realm"

    private const val CONFIG_MANAGED_SECTION = "ott_managed"
    private const val CONFIG_FRIEND_LIST_URIS_KEY = "friend_list_uris"

    private const val INTERN_LIST_NAME = "Intern"
    private const val EXTERN_LIST_NAME = "Extern"

    @WorkerThread
    fun apply(core: Core) {
        val config = core.config
        if (config.hasSection(CONFIG_SECTION) == 0) {
            Log.i(
                "$TAG No [$CONFIG_SECTION] configuration section at all, stock configuration, nothing to do"
            )
            return
        }

        val internUrl = config.getString(CONFIG_SECTION, CONFIG_INTERN_URL_KEY, "").orEmpty().trim()
        val externUrl = config.getString(CONFIG_SECTION, CONFIG_EXTERN_URL_KEY, "").orEmpty().trim()

        val desiredLists = arrayListOf<Pair<String, String>>()
        if (internUrl.isNotEmpty()) {
            desiredLists.add(INTERN_LIST_NAME to internUrl)
        }
        if (externUrl.isNotEmpty()) {
            desiredLists.add(EXTERN_LIST_NAME to externUrl)
        }
        val desiredUris = desiredLists.map { it.second }.toSet()

        val previouslyOwnedUris = readOwnedFriendListUris(config)
        for (friendList in core.friendsLists) {
            val uri = friendList.uri ?: continue
            if (uri in previouslyOwnedUris && uri !in desiredUris) {
                Log.i(
                    "$TAG Friend list [${friendList.displayName}] with URI [$uri] was provisioned by us but is no longer in configuration, removing it"
                )
                core.removeFriendList(friendList)
            }
        }

        if (desiredLists.isEmpty()) {
            Log.i(
                "$TAG No CardDAV URL left in [$CONFIG_SECTION] configuration section, all provisioned friend lists have been removed and none will be created"
            )
            config.setStringList(
                CONFIG_MANAGED_SECTION,
                CONFIG_FRIEND_LIST_URIS_KEY,
                arrayOf()
            )
            return
        }

        val username = config.getString(CONFIG_SECTION, CONFIG_USERNAME_KEY, "").orEmpty().trim()
        val password = config.getString(CONFIG_SECTION, CONFIG_PASSWORD_KEY, "").orEmpty().trim()
        val realm = config.getString(CONFIG_SECTION, CONFIG_REALM_KEY, "").orEmpty().trim()
        if (username.isNotEmpty() && password.isNotEmpty() && realm.isNotEmpty()) {
            val foundAuthInfo = core.findAuthInfo(realm, username, null)
            if (foundAuthInfo != null) {
                Log.i(
                    "$TAG Auth info with username [$username] and realm [$realm] already exists, replacing it to rotate its password"
                )
                core.removeAuthInfo(foundAuthInfo)
            } else {
                Log.i("$TAG Adding auth info with username [$username] and realm [$realm]")
            }
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
            Log.i(
                "$TAG CardDAV credentials in [$CONFIG_SECTION] configuration section incomplete, skipping auth info"
            )
        }

        for ((displayName, url) in desiredLists) {
            upsertCardDavFriendList(core, displayName, url)
        }

        Log.i("$TAG Persisting [${desiredUris.size}] provisioned friend list URI(s)")
        config.setStringList(
            CONFIG_MANAGED_SECTION,
            CONFIG_FRIEND_LIST_URIS_KEY,
            desiredUris.toTypedArray()
        )
    }

    @WorkerThread
    private fun readOwnedFriendListUris(config: Config): Set<String> {
        return config.getStringList(CONFIG_MANAGED_SECTION, CONFIG_FRIEND_LIST_URIS_KEY, arrayOf())
            .toSet()
    }

    @WorkerThread
    private fun upsertCardDavFriendList(core: Core, displayName: String, url: String) {
        val existingFriendList = core.friendsLists.find { it.uri == url }
        if (existingFriendList != null) {
            if (existingFriendList.displayName != displayName) {
                Log.i(
                    "$TAG Friend list with URI [$url] was named [${existingFriendList.displayName}], resetting its display name to [$displayName]"
                )
            }
            existingFriendList.displayName = displayName
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
