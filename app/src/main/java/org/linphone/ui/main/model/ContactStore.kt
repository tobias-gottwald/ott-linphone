/*
 * OTT fork: store (branch) awareness for contact sorting (oc-cb72).
 *
 * ott_core numbers every internal extension per store: four digits whose
 * first digit is the store prefix (1xxx Kelheim, 2xxx Abensberg, 3xxx
 * Mainburg, 4xxx Wolnzach, 5xxx Langquaid, 6xxx test). Ring-group numbers
 * (<prefix>99) share their store's prefix, so the same rule covers them.
 *
 * Anything that is not a four-digit numeric extension (outside callers,
 * external suppliers' numbers, SIP URIs with hostnames, …) belongs to no
 * store and therefore never matches — sorting degrades to alphabetical.
 */
package org.linphone.ui.main.model

object ContactStore {
    /**
     * The store prefix of a username, or null when it is not a four-digit
     * numeric extension and thus has no store.
     */
    fun storePrefix(username: String?): Char? {
        if (username == null || username.length != 4 || username.any { !it.isDigit() }) {
            return null
        }
        return username[0]
    }

    /**
     * Whether a username belongs to the same store as the given own-store
     * prefix. Always false when either side has no store.
     */
    fun isSameStore(username: String?, ownPrefix: Char?): Boolean {
        if (ownPrefix == null) return false
        return storePrefix(username) == ownPrefix
    }
}
