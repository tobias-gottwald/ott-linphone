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
package org.linphone.ui.main.model

import org.linphone.core.SearchResult

/**
 * Tier used to filter contacts between the OTT "Intern" and "Extern" CardDAV friend lists.
 *
 * A tier other than [ALL] only matches search results whose friend belongs to the friend
 * list provisioned with the corresponding CardDAV URI (read from the [CONFIG_SECTION]
 * configuration section, keys [CONFIG_INTERN_URL_KEY]/[CONFIG_EXTERN_URL_KEY], and computed
 * once per filter run by the caller - never per result). When no URI was provisioned for a
 * tier (stock configuration without OTT provisioning, null/empty URI), the friend list
 * display name is used as a fallback. Everything else (native contacts, LDAP results, raw
 * dial strings/suggestions, results without a friend) only matches [ALL].
 */
enum class ContactTier {
    ALL,
    INTERN,
    EXTERN;

    fun matches(result: SearchResult, internUri: String?, externUri: String): Boolean {
        if (this == ALL) return true
        val friendList = result.friend?.friendList ?: return false
        return when (this) {
            INTERN -> {
                if (internUri != null) {
                    friendList.uri == internUri
                } else {
                    friendList.displayName == INTERN_FRIEND_LIST_NAME
                }
            }
            EXTERN -> {
                if (externUri.isNotEmpty()) {
                    friendList.uri == externUri
                } else {
                    friendList.displayName == EXTERN_FRIEND_LIST_NAME
                }
            }
            ALL -> true
        }
    }

    companion object {
        const val CONFIG_SECTION = "ott"
        const val CONFIG_INTERN_URL_KEY = "carddav_intern_url"
        const val CONFIG_EXTERN_URL_KEY = "carddav_extern_url"

        const val INTERN_FRIEND_LIST_NAME = "Intern"
        const val EXTERN_FRIEND_LIST_NAME = "Extern"
    }
}
