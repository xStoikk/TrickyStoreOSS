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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Phase 6F-FINAL: AUTO must passthrough ATTEST_KEY creation and descriptor consumption coherently. */
class AutoAttestKeyCreationCoherenceTest {
    private companion object {
        const val UID = 10485
        const val PERSISTENT_ALIAS = "KeyAttestation_persistent"
        const val MAIN_ALIAS = "KeyAttestation"
        const val PERSISTENT_NSPACE = 0x0FEDCBA987654321L
        const val MAIN_NSPACE = 0x0123456789ABCDEFL
    }

    @Before
    fun setUp() {
        PassthroughKeyRegistry.clear()
        CertificateAliasCache.keys.clear()
        CertificateAliasCache.skipLeafHacks.clear()
        CertificateAliasCache.patchedResponses.clear()
        CertificateAliasCache.keysByNspace.clear()
    }

    @After
    fun tearDown() = setUp()

    private fun forceForge(
        needGenerate: Boolean,
        hasAttestKeyPurpose: Boolean = false,
        attestationKeyDescriptorSet: Boolean = false,
        hasDeviceIdAttestation: Boolean = false,
    ): Boolean =
        GenerateKeyRoute.computeForceForge(
            needGenerate = needGenerate,
            hasDeviceIdAttestation = hasDeviceIdAttestation,
            hasAttestKeyPurpose = hasAttestKeyPurpose,
            attestationKeyDescriptorSet = attestationKeyDescriptorSet,
        )

    private fun autoRoute(
        hasAttestKeyPurpose: Boolean = false,
        attestationKeyDescriptorSet: Boolean = false,
        hasDeviceIdAttestation: Boolean = false,
        needGenerate: Boolean = false,
    ): String {
        val forge = forceForge(needGenerate, hasAttestKeyPurpose, attestationKeyDescriptorSet, hasDeviceIdAttestation)
        return GenerateKeyRoute.selectTopLevelRoute(
            forceForge = forge,
            needHack = true,
            explicitLeafHack = false,
            hasAttestationChallenge = true,
            needGenerate = needGenerate,
        )
    }

    private fun descriptor(alias: String, nspace: Long = 0L) =
        KeyDescriptor().apply {
            domain = 0
            this.nspace = nspace
            this.alias = alias
        }

    private fun stubInfo(): CertificateAliasCache.Info {
        val kgp = CertificateGen.KeyGenParameters(emptyArray())
        return CertificateAliasCache.Info(null, null, KeyEntryResponse(), kgp)
    }

    @Test
    fun autoWorkingTeeWithAttestKeyPurposeUsesPassthrough() {
        assertFalse(forceForge(needGenerate = false, hasAttestKeyPurpose = true))
        assertEquals(GenerateKeyRoute.ROUTE_PASSTHROUGH_REAL_TEE, autoRoute(hasAttestKeyPurpose = true))
    }

    @Test
    fun autoUnknownUsableTeeWithAttestKeyPurposeUsesPassthrough() {
        assertEquals(GenerateKeyRoute.ROUTE_PASSTHROUGH_REAL_TEE, autoRoute(hasAttestKeyPurpose = true))
    }

    @Test
    fun autoBrokenTeeWithAttestKeyPurposeUsesGenerateFallback() {
        assertTrue(forceForge(needGenerate = true, hasAttestKeyPurpose = true))
        assertEquals(GenerateKeyRoute.ROUTE_GENERATE, autoRoute(hasAttestKeyPurpose = true, needGenerate = true))
    }

    @Test
    fun explicitGenerateWithAttestKeyPurposeUsesGenerate() {
        assertTrue(forceForge(needGenerate = true, hasAttestKeyPurpose = true))
        assertEquals(GenerateKeyRoute.ROUTE_GENERATE, autoRoute(hasAttestKeyPurpose = true, needGenerate = true))
    }

    @Test
    fun autoAttestKeyCreationThenDescriptorConsumerBothPassthrough() {
        assertEquals(GenerateKeyRoute.ROUTE_PASSTHROUGH_REAL_TEE, autoRoute(hasAttestKeyPurpose = true))
        assertEquals(
            GenerateKeyRoute.ROUTE_PASSTHROUGH_REAL_TEE,
            autoRoute(attestationKeyDescriptorSet = true),
        )
    }

    @Test
    fun syntheticPersistentAttestKeyToAutoClearsOwnership() {
        val key = CertificateAliasCache.Key(UID, PERSISTENT_ALIAS)
        CertificateAliasCache.keys[key] = stubInfo()
        CertificateAliasCache.skipLeafHacks[key] = true

        assertFalse(forceForge(needGenerate = false, hasAttestKeyPurpose = true))
        CertificateAliasCache.clearSyntheticCertificateState(UID, PERSISTENT_ALIAS, "transition-to-passthrough:pre")
        PassthroughKeyRegistry.promote(UID, PERSISTENT_ALIAS, PERSISTENT_NSPACE)

        assertNull(CertificateAliasCache.keys[key])
        assertFalse(CertificateAliasCache.shouldSkipLeafHackFor(UID, descriptor(PERSISTENT_ALIAS)))
        assertTrue(CertificateAliasCache.isPassthroughDescriptor(UID, descriptor(PERSISTENT_ALIAS, PERSISTENT_NSPACE)))
    }

    @Test
    fun autoAttestKeySuccessPromotesPassthroughRegistryOwner() {
        assertEquals(GenerateKeyRoute.ROUTE_PASSTHROUGH_REAL_TEE, autoRoute(hasAttestKeyPurpose = true))
        PassthroughKeyRegistry.promote(UID, PERSISTENT_ALIAS, PERSISTENT_NSPACE)
        assertTrue(PassthroughKeyRegistry.resolve(UID, PERSISTENT_ALIAS, 0L) != null)
        assertTrue(CertificateAliasCache.isPassthroughDescriptor(UID, descriptor(PERSISTENT_ALIAS, PERSISTENT_NSPACE)))
    }

    @Test
    fun autoAttestKeyFailureDoesNotPromotePassthroughRegistry() {
        assertEquals(GenerateKeyRoute.ROUTE_PASSTHROUGH_REAL_TEE, autoRoute(hasAttestKeyPurpose = true))
        assertNull(PassthroughKeyRegistry.resolve(UID, PERSISTENT_ALIAS, 0L))
    }

    @Test
    fun autoDescriptorConsumerWithRealPassthroughAttestKeyUsesPassthrough() {
        PassthroughKeyRegistry.promote(UID, PERSISTENT_ALIAS, PERSISTENT_NSPACE)
        assertFalse(forceForge(needGenerate = false, attestationKeyDescriptorSet = true))
        assertEquals(GenerateKeyRoute.ROUTE_PASSTHROUGH_REAL_TEE, autoRoute(attestationKeyDescriptorSet = true))
    }

    @Test
    fun autoDeviceIdRemainsPassthroughWithAttestKeyPurpose() {
        assertFalse(forceForge(needGenerate = false, hasAttestKeyPurpose = true, hasDeviceIdAttestation = true))
        assertEquals(
            GenerateKeyRoute.ROUTE_PASSTHROUGH_REAL_TEE,
            autoRoute(hasAttestKeyPurpose = true, hasDeviceIdAttestation = true),
        )
        assertEquals(
            GenerateKeyRoute.ROUTE_PASSTHROUGH_REAL_TEE,
            autoRoute(hasDeviceIdAttestation = true, attestationKeyDescriptorSet = true),
        )
    }
}
