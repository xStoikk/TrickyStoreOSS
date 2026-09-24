/*
 * Copyright 2026 Dakkshesh <beakthoven@gmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package io.github.beakthoven.TrickyStoreOSS.interceptors

import android.system.keystore2.KeyDescriptor
import android.system.keystore2.KeyEntryResponse
import io.github.beakthoven.TrickyStoreOSS.CertificateGen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Phase 6E: alias-scoped certificate-response ownership transitions. */
class CertificateAliasOwnershipTest {
    private companion object {
        const val UID_A = 10485
        const val UID_B = 10486
        const val ALIAS_A = "KeyAttestation"
        const val ALIAS_B = "OtherAlias"
        val NSPACE = 0x0123456789ABCDEFL
    }

    @Before
    fun setUp() {
        resetState()
    }

    @After
    fun tearDown() {
        resetState()
    }

    private fun resetState() {
        CertificateAliasCache.keys.clear()
        CertificateAliasCache.keyPairs.clear()
        CertificateAliasCache.skipLeafHacks.clear()
        CertificateAliasCache.patchedResponses.clear()
        CertificateAliasCache.keysByNspace.clear()
        CertificateAliasCache.usageRemaining.clear()
        CertificateAliasCache.grants.clear()
        PassthroughKeyRegistry.clear()
    }

    private fun routeKey(uid: Int, alias: String) = CertificateAliasCache.Key(uid, alias)

    private fun descriptor(uid: Int, alias: String, nspace: Long = 0L): KeyDescriptor =
        KeyDescriptor().apply {
            domain = 0
            this.nspace = nspace
            this.alias = alias
        }

    private fun stubInfo(): CertificateAliasCache.Info {
        val kgp = CertificateGen.KeyGenParameters(emptyArray())
        return CertificateAliasCache.Info(null, null, KeyEntryResponse(), kgp)
    }

    private fun seedGenerated(uid: Int, alias: String, nspace: Long = NSPACE) {
        val k = routeKey(uid, alias)
        CertificateAliasCache.keys[k] = stubInfo()
        CertificateAliasCache.skipLeafHacks[k] = true
        if (nspace != 0L) CertificateAliasCache.keysByNspace[nspace] = k
    }

    private fun seedPatched(uid: Int, alias: String) {
        CertificateAliasCache.patchedResponses[routeKey(uid, alias)] = KeyEntryResponse()
    }

    private fun simulatePassthroughSuccess(uid: Int, alias: String, nspace: Long = NSPACE) {
        CertificateAliasCache.clearSyntheticCertificateState(uid, alias, "transition-to-passthrough:pre")
        CertificateAliasCache.clearSyntheticCertificateState(
            uid,
            alias,
            "transition-to-passthrough:post",
            includePersistence = true,
        )
        if (nspace != 0L) CertificateAliasCache.keysByNspace[nspace] = routeKey(uid, alias)
        PassthroughKeyRegistry.promote(uid, alias, nspace)
    }

    private fun simulateGenerateClaim(uid: Int, alias: String) {
        CertificateAliasCache.relinquishPassthroughOwnership(uid, alias, "transition-to-generate")
        CertificateAliasCache.clearSyntheticCertificateState(uid, alias, "transition-to-generate:pre")
        CertificateAliasCache.keys[routeKey(uid, alias)] = stubInfo()
        CertificateAliasCache.skipLeafHacks[routeKey(uid, alias)] = true
    }

    @Test
    fun generateToPassthroughSameAlias() {
        seedGenerated(UID_A, ALIAS_A)
        assertNotNull(CertificateAliasCache.findGeneratedKey(UID_A, descriptor(UID_A, ALIAS_A)))

        simulatePassthroughSuccess(UID_A, ALIAS_A)

        assertNull(CertificateAliasCache.findGeneratedKey(UID_A, descriptor(UID_A, ALIAS_A)))
        assertFalse(CertificateAliasCache.shouldSkipLeafHackFor(UID_A, descriptor(UID_A, ALIAS_A)))
        assertTrue(CertificateAliasCache.isPassthroughDescriptor(UID_A, descriptor(UID_A, ALIAS_A, NSPACE)))
        assertEquals(
            GetKeyEntryPostPolicy.Action.PASSTHROUGH_UNMODIFIED,
            GetKeyEntryPostPolicy.decide(isPassthrough = true, hasCachedPatch = false),
        )
    }

    @Test
    fun leafPatchToPassthroughSameAlias() {
        seedPatched(UID_A, ALIAS_A)
        assertNotNull(CertificateAliasCache.resolvePatchedResponse(UID_A, descriptor(UID_A, ALIAS_A)))

        simulatePassthroughSuccess(UID_A, ALIAS_A)

        assertNull(CertificateAliasCache.resolvePatchedResponse(UID_A, descriptor(UID_A, ALIAS_A)))
        assertTrue(CertificateAliasCache.isPassthroughDescriptor(UID_A, descriptor(UID_A, ALIAS_A)))
    }

    @Test
    fun passthroughToGenerateSameAlias() {
        PassthroughKeyRegistry.promote(UID_A, ALIAS_A, NSPACE)
        assertTrue(CertificateAliasCache.isPassthroughDescriptor(UID_A, descriptor(UID_A, ALIAS_A)))

        simulateGenerateClaim(UID_A, ALIAS_A)

        assertFalse(CertificateAliasCache.isPassthroughDescriptor(UID_A, descriptor(UID_A, ALIAS_A)))
        assertNotNull(CertificateAliasCache.findGeneratedKey(UID_A, descriptor(UID_A, ALIAS_A)))
        assertTrue(CertificateAliasCache.shouldSkipLeafHackFor(UID_A, descriptor(UID_A, ALIAS_A)))
    }

    @Test
    fun deleteKeyClearsAllOwnership() {
        seedGenerated(UID_A, ALIAS_A)
        seedPatched(UID_A, ALIAS_A)
        PassthroughKeyRegistry.promote(UID_A, ALIAS_A, NSPACE)

        CertificateAliasCache.cleanupKey(UID_A, ALIAS_A)

        assertNull(CertificateAliasCache.findGeneratedKey(UID_A, descriptor(UID_A, ALIAS_A)))
        assertNull(CertificateAliasCache.resolvePatchedResponse(UID_A, descriptor(UID_A, ALIAS_A)))
        assertFalse(CertificateAliasCache.isPassthroughDescriptor(UID_A, descriptor(UID_A, ALIAS_A, NSPACE)))
    }

    @Test
    fun ownershipChangeForAliasADoesNotClearAliasB() {
        seedGenerated(UID_A, ALIAS_A)
        seedGenerated(UID_A, ALIAS_B)
        seedPatched(UID_A, ALIAS_B)

        simulatePassthroughSuccess(UID_A, ALIAS_A)

        assertNull(CertificateAliasCache.findGeneratedKey(UID_A, descriptor(UID_A, ALIAS_A)))
        assertNotNull(CertificateAliasCache.findGeneratedKey(UID_A, descriptor(UID_A, ALIAS_B)))
        assertNotNull(CertificateAliasCache.resolvePatchedResponse(UID_A, descriptor(UID_A, ALIAS_B)))
    }

    @Test
    fun identicalAliasStringsDoNotCrossContaminateUids() {
        seedGenerated(UID_A, ALIAS_A)
        seedPatched(UID_B, ALIAS_A)

        simulatePassthroughSuccess(UID_A, ALIAS_A)

        assertNull(CertificateAliasCache.findGeneratedKey(UID_A, descriptor(UID_A, ALIAS_A)))
        assertNotNull(CertificateAliasCache.resolvePatchedResponse(UID_B, descriptor(UID_B, ALIAS_A)))
        assertFalse(CertificateAliasCache.isPassthroughDescriptor(UID_B, descriptor(UID_B, ALIAS_A)))
    }

    @Test
    fun updateSubcomponentInvalidatesGeneratedOwner() {
        seedGenerated(UID_A, ALIAS_A)

        CertificateAliasCache.invalidateCertificateResponseState(
            UID_A,
            ALIAS_A,
            "updateSubcomponent:post",
            removePassthrough = true,
        )

        assertNull(CertificateAliasCache.findGeneratedKey(UID_A, descriptor(UID_A, ALIAS_A)))
        assertFalse(CertificateAliasCache.shouldSkipLeafHackFor(UID_A, descriptor(UID_A, ALIAS_A)))
        assertNull(CertificateAliasCache.keysByNspace[NSPACE])
    }

    @Test
    fun updateSubcomponentInvalidatesPatchedOwner() {
        seedPatched(UID_A, ALIAS_A)
        CertificateAliasCache.skipLeafHacks[routeKey(UID_A, ALIAS_A)] = true

        CertificateAliasCache.invalidateCertificateResponseState(
            UID_A,
            ALIAS_A,
            "updateSubcomponent:post",
            removePassthrough = true,
        )

        assertNull(CertificateAliasCache.resolvePatchedResponse(UID_A, descriptor(UID_A, ALIAS_A)))
        assertFalse(CertificateAliasCache.shouldSkipLeafHackFor(UID_A, descriptor(UID_A, ALIAS_A)))
    }

    @Test
    fun updateSubcomponentPreKeepsPassthroughPostRemovesIt() {
        PassthroughKeyRegistry.promote(UID_A, ALIAS_A, NSPACE)
        seedPatched(UID_A, ALIAS_A)

        CertificateAliasCache.invalidateCertificateResponseState(
            UID_A,
            ALIAS_A,
            "updateSubcomponent:pre",
            removePassthrough = false,
        )
        assertTrue(CertificateAliasCache.isPassthroughDescriptor(UID_A, descriptor(UID_A, ALIAS_A, NSPACE)))
        assertNull(CertificateAliasCache.resolvePatchedResponse(UID_A, descriptor(UID_A, ALIAS_A)))

        CertificateAliasCache.invalidateCertificateResponseState(
            UID_A,
            ALIAS_A,
            "updateSubcomponent:post",
            removePassthrough = true,
        )
        assertFalse(CertificateAliasCache.isPassthroughDescriptor(UID_A, descriptor(UID_A, ALIAS_A, NSPACE)))
    }

    @Test
    fun updateSubcomponentForAliasADoesNotAffectAliasB() {
        seedGenerated(UID_A, ALIAS_A)
        seedPatched(UID_A, ALIAS_B)
        PassthroughKeyRegistry.promote(UID_B, ALIAS_B, NSPACE + 1)

        CertificateAliasCache.invalidateCertificateResponseState(
            UID_A,
            ALIAS_A,
            "updateSubcomponent:post",
            removePassthrough = true,
        )

        assertNull(CertificateAliasCache.findGeneratedKey(UID_A, descriptor(UID_A, ALIAS_A)))
        assertNotNull(CertificateAliasCache.resolvePatchedResponse(UID_A, descriptor(UID_A, ALIAS_B)))
        assertTrue(CertificateAliasCache.isPassthroughDescriptor(UID_B, descriptor(UID_B, ALIAS_B, NSPACE + 1)))
    }
}
