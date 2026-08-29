/*
 * OTT fork: tests for the store-prefix rules used by the transfer picker's
 * same-store-first sorting (oc-cb72).
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
}
