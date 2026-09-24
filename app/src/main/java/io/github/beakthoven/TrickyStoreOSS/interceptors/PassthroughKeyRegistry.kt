/*
 * Copyright 2026 Dakkshesh <beakthoven@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package io.github.beakthoven.TrickyStoreOSS.interceptors

import android.system.keystore2.KeyDescriptor
import java.util.concurrent.ConcurrentHashMap

/** Tracks keys that must return unmodified hardware attestation chains. */
object PassthroughKeyRegistry {
    data class KeyId(val uid: Int, val alias: String)

    private val tracked = ConcurrentHashMap<KeyId, Unit>()
    private val byNspace = ConcurrentHashMap<Long, KeyId>()

    fun aliasHash(alias: String?): String = if (alias == null) "null" else Integer.toHexString(alias.hashCode())

    fun promote(uid: Int, alias: String, nspace: Long?) {
        val id = KeyId(uid, alias)
        tracked[id] = Unit
        if (nspace != null && nspace != 0L) {
            byNspace[nspace] = id
        }
    }

    fun remove(uid: Int, alias: String) {
        val id = KeyId(uid, alias)
        tracked.remove(id)
        byNspace.entries.removeIf { it.value == id }
    }

    fun clear() {
        tracked.clear()
        byNspace.clear()
    }

    fun isTracked(id: KeyId): Boolean = tracked.containsKey(id)

    fun resolve(uid: Int, alias: String?, nspace: Long): KeyId? {
        if (nspace != 0L) {
            byNspace[nspace]?.takeIf { it.uid == uid && isTracked(it) }?.let { return it }
        }
        if (alias != null) {
            val id = KeyId(uid, alias)
            if (isTracked(id)) return id
        }
        return null
    }

    fun isTracked(uid: Int, descriptor: KeyDescriptor): Boolean =
        resolve(uid, descriptor.alias, descriptor.nspace) != null
}

