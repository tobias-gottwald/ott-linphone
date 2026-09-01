/*
 * OTT fork: tests for ContactTier.matches against the CardDAV URI shapes
 * observed at runtime (oc-5bbd).
 *
 * liblinphone persists the DISCOVERED addressbook collection URI
 * ("<provisioned>/book", advertised by our sidecar's addressbook-home-set)
 * as the friend list URI, so tier matching must accept that spelling and
 * not only the provisioned collection URL.
 */
package org.linphone.ui.main.model

import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.linphone.core.Friend
import org.linphone.core.FriendList
import org.linphone.core.SearchResult
import org.junit.Test

class ContactTierTest {
    private val internUrl = "https://sip-deploy.otthoeren.de/carddav/6001/intern"
    private val externUrl = "https://sip-deploy.otthoeren.de/carddav/6001/extern"

    private fun resultInList(listUri: String?, listName: String? = "Intern"): SearchResult {
        val friendList = mockk<FriendList>()
        every { friendList.uri } returns listUri
        every { friendList.displayName } returns listName
        val friend = mockk<Friend>()
        every { friend.friendList } returns friendList
        val result = mockk<SearchResult>()
        every { result.friend } returns friend
        return result
    }

    @Test
    fun internMatchesDiscoveredBookUri() {
        // Shape found in the on-device friends_list table: sync_uri carries "/book"
        val result = resultInList("$internUrl/book")
        assertTrue(ContactTier.INTERN.matches(result, internUrl, externUrl))
        assertFalse(ContactTier.EXTERN.matches(result, internUrl, externUrl))
    }

    @Test
    fun internMatchesExactProvisionedUri() {
        val result = resultInList(internUrl)
        assertTrue(ContactTier.INTERN.matches(result, internUrl, externUrl))
    }

    @Test
    fun externMatchesDiscoveredBookUri() {
        val result = resultInList("$externUrl/book", listName = "Extern")
        assertTrue(ContactTier.EXTERN.matches(result, internUrl, externUrl))
        assertFalse(ContactTier.INTERN.matches(result, internUrl, externUrl))
    }

    @Test
    fun foreignAndMissingUrisNeverMatch() {
        // A list of another extension or deployment must not match
        val foreign = resultInList("https://sip-deploy.otthoeren.de/carddav/1001/intern/book")
        assertFalse(ContactTier.INTERN.matches(resultInList(null), internUrl, externUrl))
        assertFalse(ContactTier.INTERN.matches(foreign, internUrl, externUrl))
        // Truncated prefixes are not our URI
        val truncated = resultInList("$internUrl/something-else")
        assertFalse(ContactTier.INTERN.matches(truncated, internUrl, externUrl))
    }

    @Test
    fun allMatchesEverythingAndOthersRequireFriendList() {
        val noFriend = mockk<SearchResult>()
        every { noFriend.friend } returns null
        assertTrue(ContactTier.ALL.matches(noFriend, internUrl, externUrl))
        assertFalse(ContactTier.INTERN.matches(noFriend, internUrl, externUrl))
    }

    @Test
    fun tieredSearchDropsUserDomainFilter() {
        // MagicSearch only returns TEL-only friends when the domain filter is
        // empty (extern suppliers, fax rows), so tiered searches must not
        // inherit the user's contacts filter (oc-80b0).
        assertEquals("", ContactTier.INTERN.searchDomain("*"))
        assertEquals("", ContactTier.EXTERN.searchDomain("*"))
        assertEquals("", ContactTier.INTERN.searchDomain("sip-internal.otthoeren.de"))
    }

    @Test
    fun allSearchKeepsUserDomainFilter() {
        assertEquals("*", ContactTier.ALL.searchDomain("*"))
        assertEquals("sip-internal.otthoeren.de", ContactTier.ALL.searchDomain("sip-internal.otthoeren.de"))
        assertEquals("", ContactTier.ALL.searchDomain(""))
    }
}
