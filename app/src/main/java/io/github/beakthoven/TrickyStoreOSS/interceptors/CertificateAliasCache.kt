/*
 * Copyright 2026 Dakkshesh <beakthoven@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package io.github.beakthoven.TrickyStoreOSS.interceptors

import android.system.keystore2.KeyDescriptor
import android.system.keystore2.KeyEntryResponse
import androidx.annotation.Keep
import io.github.beakthoven.TrickyStoreOSS.CertificateGen
import io.github.beakthoven.TrickyStoreOSS.CertificateHack
import io.github.beakthoven.TrickyStoreOSS.PersistenceManager
import io.github.beakthoven.TrickyStoreOSS.logging.DiagLog
import java.security.KeyPair
import java.security.cert.Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKey

/** Alias-keyed certificate-response caches and ownership transitions (Phase 6E). */
object CertificateAliasCache {
    data class Key(val uid: Int, val alias: String)

    data class Info(
        val keyPair: KeyPair?,
        val secretKey: SecretKey?,
        val response: KeyEntryResponse,
        val params: CertificateGen.KeyGenParameters,
    )

    data class GrantInfo(val ownerUid: Int, val granteeUid: Int, val key: Key, val accessVector: Int = 0)

    @Keep val keys = ConcurrentHashMap<Key, Info>()

    @Keep val keyPairs = ConcurrentHashMap<Key, Pair<KeyPair, List<Certificate>>>()

    @Keep val skipLeafHacks = ConcurrentHashMap<Key, Boolean>()

    @Keep val keysByNspace = ConcurrentHashMap<Long, Key>()

    @Keep val patchedResponses = ConcurrentHashMap<Key, KeyEntryResponse>()

    @Keep val grants = ConcurrentHashMap<Long, GrantInfo>()

    @Keep val usageRemaining = ConcurrentHashMap<Key, Int>()

    @Keep fun isPassthroughKey(key: Key): Boolean =
        PassthroughKeyRegistry.isTracked(PassthroughKeyRegistry.KeyId(key.uid, key.alias))

    @Keep
    fun isPassthroughDescriptor(uid: Int, descriptor: KeyDescriptor): Boolean {
        if (PassthroughKeyRegistry.isTracked(uid, descriptor)) return true
        return resolveOwnerKeys(uid, descriptor).any { isPassthroughKey(it) }
    }

    @Keep fun getKeyPairs(uid: Int, alias: String): Pair<KeyPair, List<Certificate>>? = keyPairs[Key(uid, alias)]

    @Keep
    fun findAliasForNspace(uid: Int, nspace: Long): String? {
        val key = keysByNspace[nspace]?.takeIf { it.uid == uid } ?: return null
        return key.alias
    }

    private fun resolveOwnerKeys(uid: Int, descriptor: KeyDescriptor): List<Key> {
        val candidates = ArrayList<Key>(3)
        if (descriptor.nspace != 0L) {
            keysByNspace[descriptor.nspace]?.takeIf { it.uid == uid }?.let { candidates.add(it) }
            grants[descriptor.nspace]?.takeIf { it.granteeUid == uid }?.let { candidates.add(it.key) }
        }
        descriptor.alias?.let { candidates.add(Key(uid, it)) }
        return candidates
    }

    @Keep
    fun findGeneratedKey(uid: Int, descriptor: KeyDescriptor): Info? =
        resolveOwnerKeys(uid, descriptor).firstNotNullOfOrNull { keys[it] }

    @Keep
    fun resolvePatchedResponse(uid: Int, descriptor: KeyDescriptor): KeyEntryResponse? =
        resolveOwnerKeys(uid, descriptor).firstNotNullOfOrNull { patchedResponses[it] }

    @Keep fun isPatchedKey(key: Key): Boolean = keys.containsKey(key) || patchedResponses.containsKey(key)

    @Keep
    fun shouldSkipLeafHackFor(uid: Int, descriptor: KeyDescriptor): Boolean =
        resolveOwnerKeys(uid, descriptor).firstNotNullOfOrNull { skipLeafHacks[it] } ?: false

    @Keep
    fun resolveKey(uid: Int, descriptor: KeyDescriptor): Key? {
        if (descriptor.nspace != 0L) {
            keysByNspace[descriptor.nspace]
                ?.takeIf { it.uid == uid }
                ?.let {
                    return it
                }
        }
        return descriptor.alias?.let { Key(uid, it) }
    }

    @Keep
    fun updateKeyCertChain(key: Key, publicCert: ByteArray?, certificateChain: ByteArray?) {
        keys[key]?.let {
            it.response.metadata.certificate = publicCert
            it.response.metadata.certificateChain = certificateChain
        }
        patchedResponses[key]?.metadata?.let {
            it.certificate = publicCert
            it.certificateChain = certificateChain
        }
        skipLeafHacks.remove(key)
    }

    /** Clears synthetic/generated/patched in-memory certificate caches for [alias]. */
    @Keep
    fun clearSyntheticCertificateState(
        uid: Int,
        alias: String,
        reason: String,
        includePersistence: Boolean = false,
    ) {
        val k = Key(uid, alias)
        keys[k]?.response?.metadata?.key?.nspace?.let { keysByNspace.remove(it) }
        keysByNspace.entries.removeIf { it.value == k }
        keys.remove(k)
        keyPairs.remove(k)
        skipLeafHacks.remove(k)
        patchedResponses.remove(k)
        usageRemaining.remove(k)
        grants.values.removeIf { it.key == k }
        CertificateHack.leafAlgorithms.remove(CertificateHack.KeyIdentifier(alias, uid))
        if (includePersistence) PersistenceManager.deleteKey(uid, alias)
        DiagLog.certStateClear(uid, PassthroughKeyRegistry.aliasHash(alias), reason)
    }

    @Keep
    fun relinquishPassthroughOwnership(uid: Int, alias: String, reason: String) {
        PassthroughKeyRegistry.remove(uid, alias)
        DiagLog.passthroughTrack("remove", uid, PassthroughKeyRegistry.aliasHash(alias))
        DiagLog.certStateClear(uid, PassthroughKeyRegistry.aliasHash(alias), reason)
    }

    /**
     * Drops cached certificate-response ownership for [alias].
     * Used when the real keystore mutates certificate-bearing subcomponents.
     */
    @Keep
    fun invalidateCertificateResponseState(
        uid: Int,
        alias: String,
        reason: String,
        removePassthrough: Boolean = true,
    ) {
        clearSyntheticCertificateState(uid, alias, reason, includePersistence = false)
        if (removePassthrough) relinquishPassthroughOwnership(uid, alias, reason)
    }

    @Keep
    fun cleanupKey(uid: Int, alias: String) {
        clearSyntheticCertificateState(uid, alias, "deleteKey", includePersistence = true)
        relinquishPassthroughOwnership(uid, alias, "deleteKey")
    }

    @Keep
    fun cleanupAll() {
        keys.clear()
        keyPairs.clear()
        skipLeafHacks.clear()
        PassthroughKeyRegistry.clear()
        patchedResponses.clear()
        keysByNspace.clear()
        usageRemaining.clear()
        grants.clear()
        PersistenceManager.clearAll()
    }
}
