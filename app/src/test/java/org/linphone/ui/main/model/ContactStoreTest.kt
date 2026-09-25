/*
 * OTT fork: tests for the store-prefix rules behind the pickers' bucket
 * sorting (oc-cb72 same-store-first, oc-635b intern-tier bucket order).
 */
package org.linphone.ui.main.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactStoreTest {
    @Test
    fun storePrefixOfFourDigitExtensionsIsFirstDigit() {
        assertEquals('1', ContactStore.storePrefix("1001"))
        assertEquals('1', ContactStore.storePrefix("1099")) // ring group shares the store prefix
        assertEquals('2', ContactStore.storePrefix("2042"))
        assertEquals('6', ContactStore.storePrefix("6099"))
    }

    @Test
    fun storePrefixIsNullForNonExtensionUsernames() {
        assertNull(ContactStore.storePrefix(null))
        assertNull(ContactStore.storePrefix(""))
        assertNull(ContactStore.storePrefix("100")) // too short
        assertNull(ContactStore.storePrefix("10001")) // too long
        assertNull(ContactStore.storePrefix("100a")) // not numeric
        assertNull(ContactStore.storePrefix("+499990001")) // outside caller
        assertNull(ContactStore.storePrefix("sip:user@host")) // URI, not an extension
    }

    @Test
    fun isSameStoreMatchesOnlyOwnStoreExtensions() {
        assertTrue(ContactStore.isSameStore("1042", '1'))
        assertTrue(ContactStore.isSameStore("1099", '1'))
        assertFalse(ContactStore.isSameStore("2042", '1')) // other store
        assertFalse(ContactStore.isSameStore("+499944123456", '1')) // external number
        assertFalse(ContactStore.isSameStore(null, '1'))
    }

    @Test
    fun isSameStoreIsAlwaysFalseWithoutOwnPrefix() {
        // Degradation path: own account has no 4-digit extension -> plain alphabetical
        assertFalse(ContactStore.isSameStore("1042", null))
        assertFalse(ContactStore.isSameStore(null, null))
    }

    @Test
    fun isRingGroupMatchesReserved99Numbers() {
        assertTrue(ContactStore.isRingGroup("1099")) // Kelheim
        assertTrue(ContactStore.isRingGroup("2099")) // Abensberg
        assertTrue(ContactStore.isRingGroup("6099")) // test
        assertFalse(ContactStore.isRingGroup("2042")) // plain extension
        assertFalse(ContactStore.isRingGroup("199")) // too short
        assertFalse(ContactStore.isRingGroup("10990")) // too long
        assertFalse(ContactStore.isRingGroup("10a9")) // not numeric
        assertFalse(ContactStore.isRingGroup(null))
    }

    @Test
    fun transferOrderRanksSameStoreThenOtherRingGroupsThenOtherExts() {
        assertEquals(0, ContactStore.internRank("2110", '2', sameStoreFirst = true))
        assertEquals(0, ContactStore.internRank("2099", '2', sameStoreFirst = true)) // own ring group is same store
        assertEquals(1, ContactStore.internRank("1099", '2', sameStoreFirst = true)) // other store ring group
        assertEquals(2, ContactStore.internRank("1042", '2', sameStoreFirst = true)) // other store ext
        assertEquals(3, ContactStore.internRank("+499944123456", '2', sameStoreFirst = true)) // no store
        assertEquals(3, ContactStore.internRank(null, '2', sameStoreFirst = true))
    }

    @Test
    fun newCallOrderRanksOtherRingGroupsThenSameStoreThenOtherExts() {
        assertEquals(0, ContactStore.internRank("1099", '2', sameStoreFirst = false))
        assertEquals(1, ContactStore.internRank("2110", '2', sameStoreFirst = false))
        assertEquals(1, ContactStore.internRank("2099", '2', sameStoreFirst = false)) // own ring group is same store
        assertEquals(2, ContactStore.internRank("3042", '2', sameStoreFirst = false))
        assertEquals(3, ContactStore.internRank("+499944123456", '2', sameStoreFirst = false)) // no store
        assertEquals(3, ContactStore.internRank(null, '2', sameStoreFirst = false))
    }

    @Test
    fun internRankDegradesToNoStoreWithoutOwnPrefix() {
        // Degradation path: own account has no 4-digit extension -> plain alphabetical
        assertEquals(3, ContactStore.internRank("2110", null, sameStoreFirst = true))
        assertEquals(3, ContactStore.internRank("2110", null, sameStoreFirst = false))
    }
}
