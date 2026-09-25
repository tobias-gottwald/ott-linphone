/*
 * OTT fork: store (branch) awareness for contact sorting (oc-cb72, oc-635b).
 *
 * ott_core numbers every internal extension per store: four digits whose
 * first digit is the store prefix (1xxx Kelheim, 2xxx Abensberg, 3xxx
 * Mainburg, 4xxx Wolnzach, 5xxx Langquaid, 6xxx test). Ring-group numbers
 * (every four-digit number ending in 99, e.g. 1099/2099, reserved by the
 * PBX sidecar — enrollment refuses them for phones) share their store's
 * prefix, so the same rule covers them.
 *
 * Anything that is not a four-digit numeric extension (outside callers,
 * external suppliers' numbers, SIP URIs with hostnames, …) belongs to no
 * store and therefore never matches — sorting degrades to alphabetical.
 */
package org.linphone.ui.main.model

/**
 * Contact list ordering policy of the surface showing the list.
 *
 * The INTERN tier is ordered by buckets, alphabetically within each bucket,
 * with no-store entries (external numbers, TEL-only rows) always last:
 * - TRANSFER: same store, other stores' ring groups, other stores'
 *   extensions — transfers mostly stay inside the own store; a caller who
 *   dialed the wrong store goes to another store's ring group; reaching a
 *   specific person abroad is the rarest case.
 * - NEW_CALL: other stores' ring groups, same store, other stores'
 *   extensions — a new internal call most likely targets another store,
 *   the own store still comes before individual foreign extensions.
 *
 * On the other tiers TRANSFER keeps oc-cb72's same-store-first order and
 * NEW_CALL/ALPHABETICAL keep plain alphabetical order.
 */
enum class ContactSortMode {
    ALPHABETICAL,
    NEW_CALL,
    TRANSFER
}

object ContactStore {
    private const val NO_STORE_RANK = 3

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

    /**
     * Whether a username is a store ring-group number: every four-digit
     * number ending in 99 is reserved for ring groups (pbx_sidecar
     * locations.js RING_GROUP_EXTENSION_PATTERN /^\d{2}99$/, enrollment
     * refuses such numbers for phones), e.g. 1099 Kelheim … 6099 test.
     */
    fun isRingGroup(username: String?): Boolean {
        return username != null &&
            username.length == 4 &&
            username.endsWith("99") &&
            username.all { it.isDigit() }
    }

    /**
     * Sort rank (lower first) of an entry on the INTERN tier:
     * - sameStoreFirst (transfer order): 0 same store (incl. the own ring
     *   group), 1 other stores' ring groups, 2 other stores' extensions,
     * - otherwise (new-call order): 0 other stores' ring groups, 1 same
     *   store, 2 other stores' extensions.
     *
     * Entries without a store — and everything, when the own account has no
     * store prefix — rank [NO_STORE_RANK], i.e. after all buckets.
     */
    fun internRank(username: String?, ownPrefix: Char?, sameStoreFirst: Boolean): Int {
        if (ownPrefix == null) return NO_STORE_RANK
        val prefix = storePrefix(username) ?: return NO_STORE_RANK
        return when {
            prefix == ownPrefix -> if (sameStoreFirst) 0 else 1
            isRingGroup(username) -> if (sameStoreFirst) 1 else 0
            else -> 2
        }
    }
}
