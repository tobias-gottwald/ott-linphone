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
 * Only search results whose friend belongs to the friend list with the matching display name
 * are shown when a tier other than [ALL] is selected. Everything else (native contacts, LDAP
 * results, raw dial strings/suggestions, results without a friend) only matches [ALL].
 */
enum class ContactTier {
    ALL,
    INTERN,
    EXTERN;

    fun matches(result: SearchResult): Boolean {
        if (this == ALL) return true
        val listName = result.friend?.friendList?.displayName
        val expected = if (this == INTERN) INTERN_FRIEND_LIST_NAME else EXTERN_FRIEND_LIST_NAME
        return listName == expected
    }

    companion object {
        const val INTERN_FRIEND_LIST_NAME = "Intern"
        const val EXTERN_FRIEND_LIST_NAME = "Extern"
    }
}
